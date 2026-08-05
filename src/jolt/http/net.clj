(ns jolt.http.net
  "Blocking byte transport for HTTP, implemented over the opaque
  `teensyp.client` outbound TCP capability.

  This namespace owns the small compatibility boundary required by
  HttpURLConnection: configured connect/read deadlines become monotonic
  jolt-tcp deadlines; name resolution, connect refusal, and deadline expiry are
  mapped to their Java boundary classes with the structured transport error as
  the cause. Native reset/close/unknown failures otherwise pass through without
  being collapsed into timeouts.

  The underlying resolver is currently synchronous. A result observed after a
  connect deadline is rejected, but an in-flight getaddrinfo cannot be
  preempted.

  No native descriptor, socket handle, sockaddr, or libc binding crosses this
  layer. TLS consumes the same send/receive/close surface over memory BIOs."
  (:require [teensyp.client :as client]))

(def ^:private bufsize 65536)
(def ^:private nanos-per-ms 1000000)
(def ^:private transport-marker ::transport)
(def ^:private send-fn-key ::send-fn)
(def ^:private receive-fn-key ::receive-fn)
(def ^:private close-fn-key ::close-fn)
(def ^:private read-timeout-key ::read-timeout-ms)

(defn- typed-ex [class message cause]
  (jolt.host/throwable class (str message) cause))

(defn- monotonic-now []
  (jolt.host/mono-nanos))

(defn- timeout-error? [exception]
  (let [data (ex-data exception)]
    (or (= :timed-out (:teensyp.client/kind data))
        (= :timed-out (:jolt.net/kind data)))))

(defn- connect-boundary-ex [host port exception]
  (let [data (ex-data exception)
        kind (:jolt.net/kind data)
        message (or (ex-message exception)
                    (str "connection failed: " host ":" port))]
    (cond
      (= :name-resolution kind)
      (typed-ex "java.net.UnknownHostException" (str host) exception)

      (timeout-error? exception)
      (typed-ex "java.net.SocketTimeoutException"
                (str "Connect timed out: " host ":" port)
                exception)

      (or (= :connection-refused kind)
          (= :unreachable kind))
      (typed-ex "java.net.ConnectException" message exception)

      :else exception)))

(defn- read-boundary-ex [exception]
  (if (timeout-error? exception)
    (typed-ex "java.net.SocketTimeoutException" "Read timed out" exception)
    exception))

(defn- make-transport
  [send-fn receive-fn close-fn read-timeout-ms]
  {transport-marker true
   send-fn-key send-fn
   receive-fn-key receive-fn
   close-fn-key close-fn
   read-timeout-key (atom read-timeout-ms)})

(defn callback-transport
  "Create a descriptor-free byte transport from callbacks.

  This is an adapter seam for the in-process TLS test server. Production
  outbound transports are created by [[connect]] and close over an opaque
  teensyp.client connection. `send-fn` receives bytes plus an operation-options
  map; `receive-fn` receives the operation-options map. The map is empty for an
  unbounded operation and otherwise carries either `:timeout-ms` or the one
  request-wide `:deadline-nanos`."
  [{:keys [send-fn receive-fn close-fn read-timeout-ms]}]
  (when-not (and (fn? send-fn) (fn? receive-fn) (fn? close-fn))
    (throw (ex-info "jolt.http.net callback transport requires send/receive/close functions"
                    {:jolt.http.net/kind :invalid-callback-transport})))
  (make-transport send-fn receive-fn close-fn read-timeout-ms))

(defn transport? [value]
  (and (map? value) (true? (get value transport-marker))))

(defn- require-transport! [transport op]
  (when-not (transport? transport)
    (throw (ex-info (str "jolt.http.net " (name op)
                         ": expected an opaque byte transport")
                    {:jolt.http.net/op op
                     :jolt.http.net/kind :invalid-transport})))
  transport)

(defn- connect-options
  [connect-timeout-ms deadline-nanos]
  (cond
    (some? deadline-nanos)
    {:deadline-nanos
     (if (and connect-timeout-ms (pos? connect-timeout-ms))
       (min deadline-nanos
            (+ (monotonic-now)
               (* connect-timeout-ms nanos-per-ms)))
       deadline-nanos)}

    (and connect-timeout-ms (pos? connect-timeout-ms))
    {:connect-timeout-ms connect-timeout-ms}

    :else
    ;; This explicit nil is distinct from omitting the option: teensyp.client's
    ;; omission default is 30 seconds, while URLConnection zero/unset is
    ;; unbounded.
    {:connect-timeout-ms nil}))

(defn- operation-options [opts]
  (if (some? (:deadline-nanos opts))
    {:deadline-nanos (:deadline-nanos opts)}
    {}))

(defn connect
  "Open an opaque outbound TCP transport.

  Options:
  - a positive `:connect-timeout-ms` is one monotonic deadline across resolution
    and all address candidates; nil/zero is explicitly unbounded.
  - `:deadline-nanos` is an optional request-wide absolute monotonic deadline.
    When both deadlines are bounded, the earlier one governs connect.
  - `:read-timeout-ms` is an inactivity deadline applied independently to each
    receive. Nil/zero means unbounded, matching URLConnection read semantics."
  ([host port] (connect host port {}))
  ([host port {:keys [connect-timeout-ms read-timeout-ms deadline-nanos]}]
   (let [connect-opts (connect-options connect-timeout-ms deadline-nanos)]
     (try
       (let [connection (client/connect (str host) port connect-opts)]
         (make-transport
           (fn [data opts]
             (if (seq opts)
               (client/send-all! connection data opts)
               (client/send-all! connection data)))
           (fn [opts]
             (if (seq opts)
               (client/receive-at-most! connection bufsize opts)
               (client/receive-at-most! connection bufsize)))
           #(client/close! connection)
           read-timeout-ms))
       (catch :default exception
         (throw (connect-boundary-ex host port exception)))))))

(defn set-read-timeout!
  "Set the inactivity timeout used by subsequent receives. Nil/zero disables
  the deadline. Returns nil for HttpURLConnection compatibility."
  [transport timeout-ms]
  (require-transport! transport :set-read-timeout)
  (reset! (get transport read-timeout-key) timeout-ms)
  nil)

(defn recv-bytes
  "Receive at most one transport chunk, or nil at EOF.

  Only an actual operation deadline is mapped to SocketTimeoutException.
  Connection reset, close, and unknown native failures retain their original
  structured exception identity."
  ([transport] (recv-bytes transport {}))
  ([transport opts]
   (require-transport! transport :receive)
   (let [absolute-opts (operation-options opts)
         timeout-ms @(get transport read-timeout-key)
         receive-opts (if (seq absolute-opts)
                        absolute-opts
                        (if (and timeout-ms (pos? timeout-ms))
                          {:timeout-ms timeout-ms}
                          {}))]
     (try
       ((get transport receive-fn-key) receive-opts)
       (catch :default exception
         (throw (read-boundary-ex exception)))))))

(defn send-bytes
  "Send the entire byte-array. teensyp.client handles partial writes and
  same-direction serialization."
  ([transport data] (send-bytes transport data {}))
  ([transport data opts]
   (require-transport! transport :send)
   ((get transport send-fn-key) data (operation-options opts))
   nil))

(defn close
  "Close the opaque transport. Idempotence is delegated to its owner."
  [transport]
  (require-transport! transport :close)
  ((get transport close-fn-key))
  nil)
