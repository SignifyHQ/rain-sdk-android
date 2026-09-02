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
rain.buildEIP712Message(...)                     // wallet-agnostic — no provider required
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
| `transactionBuilder` | `RainTransactionBuilder` | **Deprecated** — the builder methods are now on `RainSdk` itself. |
| `isRainApiConfigured` | `Boolean` | True once an Api-Key and userId have been supplied. |
| `authPullChainIds` | `Set<Int>` | Chains Auth Pull is enabled on for this instance: the configured `RainAuthPullConfig`'s chains intersected with the chains that have an RPC endpoint. Empty when no `authPullConfig(...)` was supplied. Also exposed on `RainClient`; see [authPullChainIds](#authpullchainids). |

### Methods

#### builder(): Builder

Starts a new `Builder`.

#### provider(id): RainClient

Resolves the `RainClient` backed by the provider registered under `id`, materializing the vendor
wallet on first access and caching it thereafter.

- **Returns:** `RainClient` bound to that provider.
- **Throws:** `RainError.ProviderNotRegistered` if no provider was registered for `id`.
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
- **Throws:** `RainError.ProviderNotRegistered` if no registered provider matches.
- **Suspend:** Yes.

| Parameter | Type | Description |
|-----------|------|-------------|
| `predicate` | `(RainProvider) -> Boolean` | Match tested against each registered provider descriptor. |

#### reset()

Tears down all resolved clients and clears the Rain API credentials. Idempotent.

The chain configuration is immutable state fixed at `build()`, so this instance stays usable: the
next `provider(id)` / `first { }` call re-resolves the provider from scratch. Build a new `RainSdk`
via `builder()` to change configuration.

- **Suspend:** No

### Rain API (issuing)

The SDK talks to the Rain issuing API directly: supply a program **Api-Key** and Rain **userId**
(builder `rainApiCredentials(apiKey, userId)` or `configureRainApi(apiKey, userId)` at runtime) and
it mints, caches, and refreshes the client session token internally. Credentials are never
persisted. Select the environment with `rainApiEnvironment(...)` (`Dev` default, `Production`,
`Custom(url)`).

These methods need no wallet provider — only the credentials and RPC endpoints.

#### configureRainApi(apiKey, userId)

Sets or replaces the Api-Key / userId pair at runtime. The cached session token is discarded lazily;
the next API call re-mints against the new pair.

- **Suspend:** No

| Parameter | Type | Description |
|-----------|------|-------------|
| `apiKey` | `String` | Rain program Api-Key. |
| `userId` | `String` | Rain user ID the contracts and signatures belong to. |

#### fetchCollateralContracts(): List\<RainCollateralContract\>

Fetches the user's collateral contracts (`GET /v1/issuing/users/{userId}/contracts`). Token
`name` / `symbol` / `decimals` are enriched from the SDK token store (registry, host-registered
tokens, or an on-chain read) — best-effort, so a failed lookup leaves them null.

- **Throws:** `RainError.ApiNotConfigured` when no credentials were supplied.
- **Suspend:** Yes

#### fetchCollateralContract(): RainCollateralContract

Convenience for the common single-contract case: the first collateral contract.

- **Throws:** `RainError.NoCollateralContracts` when the user has none.
- **Suspend:** Yes

#### fetchAdminSignature(chainId, tokenAddress, amountBaseUnits, adminAddress, recipientAddress, isAmountNative): RainAdminSignature

Fetches the admin withdrawal signature
(`GET /v1/issuing/users/{userId}/signatures/withdrawals`) that authorizes a `withdrawCollateral`
call.

- **Throws:** `RainError.SignatureNotReady` while Rain prepares the signature; retry after the
  carried `retryAfter` seconds.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID. |
| `tokenAddress` | `String` | Token contract address to withdraw (SPL mint on Solana). |
| `amountBaseUnits` | `BigInteger` | Withdrawal amount in the token's base units. |
| `adminAddress` | `String` | One of the contract's `adminAddresses`. |
| `recipientAddress` | `String` | Withdrawal recipient. |
| `isAmountNative` | `Boolean` | Defaults to `true`. |

---

## RainSdk.Builder

Assembles a `RainSdk`. Module dependencies decide which providers can be registered — the builder
never names a vendor SDK itself.

| Method | Description |
|--------|-------------|
| `rpcEndpoints(endpoints: Map<Int, String>)` | Sets the `chainId → RPC URL` map every provider shares. **Required.** |
| `rpcEndpoints(configs: List<NetworkConfig>)` | Same, as `NetworkConfig` values (chain id + RPC URL + optional display name). Replaces rather than appends; a later duplicate `chainId` wins. |
| `register(provider: RainProvider)` | Registers a provider adapter (e.g. `PortalProvider`, `TurnkeyProvider`). Re-registering the same id replaces the prior one. |
| `registerTokens(tokens: List<TokenInfo>)` | Seeds the shared token store with extra token metadata. |
| `rainApiEnvironment(environment: RainApiEnvironment)` | Selects the Rain issuing API environment. Defaults to `Dev`. |
| `rainApiCredentials(apiKey: String, userId: String)` | Supplies the Rain program Api-Key and userId at build time — same effect as `configureRainApi` on the built instance. |
| `authPullConfig(config: RainAuthPullConfig)` | Enables Auth Pull for the exact operator and token contracts in `config` (`RainAuthPullConfig.sandbox(...)` / `.production(...)` / `.custom(...)`). Without it, the approval, allowance, confirmation, and approval-fee methods fail closed. See [AUTH_PULL.md](AUTH_PULL.md). |
| `build(): RainSdk` | Validates endpoints (fail-fast on a bad URL / chain id) and returns the SDK. Throws `RainError.InvalidConfig` if no RPC endpoints were configured, or the Rain API base URL does not parse, or the Auth Pull configuration is invalid: a malformed or zero operator or token address, an empty token map, an environment mismatch, a chain outside the known Auth Pull sets, or no RPC endpoint for any configured Auth Pull chain. |

Registering **zero** providers is allowed: the SDK is then wallet-agnostic, exposing
the transaction-building methods and the Rain API methods. Resolving `provider(id)` throws
`RainError.ProviderNotRegistered` until a provider is registered.

### Provider adapters

Each adapter is a `RainProvider` descriptor that owns its vendor SDK as a private dependency.

| Adapter | Module | Config | Notes |
|---------|--------|--------|-------|
| `PortalProvider(PortalConfig(sessionToken, chainId?, sessionPolicy?, onSessionTokenNeeded?, onSessionExpired?, autoApprove?))` | `rain-portal-android` | `sessionToken: String`, `chainId: Int?`, `sessionPolicy: PortalSessionPolicy`, `onSessionTokenNeeded: (suspend () -> String?)?`, `onSessionExpired: (() -> Unit)?`, `autoApprove: Boolean = true` | Portal MPC signer (EVM). Advertises `EXPORT`, `RECOVERY`.|
| `TurnkeyProvider(TurnkeyConfig(turnkey, walletAddress?, sessionPolicy?, onSessionExpired?))` | `rain-core-android` | `turnkey: TurnkeyContext`, `walletAddress: String?`, `sessionPolicy: TurnkeySessionPolicy`, `onSessionExpired: (() -> Unit)?` | Turnkey P256 signer (EVM + Solana). Advertises `MULTI_CHAIN`, `BIOMETRIC_GATE`. See [TURNKEY_SUPPORT.md](TURNKEY_SUPPORT.md). |
| `PrivyProvider(PrivyConfig(privy, walletAddress?, sessionPolicy?, onSessionExpired?))` | `rain-privy-android` | `privy: Privy`, `walletAddress: String?`, `sessionPolicy: PrivySessionPolicy`, `onSessionExpired: (() -> Unit)?` | Privy embedded-wallet signer (EVM + Solana). Advertises `EXPORT`, `RECOVERY`, `MULTI_CHAIN`.|

#### Portal construction

The adapter constructs the vendor `Portal` with `autoApprove = PortalConfig.autoApprove`,
`FeatureFlags(isMultiBackupEnabled = true)`, and an `eip155:<chainId> → rpcUrl` RPC config. Three
vendor-shaped details are worth knowing:

- **Storage backends.** portal-android registers backup storage at backup-call time, so the
  adapter passes none at construction.
- **`autoApprove`.** Defaults to `true`, and the adapter also answers
  `PortalEvents.PortalSigningRequested` with `PortalSigningApproved` while it is on. Every Rain call
  that signs is already an explicit, user-initiated SDK call and Portal raises no approval UI of its
  own, so an unanswered signing request simply hangs. Pass `false` only if the host gates signing
  itself, and then answer the event on the `Portal` instance handed to `onPortalCreated`. The
  handler is registered on the `Portal` instance itself, so while `autoApprove` is on every signing
  request on that instance is auto-approved, including ones the host makes directly through the
  `onPortalCreated` instance.
- **`chainId`.** `PortalConfig.chainId` feeds portal-android's **required** `legacyEthChainId`
  constructor parameter, so the adapter must supply one; the field lets the host pick it instead of
  guessing. Omit it and the adapter falls back to Avalanche mainnet when configured, else the first
  configured chain. PortalSwift 7.x takes no such parameter, so iOS's `PortalConfig` has no
  `chainId` — an intentional, vendor-imposed divergence, not a parity gap.

**Bring your own provider:** implement the `WalletProvider` port and a `RainProvider` descriptor
(with your own `ProviderId`), then `register(...)` it. Core needs no change — the transaction-building methods
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
| `authPullChainIds` | `Set<Int>` | Chains this client will accept an Auth Pull approval on. Empty until `RainSdk.Builder.authPullConfig(...)` supplies the trusted targets; see [authPullChainIds](#authpullchainids). |

---

### withdrawCollateral(chainId, addresses, amount, decimals, adminSignature, nonce)

Full withdrawal flow: builds the transaction, signs via the backing provider, submits, and
returns the transaction hash. Use [prepareWithdrawal](#preparewithdrawalchainid-addresses-amount-decimals-adminsignature-nonce)
to build without broadcasting.

> **Migrating from 1.0.x — this method always broadcasts now.** In 1.0.x the same name took an
> `autoSend: Boolean = false` trailing parameter, so a call that left it out only *prepared* the
> transaction and returned `RainWithdrawResult.transactionData`. That flag and that type are gone:
> `withdrawCollateral` signs and submits, and the prepare-only path is `prepareWithdrawal`.
> Old call sites mostly fail to compile — passing `autoSend` is an unknown parameter, reading
> `.transactionHash` / `.transactionData` off the `String` result is an error, and builds that took
> `amount: Double` no longer accept a `Double` — but a call that omitted `autoSend` **and discards
> the result** compiles unchanged and moves funds. Audit every `withdrawCollateral` call when
> upgrading; the ones meant to prepare must become `prepareWithdrawal`.

On EVM chains this calls `withdrawAsset` on the Rain coordinator contract (EIP-712 admin
signature + the Rain API signature). On Solana chains it drives Rain's on-chain collateral
program instead: the SDK reads the collateral account (program id, coordinator, nonce) from the
chain, verifies nothing is stale by simulating, and submits a transaction carrying an ed25519
verification of Rain's coordinator signature followed by the program's
`withdraw_single_signer_collateral_asset` instruction. Only single-signer Solana collateral
accounts are supported; the wallet must be the account's owner.

- **Returns:** `String` — the transaction hash (EVM) or transaction signature (Solana).
- **Throws:** `RainError` if construction, signing, or submission fails.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID (e.g. `43114`, or `901` for Solana devnet). |
| `addresses` | `RainWithdrawAddresses` | All required addresses: proxy, controller, token, recipient. On Solana, `proxyAddress` is the collateral account, `tokenAddress` is the SPL mint, and `controllerAddress` is unused (the coordinator is read from the collateral account). |
| `amount` | `BigDecimal` | Amount in human-readable token units (e.g. `BigDecimal("100.0")`). |
| `decimals` | `Int` | Token decimals (e.g. 6 for USDC, 18 for most tokens). Load-bearing on every chain including Solana: it scales the amount and is **not** checked against the SPL mint, so pass the mint's real decimals. |
| `adminSignature` | `RainAdminSignature` | Admin signature for authorization (salt, signature, expiresAt). |
| `nonce` | `BigInteger?` | Optional nonce; if `null`, SDK resolves from contract. Ignored on Solana — the nonce always comes from the on-chain collateral account. |

---

### prepareWithdrawal(chainId, addresses, amount, decimals, adminSignature, nonce)

Builds a collateral withdrawal without broadcasting it. Takes the same parameters as
`withdrawCollateral`.

This is **not** an offline build: it still prompts the wallet to sign EIP-712 (EVM) and reads the
collateral's admin set on chain. On Solana it additionally fetches a recent blockhash and simulates
the transaction.

- **Returns:** `RainPreparedWithdrawal` — `Evm(RainTransactionParameters)` carrying a complete,
  submittable transaction (`from` / `to` / `value` / `data`), or `Solana(UnsignedSolanaTransfer)`
  carrying the serialized unsigned transaction plus its `recentBlockhash`.
- **Throws:** `RainError` if construction or signing fails.
- **Suspend:** Yes

> A Solana blockhash is valid for roughly 150 slots (60–90 seconds). Submit promptly or re-prepare.

Use `evmParameters` / `solanaTransfer` to read the payload without writing a `when`.

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

### estimateWithdrawalFee(chainId, addresses, amount, decimals, salt, signature, expiresAt)

Estimates the total fee required to execute a collateral withdrawal transaction. The withdrawal
authorization (`salt` / `signature` / `expiresAt`, as returned by `fetchAdminSignature`) is
caller-supplied and embedded in the estimated calldata.

Internally builds the EIP-712 payload, signs it with the wallet, then runs `eth_estimateGas`
against the withdrawal controller. Nothing is broadcast.

> **Signing side effect.** The estimated calldata embeds a wallet signature the controller
> verifies (a placeholder would revert the estimate), so estimate-then-withdraw signs twice.

- **Returns:** `BigDecimal`, the estimated withdrawal fee in the chain's native token.
- **Throws:** `RainError` if estimation fails.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID. |
| `addresses` | `RainWithdrawAddresses` | All addresses required for the withdrawal (controller, proxy, token, recipient). |
| `amount` | `BigDecimal` | Human-readable amount to withdraw. |
| `decimals` | `Int` | Token decimals (e.g. 6 for USDC, 18 for most tokens). |
| `adminSignature` | `RainAdminSignature` | The withdrawal authorization from `RainSdk.fetchAdminSignature`. |
| `nonce` | `BigInteger?` | Optional; pin the estimate to the nonce the withdrawal will sign. |

EVM only — throws on a Solana chain id.

---

### sendNative(chainId, to, amount)

Sends native tokens (e.g. AVAX) from the current wallet.

> `sendNativeToken(chainId, toAddress, amount)` is a deprecated alias that delegates to this method.

- **Returns:** `RainTokenTransferResult` — containing the transaction hash.
- **Throws:** `RainError` if send fails.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID. |
| `to` | `String` | Recipient wallet address. |
| `amount` | `BigDecimal` | Amount in human-readable form (e.g. `BigDecimal("0.1")` for 0.1 AVAX). |

---

### sendToken(chainId, contractAddress, to, amount, decimals?)

Sends ERC-20 tokens (EVM chains) or SPL tokens (Solana chains) from the current wallet.
Routed by `chainId`.

- **Returns:** `RainTokenTransferResult` — containing the transaction hash.
- **Throws:** `RainError` if send fails.
- **On Solana chains** (Rain IDs 900 mainnet / 901 devnet, SDK-internal 902 testnet):
  `contractAddress` is the SPL mint. The mint's own on-chain `decimals` are authoritative — the
  `decimals` parameter is not used to scale the amount — and a missing recipient token account is
  created in the same transaction at the sender's expense. This applies to `sendToken` only; see
  `withdrawCollateral`, where `decimals` **is** load-bearing.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID. |
| `contractAddress` | `String` | ERC-20 token contract address, or the SPL mint on Solana. |
| `to` | `String` | Recipient wallet address. |
| `amount` | `BigDecimal` | Amount in human-readable form (e.g. `BigDecimal("100.0")` for 100 USDC). |
| `decimals` | `Int?` | Optional token decimals. When `null` (the default), the SDK resolves the token's `decimals()` from its registry or an on-chain read, so callers don't have to track it. If neither can establish it, the send throws `TokenNotFound` rather than scaling by a guessed value. |

---

### authPullChainIds

The chains this client will accept an Auth Pull approval on — the host's `RainAuthPullConfig`
narrowed to the chains that have an RPC endpoint, and the same set the approval guard enforces.
Empty until `RainSdk.Builder.authPullConfig(...)` supplies the trusted targets. Also available on
`RainSdk` itself, for gating before a client is resolved.

Gate host UI on this rather than on `RainAuthPullChains.supported(environment)`, which answers for
an environment and is the wider set. See [Auth Pull](AUTH_PULL.md#supported-chains-and-assets).

- **Type:** `Set<Int>`
- **Suspend:** No

---

### approveTokenAllowance(chainId, contractAddress, spender, amount?)

Approves `spender` to move up to `amount` of an ERC-20 token from the current wallet — the
wallet-side prerequisite for Rain's [Auth Pull](AUTH_PULL.md). Rain executes the pull itself; the
SDK only sets the allowance.

Auth Pull is disabled until `RainSdk.Builder.authPullConfig(...)` supplies the trusted operator and
per-chain token targets. The SDK rejects any different chain, token, or spender before wallet access.

- **Returns:** `RainTokenApprovalResult` — the transaction hash of the `approve` call.
- **Throws:** `RainError`. EVM only — a Solana `chainId` throws `RainError.InternalError`, since
  SPL delegation is not an ERC-20 allowance. A `chainId` outside `authPullChainIds` throws
  `RainError.InvalidConfig`.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target EVM network chain ID. Must be an Auth Pull chain for the configured environment. |
| `contractAddress` | `String` | ERC-20 token contract (USDC for Auth Pull today). |
| `spender` | `String` | Address being approved — Rain's operator. Source it from Rain; it differs between sandbox and production. |
| `amount` | `BigDecimal?` | Human-readable allowance (e.g. `BigDecimal("250")`). `null` (the default) approves an unlimited (`uint256` max) allowance; `BigDecimal.ZERO` revokes. |

There is deliberately **no `decimals` parameter** on any Auth Pull method: the scale comes from
trusted registry metadata or a strict on-chain `decimals()` read, never from the caller. A token
whose decimals cannot be established throws `RainError.TokenNotFound` rather than being guessed at.

```kotlin
// Unlimited — what Rain recommends, so the user never has to re-approve.
val result = client.approveTokenAllowance(
    chainId = RainChain.BASE_SEPOLIA,
    contractAddress = usdc,
    spender = rainOperator
)

// Capped, then revoked.
client.approveTokenAllowance(RainChain.BASE_SEPOLIA, usdc, rainOperator, BigDecimal("250"))
client.approveTokenAllowance(RainChain.BASE_SEPOLIA, usdc, rainOperator, BigDecimal.ZERO)
```

The new value is written straight over the old one. USDC accepts that; some ERC-20s (USDT and its
clones) revert unless an existing non-zero allowance is set to zero first — see
[Auth Pull](AUTH_PULL.md#3-approve).

---

### getTokenAllowance(chainId, contractAddress, spender, owner?)

Reads the ERC-20 allowance `spender` currently holds over `owner`'s balance. Call it before
approving (to skip a redundant transaction). To confirm an approval was mined, use
`confirmTokenAllowance`, this read is unpinned and can still return the pre-approval value.

- **Returns:** `RainTokenAllowance` — see [RainTokenAllowance value type](#raintokenallowance-value-type).
- **Throws:** `RainError`. EVM only, and gated to the configured environment's Auth Pull chains like
  the approval itself.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target EVM network chain ID. |
| `contractAddress` | `String` | ERC-20 token contract. |
| `spender` | `String` | Address whose allowance is being read — Rain's operator. |
| `owner` | `String?` | Wallet whose balance is approved. `null` (the default) reads this client's own wallet, which is the only case that touches the wallet provider at all. |

> `spender` precedes `owner` so the optional parameters land last and Kotlin's default arguments
> work.

---

### estimateApprovalFee(chainId, contractAddress, spender, amount?)

Estimates the total fee (estimated gas x gas price) to submit the approval, in the chain's native
token. Nothing is broadcast and no signature is requested; the fee is priced against the exact
calldata `approveTokenAllowance` would send.

- **Returns:** `BigDecimal` — fee in the chain's native currency (e.g. ETH).
- **Throws:** `RainError`.
- **Suspend:** Yes

Parameters are identical to `approveTokenAllowance`.

---

### confirmTokenAllowance(transactionHash, chainId, contractAddress, spender, amount?, owner?)

Waits for an approval transaction to mine successfully, then reads back the resulting allowance. A
transaction hash alone means submitted, not ready: use this before treating the user as approved for
Auth Pull.

Polls `eth_getTransactionReceipt` once a second for up to 60 seconds, then reads the allowance
through the same path as `getTokenAllowance`, pinned to the block the transaction mined in.

- **Returns:** `RainTokenAllowance` — the allowance actually in place after the transaction mined.
- **Throws:** `RainError`. Reverted receipt → `TransactionSimulationFailed`; poll window exhausted →
  `TransactionPending` with the transaction hash as `statusId` (not confirmed *yet* — re-read, don't
  re-approve); mined allowance not equal to the request, or a Solana `chainId` → `InternalError`.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `transactionHash` | `String` | Hash returned by `approveTokenAllowance`. |
| `chainId` | `Int` | Target EVM network chain ID. Must match the approval's chain. |
| `contractAddress` | `String` | ERC-20 token contract the approval was against. |
| `spender` | `String` | Address that was approved — Rain's operator. |
| `amount` | `BigDecimal?` | The allowance that was requested, so the result can be checked against it. `null` (the default) means the unlimited approval; `BigDecimal.ZERO` means a revoke. |
| `owner` | `String?` | Wallet whose allowance to read. `null` (the default) reads this client's own wallet. |

**The returned allowance must equal `amount`.** The read is pinned to the block the approval mined
in, so later blocks cannot move what is read back. Anything but the requested amount means the
approval did not do what was asked — below when it was raising the allowance, above when it was
lowering it, still zero when it mined against the wrong owner, token, or spender, or non-zero after
a revoke — and throws `InternalError` (`RAIN_502`). One edge: an Auth Pull `transferFrom` mined in
the *same block* as the approval also reads back lower and surfaces as `RAIN_502`; re-read with
`getTokenAllowance` before treating that as a failed approval. Later pulls decrement the live
allowance, so use `getTokenAllowance` (and `covers`) for the ongoing check.

```kotlin
val result = client.approveTokenAllowance(RainChain.BASE_SEPOLIA, usdc, rainOperator)
val allowance = client.confirmTokenAllowance(
    transactionHash = result.transactionHash,
    chainId = RainChain.BASE_SEPOLIA,
    contractAddress = usdc,
    spender = rainOperator
)
```

---

### RainTokenAllowance value type

| Member | Type | Description |
|--------|------|-------------|
| `rawAmount` | `BigInteger` | Exact allowance in the token's smallest unit. Never lossy — compare against this. |
| `decimals` | `Int` | The token's decimals (e.g. 6 for USDC). |
| `chainId` / `tokenAddress` / `owner` / `spender` | | What was read, so a merged list stays self-describing. |
| `isUnlimited` | `Boolean` | `rawAmount == uint256` max. Exact: some tokens decrement even a max allowance, so `false` does not mean "must re-approve" — compare `rawAmount` against what you need. |
| `isZero` | `Boolean` | Nothing approved — the state after a revoke. |
| `decimalAmount` | `BigDecimal` | Derived: `rawAmount / 10^decimals`. For an unlimited approval this is ~1.16e71; gate on `isUnlimited` before rendering. |
| `formatted` | `String` | Derived display string with trailing zeros trimmed. |
| `covers(amount)` | `(BigDecimal) -> Boolean` | Whether a human-readable amount is still covered, compared in exact base units. An amount that cannot be represented at all (negative, or finer than the token's scale) is `false` too. |
| `UNLIMITED_RAW_AMOUNT` | `BigInteger` | Companion constant: `uint256` max. |

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
on-chain enrichment call. Retained across re-initialization; cleared by `reset()`. Built-in
registry tokens are trusted and cannot be overridden: a registration naming one is ignored with a
warning.

- **Returns:** `Unit`
- **Suspend:** No

| Parameter | Type | Description |
|-----------|------|-------------|
| `tokens` | `List<TokenInfo>` | Tokens to add to the SDK's token store. Re-registering a host-added address replaces its entry; built-in registry tokens cannot be overridden. |

---

### generateAddressQRCode(address, dimension)

Generates a square QR code `Bitmap` encoding `address`, or the wallet's own address when `address`
is `null`. Use it for any address the host needs to show — a chain-specific wallet address (the
Solana account rather than the EVM one), or a Rain collateral deposit address.

- **Returns:** `Bitmap` — QR code image.
- **Throws:** `RainError` if wallet is unavailable or QR generation fails.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `address` | `String?` | Address to encode. If `null`, uses the current wallet address. |
| `dimension` | `Int` | Output width and height in pixels (the QR is square). Defaults to `256`. |

---

### getTransactions(chainId, limit, offset, order)

Fetches transaction history for the current wallet on the given network.

- **Returns:** `List<RainTransaction>` — the transaction records. `value` is a `BigDecimal?` in human-readable units; null when decimals could not be resolved, with `rawValue` still populated.
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

Clears this client's own state (its registered tokens). Idempotent. The chain configuration is
owned by the `RainSdk` and shared with every other resolved client, so it deliberately survives —
one client resetting must not deconfigure the others. Prefer `RainSdk.reset()` to tear down the
whole SDK.

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
| `generateAddressQRCode(address, width, height)` | `generateAddressQRCode(address, dimension)` | A QR code is square; the two dimensions were always equal in practice. |
| `composeTransactionParameters(walletAddress, contractAddress, transactionData)` | `RainSdk.buildTransactionParameters(...)` | Pure composition needs no resolved client; moved to `RainSdk`. |

### Removed without a shim

| 1.0.x signature | Replacement | Why no shim |
|-----------------|-------------|-------------|
| `withdrawCollateral(chainId, addresses, amount, decimals, adminSignature, nonce, autoSend = false): RainWithdrawResult` | `withdrawCollateral(...)` to broadcast, `prepareWithdrawal(...)` to build only | The current method shares the leading parameters, so a shim with a defaulted `autoSend` would never be selected for calls that omit it — Kotlin prefers the overload using fewer defaults — and could not restore the old prepare-only default. See the migration note under [withdrawCollateral](#withdrawcollateralchainid-addresses-amount-decimals-adminsignature-nonce). |

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
**Privy** → `EXPORT`, `RECOVERY`, `MULTI_CHAIN`.

---

## Wallet-agnostic transaction building

Available directly on `RainSdk`. These methods do **not** require a resolved provider — they can be
used with any wallet or backend, backed only by the configured RPC endpoints.

### getLatestNonce(chainId, proxyAddress)

Reads the collateral's current admin nonce — the value `buildEIP712Message` binds when `nonce` is
omitted.

- **Returns:** `BigInteger` — the current nonce.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID; the RPC endpoint is resolved from it. |
| `proxyAddress` | `String` | The collateral proxy contract address. |

---

### isCollateralAdmin(chainId, proxyAddress, walletAddress)

Whether `walletAddress` is an admin of the collateral.

- **Returns:** `Boolean?` — the contract's answer, or `null` when the check could not run (RPC
  failure, or a collateral exposing no `isAdmin`). Treat `null` as unknown and proceed, never as
  "not authorized".
- **Suspend:** Yes

---

### buildEIP712Message(chainId, walletAddress, addresses, amount, decimals, nonce)

Builds the EIP-712 message the wallet signs to authorize a withdrawal, along with the salt bound
into it.

- **Returns:** `RainEIP712Message` — `message` (typed-data JSON), `salt` (32 raw bytes), and
  `saltHex`. Feed `salt` straight back into `buildWithdrawTransactionData`; a re-generated salt
  would not match the signature.
- **Throws:** `RainError` if message construction fails or inputs are invalid.
- **Suspend:** Yes

| Parameter | Type | Description |
|-----------|------|-------------|
| `chainId` | `Int` | Target network chain ID. |
| `walletAddress` | `String` | User wallet address (used as `user` in EIP-712). |
| `addresses` | `RainWithdrawAddresses` | Proxy, controller, token, recipient addresses. |
| `amount` | `BigDecimal` | Amount in human-readable token units. |
| `decimals` | `Int` | Token decimals. |
| `nonce` | `BigInteger?` | Optional; if `null`, the SDK reads it from the contract. |

---

### buildWithdrawTransactionData(addresses, amount, decimals, executorSignature, walletSalt, walletSignature)

ABI-encodes the `withdrawAsset` call for the collateral controller. Pure encoding — no RPC, so it
needs no chain ID.

Two distinct salt/signature pairs go in, and the contract names them differently from Rain's API:
`executorSignature` (what `fetchAdminSignature` returns) encodes into `_executorPublisherSalt` /
`_executorPublisherSignature`, while the wallet's own pair encodes into `_adminSalts` /
`_adminSignatures` — the wallet is an admin of the collateral.

- **Returns:** `String` — hex-encoded calldata (e.g. `"0x..."`).
- **Throws:** `RainError` if ABI encoding or validation fails.

| Parameter | Type | Description |
|-----------|------|-------------|
| `addresses` | `RainWithdrawAddresses` | Proxy, controller, token, recipient addresses. |
| `amount` | `BigDecimal` | Amount in human-readable token units. |
| `decimals` | `Int` | Token decimals. |
| `executorSignature` | `RainAdminSignature` | Rain's authorization (salt, signature, expiresAt). |
| `walletSalt` | `ByteArray` | `RainEIP712Message.salt`, unchanged (32 bytes). |
| `walletSignature` | `String` | The wallet's hex signature over the EIP-712 message (65 bytes). |

---

### buildTransactionParameters(walletAddress, contractAddress, transactionData)

Composes a wallet-agnostic transaction parameter bag for a contract call. Pure composition — no
wallet provider and no RPC — returning a Rain-owned `RainTransactionParameters` struct with `value`
pre-set to `"0x0"`. Hosts can hand the result to any provider for signing / broadcast.

- **Returns:** `RainTransactionParameters` — `from`, `to`, `value` (`"0x0"`), `data`.
- **Suspend:** No

| Parameter | Type | Description |
|-----------|------|-------------|
| `walletAddress` | `String` | Sender wallet address. |
| `contractAddress` | `String` | Target contract address. |
| `transactionData` | `String` | Hex-encoded calldata. |

---

## Types

| Type | Description |
|------|-------------|
| **`ProviderId`** | Value class wrapping a provider id string. Well-known constants: `PORTAL`, `TURNKEY`, `PRIVY`. Host apps can ship a custom id. |
| **`Capability`** | Enum: `EXPORT`, `RECOVERY`, `MULTI_CHAIN`, `BIOMETRIC_GATE`. |
| **`RainEIP712Message`** | `message`, `salt`, `saltHex`. Returned by `buildEIP712Message`. |
| **`RainProvider`** | Registrable provider descriptor: `id`, `capabilities`, and a suspend `create(context)` that materializes the `WalletProvider`. Implemented by `PortalProvider`, `TurnkeyProvider`, and host-supplied providers. |
| **`WalletProvider`** | The port each adapter implements. Public so hosts can ship their own wallet stack. |
| **`RainWithdrawAddresses`** | `proxyAddress`, `controllerAddress`, `tokenAddress`, `recipientAddress`. Has `validated()` method for address checksumming. |
| **`RainAdminSignature`** | `salt` (String), `signature` (hex String), `expiresAt` (String, ISO-8601). |
| **`RainPreparedWithdrawal`** | Sealed: `Evm(parameters: RainTransactionParameters)` or `Solana(transfer: UnsignedSolanaTransfer)`. Has `evmParameters` / `solanaTransfer` accessors. |
| **`RainTokenTransferResult`** | `transactionHash` (String). Returned by `sendNative` and `sendToken`. |
| **`RainTokenApprovalResult`** | `transactionHash` (String): hash of the ERC-20 `approve` call. Returned by `approveTokenAllowance`. |
| **`RainTokenAllowance`** | Exact allowance value type; see [RainTokenAllowance value type](#raintokenallowance-value-type). |
| **`RainAuthPullConfig`** | Trusted Auth Pull targets for one environment: `operatorAddress` plus a `chainId → token contract` map. Built via `RainAuthPullConfig.sandbox(...)`, `.production(...)`, or `.custom(...)`; passed to `RainSdk.Builder.authPullConfig(...)`. |
| **`RainAuthPullChains`** | The Auth Pull chain sets by environment: `SANDBOX` (Base Sepolia, Arbitrum Sepolia), `PRODUCTION` (Base, Arbitrum), `supported(environment)`, `isSupported(chainId, environment)`. Answers for an *environment*; gate UI on `authPullChainIds`, which answers for the built SDK. |
| **`NetworkConfig`** | `chainId`, `rpcUrl`, `networkName?`; `eip155ChainId` renders `eip155:<chainId>`, and `NetworkConfig.fromEip155(...)` parses that form. Accepted by `Builder.rpcEndpoints(List<NetworkConfig>)`. |
| **`RainTransactionParameters`** | `from`, `to`, `value` (hex wei), `data` (hex calldata). Wallet-agnostic transaction parameter bag returned by `RainSdk.buildTransactionParameters`. |
| **`RainTransaction`** | Transaction record: `hash`, `uniqueId`, `blockNumber`, `timestamp`, `from`, `to`, `value`, `asset`, `tokenAddress`, `rawValue`, `decimals`, `category`, `chainId`, `metadata`. Identical in shape to the iOS type. |
| **`RainTransactionCategory`** | Extensible constant: `External`, `Token`, `Erc20`, `Erc721`, `Erc1155`, `ContractInternal`. |
| **`RainTransactionOrder`** | Enum: `.ASC`, `.DESC`. Used in `getTransactions(..., order:)`. |
| **`RainChain`** | Constants: `AVALANCHE_MAINNET` (43114), `AVALANCHE_TESTNET` (43113), `BASE_MAINNET` (8453), `BASE_SEPOLIA` (84532), `ARBITRUM_MAINNET` (42161), `ARBITRUM_SEPOLIA` (421614), plus the Solana sentinels. |

---

## Errors

All methods can throw `RainError` (sealed class). Each error includes an `errorCode` property for programmatic handling.

Format: `"RainSDK Error [CODE]: message"`

| Code | Class | Meaning |
|------|-------|---------|
| `RAIN_101` | `RainError.SdkNotInitialized` | Operation called before the SDK's chain configuration was set up (i.e. before `build()`). |
| `RAIN_102` | `RainError.InvalidConfig` / `RainError.ProviderNotRegistered` | Invalid RPC URL, chain ID, or address format; no provider registered for the requested id; or no provider matched a capability. |
| `RAIN_103` | `RainError.InvalidRpcUrl` | RPC URL could not be parsed as a valid URL. |
| `RAIN_201` | `RainError.TokenExpired` | Provider session token expired or invalid. |
| `RAIN_202` | `RainError.Unauthorized` | Invalid or missing token / permissions. |
| `RAIN_301` | `RainError.NetworkError` | Network/connectivity failure. |
| `RAIN_303` | `RainError.TransactionPending` | Submitted, not yet confirmed. `statusId` is what to resume from (status id, UserOperation hash, or transaction hash). Do not resend. |
| `RAIN_401` | `RainError.UserRejected` | User cancelled the signing request in the wallet. |
| `RAIN_402` | `RainError.InsufficientFunds` | Balance too low for the requested amount or gas. |
| `RAIN_403` | `RainError.TransactionSimulationFailed` | Preflight `eth_call` simulation failed (e.g. contract revert, insufficient funds). |
| `RAIN_404` | `RainError.WalletUnavailable` | The backing provider returned no usable wallet address (e.g. Turnkey context has no Ethereum account). |
| `RAIN_405` | `RainError.WithdrawalRevertedByNetwork` | Withdrawal reverted on-chain (e.g. duplicate withdrawal, already-used signature). |
| `RAIN_406` | `RainError.InvalidAmount` | The amount is invalid for the token — negative, more decimal places than the token supports, or past `uint256` max. |
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
