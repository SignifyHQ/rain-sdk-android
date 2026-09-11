package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class TurnkeyHistoryClientTest {

    private lateinit var server: MockWebServer

    /** Records what was stamped and answers with a fixed header. */
    private class RecordingStamper : TurnkeyRequestStamper {
        val calls = mutableListOf<Pair<String, String>>()
        override suspend fun stamp(sessionPublicKey: String, payload: String): Pair<String, String> {
            calls += sessionPublicKey to payload
            return "X-Stamp" to "stamp-value"
        }
    }

    private lateinit var stamper: RecordingStamper
    private lateinit var client: TurnkeyHistoryClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        stamper = RecordingStamper()
        client = TurnkeyHistoryClient(
            httpClient = OkHttpClient(),
            apiBaseUrl = server.url("/").toString().trimEnd('/'),
            stamper = stamper
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun listEth() = runBlocking {
        client.listEthTransactionHistory(
            organizationId = "org-1",
            sessionPublicKey = "session-pub",
            address = "0xabc",
            caip2 = "eip155:84532",
            limit = 25
        )
    }

    @Test
    fun `eth request posts stamped body to the query path`() {
        server.enqueue(MockResponse().setBody("""{"transactions":[]}"""))

        listEth()

        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/public/v1/query/list_eth_transaction_history")
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.getHeader("X-Stamp")).isEqualTo("stamp-value")

        val body = JSONObject(recorded.body.readUtf8())
        assertThat(body.getString("organizationId")).isEqualTo("org-1")
        assertThat(body.getString("address")).isEqualTo("0xabc")
        assertThat(body.getString("caip2")).isEqualTo("eip155:84532")
        // The API rejects a numeric limit; it must be serialized as a JSON string.
        assertThat(body.getJSONObject("paginationOptions").get("limit")).isEqualTo("25")
    }

    @Test
    fun `stamp signs the exact body that is posted`() {
        server.enqueue(MockResponse().setBody("""{"transactions":[]}"""))

        listEth()

        val posted = server.takeRequest().body.readUtf8()
        assertThat(stamper.calls).hasSize(1)
        assertThat(stamper.calls[0].first).isEqualTo("session-pub")
        assertThat(stamper.calls[0].second).isEqualTo(posted)
    }

    @Test
    fun `eth response parses transactions transfers and page metadata is ignored`() {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "transactions": [
                    {
                      "transactionHash": "0xhash",
                      "block": {"number": "123", "hash": "0xblock", "timestamp": "2026-08-12T10:00:00Z"},
                      "status": "CONFIRMED",
                      "origin": "TURNKEY",
                      "from": "0xfrom",
                      "to": "0xto",
                      "fee": {"amount": "21", "caip19": "eip155:84532/slip44:60"},
                      "transfers": [
                        {
                          "direction": "OUT",
                          "asset": {"caip19": "eip155:84532/erc20:0xtoken", "symbol": "USDC", "name": "USD Coin", "decimals": 6},
                          "amount": "2500000",
                          "counterparty": "0xcounterparty",
                          "display": {"crypto": "2.5", "usd": "2.50"}
                        }
                      ],
                      "turnkey": {"sponsored": true, "activityFingerprint": "fp"}
                    }
                  ],
                  "pageInfo": {"hasNextPage": false, "endCursor": "cursor"}
                }
                """.trimIndent()
            )
        )

        val response = listEth()

        assertThat(response.transactions).hasSize(1)
        val tx = response.transactions[0]
        assertThat(tx.transactionHash).isEqualTo("0xhash")
        assertThat(tx.block?.number).isEqualTo("123")
        assertThat(tx.block?.timestamp).isEqualTo("2026-08-12T10:00:00Z")
        assertThat(tx.status).isEqualTo("CONFIRMED")
        assertThat(tx.from).isEqualTo("0xfrom")
        assertThat(tx.to).isEqualTo("0xto")
        assertThat(tx.turnkey?.sponsored).isTrue()
        val transfer = tx.transfers.single()
        assertThat(transfer.direction).isEqualTo("OUT")
        assertThat(transfer.amount).isEqualTo("2500000")
        assertThat(transfer.counterparty).isEqualTo("0xcounterparty")
        assertThat(transfer.asset?.symbol).isEqualTo("USDC")
        assertThat(transfer.asset?.decimals).isEqualTo(6)
        assertThat(transfer.display?.crypto).isEqualTo("2.5")
        assertThat(transfer.display?.usd).isEqualTo("2.50")
    }

    @Test
    fun `sol request posts to the sol query path and parses signatures`() {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "transactions": [
                    {
                      "signature": "5sig",
                      "block": {"number": "9", "hash": "bh", "timestamp": "2026-08-12T10:00:00Z"},
                      "status": "FINALIZED",
                      "origin": "TURNKEY",
                      "feePayer": "FeePayer111",
                      "signers": [{"address": "FeePayer111", "writable": true}],
                      "transfers": []
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        val response = runBlocking {
            client.listSolTransactionHistory(
                organizationId = "org-1",
                sessionPublicKey = "session-pub",
                address = "SolAddr",
                caip2 = "solana:EtWTRABZaYq6iMfeYKouRu166VU2xqa1",
                limit = 10
            )
        }

        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/public/v1/query/list_sol_transaction_history")
        assertThat(response.transactions.single().signature).isEqualTo("5sig")
        assertThat(response.transactions.single().feePayer).isEqualTo("FeePayer111")
        assertThat(response.transactions.single().status).isEqualTo("FINALIZED")
    }

    @Test
    fun `non-2xx response throws TurnkeyHistoryError with the status code`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("""{"code":7,"message":"transaction history feature is not enabled for organization org-1"}""")
        )

        val error = assertThrows(TurnkeyHistoryError::class.java) { listEth() }
        assertThat(error.statusCode).isEqualTo(403)
        assertThat(error.message).contains("not enabled")
    }

    @Test
    fun `malformed response body throws rather than returning empty history`() {
        server.enqueue(MockResponse().setBody("not json"))

        assertThrows(Exception::class.java) { listEth() }
    }

    @Test
    fun `missing transactions field parses as empty history`() {
        server.enqueue(MockResponse().setBody("{}"))

        assertThat(listEth().transactions).isEmpty()
    }

    @Test
    fun `explicit null transactions coerces to empty history`() {
        server.enqueue(MockResponse().setBody("""{"transactions":null}"""))

        assertThat(listEth().transactions).isEmpty()
    }

    @Test
    fun `quoted numeric decimals still parses the page`() {
        // proto3-JSON may emit int64 values as strings; that must not take down the whole page.
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "transactions": [
                    {
                      "transactionHash": "0xhash",
                      "transfers": [
                        {"direction": "OUT", "asset": {"caip19": "eip155:1/erc20:0xt", "symbol": "USDC", "decimals": "6"}, "amount": "1", "counterparty": "0xc"}
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        val tx = listEth().transactions.single()
        assertThat(tx.transfers.single().asset?.decimals).isEqualTo(6)
    }
}
