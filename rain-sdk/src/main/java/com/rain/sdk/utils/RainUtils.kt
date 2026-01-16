package com.rain.sdk.utils

object RainUtils {
  /**
   * Build EIP-712 payload for Withdrawal.
   * Returns a JSON string ready to be signed.
   */
  fun buildWithdrawPayload(
    tokenAddress: String,
    decimals: Int,
    amount: Double,
    adminSignature: String
  ): String {
    // TODO: Implement logic from BuildETHTransactionParamForWithdrawAssetUseCase
    return "{}"
  }
}
