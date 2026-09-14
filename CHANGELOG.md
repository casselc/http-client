# Changelog

## Unreleased

- Converge the request-aspect fork with `jolt-lang/http-client` v0.0.10. The
  resulting provider retains the single `clj-http.lite.core/request` join point
  while adopting upstream's interruptible 250 ms read slices, response framing
  and byte pipeline, Babashka/JDK client surface, and portable `addrinfo` /
  `pollfd` fixes. It requires Jolt 0.8.1 or newer and resolves OpenSSL through
  the canonical `jolt-lang/jolt-crypto` provider at
  `44da69bad08a2fd7631bd4061e3fb53938dafff6`.

- Resolve `jolt-crypto` from its canonical `jolt-lang` repository at the
  reviewed Jolt 0.8 provider/value-first revision, with a fail-closed selected
  dependency graph guard.
