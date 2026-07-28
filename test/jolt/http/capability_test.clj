(ns jolt.http.capability-test
  "The capability seam: what happens when an optional provider is present, and
  — the part that actually matters — what happens when it is not.

  These tests must hold on every platform, so none of them assumes TLS or
  compression exists. Where a capability is present the suite checks the
  provider is really wired; where it is absent it checks the refusal is the
  structured unsupported-provider error rather than a silent downgrade. The
  absent case is also forced explicitly, so the fail-closed path is covered on
  a machine that happens to have both libraries installed."
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.http.capability :as capability]
            [jolt.http.platform :as platform]))

(defn- caught [f]
  (try (f) nil (catch :default exception exception)))

(deftest the-report-describes-this-platform
  (let [report (capability/report)]
    (is (true? (:plaintext report)))
    (is (contains? #{true false} (:tls report)))
    (is (contains? #{true false} (:compression report)))
    (is (some? (:os (:target report))))))

(deftest an-unknown-capability-is-rejected-rather-than-silently-absent
  (let [rejected (caught #(capability/provider :quantum))]
    (is (some? rejected))
    (is (= :unknown-capability (:jolt.http/kind (ex-data rejected))))))

(deftest a-resolved-capability-exports-callable-providers
  (doseq [capability [:tls :compression]]
    (when (capability/available? capability)
      (testing (str (name capability) " is present, so its exports must be callable")
        (let [exports (capability/provider capability)]
          (is (seq exports))
          (doseq [[_ target] exports]
            (is (ifn? target))))))))

(deftest compression-round-trips-when-its-provider-is-present
  (when (capability/available? :compression)
    (let [payload (.getBytes "compress me, and give me back exactly this" "UTF-8")]
      (testing "gzip"
        (is (= (seq payload)
               (seq (capability/invoke :compression :gunzip
                                       (capability/invoke :compression :gzip payload))))))
      (testing "deflate"
        (is (= (seq payload)
               (seq (capability/invoke :compression :inflate
                                       (capability/invoke :compression :deflate payload)))))))))

;; --- fail-closed ------------------------------------------------------------
;; Forced unavailability. This is the behaviour a platform without OpenSSL or
;; zlib gets, exercised where those libraries do exist, so the refusal path is
;; covered by every run rather than only by the platforms that lack a provider.

(defn- with-capability-unavailable
  "Run `body` with `capability` resolution forced to fail, then restore."
  [capability body]
  (let [resolved @#'capability/resolved
        failure (ex-info "forced absence for the fail-closed test"
                         {:jolt.http/forced true})]
    (try
      (swap! resolved assoc capability {:failure failure})
      (body)
      (finally
        (swap! resolved dissoc capability)))))

(deftest an-absent-capability-fails-closed-with-a-structured-error
  (doseq [capability [:tls :compression]]
    (with-capability-unavailable capability
      (fn []
        (testing (str (name capability) " reports itself unavailable")
          (is (false? (capability/available? capability))))

        (let [refused (caught #(capability/provider capability))]
          (testing "the refusal is the structured capability error"
            (is (some? refused))
            (is (capability/unsupported-provider-error? refused))
            (let [data (ex-data refused)]
              (is (= :unsupported-provider (:jolt.http/kind data)))
              (is (= capability (:jolt.http/capability data)))
              (is (seq (:jolt.http/libraries data)))
              (is (some? (:os (:jolt.http/target data))))))

          (testing "the underlying reason is retained as the cause"
            (is (:jolt.http/forced (ex-data (ex-cause refused))))))))))

(deftest https-is-refused-rather-than-downgraded-when-tls-is-absent
  (with-capability-unavailable :tls
    (fn []
      (let [refused (caught #(@#'platform/connect-stream
                               "secure.invalid" 443 true false nil nil nil))]
        (is (some? refused))
        (is (capability/unsupported-provider-error? refused))
        (is (= :tls (:jolt.http/capability (ex-data refused))))))))

(deftest an-ssl-context-request-is-refused-when-tls-is-absent
  ;; clj-http-lite's insecure path asks for an SSLContext before it connects.
  ;; Refusing there is what keeps a trust-all https request from getting as far
  ;; as opening a socket on a platform with no TLS at all.
  (with-capability-unavailable :tls
    (fn []
      (let [refused (caught #(javax.net.ssl.SSLContext/getInstance "SSL"))]
        (is (some? refused))
        (is (capability/unsupported-provider-error? refused))))))

(deftest decompression-is-refused-rather-than-faked-when-compression-is-absent
  ;; The response body stays encoded and Content-Encoding stays on the response;
  ;; what must never happen is undecoded bytes being handed back as decoded.
  (with-capability-unavailable :compression
    (fn []
      (doseq [construct [#(java.util.zip.GZIPInputStream.
                            (java.io.ByteArrayInputStream. (byte-array [1 2 3])))
                         #(java.util.zip.InflaterInputStream.
                            (java.io.ByteArrayInputStream. (byte-array [1 2 3])))
                         #(java.util.zip.DeflaterInputStream.
                            (java.io.ByteArrayInputStream. (byte-array [1 2 3])))]]
        (let [refused (caught construct)]
          (is (some? refused))
          (is (capability/unsupported-provider-error? refused))
          (is (= :compression (:jolt.http/capability (ex-data refused)))))))))

(defn -main [& _]
  (let [result (clojure.test/run-tests 'jolt.http.capability-test)]
    (println "\n========== CAPABILITY TOTAL ==========")
    (println (str "tests=" (:test result)
                  " pass=" (:pass result)
                  " fail=" (:fail result)
                  " error=" (:error result)))
    (println "capability report:" (capability/report))
    (flush)
    (System/exit (if (or (pos? (:fail result)) (pos? (:error result))) 1 0))))
