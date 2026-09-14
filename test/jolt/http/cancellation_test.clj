(ns jolt.http.cancellation-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.http-client :as http]
            [jolt.http.core :as core]
            [jolt.http.jdk]
            [jolt.http.net :as net]
            [jolt.http.platform]
            [jolt.io-poller]
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
        release-wait (promise)
        request-consumed? (atom false)]
    (with-redefs-fn
      {poll-var
       (fn [pollfd count timeout]
         (if (and @request-consumed? (= net/interrupt-slice-ms timeout))
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
                              (reset! request-consumed? true)
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

(defn- private-var [ns-name sym]
  (or (ns-resolve ns-name sym)
      (throw (ex-info "missing private test seam" {:namespace ns-name :symbol sym}))))

(defn- interrupted-class [f]
  (try (f) :returned (catch Throwable error (class error))))

(deftest an-unbounded-connect-is-sliced-and-interruptible
  (let [connect-var (private-var 'jolt.http.net 'c-connect)
        poll-var (private-var 'jolt.http.net 'c-poll)
        close-var (private-var 'jolt.http.net 'c-close)
        original-close @close-var
        connect-calls (atom 0)
        close-calls (atom 0)
        poll-timeouts (atom [])
        wait-entered (promise)
        release-wait (promise)
        outcome (promise)]
    (with-redefs-fn
      {connect-var (fn [& _] (swap! connect-calls inc) -1)
       poll-var (fn [_ _ timeout]
                  (swap! poll-timeouts conj timeout)
                  (deliver wait-entered true)
                  (deref release-wait 3000 nil)
                  0)
       close-var (fn [fd]
                   (swap! close-calls inc)
                   (original-close fd))}
      (fn []
        (let [worker (Thread.
                      (fn []
                        (deliver outcome
                                 (interrupted-class
                                  #(net/connect "localhost" 9 nil)))))]
          (try
            (.start worker)
            (is (= true (deref wait-entered 3000 ::wait-not-entered))
                "nil timeout must still reach the sliced non-blocking wait")
            (.interrupt worker)
            (deliver release-wait true)
            (is (= java.lang.InterruptedException
                   (deref outcome 2000 ::still-blocked)))
            (is (= [net/interrupt-slice-ms] @poll-timeouts)
                "an unbounded connect waits in one bounded slice before observing interrupt")
            (is (= 1 @connect-calls)
                "interruption must not advance to the next resolved address")
            (is (= 1 @close-calls)
                "the in-progress address fd remains connect's exact-once responsibility")
            (finally
              (deliver release-wait true)
              (.join worker 2000))))))))

(deftest a-bounded-connect-never-polls-for-the-whole-timeout
  (let [connect-var (private-var 'jolt.http.net 'c-connect)
        poll-var (private-var 'jolt.http.net 'c-poll)
        close-var (private-var 'jolt.http.net 'c-close)
        original-close @close-var
        poll-timeouts (atom [])
        wait-entered (promise)
        release-wait (promise)
        outcome (promise)]
    (with-redefs-fn
      {connect-var (fn [& _] -1)
       poll-var (fn [_ _ timeout]
                  (swap! poll-timeouts conj timeout)
                  (deliver wait-entered true)
                  (deref release-wait 3000 nil)
                  0)
       close-var original-close}
      (fn []
        (let [worker (Thread.
                      (fn []
                        (deliver outcome
                                 (interrupted-class
                                  #(net/connect "localhost" 9 10000)))))]
          (try
            (.start worker)
            (is (= true (deref wait-entered 3000 ::wait-not-entered)))
            (is (= net/interrupt-slice-ms (first @poll-timeouts))
                "a long connect timeout must not become one long native poll")
            (.interrupt worker)
            (deliver release-wait true)
            (is (= java.lang.InterruptedException
                   (deref outcome 2000 ::still-blocked)))
            (finally
              (deliver release-wait true)
              (.join worker 2000))))))))

(deftest a-partial-write-waits-for-writability-and-observes-interrupt
  (let [fcntl-get-var (private-var 'jolt.http.net 'c-fcntl-get)
        fcntl-set-var (private-var 'jolt.http.net 'c-fcntl-set)
        send-var (private-var 'jolt.http.net 'c-send)
        poll-var (private-var 'jolt.http.net 'c-poll)
        errno-var (private-var 'jolt.io-poller 'errno)
        eagain (var-get (private-var 'jolt.http.net 'eagain))
        calls (atom 0)
        lengths (atom [])
        set-flags (atom [])
        wait-entered (promise)
        release-wait (promise)
        outcome (promise)]
    (with-redefs-fn
      {fcntl-get-var (fn [_ _] 0)
       fcntl-set-var (fn [_ _ flags] (swap! set-flags conj flags) 0)
       send-var (fn [_ _ length _]
                  (swap! lengths conj length)
                  (if (= 1 (swap! calls inc)) 3 -1))
       errno-var (fn [] eagain)
       poll-var (fn [_ _ timeout]
                  (deliver wait-entered true)
                  (deref release-wait 3000 nil)
                  0)}
      (fn []
        (let [worker (Thread.
                      (fn []
                        (deliver outcome
                                 (interrupted-class
                                  #(net/send-bytes 41 (byte-array (range 8)))))))]
          (try
            (.start worker)
            (is (= true (deref wait-entered 3000 ::wait-not-entered))
                "EAGAIN after a partial write must enter the bounded POLLOUT wait")
            (.interrupt worker)
            (deliver release-wait true)
            (is (= java.lang.InterruptedException
                   (deref outcome 2000 ::still-blocked)))
            (is (= [8 5] @lengths) "the second send resumes at the unsent suffix")
            (is (= 2 @calls) "no send occurs after interruption")
            (is (= 1 (count @set-flags))
                "a failed write is made non-blocking but is never restored for reuse")
            (finally
              (deliver release-wait true)
              (.join worker 2000))))))))

(deftest a-successful-partial-write-restores-blocking-mode
  (let [fcntl-get-var (private-var 'jolt.http.net 'c-fcntl-get)
        fcntl-set-var (private-var 'jolt.http.net 'c-fcntl-set)
        send-var (private-var 'jolt.http.net 'c-send)
        poll-var (private-var 'jolt.http.net 'c-poll)
        errno-var (private-var 'jolt.io-poller 'errno)
        eagain (var-get (private-var 'jolt.http.net 'eagain))
        nonblock (var-get (private-var 'jolt.http.net 'o-nonblock))
        calls (atom 0)
        lengths (atom [])
        set-flags (atom [])]
    (with-redefs-fn
      {fcntl-get-var (fn [_ _] 0)
       fcntl-set-var (fn [_ _ flags] (swap! set-flags conj flags) 0)
       send-var (fn [_ _ length _]
                  (swap! lengths conj length)
                  (case (swap! calls inc) 1 3, 2 -1, length))
       errno-var (fn [] eagain)
       poll-var (fn [_ _ timeout]
                  (is (= net/interrupt-slice-ms timeout))
                  1)}
      (fn []
        (is (nil? (net/send-bytes 41 (byte-array (range 8)))))
        (is (= [8 5 5] @lengths))
        (is (= [nonblock 0] @set-flags)
            "success enters non-blocking mode and restores the original flags")))))

(deftest interruption-wins-before-an-expired-readiness-deadline
  (let [await-var (private-var 'jolt.http.net 'await-ready!)
        pollout (var-get (private-var 'jolt.http.net 'po-pollout))
        poll-var (private-var 'jolt.http.net 'c-poll)
        polls (atom 0)]
    (with-redefs-fn
      {poll-var (fn [& _] (swap! polls inc) 0)}
      (fn []
        (try
          (.interrupt (Thread/currentThread))
          (is (= java.lang.InterruptedException
                 (interrupted-class #(@await-var 41 pollout 0 "connect"))))
          (is (zero? @polls) "an expired deadline cannot hide an interrupt")
          (finally
            ;; The production check normally clears it; keep the runner clean if
            ;; the assertion itself fails before reaching that check.
            (Thread/interrupted)))))))

(deftest poll-errno-is-captured-before-the-post-poll-clock-read
  (let [await-var (private-var 'jolt.http.net 'await-ready!)
        pollout (var-get (private-var 'jolt.http.net 'po-pollout))
        poll-var (private-var 'jolt.http.net 'c-poll)
        errno-var (private-var 'jolt.io-poller 'errno)
        clock-var (private-var 'jolt.http.net 'current-time-ms)
        eintr (var-get (private-var 'jolt.http.net 'eintr))
        events (atom [])
        polls (atom 0)]
    (with-redefs-fn
      {clock-var (fn [] (swap! events conj :clock) 100)
       poll-var (fn [& _]
                  (swap! events conj :poll)
                  (if (= 1 (swap! polls inc)) -1 1))
       errno-var (fn [] (swap! events conj :errno) eintr)}
      (fn []
        (is (nil? (@await-var 41 pollout 1000 "connect")))
        (is (= [:clock :poll :errno :clock :clock :poll]
               @events)
            "errno is read before any post-poll clock/runtime call")))))

(deftest proxy-connect-failure-closes-once-and-preserves-the-primary
  (let [proxy-connect-var (private-var 'jolt.http.jdk 'proxy-connect!)
        send-var (private-var 'jolt.http.net 'send-bytes)
        recv-var (private-var 'jolt.http.net 'recv-bytes)
        close-var (private-var 'jolt.http.net 'close)]
    (doseq [failure-at [:send :recv]]
      (let [primary (ex-info (str failure-at " interrupted") {:at failure-at})
            closes (atom 0)
            receives (atom 0)
            caught
            (with-redefs-fn
              {send-var (fn [& _]
                          (when (= :send failure-at) (throw primary))
                          nil)
               recv-var (fn [& _]
                          (swap! receives inc)
                          (throw primary))
               close-var (fn [_]
                           (swap! closes inc)
                           (throw (ex-info "cleanup failed" {})))}
              (fn []
                (try
                  (@proxy-connect-var 41 "origin.example" 443)
                  :returned
                  (catch Throwable error error))))]
        (is (identical? primary caught)
            (str failure-at " cleanup cannot replace the primary failure"))
        (is (= 1 @closes) (str failure-at " closes the owned fd exactly once"))
        (is (= (if (= :recv failure-at) 1 0) @receives)
            (str failure-at " performs no transport operation after failure"))))))

(deftest interrupted-pooled-write-closes-once-and-is-never-replayed
  (let [perform-var (private-var 'jolt.http.platform 'perform!)
        open-var (private-var 'jolt.http.platform 'connect-stream)
        idle-var (private-var 'jolt.http.net 'idle-dead?)
        timeout-var (private-var 'jolt.http.net 'set-read-timeout!)
        url (core/parse-url "http://localhost:18080/upload")
        key (core/pool-key "localhost" 18080 false false nil nil)
        opens (atom 0)
        closes (atom 0)
        writes (atom 0)
        stream (core/tt :test/interrupted-write-stream)
        conn (doto (core/tt :jolt/http-url-connection)
               (core/tput! :url url)
               (core/tput! :method "POST")
               (core/tput! :req-headers [])
               (core/tput! :do-output true)
               (core/tput! :out-buffer (core/make-baos))
               (core/tput! :follow-redirects false)
               (core/tput! :read-timeout nil)
               (core/tput! :connect-timeout nil)
               (core/tput! :insecure false)
               (core/tput! :performed false)
               (core/tput! :response nil))]
    (core/tput! stream :write
                (fn [_ _]
                  (swap! writes inc)
                  (core/throw-typed "java.lang.InterruptedException"
                                    "write interrupted")))
    (core/tput! stream :sock 41)
    (core/tput! stream :close (fn [& _] (swap! closes inc) nil))
    (core/tput! stream :read (fn [& _] (throw (ex-info "unexpected read" {}))))
    (core/pool-clear!)
    (try
      (core/pool-release! key stream)
      (with-redefs-fn
        {open-var (fn [& _] (swap! opens inc) (throw (ex-info "unexpected replay" {})))
         idle-var (fn [_] false)
         timeout-var (fn [& _] nil)}
        (fn []
          (is (= java.lang.InterruptedException
                 (interrupted-class #(@perform-var conn))))))
      (is (= 1 @writes))
      (is (= 1 @closes) "the interrupted pooled stream is retired exactly once")
      (is (zero? @opens) "even a pre-response POST interruption is never replayed")
      (is (zero? (core/pool-count)))
      (finally (core/pool-clear!)))))
