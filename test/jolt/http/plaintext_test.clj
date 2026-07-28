(ns jolt.http.plaintext-test
  "Cross-platform acceptance gate: ordinary plaintext HTTP through the unchanged
  clj-http-lite public API, over jolt-tcp, against a real loopback origin.

  Everything here runs on the portable stack only — jolt.http.platform's
  java.net shims, jolt.http.net over teensyp.client, and a teensyp.server
  origin. No OpenSSL and no zlib is loaded, which is what makes this suite the
  same suite on Linux x86_64 and on native Windows. The TLS and compression
  behaviour is proved separately, by suites that are POSIX-only today.

  The requests are the ones a client has to get right: both directions of body
  conservation, header transmission, all three response framings, redirects,
  and the four failure modes (connect refusal, read deadline, silent server,
  server close without a response)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jolt.http.capability :as capability]
            [jolt.http.net :as net]
            [jolt.http.portable-server :as origin]
            [jolt.http-client :as http]))

(def ^:private server (atom nil))

(defn- port [] (:port @server))
(defn- url [path] (str "http://127.0.0.1:" (port) path))

(defn- with-origin [run]
  ;; Port 0 lets the OS pick, and run-server reports the bound port back, so
  ;; the suite never races another listener for a hard-coded number.
  ;; run-server also blocks until its reactor signals ready, so returning from
  ;; start is itself the readiness gate — nothing here sleeps or retries.
  (let [handle (origin/start 0)]
    (reset! server handle)
    (try
      (run)
      (finally
        (origin/stop handle)
        (reset! server nil)))))

(use-fixtures :once with-origin)

(defn- caught [f]
  (try (f) nil (catch :default exception exception)))

(defn- closed-port
  "A port with nothing listening: bind one, read it back, then release it."
  []
  (let [handle (origin/start 0)
        bound (:port handle)]
    (origin/stop handle)
    bound))

;; --- the capability claim ---------------------------------------------------

(def ^:private provider-namespaces ['jolt.http.tls 'jolt.http.zlib 'jolt.crypto])

(defn loaded-provider-namespaces
  "Which optional provider namespaces are loaded right now.

  This is the direct form of the claim. Asserting that a request 'does not need
  OpenSSL' is weak on a machine that has OpenSSL; asserting that the OpenSSL
  namespace was never loaded while the request ran is checkable everywhere,
  and it is what would break first if a require crept back into the plaintext
  graph. Nothing else in this suite may resolve a capability, or the evidence
  would be destroyed by the observer."
  []
  (vec (filter #(some? (find-ns %)) provider-namespaces)))

(deftest plaintext-requests-load-no-tls-or-compression-namespace
  (testing "no provider namespace is loaded before the request"
    (is (= [] (loaded-provider-namespaces))))

  (testing "a full plaintext exchange succeeds"
    (let [response (http/get (url "/plain"))]
      (is (= 200 (:status response)))
      (is (= "plaintext ok" (:body response)))))

  (testing "and none was loaded to serve it"
    ;; The assertion that matters on native Windows: this response was produced
    ;; with no libssl, no libcrypto and no libz anywhere in the process.
    (is (= [] (loaded-provider-namespaces)))))

;; --- GET / POST and body conservation --------------------------------------

(deftest get-returns-status-headers-and-body
  (let [response (http/get (url "/plain"))]
    (is (= 200 (:status response)))
    (is (= "plaintext ok" (:body response)))
    (is (= "text/plain" (get-in response [:headers "content-type"])))))

(deftest get-conserves-a-body-larger-than-one-read
  (let [response (http/get (url "/large"))]
    (is (= 200 (:status response)))
    (is (= (count origin/large-body) (count (:body response))))
    (is (= origin/large-body (:body response)))))

(deftest empty-and-query-responses
  (testing "204 with no body"
    (let [response (http/get (url "/empty"))]
      (is (= 204 (:status response)))
      (is (= "" (:body response)))))

  (testing "the query string reaches the origin"
    (is (= "a=1&b=two" (:body (http/get (url "/query?a=1&b=two")))))))

(deftest post-conserves-the-request-body-and-content-type
  (let [payload "field=value&other=%20spaced%20"
        response (http/post (url "/echo")
                            {:body payload :content-type "application/x-www-form-urlencoded"})]
    (is (= 200 (:status response)))
    (is (= payload (:body response)))
    (is (str/starts-with? (get-in response [:headers "content-type"])
                          "application/x-www-form-urlencoded"))))

(deftest post-conserves-a-large-body
  (let [response (http/post (url "/echo") {:body origin/large-body})]
    (is (= 200 (:status response)))
    (is (= (count origin/large-body) (count (:body response))))
    (is (= origin/large-body (:body response)))))

(deftest put-conserves-its-body
  (is (= "put body" (:body (http/put (url "/echo") {:body "put body"})))))

;; --- headers ----------------------------------------------------------------

(deftest request-headers-reach-the-origin
  (testing "a caller header is transmitted verbatim"
    (is (= "probe-value"
           (:body (http/get (url "/echo-header") {:headers {"X-Probe" "probe-value"}})))))

  (testing "Host carries the authority the client dialled"
    (let [reflected (:body (http/get (url "/headers")))]
      (is (str/includes? reflected (str "host: 127.0.0.1:" (port)))))))

;; --- response framing -------------------------------------------------------

(deftest all-three-response-framings-conserve-the-body
  (testing "fixed Content-Length"
    (is (= "plaintext ok" (:body (http/get (url "/plain"))))))

  (testing "chunked transfer-encoding is dechunked"
    (is (= "chunked body conserved" (:body (http/get (url "/chunked"))))))

  (testing "a body framed only by connection close"
    (is (= "eof framed body" (:body (http/get (url "/eof-framed")))))))

;; --- redirects --------------------------------------------------------------

(deftest redirects-are-followed-by-default
  (let [response (http/get (url "/redirect"))]
    (is (= 200 (:status response)))
    (is (= "plaintext ok" (:body response)))))

(deftest absolute-and-chained-redirects
  (testing "an absolute Location"
    (is (= "plaintext ok" (:body (http/get (url "/redirect-absolute"))))))

  (testing "more than one hop"
    (is (= "plaintext ok" (:body (http/get (url "/redirect-chain")))))))

(deftest redirects-can-be-refused
  (let [response (http/get (url "/redirect") {:follow-redirects false})]
    (is (= 302 (:status response)))
    (is (= "/plain" (get-in response [:headers "location"])))))

;; --- failure modes ----------------------------------------------------------

(deftest an-error-status-is-reported-with-its-body
  (let [response (http/get (url "/error") {:throw-exceptions false})]
    (is (= 500 (:status response)))
    (is (= "server error body" (:body response)))))

(deftest connect-refusal-is-a-connect-exception
  (let [refused (caught #(http/get (str "http://127.0.0.1:" (closed-port) "/plain")))]
    (is (some? refused))
    (is (= java.net.ConnectException (class refused)))))

(deftest a-read-deadline-expires-against-a-silent-origin
  (let [timed-out (caught #(http/get (url "/never") {:socket-timeout 300}))]
    (is (some? timed-out))
    (is (= java.net.SocketTimeoutException (class timed-out)))))

(deftest a-server-that-closes-without-responding-is-an-io-error
  (let [failed (caught #(http/get (url "/abort")))]
    (is (some? failed))
    ;; The point is that it is reported at all, as an I/O failure, rather than
    ;; surfacing as an empty 200 or hanging forever.
    (is (= java.io.IOException (class failed)))))

;; --- transport opacity ------------------------------------------------------

(deftest no-native-descriptor-crosses-the-transport-boundary
  (let [transport (net/connect "127.0.0.1" (port) {:connect-timeout-ms 2000})]
    (try
      (is (net/transport? transport))
      (testing "no descriptor-shaped key"
        (doseq [k [:fd :socket :handle :sock :native-handle]]
          (is (not (contains? transport k)))))
      (testing "no descriptor-shaped value"
        ;; A native fd/SOCKET would arrive as an integer or a raw pointer. Every
        ;; value here is a closure, an atom or the marker flag, so there is
        ;; nothing for a caller to close, dup or leak behind the client's back.
        (doseq [value (vals transport)]
          (is (not (number? value)))
          (is (or (true? value) (fn? value) (instance? clojure.lang.Atom value)))))
      (finally (net/close transport)))))

(defn- assert-expected-arch!
  "Refuse to run when the process architecture is not the one the lane claims.

  Windows on ARM64 will happily run an x86-64 binary under emulation, and such
  a run would report a green aarch64 gate having proved nothing about aarch64.
  The CI lanes set JOLT_EXPECTED_ARCH; elsewhere this is a no-op."
  []
  (when-let [expected (System/getenv "JOLT_EXPECTED_ARCH")]
    (let [actual (name (:arch (jolt.host/target)))]
      (when-not (= expected actual)
        (println (str "FATAL: expected arch " expected " but this process is " actual))
        (flush)
        (System/exit 2))
      (println (str "arch gate: running natively on " actual)))))

(defn -main [& _]
  (assert-expected-arch!)
  (let [result (clojure.test/run-tests 'jolt.http.plaintext-test)
        ;; Read before capability/report, which resolves providers and would
        ;; otherwise be the thing that loaded them.
        loaded-during-run (loaded-provider-namespaces)]
    (println "\n========== PLAINTEXT TOTAL ==========")
    (println (str "tests=" (:test result)
                  " pass=" (:pass result)
                  " fail=" (:fail result)
                  " error=" (:error result)))
    (println "target:" (:target (capability/report)))
    (println "provider namespaces loaded during the plaintext run:" loaded-during-run)
    (flush)
    (System/exit (if (or (pos? (:fail result))
                         (pos? (:error result))
                         (seq loaded-during-run))
                   1
                   0))))
