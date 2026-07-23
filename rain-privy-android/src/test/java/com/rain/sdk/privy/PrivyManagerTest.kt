package com.rain.sdk.privy

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.error.RainError
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.privy.auth.PrivyUser
import io.privy.sdk.Privy
import io.privy.wallet.ethereum.EmbeddedEthereumWallet
import io.privy.wallet.ethereum.EmbeddedEthereumWalletProvider
import io.privy.wallet.ethereum.EthereumChain
import io.privy.wallet.ethereum.EthereumRpcResponse
import io.privy.wallet.transactions.GetTransactionsParams
import io.privy.wallet.transactions.TransactionChain
import io.privy.wallet.transactions.TransactionsPage
import kotlinx.coroutines.runBlocking
import org.junit.Test

class PrivyManagerTest {

    private fun wallet(address: String, provider: EmbeddedEthereumWalletProvider): EmbeddedEthereumWallet =
        mockk<EmbeddedEthereumWallet>().also {
            every { it.address } returns address
            every { it.provider } returns provider
        }

    private fun privyWith(wallets: List<EmbeddedEthereumWallet>?): Privy {
        val privy = mockk<Privy>()
        if (wallets == null) {
            coEvery { privy.getUser() } returns null
        } else {
            val user = mockk<PrivyUser>()
            every { user.embeddedEthereumWallets } returns wallets
            coEvery { privy.getUser() } returns user
        }
        return privy
    }

    @Test
    fun `resolveWallet throws TokenExpired when no authenticated user`() = runBlocking {
        val manager = PrivyManager(privyWith(null))
        val error = runCatching { manager.getAddress(null) }.exceptionOrNull()
        assertThat(error).isInstanceOf(RainError.TokenExpired::class.java)
    }

    @Test
    fun `resolveWallet throws WalletUnavailable when user has no embedded wallet`() = runBlocking {
        val manager = PrivyManager(privyWith(emptyList()))
        val error = runCatching { manager.getAddress(null) }.exceptionOrNull()
        assertThat(error).isInstanceOf(RainError.WalletUnavailable::class.java)
    }

    @Test
    fun `resolveWallet matches the override case-insensitively`() = runBlocking {
        val provider = mockk<EmbeddedEthereumWalletProvider>()
        val manager = PrivyManager(privyWith(listOf(wallet(WALLET, provider))))
        // Override in a different case than the wallet's stored address still resolves.
        assertThat(manager.getAddress(WALLET.lowercase())).isEqualTo(WALLET)
    }

    @Test
    fun `resolveWallet throws WalletUnavailable when the override matches no wallet`() = runBlocking {
        val provider = mockk<EmbeddedEthereumWalletProvider>()
        val manager = PrivyManager(privyWith(listOf(wallet(WALLET, provider))))
        val error = runCatching { manager.getAddress("0xNOPE") }.exceptionOrNull()
        assertThat(error).isInstanceOf(RainError.WalletUnavailable::class.java)
    }

    @Test
    fun `resolveWallet falls back to the first wallet when no override is given`() = runBlocking {
        val provider = mockk<EmbeddedEthereumWalletProvider>()
        val first = wallet(WALLET, provider)
        val second = wallet("0xSECOND", provider)
        val manager = PrivyManager(privyWith(listOf(first, second)))
        assertThat(manager.getAddress(null)).isEqualTo(WALLET)
    }

    @Test
    fun `signTypedData returns the provider signature`() = runBlocking {
        val provider = mockk<EmbeddedEthereumWalletProvider>()
        coEvery { provider.request(any()) } returns
            Result.success(EthereumRpcResponse(method = "eth_signTypedData_v4", data = "0xSIG"))
        val manager = PrivyManager(privyWith(listOf(wallet(WALLET, provider))))

        assertThat(manager.signTypedData(WALLET, "{}")).isEqualTo("0xSIG")
    }

    @Test
    fun `sendTransaction switches to the custom RPC chain then broadcasts`() = runBlocking {
        val provider = mockk<EmbeddedEthereumWalletProvider>()
        every { provider.switchChain(any()) } just Runs
        coEvery { provider.request(any()) } returns
            Result.success(EthereumRpcResponse(method = "eth_sendTransaction", data = "0xHASH"))
        val manager = PrivyManager(privyWith(listOf(wallet(WALLET, provider))))

        val hash = manager.sendTransaction(walletAddress = WALLET, rpcUrl = RPC, transactionJson = "{}")

        assertThat(hash).isEqualTo("0xHASH")
        coVerify(exactly = 1) { provider.switchChain(EthereumChain.Custom(RPC)) }
    }

    @Test
    fun `request bubbles the raw provider failure rather than wrapping it`() = runBlocking {
        val provider = mockk<EmbeddedEthereumWalletProvider>()
        every { provider.switchChain(any()) } just Runs
        val raw = IllegalStateException("user rejected the request")
        coEvery { provider.request(any()) } returns Result.failure(raw)
        val manager = PrivyManager(privyWith(listOf(wallet(WALLET, provider))))

        val error = runCatching {
            manager.sendTransaction(walletAddress = WALLET, rpcUrl = RPC, transactionJson = "{}")
        }.exceptionOrNull()

        // Must NOT be pre-wrapped in RainError — core's ErrorMapper needs the raw error to
        // classify user-rejection / insufficient-funds.
        assertThat(error).isSameInstanceAs(raw)
        assertThat(error).isNotInstanceOf(RainError::class.java)
    }

    @Test
    fun `getTransactions unwraps the page from the resolved wallet`() = runBlocking {
        val provider = mockk<EmbeddedEthereumWalletProvider>()
        val w = wallet(WALLET, provider)
        val page = TransactionsPage(transactions = emptyList(), nextCursor = "cursor-1")
        coEvery { w.getTransactions(any()) } returns Result.success(page)
        val manager = PrivyManager(privyWith(listOf(w)))

        val params = GetTransactionsParams<TransactionChain.Evm>(chain = TransactionChain.Evm.Base, limit = 5)
        assertThat(manager.getTransactions(WALLET, params)).isSameInstanceAs(page)
        coVerify(exactly = 1) { w.getTransactions(params) }
    }

    @Test
    fun `getTransactions bubbles the raw failure rather than wrapping it`() = runBlocking {
        val provider = mockk<EmbeddedEthereumWalletProvider>()
        val w = wallet(WALLET, provider)
        val raw = IllegalStateException("wallet does not support transaction history")
        coEvery { w.getTransactions(any()) } returns Result.failure(raw)
        val manager = PrivyManager(privyWith(listOf(w)))

        val error = runCatching {
            manager.getTransactions(WALLET, GetTransactionsParams(chain = TransactionChain.Evm.Base))
        }.exceptionOrNull()

        assertThat(error).isSameInstanceAs(raw)
        assertThat(error).isNotInstanceOf(RainError::class.java)
    }

    @Test
    fun `getTransactions classifies a Privy authentication failure as TokenExpired`() = runBlocking {
        val provider = mockk<EmbeddedEthereumWalletProvider>()
        val w = wallet(WALLET, provider)
        coEvery { w.getTransactions(any()) } returns
            Result.failure(io.privy.auth.AuthenticationException("User must be authenticated before calling refresh."))
        val manager = PrivyManager(privyWith(listOf(w)))

        val error = runCatching {
            manager.getTransactions(WALLET, GetTransactionsParams(chain = TransactionChain.Evm.Base))
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(RainError.TokenExpired::class.java)
    }

    @Test
    fun `request classifies a Privy authentication failure as TokenExpired`() = runBlocking {
        val provider = mockk<EmbeddedEthereumWalletProvider>()
        coEvery { provider.request(any()) } returns
            Result.failure(io.privy.auth.AuthenticationException("User must be authenticated before calling refresh."))
        val manager = PrivyManager(privyWith(listOf(wallet(WALLET, provider))))

        val error = runCatching { manager.signTypedData(WALLET, "{}") }.exceptionOrNull()

        assertThat(error).isInstanceOf(RainError.TokenExpired::class.java)
    }

    @Test
    fun `request classifies a Privy wallet rejection as UserRejected`() = runBlocking {
        val provider = mockk<EmbeddedEthereumWalletProvider>()
        every { provider.switchChain(any()) } just Runs
        coEvery { provider.request(any()) } returns
            Result.failure(io.privy.wallet.EmbeddedWalletException("User rejected the request"))
        val manager = PrivyManager(privyWith(listOf(wallet(WALLET, provider))))

        val error = runCatching {
            manager.sendTransaction(walletAddress = WALLET, rpcUrl = RPC, transactionJson = "{}")
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(RainError.UserRejected::class.java)
    }

    @Test
    fun `request bubbles a raw exception thrown by the provider call`() = runBlocking {
        val provider = mockk<EmbeddedEthereumWalletProvider>()
        val raw = RuntimeException("insufficient funds for gas")
        coEvery { provider.request(any()) } throws raw
        val manager = PrivyManager(privyWith(listOf(wallet(WALLET, provider))))

        val error = runCatching { manager.signTypedData(WALLET, "{}") }.exceptionOrNull()

        assertThat(error).isSameInstanceAs(raw)
        assertThat(error).isNotInstanceOf(RainError::class.java)
    }

    private companion object {
        const val RPC = "https://rpc.example/1"
        const val WALLET = "0x000000000000000000000000000000000000dEaD"
    }
}
