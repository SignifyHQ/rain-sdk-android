package com.rain.sdk.turnkey

/**
 * Marks the managed-authentication surface of the Turnkey adapter: public in the binary because a
 * sibling Rain module (the RainWallet provider) has to call it, but not part of the SDK's contract
 * with host apps. A host that uses a marked declaration gets a compile error; the Rain modules that
 * are allowed through — and the sample app — opt in module-wide with
 * `-opt-in=com.rain.sdk.turnkey.InternalRainTurnkeyApi`. No compatibility guarantees: it changes
 * whenever the RainWallet provider needs it to.
 *
 * The Kotlin counterpart of the iOS SDK's `@_spi(RainWallet)`.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Internal Rain SDK API reserved for the RainWallet provider. Host apps use the RainWallet " +
        "provider or bring-your-own TurnkeyConfig(turnkey). No compatibility guarantees.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
)
annotation class InternalRainTurnkeyApi
