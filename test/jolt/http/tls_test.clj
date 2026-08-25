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
