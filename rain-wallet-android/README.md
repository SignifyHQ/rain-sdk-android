# rain-wallet-android

The Rain wallet: the provider a partner registers to give users a wallet under Rain's own name.
The SDK owns authentication (a one-time code by email or SMS, or a passkey bound to a domain you
control), wallet provisioning (one wallet holding an Ethereum and a Solana account, created on
first login), sessions and key export, and
its public surface names no wallet vendor. The wallet backend's identity is embedded, so an app
configures nothing but behaviour.

```kotlin
dependencies {
    // Pulls rain-core-android transitively; the wallet backend ships with it.
    implementation("io.github.spartan-quanhongtran:rain-wallet-android:1.0.1")
}
```

```kotlin
import com.rain.sdk.RainSdk
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.wallet.RainProvider
import com.rain.sdk.wallet.RainWalletConfig
import com.rain.sdk.wallet.RainWalletContact

// passkeyDomain is optional; without it the passkey methods throw RainError.InvalidConfig
val wallet = RainProvider(application, RainWalletConfig(passkeyDomain = "passkeys.example.com"))

wallet.awaitSessionRestore()
if (!wallet.hasActiveSession()) {
    wallet.sendLoginCode(RainWalletContact.Email("user@example.com"))
    // or RainWalletContact.Phone("+15551234567") for a code by SMS
    wallet.confirmLoginCode(code) // sign-up or login, plus wallet provisioning
    // or a passkey through the system sheet, presented from the foreground Activity:
    // wallet.loginWithPasskey(activity), or wallet.signUpWithPasskey(activity) for a new account
}

val rain = RainSdk.builder()
    .rpcEndpoints(mapOf(8453 to "https://mainnet.base.org"))
    .register(wallet)
    .build()
val client = rain.provider(ProviderId.RAIN)
```

`RainWalletConfig` tunes behaviour: the session policy (`RainWalletSessionPolicy`), the expiry
hook `onSessionExpired`, whether sends are fee-sponsored (`sponsorGas`, on by default) and the
passkey domain (`passkeyDomain`, off by default). The session is observable through `sessionState` and
`currentSessionState()` (`RainWalletSessionState`) and through `authState` and `currentAuthState()`
(`RainWalletAuthState`); `refreshSession()` forces a refresh, `logout()` clears the stored session,
and `close()` stops the passive session watcher when a provider is discarded.
`exportRecoveryPhrase()` returns the wallet's 12-word phrase and `exportPrivateKey(account)` one
private key (`RainWalletKeyAccount.ETHEREUM` or `SOLANA`), both decrypted on the device; the
formats are a cross-platform contract shared by Rain's SDKs. Every member, its error codes and the
host's duties after an export are in [docs/METHODS.md](../docs/METHODS.md#rain-wallet-provider).

Passkeys. With `passkeyDomain` set, `signUpWithPasskey(activity)` creates a new account whose only
login is a passkey, with the same wallet a code sign-up gets, `loginWithPasskey(activity)` signs an
existing one in, and `addPasskey(activity)` registers a passkey on a code-created account. The
domain is one you control, at least two labels of letters, digits and hyphens, serving
`https://<domain>/.well-known/assetlinks.json` in the shape of Google's passkey example: a
statement for the site itself with `get_login_creds`, and an app statement with both
`handle_all_urls` and `get_login_creds` that lists the package name and the SHA-256 fingerprint of
every certificate that signs it (debug, upload and Play App Signing). The device refuses a file that
carries the app statement alone; the sample's file at `passkeys.uptop.xyz` is a working copy, and
[docs/TURNKEY_SUPPORT.md](../docs/TURNKEY_SUPPORT.md#passkeys) shows the shape. It is permanent,
because every passkey created against it stops working when it changes, and a passkey made for one
domain does not work in an app on another. Partners host
their own file; Rain runs no shared domain. Every call to `signUpWithPasskey` creates a new account,
so returning users sign in or add a passkey, and accounts are never merged. A passkey-only account
gets an email or phone as a second login through `sendContactVerificationCode(contact)` and
`confirmContactVerification(code)`, which attach the verified contact to this account and never
move wallets; `exportRecoveryPhrase()` is its backup path either way.

Notes:

- The wallet backend's configuration is one-shot per app launch; the first authentication call
  applies it, `passkeyDomain` included, so a second provider with a different domain in the same
  launch makes every authentication call throw `RainError.InvalidConfig` until the app relaunches.
- The embedded backend identity is Rain's sandbox wallet backend; this release has no host-facing
  environment switch.
- What leaves the device: the email address or phone number passed to `sendLoginCode` or
  `sendContactVerificationCode`, sent to the wallet backend to deliver the code and key the account
  (on confirm of an attach, the verified contact and its verification token go to the backend's user
  update); on a passkey sign-up the passkey's attestation, the passkey name `passkey-<unix seconds>`
  and a temporary session key, on a passkey sign-in the passkey's assertion, on `addPasskey` a new
  passkey's attestation. The session stays on the device in storage the backend SDK owns; wallet
  keys live in the wallet backend and reach the device only through the export methods. This module
  sends nothing else.
- The Rain wallet cannot be registered together with the provider from `rain-turnkey-android` in
  one `RainSdk`: `build()` refuses the pair, because both drive one process-wide wallet backend.
- Capabilities: `EXPORT`, `MULTI_CHAIN`, plus `GAS_SPONSORSHIP` while `sponsorGas` is on. Signing is
  not gated behind a biometric prompt, so gate export yourself. No capability advertises passkeys;
  `hasActiveSession()` and `authState` answer the same for a code or a passkey login.
- Attaching a contact adds a permanent login route with no user-presence check beyond a live session,
  which can be one restored at launch, so gate `sendContactVerificationCode` and
  `confirmContactVerification` as you gate export, and show the user which contacts sign in to the
  account. The passkey requests ask the credential provider for user verification as preferred, not
  required, so gate `addPasskey` the same way.
- Minified builds inherit one rule from the wallet backend's adapter: `-dontwarn
  org.bitcoinj.core.Base58`, for a class the backend references on its export path and never loads.
  It lands in your whole R8 configuration, so a reference of your own to that class stops warning.
- Sends work on the chains the wallet backend broadcasts to; other configured chains are read-only,
  and a send there throws `RainError.ChainNotSupported` (`RAIN_104`) before any network work.
- `RainWalletSessionState` and `RainWalletAuthState` are open by design: each carries one internal
  case, so a `when` over them needs an `else` branch and a state can be added later without a
  source break.
- Every public signature of this module stays free of wallet-backend types, and the backend adapter
  is an `implementation` dependency, so your compile classpath never sees it.
- The module embeds Rain's sandbox identity, so `checkNotSandboxIdentity` refuses every publish task
  except `publishToMavenLocal` until a production identity lands.
