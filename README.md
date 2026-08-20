# Rain SDK for Android

Android SDK that connects an MPC or embedded wallet — [Portal](https://portalhq.io),
[Turnkey](https://turnkey.com), or [Privy](https://privy.io) — to Rain collateral: build EIP-712
messages, compose withdrawal transactions, sign and submit via a registered wallet provider, read
balances and history, and estimate fees. Works on EVM chains and Solana.

- **Portal wallet integration** — Register a `PortalProvider` with a Portal session token and resolve a client; use the connected MPC wallet for signing and sending transactions.
- **Turnkey wallet integration** — Register a `TurnkeyProvider` with an authenticated `TurnkeyContext` (passkeys / auth proxy / OAuth / OTP handled outside Rain by the Turnkey Kotlin SDK). See [docs/TURNKEY_SUPPORT.md](docs/TURNKEY_SUPPORT.md).
- **Privy wallet integration** — Register a `PrivyProvider` with an authenticated `Privy` instance; embedded EVM and Solana wallets are used for custody.
- **Solana support** — Native SOL and SPL transfers, balances, history, and collateral withdrawal, on the same `RainClient` methods as EVM. See [Solana](#9-solana).
- **Wallet-agnostic utilities** — The transaction-building methods (EIP-712 message, withdraw calldata) are available straight off `RainSdk` from the configured RPC endpoints, with no wallet provider resolved — use them with your own wallet or backend.
- **Pluggable providers** — Bring your own `WalletProvider` behind a `RainProvider` descriptor and register it; resolve providers by id or by `Capability`.
- **EIP-712 message building** — Build typed data for admin signature required by the collateral contract.
- **Withdrawal transaction building** — Build ABI-encoded withdraw calldata for submission.
- **Full withdrawal flow** — Builds the transaction, signs via the backing provider, and submits; returns the transaction hash. `prepareWithdrawal` builds the same transaction without broadcasting.
- **Fee estimation** — Returns the estimated gas cost in the chain's native token (e.g. AVAX).
- **Wallet information** — Get current wallet address and generate a QR code `Bitmap` for it.
- **Balances** — Get native, ERC-20, and SPL token balances for the current wallet.
- **Transaction history** — Get transactions for the current wallet with optional pagination and sort order.
- **Send tokens** — Send native, ERC-20, or SPL tokens from the current wallet.
- **Exact money handling** — Public money APIs are `BigDecimal`; base-unit conversion is exact and rejects an amount finer than the token's scale rather than truncating it.

## Installation

The SDK is modular (ports & adapters): a vendor-free **`rain-core-android`** plus one adapter module per
wallet provider. Link only the providers you use — an unselected provider's vendor SDK never enters
your dependency graph.

```kotlin
dependencies {
    // Portal-only app: pulls rain-core-android transitively. Turnkey is never fetched or shipped.
    implementation("io.github.spartan-quanhongtran:rain-portal-android:1.0.1")

    // Or, for Turnkey (the Turnkey adapter currently ships inside rain-core-android):
    // implementation("io.github.spartan-quanhongtran:rain-core-android:1.0.1")
}
```

| Module        | Contains                                                                 |
|---------------|--------------------------------------------------------------------------|
| `rain-core-android`   | The `WalletProvider` port, capability model, provider registry, all Rain domain logic, **and the Turnkey adapter** (in `com.rain.sdk.turnkey`, for now). |
| `rain-portal-android` | The Portal MPC adapter (`PortalProvider`); depends on `rain-core-android` + `portal-android`. |
| `rain-privy-android`  | The Privy embedded-key adapter (`PrivyProvider`); depends on `rain-core-android` + `privy-core`. |

## Requirements

- Android SDK 28+ (Turnkey-compatible)
- Kotlin 2.2.x (the SDK is built with Kotlin 2.2.20)

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
val result = client.sendNative(
    chainId = 43114,
    to = "0x...",
    amount = BigDecimal("0.1")
)
println("Tx Hash: ${result.transactionHash}")

// Send ERC-20 token (e.g. USDC). Omit decimals to let the SDK resolve them.
val result = client.sendToken(
    chainId = 43114,
    contractAddress = "0x...",
    to = "0x...",
    amount = BigDecimal("100.0")
)
```

### 7. Rain API: Collateral Contracts & Admin Signature

The SDK talks to the Rain issuing API directly — supply your program **Api-Key** and Rain
**userId** and it handles session (CST) minting, caching, and refresh internally. Credentials
are never persisted by the SDK. In production, prefer minting server-to-server and keeping the
Api-Key off the device.

```kotlin
import com.rain.sdk.models.RainApiEnvironment
import java.math.BigInteger

val rain = RainSdk.builder()
    .rpcEndpoints(mapOf(84532 to "https://sepolia.base.org"))
    .register(/* provider */)
    .rainApiEnvironment(RainApiEnvironment.Dev) // default; Production / Custom(baseUrl) available
    .rainApiCredentials(apiKey = "…", userId = "…") // or configureRainApi(...) at runtime
    .build()

// Or set / replace credentials later (e.g. entered in your UI):
rain.configureRainApi(apiKey = "…", userId = "…")

// GET /v1/issuing/users/{userId}/contracts — token name/symbol/decimals are enriched from
// the SDK token store or an on-chain read (best-effort; null when unresolvable)
val contract = rain.fetchCollateralContract()   // first contract, or RainError.NoCollateralContracts
val contracts = rain.fetchCollateralContracts() // full list

// GET /v1/issuing/users/{userId}/signatures/withdrawals
// Throws RainError.SignatureNotReady(status, retryAfter) while Rain prepares the signature.
val adminSignature = rain.fetchAdminSignature(
    chainId = contract.chainId,
    tokenAddress = contract.tokens.first().address,
    amountBaseUnits = BigInteger("100000000"), // base units
    adminAddress = contract.adminAddresses.first(),
    recipientAddress = "0x..."
)
```

### 8. Withdraw Collateral

The SDK uses `RainWithdrawAddresses` and `RainAdminSignature` to group withdrawal parameters.
Both are typically produced by the Rain API methods above (`fetchCollateralContract` supplies
the addresses, `fetchAdminSignature` returns a ready `RainAdminSignature`):

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

// Sign and submit via the backing provider, returns the tx hash
val txHash = client.withdrawCollateral(
    chainId = 43114,
    addresses = addresses,
    amount = BigDecimal("100.0"),
    decimals = 6,
    adminSignature = adminSignature
)
println("Tx Hash: $txHash")

// Or build it without broadcasting, for custom submission
val prepared = client.prepareWithdrawal(
    chainId = 43114,
    addresses = addresses,
    amount = BigDecimal("100.0"),
    decimals = 6,
    adminSignature = adminSignature
)
println("Tx: ${prepared.evmParameters}")   // solanaTransfer on a Solana chain
```

### 9. Solana

Solana uses the same `RainClient` methods as EVM — the SDK routes on the chain ID. `RainChain`
exposes the sentinel IDs (`SOLANA_MAINNET` 900, `SOLANA_DEVNET` 901, `SOLANA_TESTNET` 902); these
are Rain's routing IDs, not Solana chain IDs. Register a Solana RPC URL against them like any other
chain.

```kotlin
val client = rain.provider(ProviderId.TURNKEY)   // or PRIVY; Portal has no Solana account
val chainId = RainChain.SOLANA_DEVNET

client.getWalletAddress(chainId)                 // the Solana account, not the EVM address
client.getBalance(chainId, Token.Native)         // SOL
client.getTokenBalances(chainId)                 // SPL holdings
client.sendNative(chainId, recipientBase58, BigDecimal("0.01"))
client.sendToken(chainId, mintAddress, recipientBase58, BigDecimal("1.5"))
```

`withdrawCollateral` works unchanged, with `proxyAddress` as the collateral account and
`tokenAddress` as the SPL mint. Under the hood the withdrawal is authorized by Rain's coordinator
signing a message off chain rather than by EVM calldata, so the SDK composes and simulates a
collateral-program transaction and the provider signs it; `prepareWithdrawal` returns those prepared
bytes along with their `recentBlockhash`. See [TURNKEY_SUPPORT.md](docs/TURNKEY_SUPPORT.md#solana-notes) for the details.

On `sendToken`, an SPL mint's decimals are read from the chain, so the `decimals` argument does not
scale the amount. `withdrawCollateral` and `prepareWithdrawal` are the opposite: there `decimals`
**does** scale the amount and is not checked against the mint, so pass the mint's real decimals.
Mints carry no on-chain symbol — `registerTokens(...)` names the ones you want displayed.

### 10. Estimate Gas

```kotlin
val fee = client.estimateGas(
    chainId = 43114,
    from = walletAddress,
    to = controllerAddress,
    data = transactionData
)
println("Estimated fee: $fee AVAX")
```

### 11. Transaction History

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

### 12. QR Code Generation

```kotlin
val bitmap = client.generateAddressQRCode(dimension = 256)
// Use the bitmap in an ImageView
imageView.setImageBitmap(bitmap)
```

## Documentation

For a complete reference of all public methods, parameters, types, and error codes, see the [Method Reference](docs/METHODS.md).

## License

Apache License 2.0. See the [LICENSE](LICENSE) file for details.
