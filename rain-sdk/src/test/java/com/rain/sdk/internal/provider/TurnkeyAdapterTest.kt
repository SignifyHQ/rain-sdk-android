package com.rain.sdk.internal.provider

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.helpers.MockRpcServer
import com.rain.sdk.internal.helpers.TestFixtures
import com.rain.sdk.internal.helpers.assumeJdk24
import com.rain.sdk.models.Token
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * Adapter-level tests for [TurnkeyWalletProvider] covering the RPC-bound paths not exercised
 * by [TurnkeyWalletProviderTest]: polling status transitions (failure / pending →
 * broadcasted), RPC fee estimation, network-failure mapping, and session/client resolution
 * edge cases.
 *
 * Gated on JDK 24+ because the Turnkey AAR is compiled to major class version 68 — see
 * [com.rain.sdk.internal.core.RainSdkManagerTurnkeyTest] for the same pattern. The whole
 * test class would fail to load on JDK 21 since its method/field signatures touch Turnkey
 * types directly — but JUnit reflects on `@Test` methods individually, so per-test `@Before`
 * gating still works for the parameter-free public methods below.
 */
class TurnkeyAdapterTest {

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

    private fun makeProvider(
        turnkey: MockTurnkey = MockTurnkey(),
        walletAddressOverride: String? = null,
        chainId: Int = 1
    ): TurnkeyWalletProvider = TurnkeyWalletProvider(
        turnkey = turnkey,
        rpcEndpoints = mapOf(chainId to rpc.urlFor(chainId)),
        walletAddressOverride = walletAddressOverride,
        httpClient = OkHttpClient(),
        // Zero out the 1s production polling delay so retry-based tests run in milliseconds
        // and regressions in failure detection fail fast instead of hanging for 30s.
        pollingIntervalMs = 0L
    )

    // ---- Polling: pending → broadcasted -----------------------------------------

    @Test
    fun `sendTransaction polls until status returns a tx hash`(): Unit = runBlocking {
        stubSendTransactionRPCs()
        val expectedHash = "0x" + "9".repeat(64)

        val turnkey = MockTurnkey()
        val client = (turnkey.turnkeyClient as MockTurnkeyClient).apply {
            sendTransactionStatusQueue = mutableListOf(
                MockTurnkeyClient.StatusFixture.pending(),
                MockTurnkeyClient.StatusFixture.broadcasted(expectedHash)
            )
        }
        val provider = makeProvider(turnkey)

        val txHash = provider.sendTransaction(
            chainId = 1,
            from = MockTurnkey.DEFAULT_WALLET_ADDRESS,
            to = TestFixtures.RECIPIENT_ADDRESS,
            data = "0x",
            value = "0x0"
        )

        assertThat(txHash).isEqualTo(expectedHash)
        assertThat(client.sendTransactionStatusCalls).hasSize(2)
    }

    // ---- Polling: failure status path -------------------------------------------

    @Test
    fun `sendTransaction throws ProviderError when status reports failure`() {
        stubSendTransactionRPCs()
        val turnkey = MockTurnkey()
        (turnkey.turnkeyClient as MockTurnkeyClient).sendTransactionStatusQueue =
            mutableListOf(MockTurnkeyClient.StatusFixture.failed(message = "reverted"))
        val provider = makeProvider(turnkey)

        val ex = runCatching {
            runBlocking {
                provider.sendTransaction(
                    chainId = 1,
                    from = MockTurnkey.DEFAULT_WALLET_ADDRESS,
                    to = TestFixtures.RECIPIENT_ADDRESS,
                    data = "0x",
                    value = "0x0"
                )
            }
        }.exceptionOrNull()
        assertThat(ex).isInstanceOf(RainError.ProviderError::class.java)
        assertThat(ex?.cause?.message).contains("reverted")
    }

    // ---- ethSendTransaction error propagation -----------------------------------

    @Test
    fun `sendTransaction propagates ethSendTransaction failures`() {
        stubSendTransactionRPCs()
        val turnkey = MockTurnkey()
        (turnkey.turnkeyClient as MockTurnkeyClient).ethSendTransactionError =
            RuntimeException("turnkey rejected send")
        val provider = makeProvider(turnkey)

        val ex = runCatching {
            runBlocking {
                provider.sendTransaction(
                    chainId = 1,
                    from = MockTurnkey.DEFAULT_WALLET_ADDRESS,
                    to = TestFixtures.RECIPIENT_ADDRESS,
                    data = "0x",
                    value = "0x0"
                )
            }
        }.exceptionOrNull()
        // sendTransaction bubbles the underlying exception — RainSdkManager wraps it; here we
        // assert the provider surfaced the underlying RuntimeException.
        assertThat(ex).isInstanceOf(RuntimeException::class.java)
        assertThat(ex?.message).contains("turnkey rejected send")
    }

    // ---- estimateTransactionFee via RPC -----------------------------------------

    @Test
    fun `estimateTransactionFee multiplies gas estimate by gas price`(): Unit = runBlocking {
        rpc.stub(method = "eth_estimateGas", result = "0x5208") // 21000
        rpc.stub(method = "eth_gasPrice", result = "0x4a817c800") // 20 gwei = 20_000_000_000

        val provider = makeProvider()

        val fee = provider.estimateTransactionFee(
            chainId = 1,
            from = MockTurnkey.DEFAULT_WALLET_ADDRESS,
            to = TestFixtures.RECIPIENT_ADDRESS,
            data = "0x",
            value = "0x0"
        )

        val expected = 20_000_000_000.0 * 21_000.0 / 1e18
        assertThat(fee).isWithin(1e-12).of(expected)
        assertThat(rpc.recordedMethods).containsAtLeast("eth_estimateGas", "eth_gasPrice")
    }

    @Test
    fun `estimateTransactionFee surfaces RPC network failure as NetworkError`() {
        rpc.stubNetworkFailure(method = "eth_estimateGas")

        val provider = makeProvider()

        assertThrows(RainError.NetworkError::class.java) {
            runBlocking {
                provider.estimateTransactionFee(
                    chainId = 1,
                    from = MockTurnkey.DEFAULT_WALLET_ADDRESS,
                    to = TestFixtures.RECIPIENT_ADDRESS,
                    data = "0x",
                    value = "0x0"
                )
            }
        }
    }

    // ---- getBalance contract via RPC (eth_call) ----------------------------------

    @Test
    fun `getBalance contract parses eth_call result using token decimals`(): Unit = runBlocking {
        // 1 USDC = 1_000_000 with 6 decimals
        rpc.stub(method = "eth_call", result = "0x0f4240")

        val provider = makeProvider()
        val balance = provider.getBalance(
            chainId = 1,
            token = Token.Contract(TestFixtures.USDC_ADDRESS)
        )

        // USDC is in the chain-1 registry (decimals 6), so no extra metadata RPC is needed.
        assertThat(balance.decimals).isEqualTo(6)
        assertThat(balance.decimalAmount.toDouble()).isWithin(1e-12).of(1.0)
        assertThat(rpc.recordedMethods).containsExactly("eth_call")
    }

    @Test
    fun `getBalance contract maps RPC network failure to NetworkError`() {
        rpc.stubNetworkFailure(method = "eth_call")

        val provider = makeProvider()
        assertThrows(RainError.NetworkError::class.java) {
            runBlocking {
                provider.getBalance(
                    chainId = 1,
                    token = Token.Contract(TestFixtures.USDC_ADDRESS)
                )
            }
        }
    }

    // ---- native balance on an unsupported chain reads directly via RPC ----------

    @Test
    fun `getBalance native on unsupported chain reads via eth_getBalance`(): Unit = runBlocking {
        // 1 ETH in wei = 0xde0b6b3a7640000
        rpc.stub(method = "eth_getBalance", result = "0xde0b6b3a7640000")

        // 43113 (Avalanche Fuji) is outside TURNKEY_SUPPORTED_CHAINS, so the balance read
        // falls through to the chain reader / RPC rather than the Turnkey indexer.
        val provider = makeProvider(chainId = 43113)

        val balance = provider.getBalance(chainId = 43113, token = Token.Native)
        assertThat(balance.decimalAmount.toDouble()).isWithin(1e-12).of(1.0)
        assertThat(rpc.recordedMethods).contains("eth_getBalance")
    }

    // ---- Session / client missing → TokenExpired -------------------------------

    @Test
    fun `sendTransaction throws TokenExpired when turnkeyClient is missing`() {
        val turnkey = MockTurnkey(turnkeyClient = null)
        val provider = makeProvider(turnkey)
        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking {
                provider.sendTransaction(
                    chainId = 1,
                    from = MockTurnkey.DEFAULT_WALLET_ADDRESS,
                    to = TestFixtures.RECIPIENT_ADDRESS,
                    data = "0x",
                    value = "0x0"
                )
            }
        }
    }

    @Test
    fun `getTransactions throws TokenExpired when session missing`() {
        val turnkey = MockTurnkey(session = null)
        val provider = makeProvider(turnkey)
        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking { provider.getTransactions(chainId = 1) }
        }
    }

    // ---- getTransactions error propagation --------------------------------------

    @Test
    fun `getTransactions propagates getActivities failure as raw RuntimeException`() {
        val turnkey = MockTurnkey()
        (turnkey.turnkeyClient as MockTurnkeyClient).getActivitiesError =
            RuntimeException("service unavailable")
        val provider = makeProvider(turnkey)

        val ex = runCatching {
            runBlocking { provider.getTransactions(chainId = 1) }
        }.exceptionOrNull()
        // Bare provider exposes the underlying exception; RainSdkManager wraps via ErrorMapper.
        assertThat(ex).isInstanceOf(RuntimeException::class.java)
        assertThat(ex?.message).contains("service unavailable")
    }

    // ---- signTypedData failure --------------------------------------------------

    @Test
    fun `signTypedData propagates signRawPayload failures`() {
        val turnkey = MockTurnkey()
        turnkey.signRawPayloadError =
            RuntimeException("hardware key denied")
        val provider = makeProvider(turnkey)

        val ex = runCatching {
            runBlocking {
                provider.signTypedData(
                    chainId = 1,
                    walletAddress = MockTurnkey.DEFAULT_WALLET_ADDRESS,
                    typedDataJson = "{}"
                )
            }
        }.exceptionOrNull()
        // Bare provider surfaces the underlying exception unwrapped; RainSdkManager would
        // wrap it via ErrorMapper.mapSigningError. Pin the type so a future implicit-wrap
        // refactor fails this test instead of silently passing.
        assertThat(ex).isInstanceOf(RuntimeException::class.java)
        assertThat(ex).isNotInstanceOf(RainError::class.java)
        assertThat(ex?.message).contains("hardware key denied")
    }

    // ---- helpers ----------------------------------------------------------------

    /** Stubs the three JSON-RPC calls made when building a Turnkey send-transaction body. */
    private fun stubSendTransactionRPCs() {
        rpc.stub(method = "eth_getTransactionCount", result = "0x1")
        rpc.stub(method = "eth_estimateGas", result = "0x5208") // 21000
        rpc.stub(method = "eth_gasPrice", result = "0x4a817c800") // 20 gwei
    }
}
