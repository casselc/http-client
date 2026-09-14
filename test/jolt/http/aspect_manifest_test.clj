(ns jolt.http.aspect-manifest-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(def ^:private resource-name
  "META-INF/jolt/aspects/http-client-core.edn")

(def ^:private expected-manifest
  {:schema 1
   :library {:id 'jolt-lang/http-client
             :version "v0.0.10+http-client-core-aspect.1"}
   :aspects
   [{:id :http-client.core/request
     :match {:entry 'clj-http.lite.core/request
             :arity 1}
     :advice-role :http/client
     :expect {:matches 1}}]})

(deftest provider-neutral-aspect-manifest-is-packaged
  (let [resource (io/resource resource-name)
        text (some-> resource slurp)
        manifest (some-> text edn/read-string)]
    (is (some? resource))
    (is (= expected-manifest manifest))
    (is (or (nil? text)
            (not (.contains (.toLowerCase text) "otel"))))))
