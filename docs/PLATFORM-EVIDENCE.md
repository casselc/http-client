# W10A/W10B platform evidence

What was actually run, on what, with which pins. Observed results only;
platforms not covered by the current workflow are named as such.

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

### Hosted checkpoint

Source revision `0e43a6c132fa977e7f22882514053319560d7676` passed all four
configured native targets in
[run `30394288147`](https://github.com/casselc/http-client/actions/runs/30394288147):

| target | native evidence | result | cold job time |
| --- | --- | --- | --- |
| Linux x86_64 | full TLS/compression suite, babashka surface, capabilities, plaintext loopback | 75/174, 7/11, 10/57, 17/55 | 5m12s |
| Linux aarch64 | same four gates, native `aarch64` target assertion | 75/174, 7/11, 10/57, 17/55 | 3m44s |
| Windows x86_64 | plaintext loopback and absent-provider contract | 17/55, 10/45 | 9m35s |
| Windows aarch64 | same two gates, native `tarm64nt` and `aarch64` assertions | 17/55, 10/45 | 7m05s |

All counts are tests/assertions except the five-check libz gate, which also
passed on both Linux targets. The Windows capability reports were
`{:plaintext true :tls false :compression false}`; both plaintext runs asserted
that no TLS, compression, or crypto provider namespace loaded. Linux reported
both optional providers present.

This was the fork's first run, so every Chez cache was cold and each job built
Chez 10.4.1 from source. Those timings are baseline evidence for the separate
shared-toolchain migration; they are not a claim about steady-state CI cost.

Documentation-tip
[run `30395142509`](https://github.com/casselc/http-client/actions/runs/30395142509)
then restored all four repository-local caches, skipped every Chez build, and
repeated the same green gates at `69cdde90b59fa5b8aa5840e6d2c06c644c0faf54`.
Warm job times were 1m02s (Linux x86_64), 1m07s (Linux aarch64), 1m39s
(Windows x86_64), and 3m15s (Windows aarch64).

### W10B Schannel checkpoint

Source revision `8fd5d89e95f61f1b1dde76b6a74b2571eff99b6b`
passed all four configured targets in
[run `30400231076`](https://github.com/casselc/http-client/actions/runs/30400231076).
Both Windows lanes ran a real Schannel client against a PowerShell-owned
self-signed TLS origin; this was not descriptor-only evidence.

| target | unchanged/base gates | Schannel evidence |
| --- | --- | --- |
| Linux x86_64 | full suite 85/206, babashka 7/11, capability 10/56, plaintext 17/55 | all 9 bounded model verdicts match |
| Linux aarch64 | same counts and provider report as x86_64 | all 9 bounded model verdicts match |
| Windows x86_64 | plaintext 17/55, capability 10/47 with `:tls true` | portable contracts 10/32; native TLS 2/11; fixture `failed,served,failed` |
| Windows aarch64 | same counts, native `tarm64nt` and `aarch64` assertions | portable contracts 10/32; native TLS 2/11; fixture `failed,served,failed` |

Every count above had zero failures and zero errors. Both Windows plaintext
runs still reported provider namespaces `[]`; adding HTTPS did not make an
unencoded plaintext request initialize Schannel, zlib, or jolt-crypto.

The native sequence proves automatic certificate validation rejects the
self-signed origin, explicit `:insecure? true` completes a real HTTPS/HTTP
exchange, and a following secure connection rejects again. The fixture sends a
real `close_notify`. During local development it exposed and prevented two
false greens: PowerShell 5.1 `SslStream.Dispose` did not send the alert, and an
unchanged encrypted input slot could be mistaken for final plaintext on
`SEC_I_CONTEXT_EXPIRED`.

The final run also reprobed both Schannel ABIs and compared them byte-for-byte
with `tools/probed/schannel-windows-{x86-64,aarch64}.edn`. Both architectures
have the same layouts, constants, status values, and default-credential
behavior apart from the declared architecture.

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

## Not yet covered by hosted CI

The current workflow has no macOS x86_64 or arm64 row. The portable plaintext
and capability gates, POSIX compatibility suites, and lower jolt-tcp/jolt-net
stack are intended to support macOS, but this branch makes no hosted macOS
claim until those rows execute successfully.

## Platform boundaries

| suite | portable? | why |
| --- | --- | --- |
| `-M:plaintext-test` | yes | origin is `jolt.http.portable-server` on `teensyp.server`; no TLS/zlib namespace loads |
| `-M:capability` | yes | no native library required to run |
| `-M:schannel-contract-test` | yes | pure buffer, ownership, and validation-mode contracts; FFI bindings remain lazy |
| `-M:schannel-runtime-test` | Windows only | real SSPI/Schannel client and TLS loopback fixture |
| `-M:test` | POSIX only | `jolt.http.test-server` opens its listener with raw POSIX `socket`/`bind`/`listen`/`accept`; the TLS half needs OpenSSL |
| `-M:bhctest` | POSIX only | uses the same POSIX `jolt.http.test-server` |
| `-M:zlibtest` | needs libz | direct libz round-trip |

So the clj-http-lite compatibility suite and the babashka.http-client surface
were run where each is actually supported — Linux — and are not claimed for
Windows. Making them portable would mean porting `jolt.http.test-server` off
raw POSIX sockets onto `teensyp.server`; that is a separate change.

## Remaining platform work

- Add macOS x86_64 and arm64 hosted rows.
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

W10A added no new formal model. Lifecycle, deadline and ownership semantics
were not changed by that slice — the capability seam sits above the transport
and alters only *which provider* is selected, never how a connection is opened,
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

W10B does cross a new native state machine, so it adds three bounded proof
families with one-assertion buggy controls and non-vacuity witnesses:
`SECBUFFER_EXTRA` conservation, Schannel-owned output-token retirement, and
connection-local validation-mode isolation. Their exact scope, source anchors,
assumptions, and Chiasmus/standalone-Z3 evidence are in
`docs/proofs/schannel-invariants.md`.
