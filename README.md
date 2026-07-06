# Rain SDK for Android

Android SDK that integrates [Portal](https://portalhq.io) MPC wallet or [Turnkey](https://turnkey.com) with Rain collateral withdrawal: build EIP-712 messages, compose withdrawal transactions, sign and submit via a registered wallet provider, and estimate fees.

- **Portal wallet integration** — Register a `PortalProvider` with a Portal session token and resolve a client; use the connected MPC wallet for signing and sending transactions.
- **Turnkey wallet integration** — Register a `TurnkeyProvider` with an authenticated `TurnkeyContext` (passkeys / auth proxy / OAuth / OTP handled outside Rain by the Turnkey Kotlin SDK). See [docs/TURNKEY_SUPPORT.md](docs/TURNKEY_SUPPORT.md).
- **Wallet-agnostic utilities** — The `transactionBuilder` (EIP-712 message, withdraw calldata) is available straight off `RainSdk` from the configured RPC endpoints, with no wallet provider resolved — use it with your own wallet or backend.
- **Pluggable providers** — Bring your own `WalletProvider` behind a `RainProvider` descriptor and register it; resolve providers by id or by `Capability`.
- **EIP-712 message building** — Build typed data for admin signature required by the collateral contract.
- **Withdrawal transaction building** — Build ABI-encoded withdraw calldata for submission.
- **Full withdrawal flow** — Builds the transaction, signs via the backing provider, and submits; returns the transaction hash.
- **Fee estimation** — Returns the estimated gas cost in the chain's native token (e.g. AVAX).
- **Wallet information** — Get current wallet address and generate a QR code `Bitmap` for it.
- **Balances** — Get native and ERC-20 token balances for the current wallet.
- **Transaction history** — Get transactions for the current wallet with optional pagination and sort order.
- **Send tokens** — Send native or ERC-20 tokens from the current wallet.

## Installation

The SDK is modular (ports & adapters): a vendor-free **`rain-core`** plus one adapter module per
wallet provider. Link only the providers you use — an unselected provider's vendor SDK never enters
your dependency graph.

```kotlin
dependencies {
    // Portal-only app: pulls rain-core transitively. Turnkey is never fetched or shipped.
    implementation("io.github.spartan-quanhongtran:rain-portal:1.0.1")

    // Or, for Turnkey (the Turnkey adapter currently ships inside rain-core):
    // implementation("io.github.spartan-quanhongtran:rain-core:1.0.1")
}
```

| Module        | Contains                                                                 |
|---------------|--------------------------------------------------------------------------|
| `rain-core`   | The `WalletProvider` port, capability model, provider registry, all Rain domain logic, **and the Turnkey adapter** (in `com.rain.sdk.turnkey`, for now). |
| `rain-portal` | The Portal MPC adapter (`PortalProvider`); depends on `rain-core` + `portal-android`. |
| `rain-privy`  | The Privy embedded-key adapter (`PrivyProvider`) — **skeleton**; proves a net-new provider costs existing clients nothing. Operations throw `NotImplementedError` until the Privy SDK is wired. |

## Requirements

- Android SDK 28+ (Turnkey-compatible)
- Kotlin 1.8+

## Quick Start

### 1. Initialize with Portal (full wallet flow)

Use this when you want the SDK to use Portal for signing and sending transactions.

```kotlin
import com.rain.sdk.RainSdk
import com.rain.sdk.portal.PortalConfig
import com.rain.sdk.portal.PortalProvider
import com.rain.sdk.provider.ProviderId

val rain = RainSdk.builder()
    .rpcEndpoints(
        mapOf(
            43114 to "https://avalanche-c-chain-rpc.publicnode.com",
            43113 to "https://avalanche-fuji-c-chain-rpc.publicnode.com"
        )
    )
    .register(PortalProvider(PortalConfig(sessionToken = "<your-portal-session-token>")))
    .build()

// Resolve the Portal-backed client (suspending — resolves the wallet on first access).
val client = rain.provider(ProviderId.PORTAL)
```

### 2. Initialize with Turnkey (full wallet flow)

Use this when you authenticate users with Turnkey (passkeys / auth proxy / OAuth / OTP) and want Rain to sign + send through that session.

Turnkey authentication happens **outside Rain SDK** — the host app drives Turnkey's Kotlin SDK (OTP, passkey, OAuth) and hands the authenticated `TurnkeyContext` to Rain.

```kotlin
import com.rain.sdk.RainSdk
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.turnkey.TurnkeyConfig
import com.rain.sdk.turnkey.TurnkeyProvider
import com.turnkey.core.TurnkeyContext

// Turnkey is initialized in your Application.onCreate() and the user has authenticated
// (TurnkeyContext.session.value is non-null).

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

**Reference auth glue:** [`app/src/main/java/com/rain/sdk/sample/TurnkeyAuthSample.kt`](app/src/main/java/com/rain/sdk/sample/TurnkeyAuthSample.kt) shows the full email-OTP flow (init, send OTP, verify, ensure wallet) you'd write in your own app. Copy/adapt that file.

See [docs/TURNKEY_SUPPORT.md](docs/TURNKEY_SUPPORT.md) for the full Turnkey integration guide.

### 3. Bring your own provider, or resolve by capability

The registry is designed for the multi-provider case; a single-provider app is just the trivial
`N = 1` instance of it. Register your own `WalletProvider` adapter (Coinbase, Privy, Dynamic, a custom
MPC stack) behind a `RainProvider` descriptor, then resolve providers by id or by capability:

```kotlin
import com.rain.sdk.provider.Capability

// …or resolve the first registered provider with a given capability
val exporter = rain.first { Capability.EXPORT in it.capabilities }
```

### 4. Get Wallet Address

```kotlin
val address = client.getWalletAddress()
```

### 5. Check Balances

```kotlin
import com.rain.sdk.models.Token
import com.rain.sdk.models.TokenInfo

// `client` is the RainClient resolved in Quick Start (rain.provider(...))

// Native token balance (e.g. AVAX) — exact rawAmount plus resolved decimals/symbol/name
val native = client.getBalance(chainId = 43114, token = Token.Native)
println("${native.formatted} ${native.symbol}") // e.g. "1.5 AVAX"

// Specific ERC-20 token balance (e.g. USDC). The SDK resolves the token's decimals/symbol
// itself, so you only pass the contract address (case-insensitive).
val usdc = client.getBalance(chainId = 43114, token = Token.Contract("0x..."))

// All non-zero balances on a chain (native always included)
val balances: List<Balance> = client.getTokenBalances(chainId = 43114)

// Every configured chain, flattened into one list — each Balance carries its own chainId
val all: List<Balance> = client.getAllBalances()

// Optionally register extra tokens so their metadata resolves without an on-chain lookup
client.registerTokens(
    listOf(TokenInfo(chainId = 43114, address = "0x...", symbol = "FOO", decimals = 18))
)
```

Each `Balance` exposes `rawAmount` (`BigInteger`, exact base units), `decimals`, `symbol`,
`name`, plus derived `decimalAmount` (`BigDecimal`) and `formatted` (`String`) for display.

### 6. Send Tokens

```kotlin
import java.math.BigDecimal

// `client` is the RainClient resolved in Quick Start (rain.provider(...))

// Send native token (AVAX)
val result = client.sendNativeToken(
    chainId = 43114,
    toAddress = "0x...",
    amount = BigDecimal("0.1")
)
println("Tx Hash: ${result.transactionHash}")

// Send ERC-20 token (e.g. USDC). Omit decimals to let the SDK resolve them.
val result = client.sendToken(
    chainId = 43114,
    contractAddress = "0x...",
    toAddress = "0x...",
    amount = BigDecimal("100.0")
)
```

### 7. Withdraw Collateral

The SDK uses `RainWithdrawAddresses` and `RainAdminSignature` to group withdrawal parameters:

```kotlin
import com.rain.sdk.models.RainWithdrawAddresses
import com.rain.sdk.models.RainAdminSignature
import java.math.BigDecimal

val addresses = RainWithdrawAddresses(
    proxyAddress = "0x...",
    controllerAddress = "0x...",
    tokenAddress = "0x...",
    recipientAddress = "0x..."
)

val adminSignature = RainAdminSignature(
    salt = "...",
    signature = "...",
    expiresAt = "2024-12-31T23:59:59Z"
)

// Auto-send: sign and submit via the backing provider, returns tx hash
val result = client.withdrawCollateral(
    chainId = 43114,
    addresses = addresses,
    amount = BigDecimal("100.0"),
    decimals = 6,
    adminSignature = adminSignature,
    autoSend = true
)
println("Tx Hash: ${result.transactionHash}")

// Manual: get raw transaction data for custom submission
val result = client.withdrawCollateral(
    chainId = 43114,
    addresses = addresses,
    amount = BigDecimal("100.0"),
    decimals = 6,
    adminSignature = adminSignature,
    autoSend = false
)
println("Tx Data: ${result.transactionData}")
```

### 8. Estimate Gas

```kotlin
val fee = client.estimateGas(
    chainId = 43114,
    from = walletAddress,
    to = controllerAddress,
    data = transactionData
)
println("Estimated fee: $fee AVAX")
```

### 9. Transaction History

```kotlin
import com.rain.sdk.models.RainTransactionOrder

val result = client.getTransactions(
    chainId = 43114,
    limit = 20,
    offset = 0,
    order = RainTransactionOrder.DESC
)

result.transactions.forEach { tx ->
    println("${tx.hash} — ${tx.from} → ${tx.to}: ${tx.value}")
}
```

### 10. QR Code Generation

```kotlin
val bitmap = client.generateAddressQRCode(
    width = 500,
    height = 500
)
// Use the bitmap in an ImageView
imageView.setImageBitmap(bitmap)
```

## Documentation

For a complete reference of all public methods, parameters, types, and error codes, see the [Method Reference](docs/METHODS.md).

## License

See the [LICENSE](LICENSE) file for details.
