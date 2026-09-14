# Changelog

## Unreleased

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
  narrowly as a stale pooled socket and retried fresh; unrelated TLS failures
  and every mid-response truncation still surface to the caller.

- Resolve `jolt-crypto` from its canonical `jolt-lang` repository at the
  reviewed Jolt 0.8 provider/value-first revision, with a fail-closed selected
  dependency graph guard.
