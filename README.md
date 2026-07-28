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
| `java.net.URL`, `HttpURLConnection` | hand-rolled HTTP/1.1 client over the opaque `teensyp.client` API from `jolt-tcp` (`jolt.http.net` / `jolt.http.platform`) |
| `java.io.ByteArrayInput/OutputStream` | byte-stream tagged-tables wired into `io/copy` / `slurp` |
| `java.util.zip` (gzip/deflate) | the system **libz** via `jolt.ffi` (`jolt.http.zlib`) |
| `javax.net.ssl` (https, `insecure?`) | the system **OpenSSL** via `jolt.ffi`, memory-BIO TLS over the opaque jolt-tcp transport (`jolt.http.tls`) |
| `java.net.http.HttpClient` (JDK 11+ client) | construction, getters, and synchronous/live-settled sends for the modern client/request builders (`HttpClient`/`HttpRequest`/`HttpResponse` + `BodyPublishers`/`BodyHandlers`/`HttpHeaders`); used by babashka.http-client and cognitect aws-api's java backend |

`jolt-tcp` and its transitive `jolt-net` dependency own platform socket
declarations and never expose a descriptor here.

## Platforms and capabilities

Plaintext HTTP is the base graph and requires no OpenSSL and no zlib. TLS and
content-encoding are **optional capabilities** whose provider namespaces and
probes are selected on first use through `jolt.http.capability`. Jolt still
attempts optional native-library candidates while resolving the dependency
graph; a library already installed on the host may therefore be mapped before
its provider namespace is used.

| | plaintext HTTP | https (OpenSSL TLS) | gzip/deflate (libz) |
| --- | --- | --- | --- |
| Linux x86_64 / aarch64 | yes | yes | yes |
| macOS | yes | yes | yes |
| Windows x86_64 / aarch64 | yes | not yet — see below | not yet — see below |

Where a capability has no provider it **fails closed**. Requesting `https://`,
or constructing one of the `java.util.zip` streams, raises an `ex-info` carrying

```clojure
{:jolt.http/kind :unsupported-provider
 :jolt.http/capability :tls            ; or :compression
 :jolt.http/libraries ["libssl" "libcrypto"]
 :jolt.http/target {:os :windows :arch :x86-64 ...}}
```

An https request is never silently downgraded to plaintext, and a body is never
returned as decoded when no decoder ran. An unsupported encoded response aborts
with the structured refusal; no response map is returned.
`jolt.http.capability/report` actively resolves both providers and reports the
result for the running platform.

Windows TLS would need a Schannel provider and Windows compression a native
compression provider; neither is in this slice. Note that jolt-crypto's Windows
CNG backend supplies AES/HMAC/digest/RNG only — it is **not** a TLS
implementation and does not substitute for Schannel.

Native declarations: this project declares `libz` and `libssl` as `:optional`
capability libraries; `jolt-crypto` declares `libcrypto`/`bcrypt`, also
optional. Nothing in the plaintext path requires a shared object to be present,
which is what lets dependency resolution and a real request both succeed on
native Windows.

Positive connect/read timeouts retain their millisecond budgets.
`HttpURLConnection`'s unset/zero connect timeout remains unbounded, and
`HttpRequest.timeout` is one absolute monotonic deadline spanning connect,
TLS handshake, request write, and every response read. One current platform
limitation remains: `jolt.net` resolves names through synchronous
`getaddrinfo`. A result returned after the connect deadline is rejected, but an
in-flight resolver call cannot yet be preempted, so wall-clock connect time can
overrun the configured budget while DNS is blocked.

## Requirements

- Nothing native for plaintext HTTP.
- System OpenSSL (`libssl`/`libcrypto`) for https, and `libz` for
  gzip/deflate — optional, and only where those capabilities are used.
- A `jolt` build with the library-shim host hooks (`__register-class-methods!` /
  `__register-instance-check!`) and the FFI byte-buffer / charset support this
  library relies on. The pinned `jolt-net` needs host/FFI primitives a released
  `joltc` does not carry, so build against the core revision named in
  `.github/workflows/tests.yml` (`JOLT_CORE_SHA`).

## Tests

`joltc -M:test` runs clj-http-lite's own `client`, `links` and `integration`
suites under Jolt. The suites are vendored under `test/clj_http/lite`; their
`server-process` fixture is replaced with in-process plaintext + TLS servers
(`jolt.http.test-server`, with a test-only listener feeding the same opaque byte
transport + OpenSSL engine) in place of the suite's Jetty subprocess — no
external checkout needed.

```
joltc -M:test
```

The main suite includes deterministic transport/deadline and TLS error-ordering
tests in addition to clj-http-lite's integration suite. Separate
`joltc -M:bhctest` and `joltc -M:zlibtest` aliases cover babashka.http-client and
libz.

`-M:test`, `-M:zlibtest` and `-M:bhctest` are **POSIX lanes**: their in-process
origin (`jolt.http.test-server`) opens its listener with raw POSIX
`socket`/`bind`/`listen`/`accept`, and the TLS suite needs OpenSSL.

Two further aliases are portable and run identically on Linux, macOS and native
Windows:

```
joltc -M:plaintext-test   # real loopback HTTP over jolt-tcp, no TLS, no zlib
joltc -M:capability       # provider seam: resolved providers and fail-closed refusals
```

`-M:plaintext-test` serves its requests from `jolt.http.portable-server`, an
origin built on `teensyp.server` from jolt-tcp rather than raw POSIX sockets, so
the same suite witnesses the same behaviour on every platform. It covers GET /
POST / PUT, body conservation in both directions, request headers, all three
response framings (fixed `Content-Length`, chunked, and connection-close),
redirects, connect refusal, read deadlines, a silent origin, a server that
closes without responding, and the absence of any native descriptor at the
transport boundary. It **fails** if any of `jolt.http.tls`, `jolt.http.zlib` or
`jolt.crypto` is loaded during the run, which is the direct form of the claim
that an unencoded plaintext exchange does not initialize a TLS or compression
provider namespace.

Set `JOLT_EXPECTED_ARCH` (`x86-64` or `aarch64`) to make the lane refuse to run
under architecture emulation.
