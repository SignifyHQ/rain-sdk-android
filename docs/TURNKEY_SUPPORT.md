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
            43114 to "https://avalanche-c-chain-rpc.publicnode.com",
            43113 to "https://avalanche-fuji-c-chain-rpc.publicnode.com"
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
| `client.sendNative(...)` / `client.sendToken(...)` | `TurnkeyClient.ethSendTransaction` + `getSendTransactionStatus` polling |
| `client.withdrawCollateral(...)` | `TurnkeyContext.signRawPayload` (EIP-712) + `ethSendTransaction` |
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
  dry run. Failures surface as `TokenNotFound`, `TokenAccountNotFound`,
  `InsufficientTokenBalance`, or `InvalidRecipient`.
- **Balances.** From Turnkey's `get-balances` where it indexes the cluster; where it doesn't (devnet
  in particular), `getTokenBalances` discovers holdings from the node via `getTokenAccountsByOwner`
  against both token programs. Solana keeps token metadata off chain, so symbol / name stay null
  unless the mint is registered.
- **History.** From Turnkey's activity log (`ACTIVITY_TYPE_SOL_SEND_TRANSACTION`) — sends only, and
  the row's hash is the Turnkey status id, not an explorer-resolvable signature.
- **Encoding.** Turnkey hex-decodes `unsignedTransaction` despite the type documenting base64, so
  Rain sends hex. Turnkey returns a status id rather than a signature; Rain polls for it and falls
  back to `getSignaturesForAddress`.
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
