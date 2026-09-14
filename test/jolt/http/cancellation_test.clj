(ns jolt.http.cancellation-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.http-client :as http]
            [jolt.http.net :as net]
            [jolt.socket]))

(defn- close-quietly [value]
  (when value
    (try (.close value) (catch Throwable _ nil))))

(defn- read-request-head! [socket]
  (let [input (.getInputStream socket)]
    (loop [tail ""]
      (let [octet (.read input)]
        (if (neg? octet)
          (throw (ex-info "peer closed before sending a request" {}))
          (let [tail (str tail (char octet))]
            (if (.endsWith tail "\r\n\r\n")
              true
              (recur (if (> (count tail) 4) (subs tail 1) tail)))))))))

(deftest an-interrupt-unblocks-a-parked-read
  (let [listener (java.net.ServerSocket. 0)
        request-received (promise)
        peer (atom nil)
        _acceptor (future
                    (try
                      (let [socket (.accept listener)]
                        (reset! peer socket)
                        (read-request-head! socket)
                        (deliver request-received true))
                      (catch Throwable error
                        (deliver request-received error))))
        outcome (promise)
        worker (Thread.
                (fn []
                  (deliver outcome
                           (try
                             (http/get
                              (str "http://127.0.0.1:"
                                   (.getLocalPort listener)
                                   "/get")
                              {:socket-timeout 10000})
                             :returned
                             (catch Throwable error (class error))))))]
    (try
      (.start worker)
      (let [received (deref request-received 3000 ::not-received)]
        (is (= true received)
            (str "the peer must read the complete request before cancellation: "
                 received))
        (let [started (System/currentTimeMillis)]
          (.interrupt worker)
          (let [result (deref outcome 2000 ::still-blocked)
                elapsed (- (System/currentTimeMillis) started)]
            (is (= java.lang.InterruptedException result)
                (str "blocked read returned " result))
            (is (< elapsed 1500)
                (str "interrupt exceeded the read-slice bound: " elapsed " ms")))))
      (finally
        (close-quietly @peer)
        (close-quietly listener)
        (.join worker 2000)))))

(deftest interruption-happens-after-the-response-wait-is-entered
  (let [poll-var (ns-resolve 'jolt.http.net 'c-poll)
        original-poll @poll-var
        wait-entered (promise)
        release-wait (promise)]
    (with-redefs-fn
      {poll-var
       (fn [pollfd count timeout]
         (if (= net/interrupt-slice-ms timeout)
           (do
             (deliver wait-entered true)
             (deref release-wait 3000 nil)
             0)
           (original-poll pollfd count timeout)))}
      (fn []
        (let [listener (java.net.ServerSocket. 0)
              request-received (promise)
              peer (atom nil)
              _acceptor (future
                          (try
                            (let [socket (.accept listener)]
                              (reset! peer socket)
                              (read-request-head! socket)
                              (deliver request-received true))
                            (catch Throwable error
                              (deliver request-received error))))
              outcome (promise)
              worker (Thread.
                      (fn []
                        (deliver outcome
                                 (try
                                   (http/get
                                    (str "http://127.0.0.1:"
                                         (.getLocalPort listener)
                                         "/get")
                                    {:socket-timeout 10000})
                                   :returned
                                   (catch Throwable error (class error))))))]
          (try
            (.start worker)
            (is (= true (deref request-received 3000 ::not-received))
                "the peer must consume the complete request")
            (is (= true (deref wait-entered 3000 ::wait-not-entered))
                "the selected provider must enter its response-read wait")
            (.interrupt worker)
            (deliver release-wait true)
            (is (= java.lang.InterruptedException
                   (deref outcome 2000 ::still-blocked)))
            (finally
              (deliver release-wait true)
              (close-quietly @peer)
              (close-quietly listener)
              (.join worker 2000))))))))
