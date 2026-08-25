(ns jolt.http.net-test
  "Compatibility boundary tests for the teensyp.client-backed byte transport."
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.http.net :as net]
            [jolt.http.platform :as platform]
            [jolt.http.tls :as tls]
            [teensyp.client :as client]))

(defn- caught [f]
  (try
    (f)
    nil
    (catch :default exception exception)))

(defn- tagged [tag values]
  (let [table (jolt.host/tagged-table tag)]
    (doseq [[key value] values]
      (jolt.host/ref-put! table key value))
    table))

(deftest opaque-client-carries-deadlines-and-whole-byte-calls
  (let [calls (atom [])
        payload (.getBytes "whole request" "UTF-8")
        partial (.getBytes "partial" "UTF-8")]
    (with-redefs [client/connect
                  (fn [host port opts]
                    (swap! calls conj [:connect host port opts])
                    :opaque-client)
                  client/send-all!
                  (fn [connection bytes]
                    (swap! calls conj
                           [:send connection (String. bytes "UTF-8")])
                    nil)
                  client/receive-at-most!
                  (fn
                    ([connection max-bytes]
                     (swap! calls conj
                            [:receive connection max-bytes {}])
                     partial)
                    ([connection max-bytes opts]
                     (swap! calls conj
                            [:receive connection max-bytes opts])
                     partial))
                  client/close!
                  (fn [connection]
                    (swap! calls conj [:close connection])
                    true)]
      (let [transport (net/connect "example.test" 8443
                                   {:connect-timeout-ms 125
                                    :read-timeout-ms 75})]
        (is (net/transport? transport))
        (is (not (contains? transport :fd)))
        (is (not (contains? transport :socket)))
        (is (not (some #{:opaque-client} (vals transport))))
        (is (nil? (net/send-bytes transport payload)))
        (is (= "partial" (String. (net/recv-bytes transport) "UTF-8")))
        (is (nil? (net/set-read-timeout! transport 0)))
        (is (= "partial" (String. (net/recv-bytes transport) "UTF-8")))
        (is (nil? (net/close transport)))))
    (is (= [[:connect "example.test" 8443
             {:connect-timeout-ms 125}]
            [:send :opaque-client "whole request"]
            [:receive :opaque-client 65536 {:timeout-ms 75}]
            [:receive :opaque-client 65536 {}]
            [:close :opaque-client]]
           @calls))))

(deftest eof-and-native-errors-retain-their-meaning
  (testing "EOF is nil"
    (let [transport
          (net/callback-transport
            {:send-fn (fn [_ _] nil)
             :receive-fn (fn [_] nil)
             :close-fn (fn [] true)})]
      (is (nil? (net/recv-bytes transport)))))

  (testing "an operation deadline maps to SocketTimeoutException with cause"
    (let [deadline (ex-info "deadline"
                            {:teensyp.client/kind :timed-out
                             :teensyp.client/op :receive})
          transport
          (net/callback-transport
            {:send-fn (fn [_ _] nil)
             :receive-fn (fn [_] (throw deadline))
             :close-fn (fn [] true)})
          mapped (caught #(net/recv-bytes transport))]
      (is (= java.net.SocketTimeoutException (class mapped)))
      (is (identical? deadline (.getCause mapped)))))

  (testing "connection reset is never rewritten as timeout or EOF"
    (let [reset-error (ex-info "reset"
                               {:jolt.net/op :read
                                :jolt.net/kind :connection-reset
                                :jolt.net/code 104})
          transport
          (net/callback-transport
            {:send-fn (fn [_ _] nil)
             :receive-fn (fn [_] (throw reset-error))
             :close-fn (fn [] true)})]
      (is (identical? reset-error
                      (caught #(net/recv-bytes transport)))))))

(deftest connect-maps-only-java-boundary-cases
  (testing "resolver failure"
    (let [failure (ex-info "no such host"
                           {:jolt.net/op :resolve
                            :jolt.net/kind :name-resolution})
          mapped
          (with-redefs [client/connect (fn [& _] (throw failure))]
            (caught #(net/connect "missing.invalid" 80
                                  {:connect-timeout-ms 10})))]
      (is (= java.net.UnknownHostException (class mapped)))
      (is (identical? failure (.getCause mapped)))))

  (testing "connect deadline"
    (let [failure (ex-info "deadline"
                           {:teensyp.client/op :connect
                            :teensyp.client/kind :timed-out})
          mapped
          (with-redefs [client/connect (fn [& _] (throw failure))]
            (caught #(net/connect "127.0.0.1" 9
                                  {:connect-timeout-ms 10})))]
      (is (= java.net.SocketTimeoutException (class mapped)))
      (is (identical? failure (.getCause mapped)))))

  (testing "unknown native failure preserves identity"
    (let [failure (ex-info "native"
                           {:jolt.net/op :connect
                            :jolt.net/kind :unknown
                            :jolt.net/code 999})
          actual
          (with-redefs [client/connect (fn [& _] (throw failure))]
            (caught #(net/connect "127.0.0.1" 9)))]
      (is (identical? failure actual)))))

(deftest zero-and-unset-connect-timeouts-are-explicitly-unbounded
  (let [calls (atom [])]
    (with-redefs [client/connect
                  (fn [host port opts]
                    (swap! calls conj [host port opts])
                    :opaque-client)]
      (net/connect "unset.test" 80)
      (net/connect "zero.test" 443 {:connect-timeout-ms 0}))
    (is (= [["unset.test" 80 {:connect-timeout-ms nil}]
            ["zero.test" 443 {:connect-timeout-ms nil}]]
           @calls))))

(deftest one-request-deadline-reaches-connect-write-and-every-read
  (let [calls (atom [])
        chunks (atom [(.getBytes "first" "UTF-8")
                      (.getBytes "second" "UTF-8")
                      nil])]
    (with-redefs-fn
      {#'net/monotonic-now (fn [] 7000000)
       #'client/connect
       (fn [host port opts]
         (swap! calls conj [:connect host port opts])
         :opaque-client)
       #'client/send-all!
       (fn [connection bytes opts]
         (swap! calls conj
                [:send connection (String. bytes "UTF-8") opts])
         nil)
       #'client/receive-at-most!
       (fn [connection max-bytes opts]
         (swap! calls conj [:receive connection max-bytes opts])
         (let [chunk (first @chunks)]
           (swap! chunks #(vec (next %)))
           chunk))
       #'client/close! (fn [_] true)}
      (fn []
        (let [deadline 20000000
              transport
              (net/connect "deadline.test" 8080
                           {:connect-timeout-ms 20
                            :deadline-nanos deadline})]
          (net/send-bytes transport (.getBytes "request" "UTF-8")
                          {:deadline-nanos deadline})
          (is (= "first"
                 (String. (net/recv-bytes transport
                                          {:deadline-nanos deadline})
                          "UTF-8")))
          (is (= "second"
                 (String. (net/recv-bytes transport
                                          {:deadline-nanos deadline})
                          "UTF-8")))
          (is (nil? (net/recv-bytes transport
                                    {:deadline-nanos deadline}))))))
    ;; The client connect timeout would end at 27,000,000ns, so the earlier
    ;; request deadline wins. Every subsequent operation receives that same
    ;; absolute value rather than a newly-created relative timeout.
    (is (= [[:connect "deadline.test" 8080
             {:deadline-nanos 20000000}]
            [:send :opaque-client "request"
             {:deadline-nanos 20000000}]
            [:receive :opaque-client 65536
             {:deadline-nanos 20000000}]
            [:receive :opaque-client 65536
             {:deadline-nanos 20000000}]
            [:receive :opaque-client 65536
             {:deadline-nanos 20000000}]]
           @calls))))

(deftest platform-carries-connect-and-read-deadlines-to-both-transports
  (let [calls (atom [])]
    (with-redefs [net/connect
                  (fn [host port opts]
                    (swap! calls conj [:plain host port opts])
                    :plain)
                  tls/tls-connect
                  (fn [host port insecure? opts]
                    (swap! calls conj
                           [:tls host port insecure? opts])
                    :tls)]
      (is (= :plain
             (@#'platform/connect-stream
               "plain.test" 80 false false 111 222 nil)))
      (is (= :tls
             (@#'platform/connect-stream
               "secure.test" 443 true true 333 444 nil)))
      (is (= :plain
             (@#'platform/connect-stream
               "unset.test" 80 false false nil nil nil)))
      (is (= :tls
             (@#'platform/connect-stream
               "zero.test" 443 true false 0 0 nil))))
    (is (= [[:plain "plain.test" 80
             {:connect-timeout-ms 111 :read-timeout-ms 222}]
            [:tls "secure.test" 443 true
             {:connect-timeout-ms 333 :read-timeout-ms 444}]
            [:plain "unset.test" 80
             {:connect-timeout-ms nil}]
            [:tls "zero.test" 443 false
             {:connect-timeout-ms nil}]]
           @calls))))

(deftest platform-creates-one-request-wide-deadline
  (let [events (atom [])
        chunks (atom [(.getBytes
                        "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n"
                        "UTF-8")
                      (.getBytes "ok" "UTF-8")
                      nil])
        client (tagged :jolt.http/client {:connect-timeout 100})
        request (tagged :jolt.http/request
                        {:uri "http://deadline.test/resource"
                         :method "GET"
                         :headers []
                         :timeout 25})
        response
        (with-redefs-fn
          {#'platform/monotonic-now (fn [] 5000)
           #'platform/connect-stream
           (fn [host port https? insecure? connect-timeout read-timeout deadline]
             (swap! events conj
                    [:connect host port https? insecure?
                     connect-timeout read-timeout deadline])
             :stream)
           #'platform/s-write
           (fn [_stream _data opts]
             (swap! events conj [:write opts])
             nil)
           #'platform/s-read
           (fn [_stream opts]
             (swap! events conj [:read opts])
             (let [chunk (first @chunks)]
               (swap! chunks #(vec (next %)))
               chunk))
           #'platform/s-close
           (fn [_stream]
             (swap! events conj [:close])
             nil)}
          (fn []
            (@#'platform/net-http-send
              client request :jolt.http/handler-bytes)))]
    (is (= 200 (jolt.host/ref-get response :status)))
    (is (= "ok"
           (String. (jolt.host/ref-get response :body) "UTF-8")))
    (is (= [[:connect "deadline.test" 80 false false
             100 nil 25005000]
            [:write {:deadline-nanos 25005000}]
            [:read {:deadline-nanos 25005000}]
            [:read {:deadline-nanos 25005000}]
            [:read {:deadline-nanos 25005000}]
            [:close]]
           @events))))

(deftest absent-http-request-timeout-is-unbounded
  (let [events (atom [])
        chunks (atom [(.getBytes
                        "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n"
                        "UTF-8")
                      nil])
        client (tagged :jolt.http/client {})
        request (tagged :jolt.http/request
                        {:uri "http://unbounded.test/"
                         :method "GET"
                         :headers []})]
    (with-redefs-fn
      {#'platform/monotonic-now
       (fn []
         (throw (ex-info "absent timeout must not create a deadline" {})))
       #'platform/connect-stream
       (fn [_host _port _https? _insecure? connect-timeout read-timeout deadline]
         (swap! events conj [:connect connect-timeout read-timeout deadline])
         :stream)
       #'platform/s-write
       (fn [_stream _data opts]
         (swap! events conj [:write opts]))
       #'platform/s-read
       (fn [_stream opts]
         (swap! events conj [:read opts])
         (let [chunk (first @chunks)]
           (swap! chunks #(vec (next %)))
           chunk))
       #'platform/s-close (fn [_] nil)}
      (fn []
        (@#'platform/net-http-send
          client request :jolt.http/handler-bytes)))
    (is (= [[:connect nil nil nil]
            [:write {}]
            [:read {}]
            [:read {}]]
           @events))))

(deftest request-deadline-does-not-restart-after-a-response-chunk
  (let [clock (atom 0)
        reads (atom 0)
        client (tagged :jolt.http/client {})
        request (tagged :jolt.http/request
                        {:uri "http://deadline.test/slow-body"
                         :method "GET"
                         :headers []
                         :timeout 2})
        exception
        (with-redefs-fn
          {#'platform/monotonic-now (fn [] @clock)
           #'platform/connect-stream (fn [& _] :stream)
           #'platform/s-write (fn [& _] nil)
           #'platform/s-read
           (fn [_stream opts]
             (swap! reads inc)
             (is (= {:deadline-nanos 2000000} opts))
             ;; One chunk arrives just as the one request-wide budget expires.
             ;; The next loop turn must fail before attempting another read.
             (reset! clock 2000000)
             (.getBytes "HTTP/1.1 200 OK\r\n" "UTF-8"))
           #'platform/s-close (fn [_] nil)}
          (fn []
            (caught
              #(@#'platform/net-http-send
                 client request :jolt.http/handler-bytes))))]
    (is (= java.net.http.HttpTimeoutException (class exception)))
    (is (= 1 @reads))
    (is (= :timed-out
           (:jolt.http/kind (ex-data (ex-cause exception)))))))
