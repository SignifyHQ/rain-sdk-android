package com.rain.sdk.portal

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.error.RainError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.unmockkAll
import io.portalhq.android.Portal
import io.portalhq.android.mpc.data.FeatureFlags
import io.portalhq.android.provider.data.PortalProviderResult
import io.portalhq.android.provider.data.PortalProviderRpcResponse
import io.portalhq.android.provider.data.PortalRequestMethod
import io.portalhq.android.provider.data.RequestOptions
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

/**
 * Hash resolution inside [PortalManager.sendTransaction]: a hash the chain can answer for is
 * returned untouched, a UserOperation hash resolves through the EntryPoint's
 * `UserOperationEvent` to the transaction it was mined in, and a reverted operation surfaces
 * as [RainError.TransactionSimulationFailed] rather than as a mined transaction.
 */
class PortalManagerSendTransactionTest {

    @After
    fun tearDown() {
        unmockkAll()
    }

    private val chainId = 43114
    private val submittedHash = "0x" + "a".repeat(64)
    private val minedHash = "0x" + "b".repeat(64)

    private fun managerWith(portal: Portal): PortalManager {
        val manager = spyk(PortalManager())
        every { manager.createPortal(any(), any(), any(), any(), any()) } returns portal
        manager.initialize(
            apiKey = "session-token",
            legacyEthChainId = chainId,
            rpcConfig = mapOf("eip155:$chainId" to "https://rpc.test"),
            featureFlags = FeatureFlags(isMultiBackupEnabled = true),
            autoApprove = true
        )
        return manager
    }

    private fun rpc(result: Any?) = PortalProviderResult(
        id = "id",
        result = PortalProviderRpcResponse(jsonrpc = "2.0", result = result)
    )

    /** UserOperationEvent data: nonce, success, actualGasCost, actualGasUsed. */
    private fun eventData(succeeded: Boolean): String {
        val zeroWord = "0".repeat(64)
        val successWord = "0".repeat(63) + if (succeeded) "1" else "0"
        return "0x" + zeroWord + successWord + zeroWord + zeroWord
    }

    private fun portalForSend(): Portal {
        val portal = mockk<Portal>(relaxed = true)
        coEvery {
            portal.request(any(), PortalRequestMethod.eth_call, any(), null as RequestOptions?)
        } returns rpc("0x")
        coEvery {
            portal.request(any(), PortalRequestMethod.eth_blockNumber, any(), null as RequestOptions?)
        } returns rpc("0x10")
        coEvery {
            portal.request(any(), PortalRequestMethod.eth_sendTransaction, any(), null as RequestOptions?)
        } returns PortalProviderResult(id = "id", result = submittedHash)
        return portal
    }

    private fun send(manager: PortalManager): String = runBlocking {
        manager.sendTransaction(
            chainId = chainId,
            from = TestFixtures.WALLET_ADDRESS,
            to = TestFixtures.CONTRACT_ADDRESS,
            data = "0x095ea7b3deadbeef"
        )
    }

    @Test
    fun `a hash the chain knows is returned without scanning EntryPoint logs`() {
        val portal = portalForSend()
        coEvery {
            portal.request(any(), PortalRequestMethod.eth_getTransactionByHash, any(), null as RequestOptions?)
        } returns rpc(mapOf("hash" to submittedHash))

        assertThat(send(managerWith(portal))).isEqualTo(submittedHash)
        coVerify(exactly = 0) {
            portal.request(any(), PortalRequestMethod.eth_getLogs, any(), null as RequestOptions?)
        }
    }

    @Test
    fun `a UserOperation hash resolves to the transaction it was mined in`() {
        val portal = portalForSend()
        coEvery {
            portal.request(any(), PortalRequestMethod.eth_getTransactionByHash, any(), null as RequestOptions?)
        } returns rpc(null)
        coEvery {
            portal.request(any(), PortalRequestMethod.eth_getLogs, any(), null as RequestOptions?)
        } returns rpc(listOf(mapOf("transactionHash" to minedHash, "data" to eventData(succeeded = true))))

        assertThat(send(managerWith(portal))).isEqualTo(minedHash)
    }

    @Test
    fun `a reverted UserOperation throws rather than reporting a mined transaction`() {
        val portal = portalForSend()
        coEvery {
            portal.request(any(), PortalRequestMethod.eth_getTransactionByHash, any(), null as RequestOptions?)
        } returns rpc(null)
        coEvery {
            portal.request(any(), PortalRequestMethod.eth_getLogs, any(), null as RequestOptions?)
        } returns rpc(listOf(mapOf("transactionHash" to minedHash, "data" to eventData(succeeded = false))))

        val error = runCatching { send(managerWith(portal)) }.exceptionOrNull()

        assertThat(error).isInstanceOf(RainError.TransactionSimulationFailed::class.java)
    }
}
