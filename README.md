# Rain Mobile SDK for Android

The Rain Mobile SDK for Android simplifies blockchain integration by providing a unified interface for wallet management, transaction signing, and collateral withdrawals. The SDK wraps web3 methods and integrates with the Portal wallet, while also supporting wallet-agnostic operations.

## Overview

### Objective

Build a V1 of the Rain Mobile SDK using the Portal wallet to manage collateral. The SDK exposes all Portal methods, allowing users to access the full range of its features. The Portal dependency is optional, enabling clients to use the SDK's methods to simplify obtaining the admin signature and building transactions with any wallet of their choice.

### Background

Many clients want to integrate Rain's solutions into their products but face challenges with blockchain-related processes such as wallet management, transaction signing, and collateral withdrawals. This SDK addresses these challenges by providing simplified workflows for fast, seamless integration.

## Features

| Feature | Description | Priority |
|---------|-------------|----------|
| **Portal wallet SDK initialization** | Integrate the SDK and initialize it with a Portal token and network configuration. Access all Portal wallet features and set up custom flows. | Must-Have |
| **Wallet-agnostic SDK initialization** | Initialize the SDK without Portal credentials. Use convenient helper methods to obtain admin signatures and build transaction data required for collateral withdrawals and estimated transaction fees. | Must-Have |
| **Collateral withdrawal (Portal)** | Withdraw tokens from collateral balance on any supported chain. The SDK handles signature creation, transaction signing, and sending transactions automatically. Includes methods to get estimated withdrawal transaction fees. | Must-Have |

## Installation

<TBD>

### Requirements

- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 34
- **Kotlin**: 1.8+
- **Java**: 8+

## Quick Start

### Portal Wallet Integration

```kotlin
import com.rain.sdk.RainSdk
import com.rain.sdk.RainError

try {
    val portalToken = "your-portal-session-token"
    val rpcEndpoints = mapOf(
        43114 to "https://avalanche-c-chain-rpc.publicnode.com"
    )
    
    RainSdk.initializePortal(portalToken, rpcEndpoints)
    
    // Access Portal instance
    val portal = RainSdk.portal
    // Use portal methods as needed
    
} catch (e: RainError) {
    // Handle error
}
```

### Wallet-Agnostic Integration

```kotlin
import com.rain.sdk.RainSdk

// No initialization required
val utils = RainSdk.Utils

// Build withdrawal payload
val payload = utils.buildWithdrawPayload(
    tokenAddress = "0x...",
    decimals = 18,
    amount = 100.0,
    adminSignature = "0x..."
)
```

## Usage

### Portal Wallet Integration

Initialize the SDK with your Portal session token and RPC endpoints to access all Portal wallet features.

```kotlin
RainSdk.initializePortal(portalToken, rpcEndpoints)
val portal = RainSdk.portal
```

<TBD>

### Wallet-Agnostic Integration

Use SDK utilities without Portal initialization to build transaction payloads for your preferred wallet.

```kotlin
val payload = RainSdk.Utils.buildWithdrawPayload(
    tokenAddress = tokenAddress,
    decimals = 18,
    amount = withdrawalAmount,
    adminSignature = adminSignature
)
```

<TBD>

## API Reference

### RainSdk

Main SDK object providing access to Portal instance and utilities.

#### Properties

- `portal: Portal` - Portal instance (throws `RainError.PortalNotInitialized` if not initialized)
- `Utils: RainUtils` - Wallet-agnostic utilities

#### Methods

##### `initializePortal(portalSessionToken: String, rpcEndpoints: Map<Int, String>)`

Initializes the SDK with Portal wallet.

**Parameters:**
- `portalSessionToken`: Valid Portal session token
- `rpcEndpoints`: Map of chain IDs to RPC URLs

**Throws:**
- `RainError.InvalidPortalToken`
- `RainError.InvalidRPCUrl`
- `RainError.PortalError`

### RainUtils

Utility functions for wallet-agnostic operations.

#### Methods

##### `buildWithdrawPayload(tokenAddress: String, decimals: Int, amount: Double, adminSignature: String): String`

Builds EIP-712 payload for withdrawal transactions.

**Parameters:**
- `tokenAddress`: Token contract address
- `decimals`: Token decimals
- `amount`: Withdrawal amount
- `adminSignature`: Admin signature from Rain API

**Returns:** JSON string ready to be signed

## Error Handling

The SDK uses `RainError` sealed class for type-safe error handling.

```kotlin
sealed class RainError : Exception {
    object PortalNotInitialized
    data class InvalidPortalToken(val token: String)
    data class InvalidRPCUrl(val chainId: Int, val url: String)
    data class PortalError(val underlying: Throwable)
}
```

## Dependencies

- **Portal SDK** (v6.0.0) - [Portal Documentation](https://docs.portalhq.io/)
- **Web3j** (v4.12.3-android) - Blockchain interactions and smart contract handling
- **Kotlinx Serialization** (v1.9.0) - JSON serialization
- **Kotlinx Coroutines** (v1.10.2) - Asynchronous programming
- **Timber** (v5.0.1) - Logging

## License

<TBD>

## Support

<TBD>
