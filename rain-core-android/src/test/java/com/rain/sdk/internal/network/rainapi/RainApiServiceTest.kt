package com.rain.sdk.internal.network.rainapi

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.helpers.MockChainReader
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import com.rain.sdk.models.TokenInfo
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

class RainApiServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var configStore: RainApiConfigStore
    private lateinit var chainReader: MockChainReader

    /** Per-path responses consumed in order; a path's last entry repeats. */
    private val responses = mutableMapOf<String, MutableList<MockResponse>>()
    private val recordedPaths = mutableListOf<String>()
    private val recordedApiKeys = mutableListOf<String?>()

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                synchronized(recordedPaths) {
                    recordedPaths += path
                    recordedApiKeys += request.getHeader("Api-Key")
                }
                val queue = responses.entries.firstOrNull { path.endsWith(it.key) }?.value
                    ?: return MockResponse().setResponseCode(404)
                return if (queue.size > 1) queue.removeAt(0) else queue.first()
            }
        }
        configStore = RainApiConfigStore(baseUrl = server.url("/").toString().trimEnd('/'))
        configStore.setCredentials("key", "user")
        chainReader = MockChainReader(decimals = 6, symbol = "USDC", name = "USD Coin")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun service() = RainApiService(
        configStore = configStore,
        tokenStore = TokenMetadataStore(chainReader = chainReader),
        chainReader = chainReader,
    )

    // ---------- Configuration ----------

    @Test
    fun `throws ApiNotConfigured before credentials are set`() {
        configStore.clear()

        assertThrows(RainError.ApiNotConfigured::class.java) {
            runBlocking { service().fetchCollateralContracts() }
        }
    }

    // ---------- Authentication ----------

    @Test
    fun `data calls carry the Api-Key directly and never mint a session`() = runBlocking {
        stub("/contracts", contracts())

        service().fetchCollateralContracts()

        assertThat(recordedPaths.none { it.endsWith("/sessions") }).isTrue()
        assertThat(recordedApiKeys).containsExactly("key").inOrder()
    }

    @Test
    fun `401 surfaces Unauthorized without a retry`() {
        // Client session tokens are not issued for Rain's tenants, so there is nothing to re-mint:
        // a rejected key is terminal and a second attempt would only repeat the rejection.
        stub("/contracts", MockResponse().setResponseCode(401).setBody("nope"))

        assertThrows(RainError.Unauthorized::class.java) {
            runBlocking { service().fetchCollateralContracts() }
        }
        assertThat(recordedPaths.count { it.endsWith("/contracts") }).isEqualTo(1)
    }

    // ---------- Enrichment ----------

    @Test
    fun `enriches token metadata via on-chain reads`() = runBlocking {
        stub("/contracts", contracts())

        val contract = service().fetchCollateralContracts().single()
        val token = contract.tokens.single()

        assertThat(token.symbol).isEqualTo("USDC")
        assertThat(token.name).isEqualTo("USD Coin")
        assertThat(token.decimals).isEqualTo(6)
        assertThat(token.balance).isEqualTo("12.5")
    }

    @Test
    fun `registered token resolves without any on-chain read`() = runBlocking {
        stub("/contracts", contracts())
        val tokenStore = TokenMetadataStore(chainReader = chainReader)
        tokenStore.register(
            listOf(
                TokenInfo(
                    chainId = 999888,
                    address = "0xTOKENUNKNOWN",
                    symbol = "REG",
                    decimals = 8,
                    name = "Registered"
                )
            )
        )
        val service = RainApiService(configStore = configStore, tokenStore = tokenStore, chainReader = chainReader)

        val token = service.fetchCollateralContracts().single().tokens.single()

        assertThat(token.symbol).isEqualTo("REG")
        assertThat(token.decimals).isEqualTo(8)
        assertThat(chainReader.decimalsCalls).isEmpty()
        assertThat(chainReader.symbolCalls).isEmpty()
    }

    @Test
    fun `failed metadata reads leave name, symbol AND decimals null`() = runBlocking {
        // A fabricated decimals default (e.g. 18) would corrupt the caller's base-unit math,
        // so a failed read must surface as null — the fetch itself still succeeds.
        chainReader.metadataError = RuntimeException("rpc down")
        stub("/contracts", contracts())

        val token = service().fetchCollateralContracts().single().tokens.single()

        assertThat(token.symbol).isNull()
        assertThat(token.name).isNull()
        assertThat(token.decimals).isNull()
        assertThat(token.balance).isEqualTo("12.5")
    }

    // ---------- Signature passthrough ----------

    @Test
    fun `fetchAdminSignature returns the mapped signature`() = runBlocking {
        stub(
            "/signatures/withdrawals",
            json(
                """{"status":"ready","signature":{"data":"0xsig","salt":"0xsalt"},"expiresAt":"2030-01-01T00:00:00Z"}"""
            ),
        )

        val signature = service().fetchAdminSignature(
            chainId = 999_888,
            tokenAddress = "0xtoken",
            amountBaseUnits = BigInteger.TEN,
            adminAddress = "0xadmin",
            recipientAddress = "0xrecipient",
            isAmountNative = true,
        )

        assertThat(signature.signature).isEqualTo("0xsig")
        assertThat(signature.salt).isEqualTo("0xsalt")
        assertThat(signature.expiresAt).isEqualTo("2030-01-01T00:00:00Z")
    }

    // ---------- Helpers ----------

    private fun stub(pathSuffix: String, vararg queue: MockResponse) {
        responses[pathSuffix] = queue.toMutableList()
    }

    private fun json(body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    /** One contract on an unknown chain (999888) with one unknown token, so the token store
     *  always enriches through the chain reader rather than the built-in registry. */
    private fun contracts(): MockResponse = json(
        """
        [
          {
            "chainId": 999888,
            "controllerAddress": "0xcontroller",
            "proxyAddress": "0xproxy",
            "adminAddresses": ["0xadmin"],
            "tokens": [
              {"address": "0xtokenunknown", "balance": "12.5", "exchangeRate": 1.0, "advanceRate": 0.8}
            ]
          }
        ]
        """.trimIndent()
    )
}
