(ns jolt.http.schannel
  "Windows Schannel TLS client over the opaque jolt.http.net byte transport.

  All SSPI structures are caller-owned native memory described by the checked-in
  tools/probed/schannel-windows-*.edn evidence. No socket descriptor crosses
  this layer: ciphertext is received and sent through jolt.http.net, exactly as
  the POSIX OpenSSL memory-BIO provider does."
  (:require [jolt.ffi :as ffi]
            [jolt.http.net :as net]))

;; --- SSPI entry points ------------------------------------------------------

(ffi/defcfn c-acquire-credentials "AcquireCredentialsHandleW"
  [:pointer :pointer :uint32 :pointer :pointer :pointer :pointer
   :pointer :pointer]
  :int32)
(ffi/defcfn c-free-credentials "FreeCredentialsHandle"
  [:pointer] :int32)
(ffi/defcfn c-initialize-context "InitializeSecurityContextW"
  [:pointer :pointer :pointer :uint32 :uint32 :uint32 :pointer :uint32
   :pointer :pointer :pointer :pointer]
  :int32)
(ffi/defcfn c-delete-context "DeleteSecurityContext"
  [:pointer] :int32)
(ffi/defcfn c-complete-token "CompleteAuthToken"
  [:pointer :pointer] :int32)
(ffi/defcfn c-apply-control-token "ApplyControlToken"
  [:pointer :pointer] :int32)
(ffi/defcfn c-query-context "QueryContextAttributesW"
  [:pointer :uint32 :pointer] :int32)
(ffi/defcfn c-free-context-buffer "FreeContextBuffer"
  [:pointer] :int32)
(ffi/defcfn c-encrypt-message "EncryptMessage"
  [:pointer :uint32 :pointer :uint32] :int32)
(ffi/defcfn c-decrypt-message "DecryptMessage"
  [:pointer :pointer :uint32 :pointer] :int32)

;; --- Probed Windows ABI -----------------------------------------------------

(def ^:private sec-handle-size 16)
(def ^:private sec-buffer-size 16)
(def ^:private sec-buffer-desc-size 16)
(def ^:private stream-sizes-size 20)

(def ^:private buffer-length-offset 0)
(def ^:private buffer-type-offset 4)
(def ^:private buffer-pointer-offset 8)
(def ^:private desc-version-offset 0)
(def ^:private desc-count-offset 4)
(def ^:private desc-buffers-offset 8)

(def ^:private stream-header-offset 0)
(def ^:private stream-trailer-offset 4)
(def ^:private stream-maximum-message-offset 8)
(def ^:private stream-buffer-count-offset 12)
(def ^:private stream-block-size-offset 16)

(def ^:private credential-outbound 2)
(def ^:private native-data-representation 16)
(def ^:private attribute-stream-sizes 4)

(def ^:private buffer-empty 0)
(def ^:private buffer-data 1)
(def ^:private buffer-token 2)
(def ^:private buffer-extra 5)
(def ^:private buffer-stream-trailer 6)
(def ^:private buffer-stream-header 7)

(def ^:private request-sequence-detect 8)
(def ^:private request-replay-detect 4)
(def ^:private request-confidentiality 16)
(def ^:private request-allocate-memory 256)
(def ^:private request-extended-error 16384)
(def ^:private request-stream 32768)
(def ^:private request-manual-validation 524288)
(def ^:private shutdown-token 1)

(def ^:private status-ok 0)
(def ^:private status-continue-needed 590610)
(def ^:private status-complete-needed 590611)
(def ^:private status-complete-and-continue 590612)
(def ^:private status-context-expired 590615)
(def ^:private status-incomplete-credentials 590624)
(def ^:private status-incomplete-message -2146893032)
(def ^:private status-renegotiate 590625)

(def ^:private package-name "Microsoft Unified Security Protocol Provider")
(def ^:private empty-bytes (byte-array 0))

(defn- supported-target? []
  (let [{:keys [os arch pointer-bits]} (jolt.host/target)]
    (and (= :windows os)
         (contains? #{:x86-64 :aarch64} arch)
         (= 64 pointer-bits))))

;; --- Errors and byte helpers ------------------------------------------------

(defn- tls-ex
  ([message] (tls-ex message nil))
  ([message cause]
   (jolt.host/throwable "javax.net.ssl.SSLException" (str message) cause)))

(defn- check-status [operation status]
  (when-not (= status-ok status)
    (throw
      (tls-ex (str operation " failed with SECURITY_STATUS " status)))))

(defn- concat-bytes
  ([] empty-bytes)
  ([a] a)
  ([a b]
   (let [na (alength a)
         nb (alength b)
         out (byte-array (+ na nb))]
     (System/arraycopy a 0 out 0 na)
     (System/arraycopy b 0 out na nb)
     out))
  ([a b c]
   (concat-bytes (concat-bytes a b) c)))

(defn- tail-bytes [data length]
  (let [n (alength data)]
    (when (or (neg? length) (> length n))
      (throw
        (tls-ex
          (str "Schannel returned an invalid trailing-byte count "
               length " for " n " bytes"))))
    (if (zero? length)
      empty-bytes
      (let [out (byte-array length)]
        (System/arraycopy data (- n length) out 0 length)
        out))))

(defn- utf16-pointer [value]
  (let [encoded (byte-array (.getBytes (str value) "UTF-16LE"))
        length (alength encoded)
        pointer (ffi/alloc (+ length 2))]
    (try
      (ffi/write-array pointer encoded)
      (ffi/write pointer :uint16 length 0)
      pointer
      (catch Throwable exception
        (ffi/free pointer)
        (throw exception)))))

;; --- Native SecBuffer descriptors ------------------------------------------

(defn- buffer-offset [index]
  (* index sec-buffer-size))

(defn- write-buffer!
  [buffers index length type pointer]
  (let [base (buffer-offset index)]
    (ffi/write buffers :uint32 (+ base buffer-length-offset) length)
    (ffi/write buffers :uint32 (+ base buffer-type-offset) type)
    (ffi/write buffers :pointer (+ base buffer-pointer-offset) pointer)))

(defn- buffer-length [buffers index]
  (ffi/read buffers :uint32 (+ (buffer-offset index) buffer-length-offset)))

(defn- buffer-type [buffers index]
  (ffi/read buffers :uint32 (+ (buffer-offset index) buffer-type-offset)))

(defn- buffer-pointer [buffers index]
  (ffi/read buffers :pointer (+ (buffer-offset index) buffer-pointer-offset)))

(defn- allocate-descriptor [specs]
  (let [count (count specs)
        buffers (ffi/alloc (* sec-buffer-size count))]
    (try
      (let [descriptor (ffi/alloc sec-buffer-desc-size)]
        (try
          (doseq [[index {:keys [length type pointer]}]
                  (map-indexed vector specs)]
            (write-buffer! buffers index length type pointer))
          (ffi/write descriptor :uint32 desc-version-offset 0)
          (ffi/write descriptor :uint32 desc-count-offset count)
          (ffi/write descriptor :pointer desc-buffers-offset buffers)
          {:descriptor descriptor
           :buffers buffers
           :count count}
          (catch Throwable exception
            (ffi/free descriptor)
            (throw exception))))
      (catch Throwable exception
        (ffi/free buffers)
        (throw exception)))))

(defn- free-descriptor! [{:keys [descriptor buffers]}]
  (ffi/free descriptor)
  (ffi/free buffers))

(defn- find-buffer-index [buffers count wanted-type]
  (first
    (filter #(= wanted-type (buffer-type buffers %))
            (range count))))

(defn- output-token [native-descriptor]
  (let [{:keys [buffers]} native-descriptor
        length (buffer-length buffers 0)
        pointer (buffer-pointer buffers 0)]
    {:length length :pointer pointer}))

(defn- release-output-token! [native-descriptor]
  (let [{:keys [length pointer]} (output-token native-descriptor)]
    (when-not (ffi/null? pointer)
      (let [status (c-free-context-buffer pointer)
            buffers (:buffers native-descriptor)]
        ;; Publish retirement into the caller-owned descriptor before checking
        ;; the status, so an outer finally cannot free the same Schannel-owned
        ;; buffer a second time.
        (ffi/write buffers :pointer buffer-pointer-offset ffi/null)
        (ffi/write buffers :uint32 buffer-length-offset 0)
        (check-status "FreeContextBuffer" status)))
    length))

(defn- send-output-token!
  [transport native-descriptor opts]
  (let [{:keys [length pointer]} (output-token native-descriptor)]
    (try
      (when (pos? length)
        (when (ffi/null? pointer)
          (throw
            (tls-ex
              "Schannel returned a non-empty output token at a null pointer")))
        (net/send-bytes transport (ffi/read-array pointer length) opts))
      (finally
        (release-output-token! native-descriptor)))))

(defn- extra-length [native-descriptor]
  (let [{:keys [buffers count]} native-descriptor]
    (if-let [index (find-buffer-index buffers count buffer-extra)]
      (buffer-length buffers index)
      0)))

(defn- data-window [native-descriptor]
  (let [{:keys [buffers count]} native-descriptor]
    ;; Buffer 0 is the caller's encrypted SECBUFFER_DATA input. On terminal
    ;; statuses Schannel may leave its type unchanged; only slots 1..N are
    ;; eligible plaintext outputs.
    (when-let [index
               (first
                 (filter #(= buffer-data (buffer-type buffers %))
                         (range 1 count)))]
      {:length (buffer-length buffers index)
       :pointer (buffer-pointer buffers index)})))

;; --- Credential and handshake ownership ------------------------------------

(defn- request-flags [insecure?]
  (bit-or request-sequence-detect
          request-replay-detect
          request-confidentiality
          request-allocate-memory
          request-extended-error
          request-stream
          (if insecure? request-manual-validation 0)))

(defn- acquire-credentials! [credentials]
  (let [package-pointer (utf16-pointer package-name)]
    (try
      (check-status
        "AcquireCredentialsHandleW"
        (c-acquire-credentials
          ffi/null package-pointer credential-outbound
          ffi/null ffi/null ffi/null ffi/null credentials ffi/null))
      (finally
        (ffi/free package-pointer)))))

(defn- normalize-completion-status!
  [context native-output status]
  (cond
    (= status-complete-needed status)
    (do
      (check-status "CompleteAuthToken"
                    (c-complete-token context (:descriptor native-output)))
      status-ok)

    (= status-complete-and-continue status)
    (do
      (check-status "CompleteAuthToken"
                    (c-complete-token context (:descriptor native-output)))
      status-continue-needed)

    :else status))

(defn- initialize-step!
  [state target-pointer input first? opts]
  (let [input-length (if input (alength input) 0)
        input-pointer (when (pos? input-length) (ffi/alloc input-length))
        native-input
        (when input-pointer
          (try
            (ffi/write-array input-pointer input)
            (allocate-descriptor
              [{:length input-length
                :type buffer-token
                :pointer input-pointer}
               {:length 0 :type buffer-empty :pointer ffi/null}])
            (catch Throwable exception
              (ffi/free input-pointer)
              (throw exception))))]
    (try
      (let [native-output
            (allocate-descriptor
              [{:length 0 :type buffer-token :pointer ffi/null}])
            context (:context state)
            attributes (:attributes state)]
        (try
          (let [status
                (c-initialize-context
                  (:credentials state)
                  (if first? ffi/null context)
                  target-pointer
                  (:request-flags state)
                  0
                  native-data-representation
                  (if native-input (:descriptor native-input) ffi/null)
                  0
                  context
                  (:descriptor native-output)
                  attributes
                  ffi/null)
                _ (when (not (neg? status))
                    (reset! (:context-live? state) true))
                status (normalize-completion-status!
                         context native-output status)
                extra
                (if (and native-input
                         (not= status-incomplete-message status))
                  (tail-bytes input (extra-length native-input))
                  empty-bytes)]
            ;; Extended-error alerts are output tokens too. Send before
            ;; classifying the returned status so a peer is not left waiting on
            ;; an alert that this process owns.
            (send-output-token! (:transport state) native-output opts)
            {:status status
             :extra extra})
          (finally
            ;; send-output-token! normally releases this. If the native call
            ;; itself throws, release any allocated output defensively before
            ;; the descriptor storage disappears.
            (let [{:keys [pointer]} (output-token native-output)]
              (when-not (ffi/null? pointer)
                (try
                  (c-free-context-buffer pointer)
                  (catch Throwable _ nil))))
            (free-descriptor! native-output))))
      (finally
        (when native-input (free-descriptor! native-input))
        (when input-pointer (ffi/free input-pointer))))))

(defn- receive-required! [state pending opts phase]
  (if-let [received (net/recv-bytes (:transport state) opts)]
    (if (pos? (alength received))
      (concat-bytes pending received)
      (throw
        (tls-ex (str "connection closed during Schannel " phase))))
    (throw
      (tls-ex (str "connection closed during Schannel " phase)))))

(defn- query-stream-sizes! [state]
  (let [pointer (ffi/alloc stream-sizes-size)]
    (try
      (check-status
        "QueryContextAttributesW(SECPKG_ATTR_STREAM_SIZES)"
        (c-query-context (:context state) attribute-stream-sizes pointer))
      (let [result
            {:header (ffi/read pointer :uint32 stream-header-offset)
             :trailer (ffi/read pointer :uint32 stream-trailer-offset)
             :maximum-message
             (ffi/read pointer :uint32 stream-maximum-message-offset)
             :buffers
             (ffi/read pointer :uint32 stream-buffer-count-offset)
             :block-size
             (ffi/read pointer :uint32 stream-block-size-offset)}]
        (when (or (zero? (:maximum-message result))
                  (< (:buffers result) 4))
          (throw
            (tls-ex (str "Schannel returned unusable stream sizes " result))))
        result)
      (finally
        (ffi/free pointer)))))

(defn- handshake!
  ([state target-pointer opts]
   (handshake! state target-pointer nil true opts))
  ([state target-pointer initial-input first? opts]
   (loop [input initial-input
          first? first?]
     (let [input
           ;; `empty-bytes` is meaningful on the first step after
           ;; SEC_I_RENEGOTIATE: Schannel must see one empty-input
           ;; InitializeSecurityContext call before we wait for another token.
           ;; `nil` alone means the previous step requested another read.
           (if (or first? (some? input))
             input
             (receive-required! state empty-bytes opts "handshake"))
           {:keys [status extra]}
           (initialize-step! state target-pointer input first? opts)]
       (cond
         (= status-ok status)
         (do
           (jolt.host/ref-put! (:stream state) :pending extra)
           (jolt.host/ref-put! (:stream state) :sizes
                               (query-stream-sizes! state))
           true)

         (= status-continue-needed status)
         (recur (if (pos? (alength extra)) extra nil) false)

         (= status-incomplete-message status)
         (recur
           (receive-required! state (or input empty-bytes)
                              opts "handshake")
           false)

         (= status-incomplete-credentials status)
         (throw
           (tls-ex
             "Schannel server requested client credentials, which this client does not provide"))

         :else
         (throw
           (tls-ex
             (str "Schannel handshake failed with SECURITY_STATUS "
                  status))))))))

;; --- Record encryption and decryption --------------------------------------

(defn- encrypt-chunk! [state data offset length opts]
  (let [{:keys [header trailer]} (jolt.host/ref-get (:stream state) :sizes)
        capacity (+ header length trailer)
        native (ffi/alloc (max 1 capacity))]
    (try
      (let [native-descriptor
            (allocate-descriptor
              [{:length header
                :type buffer-stream-header
                :pointer native}
               {:length length
                :type buffer-data
                :pointer (+ native header)}
               {:length trailer
                :type buffer-stream-trailer
                :pointer (+ native header length)}
               {:length 0
                :type buffer-empty
                :pointer ffi/null}])]
        (try
          (ffi/write-array (+ native header) data offset length)
          (check-status
            "EncryptMessage"
            (c-encrypt-message
              (:context state) 0 (:descriptor native-descriptor) 0))
          (let [{:keys [buffers]} native-descriptor
                header-bytes
                (ffi/read-array
                  (buffer-pointer buffers 0)
                  (buffer-length buffers 0))
                data-bytes
                (ffi/read-array
                  (buffer-pointer buffers 1)
                  (buffer-length buffers 1))
                trailer-bytes
                (ffi/read-array
                  (buffer-pointer buffers 2)
                  (buffer-length buffers 2))]
            (net/send-bytes
              (:transport state)
              (concat-bytes header-bytes data-bytes trailer-bytes)
              opts))
          (finally
            (free-descriptor! native-descriptor))))
      (finally
        (ffi/free native)))))

(defn- write-plaintext! [state data opts]
  (when @(:closed? state)
    (throw (tls-ex "write on a closed Schannel stream")))
  (let [length (alength data)
        maximum (:maximum-message
                 (jolt.host/ref-get (:stream state) :sizes))]
    (loop [offset 0]
      (when (< offset length)
        (let [chunk-length (min maximum (- length offset))]
          (encrypt-chunk! state data offset chunk-length opts)
          (recur (+ offset chunk-length))))))
  (:stream state))

(defn- decrypt-once! [state encrypted]
  (let [length (alength encrypted)
        native (ffi/alloc (max 1 length))]
    (try
      (let [native-descriptor
            (allocate-descriptor
              [{:length length :type buffer-data :pointer native}
               {:length 0 :type buffer-empty :pointer ffi/null}
               {:length 0 :type buffer-empty :pointer ffi/null}
               {:length 0 :type buffer-empty :pointer ffi/null}])]
        (try
          (ffi/write-array native encrypted)
          (let [status
                (c-decrypt-message
                  (:context state) (:descriptor native-descriptor) 0 ffi/null)
                window (data-window native-descriptor)
                plaintext
                (if (and window (pos? (:length window)))
                  (do
                    (when (ffi/null? (:pointer window))
                      (throw
                        (tls-ex
                          "Schannel returned plaintext at a null pointer")))
                    (ffi/read-array (:pointer window) (:length window)))
                  empty-bytes)
                extra
                (if (= status-incomplete-message status)
                  empty-bytes
                  (tail-bytes encrypted (extra-length native-descriptor)))]
            {:status status
             :plaintext plaintext
             :extra extra})
          (finally
            (free-descriptor! native-descriptor))))
      (finally
        (ffi/free native)))))

(defn- read-plaintext! [state opts]
  (if (jolt.host/ref-get (:stream state) :eof)
    nil
    (loop [pending (jolt.host/ref-get (:stream state) :pending)]
      (let [pending
            (if (pos? (alength pending))
              pending
              (if-let [received (net/recv-bytes (:transport state) opts)]
                received
                (throw
                  (tls-ex
                    "transport closed without a Schannel close_notify"))))
            {:keys [status plaintext extra]}
            (decrypt-once! state pending)]
        (cond
          (= status-ok status)
          (do
            (jolt.host/ref-put! (:stream state) :pending extra)
            (if (pos? (alength plaintext))
              plaintext
              (recur extra)))

          (= status-incomplete-message status)
          (recur
            (if-let [received (net/recv-bytes (:transport state) opts)]
              (concat-bytes pending received)
              (throw
                (tls-ex
                  "transport closed in the middle of a Schannel record"))))

          (= status-context-expired status)
          (do
            (jolt.host/ref-put! (:stream state) :pending empty-bytes)
            (jolt.host/ref-put! (:stream state) :eof true)
            ;; Schannel can publish final application bytes alongside the
            ;; authenticated close_notify. Deliver them once; the next read
            ;; observes :eof and returns nil.
            (when (pos? (alength plaintext)) plaintext))

          (= status-renegotiate status)
          (let [target (utf16-pointer (:host state))]
            (try
              (handshake! state target extra false opts)
              (finally
                (ffi/free target)))
            (if (pos? (alength plaintext))
              plaintext
              (recur (jolt.host/ref-get (:stream state) :pending))))

          :else
          (throw
            (tls-ex
              (str "DecryptMessage failed with SECURITY_STATUS "
                   status))))))))

;; --- Shutdown and public provider ------------------------------------------

(defn- send-close-notify! [state]
  (when @(:context-live? state)
    (let [control (ffi/alloc 4)]
      (try
        (let [target (utf16-pointer (:host state))]
          (try
            (let [native-control
                  (allocate-descriptor
                    [{:length 4 :type buffer-token :pointer control}])]
              (try
                (let [native-output
                      (allocate-descriptor
                        [{:length 0
                          :type buffer-token
                          :pointer ffi/null}])]
                  (try
                    (ffi/write control :uint32 0 shutdown-token)
                    (check-status
                      "ApplyControlToken(SCHANNEL_SHUTDOWN)"
                      (c-apply-control-token
                        (:context state) (:descriptor native-control)))
                    (let [status
                          (c-initialize-context
                            (:credentials state)
                            (:context state)
                            target
                            (:request-flags state)
                            0
                            native-data-representation
                            ffi/null
                            0
                            (:context state)
                            (:descriptor native-output)
                            (:attributes state)
                            ffi/null)
                          status
                          (normalize-completion-status!
                            (:context state) native-output status)]
                      (when-not
                        (contains? #{status-ok status-continue-needed} status)
                        (throw
                          (tls-ex
                            (str
                              "Schannel shutdown failed with SECURITY_STATUS "
                              status))))
                      (send-output-token!
                        (:transport state) native-output {}))
                    (finally
                      (let [{:keys [pointer]} (output-token native-output)]
                        (when-not (ffi/null? pointer)
                          (try
                            (c-free-context-buffer pointer)
                            (catch Throwable _ nil))))
                      (free-descriptor! native-output))))
                (finally
                  (free-descriptor! native-control))))
            (finally
              (ffi/free target))))
        (finally
          (ffi/free control))))))

(defn- close-state! [state]
  (when (compare-and-set! (:closed? state) false true)
    (try
      (send-close-notify! state)
      (catch Throwable _ nil))
    (try
      (net/close (:transport state))
      (catch Throwable _ nil))
    (when @(:context-live? state)
      (try
        (c-delete-context (:context state))
        (catch Throwable _ nil)))
    (when @(:credentials-live? state)
      (try
        (c-free-credentials (:credentials state))
        (catch Throwable _ nil)))
    (ffi/free (:attributes state))
    (ffi/free (:context state))
    (ffi/free (:credentials state)))
  nil)

(defn- make-state [transport host insecure?]
  (let [credentials (ffi/alloc sec-handle-size)]
    (try
      (let [context (ffi/alloc sec-handle-size)]
        (try
          (let [attributes (ffi/alloc 4)]
            (try
              (let [stream (jolt.host/tagged-table :jolt/schannel-stream)
                    state
                    {:stream stream
                     :transport transport
                     :host (str host)
                     :credentials credentials
                     :context context
                     :attributes attributes
                     :request-flags (request-flags insecure?)
                     :credentials-live? (atom false)
                     :context-live? (atom false)
                     :closed? (atom false)}]
                (jolt.host/ref-put! stream :pending empty-bytes)
                (jolt.host/ref-put! stream :sizes nil)
                (jolt.host/ref-put! stream :eof false)
                (jolt.host/ref-put!
                  stream :write
                  (fn [_ data opts]
                    (write-plaintext! state data (or opts {}))))
                (jolt.host/ref-put!
                  stream :read
                  (fn [_ opts]
                    (read-plaintext! state (or opts {}))))
                (jolt.host/ref-put!
                  stream :close
                  (fn [& _] (close-state! state)))
                state)
              (catch Throwable exception
                (ffi/free attributes)
                (throw exception))))
          (catch Throwable exception
            (ffi/free context)
            (throw exception))))
      (catch Throwable exception
        (ffi/free credentials)
        (throw exception)))))

(defn available?
  "True when this is a supported 64-bit Windows target and default outbound
  Schannel credentials can be acquired and retired in-process."
  []
  (and
    (supported-target?)
    (try
      (ffi/load-library "Secur32.dll")
      (let [credentials (ffi/alloc sec-handle-size)]
        (try
          (acquire-credentials! credentials)
          (check-status "FreeCredentialsHandle"
                        (c-free-credentials credentials))
          true
          (finally
            (ffi/free credentials))))
      (catch Throwable _ false))))

(defn tls-connect
  "Open a Windows Schannel TLS client connection.

  `insecure?` is the only path that requests manual credential validation.
  Connect/read/deadline options pass unchanged to jolt.http.net."
  ([host port insecure?]
   (tls-connect host port insecure? {}))
  ([host port insecure? opts]
   (when-not (supported-target?)
     (throw
       (tls-ex
         (str "Schannel is unsupported on target " (jolt.host/target)))))
   (ffi/load-library "Secur32.dll")
   (let [transport (net/connect (str host) port opts)
         state-holder (atom nil)]
     (try
       (let [state (make-state transport host insecure?)]
         (reset! state-holder state)
         (let [target (utf16-pointer host)]
           (try
             (acquire-credentials! (:credentials state))
             (reset! (:credentials-live? state) true)
             (handshake!
               state
               target
               (select-keys opts [:deadline-nanos]))
             (:stream state)
             (finally
               (ffi/free target)))))
       (catch Throwable exception
         (if-let [state @state-holder]
           (close-state! state)
           (try
             (net/close transport)
             (catch Throwable _ nil)))
         (throw exception))))))
