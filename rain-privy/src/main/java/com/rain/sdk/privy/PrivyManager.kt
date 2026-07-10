package com.rain.sdk.privy

import com.rain.sdk.internal.error.RainError
import io.privy.sdk.Privy
import io.privy.wallet.ethereum.EmbeddedEthereumWallet
import io.privy.wallet.ethereum.EthereumChain
import io.privy.wallet.ethereum.EthereumRpcRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Wrapper around the Privy SDK encapsulating all Privy interactions — the custody seam.
 *
 * Privy owns signing and broadcasting via its EIP-1193 embedded-wallet provider; balance/fee reads
 * run through [PrivyRpcClient] instead. Authentication and `Privy.init` happen in the host app
 * (mirroring Turnkey), so this only ever consumes an already-authenticated [Privy] singleton.
 */
internal class PrivyManager(
    private val privy: Privy,
) {

    /**
     * Serializes [sendTransaction]. Privy's EIP-1193 provider is stateful: [sendTransaction] points
     * it at a chain via `switchChain` and then broadcasts, so two concurrent sends to different
     * chains could interleave the switch of one with the request of the other. Signing takes no
     * chain state, so only sends are guarded.
     */
    private val sendMutex = Mutex()

    /**
     * Resolves the embedded Ethereum wallet to sign with. Uses [addressOverride] when supplied,
     * otherwise the user's first embedded Ethereum wallet.
     */
    suspend fun resolveWallet(addressOverride: String?): EmbeddedEthereumWallet {
        val user = privy.getUser() ?: throw RainError.TokenExpired()
        val wallets = user.embeddedEthereumWallets
        if (wallets.isEmpty()) {
            throw RainError.WalletUnavailable(
                "Privy user has no embedded Ethereum wallet; call createEthereumWallet() first"
            )
        }
        return if (addressOverride.isNullOrEmpty()) {
            wallets.first()
        } else {
            wallets.firstOrNull { it.address.equals(addressOverride, ignoreCase = true) }
                ?: throw RainError.WalletUnavailable("No Privy wallet matches address $addressOverride")
        }
    }

    /** The signing wallet's address. */
    suspend fun getAddress(addressOverride: String?): String =
        resolveWallet(addressOverride).address

    /**
     * Signs EIP-712 typed data via `eth_signTypedData_v4`. Returns the 0x-prefixed signature.
     */
    suspend fun signTypedData(
        walletAddress: String,
        typedDataJson: String,
    ): String {
        val wallet = resolveWallet(walletAddress)
        return request(wallet, EthereumRpcRequest.ethSignTypedDataV4(wallet.address, typedDataJson))
    }

    /**
     * Broadcasts a transaction via `eth_sendTransaction`, returning the tx hash. Points Privy at
     * Rain's configured RPC for [chainId] via [EthereumChain.Custom] first; the wallet fills any
     * omitted gas/nonce. [transactionJson] carries from/to/data/value/chainId.
     */
    suspend fun sendTransaction(
        walletAddress: String,
        rpcUrl: String,
        transactionJson: String,
    ): String {
        val wallet = resolveWallet(walletAddress)
        return sendMutex.withLock {
            wallet.provider.switchChain(EthereumChain.Custom(rpcUrl))
            request(wallet, EthereumRpcRequest.ethSendTransaction(transactionJson))
        }
    }

    /**
     * Issues an RPC request through the wallet's provider, unwrapping the [Result] and data.
     *
     * Failures bubble up raw (only logged here) rather than being wrapped in [RainError]. Core's
     * `TransactionExecutor` / `TransactionSigner` short-circuit on any `RainError` before their
     * `ErrorMapper` runs, so pre-wrapping would hide user-rejection / insufficient-funds behind a
     * generic `ProviderError`. Letting the raw Privy error through lets the mapper classify it —
     * matching how the Portal and Turnkey adapters surface signing/broadcast failures.
     */
    private suspend fun request(
        wallet: EmbeddedEthereumWallet,
        rpcRequest: EthereumRpcRequest,
    ): String {
        val result = try {
            wallet.provider.request(rpcRequest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Rain SDK: Privy RPC request failed for ${rpcRequest.method}")
            throw e
        }
        return result.getOrElse { error ->
            Timber.e(error, "Rain SDK: Privy RPC request failed for ${rpcRequest.method}")
            throw error
        }.data
    }
}
