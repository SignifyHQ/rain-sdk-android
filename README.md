# Rain Mobile SDK for Android

Rain SDK provides a unified solution for integrating Rain's crypto and card services into your Android application. It simplifies blockchain operations and offers a built-in MPC wallet solution powered by Portal.

## Features

- **Unified SDK:** Single dependency for all features.
- **Dual Mode Operation:**
  - **Full Mode:** Includes a complete MPC wallet (powered by Portal).
  - **Utility Mode:** Standalone Web3 utilities for "Bring Your Own Wallet" (BYOW) integration.
- **Transaction Builder:** Easily construct complex EIP-712 payloads and manage gas estimation.

## Installation

Add the dependency to your module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.rain.sdk:rain-sdk:1.0.0")
}
```

## Usage

### 1. Initialize Portal (Full Wallet Mode)

If you want to use the built-in MPC wallet:

```kotlin
RainSdk.initializePortal(
    portalSessionToken = "YOUR_SESSION_TOKEN",
    rpcEndpoints = mapOf(43114 to "https://avalanche-c-chain-rpc.publicnode.com")
)

// Access Portal instance
val portal = RainSdk.portal
```

### 2. Web3 Utilities (Standalone Mode)

If you have your own wallet (e.g., Metamask) and just need to prepare transaction data:

```kotlin
val payload = RainSdk.Utils.buildWithdrawPayload(
    tokenAddress = "0x...",
    amount = 100.0,
    // ...
)

// Sign with your wallet
myWallet.sign(payload)
```

### 3. Withdraw Collateral

The SDK provides a unified method to orchestrate the complete withdrawal flow:

#### Full Mode (with Portal Wallet)

```kotlin
// Initialize SDK first
RainSdk.getInstance().client.initializePortal(
    portalSessionToken = "YOUR_SESSION_TOKEN",
    rpcEndpoints = mapOf(43114 to "https://avalanche-c-chain-rpc.publicnode.com")
)

// Execute withdrawal
val txHash = RainSdk.getInstance().client.withdrawCollateral(
    chainId = 43114,
    collateralProxyAddress = "0x...",
    tokenAddress = "0xB97EF9Ef8734C71904D8002F8b6Bc66Dd9c48a6E", // USDC
    amount = 100.0,
    decimals = 6,
    recipientAddress = "0x...",
    expiresAt = "1234567890",
    adminSalt = "0x...",      // Provided by your backend
    adminSignature = "0x...", // Provided by your backend
    nonce = null              // SDK will auto-resolve
)

println("Transaction submitted: $txHash")
```

#### Utility Mode (Build Your Own Wallet)

Use transaction builder utilities for manual control:

```kotlin
// 1. Build EIP-712 message
val (typedDataJson, salt) = RainSdk.getInstance().transactionBuilder.buildEIP712Message(
    chainId = 43114,
    collateralProxyAddress = "0x...",
    walletAddress = myWallet.address,
    tokenAddress = "0x...",
    amount = 100.0,
    decimals = 6,
    recipientAddress = "0x...",
    nonce = null
)

// 2. Sign with your wallet
val userSignature = myWallet.signTypedData(typedDataJson)

// 3. Build transaction data
val txData = RainSdk.getInstance().transactionBuilder.buildWithdrawTransactionData(
    proxyAddress = "0x...",
    tokenAddress = "0x...",
    amount = 100.0,
    decimals = 6,
    recipientAddress = "0x...",
    expiresAt = "1234567890",
    signatureData = userSignature,
    adminSalt = "0x...",
    adminSignature = "0x..."
)

// 4. Send transaction with your wallet
val txHash = myWallet.sendTransaction(to = proxyAddress, data = txData)
```

## Requirements

- Android SDK 26+
- Kotlin 1.8+
