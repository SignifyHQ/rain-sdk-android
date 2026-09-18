package com.rain.sdk.turnkey

import kotlinx.coroutines.CancellationException

/**
 * This throwable and its causes, nearest first, at most [MAX_CAUSE_DEPTH] deep so a cyclic cause
 * chain cannot spin a walk. The module's one cause walk: the error mapping, the export failure
 * translation, the session coordinator, the wallet provider's history fallback and
 * [cancellationInChain] all read an exception through it.
 */
internal fun Throwable.causeChain(): Sequence<Throwable> =
    generateSequence(this) { current -> current.cause?.takeIf { it !== current } }.take(MAX_CAUSE_DEPTH)

/**
 * The caller's cancellation inside a vendor failure, or null. The vendor's session calls
 * (`refreshSession`, `setSelectedSession`, `signRawPayload`, the exports) catch `Throwable` and
 * rethrow their own type with the original as the cause, a coroutine cancellation included, so a
 * `catch (e: CancellationException)` above them never matches.
 */
internal fun Throwable.cancellationInChain(): CancellationException? =
    causeChain().filterIsInstance<CancellationException>().firstOrNull()

/** Bounds the one cause walk so a cyclic cause chain cannot spin it. */
private const val MAX_CAUSE_DEPTH = 8

/**
 * This throwable with the vendor's HTTP response body removed, or [fallback] when it is not a
 * recognizable vendor HTTP failure. The typed client's message embeds the raw body, which for the
 * user-update activities can echo the email or phone being attached, and `RainError.ProviderError`
 * copies its cause's message into the message hosts log; the status and the request path or
 * activity type are what a host can act on. Applied only where a failure floors at
 * `ProviderError`, after the prose rules have read the body.
 */
internal fun Throwable.sanitizedForHost(fallback: Throwable = this): Throwable {
    val status = TurnkeyErrorMapping.turnkeyHttpStatus(this) ?: return fallback
    val target = TURNKEY_HTTP_TARGET_REGEX.find(message.orEmpty())?.groupValues?.getOrNull(1)?.trimEnd(':')
        ?: "the wallet backend"
    return TurnkeyHttpFailure(status, target)
}

/**
 * A vendor HTTP failure with the response body removed; see [sanitizedForHost]. The message keeps
 * the vendor's path shape, so [TurnkeyErrorMapping.turnkeyHttpStatus] still reads the status off a
 * mapped error's cause, which the wallet provider's history fallback relies on.
 */
internal class TurnkeyHttpFailure(status: Int, target: String) : RuntimeException("HTTP error from $target: $status")

/** The first token after "from" or "calling": the request path or the activity type, never the body. */
private val TURNKEY_HTTP_TARGET_REGEX = Regex("""^HTTP error (?:from|calling) (\S+)""")
