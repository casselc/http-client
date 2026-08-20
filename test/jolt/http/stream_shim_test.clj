(ns jolt.http.stream-shim-test
  "The ByteArrayInputStream/ByteArrayOutputStream shims installed by
  jolt.http.platform replace jolt's own PROCESS-WIDE, so any app that merely
  requires this library gets them for every (ByteArrayInputStream. …) it writes,
  HTTP-related or not. They therefore owe the whole InputStream surface, not just
  what this client calls. Expected values are JVM Clojure's."
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.http.platform])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]))

(defn- mk [] (ByteArrayInputStream. (.getBytes "hello" "UTF-8")))
(def ^:private all [104 101 108 108 111])

(deftest read-all-bytes
  (is (= all (vec (.readAllBytes (mk)))))
  (testing "reads from the current position, not the start"
    (let [s (mk)] (.read s) (is (= [101 108 108 111] (vec (.readAllBytes s))))))
  (testing "an exhausted stream yields an empty array, not nil or -1"
    (let [s (mk)] (.readAllBytes s) (is (= [] (vec (.readAllBytes s)))))))

(deftest read-n-bytes
  (is (= [104 101 108] (vec (.readNBytes (mk) 3))))
  (testing "asking for more than remains yields what remains"
    (is (= all (vec (.readNBytes (mk) 99)))))
  (testing "a negative count is an IllegalArgumentException"
    (is (thrown? IllegalArgumentException (.readNBytes (mk) -1))))
  (testing "the 3-arg arity fills a buffer and returns the count"
    (let [buf (byte-array 8)
          n (.readNBytes (mk) buf 1 3)]
      (is (= 3 n))
      (is (= [0 104 101 108 0] (vec (take 5 buf)))))))

(deftest transfer-to
  (let [o (ByteArrayOutputStream.)
        n (.transferTo (mk) o)]
    (is (= 5 n))
    (is (= all (vec (.toByteArray o)))))
  (testing "an exhausted stream transfers nothing"
    (let [s (mk) o (ByteArrayOutputStream.)]
      (.readAllBytes s)
      (is (= 0 (.transferTo s o)))
      (is (= [] (vec (.toByteArray o)))))))

(deftest skip-and-available
  (let [s (mk)]
    (is (= 2 (.skip s 2)))
    (is (= 108 (.read s))))
  (testing "skip never runs past the end"
    (is (= 5 (.skip (mk) 99))))
  (is (= 5 (.available (mk)))))

(deftest mark-and-reset
  (is (true? (.markSupported (mk))))
  (let [s (mk)]
    (.read s) (.mark s 0) (.read s) (.reset s)
    (is (= 101 (.read s))))
  (testing "reset with no mark returns to the start"
    (let [s (mk)] (.read s) (.reset s) (is (= 104 (.read s))))))

(deftest read-contract-unchanged
  (testing "no-arg read is an unsigned byte, -1 at EOF"
    (let [s (ByteArrayInputStream. (byte-array [-1]))]
      (is (= 255 (.read s)))
      (is (= -1 (.read s)))))
  (testing "buffer read fills signed bytes"
    (let [s (ByteArrayInputStream. (byte-array [-1 2]))
          buf (byte-array 2)]
      (is (= 2 (.read s buf 0 2)))
      (is (= [-1 2] (vec buf))))))
