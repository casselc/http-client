(ns jolt.http.cancellation-runner
  "Runs only the hermetic blocked-read interruption regression."
  (:require [clojure.test :as t]
            [jolt.http.timeout-test :as timeout]))

(defn -main [& _]
  (binding [t/*report-counters* (ref t/*initial-report-counters*)]
    (t/test-vars [#'timeout/an-interrupt-unblocks-a-parked-read])
    (let [{:keys [test pass fail error] :as result} @t/*report-counters*]
      (println (str "tests=" test " pass=" pass
                    " fail=" fail " error=" error))
      (when-not (t/successful? result)
        (throw (ex-info "cancellation regression failed"
                        (select-keys result [:fail :error])))))))
