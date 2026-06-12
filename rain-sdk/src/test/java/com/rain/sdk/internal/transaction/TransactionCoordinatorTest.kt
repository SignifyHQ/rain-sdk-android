package com.rain.sdk.internal.transaction

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.core.PortalManager
import com.rain.sdk.utils.EthereumConverter
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.portalhq.android.Portal
import io.portalhq.android.provider.data.PortalProviderResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

class TransactionCoordinatorTest {

  private lateinit var portalManager: PortalManager
  private lateinit var portal: Portal
  private lateinit var validator: TransactionValidator
  private lateinit var signer: TransactionSigner
  private lateinit var executor: TransactionExecutor
  private lateinit var coordinator: TransactionCoordinator

  @Before
  fun setUp() {
    portalManager = mockk()
    portal = mockk()
    validator = mockk()
    signer = mockk()
    executor = mockk()
    
    coordinator = TransactionCoordinator(portalManager, validator, signer, executor)
    
    every { portalManager.getPortalInstance() } returns portal
  }

  @After
  fun tearDown() {
    unmockkAll()
  }

  @Test
  fun `estimateGas returns correct fee in ETH`() = runBlocking {
    val chainId = 1
    val from = "0x123"
    val to = "0x456"
    val data = "0x789"
    
    // 21000 in hex is 0x5208
    val gasLimitResult = PortalProviderResult(
      result = "0x5208",
      id = "1"
    )
    
    // 20 Gwei (20 * 10^9) in hex is 0x4a817c800
    val gasPriceResult = PortalProviderResult(
      result = "0x4a817c800",
      id = "2"
    )

    coEvery { portal.ethEstimateGas(any(), any()) } returns gasLimitResult
    coEvery { portal.ethGasPrice(any()) } returns gasPriceResult

    val fee = coordinator.estimateGas(chainId, from, to, data)

    // Expected fee = 21000 * 20 * 10^9 = 420,000 * 10^9 Wei = 0.00042 ETH
    assertThat(fee).isWithin(1e-10).of(0.00042)
  }

  @Test
  fun `estimateGas returns 0 when portal results are invalid`() = runBlocking {
    val chainId = 1
    val from = "0x123"
    val to = "0x456"
    val data = "0x789"
    
    // Invalid results (missing "0x" or empty)
    val invalidResult = PortalProviderResult(
      result = "invalid",
      id = "1"
    )

    coEvery { portal.ethEstimateGas(any(), any()) } returns invalidResult
    coEvery { portal.ethGasPrice(any()) } returns invalidResult

    val fee = coordinator.estimateGas(chainId, from, to, data)

    // Expected fee = 0 * 0 = 0 ETH
    assertThat(fee).isEqualTo(0.0)
  }
}
