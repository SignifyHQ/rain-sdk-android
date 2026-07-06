package com.rain.sdk.privy

import com.rain.sdk.internal.provider.WalletProvider
import com.rain.sdk.models.Balance
import com.rain.sdk.models.RainTransactionOrder
import com.rain.sdk.models.RainTransactionResult
import com.rain.sdk.models.Token
import com.rain.sdk.provider.Capability
import com.rain.sdk.provider.ProviderId
import java.math.BigDecimal

/**
 * Privy [WalletProvider] — **skeleton**.
 *
 * Implements the port so the module compiles and registers, but every operation throws
 * [NotImplementedError] until the Privy embedded-wallet SDK is integrated. This is the single
 * file that will own all Privy-specific signing once the adapter is built.
 */
internal class PrivyWalletProvider : WalletProvider {

    override val id: ProviderId get() = ProviderId.PRIVY

    override val capabilities: Set<Capability>
        get() = setOf(Capability.EXPORT, Capability.RECOVERY)

    override suspend fun getWalletAddress(): String = notImplemented()

    override suspend fun sendNativeToken(
        chainId: Int,
        toAddress: String,
        amountInEth: BigDecimal,
    ): String = notImplemented()

    override suspend fun sendToken(
        chainId: Int,
        contractAddress: String,
        toAddress: String,
        amount: BigDecimal,
        decimals: Int,
    ): String = notImplemented()

    override suspend fun getBalance(chainId: Int, token: Token): Balance = notImplemented()

    override suspend fun getBalances(chainId: Int): List<Balance> = notImplemented()

    override suspend fun getTransactions(
        chainId: Int,
        limit: Int?,
        offset: Int?,
        order: RainTransactionOrder?,
    ): RainTransactionResult = notImplemented()

    override suspend fun signTypedData(
        chainId: Int,
        walletAddress: String,
        typedDataJson: String,
    ): String = notImplemented()

    override suspend fun sendTransaction(
        chainId: Int,
        from: String,
        to: String,
        data: String,
        value: String,
    ): String = notImplemented()

    override suspend fun estimateTransactionFee(
        chainId: Int,
        from: String,
        to: String,
        data: String,
        value: String,
    ): Double = notImplemented()

    private fun notImplemented(): Nothing =
        throw NotImplementedError("rain-privy is a skeleton; the Privy adapter is not implemented yet")
}
