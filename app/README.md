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

### 5. Execute Withdrawal

1. Click **5. Test Withdraw Collateral**.
2. The app will automatically:
   - Fetch the collateral contract info using your `accessToken`.
   - Request an admin signature from the backend.
   - Orchestrate the withdrawal via `rainClient.withdrawCollateral`.
3. Monitor the **Status** for the transaction hash.

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
    amount = amount,
    decimals = decimals,
    adminSignature = RainAdminSignature(
        salt = signature.salt,
        signature = signature.data,
        expiresAt = expiresAt
    ),
    nonce = null // SDK auto-resolves nonce
)
```
