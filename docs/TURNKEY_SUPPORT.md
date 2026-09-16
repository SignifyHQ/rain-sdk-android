# Turnkey Support

Rain SDK for Android supports [Turnkey](https://turnkey.com) as a wallet provider, alongside the Portal MPC and Privy adapters. Turnkey ships as the `TurnkeyProvider` adapter in its own `rain-turnkey-android` module (package `com.rain.sdk.turnkey`). Authentication has two modes. **Bring-your-own** (public): the host app uses the official [Turnkey Kotlin SDK](https://docs.turnkey.com/sdks/kotlin/getting-started) to authenticate (passkeys, OAuth, OTP, auth proxy) and hands the live `TurnkeyContext` to Rain via `TurnkeyConfig(turnkey)`. **Managed** (internal API, `@InternalRainTurnkeyApi`): construct `TurnkeyConfig(application, organizationId, authProxyConfigId)` and the SDK owns the one-time-code flow, email or SMS, through Turnkey's auth proxy, with `sendLoginCode` / `confirmLoginCode` / `logout` / `authState` on `TurnkeyProvider` and Ethereum + Solana account provisioning on first login. It compiles only with the opt-in and ships to hosts as the Rain wallet provider, `RainProvider` in `rain-wallet-android`, which exposes the same flow under Rain's names; see [Managed mode](#managed-mode-internal-api) and [rain-wallet-android/README.md](../rain-wallet-android/README.md). In both modes the provider also exports the wallet's recovery phrase and private keys, decrypted on the device. See [Key export](#key-export).

## Requirements

- `minSdk = 28` (matches Turnkey's requirement).
- Managed mode: nothing to initialize — the SDK configures Turnkey itself. Bring-your-own mode: the Turnkey Kotlin SDK initialized in your `Application.onCreate()`, with the passkey/auth-proxy/OAuth/OTP flow completed by the host app.
- **JDK 24+** to run unit tests that touch Turnkey types (`com.turnkey:encoding:1.0.0`, pulled in by the Turnkey SDK, ships class-file major version 68 / Java 24). Production Android builds are unaffected — R8/D8 dexes Turnkey's bytecode regardless of host JVM version. The `TurnkeyWalletProviderTest` suite skips itself automatically on JDKs older than 24 via `Assume.assumeTrue`.

## Adding the dependency

```kotlin
dependencies {
    // Pulls rain-core-android and the Turnkey artifacts transitively.
    implementation("io.github.spartan-quanhongtran:rain-turnkey-android:1.0.1")
}
```

An app that does not register Turnkey should not depend on this module: the Turnkey artifacts come with it, and nothing else in the SDK pulls them. Internally the module pulls in:

```
com.turnkey:sdk-kotlin:2.0.0
com.turnkey:http:2.0.0
com.turnkey:types:2.0.0
com.turnkey:crypto:1.0.1
```

## Two modes

| Mode | Who authenticates | Config | Auth surface |
|---|---|---|---|
| **Bring-your-own** (public) | Your app, against Turnkey's Kotlin SDK | `TurnkeyConfig(turnkey = TurnkeyContext)` | None — `sendLoginCode` / `confirmLoginCode` / `logout` throw `RainError.InvalidConfig`; `awaitSessionRestore` is a no-op, `hasActiveSession()` is `false` and `authState` is `Unauthenticated` |
| **Managed** (internal API, `@InternalRainTurnkeyApi`) | Rain SDK, through Turnkey's auth proxy | `TurnkeyConfig(application, organizationId, authProxyConfigId)` | `TurnkeyProvider.sendLoginCode` (a `LoginContact.Email` or `LoginContact.Sms`) / `confirmLoginCode` / `logout` / `authState` / `awaitSessionRestore` / `hasActiveSession` — compile only with the opt-in |

In both modes the hand-off is the same boundary: register the `TurnkeyProvider` with the `RainSdk` builder, then resolve `rain.provider(ProviderId.TURNKEY)`. Everything after that — `getWalletAddress()`, balances, sends, `withdrawCollateral()` — is identical. Key export (`exportRecoveryPhrase`, `exportPrivateKey`) works in both modes as well. See [Key export](#key-export).

## Bring-your-own mode (the public Turnkey integration)

If your app already drives Turnkey's Kotlin SDK — passkeys, OAuth, your own OTP UI — keep doing that and hand the authenticated context to Rain. Initialize Turnkey from your `Application.onCreate()`:

```kotlin
import com.rain.sdk.RainSdk
import com.turnkey.core.TurnkeyContext
import com.turnkey.core.models.TurnkeyConfig  // NOTE: Turnkey's config, not Rain's TurnkeyConfig
import com.turnkey.core.models.AuthConfig

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // 1) Initialize Turnkey first (host-app responsibility).
        //    For OTP and auth-proxy flows, only organizationId + authProxyConfigId are required:
        TurnkeyContext.init(
            app = this,
            config = TurnkeyConfig(
                organizationId = "<your-parent-organization-id>",
                authProxyConfigId = "<your-auth-proxy-config-id>"
            )
        )

        // For passkey / OAuth flows you must also supply an AuthConfig with the relying-party id
        // (passkeys) and/or appScheme (OAuth deep-links):
        //
        // TurnkeyContext.init(
        //     app = this,
        //     config = TurnkeyConfig(
        //         organizationId = "<your-parent-organization-id>",
        //         authProxyConfigId = "<your-auth-proxy-config-id>",
        //         authConfig = AuthConfig(rpId = "<your-rp-id>"),
        //         appScheme = "<your-app-scheme>"
        //     )
        // )

        // Prefer `TurnkeyContext.initSuspend(app, cfg)` inside a coroutine if you need to await
        // session restoration before driving auth.

        // 2) Drive your auth flow (passkey / OTP / OAuth) somewhere in the app.
    }
}
```

Once the user is authenticated and `TurnkeyContext.session` is populated, hand it to Rain by
registering a `TurnkeyProvider`:

```kotlin
import com.rain.sdk.RainSdk
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.turnkey.TurnkeyConfig
import com.rain.sdk.turnkey.TurnkeyProvider

val rain = RainSdk.builder()
    .rpcEndpoints(
        mapOf(
            8453 to "https://mainnet.base.org",
            84532 to "https://sepolia.base.org"
        )
    )
    .register(
        TurnkeyProvider(
            TurnkeyConfig(
                turnkey = TurnkeyContext,
                walletAddress = null // omit to use the first Ethereum account from TurnkeyContext.wallets
                // sponsorGas defaults to true: sends are gas-sponsored, which needs sponsorship enabled
                // on the Turnkey organization. Pass sponsorGas = false to have users pay their own gas.
            )
        )
    )
    .build()

val client = rain.provider(ProviderId.TURNKEY)
```

`rain.provider(...)` is a `suspend` function — resolving the Turnkey provider probes the Turnkey
wallet list and throws `RainError.WalletUnavailable` if no usable Ethereum account is available
(in managed mode, resolving before a session is live throws `RainError.TokenExpired`).
You can register other adapters (e.g. `PortalProvider`) on the same builder and resolve each
independently; providers no longer replace one another.

## Managed mode (internal API)

Managed mode is not a host-facing mode. Every member of it — the `TurnkeyConfig(application, organizationId, authProxyConfigId)` constructor, `sendLoginCode` / `confirmLoginCode` / `logout` / `awaitSessionRestore` / `hasActiveSession` / `authState` / `currentAuthState`, `LoginContact` and `TurnkeyAuthState` — is marked `@InternalRainTurnkeyApi`, a `@RequiresOptIn` annotation at error level: a host app that calls any of them gets a compile error naming the reason. It is the building block of the Rain wallet provider (`RainProvider` in `rain-wallet-android`), which exposes the same flow in Rain's own terms with no Turnkey types; that module opts in module-wide with `-opt-in=com.rain.sdk.turnkey.InternalRainTurnkeyApi`. The rest of this section documents the flow for that caller.

Before the first run, in the Turnkey dashboard: enable the **Auth Proxy** for your parent organization with **Email OTP** turned on, and **SMS OTP** as well when phone numbers are used (each auth method has its own toggle), copy its auth-proxy config id (it identifies the configuration and is safe to ship in the app; the Turnkey SDK sends it as the `X-Auth-Proxy-Config-ID` header on every proxy call), and set the code format (6–9 characters, numeric or alphanumeric; one setting shared by email and SMS) and the session lifetime (900 seconds by default) there. The SDK reads none of these settings; it obeys them. SMS authentication is a Turnkey Enterprise feature that Turnkey enables on request, off by default on top-level organizations. Leave the dashboard captcha off while this SDK pins Turnkey's Kotlin SDK 2.0.0: that SDK sends no captcha token, so an enabled captcha refuses every code request on both channels.

The SDK configures Turnkey against your parent organization and auth-proxy configuration, runs the one-time-code flow, email or SMS, on the provider itself, and provisions one wallet holding an Ethereum and a Solana account on first login:

```kotlin
import com.rain.sdk.RainSdk
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.turnkey.LoginContact
import com.rain.sdk.turnkey.TurnkeyConfig
import com.rain.sdk.turnkey.TurnkeyProvider

val provider = TurnkeyProvider(
    TurnkeyConfig(
        application = application,
        organizationId = "<your-parent-organization-id>",
        authProxyConfigId = "<your-auth-proxy-config-id>",
    )
)

// Reuse a restored session, or run the code flow:
provider.awaitSessionRestore()
if (!provider.hasActiveSession()) {
    provider.sendLoginCode("user@example.com")
    // or by SMS, with the phone number in E.164 form:
    // provider.sendLoginCode(LoginContact.Sms("+15551234567"))
    provider.confirmLoginCode(code) // sign-up or login + Ethereum/Solana account provisioning
}

val rain = RainSdk.builder()
    .rpcEndpoints(mapOf(84532 to "https://sepolia.base.org"))
    .register(provider)
    .build()
val client = rain.provider(ProviderId.TURNKEY)
```

- `authState` (a `Flow<TurnkeyAuthState>`, snapshot via `currentAuthState()`) reports `Loading` / `Authenticated` / `Unauthenticated`. It is a view over the same derivation as `sessionState`, collapsing an expired session into `Unauthenticated` — a login screen only needs to know whether a code is required. Until the first auth call (`awaitSessionRestore`, `sendLoginCode`, …) has configured Turnkey and its restore has settled, `authState` reads `Loading` and `hasActiveSession()` is `false` — call `awaitSessionRestore()` before reading either.
- A rejected code throws `RainError.InvalidLoginCode` (`RAIN_203`) and keeps the challenge, so the user can retype it. Any other failure inside the code check itself also keeps the challenge — the auth proxy has been seen wrapping a rejection in an HTTP 500 whose body carries the real status, which the Kotlin SDK discards before Rain sees it, so that case surfaces as `RainError.ProviderError` (`RAIN_501`) on Android, while the iOS SDK reads the body and reports `RAIN_203` for the same wrong code. The difference stays until Turnkey's Kotlin SDK forwards the body. On Android, treat `RAIN_501` from `confirmLoginCode` as retryable. The user can retry the code or request a new one. A failure after the code was accepted drops the challenge; if that failure is the switch to the new session, the device is signed out as well — the login already revoked the previous session server-side, so keeping it selected would only fail at the next wallet call — and `onSessionExpired` stays silent because the thrown error is the signal. A provisioning failure keeps the new session (see below). A rejected code never touches an existing session: each login stores its session under a fresh key and switches to it only on success.
- Turnkey's limits on the code flow, which the SDK cannot see and your UI has to design around: a code is valid for 5 minutes by default (up to 10 in the dashboard), locks after 3 wrong attempts, and at most 3 codes can be active per user — an expired or locked code surfaces as the same `RAIN_203` (or `ProviderError`) as a typo. Request a new code by calling `sendLoginCode` again with the same contact; it replaces the pending challenge on success and keeps it on failure. A call for another contact or channel retires the pending challenge before the request, so a failed switch leaves nothing confirmable. Codes may be alphanumeric, so never force a numeric keyboard, for SMS as much as for email: the code format is one shared setting. Turnkey also limits code requests to 3 per 3 minutes per user identifier, but the auth-proxy request carries no such identifier, so per-user throttling and restricting the destination countries you serve are your app's job; Turnkey's own guidance treats SMS as spend an attacker can drive.
- A phone number is trimmed, stripped of spaces, dots, hyphens and parentheses, and must then be E.164 (`+`, country code and number, at most 15 digits): `LoginContact.Sms("+1 (555) 123-4567")` sends `+15551234567`. A national number without its country code is refused with `RainError.InvalidConfig` before any proxy call, and so is a parenthesised trunk zero such as `+44 (0) 20 ...`, which stripping would turn into a different number; convert national formats in your app first (the sample uses `PhoneNumberUtils.formatNumberToE164` with the device's region). The canonical string is the account's identity, so the same string is sent on confirm and used for the account lookup.
- Turnkey's sandbox simulates SMS delivery: the test number `+1 999-999-9999` with the code `000000` works once the proxy's code format is numeric and 6 characters, a setting that changes email codes on the same configuration too.
- A successful login revokes the user's other Turnkey sessions server-side (`invalidateExisting`) and clears the previous local session once the switch to the new one succeeded. The signed-out device's `onSessionExpired` fires at its next call. `hasActiveSession()` reflects the local expiry only — a session revoked from another device reads as active until its first call fails.
- `logout()` clears the selected session — after waiting for a restore in flight to settle — and does **not** fire `onSessionExpired`; cached accounts are still evicted and a pending login code is dropped. With no session selected it is a no-op.
- A first sign-up creates its wallet inside the signup request: one wallet named `Wallet`, 12-word mnemonic, Ethereum `m/44'/60'/0'/0/0` (secp256k1) and Solana `m/44'/501'/0'/0'` (ed25519) — a cross-platform contract shared by Rain's SDKs, so a user provisioned on one platform resolves identically on another. Afterwards, missing accounts are added to the wallet Rain resolves (`createWalletAccounts`); only an organization with no wallet at all gets a new one, so the user has a single mnemonic to back up either way.
- A sign-up creates one Turnkey **sub-organization** per end user under your parent organization. The user is its only root user, with the verified contact, email address or phone number, as the credential (no passkey or API key at sign-up; those can be added later), so the root quorum is the user alone: the parent organization has read-only visibility and can neither reach the keys nor sign. An account is keyed on the contact it signed up with: the same person logging in by email once and by SMS once gets two sub-organizations, two wallets and two mnemonics, unless the contacts were linked outside this SDK (Turnkey's update-phone-number activity, which this SDK does not wrap). Treat one contact as one account, or link the phone number to the existing account before offering SMS to existing users. An SMS sign-up gets Turnkey's default names for the root user and the organization, since there is no email to name them after. The wallet above is created inside that same signup request, and every session and wallet call is scoped to the sub-organization. Deleting a sub-organization is a root-user activity that requires its wallets to have been exported first (or `deleteWithoutExport`) and is not exposed by this SDK.
- The Turnkey configuration is one-shot per app launch and is applied by the first auth call (`awaitSessionRestore`, `sendLoginCode`, `confirmLoginCode`, `logout`) or by provider resolution, whichever comes first. Blank ids, a second managed provider with *different* ids, or a `TurnkeyContext` your app initialized itself make every auth call throw `RainError.InvalidConfig` — relaunch to change ids, or use bring-your-own mode. If Turnkey's own initialization fails or never finishes, every auth call throws `RainError.InternalError` until the app relaunches. Wrong but well-formed ids, SMS OTP not enabled on the proxy configuration, or an undeliverable number are not detected at configuration time: the first auth-proxy call fails instead, and the code request comes back with an HTTP status and no reason (the Kotlin SDK drops the response body), surfaced as `RainError.ProviderError`.
- Resolving the provider before a session is live throws `RainError.TokenExpired`. Resolution also re-checks the account set, so a login whose provisioning failed heals itself without a new code.
- Managed mode needs an `android.app.Application` because Turnkey's Kotlin SDK stores sessions and device keys through it.

The Rain wallet module is this flow's caller; the sample app drives it through `RainSession.prepareRainWallet` / `initializeRainWallet` and `HomeViewModel`. The sample's Turnkey tab is bring-your-own (`TurnkeyAuthSample` plus `RainSession.initializeTurnkey`) and touches none of these members.

## What Rain uses Turnkey for

After the Turnkey-backed `client` is resolved, every wallet operation routes through Turnkey:

| Rain operation | Turnkey API used |
|----------------|------------------|
| `client.getWalletAddress()` | `TurnkeyContext.wallets` (first Ethereum account) |
| `client.getBalance(chainId, Token.Native)` | `TurnkeyClient.getWalletAddressBalances` (CAIP-19 `slip44:` filter) on supported chains; RPC `eth_getBalance` otherwise |
| `client.getBalance(chainId, Token.Contract(...))` | RPC `eth_call` (`balanceOf`) |
| `client.getBalances(chainId)` | `TurnkeyClient.getWalletAddressBalances` (CAIP-19) on supported chains; Multicall3 / parallel `eth_call` otherwise |
| `client.sendNative(...)` / `client.sendToken(...)` | `TurnkeyClient.ethSendTransaction` + `getSendTransactionStatus` polling. Only on Turnkey's managed-broadcast chains — other chains (Avalanche, Celo, ZKsync, Plasma, Ink) are read-only and sends throw `RAIN_105` up front. By default (`sponsorGas = true`), every EVM send (transfers, withdrawals, approvals, raw sends) is sponsored by Turnkey Gas Station (minimal payload carrying Turnkey's gas-station nonce for replay protection, fee estimate `0`), and Solana network fees are sponsored too (a zero-SOL sender skips the fee check and dry run; rent for a new recipient token account is a separate Turnkey toggle and stays with the sender). `TurnkeyConfig(sponsorGas = false)` returns to self-paid sends, and is required on a Turnkey organization without sponsorship enabled. Monad caveat: Turnkey sponsors through EIP-7702 delegation and Monad reverts any delegated-account transaction that would leave the balance under 10 MON, so a sponsored native MON send from a small wallet quotes `0` and then fails on chain (token sends are unaffected). |
| `client.withdrawCollateral(...)` | EVM: `TurnkeyContext.signRawPayload` (EIP-712) + `ethSendTransaction`. Solana: core composes the withdrawal, skipping its self-paid dry run while the adapter sponsors the fee, then `solSendTransaction`. Either chain: a chain outside Turnkey's coverage is refused with `RAIN_105` before anything is read or signed, and a revert Turnkey reports after broadcast surfaces as `WithdrawalRevertedByNetwork`, the same as a failed dry run. |
| `client.getTransactions(...)` | `TurnkeyClient.getActivities` (filtered to `ACTIVITY_TYPE_ETH_SEND_TRANSACTION`) |
| `client.estimateGas(...)` | `0` without any RPC while `sponsorGas` is on (the default) and the chain is a Turnkey broadcast chain; RPC `eth_estimateGas` + `eth_gasPrice` otherwise |

On Solana chain ids the same methods route to `TurnkeyClient.solSendTransaction` /
`getWalletAddressBalances` and the Solana account instead — see [Solana notes](#solana-notes).

## Solana notes

The Turnkey adapter is the SDK's multi-chain provider (it advertises `MULTI_CHAIN`): Solana sentinel
chain ids (`RainChain.SOLANA_MAINNET` 900 / `SOLANA_DEVNET` 901 / `SOLANA_TESTNET` 902) route
`getWalletAddress(chainId)`, balances, `sendNative`, `sendToken`, `withdrawCollateral`, and
`getTransactions` to the Turnkey Solana account.

- **Transfers.** Composition and every preflight live in core's `SolanaTransferComposer` (shared with
  the Privy adapter, so the two cannot drift); the adapter only signs and broadcasts. For SPL that
  covers recipient validation, resolving the mint's decimals and owning token program on chain,
  deriving both associated token accounts, creating the recipient's when missing
  (`CreateIdempotent`, ~0.002 SOL rent paid by the sender), fee checks, and a `simulateTransaction`
  dry run (with `sponsorGas` the fee check and dry run are skipped, the rent check stays, and the
  System Program is carried among the static account keys because Turnkey's sponsored-flow rules
  require it). Failures
  surface as `TokenNotFound`, `TokenAccountNotFound`,
  `InsufficientTokenBalance`, or `InvalidRecipient`.
- **Balances.** From Turnkey's `get-balances` where it indexes the cluster; where it doesn't (devnet
  in particular), `getTokenBalances` discovers holdings from the node via `getTokenAccountsByOwner`
  against both token programs. Solana keeps token metadata off chain, so symbol / name stay null
  unless the mint is registered.
- **History.** From Turnkey's activity log (`ACTIVITY_TYPE_SOL_SEND_TRANSACTION`) — sends only, and
  the row's hash is the Turnkey status id, not an explorer-resolvable signature.
- **Encoding.** Turnkey hex-decodes `unsignedTransaction` despite the type documenting base64, so
  Rain sends hex. Turnkey returns a status id rather than a signature; Rain polls for it, then
  recovers it from `getSignaturesForAddress` (newer than the pre-send baseline only) and verifies
  via `getTransaction` that the candidate is signed by this wallet (the fee payer on a self-paid
  send; on a sponsored send Turnkey's payer model is undocumented, so the check is on signers) with
  `err == null`. If the
  baseline read failed or nothing verifiable lands in time, the send surfaces as
  `TransactionPending` carrying the status id — the same contract as EVM — never the status id
  posing as a signature.
- **Collateral withdrawal.** Authorized differently from EVM: the coordinator executor signs a
  keccak-encoded withdraw message off chain (that is the admin signature the Rain API returns). Core
  composes a two-instruction transaction — a native ed25519 proof that the executor signed that exact
  message, then the program's `withdraw_single_signer_collateral_asset` — reading the collateral
  account, its coordinator's executors, and the mint's token program from chain, and deriving the
  collateral-authority PDA and token accounts locally. It simulates (self-paid only; a fee-sponsored
  provider skips the dry run), then hands the bytes to the
  adapter, which signs them **as-is**: re-serializing would invalidate the embedded signature.
  `proxyAddress` is the collateral account, `tokenAddress` the SPL mint; single-signer collateral
  only. `prepareWithdrawal` returns the prepared unsigned transaction with its blockhash.

## Signing

EIP-712 signing uses `TurnkeyContext.signRawPayload` with `PAYLOAD_ENCODING_EIP712` + `HASH_FUNCTION_NO_OP`. Rain normalizes the returned `r`, `s`, `v` components into a `0x`-prefixed 65-byte hex signature compatible with `eth_signTypedData_v4` responses (recovery id auto-adjusted to 27/28 range when needed).

## Key export

`TurnkeyProvider` exports the wallet's recovery phrase and one private key per chain family, in bring-your-own and managed mode alike. The methods live on the descriptor, so a host can offer a backup right after login, before the SDK is built.

```kotlin
val provider = TurnkeyProvider(TurnkeyConfig(turnkey = TurnkeyContext))

val phrase = provider.exportRecoveryPhrase()                              // space-separated BIP-39 words
val ethereumKey = provider.exportPrivateKey(TurnkeyKeyFamily.ETHEREUM)   // "0x" + 64 lowercase hex characters
val solanaKey = provider.exportPrivateKey(TurnkeyKeyFamily.SOLANA)       // plain Base58 of the 64-byte seed || public key
```

**Formats**, a cross-platform contract shared by Rain's SDKs:

- Recovery phrase: the wallet's BIP-39 phrase as the wallet was created. Managed wallets have 12 words. A bring-your-own wallet has the length it was created with.
- Ethereum: the 32-byte secp256k1 key as `0x` plus 64 lowercase hex characters, the form MetaMask's *Import account* accepts.
- Solana: the 64-byte keypair, the seed followed by the ed25519 public key, in plain Base58 with no checksum, the form Phantom's *Import private key* accepts.

**Which wallet and account.** Keys follow the accounts the SDK signs with. The Ethereum key is the account behind `getWalletAddress()`, including a `walletAddress` override, and the Solana key is the account behind the Solana address. The phrase is the wallet holding that Ethereum account, else the first wallet, so the phrase and the Ethereum key always derive the same address. It restores every account derived from that wallet's seed and nothing else. A managed wallet keeps both accounts on one seed, so one phrase covers both. A bring-your-own organization with several wallets gets one wallet's phrase, and a key may come from another wallet. Before returning anything the SDK checks that the key is 32 bytes and that the address it derives, on either curve, is the account's. A `walletAddress` that names no Ethereum account of the organization fails every export before any export call, the Solana key included, because the provider it configures cannot sign either.

**Session.** Export is a Turnkey submit activity, so it needs a session that can submit; the one-time-code login produces one. Any live session suffices, including one the vendor restored from disk at launch: the SDK performs no re-authentication of its own, so a host that wants a fresh login or a biometric prompt before export adds it. A missing session, or one Turnkey refuses to submit with, a read-only bring-your-own session for example, fails the call; [Key export errors](#key-export-errors) has the mapping. In managed mode the first export of a launch runs the one-shot configuration first, like every auth call. Export is the backup path for a user who signs in with a passkey only.

**Timing and retries.** The vendor polls the export activity for up to about four seconds; an activity still pending after that fails the call ([Key export errors](#key-export-errors)). A transient failure (408, 429, 5xx, a dropped connection) is retried with a new request, so Turnkey's audit log may show more than one export activity for one call. Turnkey marks the wallet as exported after a phrase export, and its delete-wallet and delete-sub-organization activities key on that flag, so a phrase export changes what an organization can later delete without a force option.

**Host duties.** The SDK decrypts on the device and hands the value back once. It never logs, caches or persists it. After the return, gate the call, for example behind biometrics, show the value where screenshots and screen recording are blocked, and keep it off the clipboard or clear it. The sample app's *Export keys* card shows one way to do each. See [app/README.md](../app/README.md).

**What leaves the device.** The wallet id or the account address, the organization id and a fresh P-256 public key go to Turnkey's export endpoints; an enclave-signed bundle encrypted to that key comes back and is decrypted on the device. Nothing goes to Rain.

**Capability resolution.** `TurnkeyProvider` now advertises `Capability.EXPORT`, so `rain.first { Capability.EXPORT in it.capabilities }` returns the Turnkey client in a host that registered Turnkey before Portal or Privy, where it returned one of those before. `first` returns the earliest registered match. The client it returns has no export method; the methods live on the `TurnkeyProvider` the host registered.

**Checking an import.** Restore the phrase in MetaMask: the first Ethereum account equals `getWalletAddress()`. Restore it in Phantom: the Solana account equals the Solana address. Import the Ethereum key in MetaMask and the Solana key in Phantom: the same two addresses.

**Minified builds.** Export makes the vendor's `decryptExportBundle` reachable. Its Solana branch, which Rain never takes because it builds the Solana keypair itself, calls a helper in `com.turnkey:encoding` that links `org.bitcoinj.core.Base58`, a class bitcoinj 0.17.1 (the project's security floor) no longer ships. The adapter's published consumer rules carry `-dontwarn org.bitcoinj.core.Base58`, so a minified host build does not fail on the dangling reference. The branch is dead code on this classpath. The rule reaches the host's whole R8 configuration, so host code that still references `org.bitcoinj.core.Base58` itself, or calls the vendor's Solana export format directly, is silenced too and throws `NoClassDefFoundError` at runtime; move such code to `org.bitcoinj.base.Base58` or the SDK's export. The rule goes once Turnkey's encoding artifact targets `org.bitcoinj.base.Base58`.

## Accessing the Turnkey instance

Rain exposes no vendor getters (the old `RainSdk.turnkey` / `client.turnkey` are gone — core
references no concrete vendor type). In bring-your-own mode you already own the `TurnkeyContext`
you authenticated and passed to `TurnkeyConfig`. In managed mode the SDK configured that same
process-wide `TurnkeyContext` object; it is reachable because it is a public vendor type, but treat
it as read-only — creating, selecting or clearing sessions behind Rain's back is unsupported, and
the vendor configuration itself is one-shot per launch.

## Error handling

Turnkey-specific errors are mapped into the standard `RainError` hierarchy:

| Turnkey error | Mapped to |
|---------------|-----------|
| `TurnkeyKotlinError.InvalidSession` | `RainError.TokenExpired` |
| `TurnkeyKotlinError.FailedToVerifyOtp` carrying an auth-proxy HTTP 400 / 401 / 403 (managed mode) | `RainError.InvalidLoginCode` (`RAIN_203`) — the code was refused; 408 / 429 / 5xx are not a refused code and surface as `RainError.ProviderError` (`RAIN_501`); the auth path does not retry them. A rejection the proxy wraps in an HTTP 500 (real status only in the response body) is `RAIN_501` on Android, because the Kotlin SDK drops the body before Rain sees it, and `RAIN_203` on iOS; the challenge is still kept for a retry |
| `TurnkeyKotlinError.FailedToInitOtp`, any auth-proxy HTTP status (managed mode) | `RainError.ProviderError` (`RAIN_501`): the code request failed. No session exists while a code is requested, so a 401 or 403 here is not `TokenExpired` or `Unauthorized`. The reason (the channel not enabled on the proxy configuration, an undeliverable number, a rate limit) is in the response body the Kotlin SDK drops, so only the status reaches the message. Request the code again or check the configuration |
| Turnkey API HTTP 401 | `RainError.TokenExpired` |
| Turnkey API HTTP 403 | `RainError.Unauthorized` |
| `TurnkeyKotlinError.FailedToExportWallet`, and the checks the adapter runs around an export | [Key export errors](#key-export-errors) below: one table, in the order the checks run |
| Config / setup errors (`MissingRpId`, `MissingConfigParam`, `ClientNotInitialized`, `InvalidParameter`, `InvalidResponse`, `InvalidMessage`, `InvalidRefreshTTL`, `OAuthStateMismatch`, `KeyAlreadyExists`, `KeyNotFound`) | `RainError.InternalError` |
| Wrapper errors whose underlying cause is a user cancellation | `RainError.UserRejected` |
| Anything else | `RainError.ProviderError` |

The Turnkey Kotlin SDK throws a plain `RuntimeException` for HTTP failures and carries the status
only inside the message, so the adapter's `TurnkeyErrorMapping` parses it out. That is a workaround for a vendor gap —
it becomes a typed check once the SDK exposes the status code.

Network errors raised during direct RPC calls (balances, fee estimation) surface as `RainError.NetworkError`.

### Key export errors

The one statement of the export error contract. The KDoc of `exportRecoveryPhrase` and `exportPrivateKey` and their rows in [METHODS.md](METHODS.md#turnkey-key-export) point here instead of repeating it. Conditions are listed in the order the checks run, so the first that holds is the error a call gets; the session is checked when the wallet list has to be fetched and again at the export call itself.

| Condition | Mapped to |
|-----------|-----------|
| The provider was closed | `RainError.InvalidConfig` (`RAIN_102`), before anything else |
| No live session when the wallet list is fetched, or at the export call | `RainError.TokenExpired` (`RAIN_201`) |
| `walletAddress` names no Ethereum account of the organization: the phrase and both keys, before any export call | `RainError.InvalidConfig` (`RAIN_102`) |
| No wallet to export a phrase from, or no account of the requested chain family, before any export call | `RainError.WalletUnavailable` (`RAIN_404`). In managed mode, logging in again provisions the account |
| The account of the requested family sits on another curve than the family's, before any export call | `RainError.WalletUnavailable` (`RAIN_404`). Inconsistent vendor data; logging in again does not repair it |
| Turnkey answers the export with HTTP 401 | `RainError.TokenExpired` (`RAIN_201`) after one refresh attempt |
| Turnkey answers the export with HTTP 403, a read-only bring-your-own session for example | `RainError.Unauthorized` (`RAIN_202`). Which status Turnkey sends for a read-only session is not confirmed against the sandbox yet |
| Any other status, or a transient failure (408, 429, 5xx, a dropped connection) that still fails after the retries | `RainError.ProviderError` (`RAIN_501`): the same cause inspection as every other `FailedToExportWallet` wrapper |
| The export activity is still pending after the vendor's polling of about four seconds | `RainError.ProviderError` (`RAIN_501`) |
| The enclave's bundle names another account than the one requested (keys only) | `RainError.ProviderError` (`RAIN_501`) with a fixed message, before anything is decrypted |
| The export bundle is rejected on the device (signature, organization mismatch, malformed) | `RainError.ProviderError` (`RAIN_501`) naming the rejection kind only. The vendor's own message can carry the ephemeral decryption key, so the adapter drops it before anything is logged |
| The exported key fails a check: not 32 bytes, outside the curve order, or it does not derive the account's address (keys only) | `RainError.InternalError` (`RAIN_502`) with a fixed message. Nothing is returned |

## Session expiry, refresh, and retry

Turnkey sessions are short-lived JWTs (15 minutes by default) that die silently once expired.
Rain hardens every Turnkey-backed call against this, controlled by `TurnkeySessionPolicy`:

```kotlin
TurnkeyProvider(
    TurnkeyConfig(
        turnkey = turnkeyContext,
        sessionPolicy = TurnkeySessionPolicy(
            refreshBufferSeconds = 60,        // refresh when < 60s of lifetime remain
            autoRefresh = true,               // let Rain call Turnkey's refreshSession itself
            refreshExpirationSeconds = null,  // TTL for refreshed sessions (null = Turnkey default)
            maxTransientRetries = 2,          // backoff retries for 5xx/429/network on reads
            initialRetryDelayMs = 500,
            maxRetryDelayMs = 4_000,
        ),
        onSessionExpired = {
            // Re-auth hook: the session died and could not be refreshed. Fired once per
            // session death, on the calling coroutine's thread or the watcher's — hop to the
            // main thread before touching UI. Route the user back to login.
        },
    )
)
```

What every wallet call now does:

1. **Expiry check** — the session's JWT expiry is checked before the request. An
   already-expired session throws `RainError.TokenExpired` (or is refreshed first, see below)
   instead of burning a round-trip on a guaranteed 401.
2. **Proactive refresh** — with `autoRefresh` on (the default), a session expired or inside
   `refreshBufferSeconds` of expiry is refreshed through Turnkey's `refreshSession` before the
   call. Refreshes are single-flighted: concurrent calls share one refresh.
3. **Refresh-on-401** — a call rejected with HTTP 401 / `InvalidSession` is refreshed and
   retried exactly once. A 401 means Turnkey rejected the request before executing it, so this
   is safe for sends too. A second 401 surfaces as `RainError.TokenExpired`.
4. **Transient backoff** — idempotent reads (balances, history, transaction-status polls) and
   key export retry HTTP 5xx/429/408 and network I/O failures with exponential backoff; a retried
   export is a new activity with a fresh ephemeral key. Sends and signing are never retried on
   transient failures.
5. **Re-auth hook** — when the session dies for good (refresh failed, or Turnkey's own expiry
   timer cleared it while the app was idle), `onSessionExpired` fires once — even with no Rain
   call in flight, via a passive watcher over Turnkey's auth state.

With `autoRefresh = false` Rain never touches the session: expired sessions and 401s surface
as `RainError.TokenExpired` immediately and refresh/re-auth is entirely the host's job.

### Observing session state

`TurnkeyProvider` exposes the session as seen at the Rain boundary:

```kotlin
val provider = TurnkeyProvider(TurnkeyConfig(turnkeyContext))

provider.currentSessionState()  // Loading | Active(expiresAtEpochSeconds) | Expired | Unauthenticated

scope.launch {
    provider.sessionState.collect { state ->
        if (state is TurnkeySessionState.Expired || state is TurnkeySessionState.Unauthenticated) {
            // show re-login UI
        }
    }
}

provider.refreshSession()  // manual refresh; throws RainError.TokenExpired when it fails
```

`sessionState` emits on every Turnkey auth/session change and additionally re-checks when an
active session passes its expiry instant, so a silent death is observable without polling.

Resolving the provider always starts a passive watcher over the process-wide Turnkey singleton —
it evicts cached accounts when the session dies and fires `onSessionExpired` when one is set. A
host that rebuilds the SDK per login should call
`provider.close()` on the provider it is discarding so a stale watcher cannot fire.

Reference: the sample app's `RainSession.kt`, `WalletSessionStatus.kt` and the Home screen's
session card (`HomeScreen.kt`, `SessionCard`).

## Registering alongside Portal

The one pair that cannot share a builder is `TurnkeyProvider` and the Rain wallet's `RainProvider`
(`rain-wallet-android`): both drive the same process-wide `TurnkeyContext`, so `build()` throws
`RainError.InvalidConfig` when both are registered, and an app that configured one of them in a
launch cannot switch to the other without a relaunch.

Turnkey and Portal are no longer mutually exclusive. Register both adapters on the same builder and
resolve each to its own `RainClient` — one SDK instance, two independent provider-bound clients:

```kotlin
val rain = RainSdk.builder()
    .rpcEndpoints(endpoints)
    .register(PortalProvider(PortalConfig(portalSessionToken)))
    .register(TurnkeyProvider(TurnkeyConfig(turnkeyContext)))
    .build()

val portalClient = rain.provider(ProviderId.PORTAL)
val turnkeyClient = rain.provider(ProviderId.TURNKEY)
```

Each client is bound to its provider for its lifetime; there is no "active provider" to swap.

## Bouncy Castle dependency conflict (downstream consumers)

Turnkey (via `com.turnkey:crypto` and `com.turnkey:encoding`) depends on **`org.bouncycastle:bcprov-jdk15to18`**, and so does `rain-core-android`, which needs Bouncy Castle for web3j's Keccak hashing. Web3j 4.10 itself depends on **`org.bouncycastle:bcprov-jdk18on:1.73`**. Both artifacts publish overlapping `org.bouncycastle.*` class names, so dex-ing them together fails with errors like:

```
Duplicate class org.bouncycastle.asn1.pkcs.EncryptionScheme found in modules
  bcprov-jdk15to18-1.82.jar -> bcprov-jdk15to18-1.82 (org.bouncycastle:bcprov-jdk15to18:1.82)
  bcprov-jdk18on-1.73.jar  -> bcprov-jdk18on-1.73  (org.bouncycastle:bcprov-jdk18on:1.73)
```

The two artifacts are parallel builds of the same library for different JDK targets — their class APIs are interchangeable. Rain SDK standardizes on `bcprov-jdk15to18`, floored at the catalog's `bouncycastle` version (1.86 today) by a published constraint, and publishes the `bcprov-jdk18on` exclusion on every dependency edge that would otherwise pull it: web3j in core, Portal and Privy, and `privy-core` in the Privy module. The Turnkey adapter declares `bcprov-jdk15to18` itself as well, for the ed25519 derivation behind the exported Solana keypair. It is the same artifact at the same floor, so nothing new reaches a consumer's classpath, and the `bcprov-jdk18on` exclusion is unchanged.

**Gradle consumers** (resolve via Module Metadata): no action required as long as you reach the vendor SDKs only through Rain's modules. The exclusions above are part of the published metadata, and Gradle inherits them along each edge that declares them.

**If your build still hits the duplicate-class error** (older Gradle, Maven POM-only resolution, or you declare web3j, `io.privy:privy-core` or another dependency that pulls `bcprov-jdk18on` yourself), add the exclusion in your own module:

```kotlin
// build.gradle.kts
configurations.all {
    exclude(group = "org.bouncycastle", module = "bcprov-jdk18on")
}
```

Or, if you'd rather scope it to a specific dependency:

```kotlin
implementation("org.web3j:core:4.10.0") {
    exclude(group = "org.bouncycastle", module = "bcprov-jdk18on")
}
```

Groovy DSL equivalent:

```groovy
configurations.all {
    exclude group: 'org.bouncycastle', module: 'bcprov-jdk18on'
}
```

Maven POM equivalent (for non-Gradle consumers):

```xml
<dependency>
    <groupId>org.web3j</groupId>
    <artifactId>core</artifactId>
    <version>4.10.3</version>
    <exclusions>
        <exclusion>
            <groupId>org.bouncycastle</groupId>
            <artifactId>bcprov-jdk18on</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

If you have a legitimate need for `bcprov-jdk18on` (e.g. another library you control), swap the exclusion the other way and ensure all consumers compile against the same single BC artifact.
