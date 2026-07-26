package com.rain.sdk.portal

import com.rain.sdk.RainChain
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.provider.WalletProvider
import com.rain.sdk.provider.Capability
import com.rain.sdk.provider.ProviderContext
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.provider.RainProvider
import io.portalhq.android.Portal
import io.portalhq.android.mpc.data.FeatureFlags

/**
 * Configuration for the Portal provider.
 *
 * @param sessionToken A valid Portal session token (Portal API key).
 * @param chainId Optional default chain id for Portal's legacy single-chain operations. When null,
 *                Avalanche Mainnet is used if configured, otherwise the first configured chain.
 */
class PortalConfig(
    val sessionToken: String,
    val chainId: Int? = null,
)

/**
 * Portal adapter — the registrable [RainProvider] for Portal's MPC signer.
 *
 * Lives in the `rain-portal-android` module and owns the `portal-android` dependency as a private
 * detail. Core never imports Portal; linking this module is what pulls Portal onto the classpath.
 *
 * @param onPortalCreated Optional hook invoked with the underlying [Portal] instance once it is
 *   constructed during resolution, for Portal-specific APIs like `backupWallet`/`recoverWallet`.
 */
class PortalProvider internal constructor(
    private val config: PortalConfig,
    private val onPortalCreated: ((Portal) -> Unit)?,
    private val portalManagerFactory: () -> PortalManager,
) : RainProvider {

    constructor(
        config: PortalConfig,
        onPortalCreated: ((Portal) -> Unit)? = null,
    ) : this(config, onPortalCreated, ::PortalManager)

    override val id: ProviderId get() = ProviderId.PORTAL

    /** Portal is an EVM MPC signer with key export/recovery via MPC backups. */
    override val capabilities: Set<Capability> =
        setOf(Capability.EXPORT, Capability.RECOVERY)

    override suspend fun create(context: ProviderContext): WalletProvider {
        // Portal treats the session token as its API key; an empty one would fail every call
        // downstream with an opaque vendor error. Fail fast instead.
        if (config.sessionToken.isBlank()) {
            throw RainError.Unauthorized("Portal session token must not be empty")
        }

        val rpcEndpoints = context.rpcEndpoints

        // Portal addresses chains as CAIP-2 `eip155:<chainId>` identifiers.
        val eip155RpcConfig = rpcEndpoints.entries.associate { (id, url) ->
            "$EIP155_NAMESPACE:$id" to url
        }

        val legacyChainId = config.chainId
            ?: if (rpcEndpoints.containsKey(RainChain.AVALANCHE_MAINNET)) {
                RainChain.AVALANCHE_MAINNET
            } else {
                rpcEndpoints.keys.first()
            }

        val portalManager = portalManagerFactory()
        portalManager.initialize(
            apiKey = config.sessionToken,
            legacyEthChainId = legacyChainId,
            rpcConfig = eip155RpcConfig,
            featureFlags = FeatureFlags(isMultiBackupEnabled = true),
            autoApprove = true,
        )
        onPortalCreated?.invoke(portalManager.getPortalInstance())

        return PortalWalletProvider(portalManager, context.tokenStore)
    }

    private companion object {
        const val EIP155_NAMESPACE = "eip155"
    }
}
