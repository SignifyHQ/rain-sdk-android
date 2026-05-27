# Rain SDK for Android

Android SDK that integrates [Portal](https://portalhq.io) MPC wallet with Rain collateral withdrawal: build EIP-712 messages, compose withdrawal transactions, sign and submit via Portal, and estimate fees.

- **Portal wallet integration** — Initialize with a Portal session token and RPC endpoints; use the connected MPC wallet for signing and sending transactions.
- **Wallet-agnostic mode** — Initialize with RPC endpoints only (no Portal) to use transaction-building APIs (EIP-712 message, withdraw calldata) with your own wallet or backend.
- **EIP-712 message building** — Build typed data for admin signature required by the collateral contract.
- **Withdrawal transaction building** — Build ABI-encoded withdraw calldata for submission.
- **Full withdrawal flow** — Builds the transaction, signs via Portal, and submits; returns the transaction hash.
- **Fee estimation** — Returns the estimated gas cost in the chain's native token (e.g. AVAX).
- **Wallet information** — Get current wallet address and generate a QR code `Bitmap` for it.
- **Balances** — Get native and ERC-20 token balances for the current wallet.
- **Transaction history** — Get transactions for the current wallet with optional pagination and sort order.
- **Send tokens** — Send native or ERC-20 tokens from the current wallet.

## Installation

Add the dependency to your module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.rain.sdk:rain-sdk:1.0.0")
}
```

## Requirements

- Android SDK 26+
- Kotlin 1.8+

## Quick Start

### 1. Initialize with Portal (full wallet flow)

Use this when you want the SDK to use Portal for signing and sending transactions.

```kotlin
import com.rain.sdk.RainSdk

val client = RainSdk.getInstance().client

client.initializePortal(
    portalSessionToken = "<your-portal-session-token>",
    rpcEndpoints = mapOf(
        43114 to "https://avalanche-c-chain-rpc.publicnode.com",
        43113 to "https://avalanche-fuji-c-chain-rpc.publicnode.com"
    )
)

// Access the Portal instance when needed (e.g. for UI)
val portal = RainSdk.getInstance().portal
```

### 2. Initialize without Portal (wallet-agnostic)

Use this when you only need transaction building (EIP-712 message, calldata) and will sign/submit elsewhere.

```kotlin
import com.rain.sdk.RainSdk

val txBuilder = RainSdk.getInstance().transactionBuilder

// buildEIP712Message, buildWithdrawTransactionData
// are available; withdrawCollateral with autoSend requires Portal.
```

### 3. Get Wallet Address

```kotlin
val address = RainSdk.getInstance().client.getAddress()
```

### 4. Check Balances

```kotlin
val client = RainSdk.getInstance().client

// Native token balance (e.g. AVAX)
val nativeBalance = client.getNativeBalance(chainId = 43114)

// Specific ERC-20 token balance (e.g. USDC)
val usdcBalance = client.getERC20Balance(
    chainId = 43114,
    tokenAddress = "0x...",
    decimals = 6
)

// All ERC-20 balances
val allTokens = client.getERC20Balances(chainId = 43114)

// Native + ERC-20 balances in one map
// Native balance is stored under key ""
val allBalances = client.getBalances(chainId = 43114)
```

### 5. Send Tokens

```kotlin
val client = RainSdk.getInstance().client

// Send native token (AVAX)
val result = client.sendNativeToken(
    chainId = 43114,
    toAddress = "0x...",
    amount = 0.1
)
println("Tx Hash: ${result.transactionHash}")

// Send ERC-20 token (e.g. USDC)
val result = client.sendToken(
    chainId = 43114,
    contractAddress = "0x...",
    toAddress = "0x...",
    amount = 100.0,
    decimals = 6
)
```

### 6. Withdraw Collateral

The SDK uses `RainWithdrawAddresses` and `RainAdminSignature` to group withdrawal parameters:

```kotlin
import com.rain.sdk.models.RainWithdrawAddresses
import com.rain.sdk.models.RainAdminSignature

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

// Auto-send: sign and submit via Portal, returns tx hash
val result = RainSdk.getInstance().client.withdrawCollateral(
    chainId = 43114,
    addresses = addresses,
    amount = 100.0,
    decimals = 6,
    adminSignature = adminSignature,
    autoSend = true
)
println("Tx Hash: ${result.transactionHash}")

// Manual: get raw transaction data for custom submission
val result = RainSdk.getInstance().client.withdrawCollateral(
    chainId = 43114,
    addresses = addresses,
    amount = 100.0,
    decimals = 6,
    adminSignature = adminSignature,
    autoSend = false
)
println("Tx Data: ${result.transactionData}")
```

### 7. Estimate Gas

```kotlin
val fee = RainSdk.getInstance().client.estimateGas(
    chainId = 43114,
    from = walletAddress,
    to = controllerAddress,
    data = transactionData
)
println("Estimated fee: $fee AVAX")
```

### 8. Transaction History

```kotlin
import com.rain.sdk.models.RainTransactionOrder

val result = RainSdk.getInstance().client.getTransactions(
    chainId = 43114,
    limit = 20,
    offset = 0,
    order = RainTransactionOrder.DESC
)

result.transactions.forEach { tx ->
    println("${tx.hash} — ${tx.from} → ${tx.to}: ${tx.value}")
}
```

### 9. QR Code Generation

```kotlin
val bitmap = RainSdk.getInstance().client.generateAddressQRCode(
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
