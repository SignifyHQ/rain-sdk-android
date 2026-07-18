# Rain SDK for Android — Method Reference

Reference for the Rain SDK public API. The SDK is **modular**: `rain-core-android` carries the
vendor-free port, registry, and domain logic; each wallet provider ships as its own adapter
(`PortalProvider`, `TurnkeyProvider`, …). You assemble a `RainSdk` with a builder, register the
provider adapters your app ships, then resolve a `RainClient` per provider.

```kotlin
import com.rain.sdk.RainSdk
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.portal.PortalConfig
import com.rain.sdk.portal.PortalProvider

val rain = RainSdk.builder()
    .rpcEndpoints(mapOf(43114 to "https://avalanche-c-chain-rpc.publicnode.com"))
    .register(PortalProvider(PortalConfig(sessionToken)))
    .build()

val client = rain.provider(ProviderId.PORTAL)   // suspend — RainClient for wallet operations
val txBuilder = rain.transactionBuilder          // RainTransactionBuilder — no provider required
```

There is no singleton and no `initialize*` methods: a `RainClient` is bound to one provider for
its lifetime. See [TURNKEY_SUPPORT.md](TURNKEY_SUPPORT.md) for the Turnkey adapter walkthrough.

---

## RainSdk

Entry point. Built via `RainSdk.builder()`; the host registers exactly the provider adapters it
ships and the chains it talks to. Nothing here references a concrete vendor type — a provider whose
module isn't on the classpath simply can't be registered.

### Properties

| Property | Type | Description |
|----------|------|-------------|
| `providerIds` | `Set<ProviderId>` | Ids of every provider the host registered. |
| `providers` | `Collection<RainProvider>` | The registered provider descriptors, for capability resolution. |
| `transactionBuilder` | `RainTransactionBuilder` | Wallet-agnostic transaction-building utilities. Needs no resolved provider — available straight off the SDK once `build()` succeeds. |

### Methods

#### builder(): Builder

Starts a new `Builder`.

#### provider(id): RainClient

Resolves the `RainClient` backed by the provider registered under `id`, materializing the vendor
wallet on first access and caching it thereafter.

- **Returns:** `RainClient` bound to that provider.
- **Throws:** `RainError.InvalidConfig` if no provider was registered for `id`.
- **Suspend:** Yes (materializes the vendor wallet; e.g. Turnkey probes its wallet list).

| Parameter | Type | Description |
|-----------|------|-------------|
| `id` | `ProviderId` | The id the provider was registered under (e.g. `ProviderId.PORTAL`). |

#### first(predicate): RainClient

Resolves the first registered provider matching `predicate` (e.g. by capability) and returns its
`RainClient`.

```kotlin
val exporter = rain.first { Capability.EXPORT in it.capabilities }
```

- **Returns:** `RainClient` for the first matching provider.
- **Throws:** `RainError.InvalidConfig` if no registered provider matches.
- **Suspend:** Yes.

| Parameter | Type | Description |
|-----------|------|-------------|
| `predicate` | `(RainProvider) -> Boolean` | Match tested against each registered provider descriptor. |

#### reset()

Clears resolved clients and Rain's stored chain configuration. Idempotent. After this the SDK must
be rebuilt via `builder()` before further use.

- **Suspend:** No

---

## RainSdk.Builder

Assembles a `RainSdk`. Module dependencies decide which providers can be registered — the builder
never names a vendor SDK itself.

| Method | Description |
|--------|-------------|
| `rpcEndpoints(endpoints: Map<Int, String>)` | Sets the `chainId → RPC URL` map every provider shares. **Required.** |
| `register(provider: RainProvider)` | Registers a provider adapter (e.g. `PortalProvider`, `TurnkeyProvider`). Re-registering the same id replaces the prior one. |
| `registerTokens(tokens: List<TokenInfo>)` | Seeds the shared token store with extra token metadata. |
| `build(): RainSdk` | Validates endpoints (fail-fast on a bad URL / chain id) and returns the SDK. Throws `RainError.InvalidConfig` if no RPC endpoints or no providers were registered. |

### Provider adapters

Each adapter is a `RainProvider` descriptor that owns its vendor SDK as a private dependency.

| Adapter | Module | Config | Notes |
|---------|--------|--------|-------|
| `PortalProvider(PortalConfig(sessionToken, chainId?))` | `rain-portal-android` | `sessionToken: String`, `chainId: Int?` | Portal MPC signer (EVM). Advertises `EXPORT`, `RECOVERY`. |
| `TurnkeyProvider(TurnkeyConfig(turnkey, walletAddress?))` | `rain-core-android` | `turnkey: TurnkeyContext`, `walletAddress: String?` | Turnkey P256 signer (EVM + Solana). Advertises `MULTI_CHAIN`, `BIOMETRIC_GATE`. See [TURNKEY_SUPPORT.md](TURNKEY_SUPPORT.md). |

**Bring your own provider:** implement the `WalletProvider` port and a `RainProvider` descriptor
(with your own `ProviderId`), then `register(...)` it. Core needs no change — the `transactionBuilder`
utilities are available regardless of which provider you register.

---

## RainClient

Operations Rain exposes against a single, already-resolved wallet provider. Obtained from
`rain.provider(id)` / `rain.first { … }`; bound to one provider for its lifetime, so it carries no
`initialize*` methods and never references a concrete vendor type.

Money APIs are `BigDecimal`-first.

### Properties

| Property | Type | Description |
|----------|------|-------------|
| `providerId` | `ProviderId` | Identifier of the provider backing this client (e.g. `ProviderId.PORTAL`). |
| `capabilities` | `Set<Capability>` | Optional behaviours the backing provider supports (see [Capabilities](#capabilities)). |
| `isInitialized` | `Boolean` | Whether the SDK's chain configuration is set up. |

---

### withdrawCollateral(chainId, addresses, amount, decimals, adminSignature, nonce, autoSend)

Full withdrawal flow. When `autoSend = true`, builds the transaction, signs via the backing
provider, submits, and returns the transaction hash. When `autoSend = false`, returns prepared
transaction data for manual submission.

- **Returns:** `RainWithdrawResult` — containing either `transactionHash` (if `autoSend=true`) or `transactionData` (if `autoSend=false`).
- **Throws:** `RainError` if construction, signing, or submission fails.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID (e.g. `43114`). |
| `addresses` | `RainWithdrawAddresses` | All required addresses: proxy, controller, token, recipient. |
| `amount` | `BigDecimal` | Amount in human-readable token units (e.g. `BigDecimal("100.0")`). |
| `decimals` | `Int` | Token decimals (e.g. 6 for USDC, 18 for most tokens). |
| `adminSignature` | `RainAdminSignature` | Admin signature for authorization (salt, signature, expiresAt). |
| `nonce` | `BigInteger?` | Optional nonce; if `null`, SDK resolves from contract. |
| `autoSend` | `Boolean` | If `true`, sign and send via the backing provider. If `false`, return raw transaction data. Defaults to `false`. |

---

### getWalletAddress()

Returns the current wallet address from the backing provider.

- **Returns:** `String` — hex-encoded wallet address (e.g. `"0x..."`).
- **Throws:** `RainError` if address cannot be retrieved.
- **Suspend:** Yes

---

### getWalletAddress(chainId)

Returns the wallet address for a specific chain. For EVM chains this is the same hex address as
`getWalletAddress()`. A provider that also holds non-EVM accounts (advertising
`Capability.MULTI_CHAIN`) returns the address matching `chainId`'s family — e.g. a base58 Solana
address for a Solana chain id (`RainChain.SOLANA_DEVNET`). EVM-only providers ignore the family
distinction and return the hex address.

- **Parameters:** `chainId: Int`
- **Returns:** `String` — the wallet address for that chain's family.
- **Throws:** `RainError` if the address cannot be retrieved.
- **Suspend:** Yes

---

### estimateGas(chainId, from, to, data)

Estimates the gas fee required for a transaction.

- **Returns:** `BigDecimal` — estimated gas fee in the chain's native token (e.g. AVAX).
- **Throws:** `RainError` if estimation fails.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID. |
| `from` | `String` | Sender wallet address. |
| `to` | `String` | Target contract address. |
| `data` | `String` | Hex-encoded transaction calldata (e.g. from `buildWithdrawTransactionData`). |

---

### estimateWithdrawalFee(chainId, addresses, amount, decimals, adminSignature, nonce?)

Estimates the total fee required to execute a collateral withdrawal transaction.

Internally builds + signs the EIP-712 payload, then runs `eth_estimateGas` against the withdrawal
controller — does not broadcast.

> **Signing side effect.** The estimate signs for real (its `eth_estimateGas` calldata needs a valid signature), so estimate-then-withdraw signs twice. iOS differs: it takes a caller-supplied signature (`salt` / `signature` / `expiresAt`) and doesn't sign.

- **Returns:** `Double` — estimated withdrawal fee in the chain's native token.
- **Throws:** `RainError` if estimation fails.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID. |
| `addresses` | `RainWithdrawAddresses` | All addresses required for the withdrawal (controller, proxy, token, recipient). |
| `amount` | `Double` | Human-readable amount to withdraw. |
| `decimals` | `Int` | Token decimals (e.g. 6 for USDC, 18 for most tokens). |
| `adminSignature` | `RainAdminSignature` | Admin authorization signature (same payload used by `withdrawCollateral`). |
| `nonce` | `BigInteger?` | Optional nonce; if `null`, the SDK resolves it from the contract. |

---

### composeTransactionParameters(walletAddress, contractAddress, transactionData)

Composes a wallet-agnostic transaction parameter bag for a contract call. Pure helper —
returns a Rain-owned `RainTransactionParameters` struct with `value` pre-set to `"0x0"`.
Hosts can hand the result to any provider for signing / broadcast. Mirrors the iOS
`composeTransactionParameters` API.

- **Returns:** `RainTransactionParameters` — `from`, `to`, `value` (`"0x0"`), `data`.
- **Suspend:** No

| Parameter | Type | Description |
|-----------|------|-------------|
| `walletAddress` | `String` | Sender wallet address. |
| `contractAddress` | `String` | Target contract address. |
| `transactionData` | `String` | Hex-encoded calldata. |

---

### sendNativeToken(chainId, toAddress, amount)

Sends native tokens (e.g. AVAX) from the current wallet.

- **Returns:** `RainTokenTransferResult` — containing the transaction hash.
- **Throws:** `RainError` if send fails.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID. |
| `toAddress` | `String` | Recipient wallet address. |
| `amount` | `BigDecimal` | Amount in human-readable form (e.g. `BigDecimal("0.1")` for 0.1 AVAX). |

---

### sendToken(chainId, contractAddress, toAddress, amount, decimals?)

Sends ERC-20 tokens (EVM chains) from the current wallet. Routed by `chainId`.

- **Returns:** `RainTokenTransferResult` — containing the transaction hash.
- **Throws:** `RainError` if send fails.
- **Throws on Solana chains:** SPL token transfers are not yet implemented; calling this
  method with a Solana `chainId` (sentinel 101–103) throws `RainError.InvalidConfig`.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID. EVM chain ID. (Solana SPL transfers not yet implemented — see note above.) |
| `contractAddress` | `String` | ERC-20 token contract address. |
| `toAddress` | `String` | Recipient wallet address. |
| `amount` | `BigDecimal` | Amount in human-readable form (e.g. `BigDecimal("100.0")` for 100 USDC). |
| `decimals` | `Int?` | Optional token decimals. When `null` (the default), the SDK resolves the token's `decimals()` from its registry or an on-chain read, so callers don't have to track it. |

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
- **Throws:** `RainError` if the request fails.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID (e.g. `43114` for Avalanche Mainnet). |
| `token` | `Token` | `Token.Native`, or `Token.Contract(address)` (address comparison is case-insensitive). |

---

### getTokenBalances(chainId)

Fetches all non-zero balances for the current wallet on the given network. The native
balance is always included; zero-balance contract tokens are omitted. Supersedes the
deprecated `getBalances(chainId)`, which returned a lossy `Map<String, Double>`.

- **Returns:** `List<Balance>` — one per non-zero token plus the native balance.
- **Throws:** `RainError` if the request fails.
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
- **Throws:** `RainError` if the SDK was not initialized.
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
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID. |
| `limit` | `Int?` | Optional max number of transactions to return. |
| `offset` | `Int?` | Optional pagination offset. |
| `order` | `RainTransactionOrder?` | Optional sort order: `.ASC` or `.DESC`. |

---

### reset()

Clears this client's state (registered tokens + stored chain configuration). Idempotent. Prefer
`RainSdk.reset()` to tear down the whole SDK.

- **Suspend:** No

---

## Deprecated (compatibility shims)

Default-method shims retained so code written against older releases keeps compiling and
binary-linking. Each delegates to the precise current API and collapses the result to the
old shape. Slated for removal in the next major version.

| Deprecated method | Replacement | Notes |
|-------------------|-------------|-------|
| `getAddress(): String` | `getWalletAddress()` | Renamed; shim delegates directly. |
| `sendToken(chainId, contractAddress, toAddress, amount: Double, decimals: Int)` | `sendToken(chainId, contractAddress, toAddress, amount)` | `decimals` now optional; the SDK resolves it. |
| `getBalances(chainId): Map<String, Double>` | `getTokenBalances(chainId)` | Lossy `Double` map keyed by contract address (as returned by the provider); native under the `""` key. |
| `getERC20Balances(chainId): Map<String, Double>` | `getTokenBalances(chainId)` | Drops the native entry; non-zero ERC-20s only, as `Double`. |
| `getNativeBalance(chainId): Double` | `getBalance(chainId, Token.Native)` | Read `.decimalAmount` for exact precision. |
| `getERC20Balance(chainId, tokenAddress, decimals?): Double` | `getBalance(chainId, Token.contract(tokenAddress))` | `decimals` argument ignored; SDK resolves decimals itself. |

---

## Capabilities

A provider advertises optional behaviours via `Capability`, so hosts can resolve by feature
(`rain.first { Capability.EXPORT in it.capabilities }`) and degrade gracefully instead of assuming
a capability every provider has.

| Capability | Meaning |
|------------|---------|
| `EXPORT` | The wallet's key material can be exported / backed up. |
| `RECOVERY` | The wallet supports a recovery ceremony. |
| `MULTI_CHAIN` | The provider holds accounts across multiple chain families (e.g. EVM + Solana). |
| `BIOMETRIC_GATE` | Signing is gated behind a device biometric / passkey prompt. |

Bundled providers: **Portal** → `EXPORT`, `RECOVERY`. **Turnkey** → `MULTI_CHAIN`, `BIOMETRIC_GATE`.

---

## RainTransactionBuilder Methods

Wallet-agnostic utility methods. Access via `rain.transactionBuilder`. These methods do **not**
require a resolved provider — they can be used with any wallet or backend, backed only by the
configured RPC endpoints.

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
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID. |
| `addresses` | `RainWithdrawAddresses` | Proxy, controller, token, recipient addresses. |
| `walletAddress` | `String` | User wallet address (used as `user` in EIP-712). |
| `amount` | `BigDecimal` | Amount in human-readable token units. |
| `decimals` | `Int` | Token decimals. |
| `nonce` | `BigInteger?` | Optional; if `null`, SDK fetches from contract. |

---

### buildWithdrawTransactionData(addresses, amount, decimals, saltBytes, signatureData, adminSignature)

Builds ABI-encoded withdraw calldata for the collateral proxy contract.

- **Returns:** `String` — hex-encoded calldata (e.g. `"0x..."`).
- **Throws:** `RainError` if ABI encoding or validation fails.

| Parameter | Type | Description |
|-----------|------|-------------|
| `addresses` | `RainWithdrawAddresses` | Proxy, controller, token, recipient addresses. |
| `amount` | `BigDecimal` | Amount in human-readable token units. |
| `decimals` | `Int` | Token decimals. |
| `saltBytes` | `ByteArray` | Salt data (32 bytes) for the withdrawal authorization. |
| `signatureData` | `String` | User/wallet signature from Rain API (hex string). |
| `adminSignature` | `RainAdminSignature` | Admin signature (salt, signature, expiresAt). |

---

## Types

| Type | Description |
|------|-------------|
| **`ProviderId`** | Value class wrapping a provider id string. Well-known constants: `PORTAL`, `TURNKEY`, `PRIVY`. Host apps can ship a custom id. |
| **`Capability`** | Enum: `EXPORT`, `RECOVERY`, `MULTI_CHAIN`, `BIOMETRIC_GATE`. |
| **`RainProvider`** | Registrable provider descriptor: `id`, `capabilities`, and a suspend `create(context)` that materializes the `WalletProvider`. Implemented by `PortalProvider`, `TurnkeyProvider`, and host-supplied providers. |
| **`WalletProvider`** | The port each adapter implements. Public so hosts can ship their own wallet stack. |
| **`RainWithdrawAddresses`** | `proxyAddress`, `controllerAddress`, `tokenAddress`, `recipientAddress`. Has `validated()` method for address checksumming. |
| **`RainAdminSignature`** | `salt` (String), `signature` (hex String), `expiresAt` (String, ISO-8601 or unix timestamp). |
| **`RainWithdrawResult`** | `transactionHash` (String?, present if auto-sent), `transactionData` (String?, present if not auto-sent). Has `isAutoSent` and `isTransactionData` helper properties. |
| **`RainTokenTransferResult`** | `transactionHash` (String). Returned by `sendNativeToken` and `sendToken`. |
| **`RainTransactionParameters`** | `from`, `to`, `value` (hex wei), `data` (hex calldata). Wallet-agnostic transaction parameter bag returned by `composeTransactionParameters`. |
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
| `RAIN_101` | `RainError.SdkNotInitialized` | Operation called before the SDK's chain configuration was set up (i.e. before `build()`). |
| `RAIN_102` | `RainError.InvalidConfig` | Invalid RPC URL, chain ID, or address format; no provider registered for the requested id; or no provider matched a capability. |
| `RAIN_103` | `RainError.InvalidRpcUrl` | RPC URL could not be parsed as a valid URL. |
| `RAIN_201` | `RainError.TokenExpired` | Provider session token expired or invalid. |
| `RAIN_202` | `RainError.Unauthorized` | Invalid or missing token / permissions. |
| `RAIN_301` | `RainError.NetworkError` | Network/connectivity failure. |
| `RAIN_401` | `RainError.UserRejected` | User cancelled the signing request in the wallet. |
| `RAIN_402` | `RainError.InsufficientFunds` | Balance too low for the requested amount or gas. |
| `RAIN_403` | `RainError.TransactionSimulationFailed` | Preflight `eth_call` simulation failed (e.g. contract revert, insufficient funds). |
| `RAIN_404` | `RainError.WalletUnavailable` | The backing provider returned no usable wallet address (e.g. Turnkey context has no Ethereum account). |
| `RAIN_405` | `RainError.WithdrawalRevertedByNetwork` | Withdrawal reverted on-chain (e.g. duplicate withdrawal, already-used signature). |
| `RAIN_501` | `RainError.ProviderError` | Portal, Turnkey, or other provider error. |
| `RAIN_502` | `RainError.InternalError` | EIP-712 encoding, ABI encoding, or internal processing error. |

### Error handling example

```kotlin
try {
    val client = rain.provider(ProviderId.PORTAL)
    val result = client.withdrawCollateral(...)
} catch (e: RainError) {
    when (e) {
        is RainError.SdkNotInitialized -> { /* SDK not built */ }
        is RainError.InvalidConfig -> { /* Bad config / unknown provider: ${e.message} */ }
        is RainError.InsufficientFunds -> { /* Not enough balance */ }
        is RainError.NetworkError -> { /* Network issue: ${e.cause} */ }
        else -> { /* Other error: ${e.errorCode.code} — ${e.message} */ }
    }
}
```
