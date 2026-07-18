package com.rain.sdk.internal.utils

import java.math.BigInteger

internal object RainEip712Utils {
    fun createEIP712Json(
        chainId: Long,
        verifyingContract: String,
        saltHex: String,
        walletAddress: String,
        tokenAddress: String,
        recipientAddress: String,
        amount: BigInteger,
        nonce: BigInteger
    ): String {
        return """
        {
          "domain": {
            "salt": "$saltHex",
            "chainId": $chainId,
            "verifyingContract": "$verifyingContract",
            "name": "Collateral",
            "version": "2"
          },
          "primaryType": "Withdraw",
          "message": {
            "recipient": "$recipientAddress",
            "nonce": "${nonce.toString(10)}",
            "amount": "${amount.toString(10)}",
            "user": "$walletAddress",
            "asset": "$tokenAddress"
          },
          "types": {
            "Withdraw": [
              {"type": "address", "name": "user"},
              {"name": "asset", "type": "address"},
              {"type": "uint256", "name": "amount"},
              {"name": "recipient", "type": "address"},
              {"name": "nonce", "type": "uint256"}
            ],
            "EIP712Domain": [
              {"type": "string", "name": "name"},
              {"type": "string", "name": "version"},
              {"type": "uint256", "name": "chainId"},
              {"name": "verifyingContract", "type": "address"},
              {"name": "salt", "type": "bytes32"}
            ]
          }
        }
        """.trimIndent()
    }
}
