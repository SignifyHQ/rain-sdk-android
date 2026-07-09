package com.rain.sdk.sample

import com.rain.sdk.RainSdk
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.portal.PortalConfig
import com.rain.sdk.portal.PortalProvider
import com.rain.sdk.privy.PrivyConfig
import com.rain.sdk.privy.PrivyProvider
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.turnkey.TurnkeyConfig
import com.rain.sdk.turnkey.TurnkeyProvider
import com.turnkey.core.TurnkeyContext
import io.privy.sdk.Privy

/**
 * App-side holder around the modular [RainSdk].
 *
 * The sample picks a provider at runtime (Portal or Turnkey), so it builds the [RainSdk] lazily
 * once the user supplies credentials and then keeps the resolved [RainClient] here. Screens read
 * the real [client] directly — there is no fake `RainClient` wrapper. [client] is `null` until one
 * of the `initialize*` helpers has run.
 */
class RainSession {

    var rain: RainSdk? = null
        private set

    /** The provider-backed client, or `null` before initialization. */
    var client: RainClient? = null
        private set

    val isInitialized: Boolean get() = client?.isInitialized == true

    /** Builds the SDK with the Portal provider and resolves the Portal-backed client. */
    suspend fun initializePortal(
        sessionToken: String,
        rpcEndpoints: Map<Int, String>,
        chainId: Int? = null,
    ) {
        val sdk = RainSdk.builder()
            .rpcEndpoints(rpcEndpoints)
            .register(PortalProvider(PortalConfig(sessionToken = sessionToken, chainId = chainId)))
            .build()
        rain = sdk
        client = sdk.provider(ProviderId.PORTAL)
    }

    /** Builds the SDK with the Turnkey provider and resolves the Turnkey-backed client. */
    suspend fun initializeTurnkey(
        turnkey: TurnkeyContext,
        rpcEndpoints: Map<Int, String>,
        chainId: Int? = null,
        walletAddress: String? = null,
    ) {
        val sdk = RainSdk.builder()
            .rpcEndpoints(rpcEndpoints)
            .register(TurnkeyProvider(TurnkeyConfig(turnkey = turnkey, walletAddress = walletAddress)))
            .build()
        rain = sdk
        client = sdk.provider(ProviderId.TURNKEY)
    }

    /** Builds the SDK with the Privy provider and resolves the Privy-backed client. */
    suspend fun initializePrivy(
        privy: Privy,
        rpcEndpoints: Map<Int, String>,
        walletAddress: String? = null,
    ) {
        val sdk = RainSdk.builder()
            .rpcEndpoints(rpcEndpoints)
            .register(PrivyProvider(PrivyConfig(privy = privy, walletAddress = walletAddress)))
            .build()
        rain = sdk
        client = sdk.provider(ProviderId.PRIVY)
    }

    fun reset() {
        runCatching { client?.reset() }
        runCatching { rain?.reset() }
        client = null
        rain = null
    }
}
