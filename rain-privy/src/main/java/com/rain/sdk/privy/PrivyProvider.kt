package com.rain.sdk.privy

import com.rain.sdk.internal.provider.WalletProvider
import com.rain.sdk.provider.Capability
import com.rain.sdk.provider.ProviderContext
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.provider.RainProvider

/**
 * Configuration for the Privy provider.
 *
 * Skeleton: the real Privy embedded-wallet integration (app id, authenticated Privy session /
 * embedded-key handle) will be carried here once the adapter is built out.
 *
 * @param appId The Privy app id.
 */
class PrivyConfig(
    val appId: String,
)

/**
 * Privy adapter — the registrable [RainProvider] for Privy's embedded-key signer.
 *
 * **Skeleton only.** This module exists to prove the modular architecture's thesis: a net-new
 * provider arrives as its own artifact (`rain-privy`) with its own vendor dependency, and costs
 * existing Portal / Turnkey clients nothing. The signing/wallet implementation is not wired yet —
 * [create] returns a [PrivyWalletProvider] whose operations throw [NotImplementedError] until the
 * Privy SDK is integrated.
 */
class PrivyProvider(
    @Suppress("unused") private val config: PrivyConfig,
) : RainProvider {

    override val id: ProviderId get() = ProviderId.PRIVY

    /** Privy holds an exportable embedded key with a recovery flow. */
    override val capabilities: Set<Capability> =
        setOf(Capability.EXPORT, Capability.RECOVERY)

    override suspend fun create(context: ProviderContext): WalletProvider =
        PrivyWalletProvider()
}
