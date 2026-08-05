package com.rain.sdk.models

/**
 * Result of an ERC-20 approval submitted by
 * [com.rain.sdk.interfaces.RainClient.approveTokenAllowance].
 *
 * The wrapper exists so the public surface stays forward-compatible — future versions can attach
 * richer metadata (status, included block, fee paid) without breaking the call shape.
 *
 * @property transactionHash The on-chain transaction hash of the `approve` call. The approval is
 *   only in effect once that transaction is mined; read the allowance back with
 *   [com.rain.sdk.interfaces.RainClient.getTokenAllowance] to confirm.
 */
data class RainTokenApprovalResult(
    val transactionHash: String
)
