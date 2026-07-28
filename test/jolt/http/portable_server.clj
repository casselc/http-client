(ns jolt.http.portable-server
  "A portable in-process HTTP/1.1 origin server for the cross-platform
  plaintext gate.

  jolt.http.test-server, which backs the clj-http-lite and TLS suites, opens
  its listener with raw POSIX socket/bind/listen/accept through jolt.ffi. That
  is fine where it runs, but it is exactly what cannot run on native Windows,
  so it could never witness the claim this gate exists to make. This server
  instead runs on teensyp.server from jolt-tcp — the same portable reactor the
  client's transport dials into — so one suite proves plaintext HTTP over
  jolt-tcp on every platform jolt-tcp supports.

  Routes deliberately cover the framing shapes an HTTP client must get right
  rather than only the happy path: fixed Content-Length, chunked
  transfer-encoding, a body delimited solely by connection close, redirects,
  a slow response for read-timeout coverage, and an immediate close with no
  response at all."
  (:require [clojure.string :as str]
            [teensyp.buffer :as buf]
            [teensyp.server :as server]))

(defn- ba->latin1 [ba] (String. ba "ISO-8859-1"))
(defn- latin1->ba [s] (byte-array (map int s)))

;; --- request parsing --------------------------------------------------------
;; Requests are accumulated as a latin1 string so header scanning and body
;; length accounting share one byte-per-char index.
(defn- parse-request [text head-end]
  (let [head (subs text 0 head-end)
        body (subs text (+ head-end 4))
        lines (str/split head #"\r\n")
        [method target] (str/split (first lines) #" ")
        query (str/index-of (str target) "?")
        headers (reduce (fn [m line]
                          (let [i (str/index-of line ":")]
                            (if (and i (pos? i))
                              (assoc m
                                     (str/lower-case (str/trim (subs line 0 i)))
                                     (str/trim (subs line (inc i))))
                              m)))
                        {}
                        (rest lines))]
    {:request-method (keyword (str/lower-case (str method)))
     :uri (if query (subs (str target) 0 query) (str target))
     :query (when query (subs (str target) (inc query)))
     :headers headers
     :body body
     :content-length (or (parse-long (or (get headers "content-length") "")) 0)}))

(defn- complete-request
  "Parse `text` into a request once headers and the declared body have both
  arrived, else nil. Returning nil is what drives the read loop, so a client
  that writes its headers and body in separate segments is handled the same as
  one that sends a single packet."
  [text]
  (when-let [head-end (str/index-of text "\r\n\r\n")]
    (let [request (parse-request text head-end)]
      (when (>= (count (:body request)) (:content-length request))
        request))))

;; --- routes -----------------------------------------------------------------
(def ^:private status-text
  {200 "OK" 201 "Created" 204 "No Content" 301 "Moved Permanently"
   302 "Found" 303 "See Other" 307 "Temporary Redirect" 400 "Bad Request"
   404 "Not Found" 500 "Internal Server Error"})

(def large-body
  "Bigger than the server's read buffer and the client's receive chunk, so a
  body that survives it survives segmentation rather than fitting in one read."
  (str/join (repeat 5000 "0123456789abcdef")))

(defn handler-response
  "Route a parsed request to a response description. Separate from the wire
  encoding so the routing table can be exercised directly in a test."
  [{:keys [request-method uri headers body query]}]
  (cond
    (and (= :get request-method) (= "/plain" uri))
    {:status 200 :headers {"Content-Type" "text/plain"} :body "plaintext ok"}

    (and (= :get request-method) (= "/large" uri))
    {:status 200 :body large-body}

    (and (= :get request-method) (= "/empty" uri))
    {:status 204 :body ""}

    (and (= :get request-method) (= "/query" uri))
    {:status 200 :body (str query)}

    (and (= :get request-method) (= "/echo-header" uri))
    {:status 200 :body (str (get headers "x-probe"))}

    ;; Reflects every request header, so a test can assert on what actually
    ;; went out on the wire rather than on what the client says it sent.
    (and (= :get request-method) (= "/headers" uri))
    {:status 200
     :body (str/join "\n" (sort (map (fn [[k v]] (str k ": " v)) headers)))}

    (and (= :post request-method) (= "/echo" uri))
    {:status 200
     :headers {"Content-Type" (str (get headers "content-type"))}
     :body body}

    (and (= :put request-method) (= "/echo" uri))
    {:status 200 :body body}

    ;; Response body framed by chunked transfer-encoding.
    (and (= :get request-method) (= "/chunked" uri))
    {:status 200 :framing :chunked
     :chunks ["chunked" " " "body" " " "conserved"]}

    ;; Response body framed only by the connection closing: no Content-Length
    ;; and no transfer-encoding, so the client must treat EOF as the terminator.
    (and (= :get request-method) (= "/eof-framed" uri))
    {:status 200 :framing :eof :body "eof framed body"}

    (and (= :get request-method) (= "/redirect" uri))
    {:status 302 :headers {"Location" "/plain"} :body ""}

    (and (= :get request-method) (= "/redirect-absolute" uri))
    {:status 302 :headers {"Location" (str "http://" (get headers "host") "/plain")} :body ""}

    ;; A redirect chain, to prove hops are actually followed rather than one
    ;; hop being mistaken for the whole behaviour.
    (and (= :get request-method) (= "/redirect-chain" uri))
    {:status 302 :headers {"Location" "/redirect"} :body ""}

    (and (= :post request-method) (= "/redirect" uri))
    {:status 303 :headers {"Location" "/plain"} :body ""}

    (and (= :get request-method) (= "/error" uri))
    {:status 500 :body "server error body"}

    ;; Never answers: the client's read deadline is the only thing that ends
    ;; this exchange.
    (and (= :get request-method) (= "/never" uri))
    {:framing :silent}

    ;; Closes without writing a byte of response.
    (and (= :get request-method) (= "/abort" uri))
    {:framing :abort}

    :else
    {:status 404 :body "not found"}))

;; --- wire encoding ----------------------------------------------------------
(defn- header-block [status headers]
  (let [sb (StringBuilder.)]
    (.append sb (str "HTTP/1.1 " status " " (get status-text status "OK") "\r\n"))
    (doseq [[k v] headers] (.append sb (str k ": " v "\r\n")))
    (.append sb "\r\n")
    (.toString sb)))

(defn- encode-response [{:keys [status headers body framing chunks]}]
  (case (or framing :length)
    :length
    (let [body (or body "")]
      (str (header-block status (assoc headers
                                       "Content-Length" (count body)
                                       "Connection" "close"))
           body))

    :chunked
    (str (header-block status (assoc headers
                                     "Transfer-Encoding" "chunked"
                                     "Connection" "close"))
         (str/join (map (fn [chunk]
                          (str (format "%x" (count chunk)) "\r\n" chunk "\r\n"))
                        chunks))
         "0\r\n\r\n")

    :eof
    (str (header-block status (assoc headers "Connection" "close")) (or body ""))))

;; --- connection handling ----------------------------------------------------

;; teensyp admits a write only against the remaining :write-buffer-size budget
;; and rejects anything larger outright, so a response bigger than that budget
;; has to be written in bounded segments that each wait for the bytes ahead of
;; them to drain. Awaiting write-completion is the supported way to do that:
;; completion callbacks run on a dedicated pool precisely so a handler blocked
;; on its own write cannot starve the callback that releases it. The deref is
;; bounded, so a wedged write is reported as a failure instead of hanging the
;; suite.
(def ^:private write-segment 16384)
(def ^:private write-settle-ms 30000)

(defn- write-all! [socket ^bytes data]
  (let [n (alength data)]
    (loop [off 0]
      (when (< off n)
        (let [len (min write-segment (- n off))
              segment (java.util.Arrays/copyOfRange data (int off) (int (+ off len)))
              settled (deref (server/write-completion socket (buf/wrap segment))
                             write-settle-ms
                             ::unsettled)]
          (cond
            (= ::unsettled settled)
            (throw (ex-info "portable origin: write did not settle"
                            {:offset off :length len :total n}))

            (= :failed (:status settled))
            (throw (ex-info "portable origin: write failed"
                            {:offset off :total n}
                            (:exception settled)))

            :else (recur (+ off len))))))))

;; State is a plain map threaded through the handler arities; teensyp guarantees
;; serial per-connection calls, so no lock is needed around it.
(defn- respond! [socket request]
  (let [{:keys [framing] :as response} (handler-response request)]
    (case framing
      ;; Hold the connection open and silent. The client's read deadline must
      ;; be what ends this, which is the whole point of the route.
      :silent nil
      :abort (server/close socket)
      (do (write-all! socket (latin1->ba (encode-response response)))
          (server/close socket)))))

(defn- on-read [state socket buffer]
  (if (:answered state)
    state
    (let [text (str (:text state) (buf/buffer->str buffer "ISO-8859-1"))]
      (if-let [request (complete-request text)]
        (do (respond! socket request)
            (assoc state :text "" :answered true))
        (assoc state :text text)))))

(defn handler
  ([_socket] {:text "" :answered false})
  ([state socket buffer] (on-read state socket buffer))
  ([state _exception] state))

(defn start
  "Start the portable server on `port`. Returns a handle for [[stop]]."
  [port]
  (server/run-server :port port
                     :handler handler
                     :pool-size 4
                     :reuse-address? true
                     ;; Smaller than /large and than the client's 64 KiB
                     ;; receive chunk, so multi-read bodies are the normal case
                     ;; in this suite rather than an untested edge.
                     :read-buffer-size 4096
                     :write-buffer-size 65536
                     ;; A route that deliberately never answers is not an
                     ;; error; keep it out of the test log.
                     :error-logger (fn [_] nil)))

(defn stop [handle]
  (server/stop-server handle)
  nil)
