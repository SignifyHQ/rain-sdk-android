# Rain SDK Sample App (Android)

A Jetpack Compose sample that exercises the public Rain SDK surface with a real wallet provider:
connect a wallet, read balances, send tokens, withdraw collateral, and list transaction history.

---

## Requirements

- Android Studio with its bundled JBR (JDK 21)
- An emulator or device on API 26+
- The SDK modules in this repo (`:rain-core-android`, `:rain-portal-android`, `:rain-privy-android`)

## How to run

1. Open the repo root in Android Studio.
2. Select the **app** run configuration and a device.
3. Run.

---

## Screens

| Screen | What it exercises |
|---|---|
| **Home** | Provider choice (Portal / Turnkey / Privy), Rain API credentials, auth, `RainSdk` build, active-chain dropdown, feature grid |
| **Wallet & QR** | `getWalletAddress(chainId)` and the collateral deposit address from `fetchCollateralContracts()`, each with a QR bitmap from `generateAddressQRCode(address)` |
| **Balances** | Collateral balances (Rain API) plus the wallet's own native and token balances (`getBalance`, `getTokenBalances`) |
| **Send Tokens** | `sendNative` and `sendToken` (ERC-20 on EVM, SPL on Solana) |
| **Collateral Withdraw** | `fetchAdminSignature` + `withdrawCollateral`, on both EVM and Solana collateral |
| **Transaction History** | `getTransactions(chainId, limit, offset, order)`, newest-first |

Every feature screen reads the chain picked in the **Active wallet** dropdown on Home, so switching
networks needs no re-initialization: the SDK is built with all chains' RPC endpoints at once (see
`WalletChain.rpcEndpoints`).

## Networks

`WalletChain` defines the three demo networks — Avalanche Fuji, Base Sepolia, and Solana devnet —
along with each one's RPC URL, native symbol, explorer links, default token / recipient, and address
validation. Portal holds no Solana account, so selecting Portal restricts the dropdown to the EVM
chains.

## Providers

Auth is the host app's responsibility; the SDK only wants an authenticated provider handle. Both
sample auth drivers are reference code you would write yourself:

- **Portal** — paste a Portal session token on Home and tap *Initialize SDK*.
- **Turnkey** (`TurnkeyAuthSample`) — parent organization ID + auth proxy config ID + email OTP.
  Sign-up and login share one `completeOtp` path; an EVM and a Solana wallet are provisioned if the
  sub-org lacks them.
- **Privy** (`PrivyAuthSample`) — app ID + app client ID + email OTP; embedded Ethereum and Solana
  wallets are created on first sign-in.

Rain API credentials (program `Api-Key` + Rain `userId`) are separate from the wallet provider: they
authenticate the contract and withdrawal-signature calls, and are entered in their own card on Home.
Nothing is persisted — the fields are re-entered each launch.

`RainSession` also calls `registerTokens` after resolving Turnkey and Privy. That is not a
workaround the SDK needs in production — it is the same mechanism a host app uses when a token
cannot be discovered on chain. An SPL mint carries no on-chain symbol, and the built-in token
registry is mainnet-only, so naming the testnet mints keeps the balance screen readable.

## Notes

- **Portal wallet recovery** is unavailable: the Rain API has no backup-share endpoint yet (it is
  slated to move behind `POST /v1/issuing/users/{userId}/wallet`). The PIN field on Home surfaces
  that state rather than calling a dead endpoint.
- **Solana history** rows carry the Turnkey activity id rather than a resolvable signature, so those
  rows are not linked to an explorer.

## Project structure

```
app/src/main/java/com/rain/sdk/sample/
├── MainActivity.kt          # App entry + Compose navigation host
├── Screen.kt                # Route definitions for the six screens
├── RainSession.kt           # Holds the built RainSdk + resolved RainClient
├── WalletChain.kt           # Demo networks, explorer links, address validation
├── SampleLog.kt             # Logging helper
├── TurnkeyAuthSample.kt     # Turnkey email-OTP + wallet provisioning
├── PrivyAuthSample.kt       # Privy email-OTP + embedded wallets
└── screens/                 # One Screen + ViewModel pair per feature
    ├── HomeScreen / HomeViewModel
    ├── WalletInfoScreen / WalletInfoViewModel
    ├── BalancesScreen / BalancesViewModel
    ├── SendTokensScreen / SendTokensViewModel
    ├── CollateralWithdrawScreen / CollateralWithdrawViewModel
    └── TransactionHistoryScreen / TransactionHistoryViewModel
```

---

## Key code

### Building the SDK

From `RainSession` — every provider follows the same builder shape, differing only in the registered
provider and the `ProviderId` resolved:

```kotlin
val sdk = RainSdk.builder()
    .rpcEndpoints(rpcEndpoints)                                   // Map<Int, String>
    .register(TurnkeyProvider(TurnkeyConfig(turnkey = turnkey)))
    .rainApiCredentials(apiKey, userId)                           // optional
    .build()
val client = sdk.provider(ProviderId.TURNKEY)
```

For the SDK methods the screens call and their full parameter lists, see
[Method overview](../docs/METHODS.md).
