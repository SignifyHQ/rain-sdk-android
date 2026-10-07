package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import com.turnkey.http.utils.TurnkeyHttpError
import com.turnkey.types.V1ActivityStatus
import com.turnkey.types.V1EthSendTransactionResult
import com.turnkey.types.V1Result
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * The EVM send once Turnkey has accepted the activity: what [TurnkeyWalletProvider] does with the
 * activity the vendor's client returns or throws (`TurnkeyHttpError.ActivityNotCompleted`, since
 * `com.turnkey:http` 2.2.0), and with the failures that lose it. The Solana twin of every case is in
 * [TurnkeySolanaProviderTest]; the status-poll cases that follow a status id stay in
 * [TurnkeyAdapterTest].
 *
 * Gated on JDK 24+ like [TurnkeyAdapterTest], for the same reason.
 */
class TurnkeyEvmSendActivityTest {

    private lateinit var rpc: MockRpcServer

    @Before
    fun requireJdk24() {
        assumeJdk24()
        rpc = MockRpcServer().also { it.start() }
    }

    @After
    fun tearDown() {
        if (::rpc.isInitialized) rpc.shutdown()
    }

    private fun makeProvider(turnkey: MockTurnkey): TurnkeyWalletProvider = turnkeyWalletProvider(
        turnkey = turnkey,
        rpcEndpoints = mapOf(1 to rpc.urlFor(1)),
        httpClient = OkHttpClient(),
        // No production polling delay, so the wait-budget tests run in milliseconds.
        pollingIntervalMs = 0L,
        sponsorGas = false
    )

    /** Stubs the three JSON-RPC calls made when building a Turnkey send-transaction body. */
    private fun stubSendTransactionRPCs() {
        rpc.stub(method = "eth_getTransactionCount", result = "0x1")
        rpc.stub(method = "eth_estimateGas", result = "0x5208") // 21000
        rpc.stub(method = "eth_gasPrice", result = "0x4a817c800") // 20 gwei
    }

    /**
     * The vendor's client throws `TurnkeyHttpError.ActivityNotCompleted` carrying the activity when it
     * is not completed after its poll (since http 2.2.0); the hook records the activity Turnkey holds, so
     * `getActivity` finds it, then throws the way the vendor does.
     */
    private fun MockTurnkeyClient.notCompletedAfterSubmit(
        id: String,
        status: V1ActivityStatus,
        failureMessage: String? = null
    ) {
        ethSendActivity = { input ->
            val activity = MockTurnkey.makeActivity(
                id = id,
                from = input.from,
                to = input.to,
                caip2 = input.caip2,
                value = input.value,
                data = input.data,
                sendTransactionStatusId = null,
                status = status,
                failureMessage = failureMessage
            )
            mockActivities = mockActivities + activity
            throw TurnkeyHttpError.ActivityNotCompleted(activity, "/public/v1/submit/eth_send_transaction")
        }
    }

    private suspend fun TurnkeyWalletProvider.sendOnMainnet(): String = sendTransaction(
        chainId = 1,
        from = MockTurnkey.DEFAULT_WALLET_ADDRESS,
        to = TurnkeyTestFixtures.RECIPIENT_ADDRESS,
        data = "0x",
        value = "0x0"
    )

    /**
     * Turnkey was still executing the activity when the vendor's client gave up on it and threw it. The
     * send reads it again by id at the SDK's own interval, and once it completes with a status id the
     * normal status poll takes over: the host gets the hash, not an activity id.
     */
    @Test
    fun `sendTransaction waits for the activity the vendor's error carries and polls the status id it completes with`(): Unit = runBlocking {
        stubSendTransactionRPCs()
        val expectedHash = "0x" + "a".repeat(64)
        val turnkey = MockTurnkey()
        var reads = 0
        val client = (turnkey.turnkeyClient as MockTurnkeyClient).apply {
            notCompletedAfterSubmit(id = "act-evm-settling", status = V1ActivityStatus.ACTIVITY_STATUS_PENDING)
            getActivityAnswer = { request ->
                reads++
                val held = mockActivities.single { it.id == request.activityId }
                if (reads < 2) {
                    held
                } else {
                    held.copy(
                        status = V1ActivityStatus.ACTIVITY_STATUS_COMPLETED,
                        result = V1Result(
                            ethSendTransactionResult = V1EthSendTransactionResult(sendTransactionStatusId = "status-after-wait")
                        )
                    )
                }
            }
            sendTransactionStatusQueue = mutableListOf(MockTurnkeyClient.StatusFixture.broadcasted(expectedHash))
        }
        val provider = makeProvider(turnkey)

        val txHash = provider.sendOnMainnet()

        assertThat(txHash).isEqualTo(expectedHash)
        assertThat(client.ethSendTransactionCalls).hasSize(1)
        assertThat(client.getActivityCalls.map { it.activityId }).containsExactly("act-evm-settling", "act-evm-settling")
        assertThat(client.sendTransactionStatusCalls.single().sendTransactionStatusId).isEqualTo("status-after-wait")
    }

    /** Still pending after the vendor's poll and the SDK's own wait: pending on the activity id, not a failure. */
    @Test
    fun `sendTransaction ends pending on the activity id when the activity has not settled within the SDK's own wait`() {
        stubSendTransactionRPCs()
        val turnkey = MockTurnkey()
        val client = (turnkey.turnkeyClient as MockTurnkeyClient).apply {
            notCompletedAfterSubmit(id = "act-evm-still-pending", status = V1ActivityStatus.ACTIVITY_STATUS_PENDING)
        }
        val provider = makeProvider(turnkey)

        val ex = assertThrows(RainError.TransactionPending::class.java) {
            runBlocking { provider.sendOnMainnet() }
        }

        assertThat(ex.statusId).isEqualTo("act-evm-still-pending")
        assertThat(client.getActivityCalls).hasSize(TurnkeyManager.SEND_ACTIVITY_POLL_ATTEMPTS)
        assertThat(client.sendTransactionStatusCalls).isEmpty()
        assertThat(client.ethSendTransactionCalls).hasSize(1)
    }

    /**
     * An activity Turnkey rejected before it broadcast anything (a policy denial) has no status id and
     * moved no money, so it is the one failure after acceptance the host may retry; Turnkey's reason
     * travels with the error and nothing is polled.
     */
    @Test
    fun `sendTransaction reports a rejected activity as ProviderError with the backend's reason and polls nothing`() {
        stubSendTransactionRPCs()
        val turnkey = MockTurnkey()
        val client = (turnkey.turnkeyClient as MockTurnkeyClient).apply {
            notCompletedAfterSubmit(
                id = "act-evm-rejected",
                status = V1ActivityStatus.ACTIVITY_STATUS_REJECTED,
                failureMessage = "policy engine denied the request"
            )
        }
        val provider = makeProvider(turnkey)

        val ex = assertThrows(RainError.ProviderError::class.java) {
            runBlocking { provider.sendOnMainnet() }
        }

        assertThat(ex.message).contains("policy engine denied the request")
        assertThat(client.getActivityCalls).isEmpty()
        assertThat(client.sendTransactionStatusCalls).isEmpty()
    }

    /**
     * The vendor polls `get_activity` after Turnkey accepted the activity; a 401 there is not a refusal
     * of the send, and the vendor's client loses the activity with it. It must not re-run the submit
     * through the coordinator's refresh-and-retry (a second activity, a second transfer): the send ends
     * with the fate unknown, one submit, no refresh, and no HTTP status on the error to retry on.
     */
    @Test
    fun `sendTransaction does not resubmit when the vendor's poll fails with 401 after acceptance`() {
        stubSendTransactionRPCs()
        val turnkey = MockTurnkey()
        val client = (turnkey.turnkeyClient as MockTurnkeyClient).apply {
            ethSendTransactionError = RuntimeException("HTTP error from /public/v1/query/get_activity: 401")
        }
        val provider = makeProvider(turnkey)

        val ex = assertThrows(RainError.ProviderError::class.java) {
            runBlocking { provider.sendOnMainnet() }
        }

        assertThat(ex.message).contains("the send may still land")
        assertThat(TurnkeyErrorMapping.turnkeyHttpStatus(ex)).isNull()
        assertThat(client.ethSendTransactionCalls).hasSize(1)
        assertThat(turnkey.refreshSessionCallCount).isEqualTo(0)
    }

    /** A 5xx answer to the submit may follow acceptance; the host sees status and target only, never the response body. */
    @Test
    fun `sendTransaction reports a 5xx submit answer as a send of unknown fate without the response body`() {
        stubSendTransactionRPCs()
        val turnkey = MockTurnkey()
        val client = (turnkey.turnkeyClient as MockTurnkeyClient).apply {
            ethSendTransactionError = RuntimeException(
                "HTTP error calling ACTIVITY_TYPE_ETH_SEND_TRANSACTION request\nError: {\"marker\":\"gateway-body-7f3a\"}\nCode: 504"
            )
        }
        val provider = makeProvider(turnkey)

        val ex = assertThrows(RainError.ProviderError::class.java) {
            runBlocking { provider.sendOnMainnet() }
        }

        assertThat(ex.message).contains("the send may still land")
        assertThat(ex.message).contains("504")
        assertThat(ex.message).doesNotContain("gateway-body-7f3a")
        assertThat(client.ethSendTransactionCalls).hasSize(1)
        assertThat(client.sendTransactionStatusCalls).isEmpty()
    }

    /**
     * A 401 on the submit itself is the one refusal the coordinator refreshes and retries, and the body
     * is rebuilt for the retry so the nonce and gas quotes are fresh; nothing was executed with the
     * first one.
     */
    @Test
    @Suppress("TooGenericExceptionThrown") // the vendor's own refusal is a bare RuntimeException
    fun `sendTransaction refreshes and retries once when the submit itself is refused with 401`(): Unit = runBlocking {
        stubSendTransactionRPCs()
        val expectedHash = "0x" + "b".repeat(64)
        val turnkey = MockTurnkey()
        var submits = 0
        val client = (turnkey.turnkeyClient as MockTurnkeyClient).apply {
            ethSendActivity = { input ->
                submits++
                if (submits == 1) {
                    throw RuntimeException("HTTP error calling ACTIVITY_TYPE_ETH_SEND_TRANSACTION request\nError: {}\nCode: 401")
                }
                MockTurnkey.makeActivity(
                    id = "act-after-refresh",
                    from = input.from,
                    to = input.to,
                    caip2 = input.caip2,
                    value = input.value,
                    data = input.data,
                    sendTransactionStatusId = "status-after-refresh"
                )
            }
            sendTransactionStatusQueue = mutableListOf(MockTurnkeyClient.StatusFixture.broadcasted(expectedHash))
        }
        turnkey.onRefreshSession = { turnkey.session = MockTurnkey.defaultSession() }
        val provider = makeProvider(turnkey)

        val txHash = provider.sendOnMainnet()

        assertThat(txHash).isEqualTo(expectedHash)
        assertThat(turnkey.refreshSessionCallCount).isEqualTo(1)
        assertThat(client.ethSendTransactionCalls).hasSize(2)
        assertThat(client.sendTransactionStatusCalls.single().sendTransactionStatusId).isEqualTo("status-after-refresh")
    }
}
