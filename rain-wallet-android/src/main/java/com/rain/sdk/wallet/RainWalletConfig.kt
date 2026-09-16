package com.rain.sdk.wallet

import android.app.Application
import com.rain.sdk.turnkey.TurnkeyConfig

/**
 * Configuration for the Rain wallet provider. The wallet backend identity (Rain's organization and
 * its authentication configuration) is embedded in the SDK; a host configures only behaviour. Every
 * parameter has a default. The hook is passed by name: the parameter order matches the SDK's other
 * provider configs, so it is not a trailing lambda.
 *
 * @param walletAddress Optional explicit EVM address override; when null the wallet's first
 *                      Ethereum account is used. The override is taken verbatim, with no shape or
 *                      checksum check, as the account the SDK signs with and reads balances, history
 *                      and fees for. An address that names no Ethereum account of the wallet makes
 *                      every key export fail with `RainError.InvalidConfig` (`RAIN_102`), while
 *                      reads keep reporting that address rather than the wallet's.
 * @param sessionPolicy Expiry/refresh/retry behaviour for the session guarding every wallet call.
 * @param onSessionExpired Re-auth hook: invoked once per session death when the session dies and
 *                         cannot be refreshed, whether that is discovered during a wallet call or
 *                         by the passive session watcher. May run on the calling coroutine's
 *                         thread or a watcher thread; hop to the main thread before touching UI,
 *                         and never call back into the SDK synchronously from it. Restart
 *                         authentication from here ([RainProvider.sendLoginCode] then
 *                         [RainProvider.confirmLoginCode]). A deliberate [RainProvider.logout]
 *                         does not fire it. The provider holds the hook for its whole life, so
 *                         capture no Activity or ViewModel in it.
 * @param sponsorGas When true, every send on a supported chain is fee-sponsored: the wallet
 *                   backend builds and pays the network fee, fee estimates return zero, and the
 *                   descriptor advertises `Capability.GAS_SPONSORSHIP`. Sponsorship cost passes
 *                   through to the partner. Two limits: rent for a first-time recipient's Solana
 *                   token account is a separate backend setting and stays with the sender, and a
 *                   sponsored send runs no client-side revert preflight, so a failure surfaces as
 *                   the backend's failed status after broadcast. Defaults to true, which is the
 *                   product; pass false to have users pay their own fees.
 */
class RainWalletConfig(
    val walletAddress: String? = null,
    val sessionPolicy: RainWalletSessionPolicy = RainWalletSessionPolicy(),
    val onSessionExpired: (() -> Unit)? = null,
    val sponsorGas: Boolean = true,
) {
    /** Every field, for logs and crash reports; the hook reads as set or null. Nothing here is secret. */
    override fun toString(): String =
        "RainWalletConfig(walletAddress=$walletAddress, sessionPolicy=$sessionPolicy, " +
            "onSessionExpired=${if (onSessionExpired != null) "set" else "null"}, sponsorGas=$sponsorGas)"
}

/**
 * The backing adapter's configuration for this wallet: Rain's embedded backend identity plus the
 * host's behaviour, field for field. A function of its own so the mapping is testable.
 */
internal fun RainWalletConfig.toBacking(application: Application): TurnkeyConfig = TurnkeyConfig(
    application = application,
    organizationId = RainWalletBackend.ORGANIZATION_ID,
    authProxyConfigId = RainWalletBackend.AUTH_CONFIG_ID,
    walletAddress = walletAddress,
    sessionPolicy = sessionPolicy.toBacking(),
    onSessionExpired = onSessionExpired,
    sponsorGas = sponsorGas,
)
