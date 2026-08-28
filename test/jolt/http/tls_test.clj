(ns jolt.http.tls-test
  "Memory-BIO error ordering and TLS EOF classification."
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.ffi :as ffi]
            [jolt.http.net :as net]
            [jolt.http.tls :as tls]))

(defn- caught [f]
  (try
    (f)
    nil
    (catch :default exception exception)))

(defn- inert-transport
  ([receive-fn] (inert-transport receive-fn (fn [_] nil)))
  ([receive-fn send-fn]
   (net/callback-transport
     {:send-fn (fn [data _opts] (send-fn data))
      :receive-fn (fn [_opts] (receive-fn))
      :close-fn (fn [] true)})))

(defn- event-count [events event]
  (count (filter #(= event %) events)))

(deftest ssl-error-is-captured-before-any-bio-work
  (let [events (atom [])
        result
        (with-redefs-fn
          {#'tls/c-ERR-clear-error
           (fn [] (swap! events conj :clear))
           #'tls/c-SSL-get-error
           (fn [ssl ret]
             (swap! events conj [:get-error ssl ret])
             2)}
          (fn []
            (@#'tls/ssl-io-call
              :ssl
              (fn []
                (swap! events conj :ssl-read-or-write)
                -1))))]
    (is (= [-1 2] result))
    (is (= [:clear
            :ssl-read-or-write
            [:get-error :ssl -1]]
           @events))))

(deftest only-close-notify-is-clean-tls-eof
  (is (= :data (@#'tls/read-action 1 nil)))
  (is (= :eof
         (@#'tls/read-action 0 @#'tls/ZERO-RETURN)))
  (is (= :want-read
         (@#'tls/read-action -1 @#'tls/WANT-READ)))
  (is (= :want-write
         (@#'tls/read-action -1 @#'tls/WANT-WRITE)))
  (is (= :fatal
         (@#'tls/read-action -1 @#'tls/SSL-ERROR-SSL)))
  (is (= :fatal
         (@#'tls/read-action 0 @#'tls/SSL-ERROR-SYSCALL)))
  (is (= :fatal (@#'tls/read-action -1 99))))

(deftest fatal-ssl-read-is-not-reclassified-as-eof
  (let [events (atom [])
        st (@#'tls/make-stream
             (inert-transport (fn [] nil))
             :ssl :ctx :rbio :wbio)
        exception
        (with-redefs-fn
          {#'ffi/alloc (fn [_] 1000)
           #'ffi/free (fn [_] nil)
           #'tls/c-ERR-clear-error
           (fn [] (swap! events conj :clear))
           #'tls/c-SSL-read
           (fn [& _] (swap! events conj :ssl-read) -1)
           #'tls/c-SSL-get-error
           (fn [& _] (swap! events conj :get-error)
             @#'tls/SSL-ERROR-SSL)}
          (fn []
            (caught #((jolt.host/ref-get st :read) st nil))))]
    (is (= javax.net.ssl.SSLException (class exception)))
    (is (= [:clear :ssl-read :get-error] @events))
    (is (false? (jolt.host/ref-get st :eof)))))

(deftest transport-eof-without-close-notify-is-truncation
  (let [events (atom [])
        st (@#'tls/make-stream
             (inert-transport
               (fn [] (swap! events conj :transport-eof) nil))
             :ssl :ctx :rbio :wbio)
        exception
        (with-redefs-fn
          {#'ffi/alloc (fn [_] 1000)
           #'ffi/free (fn [_] nil)
           #'tls/c-ERR-clear-error
           (fn [] (swap! events conj :clear))
           #'tls/c-SSL-read
           (fn [& _] (swap! events conj :ssl-read) -1)
           #'tls/c-SSL-get-error
           (fn [& _] (swap! events conj :get-error)
             @#'tls/WANT-READ)
           #'tls/c-BIO-ctrl
           (fn [& _] (swap! events conj :bio-pending) 0)}
          (fn []
            (caught #((jolt.host/ref-get st :read) st nil))))]
    (is (= javax.net.ssl.SSLException (class exception)))
    (is (= [:clear :ssl-read :get-error :bio-pending :transport-eof]
           @events))
    (is (false? (jolt.host/ref-get st :eof)))))

(deftest ssl-write-captures-error-before-flush
  (let [events (atom [])
        st (@#'tls/make-stream
             (inert-transport (fn [] nil))
             :ssl :ctx :rbio :wbio)
        exception
        (with-redefs-fn
          {#'ffi/alloc (fn [_] 1000)
           #'ffi/free (fn [_] nil)
           #'ffi/write-array (fn [& _] nil)
           #'tls/c-ERR-clear-error
           (fn [] (swap! events conj :clear))
           #'tls/c-SSL-write
           (fn [& _] (swap! events conj :ssl-write) -1)
           #'tls/c-SSL-get-error
           (fn [& _] (swap! events conj :get-error)
             @#'tls/SSL-ERROR-SSL)
           #'tls/c-BIO-ctrl
           (fn [& _] (swap! events conj :bio-pending) 0)}
          (fn []
            (caught #((jolt.host/ref-get st :write)
                       st (.getBytes "x" "UTF-8") {}))))]
    (is (= javax.net.ssl.SSLException (class exception)))
    (is (= [:clear :ssl-write :get-error :bio-pending]
           @events))))

(deftest clean-close-notify-is-sticky-eof
  (let [calls (atom 0)
        st (@#'tls/make-stream
             (inert-transport (fn [] nil))
             :ssl :ctx :rbio :wbio)]
    (with-redefs-fn
      {#'ffi/alloc (fn [_] 1000)
       #'ffi/free (fn [_] nil)
       #'tls/c-ERR-clear-error (fn [] nil)
       #'tls/c-SSL-read
       (fn [& _] (swap! calls inc) 0)
       #'tls/c-SSL-get-error
       (fn [& _] @#'tls/ZERO-RETURN)
       #'tls/c-BIO-ctrl (fn [& _] 0)}
      (fn []
        (is (nil? ((jolt.host/ref-get st :read) st nil)))
        (is (nil? ((jolt.host/ref-get st :read) st nil)))))
    (is (= 1 @calls))
    (is (true? (jolt.host/ref-get st :eof)))))

(deftest repeated-and-concurrent-close-releases-tls-engine-once
  (let [events (atom [])
        transport
        (net/callback-transport
          {:send-fn (fn [_data _opts] nil)
           :receive-fn (fn [_opts] nil)
           :close-fn (fn [] (swap! events conj :transport-close))})
        st (@#'tls/make-stream transport :ssl :ctx :rbio :wbio)
        close! (jolt.host/ref-get st :close)]
    (with-redefs-fn
      {#'tls/c-ERR-clear-error
       (fn [] (swap! events conj :clear))
       #'tls/c-SSL-shutdown
       (fn [_] (swap! events conj :ssl-shutdown) 1)
       #'tls/c-BIO-ctrl
       (fn [& _] 0)
       #'tls/c-SSL-free
       (fn [_] (swap! events conj :ssl-free))
       #'tls/c-SSL-CTX-free
       (fn [_] (swap! events conj :ctx-free))}
      (fn []
        (let [start (promise)
              workers (doall
                        (for [_ (range 16)]
                          (future @start (close!))))]
          (deliver start true)
          (doseq [worker workers] @worker)
          ;; A call after every contender has completed must also be inert.
          (close!))))
    (is (= 1 (event-count @events :clear)))
    (is (= 1 (event-count @events :ssl-shutdown)))
    (is (= 1 (event-count @events :transport-close)))
    (is (= 1 (event-count @events :ssl-free)))
    (is (= 1 (event-count @events :ctx-free)))))

(deftest partial-bio-acquisition-frees-only-owned-resources
  (let [events (atom [])
        bio-results (atom [:rbio :null])
        exception
        (with-redefs-fn
          {#'ffi/null? #(= :null %)
           #'tls/c-SSL-CTX-new (fn [_] :ctx)
           #'tls/c-SSL-new (fn [_] :ssl)
           #'tls/c-BIO-s-mem (fn [] :bio-method)
           #'tls/c-BIO-new
           (fn [_]
             (let [result (first @bio-results)]
               (swap! bio-results subvec 1)
               result))
           #'tls/c-BIO-free
           (fn [bio] (swap! events conj [:bio-free bio]) 1)
           #'tls/c-SSL-free
           (fn [ssl] (swap! events conj [:ssl-free ssl]))
           #'tls/c-SSL-CTX-free
           (fn [ctx] (swap! events conj [:ctx-free ctx]))}
          (fn []
            (caught
              #(@#'tls/acquire-engine!
                 :method (fn [_] nil) (fn [_] nil)))))]
    (is (= javax.net.ssl.SSLException (class exception)))
    (is (= [[:bio-free :rbio]
            [:ssl-free :ssl]
            [:ctx-free :ctx]]
           @events))))

(deftest ssl-owns-bios-after-set-bio-transfer
  (let [events (atom [])
        bio-results (atom [:rbio :wbio])
        exception
        (with-redefs-fn
          {#'ffi/null? (constantly false)
           #'tls/c-SSL-CTX-new (fn [_] :ctx)
           #'tls/c-SSL-new (fn [_] :ssl)
           #'tls/c-BIO-s-mem (fn [] :bio-method)
           #'tls/c-BIO-new
           (fn [_]
             (let [result (first @bio-results)]
               (swap! bio-results subvec 1)
               result))
           #'tls/c-SSL-set-bio
           (fn [ssl rbio wbio]
             (swap! events conj [:set-bio ssl rbio wbio]))
           #'tls/c-BIO-free
           (fn [bio] (swap! events conj [:bio-free bio]) 1)
           #'tls/c-SSL-free
           (fn [ssl] (swap! events conj [:ssl-free ssl]))
           #'tls/c-SSL-CTX-free
           (fn [ctx] (swap! events conj [:ctx-free ctx]))}
          (fn []
            (caught
              #(@#'tls/acquire-engine!
                 :method
                 (fn [_] nil)
                 (fn [_]
                   (throw (ex-info "configuration failed after transfer" {})))))))]
    (is (= "configuration failed after transfer" (ex-message exception)))
    (is (= [[:set-bio :ssl :rbio :wbio]
            [:ssl-free :ssl]
            [:ctx-free :ctx]]
           @events))
    (is (zero? (event-count @events [:bio-free :rbio])))
    (is (zero? (event-count @events [:bio-free :wbio])))))
