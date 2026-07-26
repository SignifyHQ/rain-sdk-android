package com.rain.sdk.internal.abi

import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.utils.RainAmountUtils
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Function as Web3jFunction
import org.web3j.abi.datatypes.generated.Uint256
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Canonical ABI encoding for the ERC-20 write calls the SDK sends. Calldata is
 * provider-independent, so it is encoded once here; each wallet adapter broadcasts
 * the bytes through its own `sendTransaction` path.
 *
 * Public (not Kotlin-`internal`) because the out-of-core adapter modules
 * (`rain-portal-android`, `rain-privy-android`) encode calldata too, mirroring [com.rain.sdk.utils.EthereumConverter].
 */
object Erc20Abi {

    /** Encodes `transfer(address,uint256)` calldata for [tokenAmount] base units. */
    fun encodeTransfer(toAddress: String, tokenAmount: BigInteger): String {
        val function = Web3jFunction(
            "transfer",
            listOf(Address(toAddress), Uint256(tokenAmount)),
            emptyList<TypeReference<*>>()
        )
        return FunctionEncoder.encode(function)
    }

    /**
     * Encodes `transfer(address,uint256)` calldata for a decimal [amount] of a token with
     * [decimals]. An amount finer than the token can represent is rejected rather than truncated.
     *
     * @throws RainError.InvalidAmount if [amount] carries more decimal places than [decimals].
     */
    @Throws(RainError::class)
    fun encodeTransfer(toAddress: String, amount: BigDecimal, decimals: Int): String =
        encodeTransfer(toAddress, RainAmountUtils.toBaseUnits(amount, decimals))
}
