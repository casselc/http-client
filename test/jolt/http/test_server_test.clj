(ns jolt.http.test-server-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.http.test-server :as server]))

(deftest stop-unblocks-and-joins-accept-loop
  (let [handle (server/start-plain)]
    (server/stop handle)
    (is (future-done? (:acceptor handle)))))
