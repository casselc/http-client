(ns jolt.http.timeout-runner
  "Runs the https regressions (jolt.http.timeout-test) on their own.

  Kept out of the main runner deliberately. These tests park a connection on
  purpose and stand up their own servers, while that suite requires its
  namespaces in a fixed order so integration-test's (use-fixtures :once
  with-server) wins, and serves on a SERIAL accept loop — one connection at a
  time. Isolating a deliberately-stalled peer from that is cheap insurance.

  Note for anyone reading a red main suite: self-signed-ssl-get is flaky
  independently of this work. Three consecutive runs at the unmodified tree gave
  112/1/1, 113/0/1, 112/1/1. Do not read it as a regression from these tests."
  (:require [jolt.http.platform]                 ;; installs the host shims
            [clojure.test :as t]
            [jolt.http.timeout-test]))

(defn -main [& _]
  (let [r (t/run-tests 'jolt.http.timeout-test)]
    (println (str "\n========== TOTAL =========="))
    (println (str "tests=" (:test r) " pass=" (:pass r) " fail=" (:fail r) " error=" (:error r)))
    (when (or (pos? (:fail r)) (pos? (:error r)))
      (throw (ex-info "suite failures" (select-keys r [:fail :error]))))))
