package com.rain.sdk.turnkey

import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.error.VendorErrorClassifier
import com.turnkey.core.models.errors.TurnkeyKotlinError
import timber.log.Timber

/**
 * Turnkey's exception vocabulary, translated into the Rain error contract at the adapter boundary.
 *
 * [TurnkeySessionCoordinator] runs [map] on every failure it does not refresh or retry, so no
 * vendor exception leaves this module and core never sees a Turnkey type. Two properties of
 * [classify] matter, and the tests pin both:
 *
 * - It is **total** for anything recognizably Turnkey — every [TurnkeyKotlinError] and every HTTP
 *   failure the vendor reports as a plain exception carrying the status in its message. Returning
 *   null for one of those would hand it to the prose heuristics, and a vendor error whose text
 *   happens to read "insufficient funds" or "user rejected" would change error code.
 * - It returns null for everything else, which leaves the shared prose fallback in charge.
 */
internal object TurnkeyErrorMapping {

    /**
     * Every failure that leaves the adapter passes through here. A [RainError] is already the
     * contract and passes through; a Turnkey failure is classified by type, then by HTTP status;
     * anything else is read by the shared prose rules and floors at [RainError.ProviderError].
     */
    fun map(e: Throwable): RainError =
        e as? RainError
            ?: classify(e)
            ?: VendorErrorClassifier.fromVendorError(e)
            ?: RainError.ProviderError(e)

    /**
     * A [RainError] for a Turnkey failure, null when the throwable is not one. The typed check
     * runs first, then the HTTP status parsed out of the message, because a typed signal beats
     * prose.
     */
    fun classify(t: Throwable): RainError? {
        if (t is TurnkeyKotlinError) return mapTurnkeyError(t)
        return mapTurnkeyHttpStatus(t)
    }

    /** [map] plus the authentication log line, for the managed-login catch-all boundary. */
    fun mapAuthError(e: Throwable): RainError {
        val mapped = map(e)
        // No throwable in this log line: an auth-proxy failure can echo the user's contact address.
        Timber.e(
            "Rain SDK: Authentication error %s (%s)",
            mapped.errorCode.code,
            e.javaClass.simpleName,
        )
        return mapped
    }

    /**
     * Maps Turnkey SDK errors to RainError:
     *  - InvalidSession → TokenExpired
     *  - Config/setup-style errors (missing rpId, missing config param, client not initialized,
     *    invalid parameter / message / refresh TTL / response, OAuth state mismatch, key already
     *    exists / not found) → InternalError
     *  - FailedToInitOtp (a failed code request) → ProviderError whatever the status: no session
     *    exists yet, so 401/403 cannot mean an expired session or a permission problem
     *  - Wrapper errors with an underlying cause → recurse / classify the cause's vendor prose
     *  - Everything else → ProviderError
     */
    @Suppress("ReturnCount") // one return per vendor variant reads better than a nested when
    fun mapTurnkeyError(e: TurnkeyKotlinError): RainError {
        Timber.e(e, "Rain SDK: Turnkey error")

        when (e) {
            is TurnkeyKotlinError.InvalidSession -> return RainError.TokenExpired()

            // Config / setup-time errors that indicate misuse rather than provider failure.
            is TurnkeyKotlinError.InvalidRefreshTTL,
            is TurnkeyKotlinError.ClientNotInitialized,
            is TurnkeyKotlinError.InvalidParameter,
            is TurnkeyKotlinError.InvalidResponse,
            is TurnkeyKotlinError.InvalidMessage,
            is TurnkeyKotlinError.MissingRpId,
            is TurnkeyKotlinError.MissingConfigParam,
            is TurnkeyKotlinError.OAuthStateMismatch,
            is TurnkeyKotlinError.KeyAlreadyExists,
            is TurnkeyKotlinError.KeyNotFound -> return RainError.InternalError("Turnkey: ${e.message}", e)

            // A rejected one-time login code: the auth proxy answers the verify call with a 4xx.
            // Gated on the status because the vendor wraps *every* failure of verifyOtp in this
            // type — timeouts, crypto errors, its own InvalidResponse — and 408/429 are transient,
            // not "retype your code". Everything else falls through to the cause inspection below.
            // A 401 is claimed here rather than left to the TokenExpired mapping: during code
            // verification there is no session yet, so it can only mean the code was refused.
            // Known gap: the proxy has also been seen wrapping a rejection in an HTTP 500 whose JSON
            // body carries the real status; the Kotlin SDK discards that body before Rain sees it, so
            // a wrapped rejection stays a ProviderError here. The managed
            // controller keeps the challenge for every verify-step failure so the user can still
            // retype — see isLoginCodeVerifyFailure.
            is TurnkeyKotlinError.FailedToVerifyOtp ->
                if (e.cause.let(::turnkeyHttpStatus) in REJECTED_LOGIN_CODE_STATUSES) {
                    return RainError.InvalidLoginCode()
                }

            // A failed code request: the auth proxy refused or never answered /v1/otp_init_v2. No
            // session exists while a code is being requested, so a 401 or 403 here cannot mean an
            // expired session or a missing permission; the HTTP-status mapping below would turn them
            // into TokenExpired or Unauthorized and send the host to re-authenticate a user who is
            // not signed in. The reason (the channel not enabled on the proxy configuration, an
            // undeliverable number, a rate limit) is in the response body the Kotlin SDK drops, so
            // only the status survives in the message. A wrapped vendor error keeps its own
            // classification (a client that is not initialized is still a setup problem).
            is TurnkeyKotlinError.FailedToInitOtp -> {
                val wrapped = e.cause
                if (wrapped is TurnkeyKotlinError) return mapTurnkeyError(wrapped)
                return RainError.ProviderError(e)
            }

            else -> Unit // fall through to cause inspection
        }

        // Recurse into wrapped causes (e.g. FailedToSignRawPayload(underlying)). The status
        // check precedes the prose check here too, for the same reason as classify().
        val cause = e.cause
        if (cause != null && cause !== e) {
            if (cause is TurnkeyKotlinError) return mapTurnkeyError(cause)
            mapTurnkeyHttpStatus(cause)?.let { return it }
            VendorErrorClassifier.fromVendorError(cause)?.let { return it }
        }

        return RainError.ProviderError(e)
    }

    /**
     * Maps a Turnkey API HTTP failure to [RainError.TokenExpired] (401) or
     * [RainError.Unauthorized] (403).
     *
     * The Kotlin SDK throws a plain `RuntimeException` and carries the status only inside the
     * message, in one of two generated shapes:
     *   "HTTP error from <path>: <code>"
     *   "HTTP error calling <activityType> request\nError: <body>\nCode: <code>"
     * so the status has to be parsed out. Returns `null` for anything that is not a recognizable
     * HTTP failure, leaving the caller's fallback in place. Replace this with a typed check once
     * the Turnkey SDK exposes the status code.
     */
    @Suppress("MagicNumber") // HTTP status codes read better inline than as named constants
    private fun mapTurnkeyHttpStatus(e: Throwable): RainError? {
        return when (val status = turnkeyHttpStatus(e) ?: return null) {
            401 -> RainError.TokenExpired()
            403 -> RainError.Unauthorized("Turnkey rejected the request: HTTP $status")
            else -> null
        }
    }

    const val TURNKEY_HTTP_ERROR_PREFIX = "HTTP error"

    /** Auth-proxy statuses that mean the login code itself was refused (not 408/429/5xx). */
    @Suppress("MagicNumber") // HTTP status codes; naming each one would obscure the set
    val REJECTED_LOGIN_CODE_STATUSES: Set<Int> = setOf(400, 401, 403)

    /**
     * True when [e] or any exception in its cause chain is the vendor's verify-step failure —
     * the one-time code was still being checked when the call failed. Only that step leaves a
     * code worth retrying; the account lookup, login and session steps that follow run once
     * the code was accepted, so a failure there has already spent it.
     */
    fun isLoginCodeVerifyFailure(e: Throwable): Boolean =
        generateSequence(e) { current -> current.cause?.takeIf { it !== current } }
            .take(MAX_CAUSE_DEPTH)
            .any { it is TurnkeyKotlinError.FailedToVerifyOtp }

    /** Bounds the cause walk so a cyclic cause chain cannot spin it. */
    private const val MAX_CAUSE_DEPTH = 8

    /** Trailing ": <code>" or "Code: <code>" — the two shapes the Turnkey SDK generates. */
    val TURNKEY_HTTP_STATUS_REGEX = Regex("""(?:Code:\s*|:\s*)(\d{3})\s*$""")

    /**
     * The HTTP status carried in a Turnkey SDK failure message, or null when the throwable
     * is not a recognizable Turnkey HTTP failure. Shared with the session coordinator's
     * retry classification.
     */
    @Suppress("ReturnCount") // two guard clauses before the parse
    fun turnkeyHttpStatus(e: Throwable): Int? {
        val message = e.message ?: return null
        if (!message.startsWith(TURNKEY_HTTP_ERROR_PREFIX)) return null
        return TURNKEY_HTTP_STATUS_REGEX.find(message)
            ?.groupValues?.getOrNull(1)
            ?.toIntOrNull()
    }
}
