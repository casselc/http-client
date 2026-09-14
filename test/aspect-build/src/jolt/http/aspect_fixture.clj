(ns jolt.http.aspect-fixture
  (:require [clj-http.lite.core :as http]))

(def aspect-provider
  {:schema 1
   :libraries {'jolt-lang/http-client
               "v0.0.10+http-client-core-aspect.1"}
   :roles {:http/client
           {:fn 'jolt.http.aspect-fixture/around-request
            :contract :args-v1}}})

(defn around-request [_join-point _evaluated-args proceed]
  (proceed))

(defn- request-one [request]
  (http/request request))

(defn- request-two [request]
  (http/request request))

(defn -main [& args]
  ;; Both calls remain reachable so changing the exact entry selector to a call
  ;; selector is a real over-match mutant. The gate builds but never runs this
  ;; fixture, so it performs no network request.
  (if (seq args)
    (request-one {:method :get :url (first args)})
    (request-two {:method :get :url "http://127.0.0.1/"})))
