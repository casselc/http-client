(ns clj-http.lite.test-util.server-process
  "Jolt stand-in for clj-http-lite's server-process fixture. The upstream version
  shells out to a Jetty subprocess; here `launch` starts in-process plaintext +
  TLS servers (jolt.http.test-server, over jolt.ffi sockets + OpenSSL) serving the
  same routes, and returns their ports. `kill` stops them."
  (:require [jolt.http.test-server :as srv]))

(defn launch []
  ;; JOLT_PWD is set by CI but not by an interactive `jolt -M:test` — without
  ;; the user.dir fallback the cert path becomes "/test/resources/cert.pem",
  ;; SSL_CTX_use_certificate_file fails, and the TLS server closes every
  ;; connection during the handshake. self-signed-ssl-get then passes its
  ;; thrown? vacuously and crashes on the insecure request, wearing whatever
  ;; exception class the close happened to produce (RST vs clean EOF) — which
  ;; read as an intermittent TLS flake for a long time.
  (let [pwd  (or (jolt.host/getenv "JOLT_PWD")
                 (System/getProperty "user.dir")
                 (jolt.host/getenv "PWD"))
        cert (str pwd "/test/resources/cert.pem")
        key  (str pwd "/test/resources/key.pem")
        _ (doseq [path [cert key]]
            (when-not (.exists (java.io.File. path))
              (throw (ex-info (str "TLS test fixture not found: " path)
                              {:path path}))))
        plain (srv/start-plain)
        tls   (srv/start-tls cert key)]
    (Thread/sleep 300)
    {:http-port (:port plain) :https-port (:port tls) :plain plain :tls tls}))

(defn kill [{:keys [plain tls]}]
  (when plain (srv/stop plain))
  (when tls (srv/stop tls))
  nil)
