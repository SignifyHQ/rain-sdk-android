# rain-wallet-android

The Rain wallet: the provider a partner registers to give users a wallet under Rain's own name.
The SDK owns authentication (a one-time code by email or SMS), wallet provisioning (one wallet
holding an Ethereum and a Solana account, created on first login), sessions and key export, and
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
import com.rain.sdk.wallet.RainWalletContact

val wallet = RainProvider(application)

wallet.awaitSessionRestore()
if (!wallet.hasActiveSession()) {
    wallet.sendLoginCode(RainWalletContact.Email("user@example.com"))
    // or RainWalletContact.Sms("+15551234567") for a code by SMS
    wallet.confirmLoginCode(code) // sign-up or login, plus wallet provisioning
}

val rain = RainSdk.builder()
    .rpcEndpoints(mapOf(8453 to "https://mainnet.base.org"))
    .register(wallet)
    .build()
val client = rain.provider(ProviderId.RAIN)
```

`RainWalletConfig` tunes behaviour: an EVM address override, the session policy
(`RainWalletSessionPolicy`), the expiry hook `onSessionExpired`, and whether sends are
fee-sponsored (`sponsorGas`, on by default). The session is observable through `sessionState` and
`currentSessionState()` (`RainWalletSessionState`) and through `authState` and `currentAuthState()`
(`RainWalletAuthState`); `refreshSession()` forces a refresh, `logout()` clears the stored session,
and `close()` stops the passive session watcher when a provider is discarded.
`exportRecoveryPhrase()` returns the wallet's 12-word phrase and `exportPrivateKey(account)` one
private key (`RainWalletKeyAccount.ETHEREUM` or `SOLANA`), both decrypted on the device; the
formats are a cross-platform contract shared by Rain's SDKs. Every member, its error codes and the
host's duties after an export are in [docs/METHODS.md](../docs/METHODS.md#rain-wallet-provider).

Notes:

- The wallet backend's configuration is one-shot per app launch; the first authentication call
  applies it.
- The embedded backend identity is Rain's sandbox wallet backend; this release has no host-facing
  environment switch.
- What leaves the device: the email address or phone number passed to `sendLoginCode`, sent to the
  wallet backend to deliver the code and key the account. The session stays on the device in
  storage the backend SDK owns; wallet keys live in the wallet backend and reach the device only
  through the export methods. This module sends nothing else.
- The Rain wallet cannot be registered together with the provider from `rain-turnkey-android` in
  one `RainSdk`: `build()` refuses the pair, because both drive one process-wide wallet backend.
- Capabilities: `EXPORT`, `MULTI_CHAIN`, `BIOMETRIC_GATE`, plus `GAS_SPONSORSHIP` while
  `sponsorGas` is on.
- Sends work on the chains the wallet backend broadcasts to; other configured chains are read-only,
  and a send there throws `RainError.ChainNotSupported` (`RAIN_105`) before any network work.
- Every public signature of this module stays free of wallet-backend types. The adapter is an
  `implementation` dependency, so a leaked type would break a host's compile, not this module's.
