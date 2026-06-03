# Rain SDK for Android — Method Reference

Reference for Rain SDK public methods. Access via `RainSdk.getInstance().client` for wallet operations and `RainSdk.getInstance().transactionBuilder` for wallet-agnostic utilities.

---

## RainSdk

Main entry point for Rain SDK. Provides access to both full wallet operations and transaction builder utilities.

```kotlin
val sdk = RainSdk.getInstance()
val client = sdk.client                  // RainClient — full wallet operations
val txBuilder = sdk.transactionBuilder   // RainTransactionBuilder — wallet-agnostic utilities
val portal = sdk.portal                  // Portal instance (after initialization)
```

### Properties

| Property | Type | Description |
|----------|------|-------------|
| `client` | `RainClient` | Access to all wallet operations. |
| `transactionBuilder` | `RainTransactionBuilder` | Transaction builder for wallet-agnostic mode. Throws `RainError.SdkNotInitialized` if not initialized. |
| `portal` | `Portal` | Convenience access to the Portal instance. Throws `RainError.SdkNotInitialized` if not initialized. |
| `turnkey` | `TurnkeyContext` | Convenience access to the Turnkey context. Throws `RainError.SdkNotInitialized` if not initialized via `initializeTurnkey`. |
| `isInitialized` | `Boolean` | Whether the SDK has been successfully initialized. |

---

## RainClient Methods

### initializePortal(portalSessionToken, rpcEndpoints, chainId)

Initializes the SDK with a Portal session token and chain-specific RPC endpoints. Use for full wallet flow (sign + send via Portal).

- **Returns:** (none)
- **Throws:** `RainError` if initialization fails (e.g. invalid token, invalid RPC URLs).

| Parameter | Type | Description |
|-----------|------|-------------|
| `portalSessionToken` | `String` | Valid Portal session token. Defaults to `""`. |
| `rpcEndpoints` | `Map<Int, String>` | Map of numeric chain IDs to RPC URLs. Example: `mapOf(43114 to "https://avalanche-rpc.com")`. |
| `chainId` | `Int?` | Optional default chain ID. If not provided, SDK selects from `rpcEndpoints`. |

---

### initializeTurnkey(turnkey, rpcEndpoints, chainId, walletAddress)

Initializes the SDK with an authenticated [Turnkey](https://turnkey.com) context and chain-specific RPC endpoints. Use for full wallet flow (sign + send via Turnkey). Turnkey authentication (passkeys / auth proxy / OAuth / OTP) happens **outside** Rain — initialize the Turnkey singleton in your `Application.onCreate()` and pass it here. See [TURNKEY_SUPPORT.md](TURNKEY_SUPPORT.md) for a full walkthrough.

- **Returns:** (none, suspend)
- **Throws:** `RainError` if initialization fails (e.g. invalid RPC URLs, no usable Ethereum wallet).
- **Suspend:** Yes (Rain probes the Turnkey wallet list during init).

| Parameter | Type | Description |
|-----------|------|-------------|
| `turnkey` | `TurnkeyContext` | Authenticated `TurnkeyContext` singleton from the Turnkey Kotlin SDK. |
| `rpcEndpoints` | `Map<Int, String>` | Map of numeric chain IDs to RPC URLs. |
| `chainId` | `Int?` | Optional default chain ID. If not provided, SDK selects from `rpcEndpoints`. |
| `walletAddress` | `String?` | Optional explicit EVM address override. When `null`, Rain uses the first Ethereum account from `TurnkeyContext.wallets`. |

`initializeTurnkey` and `initializePortal` are mutually exclusive — calling one swaps the active wallet provider and clears the other.

---

### withdrawCollateral(chainId, addresses, amount, decimals, adminSignature, nonce, autoSend)

Full withdrawal flow. When `autoSend = true`, builds the transaction, signs via Portal, submits, and returns the transaction hash. When `autoSend = false`, returns prepared transaction data for manual submission.

- **Returns:** `RainWithdrawResult` — containing either `transactionHash` (if `autoSend=true`) or `transactionData` (if `autoSend=false`).
- **Throws:** `RainError` if construction, signing, or submission fails.
- **Requires:** `initializePortal` first (Portal required for `autoSend = true`).
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID (e.g. `43114`). |
| `addresses` | `RainWithdrawAddresses` | All required addresses: proxy, controller, token, recipient. |
| `amount` | `Double` | Amount in human-readable token units (e.g. `100.0`). |
| `decimals` | `Int` | Token decimals (e.g. 6 for USDC, 18 for most tokens). |
| `adminSignature` | `RainAdminSignature` | Admin signature for authorization (salt, signature, expiresAt). |
| `nonce` | `BigInteger?` | Optional nonce; if `null`, SDK resolves from contract. |
| `autoSend` | `Boolean` | If `true`, sign and send via Portal. If `false`, return raw transaction data. Defaults to `false`. |

---

### getAddress()

Returns the current wallet address from the Portal wallet provider.

- **Returns:** `String` — hex-encoded wallet address (e.g. `"0x..."`).
- **Throws:** `RainError` if address cannot be retrieved.
- **Requires:** `initializePortal` first.
- **Suspend:** Yes

---

### estimateGas(chainId, from, to, data)

Estimates the gas fee required for a transaction.

- **Returns:** `Double` — estimated gas fee in the chain's native token (e.g. AVAX).
- **Throws:** `RainError` if estimation fails.
- **Requires:** `initializePortal` first.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID. |
| `from` | `String` | Sender wallet address. |
| `to` | `String` | Target contract address. |
| `data` | `String` | Hex-encoded transaction calldata (e.g. from `buildWithdrawTransactionData`). |

---

### sendNativeToken(chainId, toAddress, amount)

Sends native tokens (e.g. AVAX) from the current wallet.

- **Returns:** `RainTokenTransferResult` — containing the transaction hash.
- **Throws:** `RainError` if send fails.
- **Requires:** `initializePortal` first.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID. |
| `toAddress` | `String` | Recipient wallet address. |
| `amount` | `Double` | Amount in human-readable form (e.g. `0.1` for 0.1 AVAX). |

---

### sendToken(chainId, contractAddress, toAddress, amount, decimals)

Sends ERC-20 tokens from the current wallet.

- **Returns:** `RainTokenTransferResult` — containing the transaction hash.
- **Throws:** `RainError` if send fails.
- **Requires:** `initializePortal` first.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID. |
| `contractAddress` | `String` | ERC-20 token contract address. |
| `toAddress` | `String` | Recipient wallet address. |
| `amount` | `Double` | Amount in human-readable form (e.g. `100.0` for 100 USDC). |
| `decimals` | `Int` | Token decimals (e.g. 6 for USDC, 18 for WETH). |

---

### Balance value type

All balance methods return rich `Balance` values rather than lossy `Double`s.

| Field | Type | Description |
|-------|------|-------------|
| `token` | `Token` | `Token.Native` or `Token.Contract(address)`. |
| `chainId` | `Int` | EIP-155 chain ID the balance was read on. |
| `rawAmount` | `BigInteger` | Exact balance in the token's smallest unit (never lossy). |
| `decimals` | `Int` | Token decimal places (e.g. 6 for USDC, 18 for ETH). |
| `symbol` | `String?` | Token symbol, when known. |
| `name` | `String?` | Human-readable name, when known. |
| `decimalAmount` | `BigDecimal` | Derived: `rawAmount / 10^decimals`. |
| `formatted` | `String` | Derived display string (e.g. `"1.5"`). |

---

### getBalance(chainId, token)

Fetches a single balance (native or a contract token) for the current wallet.

- **Returns:** `Balance` — exact `rawAmount` plus resolved decimals / symbol / name.
- **Throws:** `RainError` if no wallet provider is set, or if the request fails.
- **Requires:** `initializePortal` / `initializeTurnkey` first.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID (e.g. `43114` for Avalanche Mainnet). |
| `token` | `Token` | `Token.Native`, or `Token.Contract(address)` (address comparison is case-insensitive). |

---

### getBalances(chainId)

Fetches all non-zero balances for the current wallet on the given network. The native
balance is always included; zero-balance contract tokens are omitted.

- **Returns:** `List<Balance>` — one per non-zero token plus the native balance.
- **Throws:** `RainError` if no wallet provider is set, or if the request fails.
- **Requires:** `initializePortal` / `initializeTurnkey` first.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID. |

---

### getAllBalances()

Fetches balances across every chain the SDK was initialized with, in parallel, flattened
into a single list. Each `Balance` carries its own `chainId`. Per-chain failures are
tolerated — a chain that errors out contributes no entries rather than failing the whole
call.

- **Returns:** `List<Balance>` — a flat list spanning all healthy configured chains.
- **Throws:** `RainError` if the SDK was not initialized or no wallet provider is set.
- **Suspend:** Yes

---

### registerTokens(tokens)

Registers additional tokens so their metadata (decimals / symbol) resolves without an
on-chain enrichment call. Retained across re-initialization; cleared by `reset()`.

- **Returns:** `Unit`
- **Suspend:** No

| Parameter | Type | Description |
|-----------|------|-------------|
| `tokens` | `List<TokenInfo>` | Tokens to add to the SDK's token store. |

---

### generateAddressQRCode(address, width, height)

Generates an Android `Bitmap` containing a QR code for a wallet address.

- **Returns:** `Bitmap` — QR code image.
- **Throws:** `RainError` if wallet is unavailable or QR generation fails.
- **Requires:** `initializePortal` first (unless `address` is provided).
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `address` | `String?` | Address to encode. If `null`, uses current wallet address. |
| `width` | `Int` | Output width in pixels. Defaults to `500`. |
| `height` | `Int` | Output height in pixels. Defaults to `500`. |

---

### getTransactions(chainId, limit, offset, order)

Fetches transaction history for the current wallet on the given network.

- **Returns:** `RainTransactionResult` — containing a list of `RainTransaction`.
- **Throws:** `RainError` if transaction history cannot be retrieved.
- **Requires:** `initializePortal` first.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID. |
| `limit` | `Int?` | Optional max number of transactions to return. |
| `offset` | `Int?` | Optional pagination offset. |
| `order` | `RainTransactionOrder?` | Optional sort order: `.ASC` or `.DESC`. |

---

## RainTransactionBuilder Methods

Wallet-agnostic utility methods. Access via `RainSdk.getInstance().transactionBuilder`. These methods do **not** require Portal — they can be used with any wallet or backend.

### getLatestNonce(rpcUrl, proxyAddress)

Gets the latest nonce for a given proxy address from the contract.

- **Returns:** `BigInteger` — the current nonce.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `rpcUrl` | `String` | RPC endpoint URL for the target network. |
| `proxyAddress` | `String` | The collateral proxy contract address. |

---

### buildEIP712Message(chainId, addresses, walletAddress, amount, decimals, nonce)

Builds EIP-712 typed data for obtaining the admin signature required for withdrawals.

- **Returns:** `Pair<String, ByteArray>` — serialized EIP-712 message and salt bytes.
- **Throws:** `RainError` if message construction fails or inputs are invalid.
- **Requires:** SDK initialized.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID. |
| `addresses` | `RainWithdrawAddresses` | Proxy, controller, token, recipient addresses. |
| `walletAddress` | `String` | User wallet address (used as `user` in EIP-712). |
| `amount` | `Double` | Amount in human-readable token units. |
| `decimals` | `Int` | Token decimals. |
| `nonce` | `BigInteger?` | Optional; if `null`, SDK fetches from contract. |

---

### buildWithdrawTransactionData(addresses, amount, decimals, saltBytes, signatureData, adminSignature)

Builds ABI-encoded withdraw calldata for the collateral proxy contract.

- **Returns:** `String` — hex-encoded calldata (e.g. `"0x..."`).
- **Throws:** `RainError` if ABI encoding or validation fails.
- **Requires:** SDK initialized (no Portal required).

| Parameter | Type | Description |
|-----------|------|-------------|
| `addresses` | `RainWithdrawAddresses` | Proxy, controller, token, recipient addresses. |
| `amount` | `Double` | Amount in human-readable token units. |
| `decimals` | `Int` | Token decimals. |
| `saltBytes` | `ByteArray` | Salt data (32 bytes) for the withdrawal authorization. |
| `signatureData` | `String` | User/wallet signature from Rain API (hex string). |
| `adminSignature` | `RainAdminSignature` | Admin signature (salt, signature, expiresAt). |

---

## Types

| Type | Description |
|------|-------------|
| **`RainWithdrawAddresses`** | `proxyAddress`, `controllerAddress`, `tokenAddress`, `recipientAddress`. Has `validated()` method for address checksumming. |
| **`RainAdminSignature`** | `salt` (String), `signature` (hex String), `expiresAt` (String, ISO-8601 or unix timestamp). |
| **`RainWithdrawResult`** | `transactionHash` (String?, present if auto-sent), `transactionData` (String?, present if not auto-sent). Has `isAutoSent` and `isTransactionData` helper properties. |
| **`RainTokenTransferResult`** | `transactionHash` (String). Returned by `sendNativeToken` and `sendToken`. |
| **`RainTransaction`** | Transaction record: `hash`, `from`, `to`, `value`, `blockNumber`, `blockTimestamp`, `gas`, `gasPrice`, `chainId`, `symbol`, `tokenAddress`, `metadata`. |
| **`RainTransactionResult`** | `transactions: List<RainTransaction>`. Returned by `getTransactions`. |
| **`RainTransactionOrder`** | Enum: `.ASC`, `.DESC`. Used in `getTransactions(..., order:)`. |
| **`RainChain`** | Constants: `AVALANCHE_MAINNET` (43114), `AVALANCHE_TESTNET` (43113). |

---

## Errors

All methods can throw `RainError` (sealed class). Each error includes an `errorCode` property for programmatic handling.

Format: `"RainSDK Error [CODE]: message"`

| Code | Class | Meaning |
|------|-------|---------|
| `RAIN_101` | `RainError.SdkNotInitialized` | Method called before `initializePortal`. |
| `RAIN_102` | `RainError.InvalidConfig` | Invalid RPC URL, chain ID, or address format. |
| `RAIN_201` | `RainError.TokenExpired` | Portal session token expired or invalid. |
| `RAIN_202` | `RainError.Unauthorized` | Invalid or missing token / permissions. |
| `RAIN_301` | `RainError.NetworkError` | Network/connectivity failure. |
| `RAIN_401` | `RainError.UserRejected` | User cancelled the signing request in the wallet. |
| `RAIN_402` | `RainError.InsufficientFunds` | Balance too low for the requested amount or gas. |
| `RAIN_403` | `RainError.TransactionSimulationFailed` | Preflight `eth_call` simulation failed (e.g. contract revert, insufficient funds). |
| `RAIN_404` | `RainError.WalletUnavailable` | The active wallet provider returned no usable wallet address (e.g. Turnkey context has no Ethereum account). |
| `RAIN_405` | `RainError.WithdrawalRevertedByNetwork` | Withdrawal reverted on-chain (e.g. duplicate withdrawal, already-used signature). |
| `RAIN_501` | `RainError.ProviderError` | Portal, Turnkey, or other provider error. |
| `RAIN_502` | `RainError.InternalError` | EIP-712 encoding, ABI encoding, or internal processing error. |

### Error handling example

```kotlin
try {
    val result = RainSdk.getInstance().client.withdrawCollateral(...)
} catch (e: RainError) {
    when (e) {
        is RainError.SdkNotInitialized -> { /* SDK not initialized */ }
        is RainError.InvalidConfig -> { /* Bad config: ${e.message} */ }
        is RainError.InsufficientFunds -> { /* Not enough balance */ }
        is RainError.NetworkError -> { /* Network issue: ${e.cause} */ }
        else -> { /* Other error: ${e.errorCode.code} — ${e.message} */ }
    }
}
```
