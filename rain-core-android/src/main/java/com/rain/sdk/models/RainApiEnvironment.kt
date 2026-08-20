package com.rain.sdk.models

/**
 * Rain issuing API environment the SDK talks to.
 *
 * Selected at build time via `RainSdk.Builder.rainApiEnvironment(...)`; defaults to [Dev].
 */
sealed class RainApiEnvironment(val baseUrl: String) {

    /** Rain development environment (`api-dev.rain.xyz`). The default. */
    object Dev : RainApiEnvironment("https://api-dev.rain.xyz")

    /** Rain production environment (`api.rain.xyz`). */
    object Production : RainApiEnvironment("https://api.rain.xyz")

    /** Escape hatch for staging or self-hosted gateways: any explicit base URL. */
    class Custom(baseUrl: String) : RainApiEnvironment(baseUrl.trimEnd('/'))
}
