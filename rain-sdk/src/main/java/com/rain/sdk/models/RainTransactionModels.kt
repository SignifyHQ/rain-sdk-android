package com.rain.sdk.models

/**
 * Defines the sorting order for transaction history.
 */
enum class RainTransactionOrder(val value: String) {
    ASC("asc"),
    DESC("desc")
}

/**
 * Represents a single transaction in the transaction history.
 * This is a Rain SDK wrapper around the underlying provider's transaction model.
 *
 * @property hash The transaction hash.
 * @property blockNumber The block number in which the transaction was included.
 * @property blockTimestamp The ISO-8601 timestamp of the block.
 * @property from The sender's address.
 * @property to The recipient's address.
 * @property value The amount of native token transferred (as a string to preserve precision).
 * @property gas The amount of gas used.
 * @property gasPrice The price of gas.
 * @property chainId The chain identifier (e.g., CAIP-2 format).
 * @property metadata Additional unstructured metadata, if any.
 */
data class RainTransaction(
    val hash: String,
    val blockNumber: String? = null,
    val blockTimestamp: String? = null,
    val from: String,
    val to: String? = null,
    val value: String? = null,
    val gas: String? = null,
    val gasPrice: String? = null,
    val chainId: String? = null,
    val metadata: Map<String, Any?>? = null
)

/**
 * Represents the result of querying transaction history.
 *
 * @property transactions The list of transactions.
 */
data class RainTransactionResult(
    val transactions: List<RainTransaction>
)
