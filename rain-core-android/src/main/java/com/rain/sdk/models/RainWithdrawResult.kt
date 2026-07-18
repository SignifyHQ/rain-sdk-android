package com.rain.sdk.models

/**
 * Result of a withdraw collateral operation.
 * Can contain either a transaction hash (if auto-sent) or transaction data (if not auto-sent).
 *
 * @property transactionHash The transaction hash, present if transaction was auto-sent
 * @property transactionData The prepared transaction data, present if transaction was not auto-sent
 */
data class RainWithdrawResult(
    val transactionHash: String? = null,
    val transactionData: String? = null
) {
    /**
     * Checks if this result contains a transaction hash (transaction was auto-sent).
     */
    val isAutoSent: Boolean
        get() = transactionHash != null

    /**
     * Checks if this result contains transaction data (transaction was not auto-sent).
     */
    val isTransactionData: Boolean
        get() = transactionData != null
}
