package com.rain.sdk.turnkey

/**
 * Which of a Turnkey wallet's keys [TurnkeyProvider.exportPrivateKey] returns. A wallet holds one
 * account per chain family, each with its own key derived from the wallet's seed, so the recovery
 * phrase from [TurnkeyProvider.exportRecoveryPhrase] restores both. The output formats are
 * documented on [TurnkeyProvider.exportPrivateKey].
 */
enum class TurnkeyKeyFamily {
    /** The secp256k1 key behind the Ethereum account, returned as `0x` plus 64 lowercase hex characters. */
    ETHEREUM,

    /** The ed25519 key behind the Solana account, returned as the Base58 keypair string Solana wallets import. */
    SOLANA,
}
