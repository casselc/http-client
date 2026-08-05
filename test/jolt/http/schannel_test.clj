(ns jolt.http.schannel-test
  "Portable contracts around Schannel's probed native boundary.

  These run on every host. Native Windows gates separately prove the actual
  SSPI calls and loopback handshake."
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.ffi :as ffi]
            [jolt.http.schannel :as schannel]))

(deftest target-names-are-nul-terminated-utf16le
  (let [pointer (@#'schannel/utf16-pointer "AΩ")]
    (try
      (let [bytes (ffi/read-array pointer 6)]
        (is (= [65 0 -87 3 0 0] (vec bytes)))
        (is (= [65 0 169 3 0 0]
               (mapv #(bit-and % 0xff) bytes))))
      (finally
        (ffi/free pointer)))))

(deftest extra-data-is-the-exact-ordered-input-suffix
  (let [input (byte-array [10 11 12 13 14])]
    (is (= [] (vec (@#'schannel/tail-bytes input 0))))
    (is (= [13 14] (vec (@#'schannel/tail-bytes input 2))))
    (is (= [10 11 12 13 14]
           (vec (@#'schannel/tail-bytes input 5))))
    (is (thrown? javax.net.ssl.SSLException
                 (@#'schannel/tail-bytes input 6)))))

(deftest encrypted-input-slot-is-never-reported-as-plaintext
  (let [input-only
        (@#'schannel/allocate-descriptor
          [{:length 26
            :type @#'schannel/buffer-data
            :pointer 1001}
           {:length 0
            :type @#'schannel/buffer-empty
            :pointer 0}])
        with-output
        (@#'schannel/allocate-descriptor
          [{:length 26
            :type @#'schannel/buffer-data
            :pointer 1001}
           {:length 2
            :type @#'schannel/buffer-data
            :pointer 2002}])]
    (try
      (is (nil? (@#'schannel/data-window input-only)))
      (is (= {:length 2 :pointer 2002}
             (@#'schannel/data-window with-output)))
      (finally
        (@#'schannel/free-descriptor! input-only)
        (@#'schannel/free-descriptor! with-output)))))

(deftest trust-all-is-one-explicit-request-bit
  (let [secure (@#'schannel/request-flags false)
        insecure (@#'schannel/request-flags true)
        manual @#'schannel/request-manual-validation]
    (is (zero? (bit-and secure manual)))
    (is (= manual (bit-and insecure manual)))
    (is (= manual (bit-xor secure insecure)))))

(deftest output-token-retirement-is-published-before-an-outer-finally
  (let [native (@#'schannel/allocate-descriptor
                 [{:length 7
                   :type @#'schannel/buffer-token
                   :pointer 1234}])
        calls (atom [])]
    (try
      (with-redefs-fn
        {#'schannel/c-free-context-buffer
         (fn [pointer]
           (swap! calls conj pointer)
           @#'schannel/status-ok)}
        (fn []
          (is (= 7 (@#'schannel/release-output-token! native)))
          (is (= {:length 0 :pointer 0}
                 (@#'schannel/output-token native)))
          (is (= 0 (@#'schannel/release-output-token! native)))))
      (is (= [1234] @calls))
      (finally
        (@#'schannel/free-descriptor! native)))))

(deftest an-empty-renegotiation-token-reenters-sspi-before-reading
  (let [stream (jolt.host/tagged-table :test/schannel-stream)
        state {:stream stream :transport :test/transport}
        calls (atom [])]
    (with-redefs-fn
      {#'schannel/initialize-step!
       (fn [_ _ input first? opts]
         (swap! calls conj
                {:input (vec input) :first? first? :opts opts})
         {:status @#'schannel/status-ok
          :extra (byte-array 0)})
       #'schannel/query-stream-sizes!
       (fn [_] {:header 5 :trailer 16 :maximum-message 16384 :buffers 4})
       #'schannel/receive-required!
       (fn [& _]
         (throw (ex-info "renegotiation read happened too early" {})))}
      (fn []
        (is (true?
              (@#'schannel/handshake!
                state 1234 (byte-array 0) false {:deadline-nanos 99})))))
    (is (= [{:input [] :first? false :opts {:deadline-nanos 99}}]
           @calls))
    (is (= []
           (vec (jolt.host/ref-get stream :pending))))
    (is (= 16384
           (:maximum-message (jolt.host/ref-get stream :sizes))))))

(deftest close-notify-delivers-final-plaintext-once
  (let [stream (jolt.host/tagged-table :test/schannel-stream)
        state {:stream stream :transport :test/transport}
        decrypt-calls (atom 0)]
    (jolt.host/ref-put! stream :pending (byte-array [9]))
    (jolt.host/ref-put! stream :eof false)
    (with-redefs-fn
      {#'schannel/decrypt-once!
       (fn [_ encrypted]
         (swap! decrypt-calls inc)
         (is (= [9] (vec encrypted)))
         {:status @#'schannel/status-context-expired
          :plaintext (byte-array [65 66])
          :extra (byte-array 0)})
       #'jolt.http.net/recv-bytes
       (fn [& _]
         (throw (ex-info "read past close_notify" {})))}
      (fn []
        (is (= [65 66]
               (vec (@#'schannel/read-plaintext! state {}))))
        (is (nil? (@#'schannel/read-plaintext! state {})))))
    (is (= 1 @decrypt-calls))
    (is (true? (jolt.host/ref-get stream :eof)))))

(deftest native-allocation-failures-retire-earlier-storage
  (testing "descriptor storage is retired if descriptor allocation fails"
    (let [allocations (atom [101])
          frees (atom [])]
      (with-redefs-fn
        {#'ffi/alloc
         (fn [_]
           (if-let [pointer (first @allocations)]
             (do (swap! allocations rest) pointer)
             (throw (ex-info "descriptor allocation failed" {}))))
         #'ffi/free
         (fn [pointer] (swap! frees conj pointer))}
        (fn []
          (is (thrown? Exception
                       (@#'schannel/allocate-descriptor [])))))
      (is (= [101] @frees))))

  (testing "state allocations unwind in reverse acquisition order"
    (let [allocations (atom [201 202])
          frees (atom [])]
      (with-redefs-fn
        {#'ffi/alloc
         (fn [_]
           (if-let [pointer (first @allocations)]
             (do (swap! allocations rest) pointer)
             (throw (ex-info "attribute allocation failed" {}))))
         #'ffi/free
         (fn [pointer] (swap! frees conj pointer))}
        (fn []
          (is (thrown? Exception
                       (@#'schannel/make-state
                         :test/transport "localhost" false)))))
      (is (= [202 201] @frees)))))

(deftest plaintext-is-split-only-at-the-negotiated-maximum
  (let [stream (jolt.host/tagged-table :test/schannel-stream)
        state {:stream stream :closed? (atom false)}
        calls (atom [])
        data (byte-array (range 13))]
    (jolt.host/ref-put! stream :sizes {:maximum-message 5})
    (with-redefs-fn
      {#'schannel/encrypt-chunk!
       (fn [_ bytes offset length opts]
         (swap! calls conj
                {:offset offset
                 :length length
                 :bytes (vec bytes)
                 :opts opts}))}
      (fn []
        (is (= stream
               (@#'schannel/write-plaintext!
                 state data {:deadline-nanos 99})))))
    (is (= [[0 5] [5 5] [10 3]]
           (mapv (juxt :offset :length) @calls)))
    (is (every? #(= {:deadline-nanos 99} (:opts %)) @calls))
    (is (every? #(= (vec data) (:bytes %)) @calls))))

(deftest a-closed-stream-refuses-plaintext-before-native-work
  (let [stream (jolt.host/tagged-table :test/schannel-stream)
        state {:stream stream :closed? (atom true)}]
    (jolt.host/ref-put! stream :sizes {:maximum-message 5})
    (is (thrown? javax.net.ssl.SSLException
                 (@#'schannel/write-plaintext!
                   state (byte-array [1]) {})))))

(defn -main [& _]
  (let [result (clojure.test/run-tests 'jolt.http.schannel-test)]
    (println "\n========== SCHANNEL CONTRACT TOTAL ==========")
    (println (str "tests=" (:test result)
                  " pass=" (:pass result)
                  " fail=" (:fail result)
                  " error=" (:error result)))
    (flush)
    (System/exit
      (if (or (pos? (:fail result)) (pos? (:error result))) 1 0))))
