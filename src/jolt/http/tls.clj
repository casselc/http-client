(ns jolt.http.tls
  "TLS transport over the system OpenSSL, bound through jolt.ffi. SSL runs against
  in-memory BIOs while ciphertext is shuttled over an opaque jolt.http.net byte
  transport, so no raw descriptor access into OpenSSL is needed and an
  in-process client + server can share one process.

  libssl/libcrypto are declared in deps.edn (:jolt/native), but this namespace
  re-loads them itself immediately below — see ensure-openssl!. A TLS stream is a
  host tagged-table carrying :write / :read / :close closures; jolt.http.platform
  dispatches socket ops through them."
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]
            [jolt.http.net :as net]))

;; --- pinning the right OpenSSL ---------------------------------------------
;; Chez resolves a foreign symbol against loaded shared objects most-recent-first,
;; and `defcfn` resolves when the def is evaluated. So whichever object was loaded
;; last before this namespace loads wins the SSL_* symbols.
;;
;; That is a live hazard on macOS, where the process image transitively links
;; /usr/lib/libssl.dylib — LibreSSL, not OpenSSL. Any library that calls
;; `(ffi/load-library)` with no argument, which loads the running process's own
;; symbols, therefore puts LibreSSL ahead of the OpenSSL that :jolt/native
;; loaded. jolt.nrepl does exactly that at ns load to bind sockets. The result is
;; not a missing symbol but a SILENT MIX: TLS_client_method comes from one
;; implementation and SSL_CTX_new from the other, whose SSL_CTX layouts disagree,
;; and the first call through it faults with "invalid memory reference".
;;
;; Loading the libraries again here re-asserts them as most-recent at exactly the
;; point the bindings below resolve, which makes this namespace independent of
;; who loaded what first. load-shared-object on an already-loaded object is cheap
;; and does not duplicate it. The candidate lists mirror jolt-lang/jolt-crypto's
;; :jolt/native, homebrew before /usr/lib, so the OpenSSL build is preferred and
;; the system LibreSSL is only a last resort.
(def ^:private openssl-candidates
  (if (str/includes? (str/lower-case (or (System/getProperty "os.name") "")) "mac")
    {:crypto ["/opt/homebrew/opt/openssl@3/lib/libcrypto.dylib"
              "/usr/local/opt/openssl@3/lib/libcrypto.dylib"
              "libcrypto.dylib" "/usr/lib/libcrypto.dylib"]
     :ssl    ["/opt/homebrew/opt/openssl@3/lib/libssl.dylib"
              "/usr/local/opt/openssl@3/lib/libssl.dylib"
              "libssl.dylib" "/usr/lib/libssl.dylib"]}
    {:crypto ["libcrypto.so.3" "libcrypto.so.1.1" "libcrypto.so"]
     :ssl    ["libssl.so.3" "libssl.so.1.1" "libssl.so"]}))

(defn- load-first! [candidates]
  (some (fn [path]
          (when (try (ffi/load-library path) true (catch Throwable _ false))
            path))
        candidates))

;; crypto first, then ssl: ssl ends up most-recent and wins SSL_*, while symbols
;; it does not define (EVP_*, ERR_*) fall through to crypto, still ahead of the
;; process. Returns the pair actually loaded, which the AOT/load-order tests read.
(defn ensure-openssl! []
  {:crypto (load-first! (:crypto openssl-candidates))
   :ssl    (load-first! (:ssl openssl-candidates))})

(def loaded-openssl (ensure-openssl!))

;; SSL_get_error codes / verify modes / ctrl commands.
(def ^:private SSL-ERROR-SSL 1)
(def ^:private WANT-READ 2)
(def ^:private WANT-WRITE 3)
(def ^:private SSL-ERROR-SYSCALL 5)
(def ^:private ZERO-RETURN 6)
(def ^:private VERIFY-NONE 0)
(def ^:private VERIFY-PEER 1)
(def ^:private BIO-PENDING 10)
(def ^:private SET-TLSEXT-HOSTNAME 55)
(def ^:private NAMETYPE-host-name 0)
(def ^:private FILETYPE-PEM 1)
(def ^:private chunk 16384)

(ffi/defcfn c-TLS-client-method "TLS_client_method" [] :pointer)
(ffi/defcfn c-TLS-server-method "TLS_server_method" [] :pointer)
(ffi/defcfn c-SSL-CTX-new       "SSL_CTX_new"       [:pointer] :pointer)
(ffi/defcfn c-SSL-CTX-free      "SSL_CTX_free"      [:pointer] :void)
(ffi/defcfn c-SSL-CTX-set-verify "SSL_CTX_set_verify" [:pointer :int :pointer] :void)
(ffi/defcfn c-SSL-CTX-default-verify "SSL_CTX_set_default_verify_paths" [:pointer] :int)
(ffi/defcfn c-SSL-CTX-use-cert  "SSL_CTX_use_certificate_file" [:pointer :pointer :int] :int)
(ffi/defcfn c-SSL-CTX-use-key   "SSL_CTX_use_PrivateKey_file"  [:pointer :pointer :int] :int)
(ffi/defcfn c-SSL-new           "SSL_new"           [:pointer] :pointer)
(ffi/defcfn c-SSL-free          "SSL_free"          [:pointer] :void)
(ffi/defcfn c-SSL-set-bio       "SSL_set_bio"       [:pointer :pointer :pointer] :void)
(ffi/defcfn c-SSL-set-connect   "SSL_set_connect_state" [:pointer] :void)
(ffi/defcfn c-SSL-set-accept    "SSL_set_accept_state"  [:pointer] :void)
(ffi/defcfn c-SSL-connect       "SSL_connect"       [:pointer] :int)
(ffi/defcfn c-SSL-accept        "SSL_accept"        [:pointer] :int)
(ffi/defcfn c-SSL-read          "SSL_read"          [:pointer :pointer :int] :int)
(ffi/defcfn c-SSL-write         "SSL_write"         [:pointer :pointer :int] :int)
(ffi/defcfn c-SSL-get-error     "SSL_get_error"     [:pointer :int] :int)
(ffi/defcfn c-SSL-ctrl          "SSL_ctrl"          [:pointer :int :int64 :pointer] :int64)
(ffi/defcfn c-SSL-shutdown      "SSL_shutdown"      [:pointer] :int)
(ffi/defcfn c-SSL-set1-host     "SSL_set1_host"     [:pointer :pointer] :int)
(ffi/defcfn c-ERR-clear-error   "ERR_clear_error"   [] :void)
(ffi/defcfn c-BIO-new           "BIO_new"           [:pointer] :pointer)
(ffi/defcfn c-BIO-s-mem         "BIO_s_mem"         [] :pointer)
(ffi/defcfn c-BIO-read          "BIO_read"          [:pointer :pointer :int] :int)
(ffi/defcfn c-BIO-write         "BIO_write"         [:pointer :pointer :int] :int)
(ffi/defcfn c-BIO-ctrl          "BIO_ctrl"          [:pointer :int :int64 :pointer] :int64)
(ffi/defcfn c-BIO-free          "BIO_free"          [:pointer] :int)

(defn- ssl-ex
  "Build a typed SSLException with a real message and optional cause."
  ([msg] (ssl-ex msg nil))
  ([msg cause]
   (jolt.host/throwable "javax.net.ssl.SSLException" (str msg) cause)))

(defn- bio-pending [bio] (c-BIO-ctrl bio BIO-PENDING 0 ffi/null))

(defn- once-action
  "Return a nullary action whose body is claimed atomically by at most one
  caller. Cleanup exceptions belong to body; they never reopen the claim."
  [body]
  (let [claimed? (atom false)]
    (fn []
      (when (compare-and-set! claimed? false true)
        (body)))))

(defn- release-engine-action [ssl ctx]
  (once-action
    (fn []
      ;; SSL_free owns both BIOs after SSL_set_bio returns. Keep the context
      ;; release independent so an unexpected cleanup exception cannot leak it.
      (try (c-SSL-free ssl) (catch Throwable _ nil))
      (try (c-SSL-CTX-free ctx) (catch Throwable _ nil))
      nil)))

(defn- require-pointer [pointer operation]
  (when (ffi/null? pointer)
    (throw (ssl-ex (str operation " failed"))))
  pointer)

(defn- acquire-engine!
  "Acquire an SSL context, SSL object, and two memory BIOs. Before
  SSL_set_bio returns, each BIO is owned here and must be freed directly. After
  it returns, ownership has transferred to SSL and SSL_free is the sole BIO
  destructor. Returned release-engine! is safe under repeated/concurrent use."
  [method configure-context! configure-ssl!]
  (let [ctx (require-pointer (c-SSL-CTX-new method) "SSL_CTX_new")
        ssl* (atom nil)
        owned-bios (atom [])
        bios-transferred? (atom false)]
    (try
      (configure-context! ctx)
      (let [ssl (require-pointer (c-SSL-new ctx) "SSL_new")]
        (reset! ssl* ssl)
        (let [mem-method (require-pointer (c-BIO-s-mem) "BIO_s_mem")
              rbio (require-pointer (c-BIO-new mem-method) "BIO_new")]
          (swap! owned-bios conj rbio)
          (let [wbio (require-pointer (c-BIO-new mem-method) "BIO_new")]
            (swap! owned-bios conj wbio)
            ;; This call is the ownership linearization point. There must be no
            ;; fallible operation between it and recording the transfer.
            (c-SSL-set-bio ssl rbio wbio)
            (reset! bios-transferred? true)
            (configure-ssl! ssl)
            {:ctx ctx
             :ssl ssl
             :rbio rbio
             :wbio wbio
             :release-engine! (release-engine-action ssl ctx)})))
      (catch Throwable exception
        (when-not @bios-transferred?
          (doseq [bio (reverse @owned-bios)]
            (try (c-BIO-free bio) (catch Throwable _ nil))))
        (when-let [ssl @ssl*]
          (try (c-SSL-free ssl) (catch Throwable _ nil)))
        (try (c-SSL-CTX-free ctx) (catch Throwable _ nil))
        (throw exception)))))

;; SSL_get_error is only reliable for the immediately preceding SSL operation
;; on this thread, with an empty error queue before that operation. Keep this
;; pair structural: no BIO, flush, or other OpenSSL call may appear between the
;; operation and c-SSL-get-error.
(defn- ssl-io-call [ssl operation]
  (c-ERR-clear-error)
  (let [ret (operation)]
    [ret (when-not (pos? ret) (c-SSL-get-error ssl ret))]))

(defn- ssl-handshake-call [ssl operation]
  (c-ERR-clear-error)
  (let [ret (operation)]
    [ret (when-not (= ret 1) (c-SSL-get-error ssl ret))]))

(defn- transport-failure? [exception]
  (let [data (ex-data exception)]
    (or (str/starts-with? (str (class exception)) "class java.net.")
        (some? (:jolt.net/kind data))
        (some? (:teensyp.client/kind data)))))

(defn- read-action [got error]
  (cond
    (pos? got) :data
    (= error ZERO-RETURN) :eof
    (= error WANT-READ) :want-read
    (= error WANT-WRITE) :want-write
    :else :fatal))

;; Drain ciphertext OpenSSL produced into wbio out to the byte transport.
(defn- flush-out
  ([st] (flush-out st {}))
  ([st opts]
   (let [wbio (jolt.host/ref-get st :wbio)
         sock (jolt.host/ref-get st :sock)]
     (loop []
       (let [p (bio-pending wbio)]
         (when (pos? p)
           (let [buf (ffi/alloc p)
                 n   (c-BIO-read wbio buf p)]
             (try
               (if (pos? n)
                 (net/send-bytes sock (ffi/read-array buf n) opts)
                 (throw (ssl-ex "TLS memory BIO reported pending bytes but could not read them")))
               (finally (ffi/free buf)))
             (recur))))))))

;; Pull one ciphertext chunk off the socket into rbio; false at EOF.
(defn- feed-in
  ([st] (feed-in st {}))
  ([st opts]
   (let [data (net/recv-bytes (jolt.host/ref-get st :sock) opts)]
     (if (and data (pos? (alength data)))
       (let [n (alength data) buf (ffi/alloc n)]
         (try
           (ffi/write-array buf data)
           (let [written (c-BIO-write (jolt.host/ref-get st :rbio) buf n)]
             (when-not (= n written)
               (throw (ssl-ex
                        (str "TLS memory BIO accepted " written " of " n
                             " ciphertext bytes"))))
             true)
           (finally (ffi/free buf))))
       false))))

(defn- handshake!
  ([st connect?] (handshake! st connect? {}))
  ([st connect? opts]
   (try
     (loop []
       (let [ssl (jolt.host/ref-get st :ssl)
             [ret err]
             (ssl-handshake-call
               ssl
               #(if connect? (c-SSL-connect ssl) (c-SSL-accept ssl)))]
         ;; SSL_get_error has already captured the operation's classification.
         (flush-out st opts)
         (when-not (= ret 1)
           (cond
             (= err WANT-READ) (do (when-not (feed-in st opts)
                                     (throw (ssl-ex "connection closed during TLS handshake")))
                                   (recur))
             (= err WANT-WRITE) (recur)
             :else (throw (ssl-ex
                            (str "TLS handshake failed (SSL_get_error="
                                 err ")")))))))
     (catch Throwable exception
       (if (transport-failure? exception)
         (throw (ssl-ex (str (ex-message exception) " during TLS handshake")
                        exception))
         (throw exception))))))

(defn- make-stream
  ([sock ssl ctx rbio wbio]
   (make-stream sock ssl ctx rbio wbio (release-engine-action ssl ctx)))
  ([sock ssl ctx rbio wbio release-engine!]
   (let [st (jolt.host/tagged-table :jolt/tls-stream)]
    (jolt.host/ref-put! st :sock sock) (jolt.host/ref-put! st :ssl ssl)
    (jolt.host/ref-put! st :ctx ctx) (jolt.host/ref-put! st :rbio rbio)
    (jolt.host/ref-put! st :wbio wbio) (jolt.host/ref-put! st :eof false)
    (jolt.host/ref-put! st :write
      (fn [self data opts]
        (let [opts (or opts {})
              n (alength data)
              buf (ffi/alloc (max 1 n))]
          (try
            (ffi/write-array buf data)
            (loop [off 0]
              (when (< off n)
                (let [ssl (jolt.host/ref-get self :ssl)
                      [wrote err]
                      (ssl-io-call
                        ssl
                        #(c-SSL-write ssl (+ buf off) (- n off)))]
                  ;; Capture SSL_get_error before draining the write BIO.
                  (flush-out self opts)
                  (cond
                    (pos? wrote)
                    (recur (+ off wrote))

                    (= err WANT-READ)
                    (if (feed-in self opts)
                      (recur off)
                      (throw (ssl-ex
                               "connection closed while TLS write needed input")))

                    (= err WANT-WRITE)
                    (recur off)

                    :else
                    (throw (ssl-ex
                             (str "TLS write failed (SSL_get_error="
                                  err ")")))))))
            (finally (ffi/free buf)))
          self)))
    (jolt.host/ref-put! st :read
      ;; return a decrypted byte-array chunk, or nil at EOF.
      (fn [self opts]
        (when-not (jolt.host/ref-get self :eof)
          (let [opts (or opts {})
                tmp (ffi/alloc chunk)]
            (try
              (loop []
                (let [ssl (jolt.host/ref-get self :ssl)
                      [got err] (ssl-io-call ssl #(c-SSL-read ssl tmp chunk))
                      action (read-action got err)]
                  ;; Capture SSL_get_error before any BIO or transport call.
                  (case action
                    :data
                    (do
                      (flush-out self opts)
                      (ffi/read-array tmp got))

                    :eof
                    (do
                      (flush-out self opts)
                      (jolt.host/ref-put! self :eof true)
                      nil)

                    :want-read
                    (do
                      (flush-out self opts)
                      (if (feed-in self opts)
                        (recur)
                        ;; Raw transport EOF without SSL_ERROR_ZERO_RETURN is
                        ;; truncation, not clean TLS EOF.
                        (throw (ssl-ex
                                 "transport closed without TLS close_notify"))))

                    :want-write
                    (do (flush-out self opts) (recur))

                    :fatal
                    (throw (ssl-ex
                             (str "TLS read failed (SSL_get_error="
                                  err ")"))))))
              (finally (ffi/free tmp)))))))
    (let [close! (once-action
                   (fn []
                     (try
                       ;; SSL_shutdown may generate close_notify into the memory
                       ;; BIO. Clear stale errors before it and flush the alert
                       ;; before closing TCP.
                       (c-ERR-clear-error)
                       (let [ret (c-SSL-shutdown ssl)
                             _err (when (neg? ret)
                                    (c-SSL-get-error ssl ret))]
                         (flush-out st))
                       (catch Throwable _ nil))
                     (try (net/close sock) (catch Throwable _ nil))
                     (release-engine!)
                     nil))]
      (jolt.host/ref-put! st :close (fn [& _] (close!))))
     st)))

(defn tls-connect
  "Open a TLS client connection to host:port. insecure? disables peer
  verification (self-signed/expired certs accepted). Connect and read deadline
  options are passed unchanged to jolt.http.net."
  ([host port insecure?] (tls-connect host port insecure? {}))
  ([host port insecure? opts]
   (ffi/with-c-string [host-buf (str host)]
     (let [{:keys [ssl ctx rbio wbio release-engine!]}
           (acquire-engine!
             (c-TLS-client-method)
             (fn [ctx]
               (if insecure?
                 (c-SSL-CTX-set-verify ctx VERIFY-NONE ffi/null)
                 (do (c-SSL-CTX-default-verify ctx)
                     (c-SSL-CTX-set-verify ctx VERIFY-PEER ffi/null))))
             (fn [ssl]
               (c-SSL-set-connect ssl)
               ;; SNI
               (c-SSL-ctrl ssl SET-TLSEXT-HOSTNAME NAMETYPE-host-name host-buf)
               (when-not insecure? (c-SSL-set1-host ssl host-buf))))
           stream (atom nil)]
       (try
         (let [sock (net/connect host port opts)
               st   (make-stream sock ssl ctx rbio wbio release-engine!)]
           (reset! stream st)
           (handshake! st true (select-keys opts [:deadline-nanos]))
           st)
         (catch Throwable exception
           (if-let [st @stream]
             ((jolt.host/ref-get st :close))
             (release-engine!))
           (throw exception)))))))

(defn tls-wrap-server
  "Wrap an accepted opaque byte transport as the server side of a TLS session,
  using PEM `cert-file` and `key-file`. Returns a TLS stream."
  [sock cert-file key-file]
  (ffi/with-c-string [cf (str cert-file)]
    (ffi/with-c-string [kf (str key-file)]
      (let [{:keys [ssl ctx rbio wbio release-engine!]}
            (acquire-engine!
              (c-TLS-server-method)
              (fn [ctx]
                (when (zero? (c-SSL-CTX-use-cert ctx cf FILETYPE-PEM))
                  (throw (ssl-ex (str "cannot load cert " cert-file))))
                (when (zero? (c-SSL-CTX-use-key ctx kf FILETYPE-PEM))
                  (throw (ssl-ex (str "cannot load key " key-file)))))
              (fn [ssl] (c-SSL-set-accept ssl)))
            st (make-stream sock ssl ctx rbio wbio release-engine!)]
        (try
          (handshake! st false)
          st
          (catch Throwable exception
            ((jolt.host/ref-get st :close))
            (throw exception)))))))
