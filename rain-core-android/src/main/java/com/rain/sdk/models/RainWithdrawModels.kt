package com.rain.sdk.models

import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.utils.RainHexUtils

/**
 * The addresses a withdrawal needs. Three come from the user's collateral contract as the Rain API
 * returns it (`GET /v1/issuing/users/{userId}/contracts`: `proxyAddress`, `controllerAddress` and
 * the token's `tokens[].address`); the recipient is the host's choice.
 *
 * @property proxyAddress The address of the collateral proxy contract.
 * @property controllerAddress The address of the collateral controller contract.
 * @property tokenAddress The address of the token being withdrawn.
 * @property recipientAddress The address receiving the tokens.
 */
data class RainWithdrawAddresses(
    val proxyAddress: String,
    val controllerAddress: String,
    val tokenAddress: String,
    val recipientAddress: String
) {
    /**
     * Returns a new instance with checksummed addresses.
     * Throws [RainError.InvalidConfig] if any address is invalid.
     */
    fun validated(): RainWithdrawAddresses {
        return RainWithdrawAddresses(
            proxyAddress = RainHexUtils.validateAndChecksum(proxyAddress, "proxyAddress"),
            controllerAddress = RainHexUtils.validateAndChecksum(controllerAddress, "controllerAddress"),
            tokenAddress = RainHexUtils.validateAndChecksum(tokenAddress, "tokenAddress"),
            recipientAddress = RainHexUtils.validateAndChecksum(recipientAddress, "recipientAddress")
        )
    }
}

/**
 * Rain's authorization for one withdrawal, as the Rain API returns it
 * (`GET /v1/issuing/users/{userId}/signatures/withdrawals`): `signature.salt`, `signature.data`
 * and the top-level `expiresAt`, passed through unchanged. The host fetches it from its backend;
 * the SDK only consumes it.
 *
 * @property salt Base64 of the 32-byte salt Rain signed with, on every chain.
 * @property signature The admin signature: on EVM chains 0x-prefixed hex of 65 bytes, on Solana
 *   base64 of the 64-byte ed25519 signature.
 * @property expiresAt When the authorization lapses: unix seconds, or an ISO-8601 instant such as
 *   "2030-12-31T23:59:59Z".
 */
data class RainAdminSignature(
    val salt: String,
    val signature: String,
    val expiresAt: String
)
