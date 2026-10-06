# Changelog

Notable changes to the Rain Android SDK, newest first. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). An entry a host must act on starts with
**Breaking:** and carries a migration note.

## [Unreleased]

### Fixed

- Solana sends and collateral withdrawals through `rain-turnkey-android` and `rain-wallet-android`
  reach the chain: the wallet backend's client is `com.turnkey:sdk-kotlin` 2.0.2 with
  `com.turnkey:http` 2.1.1, which post the activity type the request body requires.
- `PrivyProvider.refreshSession()` no longer reports a live session as dead because the device was
  offline. Only a refresh Privy refuses, or one it drops the session on, throws `RAIN_201` and
  fires `onSessionExpired`. A refresh that got no answer from the network throws `RAIN_301`
  `NetworkError`; a session the vendor restored without a network answer is verified with it
  first (`Privy.onNetworkRestored()`) and throws `RAIN_301` only if that still fails, from the
  refresh and from the `create()` probe; any other failure with the session still in place throws
  what it was (`RAIN_501` at the floor), with the hook silent. A host that logs the user out on
  the hook no longer does so after a moment offline. On a wallet call through a wallet on Privy's
  server-wallet stack, a request that never left the device (the device offline, no route, no
  connection) now leaves as `RAIN_301` instead of `RAIN_501`, as the adapter's own RPC reads
  already did, and a read whose answer was lost is `RAIN_301` after its retries; an on-device
  wallet reports an offline device through its own prose and stays `RAIN_501`. A send whose
  answer was lost after the request left (a timeout, a reset, or a resend the HTTP client made
  after a reset that then could not connect) stays `RAIN_501`: Privy signs and broadcasts inside
  that request, so its fate is unknown and `RAIN_301`'s "retry" would send twice. A cancellation
  the vendor wrapped in its own failure now leaves a Privy call as the cancellation.

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

- Solana sends through the Turnkey provider and the Rain wallet fail with `RAIN_501`
  (`ProviderError`), and nothing reaches the chain. The wallet backend rejects the request with
  HTTP 400. This affects `sendNative`, `sendToken` and `withdrawCollateral` on Solana chains. EVM
  sends, Solana reads, `prepareWithdrawal` and Privy's Solana sends are not affected. The fix is
  listed under Unreleased above.
- `PrivyProvider.refreshSession()` with the device offline throws `RAIN_201` and fires
  `onSessionExpired`, although the session is live and the next refresh online succeeds; on a
  session restored while offline it throws `RAIN_201` without firing the hook. A host that logs
  the user out on the hook logs them out after a moment offline. Until the fix, check connectivity
  before acting on the hook, or confirm the death with a second `refreshSession()` once the device
  is online. The fix is listed under Unreleased above.
- The Rain wallet's contact attach, `sendContactVerificationCode` followed by
  `confirmContactVerification`, replaces the account's email address or phone number instead of
  adding a second one. The old contact then no longer logs in to the account. The attach also
  succeeds for an email address that already logs in to another account. That address keeps
  opening the other account, which leaves the signed-in account with no working email login.
  Tell users that an attach replaces their current contact.
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
