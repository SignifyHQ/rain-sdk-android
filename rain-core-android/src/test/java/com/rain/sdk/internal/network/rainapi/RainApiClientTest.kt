package com.rain.sdk.internal.network.rainapi

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.error.RainError
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.math.BigInteger
import java.time.Instant

class RainApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: RainApiClient
    private val credentials = RainApiCredentials(apiKey = "key-123", userId = "user-abc")

    private val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        client = RainApiClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ---------- Session ----------

    @Test
    fun `createSession parses token and expiry`() = runBlocking {
        server.enqueue(
            json("""{"token":"cst_abc","expiresAt":"2030-01-01T00:00:00Z","userId":"user-abc"}""")
        )

        val session = client.createSession(baseUrl, credentials)

        assertThat(session.token).isEqualTo("cst_abc")
        assertThat(session.expiresAt).isEqualTo(Instant.parse("2030-01-01T00:00:00Z"))
    }

    @Test
    fun `createSession sends Api-Key header with empty body and no content type`() = runBlocking {
        server.enqueue(json("""{"token":"cst_abc","expiresAt":"2030-01-01T00:00:00Z"}"""))

        client.createSession(baseUrl, credentials)

        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.path).isEqualTo("/v1/issuing/users/user-abc/sessions")
        assertThat(recorded.getHeader("Api-Key")).isEqualTo("key-123")
        assertThat(recorded.body.size).isEqualTo(0)
        // Rain 400s when an empty body declares a content type — must stay absent.
        assertThat(recorded.getHeader("Content-Type")).isNull()
    }

    @Test
    fun `createSession without parseable expiry yields null expiresAt`() = runBlocking {
        server.enqueue(json("""{"token":"cst_abc","expiresAt":"not-a-date"}"""))

        val session = client.createSession(baseUrl, credentials)

        assertThat(session.expiresAt).isNull()
    }

    @Test
    fun `createSession with missing token throws NetworkError`() {
        server.enqueue(json("""{"expiresAt":"2030-01-01T00:00:00Z"}"""))

        assertThrows(RainError.NetworkError::class.java) {
            runBlocking { client.createSession(baseUrl, credentials) }
        }
    }

    // ---------- Contracts ----------

    @Test
    fun `getContracts parses full and minimal contracts and sends Bearer header`() = runBlocking {
        server.enqueue(
            json(
                """
                [
                  {
                    "id": "c-1",
                    "chainId": 43114,
                    "controllerAddress": "0xcontroller",
                    "proxyAddress": "0xproxy",
                    "depositAddress": "0xdeposit",
                    "adminAddresses": ["0xadmin1", "0xadmin2"],
                    "contractVersion": 2,
                    "tokens": [
                      {"address": "0xtoken", "balance": "12.5", "exchangeRate": 1.0, "advanceRate": 0.8}
                    ]
                  },
                  {
                    "chainId": 1,
                    "controllerAddress": "0xc2",
                    "proxyAddress": "0xp2"
                  }
                ]
                """.trimIndent()
            )
        )

        val contracts = client.getContracts(baseUrl, "cst_abc", "user-abc")

        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/v1/issuing/users/user-abc/contracts")
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer cst_abc")

        assertThat(contracts).hasSize(2)
        val full = contracts[0]
        assertThat(full.id).isEqualTo("c-1")
        assertThat(full.chainId).isEqualTo(43114)
        assertThat(full.proxyAddress).isEqualTo("0xproxy")
        assertThat(full.controllerAddress).isEqualTo("0xcontroller")
        assertThat(full.depositAddress).isEqualTo("0xdeposit")
        assertThat(full.adminAddresses).containsExactly("0xadmin1", "0xadmin2").inOrder()
        assertThat(full.contractVersion).isEqualTo(2)
        assertThat(full.tokens).hasSize(1)
        assertThat(full.tokens[0].address).isEqualTo("0xtoken")
        assertThat(full.tokens[0].balance).isEqualTo("12.5")
        assertThat(full.tokens[0].balanceAmount).isEqualTo("12.5".toBigDecimal())
        assertThat(full.tokens[0].exchangeRate).isEqualTo(1.0)
        assertThat(full.tokens[0].advanceRate).isEqualTo(0.8)
        assertThat(full.tokens[0].symbol).isNull()
        assertThat(full.tokens[0].decimals).isNull()

        val minimal = contracts[1]
        assertThat(minimal.id).isNull()
        assertThat(minimal.depositAddress).isNull()
        assertThat(minimal.contractVersion).isNull()
        assertThat(minimal.adminAddresses).isEmpty()
        assertThat(minimal.tokens).isEmpty()
    }

    @Test
    fun `getContracts maps explicit JSON nulls to Kotlin null, never the string null`() = runBlocking {
        server.enqueue(
            json(
                """
                [
                  {
                    "id": null,
                    "chainId": 1,
                    "controllerAddress": "0xc",
                    "proxyAddress": "0xp",
                    "depositAddress": null,
                    "adminAddresses": ["0xadmin", null],
                    "contractVersion": null,
                    "tokens": [{"address": "0xtoken", "balance": null}]
                  }
                ]
                """.trimIndent()
            )
        )

        val contract = client.getContracts(baseUrl, "cst_abc", "user-abc").single()

        assertThat(contract.id).isNull()
        assertThat(contract.depositAddress).isNull()
        assertThat(contract.contractVersion).isNull()
        assertThat(contract.adminAddresses).containsExactly("0xadmin")
        assertThat(contract.tokens.single().balance).isEmpty()
    }

    @Test
    fun `getContracts on empty array returns empty list`() = runBlocking {
        server.enqueue(json("[]"))

        val contracts = client.getContracts(baseUrl, "cst_abc", "user-abc")

        assertThat(contracts).isEmpty()
    }

    // ---------- Withdrawal signature ----------

    @Test
    fun `getWithdrawalSignature sends expected query params`() = runBlocking {
        server.enqueue(readySignature())

        client.getWithdrawalSignature(
            baseUrl = baseUrl,
            cst = "cst_abc",
            userId = "user-abc",
            chainId = 43114,
            tokenAddress = "0xtoken",
            amountBaseUnits = BigInteger("1500000"),
            adminAddress = "0xadmin",
            recipientAddress = "0xrecipient",
            isAmountNative = true,
        )

        val url = server.takeRequest().requestUrl!!
        assertThat(url.encodedPath).isEqualTo("/v1/issuing/users/user-abc/signatures/withdrawals")
        assertThat(url.queryParameter("chainId")).isEqualTo("43114")
        assertThat(url.queryParameter("token")).isEqualTo("0xtoken")
        assertThat(url.queryParameter("amount")).isEqualTo("1500000")
        assertThat(url.queryParameter("adminAddress")).isEqualTo("0xadmin")
        assertThat(url.queryParameter("recipientAddress")).isEqualTo("0xrecipient")
        assertThat(url.queryParameter("isAmountNative")).isEqualTo("true")
    }

    @Test
    fun `getWithdrawalSignature maps ready response to RainAdminSignature`() = runBlocking {
        server.enqueue(readySignature())

        val signature = fetchSignature()

        assertThat(signature.salt).isEqualTo("0xsalt")
        assertThat(signature.signature).isEqualTo("0xsigdata")
        assertThat(signature.expiresAt).isEqualTo("2030-01-01T00:00:00Z")
    }

    @Test
    fun `getWithdrawalSignature pending status throws SignatureNotReady with retryAfter`() {
        server.enqueue(json("""{"status":"pending","retryAfter":30}"""))

        val error = assertThrows(RainError.SignatureNotReady::class.java) {
            runBlocking { fetchSignature() }
        }
        assertThat(error.status).isEqualTo("pending")
        assertThat(error.retryAfter).isEqualTo(30)
    }

    @Test
    fun `getWithdrawalSignature ready without signature throws SignatureNotReady`() {
        server.enqueue(json("""{"status":"ready"}"""))

        val error = assertThrows(RainError.SignatureNotReady::class.java) {
            runBlocking { fetchSignature() }
        }
        assertThat(error.retryAfter).isNull()
    }

    @Test
    fun `getWithdrawalSignature ready with empty signature data throws SignatureNotReady`() {
        server.enqueue(json("""{"status":"ready","signature":{"data":null,"salt":"0xsalt"}}"""))

        assertThrows(RainError.SignatureNotReady::class.java) {
            runBlocking { fetchSignature() }
        }
    }

    @Test
    fun `getWithdrawalSignature null status maps to unknown`() {
        server.enqueue(json("""{"status":null}"""))

        val error = assertThrows(RainError.SignatureNotReady::class.java) {
            runBlocking { fetchSignature() }
        }
        assertThat(error.status).isEqualTo("unknown")
    }

    // ---------- Error mapping ----------

    @Test
    fun `401 maps to Unauthorized`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("bad key"))

        assertThrows(RainError.Unauthorized::class.java) {
            runBlocking { client.getContracts(baseUrl, "cst_abc", "user-abc") }
        }
    }

    @Test
    fun `403 maps to Unauthorized`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("forbidden"))

        assertThrows(RainError.Unauthorized::class.java) {
            runBlocking { client.getContracts(baseUrl, "cst_abc", "user-abc") }
        }
    }

    @Test
    fun `500 maps to ApiError carrying the status code`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val error = assertThrows(RainError.ApiError::class.java) {
            runBlocking { client.getContracts(baseUrl, "cst_abc", "user-abc") }
        }
        assertThat(error.statusCode).isEqualTo(500)
        assertThat(error.message).contains("boom")
    }

    @Test
    fun `transport failure maps to NetworkError`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertThrows(RainError.NetworkError::class.java) {
            runBlocking { client.getContracts(baseUrl, "cst_abc", "user-abc") }
        }
    }

    @Test
    fun `non-JSON body maps to NetworkError`() {
        server.enqueue(json("<html>gateway error</html>"))

        assertThrows(RainError.NetworkError::class.java) {
            runBlocking { client.getContracts(baseUrl, "cst_abc", "user-abc") }
        }
    }

    // ---------- Helpers ----------

    private fun json(body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun readySignature(): MockResponse = json(
        """
        {
          "status": "ready",
          "signature": {"data": "0xsigdata", "salt": "0xsalt"},
          "expiresAt": "2030-01-01T00:00:00Z"
        }
        """.trimIndent()
    )

    private suspend fun fetchSignature() = client.getWithdrawalSignature(
        baseUrl = baseUrl,
        cst = "cst_abc",
        userId = "user-abc",
        chainId = 1,
        tokenAddress = "0xtoken",
        amountBaseUnits = BigInteger.ONE,
        adminAddress = "0xadmin",
        recipientAddress = "0xrecipient",
        isAmountNative = true,
    )
}
