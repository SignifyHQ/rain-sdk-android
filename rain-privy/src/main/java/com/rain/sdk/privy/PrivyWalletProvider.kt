package com.rain.sdk.privy

import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.provider.WalletProvider
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import com.rain.sdk.models.Balance
import com.rain.sdk.models.RainTransactionOrder
import com.rain.sdk.models.RainTransactionResult
import com.rain.sdk.models.Token
import com.rain.sdk.provider.Capability
import com.rain.sdk.provider.ProviderId
import com.rain.sdk.utils.EthereumConverter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Function as Web3jFunction
import org.web3j.abi.datatypes.generated.Uint256
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Privy-backed [WalletProvider].
 *
 * Splits responsibilities the way the SDK's other adapters do: custody (signing, broadcasting) goes
 * through Privy's EIP-1193 embedded wallet via [PrivyManager]; balance and fee reads run through
 * [PrivyRpcClient] against Rain's configured RPC, with metadata resolved by [tokenStore].
 *
 * Privy exposes no transaction-history endpoint, so [getTransactions] returns an empty result —
 * on-chain history is expected to come from the Rain backend, not the signer.
 */
internal class PrivyWalletProvider(
    private val manager: PrivyManager,
    private val rpcEndpoints: Map<Int, String>,
    private val tokenStore: TokenMetadataStore,
    private val walletAddressOverride: String? = null,
    private val rpcClient: PrivyRpcClient = PrivyRpcClient(),
) : WalletProvider {

    override val id: ProviderId get() = ProviderId.PRIVY

    /** Privy holds an exportable embedded key with a recovery flow. */
    override val capabilities: Set<Capability>
        get() = setOf(Capability.EXPORT, Capability.RECOVERY)

    @Volatile
    private var cachedAddress: String? = null

    // ---------- address ----------

    override suspend fun getWalletAddress(): String {
        walletAddressOverride?.takeIf { it.isNotEmpty() }?.let { return it }
        cachedAddress?.let { return it }
        return manager.getAddress(walletAddressOverride).also { cachedAddress = it }
    }

    // ---------- high-level send ----------

    override suspend fun sendNativeToken(
        chainId: Int,
        toAddress: String,
        amountInEth: BigDecimal,
    ): String {
        val from = getWalletAddress()
        val valueHex = EthereumConverter.convertEthToWeiHex(amountInEth)
        return sendTransaction(chainId = chainId, from = from, to = toAddress, data = "0x", value = valueHex)
    }

    override suspend fun sendToken(
        chainId: Int,
        contractAddress: String,
        toAddress: String,
        amount: BigDecimal,
        decimals: Int,
    ): String {
        val from = getWalletAddress()
        val tokenAmount = amount.multiply(BigDecimal.TEN.pow(decimals)).toBigInteger()
        val function = Web3jFunction(
            "transfer",
            listOf(Address(toAddress), Uint256(tokenAmount)),
            emptyList<TypeReference<*>>()
        )
        val data = FunctionEncoder.encode(function)
        return sendTransaction(chainId = chainId, from = from, to = contractAddress, data = data, value = "0x0")
    }

    // ---------- low-level send / sign / fee ----------

    override suspend fun sendTransaction(
        chainId: Int,
        from: String,
        to: String,
        data: String,
        value: String,
    ): String {
        val rpcUrl = rpcUrlFor(chainId)
        val transactionJson = JSONObject().apply {
            put("from", from)
            put("to", to)
            put("value", value.ifEmpty { "0x0" })
            if (data.isNotEmpty() && data != "0x") put("data", data)
            put("chainId", "0x${chainId.toString(16)}")
        }.toString()
        return manager.sendTransaction(walletAddress = from, rpcUrl = rpcUrl, transactionJson = transactionJson)
    }

    override suspend fun signTypedData(
        chainId: Int,
        walletAddress: String,
        typedDataJson: String,
    ): String = manager.signTypedData(walletAddress = walletAddress, typedDataJson = typedDataJson)

    override suspend fun estimateTransactionFee(
        chainId: Int,
        from: String,
        to: String,
        data: String,
        value: String,
    ): Double {
        val rpcUrl = rpcUrlFor(chainId)
        val gasLimitHex = rpcClient.callForHexResult(
            rpcUrl, "eth_estimateGas", listOf(rpcTransactionObject(from, to, data, value))
        )
        val gasPriceHex = rpcClient.callForHexResult(rpcUrl, "eth_gasPrice", emptyList())
        val gasLimit = EthereumConverter.parseHexToBigInteger(gasLimitHex)
        val gasPrice = EthereumConverter.parseHexToBigInteger(gasPriceHex)
        return EthereumConverter.convertWeiToEth(gasLimit.multiply(gasPrice))
    }

    // ---------- balances ----------

    override suspend fun getBalance(chainId: Int, token: Token): Balance {
        val walletAddress = getWalletAddress()
        return when (token) {
            is Token.Native -> fetchNativeBalance(chainId, walletAddress)
            is Token.Contract -> fetchContractBalance(chainId, walletAddress, token.address)
        }
    }

    override suspend fun getBalances(chainId: Int): List<Balance> = coroutineScope {
        val walletAddress = getWalletAddress()
        val nativeDeferred = async { fetchNativeBalance(chainId, walletAddress) }
        val contractDeferred = tokenStore.registeredTokens(chainId).map { info ->
            async { fetchContractBalance(chainId, walletAddress, info.address) }
        }
        val output = mutableListOf(nativeDeferred.await())
        output += contractDeferred.awaitAll().filter { it.rawAmount > BigInteger.ZERO }
        output
    }

    private suspend fun fetchNativeBalance(chainId: Int, walletAddress: String): Balance {
        val rpcUrl = rpcUrlFor(chainId)
        val hex = rpcClient.callForHexResult(rpcUrl, "eth_getBalance", listOf(walletAddress, "latest"))
        val native = tokenStore.nativeCurrency(chainId)
        return Balance(
            token = Token.Native,
            chainId = chainId,
            rawAmount = EthereumConverter.parseHexToBigInteger(hex),
            decimals = native.decimals,
            symbol = native.symbol,
            name = native.name
        )
    }

    private suspend fun fetchContractBalance(
        chainId: Int,
        walletAddress: String,
        address: String,
    ): Balance {
        val rpcUrl = rpcUrlFor(chainId)
        val info = tokenStore.tokenInfo(chainId, address)
        val function = Web3jFunction(
            "balanceOf",
            listOf(Address(walletAddress)),
            listOf(object : TypeReference<Uint256>() {})
        )
        val callObject = mapOf("to" to address, "data" to FunctionEncoder.encode(function))
        val hex = rpcClient.callForHexResult(rpcUrl, "eth_call", listOf(callObject, "latest"))
        return Balance(
            token = Token.Contract(address),
            chainId = chainId,
            rawAmount = EthereumConverter.parseHexToBigInteger(hex),
            decimals = info.decimals,
            symbol = info.symbol,
            name = info.name
        )
    }

    // ---------- transactions ----------

    /** Privy exposes no history endpoint; on-chain history comes from the Rain backend. */
    override suspend fun getTransactions(
        chainId: Int,
        limit: Int?,
        offset: Int?,
        order: RainTransactionOrder?,
    ): RainTransactionResult = RainTransactionResult(transactions = emptyList())

    // ---------- helpers ----------

    private fun rpcUrlFor(chainId: Int): String =
        rpcEndpoints[chainId]
            ?: throw RainError.InvalidConfig("No RPC endpoint configured for chainId=$chainId")

    private fun rpcTransactionObject(
        from: String,
        to: String,
        data: String,
        value: String,
    ): Map<String, String> {
        val tx = mutableMapOf("from" to from, "to" to to, "value" to value.ifEmpty { "0x0" })
        if (data.isNotEmpty() && data != "0x") tx["data"] = data
        return tx
    }
}
