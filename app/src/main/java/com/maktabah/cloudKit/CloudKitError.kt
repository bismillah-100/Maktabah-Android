package com.maktabah.cloudKit

/**
 * Structured error types from CloudKit Web Services API.
 * All CloudKitCoreManager methods return Result.failure(CloudKitError.*)
 * instead of raw Exception with string messages.
 */
sealed class CloudKitError(message: String) : Exception(message) {
    /** ckWebAuthToken missing from local storage. */
    data object NoWebAuthToken : CloudKitError("No Web Auth Token")

    /** ckWebAuthToken rejected by Apple (HTTP 401 or reason=AUTHENTICATION_FAILED). */
    data object AuthenticationExpired : CloudKitError("Authentication expired")

    /** Delta sync token expired on server (reason=CHANGE_TOKEN_EXPIRED). */
    data class ChangeTokenExpired(val zoneName: String) : CloudKitError("Change token expired for zone: $zoneName")

    /** Any other server-side error. */
    data class ServerError(val reason: String) : CloudKitError(reason)
}
