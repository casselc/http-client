(ns clj-http.lite.test-util.server-process
  "Jolt stand-in for clj-http-lite's server-process fixture. The upstream version
  shells out to a Jetty subprocess; here `launch` starts in-process plaintext +
  TLS servers (jolt.http.test-server, over jolt.ffi sockets + OpenSSL) serving the
  same routes, and returns their ports. `kill` stops them."
  (:require [jolt.http.test-server :as srv]))

(def ^:private http-port 18091)
(def ^:private https-port 18092)

(defn launch []
  (let [pwd  (or (jolt.host/getenv "JOLT_PWD")
                 (System/getProperty "user.dir")
                 (jolt.host/getenv "PWD"))
        cert (str pwd "/test/resources/cert.pem")
        key  (str pwd "/test/resources/key.pem")
        _ (doseq [path [cert key]]
            (when-not (.exists (java.io.File. path))
              (throw (ex-info (str "TLS test fixture not found: " path)
                              {:path path}))))
        plain (srv/start-plain http-port)
        tls   (srv/start-tls https-port cert key)]
    ;; No readiness sleep: start-plain / start-tls call bind(2) and listen(2)
    ;; synchronously and only then spawn the accept loop, so the socket is
    ;; already listening when they return, and the kernel backlog holds any
    ;; connection arriving before accept(2) is first entered. A sleep here
    ;; would be guessing at a race that cannot happen.
    {:http-port http-port :https-port https-port :plain plain :tls tls}))

(defn kill [{:keys [plain tls]}]
  (when plain (srv/stop plain))
  (when tls (srv/stop tls))
  nil)
