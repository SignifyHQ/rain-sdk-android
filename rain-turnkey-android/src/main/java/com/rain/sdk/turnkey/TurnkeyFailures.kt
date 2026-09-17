package com.rain.sdk.turnkey

import kotlinx.coroutines.CancellationException

/**
 * The caller's cancellation inside a vendor failure, or null. The vendor's session calls
 * (`refreshSession`, `setSelectedSession`, `signRawPayload`, the exports) catch `Throwable` and
 * rethrow their own type with the original as the cause, a coroutine cancellation included, so a
 * `catch (e: CancellationException)` above them never matches. Bounded so a cyclic cause chain
 * cannot spin the walk.
 */
internal fun Throwable.cancellationInChain(): CancellationException? {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < CAUSE_CHAIN_LIMIT) {
        if (current is CancellationException) return current
        current = current.cause?.takeIf { it !== current }
        depth++
    }
    return null
}

private const val CAUSE_CHAIN_LIMIT = 8
