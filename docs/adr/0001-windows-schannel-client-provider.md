# ADR 0001: Add Windows client TLS through Schannel

## Status

Accepted for implementation. The x86-64 ABI and default-credential premise are
probed natively; the first ARM64 workflow run is deliberately evidence
collection and must be reviewed before ARM64 becomes a descriptor gate.

## Context

W10A made plaintext HTTP independent of TLS and compression. POSIX HTTPS still
uses `jolt.http.tls`, an OpenSSL memory-BIO adapter over the opaque
`jolt.http.net` byte transport. Windows now has a real TCP stack and a
platform-native crypto provider in `jolt-crypto`, but the HTTP client still
reports TLS unavailable because its only TLS provider is OpenSSL.

Shipping OpenSSL DLLs would reintroduce an artifact and loader problem that the
platform-provider work is intended to remove. Windows already supplies TLS
through Schannel in `Secur32.dll`.

The production capability is smaller than the OpenSSL namespace initially made
it appear. `jolt.http.platform` calls only one TLS export:

```clojure
(capability/invoke :tls :connect host port insecure? opts)
```

`tls-wrap-server` is a test-fixture helper used directly by the POSIX test
server. It is not part of the application SPI. A Windows client provider does
not need to implement a TLS server.

## Decision

Select the TLS client provider by observed Jolt target:

- Linux and macOS use `jolt.http.tls/tls-connect` over OpenSSL.
- Windows uses `jolt.http.schannel/tls-connect` over Schannel.
- The portable export map contains only `:connect`.
- `jolt.crypto` remains a TLS-adjacent load on both paths because it owns the
  real `java.security.SecureRandom` shim that clj-http-lite constructs on its
  trust-all path. On Windows that shim is already backed by CNG, not OpenSSL.
- Plaintext continues to load neither TLS provider.

The Schannel provider passes only pointers and scalar values through
`jolt.ffi`. It needs no aggregate-by-value call:

- `CredHandle` / `CtxtHandle`;
- `SecBuffer` and `SecBufferDesc`;
- `SecPkgContext_StreamSizes`; and
- byte storage for UTF-16LE target names and TLS records.

Current core already supplies 16-bit reads/writes, native allocation, scoped
byte-array pointers, and bulk `read-array!` / `write-array` copies. A
NUL-terminated target name is therefore an ordinary UTF-16LE byte-array plus
two zero bytes. No core primitive is proposed.

Use default outbound credentials by passing `NULL` as `pAuthData` to
`AcquireCredentialsHandleW`. The secure path leaves automatic credential
validation enabled and passes the target hostname to
`InitializeSecurityContextW`. The explicit trust-all path adds
`ISC_REQ_MANUAL_CRED_VALIDATION`; it must never be selected implicitly.

## Native ABI evidence

`tools/probe-schannel.c` is compiled with `/W4 /WX`, linked to `Secur32.lib`,
and executed. It provides three different kinds of evidence:

1. typed function-pointer assignments check the exact SDK declarations for
   credential, context, query, encrypt, decrypt, and cleanup calls;
2. emitted layouts and constants are compared byte-for-byte with committed
   EDN; and
3. two real initial client contexts prove that default outbound credentials
   produce a non-empty ClientHello both with automatic validation and with the
   explicit manual-validation request.

The local native Windows x86-64 probe used Windows SDK `10.0.26100.0` and
recorded:

- 16-byte security handles;
- 16-byte `SecBuffer` and `SecBufferDesc`;
- a 20-byte stream-size result;
- pointer fields at offset 8; and
- signed `SECURITY_STATUS` values, including
  `SEC_E_INCOMPLETE_MESSAGE = -2146893032`.

The committed record is
`tools/probed/schannel-windows-x86-64.edn`. The workflow refuses descriptor
drift. ARM64 remains evidence collection until its native artifact has been
reviewed and committed.

## State-machine obligations

The implementation must preserve these boundaries:

1. Every output token returned with `ISC_REQ_ALLOCATE_MEMORY` is sent at most
   once and released exactly once with `FreeContextBuffer`.
2. `SECBUFFER_EXTRA` bytes are retained in order and become the prefix of the
   next handshake or decrypt input. No ciphertext byte is duplicated or lost.
3. `SEC_E_INCOMPLETE_MESSAGE` retains the entire input and obtains more bytes;
   it never discards a partial record.
4. `EncryptMessage` sends exactly the returned header, data, and trailer
   windows, split at `cbMaximumMessage`.
5. `SEC_I_CONTEXT_EXPIRED` is clean TLS EOF. Raw transport EOF without that
   status is truncation.
6. `SEC_I_RENEGOTIATE`, including TLS 1.3 post-handshake processing, returns to
   the same token loop rather than being treated as application data.
7. Credential and context handles are retired once, after the last operation;
   transport close remains idempotent.
8. Every network read/write receives the caller's existing absolute deadline
   options unchanged.
9. The secure path can never select manual validation, and the trust-all flag
   can never leak from one connection to another.

Before landing the provider, deterministic tests should exercise the buffer
partition transitions independently of native Schannel. A native Windows
loopback gate must then prove a real self-signed handshake succeeds only on the
explicit trust-all path and is rejected on the secure path.

## Rejected approaches

### Ship OpenSSL for Windows

This adds an external native artifact despite Windows already providing TLS,
and makes applications manage DLL discovery and architecture matching.

### Put Schannel in Jolt core

Core owns the FFI primitives. TLS policy and the byte-transport adapter belong
in this library, where OpenSSL already lives and provider selection can evolve
without changing the runtime.

### Model the entire provider as a JVM-compatible socket

The portable HTTP layer already has a smaller, descriptor-free byte transport.
Recreating Java socket classes underneath Schannel would widen the trusted
surface without helping another consumer.

### Require a Windows TLS server implementation

The application SPI is client `:connect`. The existing server wrapper is only a
POSIX test fixture and remains directly testable there.
