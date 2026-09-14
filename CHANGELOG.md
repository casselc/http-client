# Changelog

## Unreleased

- Make TCP connect, plaintext writes, and TLS ciphertext writes observe thread
  interruption within the same bounded 250 ms readiness slice already used by
  response reads. Every connect attempt is temporarily non-blocking even when
  no connect timeout was configured; partial writes resume from the exact
  unsent suffix after `EAGAIN`/`POLLOUT`. Interrupted or otherwise failed
  streams remain owned by the active request, are closed once, and are never
  replayed. DNS resolution and `CompletableFuture.cancel` propagation remain
  outside this transport-level change.

- Converge the request-aspect fork with `jolt-lang/http-client` v0.0.10. The
  resulting provider retains the single `clj-http.lite.core/request` join point
  while adopting upstream's interruptible 250 ms read slices, response framing
  and byte pipeline, Babashka/JDK client surface, and portable `addrinfo` /
  `pollfd` fixes. It requires Jolt 0.8.1 or newer and resolves OpenSSL through
  the canonical `jolt-lang/jolt-crypto` provider at
  `44da69bad08a2fd7631bd4061e3fb53938dafff6`.
  Compiler-backed controls now prove that the packaged manifest resolves the
  real request entry exactly once and rejects zero/duplicate matches. TLS keeps
  HTTP framing completion independent from transport shutdown while accepting
  only `close_notify` as clean TLS EOF, so framed-complete raw closes succeed
  without an extra read and truncated or close-delimited raw closes fail. A raw
  close on a reused TLS connection before any response byte is classified
  narrowly as a stale pooled socket. Idempotent methods retry fresh, while POST
  and other non-idempotent requests surface the failure rather than risk replay;
  unrelated TLS failures and every mid-response truncation also surface.

- Resolve `jolt-crypto` from its canonical `jolt-lang` repository at the
  reviewed Jolt 0.8 provider/value-first revision, with a fail-closed selected
  dependency graph guard.
