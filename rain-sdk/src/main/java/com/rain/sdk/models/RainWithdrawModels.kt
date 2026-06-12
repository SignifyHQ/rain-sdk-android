package com.rain.sdk.models

import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.utils.RainHexUtils

/**
 * Groups all addresses required for a withdrawal operation.
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
 * Groups the admin signature and its associated metadata.
 * 
 * @property salt The salt used for the admin signature.
 * @property signature The hex string of the admin signature.
 * @property expiresAt The expiration timestamp (as a String, usually ISO-8601 or unix timestamp).
 */
data class RainAdminSignature(
    val salt: String,
    val signature: String,
    val expiresAt: String
)
