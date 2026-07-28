# Schannel client invariants

The Schannel provider crosses a native stateful API, so its portable tests,
bounded models, ABI probes, and real socket gate have distinct jobs. None is
presented as a substitute for the others.

## Exact `SECBUFFER_EXTRA` conservation

`tail-bytes` validates `0 <= extra <= input length`, starts the bulk copy at
`input length - extra`, and creates a fresh array of exactly `extra` bytes.
Both the handshake and record paths publish that array as their next input.

The corrected bounded model covers input and suffix lengths from zero through
four and asks whether the next input can differ in length or at any valid byte
position. It is `unsat`. Moving the copy origin one byte late is the sole
changed assertion in the buggy control and is `sat`. The non-vacuity witness
copies `[10 11 12 13]` with extra length two to `[12 13]`.

Source and runtime oracles:

- `src/jolt/http/schannel.clj`: `tail-bytes`, `initialize-step!`, and
  `decrypt-once!`;
- `test/jolt/http/schannel_test.clj`:
  `extra-data-is-the-exact-ordered-input-suffix`.

The model assumes SSPI truthfully reports a trailing byte count. The source
fails closed if that count exceeds the supplied input.

## Output-token retirement

With `ISC_REQ_ALLOCATE_MEMORY`, Schannel owns a returned token until
`FreeContextBuffer`. `send-output-token!` reads and sends a non-empty token,
then its `finally` releases it. `release-output-token!` clears the caller-owned
descriptor before checking the native status, so a status exception cannot
make the outer cleanup observe the token as live.

The corrected four-step model orders send, native free, descriptor clear, and
outer cleanup, then asks for a leak, double free, duplicate send, or send after
release. It is `unsat`. Moving outer cleanup before descriptor clearing is the
sole buggy mutation and yields a concrete double-free trace. The non-vacuity
model reaches one send and exactly one native free.

Source and runtime oracles:

- `src/jolt/http/schannel.clj`: `release-output-token!`,
  `send-output-token!`, and the defensive outer cleanup in
  `initialize-step!`;
- `test/jolt/http/schannel_test.clj`:
  `output-token-retirement-is-published-before-an-outer-finally`.

The model treats a completed FFI call as atomic: if `FreeContextBuffer` returns,
its status describes that call. Process termination or an exception raised
after native execution but before the FFI call returns is outside the model.

## Validation-mode isolation

`request-flags` is a pure function of one connection's `insecure?` argument,
and `make-state` stores its result in that new connection's state. There is no
mutable global validation flag.

The corrected three-connection model fixes a secure/insecure/secure trace and
asks whether either secure call can carry the manual-validation bit, or the
insecure call can omit it. It is `unsat`. Reusing call one's bit in call two is
the sole buggy assertion and is `sat`. The non-vacuity model reaches the
expected false/true/false selections.

Source and runtime oracles:

- `src/jolt/http/schannel.clj`: `request-flags`, `make-state`, and
  `tls-connect`;
- `test/jolt/http/schannel_test.clj`:
  `trust-all-is-one-explicit-request-bit`;
- `test/jolt/http/schannel_runtime_test.clj`:
  `certificate-validation-is-explicit-and-does-not-leak`, a real
  secure/insecure/secure sequence against a self-signed Windows TLS origin.

The secure behavior also depends on Schannel's automatic validation semantics,
the supplied target hostname, and the native credential/context calls. The
native gate exercises those environmental premises; the bounded model proves
only the source-level mode selection relation.

## Reproduction

```sh
tools/verify-models.sh
```

See `models/README.md` for expected verdicts and limits.
