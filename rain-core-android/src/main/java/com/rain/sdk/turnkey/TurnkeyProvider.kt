package com.rain.sdk.turnkey

import com.rain.sdk.internal.provider.WalletProvider
import com.rain.sdk.provider.Capability
import com.rain.sdk.provider.ProviderContext
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.provider.RainProvider
import com.turnkey.core.TurnkeyContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Configuration for the Turnkey provider.
 *
 * Turnkey authentication (passkeys, auth proxy, OAuth, OTP) happens outside Rain via the official
 * Turnkey Kotlin SDK: initialize [TurnkeyContext] in `Application.onCreate()`, complete login, then
 * hand the singleton here.
 *
 * @param turnkey The authenticated `TurnkeyContext` singleton.
 * @param walletAddress Optional explicit EVM address override; when null Rain uses the first
 *                      available Ethereum account from the context.
 */
class TurnkeyConfig(
    val turnkey: TurnkeyContext,
    val walletAddress: String? = null,
)

/**
 * Turnkey adapter — the registrable [RainProvider] for Turnkey's P256-stamper signer.
 *
 * Per the modular-architecture migration this adapter currently lives inside `rain-core-android` (in the
 * `com.rain.sdk.turnkey` package) rather than a standalone `rain-turnkey` module, so core still
 * carries the Turnkey SDK as a dependency for now. The seam is otherwise identical to a true
 * out-of-module adapter: it implements the port and owns all Turnkey-specific wiring.
 */
class TurnkeyProvider internal constructor(
    private val config: TurnkeyConfig,
    private val contextOverride: TurnkeyContextProtocol?,
) : RainProvider {

    constructor(config: TurnkeyConfig) : this(config, contextOverride = null)

    override val id: ProviderId get() = ProviderId.TURNKEY

    /** Turnkey holds EVM + Solana accounts and gates signing behind passkeys/biometrics. */
    override val capabilities: Set<Capability> =
        setOf(Capability.MULTI_CHAIN, Capability.BIOMETRIC_GATE)

    override suspend fun create(context: ProviderContext): WalletProvider {
        val turnkeyContext: TurnkeyContextProtocol =
            contextOverride ?: TurnkeyContextAdapter(config.turnkey)

        val provider = TurnkeyWalletProvider(
            turnkey = turnkeyContext,
            rpcEndpoints = context.rpcEndpoints,
            walletAddressOverride = config.walletAddress,
            chainReader = context.evmChainReader,
            solanaChainReader = context.solanaChainReader,
            tokenStore = context.tokenStore,
        )

        // Probe — ensures Turnkey has an EVM wallet available before the provider is handed out.
        withContext(Dispatchers.IO) {
            provider.getWalletAddress()
        }

        return provider
    }
}
