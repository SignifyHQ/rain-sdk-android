# Rain SDK Sample App

This application demonstrates how to integrate and use the Rain SDK in a real-world Android environment. It showcases the full lifecycle of an MPC wallet initialization and a collateral withdrawal flow.

## Project Structure

The sample app follows a modern MVVM architecture using Jetpack Compose (if applicable) or standardized View Binding.

- `MainActivity.kt`: The UI entry point. It handles user interactions and displays status updates.
- `SampleViewModel.kt`: Orchestrates the business logic, interacts with the `RainClient`, and manages UI state.
- `NetworkClient.kt`: A mock implementation representing your backend API, providing necessary authorization like `adminSignature`.

## Quick Start (Manual Testing Flow)

To test the full withdrawal flow, follow these exact steps:

### 1. Obtain Tokens

Since the sample app interacts with Rain's staging/production environment, you need valid tokens:

- **Rain Access Token**: Copy this from your login session in the main Rain application.
- **Portal Session Token**: This is the MPC-specific token, usually found alongside your session data in the main app.

### 2. Configure the App

1. Launch the sample app on an emulator or physical device.
2. In the **2. Configuration** section:
   - Paste the **Portal Session Token**.
   - Paste the **Rain Access Token**.

### 3. Initialize and Recover

1. Click **3. Initialize SDK**. If successful, the status will show "SDK Initialized Successfully!".
2. If the wallet was previously created, a **Wallet Recovery Required** section will appear.
   - Enter your **PIN** (the one you set during wallet creation in the main app).
   - Click **Recover Wallet**.

### 4. Verify Wallet

1. Click **4. Get Wallet Address**. This confirms the MPC share is correctly loaded and the SDK can interact with the wallet.
2. Observe the **Status** text to see your wallet address.

### 5. Utilities & Transactions

1. **Generate QR Code**: Click **Generate QR Code** to see a visual QR of your address.
2. **Estimate Gas**: Click **Estimate Gas** to calculate the fee for a sample withdrawal.
3. **Get Balances**: Click **Get Balances** to fetch current native and ERC-20 balances.
4. **Get Transactions**: Click **6. Get Transactions** to see recent history.

### 6. Execute Tokens & Withdrawal

1. Click **5. Test Withdraw Collateral**.
2. **Send Native Token**: Enter recipient and amount, then click **Send Native**.
3. **Send ERC-20 Token**: Enter contract, recipient, and amount, then click **Send Token**.
4. Monitor the **Status** for transaction hashes (`0x...`).

### 6. Fetch Transaction History

1. Click **6. Get Transactions**.
2. The app will fetch the most recent transactions for the configured chain and display them in the status log.

## Key Code Snippets

### Initializing the SDK

Located in `SampleViewModel.kt`. Note that we specify the chain and RPC:

```kotlin
rainClient.initializePortal(
    portalSessionToken = sessionToken,
    rpcEndpoints = mapOf(RainChain.AVALANCHE_TESTNET to "https://api.avax-test.network/ext/bc/C/rpc"),
    chainId = RainChain.AVALANCHE_TESTNET
)
```

### The Withdrawal Logic

The sample app simplifies the complex flow into a one-liner after fetching necessary data:

```kotlin
val txHash = rainClient.withdrawCollateral(
    chainId = chainId,
    addresses = RainWithdrawAddresses(
        proxyAddress = contract.address,
        controllerAddress = contract.controllerAddress,
        tokenAddress = tokenAddress,
        recipientAddress = recipientAddress
    ),
    amount = BigDecimal("100.0"),
    decimals = decimals,
    adminSignature = RainAdminSignature(
        salt = signature.salt,
        signature = signature.data,
        expiresAt = expiresAt
    ),
    nonce = null // SDK auto-resolves nonce
)
```

### Fetching Transactions

```kotlin
val result = rainClient.getTransactions(
    chainId = chainId,
    limit = 10,
    offset = 0,
    order = RainTransactionOrder.DESC
)
```

### Token Transfers & Balances

```kotlin
import java.math.BigDecimal

// Sending Native Token
val res = rainClient.sendNativeToken(chainId, recipient, BigDecimal("0.1"))

// Sending ERC-20 Token
val res = rainClient.sendToken(chainId, contract, recipient, BigDecimal("10.0"), 6)

// Fetching Balances
val native = rainClient.getNativeBalanceDecimal(chainId)
val token = rainClient.getERC20BalanceDecimal(chainId, contract, 6)
```

### QR & Gas Utilities

```kotlin
val bitmap = rainClient.generateAddressQRCode()
val gasEth = rainClient.estimateGasDecimal(chainId, from, to, txData)
```
