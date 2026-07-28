(ns jolt.http.tls
  "TLS transport over the system OpenSSL, bound through jolt.ffi. SSL runs against
  in-memory BIOs while ciphertext is shuttled over an opaque jolt.http.net byte
  transport, so no raw descriptor access into OpenSSL is needed and an
  in-process client + server can share one process.

  libssl/libcrypto are declared in deps.edn (:jolt/native) and loaded before this
  namespace, so the foreign-fn bindings resolve at load. A TLS stream is a host
  tagged-table carrying :write / :read / :close closures; jolt.http.platform
  dispatches socket ops through them."
  (:require [jolt.ffi :as ffi]
            [jolt.http.net :as net]))

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

(defn- ssl-ex
  ([msg] (ssl-ex msg nil))
  ([msg cause]
   (jolt.host/throwable "javax.net.ssl.SSLException" (str msg) cause)))

;; A NUL-terminated C-string pointer; the caller frees it.
(defn- cstr [s] (ffi/string->ptr (str s)))

(defn- bio-pending [bio] (c-BIO-ctrl bio BIO-PENDING 0 ffi/null))

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
                               err ")")))))))))

(defn- make-stream [sock ssl ctx rbio wbio]
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
    (jolt.host/ref-put! st :close
      (fn [& _]
        (try
          ;; SSL_shutdown may generate close_notify into the memory BIO. Clear
          ;; stale errors before it and flush the alert before closing TCP.
          (c-ERR-clear-error)
          (let [ret (c-SSL-shutdown ssl)
                _err (when (neg? ret) (c-SSL-get-error ssl ret))]
            (flush-out st))
          (catch Throwable _ nil))
        (try (net/close sock) (catch Throwable _ nil))
        (try (c-SSL-free ssl) (catch Throwable _ nil))
        (try (c-SSL-CTX-free ctx) (catch Throwable _ nil))
        nil))
    st))

(defn available?
  "True when libssl is actually callable in this process.

  jolt.ffi binds a defcfn when it is called, not when this namespace loads, so
  loading proves nothing about the shared object. TLS_client_method returns a
  static method table, allocates nothing and frees nothing, which makes it the
  cheapest honest question to ask. jolt.http.capability probes through here
  before reporting TLS as available, so an https request on a platform without
  OpenSSL is refused with a structured capability error instead of failing
  somewhere inside the handshake."
  []
  (try
    (not (ffi/null? (c-TLS-client-method)))
    (catch :default _ false)))

(defn tls-connect
  "Open a TLS client connection to host:port. insecure? disables peer
  verification (self-signed/expired certs accepted). Connect and read deadline
  options are passed unchanged to jolt.http.net."
  ([host port insecure?] (tls-connect host port insecure? {}))
  ([host port insecure? opts]
   (let [ctx (c-SSL-CTX-new (c-TLS-client-method))]
     (when (ffi/null? ctx) (throw (ssl-ex "SSL_CTX_new failed")))
     (if insecure?
       (c-SSL-CTX-set-verify ctx VERIFY-NONE ffi/null)
       (do (c-SSL-CTX-default-verify ctx)
           (c-SSL-CTX-set-verify ctx VERIFY-PEER ffi/null)))
     (let [ssl     (c-SSL-new ctx)
           memmeth (c-BIO-s-mem)
           rbio    (c-BIO-new memmeth)
           wbio    (c-BIO-new memmeth)
           host-buf (cstr host)
           stream (atom nil)]
       (c-SSL-set-bio ssl rbio wbio)
       (c-SSL-set-connect ssl)
       (c-SSL-ctrl ssl SET-TLSEXT-HOSTNAME NAMETYPE-host-name host-buf)  ; SNI
       (when-not insecure? (c-SSL-set1-host ssl host-buf))
       (try
         (let [sock (net/connect host port opts)
               st   (make-stream sock ssl ctx rbio wbio)]
           (reset! stream st)
           (handshake! st true (select-keys opts [:deadline-nanos]))
           st)
         (catch Throwable exception
           (if-let [st @stream]
             ((jolt.host/ref-get st :close))
             (do
               (try (c-SSL-free ssl) (catch Throwable _ nil))
               (try (c-SSL-CTX-free ctx) (catch Throwable _ nil))))
           (throw exception))
         (finally (ffi/free host-buf)))))))

(defn tls-wrap-server
  "Wrap an accepted opaque byte transport as the server side of a TLS session,
  using PEM `cert-file` and `key-file`. Returns a TLS stream."
  [sock cert-file key-file]
  (let [ctx (c-SSL-CTX-new (c-TLS-server-method))
        cf  (cstr cert-file)
        kf  (cstr key-file)]
    (try
      (when (zero? (c-SSL-CTX-use-cert ctx cf FILETYPE-PEM))
        (throw (ssl-ex (str "cannot load cert " cert-file))))
      (when (zero? (c-SSL-CTX-use-key ctx kf FILETYPE-PEM))
        (throw (ssl-ex (str "cannot load key " key-file))))
      (catch Throwable exception
        (try (c-SSL-CTX-free ctx) (catch Throwable _ nil))
        (throw exception))
      (finally (ffi/free cf) (ffi/free kf)))
    (let [ssl     (c-SSL-new ctx)
          memmeth (c-BIO-s-mem)
          rbio    (c-BIO-new memmeth)
          wbio    (c-BIO-new memmeth)]
      (c-SSL-set-bio ssl rbio wbio)
      (c-SSL-set-accept ssl)
      (let [st (make-stream sock ssl ctx rbio wbio)]
        (try
          (handshake! st false)
          st
          (catch Throwable exception
            ((jolt.host/ref-get st :close))
            (throw exception)))))))
