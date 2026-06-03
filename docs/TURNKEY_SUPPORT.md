# Turnkey Support

Rain SDK for Android supports [Turnkey](https://turnkey.com) as a wallet provider, alongside the existing Portal MPC integration. Turnkey authentication (passkeys, OAuth, OTP, auth proxy) happens **outside** Rain — the host app uses the official [Turnkey Kotlin SDK](https://docs.turnkey.com/sdks/kotlin/getting-started) to authenticate the user and then hands the live `TurnkeyContext` to Rain for wallet operations.

## Requirements

- `minSdk = 28` (matches Turnkey's requirement).
- Turnkey Kotlin SDK initialized in your `Application.onCreate()` (passkey/auth-proxy/OAuth/OTP flow completed by the host app).
- **JDK 24+** to run unit tests that touch Turnkey types (the Turnkey 1.0.2 AAR ships class-file major version 68 / Java 24). Production Android builds are unaffected — R8/D8 dexes Turnkey's bytecode regardless of host JVM version. The `TurnkeyWalletProviderTest` suite skips itself automatically on JDKs older than 24 via `Assume.assumeTrue`.

## Adding the dependency

The Turnkey artifacts ship transitively with `rain-sdk` via `api(...)`, so consumers don't need to add them explicitly. Internally Rain pulls in:

```
com.turnkey:sdk-kotlin:1.0.2
com.turnkey:http:1.0.2
com.turnkey:types:1.0.2
```

## Architectural split

Rain SDK's public Turnkey surface is exactly one entry point: `RainClient.initializeTurnkey(turnkey, rpcEndpoints, chainId, walletAddress)`. Everything *before* that call — `TurnkeyContext.initSuspend`, OTP/passkey/OAuth flows, sub-org provisioning, wallet creation — is host-app code, written against Turnkey's own Kotlin SDK. This split keeps Rain free of Turnkey's auth-UI surface.

| Layer | Who owns it | Examples |
|---|---|---|
| Authentication (pre-init) | Your app | `TurnkeyContext.initSuspend`, `initOtp`, `loginOrSignUpWithOtp`, `createWallet` |
| Hand-off | Boundary call | `rainClient.initializeTurnkey(turnkeyContext, …)` |
| Wallet operations (post-init) | Rain SDK | `rainClient.getAddress()`, `getBalance()`, `sendNativeToken()`, `withdrawCollateral()` |

## Reference auth glue (sample app)

The sample app ships a ready-to-copy helper that drives the email-OTP path end-to-end:

**[`app/src/main/java/com/rain/sdk/sample/TurnkeyAuthSample.kt`](../app/src/main/java/com/rain/sdk/sample/TurnkeyAuthSample.kt)**

```kotlin
object TurnkeyAuthSample {
    val context: TurnkeyContext            // hand to RainClient.initializeTurnkey
    val subOrganizationId: String?         // null until login completes

    suspend fun init(app, organizationId, authProxyConfigId)
    suspend fun sendEmailOtp(email): String                       // returns otpId
    suspend fun verifyEmailOtp(otpId, otpCode, email)
    suspend fun ensureEthereumWallet(): Boolean                   // creates one if missing
}
```

`HomeViewModel.kt` in the sample then reads as just two things — sample-app auth glue, then Rain SDK calls:

```kotlin
TurnkeyAuthSample.init(app, orgId, authProxyConfigId)
val otpId = TurnkeyAuthSample.sendEmailOtp(email)
// ... user types OTP code into the UI ...
TurnkeyAuthSample.verifyEmailOtp(otpId, otpCode, email)
TurnkeyAuthSample.ensureEthereumWallet()

rainClient.initializeTurnkey(
    turnkey = TurnkeyAuthSample.context,
    rpcEndpoints = mapOf(43113 to "https://api.avax-test.network/ext/bc/C/rpc"),
    chainId = 43113,
    walletAddress = null
)
```

Copy `TurnkeyAuthSample.kt` into your own app and adapt as needed (swap email OTP for passkey / OAuth by calling the corresponding `TurnkeyContext.*` methods — same shape).

## Initialization (manual / passkey / OAuth path)

If you'd rather not use the helper, you can drive Turnkey directly from your `Application.onCreate()`:

```kotlin
import com.rain.sdk.RainSdk
import com.turnkey.core.TurnkeyContext
import com.turnkey.core.models.TurnkeyConfig
import com.turnkey.core.models.AuthConfig

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // 1) Initialize Turnkey first (host-app responsibility).
        TurnkeyContext.init(
            app = this,
            config = TurnkeyConfig(
                authProxyConfigId = "<your-auth-proxy-config-id>",
                organizationId = "<your-parent-organization-id>",
                appScheme = "<your-app-scheme>",
                authConfig = AuthConfig(rpId = "<your-rp-id>")
            )
        )

        // 2) Drive your auth flow (passkey / OTP / OAuth) somewhere in the app.
    }
}
```

Once the user is authenticated and `TurnkeyContext.session` is populated, hand it to Rain:

```kotlin
val client = RainSdk.getInstance().client

client.initializeTurnkey(
    turnkey = TurnkeyContext,
    rpcEndpoints = mapOf(
        43114 to "https://avalanche-c-chain-rpc.publicnode.com",
        43113 to "https://avalanche-fuji-c-chain-rpc.publicnode.com"
    ),
    walletAddress = null // omit to use the first Ethereum account from TurnkeyContext.wallets
)
```

`initializeTurnkey` is a `suspend` function — it probes the Turnkey wallet list during init and throws `RainError.ProviderError` if no usable Ethereum account is available.

## What Rain uses Turnkey for

After `initializeTurnkey`, every wallet operation routes through Turnkey:

| Rain operation | Turnkey API used |
|----------------|------------------|
| `client.getAddress()` | `TurnkeyContext.wallets` (first Ethereum account) |
| `client.getBalance(chainId, Token.Native)` | `TurnkeyClient.getWalletAddressBalances` (CAIP-19 `slip44:` filter) on supported chains; RPC `eth_getBalance` otherwise |
| `client.getBalance(chainId, Token.Contract(...))` | RPC `eth_call` (`balanceOf`) |
| `client.getBalances(chainId)` | `TurnkeyClient.getWalletAddressBalances` (CAIP-19) on supported chains; Multicall3 / parallel `eth_call` otherwise |
| `client.sendNativeToken(...)` / `client.sendToken(...)` | `TurnkeyClient.ethSendTransaction` + `getSendTransactionStatus` polling |
| `client.withdrawCollateral(...)` | `TurnkeyContext.signRawPayload` (EIP-712) + `ethSendTransaction` |
| `client.getTransactions(...)` | `TurnkeyClient.getActivities` (filtered to `ACTIVITY_TYPE_ETH_SEND_TRANSACTION`) |
| `client.estimateGas(...)` | RPC `eth_estimateGas` + `eth_gasPrice` |

## Signing

EIP-712 signing uses `TurnkeyContext.signRawPayload` with `PAYLOAD_ENCODING_EIP712` + `HASH_FUNCTION_NO_OP`. Rain normalizes the returned `r`, `s`, `v` components into a `0x`-prefixed 65-byte hex signature compatible with `eth_signTypedData_v4` responses (recovery id auto-adjusted to 27/28 range when needed).

## Accessing the Turnkey instance

After init you can still reach the Turnkey singleton through Rain for advanced operations:

```kotlin
val turnkey: TurnkeyContext = RainSdk.getInstance().turnkey
// or
val turnkey = RainSdk.getInstance().client.turnkey
```

## Error handling

Turnkey-specific errors are mapped into the standard `RainError` hierarchy:

| Turnkey error | Mapped to |
|---------------|-----------|
| `TurnkeyKotlinError.InvalidSession` | `RainError.TokenExpired` |
| Anything wrapping a user cancellation | `RainError.UserRejected` |
| Anything else | `RainError.ProviderError` |

Network errors raised during direct RPC calls (balances, fee estimation) surface as `RainError.NetworkError`.

## Mutual exclusion with Portal

`initializeTurnkey` and `initializePortal` are mutually exclusive — calling one swaps the active wallet provider and clears the other. The most recently called wins.

## Bouncy Castle dependency conflict (downstream consumers)

Turnkey (via `com.turnkey:crypto` and `com.turnkey:encoding`) depends on **`org.bouncycastle:bcprov-jdk15to18:1.82`**, while web3j 4.10 (a transitive dependency of rain-sdk) depends on **`org.bouncycastle:bcprov-jdk18on:1.73`**. Both artifacts publish overlapping `org.bouncycastle.*` class names, so dex-ing them together fails with errors like:

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
