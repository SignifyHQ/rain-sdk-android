package com.rain.sdk.turnkey

/**
 * Which of a Turnkey wallet's keys [TurnkeyProvider.exportPrivateKey] returns. The SDK uses one
 * account per family: the first the organization lists, or for Ethereum the account at
 * [TurnkeyConfig.walletAddress]. Each key derives from its wallet's seed, so the recovery phrase
 * from [TurnkeyProvider.exportRecoveryPhrase] restores both when they share a wallet. Families may
 * be added as Turnkey adds curves, so prefer an `else` branch over an exhaustive `when`. The output
 * formats are documented on [TurnkeyProvider.exportPrivateKey].
 */
enum class TurnkeyKeyFamily {
    /** The secp256k1 key behind the Ethereum account, returned as `0x` plus 64 lowercase hex characters. */
    ETHEREUM,

    /** The ed25519 key behind the Solana account, returned as the Base58 keypair string Solana wallets import. */
    SOLANA,
}
