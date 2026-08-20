package com.rain.sdk.internal.transaction

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.provider.WalletProvider
import org.junit.Assert.assertThrows
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

class TransactionCoordinatorTest {

  private lateinit var walletProvider: WalletProvider
  private lateinit var validator: TransactionValidator
  private lateinit var signer: TransactionSigner
  private lateinit var executor: TransactionExecutor
  private lateinit var coordinator: TransactionCoordinator

  @Before
  fun setUp() {
    walletProvider = mockk()
    validator = mockk()
    signer = mockk()
    executor = mockk()

    coordinator = TransactionCoordinator(
      walletProvider = { walletProvider },
      transactionBuilder = mockk(),
      validator = validator,
      signer = signer,
      executor = executor
    )
  }

  @After
  fun tearDown() {
    unmockkAll()
  }

  @Test
  fun `estimateGas delegates to walletProvider estimateTransactionFee`() = runBlocking {
    val chainId = 1
    val from = "0x123"
    val to = "0x456"
    val data = "0x789"
    val expectedFee = java.math.BigDecimal("0.00042")

    coEvery {
      walletProvider.estimateTransactionFee(chainId, from, to, data, "0x0")
    } returns expectedFee

    val fee = coordinator.estimateGas(chainId, from, to, data)

    assertThat(fee).isEqualTo(expectedFee)
  }

  // The Solana withdrawal path in RainSdkManager shares this wrapper, so its contract is pinned.

  @Test
  fun `withWithdrawalErrors maps a simulation failure to WithdrawalRevertedByNetwork`() {
    assertThrows(RainError.WithdrawalRevertedByNetwork::class.java) {
      runBlocking {
        coordinator.withWithdrawalErrors<Unit>("Withdraw collateral") {
          throw RainError.TransactionSimulationFailed(RuntimeException("revert"))
        }
      }
    }
  }

  @Test
  fun `withWithdrawalErrors wraps raw exceptions as InternalError`() {
    val ex = assertThrows(RainError.InternalError::class.java) {
      runBlocking {
        coordinator.withWithdrawalErrors<Unit>("Withdraw collateral") {
          throw IllegalArgumentException("truncated account data")
        }
      }
    }
    assertThat(ex.message).contains("Withdraw collateral")
  }

  @Test
  fun `withWithdrawalErrors passes RainError through unchanged`() {
    assertThrows(RainError.InvalidAmount::class.java) {
      runBlocking {
        coordinator.withWithdrawalErrors<Unit>("Withdraw collateral") {
          throw RainError.InvalidAmount("0", "amount must be positive")
        }
      }
    }
  }
}
