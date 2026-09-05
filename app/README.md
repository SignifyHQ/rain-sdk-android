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
| **Home** | Provider choice (Portal MPC / Turnkey / Privy), Rain API credentials, auth, `RainSdk` build, session card, active-wallet dropdown, feature grid |
| **Wallet & QR** | `getWalletAddress(chainId)` and the collateral deposit address from `fetchCollateralContracts()`, each with a QR bitmap from `generateAddressQRCode(address)` |
| **Balances** | Collateral balances (Rain API) plus the wallet's own native and token balances (`getBalance`, `getTokenBalances`) |
| **Send tokens** | `sendNative` and `sendToken` (ERC-20 on EVM, SPL on Solana) |
| **Withdraw collateral** | `fetchAdminSignature` + `withdrawCollateral`, with `estimateWithdrawalFee` and `prepareWithdrawal` dry runs, on both EVM and Solana collateral |
| **Auth pull** | `getTokenAllowance`, `estimateApprovalFee`, `approveTokenAllowance` + `confirmTokenAllowance`, and revocation |
| **History** | `getTransactions(chainId, limit, offset, order)`, newest-first |

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

- **Portal MPC** — paste a Portal session token on Home and tap *Initialize SDK*.
- **Turnkey** (`TurnkeyAuthSample`) — parent organization ID + auth proxy config ID + email OTP.
  Sign-up and login share one `completeOtp` path; an EVM and a Solana wallet are provisioned if the
  sub-org lacks them.
- **Privy** (`PrivyAuthSample`) — app ID + app client ID + email OTP; embedded Ethereum and Solana
  wallets are created on first sign-in.

Rain API credentials (program `Api-Key` + Rain `userId`) are separate from the wallet provider: they
authenticate the contract and withdrawal-signature calls, and are entered in their own card on Home.
Nothing is persisted — the fields are re-entered each launch.

`RainSession` also registers each demo chain's testnet token (`WalletChain.defaultTokenInfo`) via
`registerTokens` on the builder, identically for all three providers. That is not a workaround the
SDK needs in production — it is the same mechanism a host app uses when a token cannot be
discovered on chain. An SPL mint carries no on-chain symbol, and the built-in token registry is
mainnet-only, so naming the testnet tokens keeps the balance screen readable.

## Notes

- **Portal wallet recovery** is unavailable: the Rain API has no backup-share endpoint yet (it is
  slated to move behind `POST /v1/issuing/users/{userId}/wallet`), so the app has no recovery UI.
- **Solana history** rows carry the Turnkey activity id rather than a resolvable signature, so those
  rows are not linked to an explorer.

## Project structure

```
app/src/main/java/com/rain/sdk/sample/
├── MainActivity.kt          # App entry + Compose navigation host (wrapped in RainTheme)
├── Screen.kt                # Route definitions for the seven screens
├── RainSession.kt           # Holds the built RainSdk + resolved RainClient
├── WalletChain.kt           # Demo networks, explorer links, address validation
├── SampleEnvironment.kt     # Sandbox vs production: Rain API host, Auth pull operator
├── SampleLog.kt             # Logging helper
├── TurnkeyAuthSample.kt     # Turnkey email-OTP + wallet provisioning
├── PrivyAuthSample.kt       # Privy email-OTP + embedded wallets
├── ui/                      # Rain design system port (see Design below)
│   ├── theme/               # RainColors, RainType, RainTheme
│   └── RainComponents.kt    # Cards, pill buttons, inputs, badges, icon tiles, toggle
└── screens/                 # One Screen + ViewModel pair per feature, plus shared helpers
    ├── Common.kt            # Address/hash/money formatting, TransactionResultCard
    ├── HomeScreen / HomeViewModel
    ├── WalletInfoScreen / WalletInfoViewModel
    ├── BalancesScreen / BalancesViewModel
    ├── SendTokensScreen / SendTokensViewModel
    ├── CollateralWithdrawScreen / CollateralWithdrawViewModel
    ├── AuthPullScreen / AuthPullViewModel
    └── TransactionHistoryScreen / TransactionHistoryViewModel
app/src/main/res/drawable/   # Rain wordmark and Phosphor line icons as vector drawables
```

## Design

The screens follow the Rain brand system rather than stock Material: a white canvas, one typeface
in two weights (Light for body, Semibold for headings and labels), two type sizes (16 and 32, plus
12 for badges), hairline neutral borders on all four sides, 20dp cards, 4dp inputs, pill buttons
that settle to pink while pressed, sentence case throughout, and no emoji. Pink is reserved for the
wordmark, the icon tiles, and interaction states. The tokens live in `ui/theme` and the components
in `ui/RainComponents.kt`; the design canvas the port was built from is the Claude Design project
"Rain SDK Sample App".

Two things are stand-ins until the brand assets are dropped in:

- **Typeface.** Rain's Antique Legacy is licensed and not checked in, so text renders on the
  platform sans at the same two weights. `RainType.fontFamily` documents the one-line swap once the
  OTFs are placed in `app/src/main/res/font/`.
- **Icon tiles.** The home grid's wallet / coin / transaction / bank / secure / time glyphs are
  hand-drawn line icons in the Phosphor idiom inside the brand's pink container
  (`RainIconTile`). The design canvas uses the brand's "settle" (handshake) tile for Withdraw; a
  legible handshake needs the real asset, so the port uses the set's "bank" glyph until the PNGs
  (or Phosphor's SVGs) replace the `ic_tile_*` drawables. Chrome icons (back, caret, copy, external
  link, check) are Phosphor's own paths.

## Key code

### Building the SDK

From `RainSession` — every provider follows the same builder shape, differing only in the registered
provider and the `ProviderId` resolved:

```kotlin
val sdk = RainSdk.builder()
    .rpcEndpoints(rpcEndpoints)                                   // Map<Int, String>
    .register(TurnkeyProvider(TurnkeyConfig(turnkey = turnkey)))
    .registerTokens(WalletChain.entries.map { it.defaultTokenInfo })
    .rainApiCredentials(apiKey, userId)                           // optional
    .build()
val client = sdk.provider(ProviderId.TURNKEY)
```

For the SDK methods the screens call and their full parameter lists, see
[Method overview](../docs/METHODS.md).
