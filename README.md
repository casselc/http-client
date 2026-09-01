# jolt-lang/http-client

[clj-http-lite](https://github.com/clj-commons/clj-http-lite) running on
[Jolt](https://github.com/jolt-lang/jolt).

clj-http-lite is a small, dependency-light Clojure HTTP client built on the JVM's
`java.net.HttpURLConnection`. Jolt has no JVM, so this library supplies the host
APIs clj-http-lite needs as Jolt host shims — the same approach
[jolt-lang/router](https://github.com/jolt-lang/router) uses for reitit. None of
it lives in jolt core: requiring this library installs the shims at load.

```clojure
(require '[jolt.http-client :as http])

(http/get "https://example.com")
(http/get "https://api.example.com/things" {:query-params {"q" "jolt"} :as :json})
(http/post "https://example.com/x" {:body "{\"a\":1}" :content-type :json})
;; also: head, put, delete, and the lower-level request
```

The functions mirror `clj-http.lite.client` exactly — see its
[docs](https://github.com/clj-commons/clj-http-lite) for the full request/response
map.

## What it provides

| clj-http-lite uses | Jolt shim |
| --- | --- |
| `java.net.URL`, `HttpURLConnection` | hand-rolled HTTP/1.1 client over BSD sockets via `jolt.ffi` (`jolt.http.net` / `jolt.http.platform`) |
| `java.io.ByteArrayInput/OutputStream` | byte-stream tagged-tables wired into `io/copy` / `slurp` |
| `java.util.zip` (gzip/deflate) | the system **libz** via `jolt.ffi` (`jolt.http.zlib`) |
| `javax.net.ssl` (https, `insecure?`) | the system **OpenSSL** via `jolt.ffi`, memory-BIO TLS over the socket (`jolt.http.tls`) |
| `java.net.http.HttpClient` (JDK 11+ client) | the modern client/request builders (`HttpClient`/`HttpRequest`/`HttpResponse` + `BodyPublishers`/`BodyHandlers`/`HttpHeaders`); used by cognitect aws-api's java backend. `send` and `sendAsync` go over the same socket/TLS layer as everything else; `sendAsync` hands back an already-settled future, enough for `thenApply`/`exceptionally` but not a real `CompletableFuture`. |

The native libraries (libc sockets, libz, OpenSSL) are declared in `deps.edn`
under `:jolt/native`; jolt loads them before the namespaces are required.

## Timeouts

`:conn-timeout` and `:socket-timeout` are milliseconds, and both are off unless
you pass them.

```clojure
(http/get "https://example.com" {:conn-timeout 2000 :socket-timeout 10000})
```

`:conn-timeout` bounds each connect attempt, the way `java.net.Socket`'s does —
a name resolving to a dead address and a live one still connects, and the dead
one costs at most the timeout instead of the kernel's SYN retry window (~75s on
macOS, ~130s on Linux). `:socket-timeout` bounds each individual read.

Neither bounds a peer that keeps trickling bytes: every read beats the read
timeout, so the response never ends. `(jolt.http.platform/set-max-response-ms!
ms)` caps the total wall-clock time of a response body across all reads. It
applies process-wide, and is nil (uncapped) by default.

## Requirements

- jolt 0.8.0 or newer. This library uses the value-first `jolt.ffi/write`
  signature introduced in 0.8.0. The floor is declared as `:jolt/min-version`
  so supported runtimes reject an incompatible dependency graph before a native
  buffer can be written with the old offset-first interpretation.
- System `libz` (always present) and OpenSSL (`libssl`/`libcrypto`) for https.

## Tests

`jolt -M:test` runs clj-http-lite's own `client`, `links` and `integration`
suites under Jolt. The suites are vendored under `test/clj_http/lite`; their
`server-process` fixture is replaced with in-process plaintext + TLS servers
(`jolt.http.test-server`, over `jolt.ffi` sockets + OpenSSL) in place of the
suite's Jetty subprocess — no external checkout needed.

```
jolt -M:test
```

All 60 tests pass (116 assertions), including the self-signed-cert TLS test and
the gzip/deflate decompression tests.

Three suites run separately, and CI runs only `:test`:

```
jolt -M:timeouttest   # timeout/deadline regressions; stalls connections on
                      # purpose and stands up its own servers, which the main
                      # suite's serial accept loop doesn't tolerate. One case
                      # loads jolt.nrepl in a subprocess and fetches
                      # https://example.com, so it needs network egress.
jolt -M:bhctest       # babashka.http-client over the java.net.http shim
jolt -M:zlibtest      # zlib round-trip, no sockets
```
