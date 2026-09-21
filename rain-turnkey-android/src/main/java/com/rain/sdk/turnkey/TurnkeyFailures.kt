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
