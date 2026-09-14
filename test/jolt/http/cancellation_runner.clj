(ns jolt.http.cancellation-runner
  "Runs only the hermetic blocked-read interruption regression."
  (:require [clojure.test :as t]
            [jolt.http.cancellation-test]))

(defn -main [& _]
  (let [{:keys [test pass fail error] :as result}
        (t/run-tests 'jolt.http.cancellation-test)]
    (println (str "tests=" test " pass=" pass
                  " fail=" fail " error=" error))
    (when-not (t/successful? result)
      (throw (ex-info "cancellation regression failed"
                      (select-keys result [:fail :error]))))))
