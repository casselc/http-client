(ns jolt.http.zlib-test
  "Isolated libz round-trip check (no clj-http-lite / sockets)."
  (:require [jolt.ffi :as ffi]
            [jolt.http.zlib :as zlib]))

(def failures (atom 0))
(defn check [label ok?]
  (if ok? (println "  ok  " label)
      (do (swap! failures inc) (println "  FAIL" label))))

(defn- ->ba [^String s] (byte-array (.getBytes s "UTF-8")))
(defn- ba= [a b] (= (seq a) (seq b)))

(defn -main [& _]
  (println "jolt.http.zlib over libz")
  ;; The four-argument API is (pointer type value offset). Both trailing
  ;; arguments are integers here so this detects accidentally restoring the
  ;; pre-0.8 offset-first spelling, which otherwise writes valid-looking data
  ;; to the wrong address without a type error.
  (let [p (ffi/alloc 128)]
    (try
      (ffi/write p :uint8 0x5a 3)
      (check "ffi/write places value before offset" (= 0x5a (ffi/read p :uint8 3)))
      (finally (ffi/free p))))
  (let [msg (apply str (repeat 200 "the quick brown fox jumps over the lazy dog. "))
        src (->ba msg)]
    ;; gzip framing: output starts with the gzip magic 1f 8b and round-trips.
    (let [gz (zlib/gzip src)]
      (check "gzip emits gzip magic 0x1f8b" (and (= 0x1f (bit-and (aget gz 0) 0xff))
                                                 (= 0x8b (bit-and (aget gz 1) 0xff))))
      (check "gzip is smaller than source" (< (alength gz) (alength src)))
      (check "gunzip(gzip(x)) == x" (ba= src (zlib/gunzip gz))))
    ;; zlib (deflate w/ header) round-trips
    (let [z (zlib/zlib-deflate src)]
      (check "zlib-inflate(zlib-deflate(x)) == x" (ba= src (zlib/zlib-inflate z))))
    ;; empty input
    (check "gunzip(gzip(empty)) == empty" (ba= (->ba "") (zlib/gunzip (zlib/gzip (->ba ""))))))
  (println (if (zero? @failures) "\nall passed" (str "\n" @failures " FAILED")))
  (when (pos? @failures) (throw (ex-info "zlib test failures" {:n @failures}))))
