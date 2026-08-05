# W10A/W10B and shared-toolchain platform evidence

This records the current compatibility target and its exact source-runtime
evidence separately from predecessor checkpoints. Platforms not covered by the
current workflow are named as such.

## Current compatibility target

The source and workflow target the same current Jolt core, jolt-tcp, and
jolt-net spine already exercised by jolt-sim. The exact candidate now has its
own six-target hosted source-runtime evidence below; jolt-sim itself is not
used as evidence for this client's clj-http-lite, TLS, compression, or Schannel
behavior.

| component | pin | how it enters the graph |
| --- | --- | --- |
| Jolt core | `9fc64f93eba8b56a319f91bb1a322e2efced9c70` | the Jolt 0.5.20 proposal compiler/runtime selected by workflow `JOLT_CORE_SHA` |
| jolt-tcp | `0ceaa900bfca11933d35831d7697c7e2c5b22f04` | `deps.edn` (`casselc/jolt-tcp`) |
| jolt-net | `699b908ffb4eb79ad35055cdc20866bb504e6932` | transitively pinned by jolt-tcp |
| jolt-crypto | `c0b8237e74e4f17d2675b57bab32d4aebd92812f` | unchanged direct dependency; public CNG-capable revision |
| clj-http-lite | `5bc2a98969b4926d090787baf9297fd73cea42d0` | unchanged direct dependency |

The jolt-tcp public calls consumed here (`client/connect`, `send-all!`,
`receive-at-most!`, `close!`; `server/run-server`, `write-completion`, `close`,
and `stop-server`) retain their arities and contracts at the new pin. The core
renamed the public monotonic clock from `jolt.host/monotonic-nanos` to
`jolt.host/mono-nanos`; both client deadline sources now use the current name.
No transport, TLS, compression, or Schannel compatibility shim was added.

### Current six-target hosted checkpoint

Source revision `8f08a782c0e2b9e62ee4d9b11b04cb82f4feaf51` passed two
independent exact-head matrices: push
[run `30970312877`](https://github.com/casselc/http-client/actions/runs/30970312877)
and pull-request
[run `30970314358`](https://github.com/casselc/http-client/actions/runs/30970314358).

| target | source-runtime evidence | result |
| --- | --- | --- |
| Linux x86_64 | full compatibility/TLS suite, libz, babashka surface, capabilities, plaintext loopback, bounded models | 85/207; 5/5; 7/11; 10/56; 18/56; 9/9 |
| Linux aarch64 | same gates with native architecture assertion | pass in both matrices |
| macOS x86_64 | same POSIX gates | pass in both matrices |
| macOS arm64 | same POSIX gates with native architecture assertion | pass in both matrices |
| Windows x86_64 | plaintext, capabilities, portable Schannel contracts, real Schannel loopback | 18/56; 10/47; 10/33; 2/11, fixture `failed,served,failed` |
| Windows aarch64 | same gates with native `aarch64` assertion | pass in both matrices |

Counts are tests/assertions except libz and model verdicts. The first candidate
`a8c4616` is deliberately not counted: its red matrix exposed the signed-byte
stream-contract defect fixed in `8f08a78`. The passing matrix therefore proves
the fix rather than hiding its original failure.

## Previous fully observed stack

| component | pin | how it enters the graph |
| --- | --- | --- |
| Jolt core | `46e1f74fc14f29283586900ef4b98c45375c0500` | compiler/runtime used by the recorded runs below |
| jolt-tcp | `911cf783d56e988adb2b8f716b6636fae5454e52` | then-current direct dependency |
| jolt-net | `c3747385235df812e0d739a3e9f71c4dfb07b474` | transitive pin of that jolt-tcp revision |
| jolt-crypto | `c0b8237e74e4f17d2675b57bab32d4aebd92812f` | `deps.edn` (`casselc/jolt-crypto`), public CNG-capable revision |
| clj-http-lite | `5bc2a98969b4926d090787baf9297fd73cea42d0` | `deps.edn` (`clj-commons/clj-http-lite`) |

All four dependency pins in this historical table were confirmed fetchable
from their public URLs, and were fetched at exactly these SHAs into empty caches
on both platforms below.

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

### Six-target shared-toolchain promotion

Source revision `af2e0036358974b38c63595477d136c683df2862` passed the
expanded six-target matrix in
[run `30400911333`](https://github.com/casselc/http-client/actions/runs/30400911333).
This was the first use of the shared toolchain in this repository: all six
exact archive keys reported `Cache not found`, downloaded the immutable
`chez-ci-10.4.1.1` release assets, verified their caller-pinned SHA-256
digests, inventories, Chez versions, and machine types, and installed them
without a source-build fallback.

| target | runtime evidence | cold job time |
| --- | --- | --- |
| Linux x86_64 | full 85/206, babashka 7/11, capability 10/56, plaintext 17/55, libz, 9/9 models | 1m14s |
| Linux aarch64 | same counts, native `aarch64` target | 1m07s |
| macOS arm64 | same POSIX counts, `:tls true`, `:compression true`, provider-free plaintext | 1m20s |
| macOS x86_64 | same POSIX counts and capability report | 2m05s |
| Windows x86_64 | plaintext 17/55, capability 10/47, contracts 10/32, native TLS 2/11 | 2m02s |
| Windows aarch64 | same counts, native `tarm64nt` and `aarch64` assertions | 3m39s |

Every count had zero failures and zero errors. The four POSIX capability
reports were `{:plaintext true :tls true :compression true}`. Both Windows
reports were `{:plaintext true :tls true :compression false}`. Every plaintext
row reported provider namespaces `[]`; adding provider coverage on the same
runner did not weaken the provider-free plaintext claim. Both native TLS
fixtures again reported `failed,served,failed`.

The final workflow pins
`casselc/jolt-toolchains/setup-chez@095108ae32659757808064d004855092567d3ad3`.
That follow-up changes only the shared action's internal cache implementation
to `actions/cache` 5.1.0 on Node 24. Source revision
`ec6650cfee0788a203be070cd8ff0480268db654` passed
[run `30401296906`](https://github.com/casselc/http-client/actions/runs/30401296906)
with all six exact archive keys reporting `Cache restored from key`, no release
download, no Chez source build, and no Node 20 warning. It repeated every count
above. Warm job times were 1m20s (Linux x86_64), 1m16s (Linux aarch64), 1m33s
(macOS arm64), 1m41s (macOS x86_64), 2m00s (Windows x86_64), and 3m46s
(Windows aarch64). Runtime tests, rather than toolchain provisioning, now
dominate those jobs.

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

## Current hosted coverage boundary

The current workflow observes source-runtime behavior on
Linux x86_64/aarch64, macOS arm64/x86_64, and Windows x86_64/aarch64. The four
POSIX rows run the full compatibility, OpenSSL, libz, capability, plaintext,
and proof gates. Windows runs the portable plaintext/capability contracts and
the focused Schannel contracts plus a real TLS loopback fixture. This is
source-mode evidence against the exact fork core; it is not packaged `joltc` or
AOT-image evidence.

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
