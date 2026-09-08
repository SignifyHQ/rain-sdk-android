# Turnkey Support

Rain SDK for Android supports [Turnkey](https://turnkey.com) as a wallet provider, alongside the Portal MPC adapter. Turnkey ships as the `TurnkeyProvider` adapter, which currently lives inside the `rain-core-android` module (package `com.rain.sdk.turnkey`). Turnkey authentication (passkeys, OAuth, OTP, auth proxy) happens **outside** Rain — the host app uses the official [Turnkey Kotlin SDK](https://docs.turnkey.com/sdks/kotlin/getting-started) to authenticate the user and then hands the live `TurnkeyContext` to Rain via `TurnkeyConfig` for wallet operations.

## Requirements

- `minSdk = 28` (matches Turnkey's requirement).
- Turnkey Kotlin SDK initialized in your `Application.onCreate()` (passkey/auth-proxy/OAuth/OTP flow completed by the host app).
- **JDK 24+** to run unit tests that touch Turnkey types (the Turnkey 2.0.0 AAR ships class-file major version 68 / Java 24). Production Android builds are unaffected — R8/D8 dexes Turnkey's bytecode regardless of host JVM version. The `TurnkeyWalletProviderTest` suite skips itself automatically on JDKs older than 24 via `Assume.assumeTrue`.

## Adding the dependency

The Turnkey artifacts ship transitively with `rain-core-android` via `api(...)`, so consumers don't need to add them explicitly. Internally Rain pulls in:

```
com.turnkey:sdk-kotlin:2.0.0
com.turnkey:http:2.0.0
com.turnkey:types:2.0.0
```

## Architectural split

Rain SDK's public Turnkey surface is exactly one boundary: registering a `TurnkeyProvider(TurnkeyConfig(turnkey, walletAddress))` with the `RainSdk` builder, then resolving `rain.provider(ProviderId.TURNKEY)`. Everything *before* that — `TurnkeyContext.initSuspend`, OTP/passkey/OAuth flows, sub-org provisioning, wallet creation — is host-app code, written against Turnkey's own Kotlin SDK. This split keeps Rain free of Turnkey's auth-UI surface.

| Layer | Who owns it | Examples |
|---|---|---|
| Authentication (pre-register) | Your app | `TurnkeyContext.initSuspend`, `initOtp`, `loginOrSignUpWithOtp`, `createWallet` |
| Hand-off | Boundary | `RainSdk.builder().register(TurnkeyProvider(TurnkeyConfig(turnkeyContext, …)))` → `rain.provider(ProviderId.TURNKEY)` |
| Wallet operations (post-resolve) | Rain SDK | `client.getWalletAddress()`, `getBalance()`, `sendNative()`, `withdrawCollateral()` |

## Reference auth glue (sample app)

The sample app ships a ready-to-copy helper that drives the email-OTP path end-to-end:

**[`app/src/main/java/com/rain/sdk/sample/TurnkeyAuthSample.kt`](../app/src/main/java/com/rain/sdk/sample/TurnkeyAuthSample.kt)**

```kotlin
object TurnkeyAuthSample {
    val context: TurnkeyContext            // hand to TurnkeyConfig / TurnkeyProvider
    val subOrganizationId: String?         // null until login completes

    fun hasActiveSession(): Boolean        // true if a persisted session is still valid

    suspend fun init(app, organizationId, authProxyConfigId)
    suspend fun sendEmailOtp(email): InitOtpResult                                    // returns { otpId, otpEncryptionTargetBundle }
    suspend fun verifyEmailOtp(otpId, otpCode, otpEncryptionTargetBundle, email)      // Turnkey 2.0 encrypts OTP verification to a target key
    suspend fun ensureEthereumWallet(): Boolean                                       // creates one if missing
    suspend fun ensureSolanaWallet(): Boolean                                         // creates one if missing
    suspend fun logout()                                                              // clears all stored sessions
}
```

`HomeViewModel.kt` in the sample then reads as just two things — sample-app auth glue, then Rain SDK calls:

```kotlin
TurnkeyAuthSample.init(app, orgId, authProxyConfigId)

// Resume an existing session if one is still valid; otherwise run OTP.
if (!TurnkeyAuthSample.hasActiveSession()) {
    val otpResult = TurnkeyAuthSample.sendEmailOtp(email)
    // ... user types OTP code into the UI ...
    TurnkeyAuthSample.verifyEmailOtp(
        otpId = otpResult.otpId,
        otpCode = otpCode,
        otpEncryptionTargetBundle = otpResult.otpEncryptionTargetBundle, // Turnkey SDK 2.0
        email = email
    )
}
TurnkeyAuthSample.ensureEthereumWallet()
// Optional, for Solana support:
// TurnkeyAuthSample.ensureSolanaWallet()

val rain = RainSdk.builder()
    .rpcEndpoints(mapOf(43113 to "https://api.avax-test.network/ext/bc/C/rpc"))
    .register(
        TurnkeyProvider(
            TurnkeyConfig(
                turnkey = TurnkeyAuthSample.context,
                walletAddress = null // first Ethereum account from TurnkeyContext.wallets
            )
        )
    )
    .build()

val client = rain.provider(ProviderId.TURNKEY)
```

Copy `TurnkeyAuthSample.kt` into your own app and adapt as needed (swap email OTP for passkey / OAuth by calling the corresponding `TurnkeyContext.*` methods — same shape).

## Initialization (manual / passkey / OAuth path)

If you'd rather not use the helper, you can drive Turnkey directly from your `Application.onCreate()`:

```kotlin
import com.rain.sdk.RainSdk
import com.turnkey.core.TurnkeyContext
import com.turnkey.core.models.TurnkeyConfig  // NOTE: Turnkey's config, not Rain's TurnkeyConfig
import com.turnkey.core.models.AuthConfig

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // 1) Initialize Turnkey first (host-app responsibility).
        //    For OTP and auth-proxy flows, only organizationId + authProxyConfigId are required:
        TurnkeyContext.init(
            app = this,
            config = TurnkeyConfig(
                organizationId = "<your-parent-organization-id>",
                authProxyConfigId = "<your-auth-proxy-config-id>"
            )
        )

        // For passkey / OAuth flows you must also supply an AuthConfig with the relying-party id
        // (passkeys) and/or appScheme (OAuth deep-links):
        //
        // TurnkeyContext.init(
        //     app = this,
        //     config = TurnkeyConfig(
        //         organizationId = "<your-parent-organization-id>",
        //         authProxyConfigId = "<your-auth-proxy-config-id>",
        //         authConfig = AuthConfig(rpId = "<your-rp-id>"),
        //         appScheme = "<your-app-scheme>"
        //     )
        // )

        // Prefer `TurnkeyContext.initSuspend(app, cfg)` inside a coroutine if you need to await
        // session restoration before driving auth — see `TurnkeyAuthSample.init` for an example.

        // 2) Drive your auth flow (passkey / OTP / OAuth) somewhere in the app.
    }
}
```

Once the user is authenticated and `TurnkeyContext.session` is populated, hand it to Rain by
registering a `TurnkeyProvider`:

```kotlin
import com.rain.sdk.RainSdk
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.turnkey.TurnkeyConfig
import com.rain.sdk.turnkey.TurnkeyProvider

val rain = RainSdk.builder()
    .rpcEndpoints(
        mapOf(
            8453 to "https://mainnet.base.org",
            84532 to "https://sepolia.base.org"
        )
    )
    .register(
        TurnkeyProvider(
            TurnkeyConfig(
                turnkey = TurnkeyContext,
                walletAddress = null // omit to use the first Ethereum account from TurnkeyContext.wallets
            )
        )
    )
    .build()

val client = rain.provider(ProviderId.TURNKEY)
```

`rain.provider(...)` is a `suspend` function — resolving the Turnkey provider probes the Turnkey
wallet list and throws `RainError.WalletUnavailable` if no usable Ethereum account is available.
You can register other adapters (e.g. `PortalProvider`) on the same builder and resolve each
independently; providers no longer replace one another.

## What Rain uses Turnkey for

After the Turnkey-backed `client` is resolved, every wallet operation routes through Turnkey:

| Rain operation | Turnkey API used |
|----------------|------------------|
| `client.getWalletAddress()` | `TurnkeyContext.wallets` (first Ethereum account) |
| `client.getBalance(chainId, Token.Native)` | `TurnkeyClient.getWalletAddressBalances` (CAIP-19 `slip44:` filter) on supported chains; RPC `eth_getBalance` otherwise |
| `client.getBalance(chainId, Token.Contract(...))` | RPC `eth_call` (`balanceOf`) |
| `client.getBalances(chainId)` | `TurnkeyClient.getWalletAddressBalances` (CAIP-19) on supported chains; Multicall3 / parallel `eth_call` otherwise |
| `client.sendNative(...)` / `client.sendToken(...)` | `TurnkeyClient.ethSendTransaction` + `getSendTransactionStatus` polling. Only on Turnkey's managed-broadcast chains — other chains (Avalanche, Celo, ZKsync, Plasma, Ink) are read-only and sends throw `RAIN_105` up front. By default (`sponsorGas = true`), every EVM send (transfers, withdrawals, approvals, raw sends) is sponsored by Turnkey Gas Station (minimal payload carrying Turnkey's gas-station nonce for replay protection, fee estimate `0`), and Solana network fees are sponsored too (a zero-SOL sender skips the fee check and dry run; rent for a new recipient token account is a separate Turnkey toggle and stays with the sender). `TurnkeyConfig(sponsorGas = false)` returns to self-paid sends, and is required on a Turnkey organization without sponsorship enabled. |
| `client.withdrawCollateral(...)` | EVM: `TurnkeyContext.signRawPayload` (EIP-712) + `ethSendTransaction`. Solana: core composes the withdrawal, skipping its self-paid dry run while the adapter advertises `GAS_SPONSORSHIP`, then `solSendTransaction`. |
| `client.getTransactions(...)` | `TurnkeyClient.getActivities` (filtered to `ACTIVITY_TYPE_ETH_SEND_TRANSACTION`) |
| `client.estimateGas(...)` | RPC `eth_estimateGas` + `eth_gasPrice` |

On Solana chain ids the same methods route to `TurnkeyClient.solSendTransaction` /
`getWalletAddressBalances` and the Solana account instead — see [Solana notes](#solana-notes).

## Solana notes

The Turnkey adapter is the SDK's multi-chain provider (it advertises `MULTI_CHAIN`): Solana sentinel
chain ids (`RainChain.SOLANA_MAINNET` 900 / `SOLANA_DEVNET` 901 / `SOLANA_TESTNET` 902) route
`getWalletAddress(chainId)`, balances, `sendNative`, `sendToken`, `withdrawCollateral`, and
`getTransactions` to the Turnkey Solana account.

- **Transfers.** Composition and every preflight live in core's `SolanaTransferComposer` (shared with
  the Privy adapter, so the two cannot drift); the adapter only signs and broadcasts. For SPL that
  covers recipient validation, resolving the mint's decimals and owning token program on chain,
  deriving both associated token accounts, creating the recipient's when missing
  (`CreateIdempotent`, ~0.002 SOL rent paid by the sender), fee checks, and a `simulateTransaction`
  dry run (with `sponsorGas` the fee check and dry run are skipped; the rent check stays). Failures
  surface as `TokenNotFound`, `TokenAccountNotFound`,
  `InsufficientTokenBalance`, or `InvalidRecipient`.
- **Balances.** From Turnkey's `get-balances` where it indexes the cluster; where it doesn't (devnet
  in particular), `getTokenBalances` discovers holdings from the node via `getTokenAccountsByOwner`
  against both token programs. Solana keeps token metadata off chain, so symbol / name stay null
  unless the mint is registered.
- **History.** From Turnkey's activity log (`ACTIVITY_TYPE_SOL_SEND_TRANSACTION`) — sends only, and
  the row's hash is the Turnkey status id, not an explorer-resolvable signature.
- **Encoding.** Turnkey hex-decodes `unsignedTransaction` despite the type documenting base64, so
  Rain sends hex. Turnkey returns a status id rather than a signature; Rain polls for it, then
  recovers it from `getSignaturesForAddress` (newer than the pre-send baseline only) and verifies
  via `getTransaction` that the candidate is signed by this wallet (the fee payer on a self-paid
  send, a later signer on a sponsored one) with `err == null`. If the
  baseline read failed or nothing verifiable lands in time, the send surfaces as
  `TransactionPending` carrying the status id — the same contract as EVM — never the status id
  posing as a signature.
- **Collateral withdrawal.** Authorized differently from EVM: the coordinator executor signs a
  keccak-encoded withdraw message off chain (that is the admin signature the Rain API returns). Core
  composes a two-instruction transaction — a native ed25519 proof that the executor signed that exact
  message, then the program's `withdraw_single_signer_collateral_asset` — reading the collateral
  account, its coordinator's executors, and the mint's token program from chain, and deriving the
  collateral-authority PDA and token accounts locally. It simulates, then hands the bytes to the
  adapter, which signs them **as-is**: re-serializing would invalidate the embedded signature.
  `proxyAddress` is the collateral account, `tokenAddress` the SPL mint; single-signer collateral
  only. `prepareWithdrawal` returns the prepared unsigned transaction with its blockhash.

## Signing

EIP-712 signing uses `TurnkeyContext.signRawPayload` with `PAYLOAD_ENCODING_EIP712` + `HASH_FUNCTION_NO_OP`. Rain normalizes the returned `r`, `s`, `v` components into a `0x`-prefixed 65-byte hex signature compatible with `eth_signTypedData_v4` responses (recovery id auto-adjusted to 27/28 range when needed).

## Accessing the Turnkey instance

Rain no longer exposes vendor getters (the old `RainSdk.turnkey` / `client.turnkey` are gone —
core references no concrete vendor type). You already own the `TurnkeyContext` — it's the singleton
you authenticated and passed to `TurnkeyConfig` — so keep your own reference for advanced Turnkey
operations:

```kotlin
val turnkey: TurnkeyContext = TurnkeyAuthSample.context  // the same instance you registered
```

## Error handling

Turnkey-specific errors are mapped into the standard `RainError` hierarchy:

| Turnkey error | Mapped to |
|---------------|-----------|
| `TurnkeyKotlinError.InvalidSession` | `RainError.TokenExpired` |
| Turnkey API HTTP 401 | `RainError.TokenExpired` |
| Turnkey API HTTP 403 | `RainError.Unauthorized` |
| Config / setup errors (`MissingRpId`, `MissingConfigParam`, `ClientNotInitialized`, `InvalidParameter`, `InvalidResponse`, `InvalidMessage`, `InvalidRefreshTTL`, `OAuthStateMismatch`, `KeyAlreadyExists`, `KeyNotFound`) | `RainError.InternalError` |
| Wrapper errors whose underlying cause is a user cancellation | `RainError.UserRejected` |
| Anything else | `RainError.ProviderError` |

The Turnkey Kotlin SDK throws a plain `RuntimeException` for HTTP failures and carries the status
only inside the message, so `ErrorMapper` parses it out. That is a workaround for a vendor gap —
it becomes a typed check once the SDK exposes the status code.

Network errors raised during direct RPC calls (balances, fee estimation) surface as `RainError.NetworkError`.

## Session expiry, refresh, and retry

Turnkey sessions are short-lived JWTs (15 minutes by default) that die silently once expired.
Rain hardens every Turnkey-backed call against this, controlled by `TurnkeySessionPolicy`:

```kotlin
TurnkeyProvider(
    TurnkeyConfig(
        turnkey = turnkeyContext,
        sessionPolicy = TurnkeySessionPolicy(
            refreshBufferSeconds = 60,        // refresh when < 60s of lifetime remain
            autoRefresh = true,               // let Rain call Turnkey's refreshSession itself
            refreshExpirationSeconds = null,  // TTL for refreshed sessions (null = Turnkey default)
            maxTransientRetries = 2,          // backoff retries for 5xx/429/network on reads
            initialRetryDelayMs = 500,
            maxRetryDelayMs = 4_000,
        ),
        onSessionExpired = {
            // Re-auth hook: the session died and could not be refreshed. Fired once per
            // session death, on a background thread. Route the user back to login.
        },
    )
)
```

What every wallet call now does:

1. **Expiry check** — the session's JWT expiry is checked before the request. An
   already-expired session throws `RainError.TokenExpired` (or is refreshed first, see below)
   instead of burning a round-trip on a guaranteed 401.
2. **Proactive refresh** — with `autoRefresh` on (the default), a session expired or inside
   `refreshBufferSeconds` of expiry is refreshed through Turnkey's `refreshSession` before the
   call. Refreshes are single-flighted: concurrent calls share one refresh.
3. **Refresh-on-401** — a call rejected with HTTP 401 / `InvalidSession` is refreshed and
   retried exactly once. A 401 means Turnkey rejected the request before executing it, so this
   is safe for sends too. A second 401 surfaces as `RainError.TokenExpired`.
4. **Transient backoff** — idempotent reads (balances, history, transaction-status polls)
   retry HTTP 5xx/429/408 and network I/O failures with exponential backoff. Sends and signing
   are never retried on transient failures.
5. **Re-auth hook** — when the session dies for good (refresh failed, or Turnkey's own expiry
   timer cleared it while the app was idle), `onSessionExpired` fires once — even with no Rain
   call in flight, via a passive watcher over Turnkey's auth state.

With `autoRefresh = false` Rain never touches the session: expired sessions and 401s surface
as `RainError.TokenExpired` immediately and refresh/re-auth is entirely the host's job.

### Observing session state

`TurnkeyProvider` exposes the session as seen at the Rain boundary:

```kotlin
val provider = TurnkeyProvider(TurnkeyConfig(turnkeyContext))

provider.currentSessionState()  // Loading | Active(expiresAtEpochSeconds) | Expired | Unauthenticated

scope.launch {
    provider.sessionState.collect { state ->
        if (state is TurnkeySessionState.Expired || state is TurnkeySessionState.Unauthenticated) {
            // show re-login UI
        }
    }
}

provider.refreshSession()  // manual refresh; throws RainError.TokenExpired when it fails
```

`sessionState` emits on every Turnkey auth/session change and additionally re-checks when an
active session passes its expiry instant, so a silent death is observable without polling.

When `onSessionExpired` is set, resolving the provider starts a passive watcher over the
process-wide Turnkey singleton. A host that rebuilds the SDK per login should call
`provider.close()` on the provider it is discarding so a stale watcher cannot fire.

Reference: the sample app's `RainSession.kt`, `WalletSessionStatus.kt` and the Home screen's
session card (`HomeScreen.kt`, `SessionSection`).

## Registering alongside Portal

Turnkey and Portal are no longer mutually exclusive. Register both adapters on the same builder and
resolve each to its own `RainClient` — one SDK instance, two independent provider-bound clients:

```kotlin
val rain = RainSdk.builder()
    .rpcEndpoints(endpoints)
    .register(PortalProvider(PortalConfig(portalSessionToken)))
    .register(TurnkeyProvider(TurnkeyConfig(turnkeyContext)))
    .build()

val portalClient = rain.provider(ProviderId.PORTAL)
val turnkeyClient = rain.provider(ProviderId.TURNKEY)
```

Each client is bound to its provider for its lifetime; there is no "active provider" to swap.

## Bouncy Castle dependency conflict (downstream consumers)

Turnkey (via `com.turnkey:crypto` and `com.turnkey:encoding`) depends on **`org.bouncycastle:bcprov-jdk15to18:1.82`**, while web3j 4.10 (a transitive dependency of rain-core-android) depends on **`org.bouncycastle:bcprov-jdk18on:1.73`**. Both artifacts publish overlapping `org.bouncycastle.*` class names, so dex-ing them together fails with errors like:

```
Duplicate class org.bouncycastle.asn1.pkcs.EncryptionScheme found in modules
  bcprov-jdk15to18-1.82.jar -> jetified-bcprov-jdk15to18-1.82 (org.bouncycastle:bcprov-jdk15to18:1.82)
  bcprov-jdk18on-1.73.jar  -> jetified-bcprov-jdk18on-1.73  (org.bouncycastle:bcprov-jdk18on:1.73)
```

The two artifacts are parallel builds of the same library for different JDK targets — their class APIs are interchangeable. Rain SDK pins everyone to `bcprov-jdk15to18:1.82` (Turnkey's choice, also the newer version).

**Gradle consumers** (resolve via Module Metadata): no action required. Rain SDK publishes a `compileOnly`/`runtime` exclusion against `bcprov-jdk18on` on its web3j dependency, and Gradle inherits it.

**If your build still hits the duplicate-class error** (older Gradle, Maven POM-only resolution, or you depend on web3j directly), add the exclusion in your own module:

```kotlin
// build.gradle.kts
configurations.all {
    exclude(group = "org.bouncycastle", module = "bcprov-jdk18on")
}
```

Or, if you'd rather scope it to a specific dependency:

```kotlin
implementation("org.web3j:core:4.10.0") {
    exclude(group = "org.bouncycastle", module = "bcprov-jdk18on")
}
```

Groovy DSL equivalent:

```groovy
configurations.all {
    exclude group: 'org.bouncycastle', module: 'bcprov-jdk18on'
}
```

Maven POM equivalent (for non-Gradle consumers):

```xml
<dependency>
    <groupId>org.web3j</groupId>
    <artifactId>core</artifactId>
    <version>4.10.0</version>
    <exclusions>
        <exclusion>
            <groupId>org.bouncycastle</groupId>
            <artifactId>bcprov-jdk18on</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

If you have a legitimate need for `bcprov-jdk18on` (e.g. another library you control), swap the exclusion the other way and ensure all consumers compile against the same single BC artifact.
