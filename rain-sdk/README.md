# Rain SDK Core

The Rain SDK is a comprehensive Android library designed to simplify blockchain interactions and provide built-in MPC wallet capabilities for seamless crypto and card service integration.

## Table of Contents

- [Architecture](#architecture)
- [Key Features](#key-features)
- [Core Components](#core-components)
- [Data Models](#data-models)
- [Error Handling](#error-handling)
- [Advanced Configuration](#advanced-configuration)

---

## Architecture

Rain SDK sits between your application logic and the blockchain. It encapsulates:

1. **Portal SDK**: For MPC wallet management (Key generation, signing).
2. **Web3j**: For interacting with Ethereum-compatible blockchains.
3. **Internal Orchestration**: Managing the complex flow of multi-step transactions.

```mermaid
graph TD
    App[Android App] --> RainClient[RainClient]
    RainClient --> PortalMgr[PortalManager]
    RainClient --> TxCoord[TransactionCoordinator]
    TxCoord --> TxBuilder[RainTransactionBuilder]
    TxCoord --> PortalMgr
    PortalMgr --> PortalSDK[Portal HQ SDK]
    TxBuilder --> Web3j[Web3j RPC]
```

## Key Features

- **Dual Mode Operation**:
  - **Full Mode**: Uses the integrated Portal MPC wallet.
  - **Utility Mode**: Use the internal builders to generate data for your own wallet implementation.
- **Unified Withdrawal Flow**: A single method call handles validation, EIP-712 signing, and transaction submission.
- **Thread Safety**: Built using Kotlin Coroutines for efficient, non-blocking operations.

## Core Components

### `RainClient`

The primary entry point. Accessed via `RainSdk.getInstance().client`.

- `initializePortal(...)`: Sets up the MPC wallet.
- `withdrawCollateral(...)`: Orchestrates the full withdrawal flow.

### `RainTransactionBuilder`

Low-level utilities for manual transaction construction.

- `buildEIP712Message(...)`: Generates typed data for user signing.
- `buildWithdrawTransactionData(...)`: Encodes the final contract call.

## Data Models

### `RainWithdrawAddresses`

Groups all contract and token addresses required for a withdrawal.

```kotlin
data class RainWithdrawAddresses(
    val proxyAddress: String,      // Collateral Proxy
    val controllerAddress: String, // Collateral Controller
    val tokenAddress: String,      // Token to withdraw
    val recipientAddress: String   // Target wallet
)
```

### `RainAdminSignature`

Contains the authorization data provided by your backend.

```kotlin
data class RainAdminSignature(
    val salt: String,
    val signature: String,
    val expiresAt: String // ISO-8601 format
)
```

## Error Handling

All SDK operations throw `RainError`. Use a `try-catch` block to handle them:

- `RainError.NetworkError`: Problem communicating with RPC nodes.
- `RainError.ProviderError`: Error from the Portal MPC provider.
- `RainError.InvalidConfig`: Missing or incorrect parameters/setup.
- `RainError.InternalError`: Unexpected SDK behavior.

## Advanced Configuration

You can configure RPC endpoints and security settings via `RainConfig`:

```kotlin
RainConfig.getInstance().setRpcUrl(chainId, "https://...")
```
