package com.rain.sdk.sample

import android.app.Application
import com.rain.sdk.RainSdk
import com.rain.sdk.interfaces.RainClient
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.portal.PortalConfig
import com.rain.sdk.portal.PortalProvider
import com.rain.sdk.privy.PrivyConfig
import com.rain.sdk.privy.PrivyProvider
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.turnkey.TurnkeyConfig
import com.rain.sdk.turnkey.TurnkeyProvider
import io.portalhq.android.Portal
import io.portalhq.android.storage.mobile.PortalNamespace
import io.privy.sdk.Privy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * App-side holder around the modular [RainSdk].
 *
 * The sample picks a provider at runtime (Portal, Turnkey or Privy), so it builds the [RainSdk] lazily
 * once the user supplies credentials and then keeps the resolved [RainClient] here. Screens read
 * the real [client] directly — there is no fake `RainClient` wrapper. [client] is `null` until one
 * of the `initialize*` helpers has run.
 */
@Suppress("TooManyFunctions") // sample glue: one entry point per provider flow step
class RainSession {

    var rain: RainSdk? = null
        private set

    /** The provider-backed client, or `null` before initialization. */
    var client: RainClient? = null
        private set

    val isInitialized: Boolean get() = client?.isInitialized == true

    /** Typed because the session surface lives on the provider, not on [RainClient]. */
    private sealed interface ActiveProvider {
        data class Portal(val provider: PortalProvider) : ActiveProvider
        data class Turnkey(val provider: TurnkeyProvider) : ActiveProvider
        data class Privy(val provider: PrivyProvider) : ActiveProvider
    }

    private val activeProvider = MutableStateFlow<ActiveProvider?>(null)

    /** The active provider's `sessionState` as a display model; `null` before resolution. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val sessionStatus: Flow<WalletSessionStatus?> = activeProvider.flatMapLatest { active ->
        when (active) {
            null -> flowOf(null)
            is ActiveProvider.Portal -> active.provider.sessionState.map { it.toStatus() }
            is ActiveProvider.Turnkey -> active.provider.sessionState.map { it.toStatus() }
            is ActiveProvider.Privy -> active.provider.sessionState.map { it.toStatus() }
        }
    }

    /** Forces a refresh on the active provider; `RainError.TokenExpired` means re-auth. */
    suspend fun refreshSession() {
        when (val active = activeProvider.value) {
            null -> throw RainError.SdkNotInitialized()
            is ActiveProvider.Turnkey -> active.provider.refreshSession()
            is ActiveProvider.Privy -> active.provider.refreshSession()
            is ActiveProvider.Portal -> active.provider.refreshSession()
        }
    }

    /** Portal only: installs a host-minted token for the same Portal client. */
    suspend fun updatePortalSessionToken(sessionToken: String) {
        val active = activeProvider.value as? ActiveProvider.Portal
            ?: throw RainError.SdkNotInitialized()
        active.provider.updateSessionToken(sessionToken)
    }

    /** A discarded SDK must never fire its providers' hooks. */
    private fun closeActiveProvider() {
        runCatching { rain?.close() }
        activeProvider.value = null
        // A closed SDK must not read as initialized on the next Activity recreation.
        client = null
        rain = null
    }

    // Rain API credentials entered in the Home screen. Stashed here because the SDK is built
    // lazily — applied via the builder at build time and pushed through configureRainApi when
    // the SDK already exists.
    private var rainApiKey: String = ""
    private var rainUserId: String = ""

    /** True once an Api-Key and userId are available (SDK built or not). */
    val isRainApiConfigured: Boolean
        get() = rain?.isRainApiConfigured ?: (rainApiKey.isNotBlank() && rainUserId.isNotBlank())

    /** Stores the Rain Api-Key + userId and forwards them to the SDK when it exists. */
    fun configureRainApi(apiKey: String, userId: String) {
        rainApiKey = apiKey.trim()
        rainUserId = userId.trim()
        rain?.configureRainApi(rainApiKey, rainUserId)
    }

    // Shared builder config: Rain API credentials plus naming for every chain's testnet token,
    // applied identically whichever provider is registered (see WalletChain.defaultTokenInfo).
    private fun RainSdk.Builder.withSharedConfig(): RainSdk.Builder = apply {
        registerTokens(WalletChain.selectable.map { it.defaultTokenInfo })
        // Selects the Rain API host and, with it, the chains Auth Pull approvals are allowed on.
        rainApiEnvironment(SampleEnvironment.rainApi)
        authPullConfig(SampleEnvironment.authPullConfig)
        if (rainApiKey.isNotBlank() && rainUserId.isNotBlank()) {
            rainApiCredentials(rainApiKey, rainUserId)
        }
    }

    // Captured during provider resolution so the sample can reach Portal-only APIs the Rain
    // surface doesn't expose (wallet generation, backup/recover).
    private var portal: Portal? = null

    /** Builds the SDK with the Portal provider and resolves the Portal-backed client. */
    suspend fun initializePortal(
        sessionToken: String,
        rpcEndpoints: Map<Int, String>,
        chainId: Int? = null,
        onSessionTokenNeeded: (suspend () -> String?)? = null,
        onSessionExpired: (() -> Unit)? = null,
    ) {
        closeActiveProvider()
        val provider = PortalProvider(
            config = PortalConfig(
                sessionToken = sessionToken,
                chainId = chainId,
                onSessionTokenNeeded = onSessionTokenNeeded,
                onSessionExpired = onSessionExpired,
            ),
            // Re-fired after every token refresh.
            onPortalCreated = { portal = it },
        )
        val sdk = RainSdk.builder()
            .rpcEndpoints(rpcEndpoints)
            .register(provider)
            .withSharedConfig()
            .build()
        rain = sdk
        client = sdk.provider(ProviderId.PORTAL)
        activeProvider.value = ActiveProvider.Portal(provider)
    }

    /**
     * Ensures this device can sign for the Portal client, running MPC key generation on first
     * use. Returns true if a wallet was created, false if one was already usable here.
     */
    suspend fun ensurePortalWallet(): Boolean {
        val portal = portal ?: error("Portal not initialized")

        // Two independent facts: the signing share lives in this device's keychain, the wallet
        // itself lives on the Portal client. Creating on a client that already has one fails.
        // An empty keychain throws rather than returning false, and that is the ordinary
        // first-run case — so a failed read here means "not on device", not an error.
        val onDevice = runCatching { portal.isWalletOnDeviceOrThrow(PortalNamespace.EIP155) }
            .getOrDefault(false)
        val onClient = portal.doesWalletExistOrThrow(PortalNamespace.EIP155)
        SampleLog.d("Portal.wallet", "ensurePortalWallet onDevice=$onDevice onClient=$onClient")

        if (onDevice) return false
        if (onClient) {
            error(
                "This Portal client already has a wallet, but its signing share is not on this " +
                    "device. Recover it from a backup, or use a client ID dedicated to this device."
            )
        }

        val created = portal.createWallet { status ->
            SampleLog.d("Portal.wallet", "keygen status=$status")
        }.getOrThrow()
        SampleLog.i("Portal.wallet", "created wallet eth=${created.ethereumAddress}")
        return true
    }

    /**
     * The managed Turnkey provider, created by [prepareTurnkey]. Authentication (`sendLoginCode` /
     * `confirmLoginCode`) runs on it before Rain is initialized.
     */
    var turnkeyProvider: TurnkeyProvider? = null
        private set

    /**
     * Creates the managed Turnkey provider. The SDK owns Turnkey configuration and the one-time-code
     * flow, email or SMS, from here on — the sample never touches the vendor SDK. The previous
     * provider is retired first, whether or not it was built into an SDK: two providers must never
     * share the process-wide vendor context.
     */
    fun prepareTurnkey(
        application: Application,
        organizationId: String,
        authProxyConfigId: String,
        onSessionExpired: (() -> Unit)? = null,
    ): TurnkeyProvider {
        val previous = turnkeyProvider
        if (previous != null) {
            if ((activeProvider.value as? ActiveProvider.Turnkey)?.provider === previous) {
                closeActiveProvider()
            } else {
                previous.close()
            }
        }
        val provider = TurnkeyProvider(
            TurnkeyConfig(
                application = application,
                organizationId = organizationId,
                authProxyConfigId = authProxyConfigId,
                onSessionExpired = onSessionExpired,
            )
        )
        turnkeyProvider = provider
        return provider
    }

    /**
     * Builds the SDK with the prepared, authenticated Turnkey provider and resolves the
     * Turnkey-backed client. When that same provider is already registered (a second tap), the
     * existing SDK is kept — rebuilding would close the very provider about to be used. Each new
     * login goes through [prepareTurnkey] first, so the sample otherwise starts from a clean SDK;
     * the SDK itself also supports keeping one provider across logins (a fresh login evicts the
     * previous user's cached accounts).
     */
    suspend fun initializeTurnkey(rpcEndpoints: Map<Int, String>) {
        val provider = turnkeyProvider
            ?: throw RainError.InvalidConfig("Call prepareTurnkey before initializeTurnkey")
        val existing = rain
        if (existing != null && (activeProvider.value as? ActiveProvider.Turnkey)?.provider === provider) {
            client = existing.provider(ProviderId.TURNKEY)
            return
        }
        closeActiveProvider()
        val sdk = RainSdk.builder()
            .rpcEndpoints(rpcEndpoints)
            .register(provider)
            .withSharedConfig()
            .build()
        rain = sdk
        client = sdk.provider(ProviderId.TURNKEY)
        activeProvider.value = ActiveProvider.Turnkey(provider)
    }

    /**
     * Managed Turnkey logout: clears the stored session so the next run needs a fresh code.
     * Returns false when the SDK refused, so the caller keeps its own state instead of pretending
     * the device is signed out while the session is still on it.
     */
    suspend fun logoutTurnkey(): Boolean {
        val provider = turnkeyProvider ?: return true
        return try {
            provider.logout()
            true
        } catch (e: RainError) {
            SampleLog.w("Turnkey.session", "logout failed: ${e.message}", e)
            false
        }
    }

    /** Builds the SDK with the Privy provider and resolves the Privy-backed client. */
    suspend fun initializePrivy(
        privy: Privy,
        rpcEndpoints: Map<Int, String>,
        walletAddress: String? = null,
        onSessionExpired: (() -> Unit)? = null,
    ) {
        closeActiveProvider()
        val provider = PrivyProvider(
            PrivyConfig(
                privy = privy,
                walletAddress = walletAddress,
                onSessionExpired = onSessionExpired,
            )
        )
        val sdk = RainSdk.builder()
            .rpcEndpoints(rpcEndpoints)
            .register(provider)
            .withSharedConfig()
            .build()
        rain = sdk
        client = sdk.provider(ProviderId.PRIVY)
        activeProvider.value = ActiveProvider.Privy(provider)
    }

    fun reset() {
        // close() resets and tears the registered providers down, so their session watchers
        // stop and no stale hook survives into the next login.
        runCatching { rain?.close() }
        activeProvider.value = null
        client = null
        rain = null
        portal = null
        // Closing the SDK closed a registered provider; a prepared-but-unbuilt one is closed here.
        turnkeyProvider?.close()
        turnkeyProvider = null
    }
}
