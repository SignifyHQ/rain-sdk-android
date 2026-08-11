# Auth Pull integration (Android)

Auth Pull lets a card authorization draw funds directly from the user's wallet into their Rain
collateral contract. When an authorization arrives for a user whose tenant is enabled for Auth
Pull, Rain pulls the full amount in USDC from that wallet before approving the authorization.

**What the SDK does:** sets and inspects the ERC-20 allowance that makes the pull possible.
**What the SDK does not do:** the pull itself. That is Rain's `transferFrom`, executed server-side
with no app involvement.

Four methods cover the whole surface:

| Method | Purpose |
|---|---|
| `approveTokenAllowance(chainId, contractAddress, spender, amount?)` | Approve Rain's operator to spend the user's USDC |
| `getTokenAllowance(chainId, contractAddress, spender, owner?)` | Read the current allowance |
| `estimateApprovalFee(chainId, contractAddress, spender, amount?)` | Price the approval before submitting it |
| `confirmTokenAllowance(transactionHash, chainId, contractAddress, spender, amount?, owner?)` | Wait for a successful receipt and read back the resulting allowance |

See [Method reference](METHODS.md#approvetokenallowancechainid-contractaddress-spender-amount)
for full parameter tables.

---

## Prerequisites

Before an authorization can pull:

1. The tenant is enabled for Auth Pull — contact Rain.
2. The user has a wallet address associated with their Rain account on a supported chain.
3. That wallet holds USDC on the supported chain.
4. That wallet holds enough native gas to pay for the approval transaction.
5. That wallet has approved the Rain operator — the part this SDK performs.

## Supported chains and assets

USDC is the only asset supported while Auth Pull is in beta.

### Sandbox

| Chain | Chain ID | USDC |
|---|---|---|
| Base Sepolia | `84532` (`RainChain.BASE_SEPOLIA`) | `0x036CbD53842c5426634e7929541eC2318f3dCF7e` |
| Arbitrum Sepolia | `421614` (`RainChain.ARBITRUM_SEPOLIA`) | `0x75faf114eafb1BDbe2F0316DF893fd58CE46AA4d` |

### Production

| Chain | Chain ID | USDC |
|---|---|---|
| Base | `8453` (`RainChain.BASE_MAINNET`) | `0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913` |
| Arbitrum | `42161` (`RainChain.ARBITRUM_MAINNET`) | `0xaf88d065e77c8cC2239327C5EDb3A432268e5831` |

All four are in the SDK's built-in token registry, so balance and allowance reads resolve USDC's
decimals with no `registerTokens` call and no on-chain lookup. The public API is
token-address-based rather than USDC-only, so it keeps working when Rain adds assets.

`RainAuthPullChains` holds these two sets, keyed by environment:

```kotlin
val chains = RainAuthPullChains.supported(RainApiEnvironment.Dev)   // {84532, 421614}
RainAuthPullChains.isSupported(RainChain.BASE_MAINNET, RainApiEnvironment.Dev)  // false
```

**To gate UI, use `authPullChainIds` instead.** The environment's set is the wider answer; what a
built SDK will accept is that set narrowed by the host's `RainAuthPullConfig` and by which chains
have an RPC endpoint. Both `RainSdk` and `RainClient` expose the resolved one, so a screen holding
either can offer exactly the chains an approval will succeed on:

```kotlin
val enabled = rain.authPullChainIds          // or: client.authPullChainIds
if (chainId !in enabled) { /* don't offer Auth Pull here */ }
```

The two differ whenever a configuration is narrower than its environment, an RPC endpoint is
missing, or the environment is `Custom` — which `supported(...)` reports as empty however the
gateway is configured, making the resolved set the only way to enumerate a custom gateway's chains.
Reach for `supported(...)` only where no SDK exists yet, such as a chain picker built at startup.

## Environments must match

The sandbox and production chain sets are disjoint, and the operator and USDC addresses differ
between them. Approving on the wrong environment's chain still mines a perfectly valid allowance —
one that no authorization will ever draw on, and on mainnet at real cost to the user. `approve`
succeeds against any address, so nothing downstream would catch it.

The SDK therefore rejects mismatched chain, token, and operator targets locally with
`RainError.InvalidConfig` (`RAIN_102`), before wallet access.

The environment defaults to `RainApiEnvironment.Dev`, but Auth Pull itself is disabled until the
builder receives a trusted configuration:

```kotlin
val rain = RainSdk.builder()
    .rpcEndpoints(rpcEndpoints)
    .rainApiEnvironment(RainApiEnvironment.Dev)
    .authPullConfig(RainAuthPullConfig.sandbox(rainOperatorAddress))
    .register(provider)
    .build()
```

The SDK then requires the exact configured operator and canonical USDC contract on every approval,
allowance read, confirmation, and fee estimate. `RainApiEnvironment.Custom` fails closed; a custom
gateway must explicitly use `RainAuthPullConfig.custom(operator, tokenAddresses)`.

## The operator address

The spender is Rain's operator: **one address per environment**, the same on every chain within
that environment, and different between sandbox and production.

It is trusted builder configuration, deliberately not an SDK constant — read it from Rain rather
than hardcoding it, and key it off the same environment the SDK is configured with. Rain publishes
the
current values in its
[Auth Pull docs](https://docs.rain.xyz/docs/authorization-pull-from-user-wallet).

Passing a spender or token other than the configured target throws `RainError.InvalidConfig` before
wallet access.

## Funding the wallet in sandbox

Testnet USDC comes from the [Circle faucet](https://faucet.circle.com/) — choose Base Sepolia or
Arbitrum Sepolia and paste the wallet address associated with the Rain user. Native gas comes from
the usual chain faucet. Without native gas the approval cannot be submitted at all.

---

## Integration

### 1. Read the current allowance

Cheap and free — do this before prompting the user, so a wallet that is already approved does not
pay for a redundant transaction.

```kotlin
val allowance = client.getTokenAllowance(
    chainId = RainChain.BASE_SEPOLIA,
    contractAddress = usdcAddress,
    spender = rainOperatorAddress
)

when {
    allowance.isUnlimited -> Unit               // Nothing to do.
    allowance.covers(expectedSpend) -> Unit     // Enough for now.
    else -> promptForApproval()
}
```

`rawAmount` is the exact base-unit value and is never lossy — compare against it. `decimalAmount`
and `formatted` are for display; `formatted` is plain notation, so an unlimited allowance prints as
a meaningless 72-digit USDC figure — gate on `isUnlimited` and show a label instead. `covers`
answers from `rawAmount`, and returns `false` for an amount it cannot represent at all (negative,
or finer than the token's scale) — so `false` means "do not rely on this allowance", not "approve
more".

`isUnlimited` is exact (`rawAmount == uint256` max). Some tokens decrement even a max allowance on
every `transferFrom`, so a wallet approved as unlimited can later report `false` while still
holding an enormous allowance. Treat `false` as "compare `rawAmount` against what you need", not
as "must re-approve".

### 2. Estimate the fee (optional)

```kotlin
val fee = client.estimateApprovalFee(
    chainId = RainChain.BASE_SEPOLIA,
    contractAddress = usdcAddress,
    spender = rainOperatorAddress
)
// e.g. 0.00042 ETH
```

Nothing is broadcast and the user is not prompted. The estimate prices the exact calldata the
approval would send.

### 3. Approve

```kotlin
// Unlimited — Rain's recommendation, so the user never has to re-approve.
val result = client.approveTokenAllowance(
    chainId = RainChain.BASE_SEPOLIA,
    contractAddress = usdcAddress,
    spender = rainOperatorAddress
)
println(result.transactionHash)
```

An omitted (or `null`) `amount` means unlimited. Pass a `BigDecimal` to cap the allowance, and
`BigDecimal.ZERO` to revoke:

```kotlin
// Cap at 250 USDC.
client.approveTokenAllowance(
    chainId = RainChain.BASE_SEPOLIA,
    contractAddress = usdcAddress,
    spender = rainOperatorAddress,
    amount = BigDecimal("250")
)

// Revoke.
client.approveTokenAllowance(
    chainId = RainChain.BASE_SEPOLIA,
    contractAddress = usdcAddress,
    spender = rainOperatorAddress,
    amount = BigDecimal.ZERO
)
```

A capped approval is a standing balance, not a per-authorization budget: each pull consumes part
of it, and once it runs out the next authorization is declined. Unlimited avoids that failure mode.

**Changing a non-zero allowance.** The call writes the new value straight over the old one. USDC
accepts that on all four Auth Pull chains, but some ERC-20s (USDT and its clones) revert unless the
allowance is set to `BigDecimal.ZERO` first — approve zero, wait for it to mine, then approve the
new amount. The same rewrite is also the classic approval race: between the two transactions a
spender that already holds an allowance can spend the old value and then the new one. Neither
applies to Rain's operator on USDC today; both become live questions if Auth Pull adds an asset.

### 4. Confirm

`transactionHash` means submitted, not mined. Use `confirmTokenAllowance` before treating the user
as ready; it polls for a successful receipt (up to 60s) and reads back the resulting allowance:

```kotlin
val updated = client.confirmTokenAllowance(
    transactionHash = result.transactionHash,
    chainId = RainChain.BASE_SEPOLIA,
    contractAddress = usdcAddress,
    spender = rainOperatorAddress,
    amount = BigDecimal("250")   // omit for the unlimited approval
)
```

**The result can legitimately be lower than what you approved.** Auth Pull spends this very
allowance, and USDC decrements it on every `transferFrom` — including a `uint256` max one, which
Circle's token does not special-case. An authorization that pulls between the receipt and the read
therefore leaves less than was approved, and that is a success, not a failure. Compare `rawAmount`
(or `covers`) against what you still need rather than against what you asked for.

Two outcomes are genuine failures and throw: a mined revoke that left a spendable allowance, and a
mined approval whose allowance is still zero — the shape a wrong owner, token, or spender produces.
A reverted receipt throws `TransactionSimulationFailed`; exhausting the poll window throws
`NetworkError`, which means "not confirmed yet", not "failed" — re-read the allowance rather than
re-approving.

Once the allowance is in place, no further app action is required. Rain pulls the full
authorization amount into the user's collateral contract at authorization time.

---

## Amounts and decimals

Amounts are human-readable `BigDecimal`s, matching `sendToken`. The SDK scales by the token's
decimals and **rejects** an amount finer than the token supports (`InvalidAmount`, `RAIN_406`)
rather than truncating it — `1.2345678` on 6-decimal USDC is an error, not `1.234567`.

**There is no `decimals` parameter.** Auth Pull resolves the scale itself — trusted registry
metadata first, then a one-time strict on-chain `decimals()` read — and takes no override, because
a caller-supplied scale is exactly the input that turns a 250 USDC approval into 250 million. An
unlimited approval skips resolution entirely; `uint256` max needs no scaling.

**The approval path never guesses a scale.** Elsewhere in the SDK a failed `decimals()` read falls
back to 18, which misreports a balance. Here the same guess against a 6-decimal token would approve
10^12 times the intended amount — and `approve` succeeds regardless of the wallet's balance, so
nothing downstream would catch it. An unresolvable token therefore throws `RainError.TokenNotFound`
(`RAIN_102`). USDC on all four Auth Pull chains ships in the registry, so this only fires for a
token whose `decimals()` read fails — register it up front with `registerTokens` if you hit it.

## Provider support

| Provider | Approve / allowance / fee estimate | Notes |
|---|---|---|
| **Portal** | Supported | Broadcast goes through `WalletProvider.sendTransaction`, the same path withdrawals use; Portal's pre-simulation applies to approvals unchanged. |
| **Privy** | Supported | Same generic path, carrying the approve calldata unchanged. |
| **Turnkey** | Supported | Approvals ride the same signed-transaction pipeline as any other ERC-20 send; no Turnkey-specific gating is needed. |

No provider needed a bespoke approval path, so no capability gate exists for this feature. The
allowance value itself is read over the SDK's own configured RPC (`eth_call`), never through the
wallet provider, so it works on any provider and on any configured, trusted Auth Pull chain. The
provider is asked for one thing: the wallet address to read the allowance *for*, and only when
`owner` is omitted — pass `owner` explicitly and the read touches no wallet at all.

## Failure modes

| Code | Case | When |
|---|---|---|
| `RAIN_102` | `RainError.InvalidConfig` | Auth Pull is not configured, target differs from the trusted token/operator, malformed input, wrong chain/environment, or the token reports decimals outside `0..77`. Local configuration failures occur before wallet access. |
| `RAIN_102` | `RainError.TokenNotFound` | The token's decimals could not be established (not in the registry and its `decimals()` read failed), so a capped amount cannot be scaled safely. Never raised for an unlimited approval. |
| `RAIN_301` | `RainError.NetworkError` | `confirmTokenAllowance` exhausted its 60s poll window. Not confirmed yet — re-read the allowance, don't re-approve. |
| `RAIN_401` | `RainError.UserRejected` | The user declined the signature in the wallet UI. |
| `RAIN_402` | `RainError.InsufficientFunds` | Not enough native gas to submit the approval. |
| `RAIN_403` | `RainError.TransactionSimulationFailed` | Preflight simulation reverted (providers that simulate), or `confirmTokenAllowance` found a mined receipt that reverted. |
| `RAIN_406` | `RainError.InvalidAmount` | Negative amount, or more decimal places than the token supports. |
| `RAIN_501` | `RainError.ProviderError` | The wallet provider failed for its own reasons. |
| `RAIN_502` | `RainError.InternalError` | ABI encoding failed, a Solana chain ID was passed (approvals are EVM-only), or a mined allowance contradicted the request (revoke left a spendable allowance, or an approval left zero). |

Errors are always `RainError`; vendor errors are wrapped, never surfaced raw.

## Not covered

EIP-2612 `permit` (gasless) approvals, gasless / sponsored approval transactions, non-USDC assets,
and account-abstraction flows are out of scope for this release.

## See also

- [Method reference](METHODS.md)
- Sample app — the **Auth Pull** screen in `app/` runs this whole flow
- Rain's [Auth Pull documentation](https://docs.rain.xyz/docs/authorization-pull-from-user-wallet)
