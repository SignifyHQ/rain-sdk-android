# Changelog

Notable changes to the Rain Android SDK, newest first. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). An entry a host must act on starts with
**Breaking:** and carries a migration note.

## [Unreleased]

### Fixed

- Sends and collateral withdrawals refuse an EVM address written in mixed case whose EIP-55
  checksum doesn't match, with `RAIN_102`, before anything is signed. A mistyped character almost
  always breaks the checksum, and 5.0.0-beta.1 checked only the address's shape, so `sendNative`,
  `sendToken`, `withdrawCollateral` and `prepareWithdrawal` went ahead with the mistyped address. A
  send's recipient is refused as `InvalidRecipient`, a withdrawal address as `InvalidConfig`. The
  same check guards every method that takes `RainWithdrawAddresses`, `estimateWithdrawalFee` and
  the wallet-agnostic `RainSdk.buildEIP712Message` and `RainSdk.buildWithdrawTransactionData`
  included, the proxy passed to `RainSdk.getLatestNonce`, and the wallet address
  `buildEIP712Message` signs for. `sendToken` now checks its token contract the same way, and a
  malformed contract fails with `RAIN_102` before the provider is called, where 5.0.0-beta.1 handed
  it to the provider and surfaced the provider's error. `RainSdk.Builder.build()` checks the Auth
  Pull operator and token contracts. An address written in a single letter case carries no
  checksum and is accepted as before.
- The built-in token registry lists BNB Chain USDC and Celo USDT in EIP-55 form. 5.0.0-beta.1
  carried both with a wrong mixed-case checksum, so `tokenMetadata` and `registerTokens` refused
  the registry's own spelling of those two tokens, and the check above would have refused them on
  `sendToken` as well.
- Solana sends and collateral withdrawals through `rain-turnkey-android` and `rain-wallet-android`
  reach the chain: the wallet backend's client is `com.turnkey:sdk-kotlin` 2.0.2 with
  `com.turnkey:http` 2.1.1, which post the activity type the request body requires.
- With Privy, two refusals for a shortfall of the native currency that arrived with the wrong code
  are `RAIN_402` `InsufficientFunds`: an EVM send over the balance, which the `eth_call` preflight
  now reads through the shared classifier (it knows "EVM error: OutOfFunds", Base Sepolia's node's
  wording), and a Solana `sendNative` from a wallet holding no SOL, which Privy's wallet API
  refuses with the node's "found no record of a prior credit". Every 4xx from that API other than
  a 401 or 403, on any wallet-API call, is now read as the preflight reads a node answer before it
  floors at `RAIN_501`: a revert is `RAIN_403`, then a funds shortfall is `RAIN_402`. A Solana
  `sendNative` from a wallet that holds some SOL but not the amount plus the fee is refused with a
  bare program error the SDK cannot read, so it still arrives as `RAIN_501`; core runs no native
  SOL preflight of its own.
- The SDK clears a Rain wallet or Turnkey session revoked by a login on another device as soon as
  the wallet backend refuses to refresh it: inside any wallet call that refreshes (with
  `autoRefresh` on) and inside `refreshSession()`. `hasActiveSession()`, `currentAuthState()` and
  `currentSessionState()` read signed out when the failed call returns, and `sessionState` and
  `authState` emit it. Before, they kept reporting the stored session's local expiry, for up to
  the session's whole lifetime, and a login flow that skips the code on `hasActiveSession()`
  restored the dead session. A refresh that failed for another reason (offline, a 5xx) still
  leaves the session stored, and a refresh whose session a login replaced meanwhile proceeds with
  the new session. In bring-your-own mode the clear removes the session from the `TurnkeyContext`
  the host owns, and the vendor's expiry timer for it is cancelled with it. The sample app asks the
  backend before reusing a restored session.
- The Rain wallet's contact attach refuses a contact that already signs in to another account.
  Once the code is accepted, `confirmContactVerification` asks the wallet backend which account
  the contact signs in to and throws `RAIN_202` (`Unauthorized`) before anything changes when it
  is another one; a contact nobody signs in with, or this account's own, attaches as before. In
  `5.0.0-beta.1` the attach succeeded and replaced the signed-in account's contact with one that
  kept opening the other account. The attach still replaces an existing contact of the same kind
  instead of adding a second one; see Known issues under `5.0.0-beta.1`.

### Changed

- **Breaking:** `sendLoginCode` and `sendContactVerificationCode` lowercase an email before it
  reaches Turnkey, which matches emails case-sensitively: `Jo@Example.com` and `jo@example.com`
  signed up as two accounts with two wallets. Migration: an account created from a mixed-case email
  under 5.0.0-beta.1 is keyed on that exact spelling, and after this change a login with that
  address is lowercased, misses it and signs up a new, empty account. Rain cannot change the stored
  spelling, because the user is the sub-organization's only root. Before upgrading, have such users
  sign in on the current build and export the recovery phrase or move their funds out. Sandbox test
  accounts can simply be recreated.
- On a Solana send through `rain-turnkey-android` or `rain-wallet-android`, `RAIN_302`
  `TransactionPending` may carry the wallet backend's activity id when the backend accepted the send
  without a readable status id, and the activity-log history lists Solana sends under both of the
  backend's send activity types. See [TURNKEY_SUPPORT.md](docs/TURNKEY_SUPPORT.md#solana-notes).

## [5.0.0-beta.1] - 2026-09-30

First public beta, published to Maven Central under `xyz.rain`. Pin the exact version during the
beta; see [Compatibility](README.md#compatibility).

### Added

- `xyz.rain:rain-core-android`, `rain-portal-android`, `rain-privy-android`, `rain-turnkey-android`
  and `rain-wallet-android`, all at `5.0.0-beta.1`. `rain-wallet-android` embeds Rain's production
  wallet backend, so every account it creates is a production account, in a test build too.
- `@ExperimentalRainApi`. Implementing `WalletProvider`, `ProviderDescriptor` or `RainClient`
  requires opting in to it, because Rain may add members to these interfaces in any release.

### Known issues

Rain plans to fix each of these in a later beta.

- Sends and collateral withdrawals accept an EVM address written in mixed case whose EIP-55
  checksum doesn't match. `sendNative`, `sendToken`, `withdrawCollateral`, `prepareWithdrawal` and
  `estimateWithdrawalFee` check only that the address is 40 hex characters, and so do the
  wallet-agnostic `RainSdk.buildEIP712Message`, `RainSdk.buildWithdrawTransactionData` and
  `RainSdk.getLatestNonce`, so a mistyped character in a checksummed address goes through and the
  funds go to an address nobody may control. `sendToken` doesn't check its token contract at all.
  The Auth Pull operator and token contracts passed to `RainSdk.Builder.authPullConfig(...)` have
  the same gap. Until the fix, check mixed-case input in the app before calling, by comparing it
  with its EIP-55 form from any EIP-55 implementation. The fix is listed under Unreleased above.
- Solana sends through the Turnkey provider and the Rain wallet fail with `RAIN_501`
  (`ProviderError`), and nothing reaches the chain. The wallet backend rejects the request with
  HTTP 400. This affects `sendNative`, `sendToken` and `withdrawCollateral` on Solana chains. EVM
  sends, Solana reads, `prepareWithdrawal` and Privy's Solana sends are not affected. The fix is
  listed under Unreleased above.
- With Privy, a send the chain refuses for a shortfall of the native currency can arrive with the
  wrong code: an EVM send over the balance as `RAIN_502` (`InternalError`, "EVM error:
  OutOfFunds") and a Solana `sendNative` from a wallet holding no SOL as `RAIN_501`
  (`ProviderError`). Both mean `RAIN_402` (`InsufficientFunds`). A Solana `sendNative` from a
  wallet holding some SOL but less than the amount plus the fee also arrives as `RAIN_501`, and
  stays so after the fix. An app that retries on `RAIN_501` or `RAIN_502` resends a transfer that
  cannot succeed. The fix is listed under Unreleased above.
- The Rain wallet's contact attach, `sendContactVerificationCode` followed by
  `confirmContactVerification`, replaces the account's email address or phone number instead of
  adding a second one. The old contact then no longer logs in to the account. The attach also
  succeeds for an email address that already logs in to another account. That address keeps
  opening the other account, which leaves the signed-in account with no working email login. The
  fix for that second part, a refusal, is listed under Unreleased above; the replacement remains.
  Tell users that an attach replaces their current contact.
- A session revoked by a login on another device keeps reading as active on the first device:
  `hasActiveSession()`, `currentAuthState()` and `currentSessionState()` report the stored
  session's local expiry until it passes, up to the session's whole lifetime, while every wallet
  call fails with `RAIN_201` and `onSessionExpired` fires once. A login flow that skips the code on
  `hasActiveSession()` restores the dead session. Call `refreshSession()` and treat `RAIN_201` as
  signed out before skipping the code. The fix is listed under Unreleased above.
- `getTokenBalances` and `getAllBalances` can leave out a token the wallet holds. On Ethereum, Base,
  Polygon and their test networks, the Turnkey provider and the Rain wallet build the list from the
  wallet backend's balance service. On other EVM chains, and with Privy on every EVM chain, the
  list holds only the SDK's built-in tokens and those passed to `registerTokens`. Portal builds it
  from Portal's own service. On Solana, the Turnkey provider and the Rain wallet ask the wallet
  backend first and read the node when it fails or lists no tokens, and Privy reads the node, which
  lists every token the wallet holds. On Base Sepolia, for example, the Rain wallet leaves out
  Rain USD.
  `getBalance(chainId, Token.Contract(address))` reads a single token from the chain and works
  for any token.
- At launch with a saved session, `RainProvider.authState` and `sessionState`, and
  `TurnkeyProvider.sessionState`, can emit `Unauthenticated` for a few milliseconds between
  `Loading` and the live session. An app that opens its login screen on that `Unauthenticated`
  shows it to a user who is still signed in.
