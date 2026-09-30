package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import com.turnkey.core.TurnkeyContext
import com.turnkey.crypto.generateP256KeyPair
import com.turnkey.http.TurnkeyClient
import com.turnkey.stamper.Stamper
import com.turnkey.types.TSolSendTransactionBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Base64

/**
 * The context adapter's seam for the module's own Solana send: the session key is read on every
 * request and the stamper built from it, so the retry after a refresh signs with the key the vendor
 * client then holds. The vendor singleton's class initializer dispatches onto `Dispatchers.Main`,
 * absent on the JVM, so a test dispatcher stands in for it, as the provider test does; nothing
 * dispatched there is ever run, and the adapter's two vendor reads are handed in instead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TurnkeyContextAdapterTest {

    private lateinit var server: MockWebServer
    private lateinit var vendorClient: TurnkeyClient
    private val stampersBuiltFor = mutableListOf<String>()
    private var sessionKey: String? = null

    private val firstKey = generateP256KeyPair()
    private val secondKey = generateP256KeyPair()

    @Before
    fun setUp() {
        assumeJdk24()
        Dispatchers.setMain(StandardTestDispatcher())
        server = MockWebServer()
        server.start()
        vendorClient = TurnkeyClient(
            apiBaseUrl = server.url("/").toString().trimEnd('/'),
            stamper = Stamper(firstKey.publicKeyCompressed, firstKey.privateKey),
            organizationId = "org-1",
        )
        sessionKey = firstKey.publicKeyCompressed
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.shutdown()
        Dispatchers.resetMain()
    }

    private fun adapter() = TurnkeyContextAdapter(
        context = TurnkeyContext,
        httpClient = OkHttpClient(),
        stamperFor = { publicKey ->
            stampersBuiltFor += publicKey
            val pair = if (publicKey == firstKey.publicKeyCompressed) firstKey else secondKey
            Stamper(pair.publicKeyCompressed, pair.privateKey)
        },
        sessionPublicKey = { sessionKey },
        vendorClient = { vendorClient },
    )

    private fun completedActivity() =
        """
        {"activity": {"id": "activity-1", "organizationId": "org-1", "status": "ACTIVITY_STATUS_COMPLETED",
         "type": "ACTIVITY_TYPE_SOL_SEND_TRANSACTION_V2", "intent": {}, "result": {"solSendTransactionResultV2": {"sendTransactionStatusId": "status-1"}},
         "votes": [], "fingerprint": "fp", "canApprove": false, "canReject": false,
         "createdAt": {"seconds": "1", "nanos": "0"}, "updatedAt": {"seconds": "1", "nanos": "0"}}}
        """.trimIndent()

    private fun body() = TSolSendTransactionBody(
        organizationId = "org-1",
        unsignedTransaction = "0100deadbeef",
        signWiths = listOf("9C6hybhQ6Aycep9jaUnP6uL9ZYvDjUp1aSkFWPUFJtpj"),
        caip2 = "solana:devnet",
    )

    private fun stampPublicKeyOfNextRequest(): String {
        val header = server.takeRequest().getHeader("X-Stamp")
        return JSONObject(String(Base64.getUrlDecoder().decode(header))).getString("publicKey")
    }

    @Test
    fun `the Solana send re-reads the session key on each request, so a rotated key signs the next one`() {
        server.enqueue(MockResponse().setBody(completedActivity()))
        server.enqueue(MockResponse().setBody(completedActivity()))
        val adapter = adapter()

        runBlocking { adapter.turnkeyClient!!.solSendTransaction(body()) }
        assertThat(stampPublicKeyOfNextRequest()).isEqualTo(firstKey.publicKeyCompressed)

        // A refresh rotated the session; the flow now shows the new key.
        sessionKey = secondKey.publicKeyCompressed
        runBlocking { adapter.turnkeyClient!!.solSendTransaction(body()) }
        assertThat(stampPublicKeyOfNextRequest()).isEqualTo(secondKey.publicKeyCompressed)

        assertThat(stampersBuiltFor).containsExactly(firstKey.publicKeyCompressed, secondKey.publicKeyCompressed).inOrder()
    }

    @Test
    fun `the client adapter is rebuilt on every read, so a swapped vendor client takes the next send`() {
        val elsewhere = MockWebServer()
        elsewhere.start()
        try {
            server.enqueue(MockResponse().setBody(completedActivity()))
            elsewhere.enqueue(MockResponse().setBody(completedActivity()))
            val adapter = adapter()

            runBlocking { adapter.turnkeyClient!!.solSendTransaction(body()) }
            // A refresh rebuilt the vendor's client; the flow now hands out one bound elsewhere.
            vendorClient = TurnkeyClient(
                apiBaseUrl = elsewhere.url("/").toString().trimEnd('/'),
                stamper = Stamper(firstKey.publicKeyCompressed, firstKey.privateKey),
                organizationId = "org-1",
            )
            runBlocking { adapter.turnkeyClient!!.solSendTransaction(body()) }

            assertThat(server.requestCount).isEqualTo(1)
            assertThat(elsewhere.requestCount).isEqualTo(1)
        } finally {
            elsewhere.shutdown()
        }
    }
}
