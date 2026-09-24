package com.rain.sdk.wallet

/**
 * Rain's wallet backend identity: the sandbox organization and its authentication configuration,
 * the pair Rain's SDKs share as a cross-platform contract. Public identifiers, not secrets:
 * possession grants nothing, because authentication still runs the one-time-code flow and abuse is
 * bounded by the backend's rate limits. Embedded so a host needs zero configuration to use the Rain
 * wallet; there is no host-facing environment switch.
 */
internal object RainWalletBackend {
    const val ORGANIZATION_ID: String = "63495e45-8e64-42b5-b602-c68f019ca806"
    const val AUTH_CONFIG_ID: String = "1d8aac5e-f236-4800-bab7-98a9e27b4b2a"
}
