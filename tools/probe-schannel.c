/*
 * probe-schannel.c -- emit the exact Windows SSPI/Schannel ABI used by the
 * jolt HTTP client's native TLS provider.
 *
 * This is deliberately more than a header scraper:
 *
 *   - typed function-pointer assignments make signature drift a compile error;
 *   - linking against Secur32.lib proves the required entry points exist; and
 *   - two real initial client handshakes prove that default outbound
 *     credentials work both with automatic certificate validation and with
 *     the explicit manual-validation request used by the trust-all path.
 *
 * No network traffic is sent. InitializeSecurityContextW produces the initial
 * ClientHello token and returns SEC_I_CONTINUE_NEEDED before a transport is
 * required.
 */
#define SECURITY_WIN32

#include <windows.h>
#include <security.h>
#include <schannel.h>

#include <stddef.h>
#include <stdint.h>
#include <stdio.h>

#if defined(_M_X64) || defined(__x86_64__)
#  define ARCH_NAME "x86-64"
#elif defined(_M_ARM64) || defined(__aarch64__)
#  define ARCH_NAME "aarch64"
#else
#  define ARCH_NAME "unknown"
#endif

#define OFFSET(type, field) ((size_t)offsetof(type, field))
#define STATUS(value) ((int32_t)(SECURITY_STATUS)(value))

static int initial_token(
    PCredHandle credentials,
    ULONG extra_request_flags,
    ULONG *token_length)
{
    CtxtHandle context = {0};
    SecBuffer output_buffer = {0};
    SecBufferDesc output = {0};
    ULONG attributes = 0;
    SECURITY_STATUS status;
    SECURITY_STATUS cleanup_status;
    const ULONG request_flags =
        ISC_REQ_SEQUENCE_DETECT |
        ISC_REQ_REPLAY_DETECT |
        ISC_REQ_CONFIDENTIALITY |
        ISC_REQ_EXTENDED_ERROR |
        ISC_REQ_ALLOCATE_MEMORY |
        ISC_REQ_STREAM |
        extra_request_flags;

    output_buffer.BufferType = SECBUFFER_TOKEN;
    output.ulVersion = SECBUFFER_VERSION;
    output.cBuffers = 1;
    output.pBuffers = &output_buffer;

    status = InitializeSecurityContextW(
        credentials,
        NULL,
        (SEC_WCHAR *)L"example.com",
        request_flags,
        0,
        SECURITY_NATIVE_DREP,
        NULL,
        0,
        &context,
        &output,
        &attributes,
        NULL);

    if (status != SEC_I_CONTINUE_NEEDED) {
        fprintf(
            stderr,
            "InitializeSecurityContextW returned 0x%08lx, expected "
            "SEC_I_CONTINUE_NEEDED\n",
            (unsigned long)(uint32_t)status);
        return 1;
    }
    if (output_buffer.pvBuffer == NULL || output_buffer.cbBuffer == 0) {
        fputs(
            "InitializeSecurityContextW returned no initial client token\n",
            stderr);
        if (SecIsValidHandle(&context)) {
            DeleteSecurityContext(&context);
        }
        return 1;
    }

    *token_length = output_buffer.cbBuffer;

    cleanup_status = FreeContextBuffer(output_buffer.pvBuffer);
    if (cleanup_status != SEC_E_OK) {
        fprintf(
            stderr,
            "FreeContextBuffer returned 0x%08lx\n",
            (unsigned long)(uint32_t)cleanup_status);
        DeleteSecurityContext(&context);
        return 1;
    }

    cleanup_status = DeleteSecurityContext(&context);
    if (cleanup_status != SEC_E_OK) {
        fprintf(
            stderr,
            "DeleteSecurityContext returned 0x%08lx\n",
            (unsigned long)(uint32_t)cleanup_status);
        return 1;
    }

    return 0;
}

int main(void)
{
    /*
     * These local assignments are load-bearing compile-time evidence. If the
     * SDK changes a declaration or the probe accidentally uses an A/W-mismatched
     * entry point, /WX makes the build fail. Keeping them local also avoids
     * MSVC C4232, which rejects static initialization from a dllimport address.
     */
    ACQUIRE_CREDENTIALS_HANDLE_FN_W signature_acquire =
        AcquireCredentialsHandleW;
    FREE_CREDENTIALS_HANDLE_FN signature_free_credentials =
        FreeCredentialsHandle;
    INITIALIZE_SECURITY_CONTEXT_FN_W signature_initialize =
        InitializeSecurityContextW;
    DELETE_SECURITY_CONTEXT_FN signature_delete_context =
        DeleteSecurityContext;
    QUERY_CONTEXT_ATTRIBUTES_FN_W signature_query =
        QueryContextAttributesW;
    COMPLETE_AUTH_TOKEN_FN signature_complete =
        CompleteAuthToken;
    APPLY_CONTROL_TOKEN_FN signature_apply_control =
        ApplyControlToken;
    FREE_CONTEXT_BUFFER_FN signature_free_buffer =
        FreeContextBuffer;
    ENCRYPT_MESSAGE_FN signature_encrypt =
        EncryptMessage;
    DECRYPT_MESSAGE_FN signature_decrypt =
        DecryptMessage;
    CredHandle credentials = {0};
    ULONG automatic_token_length = 0;
    ULONG manual_token_length = 0;
    SECURITY_STATUS status;
    SECURITY_STATUS cleanup_status;

    /*
     * NULL pAuthData asks Schannel for the process' default outbound
     * credentials. This smaller contract avoids either SCHANNEL_CRED or
     * SCH_CREDENTIALS in the Jolt implementation.
     */
    status = signature_acquire(
        NULL,
        (SEC_WCHAR *)UNISP_NAME_W,
        SECPKG_CRED_OUTBOUND,
        NULL,
        NULL,
        NULL,
        NULL,
        &credentials,
        NULL);
    if (status != SEC_E_OK) {
        fprintf(
            stderr,
            "AcquireCredentialsHandleW returned 0x%08lx\n",
            (unsigned long)(uint32_t)status);
        return 1;
    }

    if (initial_token(&credentials, 0, &automatic_token_length) != 0 ||
        initial_token(
            &credentials,
            ISC_REQ_MANUAL_CRED_VALIDATION,
            &manual_token_length) != 0) {
        signature_free_credentials(&credentials);
        return 1;
    }

    cleanup_status = signature_free_credentials(&credentials);
    if (cleanup_status != SEC_E_OK) {
        fprintf(
            stderr,
            "FreeCredentialsHandle returned 0x%08lx\n",
            (unsigned long)(uint32_t)cleanup_status);
        return 1;
    }

    /*
     * Reference every remaining typed entry point at runtime as well. The
     * volatile sink prevents an optimizing linker from discarding the imports.
     */
    {
        volatile uintptr_t signature_sink =
            (uintptr_t)signature_acquire ^
            (uintptr_t)signature_free_credentials ^
            (uintptr_t)signature_initialize ^
            (uintptr_t)signature_delete_context ^
            (uintptr_t)signature_query ^
            (uintptr_t)signature_complete ^
            (uintptr_t)signature_apply_control ^
            (uintptr_t)signature_free_buffer ^
            (uintptr_t)signature_encrypt ^
            (uintptr_t)signature_decrypt;
        if (signature_sink == 0) {
            fputs("Schannel function signature sink unexpectedly vanished\n", stderr);
            return 1;
        }
    }

    printf("{:os :windows\n");
    printf(" :arch :%s\n", ARCH_NAME);
    printf(" :pointer-bits %zu\n", sizeof(void *) * 8);
    printf(" :layout {\n");
    printf(
        "  :sec-handle {:size %zu :lower %zu :upper %zu}\n",
        sizeof(SecHandle),
        OFFSET(SecHandle, dwLower),
        OFFSET(SecHandle, dwUpper));
    printf(
        "  :sec-buffer {:size %zu :length %zu :type %zu :pointer %zu}\n",
        sizeof(SecBuffer),
        OFFSET(SecBuffer, cbBuffer),
        OFFSET(SecBuffer, BufferType),
        OFFSET(SecBuffer, pvBuffer));
    printf(
        "  :sec-buffer-desc {:size %zu :version %zu :count %zu :buffers %zu}\n",
        sizeof(SecBufferDesc),
        OFFSET(SecBufferDesc, ulVersion),
        OFFSET(SecBufferDesc, cBuffers),
        OFFSET(SecBufferDesc, pBuffers));
    printf(
        "  :stream-sizes {:size %zu :header %zu :trailer %zu "
        ":maximum-message %zu :buffers %zu :block-size %zu}\n",
        sizeof(SecPkgContext_StreamSizes),
        OFFSET(SecPkgContext_StreamSizes, cbHeader),
        OFFSET(SecPkgContext_StreamSizes, cbTrailer),
        OFFSET(SecPkgContext_StreamSizes, cbMaximumMessage),
        OFFSET(SecPkgContext_StreamSizes, cBuffers),
        OFFSET(SecPkgContext_StreamSizes, cbBlockSize));
    printf(" }\n");

    printf(" :const {\n");
    printf("  :credential-outbound %lu\n", (unsigned long)SECPKG_CRED_OUTBOUND);
    printf("  :native-data-representation %lu\n", (unsigned long)SECURITY_NATIVE_DREP);
    printf("  :attribute-stream-sizes %lu\n", (unsigned long)SECPKG_ATTR_STREAM_SIZES);
    printf("  :buffer-empty %lu\n", (unsigned long)SECBUFFER_EMPTY);
    printf("  :buffer-data %lu\n", (unsigned long)SECBUFFER_DATA);
    printf("  :buffer-token %lu\n", (unsigned long)SECBUFFER_TOKEN);
    printf("  :buffer-extra %lu\n", (unsigned long)SECBUFFER_EXTRA);
    printf("  :buffer-stream-trailer %lu\n", (unsigned long)SECBUFFER_STREAM_TRAILER);
    printf("  :buffer-stream-header %lu\n", (unsigned long)SECBUFFER_STREAM_HEADER);
    printf("  :request-sequence-detect %lu\n", (unsigned long)ISC_REQ_SEQUENCE_DETECT);
    printf("  :request-replay-detect %lu\n", (unsigned long)ISC_REQ_REPLAY_DETECT);
    printf("  :request-confidentiality %lu\n", (unsigned long)ISC_REQ_CONFIDENTIALITY);
    printf("  :request-allocate-memory %lu\n", (unsigned long)ISC_REQ_ALLOCATE_MEMORY);
    printf("  :request-extended-error %lu\n", (unsigned long)ISC_REQ_EXTENDED_ERROR);
    printf("  :request-stream %lu\n", (unsigned long)ISC_REQ_STREAM);
    printf(
        "  :request-manual-validation %lu\n",
        (unsigned long)ISC_REQ_MANUAL_CRED_VALIDATION);
    printf("  :shutdown-token %lu\n", (unsigned long)SCHANNEL_SHUTDOWN);
    printf(" }\n");

    printf(" :status {\n");
    printf("  :ok %ld\n", (long)STATUS(SEC_E_OK));
    printf("  :continue-needed %ld\n", (long)STATUS(SEC_I_CONTINUE_NEEDED));
    printf("  :complete-needed %ld\n", (long)STATUS(SEC_I_COMPLETE_NEEDED));
    printf(
        "  :complete-and-continue %ld\n",
        (long)STATUS(SEC_I_COMPLETE_AND_CONTINUE));
    printf("  :context-expired %ld\n", (long)STATUS(SEC_I_CONTEXT_EXPIRED));
    printf(
        "  :incomplete-credentials %ld\n",
        (long)STATUS(SEC_I_INCOMPLETE_CREDENTIALS));
    printf("  :incomplete-message %ld\n", (long)STATUS(SEC_E_INCOMPLETE_MESSAGE));
    printf("  :renegotiate %ld\n", (long)STATUS(SEC_I_RENEGOTIATE));
    printf(" }\n");

    printf(" :runtime {\n");
    printf("  :default-outbound-credentials true\n");
    printf("  :automatic-validation-initial-token true\n");
    printf("  :manual-validation-initial-token true\n");
    printf("  :automatic-token-nonempty %s\n",
           automatic_token_length > 0 ? "true" : "false");
    printf("  :manual-token-nonempty %s\n",
           manual_token_length > 0 ? "true" : "false");
    printf(" }}\n");

    return 0;
}
