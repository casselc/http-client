(ns jolt.http.capability
  "Optional native capabilities behind the HTTP client.

  Plaintext HTTP is the base graph. It reaches the network through
  jolt.http.net over jolt-tcp and requires no OpenSSL and no zlib, so
  dependency resolution and a real request both succeed on every platform
  jolt-tcp supports — including native Windows, where neither libssl nor libz
  is present by default.

  TLS and content-encoding are capabilities selected on first use, never at
  load. Two properties make that safe:

    - the provider namespaces (jolt.http.tls, jolt.http.zlib) are required
      lazily here, so a graph that never speaks https or handles compressed
      content never loads them;
    - jolt.ffi resolves a `defcfn` binding when it is *called*, not when the
      namespace loads, so requiring a provider whose shared object is absent
      does not abort — which is precisely why a load alone proves nothing and
      each provider is probed with one cheap real native call.

  A capability that cannot be provided fails closed: callers get a structured
  ::unsupported-provider error naming the capability, the target, and the
  libraries that would have supplied it. It is never silently degraded, and
  content is never reported as decoded when no decoder ran.

  Jolt's dependency loader does attempt every optional native candidate before
  loading project namespaces. The laziness here therefore applies to provider
  namespaces, probes, and use; it does not claim that an already-present shared
  object was not mapped at process startup."
  (:require [clojure.string :as str]))

;; A capability is its provider namespace, a probe that performs one real call
;; into the shared object, the functions exported to callers, and any sibling
;; namespace whose host shims the capability implies. jolt.crypto is TLS-only:
;; it installs the java.security.SecureRandom shim clj-http-lite's insecure
;; (trust-all) path constructs, and its own provider selection is eager, so it
;; must not be pulled into the plaintext graph.
(def ^:private capabilities
  {:tls
   {:provider-ns 'jolt.http.tls
    :also-load   ['jolt.crypto]
    :probe       'jolt.http.tls/available?
    :libraries   ["libssl" "libcrypto"]
    :exports     {:connect     'jolt.http.tls/tls-connect
                  :wrap-server 'jolt.http.tls/tls-wrap-server}}

   :compression
   {:provider-ns 'jolt.http.zlib
    :also-load   []
    :probe       'jolt.http.zlib/available?
    :libraries   ["libz"]
    :exports     {:gzip    'jolt.http.zlib/gzip
                  :gunzip  'jolt.http.zlib/gunzip
                  :deflate 'jolt.http.zlib/zlib-deflate
                  :inflate 'jolt.http.zlib/zlib-inflate}}})

(defn- unsupported
  [capability failure]
  (let [spec (get capabilities capability)]
    (ex-info
      (str "jolt.http: no " (name capability) " provider is available on this "
           "platform (needs " (str/join " + " (:libraries spec)) ")")
      {:jolt.http/kind :unsupported-provider
       :jolt.http/capability capability
       :jolt.http/libraries (:libraries spec)
       :jolt.http/provider-ns (:provider-ns spec)
       :jolt.http/target (jolt.host/target)}
      failure)))

(defn- resolve-capability [capability]
  (let [{:keys [provider-ns also-load probe exports]} (get capabilities capability)]
    (try
      (doseq [namespace also-load] (require namespace))
      (require provider-ns)
      (let [probe-var (resolve probe)]
        (when (nil? probe-var)
          (throw (ex-info (str "provider namespace " provider-ns
                               " does not expose " probe)
                          {:jolt.http/provider-ns provider-ns})))
        ;; The probe makes a real call into the shared object. A missing
        ;; library surfaces here rather than mid-request.
        (when-not (probe-var)
          (throw (ex-info (str "provider " provider-ns
                               " reported its native library is unavailable")
                          {:jolt.http/provider-ns provider-ns}))))
      {:provider
       (reduce-kv (fn [m export-key target-sym]
                    (let [target (resolve target-sym)]
                      (when (nil? target)
                        (throw (ex-info (str "provider namespace " provider-ns
                                             " does not expose " target-sym)
                                        {:jolt.http/provider-ns provider-ns})))
                      (assoc m export-key target)))
                  {}
                  exports)}
      (catch :default failure
        {:failure failure}))))

(defn- resolution-delay [capability]
  (delay (resolve-capability capability)))

;; capability -> thread-safe delay of {:provider {...}} | {:failure exception}.
;; `require` is not safe to race through Jolt's namespace loader. Each delay
;; serializes the first use, publishes only the complete result, and retains it
;; thereafter. The atom exists only so tests can install and then restore a
;; controlled delay; production never mutates it.
(def ^:private resolved
  (atom {:tls (resolution-delay :tls)
         :compression (resolution-delay :compression)}))

(defn- entry [capability]
  (when-not (contains? capabilities capability)
    (throw (ex-info (str "unknown jolt.http capability " capability)
                    {:jolt.http/kind :unknown-capability
                     :jolt.http/capability capability})))
  (force (get @resolved capability)))

(defn available?
  "True when `capability` (:tls or :compression) has a working native provider.
  Callers that can proceed without the capability should ask rather than catch."
  [capability]
  (some? (:provider (entry capability))))

(defn provider
  "The export map for `capability`, or throw the structured
  ::unsupported-provider error. This is the fail-closed edge: every https and
  every content-encoding path goes through it, so an absent provider can only
  ever produce a typed refusal — never a plaintext fallback and never a body
  passed off as decoded."
  [capability]
  (let [{:keys [provider failure]} (entry capability)]
    (or provider (throw (unsupported capability failure)))))

(defn invoke
  "Call export `export-key` of `capability` with `args`, failing closed if the
  capability is unavailable."
  [capability export-key & args]
  (let [exports (provider capability)
        target (get exports export-key)]
    (when (nil? target)
      (throw (ex-info (str "capability " capability " has no export " export-key)
                      {:jolt.http/kind :unknown-export
                       :jolt.http/capability capability
                       :jolt.http/export export-key})))
    (apply target args)))

(defn unsupported-provider-error?
  "True for the structured capability refusal thrown by [[provider]]."
  [exception]
  (= :unsupported-provider (:jolt.http/kind (ex-data exception))))

(defn report
  "Resolve both capabilities and return a diagnostic report. Names the target
  and which providers initialized successfully; never exposes native handles."
  []
  {:target (jolt.host/target)
   :plaintext true
   :tls (available? :tls)
   :compression (available? :compression)})
