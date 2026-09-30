package com.rain.sdk.turnkey

import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.rain.sdk.error.RainError
import com.turnkey.crypto.generateP256KeyPair
import com.turnkey.http.TurnkeyClient
import com.turnkey.http.utils.ActivityPollerConfig
import com.turnkey.stamper.Stamper
import com.turnkey.types.TSolSendTransactionBody
import com.turnkey.types.V1ActivityStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.ECPublicKeySpec
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.io.IOException
import java.security.KeyFactory
import java.security.Signature
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * The Solana send on the wire, through the module's own request with the vendor's stamper against a
 * mock server: the path, the activity type, the parameters, the stamp, and what comes back for each
 * answer Turnkey can give. The last test pins the vendor defect the request exists for, so the
 * vendor release that fixes it fails here first and the workaround can be deleted.
 */
class TurnkeySolanaSendWireTest {

    private lateinit var server: MockWebServer
    private lateinit var vendorClient: TurnkeyClient
    private lateinit var stamper: Stamper
    private lateinit var sessionPublicKey: String

    @Before
    fun setUp() {
        assumeJdk24()
        server = MockWebServer()
        server.start()
        // An API-key-shaped P-256 pair, the shape a session key has once the vendor rebuilt its
        // client from it; the real path builds the same stamper from the vendor's key store.
        val keyPair = generateP256KeyPair()
        sessionPublicKey = keyPair.publicKeyCompressed
        stamper = Stamper(keyPair.publicKeyCompressed, keyPair.privateKey)
        vendorClient = TurnkeyClient(
            apiBaseUrl = server.url("/").toString().trimEnd('/'),
            stamper = stamper,
            organizationId = "org-1",
        )
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.shutdown()
    }

    private fun request(
        poller: ActivityPollerConfig = ActivityPollerConfig(intervalMs = 0L, numRetries = 2),
        http: OkHttpClient = OkHttpClient(),
        replayBackoffMs: Long = 0L,
    ) = TurnkeySolanaSendRequest(vendorClient, { stamper }, http, poller, replayBackoffMs = replayBackoffMs)

    private fun body(timestampMs: String? = null) = TSolSendTransactionBody(
        timestampMs = timestampMs,
        organizationId = "org-1",
        unsignedTransaction = "0100deadbeef",
        signWiths = listOf(SENDER),
        sponsor = true,
        caip2 = "solana:devnet",
        recentBlockhash = BLOCKHASH,
    )

    private fun submit(input: TSolSendTransactionBody = body()) = runBlocking { request().submit(input) }

    private fun submitIgnoringFailure() {
        runCatching { submit() }
    }

    /** Suspends, without blocking the test thread the send runs on, until the server has seen [count] requests. */
    private suspend fun awaitRequests(count: Int) {
        while (server.requestCount < count) delay(10)
    }

    @Test
    fun `posts the V2 activity type with the V2 parameters, stamped, to the sol_send_transaction path`() {
        server.enqueue(MockResponse().setBody(completedActivity(V2_RESULT)))

        val activity = submit(body(timestampMs = "1700000000000"))

        assertThat(activity.status).isEqualTo(V1ActivityStatus.ACTIVITY_STATUS_COMPLETED)
        assertThat(activity.result.solSendTransactionResultV2?.sendTransactionStatusId).isEqualTo("status-1")
        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.path).isEqualTo(SEND_PATH)
        assertThat(recorded.getHeader("Content-Type")).startsWith("application/json")
        assertThat(recorded.getHeader("X-Client-Version")).isEqualTo(com.turnkey.http.Version.VERSION)
        val posted = recorded.body.readUtf8()
        val envelope = JSONObject(posted)
        assertThat(envelope.getString("type")).isEqualTo("ACTIVITY_TYPE_SOL_SEND_TRANSACTION_V2")
        assertThat(envelope.getString("organizationId")).isEqualTo("org-1")
        assertThat(envelope.getString("timestampMs")).isEqualTo("1700000000000")
        assertThat(envelope.keyList()).containsExactly("parameters", "organizationId", "timestampMs", "type")
        val parameters = envelope.getJSONObject("parameters")
        assertThat(parameters.getString("unsignedTransaction")).isEqualTo("0100deadbeef")
        assertThat(parameters.getJSONArray("signWiths").length()).isEqualTo(1)
        assertThat(parameters.getJSONArray("signWiths").getString(0)).isEqualTo(SENDER)
        assertThat(parameters.getBoolean("sponsor")).isTrue()
        assertThat(parameters.getString("caip2")).isEqualTo("solana:devnet")
        assertThat(parameters.getString("recentBlockhash")).isEqualTo(BLOCKHASH)
        // The V2 intent has no `signWith`, and the envelope fields never leak into the parameters.
        assertThat(parameters.keyList()).containsExactly("unsignedTransaction", "signWiths", "sponsor", "caip2", "recentBlockhash")
        assertStampVerifies(recorded, posted)
    }

    @Test
    fun `a body without a timestamp is stamped with the current time`() {
        server.enqueue(MockResponse().setBody(completedActivity(V2_RESULT)))
        val before = System.currentTimeMillis()

        submit(body(timestampMs = null))

        val recorded = server.takeRequest()
        val posted = recorded.body.readUtf8()
        val timestamp = JSONObject(posted).getString("timestampMs").toLong()
        assertThat(timestamp).isAtLeast(before)
        assertThat(timestamp).isAtMost(System.currentTimeMillis())
        assertStampVerifies(recorded, posted)
    }

    @Test
    fun `optional parameters the body does not carry are omitted, not sent as null`() {
        server.enqueue(MockResponse().setBody(completedActivity(V2_RESULT)))

        submit(
            TSolSendTransactionBody(
                organizationId = "org-1",
                unsignedTransaction = "0100deadbeef",
                signWiths = listOf(SENDER),
                caip2 = "solana:devnet",
            )
        )

        val parameters = JSONObject(server.takeRequest().body.readUtf8()).getJSONObject("parameters")
        assertThat(parameters.keyList()).containsExactly("unsignedTransaction", "signWiths", "caip2")
    }

    @Test
    fun `a completed activity carrying only the V1 result parses`() {
        server.enqueue(MockResponse().setBody(completedActivity(V1_RESULT)))

        val activity = submit()

        assertThat(activity.result.solSendTransactionResult?.sendTransactionStatusId).isEqualTo("status-1")
        assertThat(activity.result.solSendTransactionResultV2).isNull()
    }

    @Test
    fun `a completed activity without a readable result comes back as it is, for the caller to report pending`() {
        server.enqueue(MockResponse().setBody(completedActivity(result = "{}")))

        val activity = submit()

        assertThat(activity.id).isEqualTo("activity-1")
        assertThat(activity.status).isEqualTo(V1ActivityStatus.ACTIVITY_STATUS_COMPLETED)
        assertThat(activity.result.solSendTransactionResult).isNull()
        assertThat(activity.result.solSendTransactionResultV2).isNull()
    }

    @Test
    fun `a pending activity is read back through get_activity until it settles`() {
        server.enqueue(MockResponse().setBody(activityJson("ACTIVITY_STATUS_PENDING", result = "{}")))
        server.enqueue(MockResponse().setBody(activityJson("ACTIVITY_STATUS_PENDING", result = "{}")))
        server.enqueue(MockResponse().setBody(completedActivity(V2_RESULT)))

        val activity = submit()

        assertThat(activity.status).isEqualTo(V1ActivityStatus.ACTIVITY_STATUS_COMPLETED)
        assertThat(activity.result.solSendTransactionResultV2?.sendTransactionStatusId).isEqualTo("status-1")
        assertThat(server.requestCount).isEqualTo(3)
        server.takeRequest() // the submit
        val poll = server.takeRequest()
        assertThat(poll.path).isEqualTo("/public/v1/query/get_activity")
        val pollBody = JSONObject(poll.body.readUtf8())
        assertThat(pollBody.getString("activityId")).isEqualTo("activity-1")
        assertThat(pollBody.getString("organizationId")).isEqualTo("org-1")
        // The poll is stamped by the vendor's own stamp helper, with the vendor client's key.
        val pollStamp = JSONObject(String(Base64.getUrlDecoder().decode(poll.getHeader("X-Stamp"))))
        assertThat(pollStamp.getString("publicKey")).isEqualTo(sessionPublicKey)
    }

    @Test
    fun `an activity still pending after the poll is returned pending, not thrown`() {
        repeat(5) { server.enqueue(MockResponse().setBody(activityJson("ACTIVITY_STATUS_PENDING", result = "{}"))) }

        val activity = submit()

        assertThat(activity.status).isEqualTo(V1ActivityStatus.ACTIVITY_STATUS_PENDING)
        assertThat(activity.id).isEqualTo("activity-1")
        // The submit, then the vendor's poll shape: numRetries + 1 delayed reads and one last read.
        assertThat(server.requestCount).isEqualTo(5)
    }

    @Test
    fun `the client adapter routes the Solana send through the V2 request, not the vendor's method`() {
        server.enqueue(MockResponse().setBody(completedActivity(V2_RESULT)))

        val activity = runBlocking { TurnkeyClientAdapter(vendorClient, request()).solSendTransaction(body()) }

        assertThat(activity.result.solSendTransactionResultV2?.sendTransactionStatusId).isEqualTo("status-1")
        assertThat(server.requestCount).isEqualTo(1)
        val envelope = JSONObject(server.takeRequest().body.readUtf8())
        assertThat(envelope.getString("type")).isEqualTo("ACTIVITY_TYPE_SOL_SEND_TRANSACTION_V2")
    }

    /**
     * A connection that drops before the answer arrives leaves the send's fate unknown. The identical
     * request is replayed, same body and same stamp, which Turnkey's body-fingerprint idempotency
     * answers with the same activity; a new body would be a second send.
     */
    @Test
    fun `a lost answer replays the identical request, bytes and stamp included`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setBody(completedActivity(V2_RESULT)))

        val activity = submit(body(timestampMs = "1700000000000"))

        assertThat(activity.result.solSendTransactionResultV2?.sendTransactionStatusId).isEqualTo("status-1")
        assertThat(server.requestCount).isEqualTo(2)
        val first = server.takeRequest()
        val second = server.takeRequest()
        val firstBody = first.body.readUtf8()
        assertThat(second.body.readUtf8()).isEqualTo(firstBody)
        assertThat(second.getHeader("X-Stamp")).isEqualTo(first.getHeader("X-Stamp"))
        assertThat(JSONObject(firstBody).getString("timestampMs")).isEqualTo("1700000000000")
    }

    @Test
    fun `a gateway status replays the identical request and a later answer wins`() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("upstream unavailable"))
        server.enqueue(MockResponse().setBody(completedActivity(V2_RESULT)))

        val activity = submit()

        assertThat(activity.status).isEqualTo(V1ActivityStatus.ACTIVITY_STATUS_COMPLETED)
        assertThat(server.requestCount).isEqualTo(2)
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertThat(second.body.readUtf8()).isEqualTo(first.body.readUtf8())
        assertThat(second.getHeader("X-Stamp")).isEqualTo(first.getHeader("X-Stamp"))
    }

    @Test
    fun `replays are bounded and the last connection failure surfaces`() {
        repeat(3) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) }

        assertThrows(IOException::class.java) { submit() }

        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `a gateway status that persists surfaces as the vendor's HTTP failure shape`() {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(502).setBody("bad gateway")) }

        val error = assertThrows(TurnkeyHttpFailure::class.java) { submit() }

        assertThat(TurnkeyErrorMapping.turnkeyHttpStatus(error)).isEqualTo(502)
        assertThat(server.requestCount).isEqualTo(3)
    }

    /**
     * Turnkey echoes the voting user inside the activity, with its authenticators and keys, whose
     * enumerations grow over time. An activity the vendor's full model no longer decodes still yields
     * the fields the send needs, so the accepted send is tracked instead of reported failed.
     */
    @Test
    fun `an activity the vendor model cannot decode in full still yields its id and status id`() {
        server.enqueue(
            MockResponse().setBody(
                activityJson("ACTIVITY_STATUS_COMPLETED", result = V2_RESULT, votes = """[{"id": "vote-1", "unknownShape": true}]""")
            )
        )

        val activity = submit()

        assertThat(activity.id).isEqualTo("activity-1")
        assertThat(activity.organizationId).isEqualTo("org-1")
        assertThat(activity.status).isEqualTo(V1ActivityStatus.ACTIVITY_STATUS_COMPLETED)
        assertThat(activity.result.solSendTransactionResultV2?.sendTransactionStatusId).isEqualTo("status-1")
    }

    @Test
    fun `a failed activity the vendor model cannot decode keeps its status and reason`() {
        server.enqueue(
            MockResponse().setBody(
                activityJson(
                    "ACTIVITY_STATUS_FAILED",
                    result = "{}",
                    failure = """"failure": {"code": 3, "message": "policy engine denied the request"},""",
                    votes = """[{"id": "vote-1"}]"""
                )
            )
        )

        val activity = submit()

        assertThat(activity.status).isEqualTo(V1ActivityStatus.ACTIVITY_STATUS_FAILED)
        assertThat(activity.failure?.message).isEqualTo("policy engine denied the request")
        assertThat(activity.result.solSendTransactionResultV2).isNull()
    }

    @Test
    fun `an answer without an activity id is the one decode failure that surfaces`() {
        server.enqueue(MockResponse().setBody("""{"activity": {"status": "ACTIVITY_STATUS_COMPLETED"}}"""))

        assertThrows(kotlinx.serialization.SerializationException::class.java) { submit() }
    }

    @Test
    fun `a poll read that fails after acceptance returns the accepted activity, never a failure`() {
        server.enqueue(MockResponse().setBody(activityJson("ACTIVITY_STATUS_PENDING", result = "{}")))
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"message":"try later"}"""))

        val activity = submit()

        assertThat(activity.status).isEqualTo(V1ActivityStatus.ACTIVITY_STATUS_PENDING)
        assertThat(activity.id).isEqualTo("activity-1")
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `a failed activity is returned with its reason, for the caller to classify`() {
        server.enqueue(
            MockResponse().setBody(
                activityJson(
                    "ACTIVITY_STATUS_FAILED",
                    result = "{}",
                    failure = """"failure": {"code": 3, "message": "policy engine denied the request"},"""
                )
            )
        )

        val activity = submit()

        assertThat(activity.status).isEqualTo(V1ActivityStatus.ACTIVITY_STATUS_FAILED)
        assertThat(activity.failure?.message).isEqualTo("policy engine denied the request")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a refusal is the vendor's HTTP failure shape with the status and the activity type, never the body`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"code":3,"message":"invalid request: signWith is required"}""")
        )

        val error = assertThrows(TurnkeyHttpFailure::class.java) { submit() }

        assertThat(TurnkeyErrorMapping.turnkeyHttpStatus(error)).isEqualTo(400)
        assertThat(error.message).isEqualTo("HTTP error from ACTIVITY_TYPE_SOL_SEND_TRANSACTION_V2: 400")
        assertThat(error.message).doesNotContain("signWith is required")
        val mapped = TurnkeyErrorMapping.map(error)
        assertThat(mapped).isInstanceOf(RainError.ProviderError::class.java)
        assertThat(mapped.message).doesNotContain("signWith is required")
    }

    @Test
    fun `a 401 reads as the session failure the coordinator refreshes on`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))

        val error = assertThrows(TurnkeyHttpFailure::class.java) { submit() }

        assertThat(TurnkeyErrorMapping.turnkeyHttpStatus(error)).isEqualTo(401)
        assertThat(TurnkeyErrorMapping.map(error)).isInstanceOf(RainError.TokenExpired::class.java)
    }

    /**
     * Turnkey answered 2xx and the connection dropped while the body was still arriving: the send
     * was accepted, and the identical replay is what tells the caller so.
     */
    @Test
    fun `a body cut short after a 2xx replays the identical request`() {
        server.enqueue(
            MockResponse().setBody(completedActivity(V2_RESULT)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        server.enqueue(MockResponse().setBody(completedActivity(V2_RESULT)))

        val activity = submit(body(timestampMs = "1700000000000"))

        assertThat(activity.status).isEqualTo(V1ActivityStatus.ACTIVITY_STATUS_COMPLETED)
        assertThat(activity.result.solSendTransactionResultV2?.sendTransactionStatusId).isEqualTo("status-1")
        assertThat(server.requestCount).isEqualTo(2)
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertThat(second.body.readUtf8()).isEqualTo(first.body.readUtf8())
        assertThat(second.getHeader("X-Stamp")).isEqualTo(first.getHeader("X-Stamp"))
    }

    @Test
    fun `a stalled connection is a lost answer and is replayed`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setBody(completedActivity(V2_RESULT)))
        val impatient = OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).build()

        val activity = runBlocking { request(http = impatient).submit(body()) }

        assertThat(activity.status).isEqualTo(V1ActivityStatus.ACTIVITY_STATUS_COMPLETED)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `only gateway statuses are replayed, a 500 surfaces after one request`() {
        var expectedRequests = 0
        for ((code, replayed) in listOf(500 to false, 502 to true, 503 to true, 504 to true)) {
            repeat(3) { server.enqueue(MockResponse().setResponseCode(code).setBody("upstream")) }

            val error = assertThrows(TurnkeyHttpFailure::class.java) { submit() }

            assertThat(TurnkeyErrorMapping.turnkeyHttpStatus(error)).isEqualTo(code)
            expectedRequests += if (replayed) 3 else 1
            assertThat(server.requestCount).isEqualTo(expectedRequests)
            // Drain what the non-replayed case left enqueued so the next code starts clean.
            if (!replayed) repeat(2) { submitIgnoringFailure() }
            expectedRequests = server.requestCount
        }
    }

    /**
     * The poll reads the activity the same tolerant way as the submit answer, so a value the vendor's
     * model does not know, which every answer for this activity carries, cannot leave a completed
     * send looking pending.
     */
    @Test
    fun `a pending activity the vendor model cannot decode is still polled to its status id`() {
        val votes = """[{"id": "vote-1", "unknownShape": true}]"""
        server.enqueue(MockResponse().setBody(activityJson("ACTIVITY_STATUS_PENDING", result = "{}", votes = votes)))
        server.enqueue(MockResponse().setBody(activityJson("ACTIVITY_STATUS_PENDING", result = "{}", votes = votes)))
        server.enqueue(MockResponse().setBody(activityJson("ACTIVITY_STATUS_COMPLETED", result = V2_RESULT, votes = votes)))

        val activity = submit()

        assertThat(activity.status).isEqualTo(V1ActivityStatus.ACTIVITY_STATUS_COMPLETED)
        assertThat(activity.result.solSendTransactionResultV2?.sendTransactionStatusId).isEqualTo("status-1")
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `a 2xx whose body is not an activity surfaces as one decode failure type`() {
        for (junk in listOf("[]", "null", "<html><body>captive portal</body></html>", """{"activity": []}""")) {
            server.enqueue(MockResponse().setBody(junk))

            assertThrows(kotlinx.serialization.SerializationException::class.java) { submit() }
        }
        assertThat(server.requestCount).isEqualTo(4)
    }

    @Test
    fun `cancelling during the replay backoff leaves as cancellation and posts nothing more`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setBody(completedActivity(V2_RESULT)))
        var thrown: Throwable? = null

        runBlocking {
            val job = launch {
                try {
                    request(replayBackoffMs = 400L).submit(body())
                } catch (t: Throwable) {
                    thrown = t
                    throw t
                }
            }
            awaitRequests(1)
            delay(100)
            job.cancelAndJoin()
        }

        assertThat(thrown).isInstanceOf(CancellationException::class.java)
        server.takeRequest() // the first attempt
        // Twice the backoff is long enough for the replay to have gone out; the cancelled send posts nothing.
        assertThat(server.takeRequest(800, TimeUnit.MILLISECONDS)).isNull()
    }

    @Test
    fun `cancelling during the poll read leaves as cancellation, not a pending activity`() {
        server.enqueue(MockResponse().setBody(activityJson("ACTIVITY_STATUS_PENDING", result = "{}")))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        var thrown: Throwable? = null

        runBlocking {
            val job = launch {
                try {
                    request().submit(body())
                } catch (t: Throwable) {
                    thrown = t
                    throw t
                }
            }
            awaitRequests(2)
            delay(100)
            job.cancelAndJoin()
        }

        assertThat(thrown).isInstanceOf(CancellationException::class.java)
        repeat(2) { server.takeRequest() } // the submit and the poll read that was cancelled
        // The poll interval is zero here, so an uncancelled poll would have read again at once.
        assertThat(server.takeRequest(300, TimeUnit.MILLISECONDS)).isNull()
    }

    /**
     * A redirect means something in front of Turnkey answered. On the client the provider hands the
     * request, the stamped send is never re-posted to wherever the `Location` header points: the 307
     * surfaces as the vendor's HTTP failure shape and the other server sees nothing.
     */
    @Test
    fun `a redirect in front of the send is not followed`() {
        val elsewhere = MockWebServer()
        elsewhere.start()
        try {
            elsewhere.enqueue(MockResponse().setBody(completedActivity(V2_RESULT)))
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", elsewhere.url(SEND_PATH).toString()))

            val error = assertThrows(TurnkeyHttpFailure::class.java) {
                runBlocking { request(http = TurnkeySolanaSendRequest.sendClient(OkHttpClient())).submit(body()) }
            }

            assertThat(TurnkeyErrorMapping.turnkeyHttpStatus(error)).isEqualTo(307)
            assertThat(server.requestCount).isEqualTo(1)
            assertThat(elsewhere.requestCount).isEqualTo(0)
        } finally {
            elsewhere.shutdown()
        }
    }

    /**
     * The decoder's message can quote a slice of the answer, and the answer echoes the voting user's
     * contact details, so the line that reports a partial decode carries the exception's class and its
     * message cut before the quoted input, never the throwable itself.
     */
    @Test
    fun `a partial decode is logged without the answer's body`() {
        val logged = mutableListOf<Triple<Int, String, Throwable?>>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                logged += Triple(priority, message, t)
            }
        }
        Timber.plant(tree)
        try {
            // A structural failure, the kind whose message quotes the input around the offset.
            server.enqueue(
                MockResponse().setBody(
                    activityJson("ACTIVITY_STATUS_COMPLETED", result = V2_RESULT, votes = """{"user": {"userEmail": "person@example.com"}}""")
                )
            )

            val activity = submit()

            assertThat(activity.result.solSendTransactionResultV2?.sendTransactionStatusId).isEqualTo("status-1")
            val (priority, message, throwable) = logged.single { (_, message, _) -> message.contains("did not decode in full") }
            assertThat(priority).isEqualTo(Log.WARN)
            assertThat(throwable).isNull()
            assertThat(message).contains("JsonDecodingException")
            assertThat(message).doesNotContain("JSON input")
            assertThat(message).doesNotContain("userEmail")
        } finally {
            Timber.uproot(tree)
        }
    }

    /**
     * The defect this request exists for. When a vendor release posts the V2 type here, this test
     * fails: delete [TurnkeySolanaSendRequest], its dependency line and this suite, and let the
     * adapter call the vendor's method again.
     */
    @Test
    fun `the vendor client still posts the V1 activity type with the V2 body`() {
        server.enqueue(MockResponse().setBody(completedActivity(V2_RESULT)))

        runBlocking { vendorClient.solSendTransaction(body()) }

        val envelope = JSONObject(server.takeRequest().body.readUtf8())
        assertThat(envelope.getString("type")).isEqualTo(TurnkeySolanaSendRequest.VENDOR_ACTIVITY_TYPE)
        assertThat(envelope.getJSONObject("parameters").has("signWiths")).isTrue()
        assertThat(envelope.getJSONObject("parameters").has("signWith")).isFalse()
    }

    /**
     * The stamp Turnkey verifies: base64url JSON naming the session key and the API-key scheme, with a
     * DER ECDSA P-256 signature over SHA-256 of the exact body bytes that were posted.
     */
    private fun assertStampVerifies(recorded: RecordedRequest, postedBody: String) {
        val header = recorded.getHeader("X-Stamp")
        assertThat(header).isNotNull()
        val stamp = JSONObject(String(Base64.getUrlDecoder().decode(header)))
        assertThat(stamp.getString("publicKey")).isEqualTo(sessionPublicKey)
        assertThat(stamp.getString("scheme")).isEqualTo("SIGNATURE_SCHEME_TK_API_P256")
        val provider = BouncyCastleProvider()
        val spec = ECNamedCurveTable.getParameterSpec("secp256r1")
        val point = spec.curve.decodePoint(hexToBytes(sessionPublicKey))
        val publicKey = KeyFactory.getInstance("EC", provider).generatePublic(ECPublicKeySpec(point, spec))
        val verifier = Signature.getInstance("SHA256withECDSA", provider)
        verifier.initVerify(publicKey)
        verifier.update(postedBody.toByteArray(Charsets.UTF_8))
        assertThat(verifier.verify(hexToBytes(stamp.getString("signature")))).isTrue()
    }

    private fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** The object's keys; the Android `JSONObject` the tests compile against has no `keySet()`. */
    private fun JSONObject.keyList(): List<String> = keys().asSequence().toList()

    private fun completedActivity(result: String) = activityJson("ACTIVITY_STATUS_COMPLETED", result)

    private fun activityJson(status: String, result: String, failure: String = "", votes: String = "[]") =
        """
        {
          "activity": {
            "id": "activity-1",
            "organizationId": "org-1",
            "status": "$status",
            "type": "ACTIVITY_TYPE_SOL_SEND_TRANSACTION_V2",
            "intent": {
              "solSendTransactionIntentV2": {
                "unsignedTransaction": "0100deadbeef",
                "signWiths": ["$SENDER"],
                "sponsor": true,
                "caip2": "solana:devnet",
                "recentBlockhash": "$BLOCKHASH"
              }
            },
            "result": $result,
            $failure
            "votes": $votes,
            "fingerprint": "fp",
            "canApprove": false,
            "canReject": false,
            "createdAt": {"seconds": "1700000000", "nanos": "0"},
            "updatedAt": {"seconds": "1700000000", "nanos": "0"},
            "someFutureField": {"ignored": true}
          }
        }
        """.trimIndent()

    private companion object {
        const val SEND_PATH = "/public/v1/submit/sol_send_transaction"
        const val SENDER = "9C6hybhQ6Aycep9jaUnP6uL9ZYvDjUp1aSkFWPUFJtpj"
        const val BLOCKHASH = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        const val V2_RESULT = """{"solSendTransactionResultV2": {"sendTransactionStatusId": "status-1"}}"""
        const val V1_RESULT = """{"solSendTransactionResult": {"sendTransactionStatusId": "status-1"}}"""
    }
}
