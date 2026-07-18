package com.rain.sdk.internal.transaction

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.provider.WalletProvider
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
    val expectedFee = 0.00042

    coEvery {
      walletProvider.estimateTransactionFee(chainId, from, to, data, "0x0")
    } returns expectedFee

    val fee = coordinator.estimateGas(chainId, from, to, data)

    assertThat(fee).isWithin(1e-10).of(expectedFee)
  }
}
