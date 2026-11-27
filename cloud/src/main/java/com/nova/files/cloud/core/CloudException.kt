package com.nova.files.cloud.core

/**
 * Typed failures with user-facing messages. Messages never contain tokens, passwords or headers.
 * [retryable] marks transient failures the engine may retry (subject to idempotency rules).
 */
sealed class CloudException(val userMessage: String, cause: Throwable? = null, val retryable: Boolean = false) : Exception(userMessage, cause) {
    class AuthRequired(val accountId: String?, reason: String = "Sign-in expired – reconnect this account") : CloudException(reason)
    class ConsentRequired(detail: String) : CloudException("Permission not granted: $detail. An administrator may need to approve access for work or school accounts.")
    class Forbidden(detail: String = "Access denied by the provider") : CloudException(detail)
    class NotFound(detail: String = "The item no longer exists") : CloudException(detail)
    class Conflict(val existingName: String?, detail: String = "An item with that name already exists") : CloudException(detail)
    class QuotaExceeded(detail: String = "Cloud storage is full") : CloudException(detail)
    class PayloadTooLarge(detail: String = "File too large for this provider or upload method") : CloudException(detail)
    class RateLimited(val retryAfterMs: Long?) : CloudException("The provider asked us to slow down", retryable = true)
    class Server(val code: Int) : CloudException("The provider had a temporary problem ($code)", retryable = true)
    class Network(cause: Throwable?, detail: String = "Network unavailable or connection dropped") : CloudException(detail, cause, retryable = true)
    class Tls(cause: Throwable?) : CloudException("Secure connection failed – the server certificate isn't trusted", cause)
    class Unsupported(val op: Operation, detail: String) : CloudException(detail)
    class Integrity(detail: String) : CloudException(detail)
    class InsufficientStorage(needed: Long, free: Long) : CloudException("Not enough space on this device (needs ${needed / 1048576} MB, ${free / 1048576} MB free)")
    class CredentialsUnavailable(val accountId: String) : CloudException("Stored sign-in couldn't be read – reconnect this account")
    class Http(val code: Int, detail: String) : CloudException(detail)
    /** A provider upload session is no longer valid; the upload restarts from the beginning. */
    class UploadSessionExpired : CloudException("Upload session expired – restarting")
    class OffsetMismatch(val correct: Long?) : CloudException("Upload position mismatch", retryable = true)
}
