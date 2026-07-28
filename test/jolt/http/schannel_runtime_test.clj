(ns jolt.http.schannel-runtime-test
  "Native Windows Schannel client gate against the PowerShell-owned TLS fixture."
  (:require [clj-http.lite.core :as http]
            [clojure.test :refer [deftest is testing]]
            [jolt.http.capability :as capability]
            [jolt.http.platform]))

(defn- caught [f]
  (try
    (f)
    nil
    (catch :default exception exception)))

(defn- required-env [name]
  (or (System/getenv name)
      (throw (ex-info (str "missing environment variable " name)
                      {:name name}))))

(defn- port []
  (or (parse-long (required-env "JOLT_SCHANNEL_PORT"))
      (throw (ex-info "JOLT_SCHANNEL_PORT is not an integer" {}))))

(defn- request [insecure?]
  (http/request
    {:request-method :get
     :uri "/schannel"
     :scheme :https
     :server-name "localhost"
     :server-port (port)
     :insecure? insecure?}))

(deftest native-target-selects-only-the-client-tls-export
  (let [target (jolt.host/target)
        expected (keyword (required-env "JOLT_EXPECTED_ARCH"))
        report (capability/report)
        provider (capability/provider :tls)]
    (is (= :windows (:os target)))
    (is (= expected (:arch target)))
    (is (true? (:tls report)))
    (is (= #{:connect} (set (keys provider))))
    (is (ifn? (:connect provider)))))

(deftest certificate-validation-is-explicit-and-does-not-leak
  (testing "automatic validation rejects the self-signed origin"
    (let [exception (caught #(request false))]
      (is (some? exception))
      (is (= javax.net.ssl.SSLException (class exception)))))

  (testing "the explicit trust-all request completes a real TLS/HTTP exchange"
    (let [response (request true)]
      (is (= 200 (:status response)))
      (is (= "schannel" (slurp (:body response))))))

  (testing "the next secure connection still performs automatic validation"
    (let [exception (caught #(request false))]
      (is (some? exception))
      (is (= javax.net.ssl.SSLException (class exception))))))

(defn -main [& _]
  (let [result
        (clojure.test/run-tests 'jolt.http.schannel-runtime-test)]
    (println "\n========== SCHANNEL RUNTIME TOTAL ==========")
    (println (str "tests=" (:test result)
                  " pass=" (:pass result)
                  " fail=" (:fail result)
                  " error=" (:error result)))
    (flush)
    (System/exit
      (if (or (pos? (:fail result)) (pos? (:error result))) 1 0))))
