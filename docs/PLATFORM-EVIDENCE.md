# W10A platform evidence

What was actually run, on what, with which pins. Observed results only; lanes
that were configured but not executed are named as such.

## Pinned stack

| component | pin | how it enters the graph |
| --- | --- | --- |
| Jolt core | `46e1f74fc14f29283586900ef4b98c45375c0500` | the compiler/runtime; not a dep. Every lane builds against it because the pinned jolt-net needs host/FFI primitives a released `joltc` does not carry |
| jolt-tcp | `911cf783d56e988adb2b8f716b6636fae5454e52` | `deps.edn` (`casselc/jolt-tcp`) |
| jolt-net | `c3747385235df812e0d739a3e9f71c4dfb07b474` | transitively, pinned by jolt-tcp |
| jolt-crypto | `c0b8237e74e4f17d2675b57bab32d4aebd92812f` | `deps.edn` (`casselc/jolt-crypto`), public CNG-capable revision |
| clj-http-lite | `5bc2a98969b4926d090787baf9297fd73cea42d0` | `deps.edn` (`clj-commons/clj-http-lite`) |

All four dependency pins were confirmed fetchable from their public URLs, and
were fetched at exactly these SHAs into empty caches on both platforms below.

## Observed runs

Source/runtime mode throughout: interpreted source mode against the pinned core
(`bin/jolt` on Linux; native Chez 10.4.1 driving `host\chez\cli.ss` on
Windows, with `JOLT_AOT_CACHE=0`). No packaged/released `joltc` was used.

### Linux x86_64 (local, glibc)

| lane | alias | result |
| --- | --- | --- |
| clj-http-lite suites + transport + TLS | `-M:test` | 75 tests, 174 assertions, 0 fail, 0 error |
| libz round-trip | `-M:zlibtest` | 5 checks, all passed |
| babashka.http-client surface | `-M:bhctest` | 7 tests, 11 assertions, 0 fail, 0 error |
| capability seam | `-M:capability` | 10 tests, 57 assertions, 0 fail, 0 error |
| plaintext loopback gate | `-M:plaintext-test` | 17 tests, 55 assertions, 0 fail, 0 error |

Capability report: `{:plaintext true :tls true :compression true}`.
Provider namespaces loaded during the plaintext run: `[]`.

`-M:test` was run three consecutive times after the readiness-sleep removal
(below); 75/174 each time.

**Clean-cache resolution.** With `JOLT_GITLIBS`, `JOLT_CACHE_DIR` and `GITLIBS`
all redirected to empty directories, `-M:plaintext-test` fetched all four deps
at the pins above and passed 17/55. Note that Jolt uses two cache mechanisms —
`JOLT_GITLIBS` and the tools.deps-style `GITLIBS` — and redirecting only the
first leaves jolt-tcp/jolt-net resolving from `~/.gitlibs`; a clean-cache claim
must redirect both.

### Windows x86_64 (native, observed)

Real native Windows, not emulation or WSL: `{:os :windows, :arch :x86-64,
:abi :win64, :pointer-bits 64}`, reached through WSL interop to the Windows
host. Chez Scheme 10.4.1 native (`D:\chez-10.4.1`), pinned core checked out at
`46e1f74f`, `.jolt-cache/gitlibs` created empty for the run.

| lane | alias | result |
| --- | --- | --- |
| plaintext loopback gate | `-M:plaintext-test` | 17 tests, 55 assertions, 0 fail, 0 error |
| capability seam | `-M:capability` | 10 tests, 45 assertions, 0 fail, 0 error |

Capability report: `{:plaintext true :tls false :compression false}`.
Provider namespaces loaded during the plaintext run: `[]`.
Arch gate output: `arch gate: running natively on x86-64`.

Two things make this the load-bearing evidence:

- Plaintext dependency resolution and a real loopback HTTP exchange both
  succeeded on a platform with **no OpenSSL and no zlib present** — `:tls false`
  and `:compression false` are the probes reporting genuine absence, not a
  configuration choice.
- The capability suite's fail-closed assertions ran against real absence here,
  where on Linux the same assertions run against forced absence.

The assertion-count difference (45 on Windows vs 57 on Linux) is by design: the
"provider present" tests — callable exports and the gzip/deflate round-trip —
are skipped when a capability is absent. No test failed or was skipped
silently.

Reproduction (from WSL, project staged at
`D:\src\http-client-w10a-codex`):

```powershell
& "D:\src\http-client-w10a-codex\tools\test-windows-source.ps1" `
  -ProjectPath "D:\src\http-client-w10a-codex" `
  -RuntimePath "D:\src\jolt-core-w9" `
  -ChezExe "D:\chez-10.4.1\bin\scheme.exe" `
  -ExpectedArch 'x86-64' -TestAlias '-M:plaintext-test' -TimeoutSeconds 1200
```

## Configured but NOT observed

- **Windows aarch64.** The `windows-arm64-plaintext` job exists and reuses
  jolt-tcp's proven ARM64 recipe (native `tarm64nt` Chez built from source, a
  runner-arch assertion, and a `machine-type` check), and the lane's own
  `JOLT_EXPECTED_ARCH=aarch64` gate makes an emulated x86-64 process fail before
  any assertion can pass. No ARM64 Windows host was available here, so this is
  the next lane — it has not been run.
- **Linux aarch64** (`posix` matrix) and the CI lanes generally: the workflow
  was not executed, because this branch has no push access to
  `jolt-lang/http-client`. The Linux results above were produced locally with
  the same aliases the workflow invokes.

## Platform boundaries

| suite | portable? | why |
| --- | --- | --- |
| `-M:plaintext-test` | yes | origin is `jolt.http.portable-server` on `teensyp.server`; no TLS/zlib namespace loads |
| `-M:capability` | yes | no native library required to run |
| `-M:test` | POSIX only | `jolt.http.test-server` opens its listener with raw POSIX `socket`/`bind`/`listen`/`accept`; the TLS half needs OpenSSL |
| `-M:bhctest` | POSIX only | uses the same POSIX `jolt.http.test-server` |
| `-M:zlibtest` | needs libz | direct libz round-trip |

So the clj-http-lite compatibility suite and the babashka.http-client surface
were run where each is actually supported — Linux — and are not claimed for
Windows. Making them portable would mean porting `jolt.http.test-server` off
raw POSIX sockets onto `teensyp.server`; that is a separate change.

## Remaining platform work

- Windows TLS needs a Schannel provider behind the `:tls` capability.
  jolt-crypto's Windows CNG backend provides AES/HMAC/digest/RNG only and is
  **not** a TLS implementation — it does not substitute for Schannel.
- Windows compression needs a native compression provider behind the
  `:compression` capability.
- Porting the POSIX test origin onto `teensyp.server` would let the
  clj-http-lite compatibility suite run on Windows too.
- `jolt.net` still resolves names through synchronous `getaddrinfo`, so an
  in-flight resolver call cannot be preempted by a connect deadline. Unchanged
  by this slice; noted in the README.

## Audit notes on the inherited WIP

The transferred `codex/platform-tcp-validation` work was reviewed as inherited
implementation rather than restarted. Findings and what was done:

- **Descriptor leakage** — none. `jolt.http.net` transports are maps of
  closures over an opaque `teensyp.client` connection; no fd, handle, sockaddr
  or libc binding crosses the boundary. Now asserted directly against a live
  connection in `plaintext-test` (no descriptor-shaped key, and every value is
  a closure, an atom or the marker flag).
- **Timeout/deadline semantics** — correct and already well covered by the
  inherited `net_test.clj`: one request-wide absolute monotonic deadline
  reaches connect, write and every read; it does not restart after a response
  chunk; unset/zero connect timeouts stay explicitly unbounded rather than
  inheriting teensyp's 30s default.
- **EOF/reset mapping** — correct. Only real operation deadlines become
  `SocketTimeoutException`; connection reset and unknown native failures keep
  their structured identity instead of being collapsed into timeouts.
- **Ownership/close** — each request closes its stream in a `finally`, and each
  redirect hop opens and closes its own.
- **Stream dispatch (changed)** — `s-write`/`s-read`/`s-close` dispatched
  negatively on "not a table", so the plaintext path was defined as the absence
  of the TLS shape. Now dispatches positively on `net/transport?`.
- **Readiness sleep (changed)** — the POSIX fixture slept 300ms after starting
  its servers. `start-plain`/`start-tls` call `bind` and `listen` synchronously
  before spawning the accept loop, so the socket is already listening on return
  and the kernel backlog covers the gap; the sleep was guessing at an
  impossible race. Removed, and `-M:test` was re-run three times to confirm.

No new formal model was added. Lifecycle, deadline and ownership semantics were
not changed by this slice — the capability seam sits above the transport and
alters only *which provider* is selected, never how a connection is opened,
bounded or closed — and the inherited deterministic tests already cover those
semantics directly.

Capability initialization does have a concurrency boundary: Jolt's namespace
loader must not be raced by simultaneous first callers. Each provider now uses
Jolt's thread-safe `delay`, whose runtime contract serializes evaluation and
caches the result. A barrier-driven 16-worker regression test forces concurrent
first use and requires exactly one provider evaluation with every caller
observing the same complete result.
Because the runtime primitive supplies that exactly-once publication contract,
a separate custom capability state machine or SMT model would duplicate the
primitive rather than strengthen this layer's evidence. Present/absent
selection and the end-to-end clj-http-lite decompression refusal remain covered
by `capability-test`.
