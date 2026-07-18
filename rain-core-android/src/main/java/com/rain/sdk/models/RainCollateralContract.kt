package com.rain.sdk.models

import java.math.BigDecimal

/**
 * A collateral contract returned by `GET /v1/issuing/users/{userId}/contracts`.
 *
 * @property id Rain's identifier for the contract, when present.
 * @property chainId EIP-155 chain ID the contract is deployed on.
 * @property proxyAddress The collateral proxy contract address (the address funds are held at).
 * @property controllerAddress The collateral controller contract address.
 * @property depositAddress Optional dedicated deposit address, when Rain provides one.
 * @property adminAddresses Admin addresses of the collateral contract; one of these is passed to
 *   `fetchAdminSignature`.
 * @property contractVersion Rain's contract version, when present.
 * @property tokens Collateral tokens held by the contract.
 */
data class RainCollateralContract(
    val id: String?,
    val chainId: Int,
    val proxyAddress: String,
    val controllerAddress: String,
    val depositAddress: String?,
    val adminAddresses: List<String>,
    val contractVersion: Int?,
    val tokens: List<RainCollateralToken>,
)

/**
 * A collateral token entry within a [RainCollateralContract].
 *
 * The contracts endpoint returns only `address` / `balance` / `exchangeRate` / `advanceRate`;
 * the SDK enriches [name] / [symbol] / [decimals] from its token store (registry,
 * host-registered tokens, or an on-chain read). Enrichment is best-effort — a failed lookup
 * leaves those fields `null` rather than failing the fetch.
 *
 * @property balance Raw balance string exactly as returned by the Rain API (e.g. "0.0").
 */
data class RainCollateralToken(
    val address: String,
    val balance: String,
    val exchangeRate: Double,
    val advanceRate: Double,
    val name: String? = null,
    val symbol: String? = null,
    val decimals: Int? = null,
) {
    /** [balance] as a decimal, or `null` when it doesn't parse. */
    val balanceAmount: BigDecimal? get() = balance.toBigDecimalOrNull()
}
