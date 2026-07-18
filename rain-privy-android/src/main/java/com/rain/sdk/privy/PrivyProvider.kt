package com.rain.sdk.privy

import com.rain.sdk.internal.provider.WalletProvider
import com.rain.sdk.provider.Capability
import com.rain.sdk.provider.ProviderContext
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.provider.RainProvider
import io.privy.sdk.Privy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Configuration for the Privy provider.
 *
 * Privy authentication (SMS / email / OAuth / passkey) and `Privy.init` happen outside Rain via the
 * official Privy Android SDK: initialize the [Privy] singleton in `Application.onCreate()`, log the
 * user in, ensure an embedded Ethereum wallet exists (`user.createEthereumWallet()`), then hand the
 * singleton here — mirroring how `TurnkeyConfig` takes an authenticated `TurnkeyContext`.
 *
 * @param privy The initialized, authenticated `Privy` singleton.
 * @param walletAddress Optional explicit embedded-wallet address; when null Rain uses the user's
 *                      first embedded Ethereum wallet.
 */
class PrivyConfig(
    val privy: Privy,
    val walletAddress: String? = null,
)

/**
 * Privy adapter — the registrable [RainProvider] for Privy's embedded-key signer.
 *
 * Lives in the `rain-privy-android` module and owns the Privy SDK as a dependency. Custody (signing,
 * broadcasting) routes through Privy's EIP-1193 embedded-wallet provider; balance/fee reads use
 * Rain's configured RPC. Core never imports Privy; linking this module is what pulls it onto the
 * classpath.
 */
class PrivyProvider(
    private val config: PrivyConfig,
) : RainProvider {

    override val id: ProviderId get() = ProviderId.PRIVY

    /** Privy holds an exportable embedded key with a recovery flow. */
    override val capabilities: Set<Capability> =
        setOf(Capability.EXPORT, Capability.RECOVERY)

    override suspend fun create(context: ProviderContext): WalletProvider {
        val provider = PrivyWalletProvider(
            manager = PrivyManager(config.privy),
            rpcEndpoints = context.rpcEndpoints,
            tokenStore = context.tokenStore,
            walletAddressOverride = config.walletAddress,
        )

        // Probe — ensures Privy has an embedded Ethereum wallet available before handing it out.
        withContext(Dispatchers.IO) {
            provider.getWalletAddress()
        }

        return provider
    }
}
