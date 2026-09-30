package com.rain.sdk.turnkey

import com.turnkey.http.TurnkeyClient
import com.turnkey.http.Version
import com.turnkey.http.utils.ActivityPollerConfig
import com.turnkey.stamper.Stamper
import com.turnkey.types.Externaldatav1Timestamp
import com.turnkey.types.RpcStatus
import com.turnkey.types.TGetActivityBody
import com.turnkey.types.TSignedRequest
import com.turnkey.types.TSolSendTransactionBody
import com.turnkey.types.V1Activity
import com.turnkey.types.V1ActivityResponse
import com.turnkey.types.V1ActivityStatus
import com.turnkey.types.V1ActivityType
import com.turnkey.types.V1Intent
import com.turnkey.types.V1Result
import com.turnkey.types.V1SolSendTransactionResultV2
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Posts Turnkey's current Solana send activity, `ACTIVITY_TYPE_SOL_SEND_TRANSACTION_V2`, as a stamped
 * request of this module's own, and returns the activity Turnkey records for it.
 *
 * Vendor defect, `com.turnkey:http` 2.1.0 (and tkhq/kotlin-sdk main as of 2026-09-29):
 * `TurnkeyClient.solSendTransaction` posts the activity type `ACTIVITY_TYPE_SOL_SEND_TRANSACTION` with
 * the V2 body that `com.turnkey:types` 2.1.0 serializes (`signWiths`, a list). The V1 intent requires
 * the singular `signWith`, so Turnkey refuses the envelope with HTTP 400 before the transaction is
 * processed, and every Solana send through the vendor's method fails. Turnkey's reference for
 * `/public/v1/submit/sol_send_transaction` lists the V2 type only. This class follows the vendor's
 * `activity()` helper with the type string corrected, and adds what a money-moving request needs
 * that the vendor's helper lacks: an identical replay when the answer is lost, and a tolerant read
 * of the activity that comes back, so a send Turnkey accepted never ends as a failure here.
 *
 * Built from the vendor's public surface only, never its private fields: the endpoint comes from the
 * vendor's own stamp helpers, the stamp of the send from a [Stamper] over the session's key, and the
 * poll from the vendor's stamp helper for `get_activity`.
 *
 * Removing the workaround: the vendor's `solSendTransaction` may replace this class only when it
 * both posts the V2 type and stops throwing on an activity without the V2 result (2.1.0 throws
 * `RuntimeException("No result found ...")` on a pending or failed activity, a failure after
 * acceptance). Until then, keep the class. When a release qualifies, delete: this file;
 * `TurnkeySolanaSendWireTest`; the `kotlinx-serialization-json` line in the module build file and
 * its catalog alias; the `TurnkeySolanaSendRequest` parameter of [TurnkeyClientAdapter]; in
 * `TurnkeyContextAdapter` the four constructor parameters added for this class (`httpClient`,
 * `stamperFor`, `sessionPublicKey`, `vendorClient`), `currentSessionStamper` and the `Stamper`
 * import; the top-level `sessionStamper` with `TurnkeySessionStamperTest` and
 * `TurnkeyContextAdapterTest`; and let the adapter return `client.solSendTransaction(input).activity`.
 * The shared HTTP client the provider owns stays, the manager uses it too; [sendClient] leaves with
 * this class. The wire test named `the vendor client still posts the V1 activity type with the V2
 * body` fails on the release that corrects the type, which is the prompt to check the second
 * condition.
 *
 * @param client the vendor client of the current session, for the endpoint URLs and the poll's stamp.
 * @param stamper produces the stamper for the current session's key on each request, so a key
 *   rotated by a refresh is picked up; the vendor builds its own client from the same call.
 * @param http the client the requests go out on, the provider's shared one with its timeouts and
 *   redirects refused (see [sendClient]); the vendor keeps its own private.
 * @param poller the vendor's interval and retry count for an activity Turnkey answers as pending.
 * @param ioDispatcher where the stamps are computed and the response bodies read, off the caller's
 *   thread as in the vendor's helper; hosts call from the main thread.
 * @param replayBackoffMs the wait before an identical replay; tests pass zero.
 */
@Suppress("LongParameterList") // one seam per collaborator, each with its production default
internal class TurnkeySolanaSendRequest(
    private val client: TurnkeyClient,
    private val stamper: suspend () -> Stamper,
    private val http: OkHttpClient,
    private val poller: ActivityPollerConfig = ActivityPollerConfig(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val replayBackoffMs: Long = REPLAY_BACKOFF_MS,
) {

    /** An HTTP answer once fully read: the status and the body text. */
    private class Answer(val code: Int, val body: String) {
        val isSuccessful: Boolean get() = code in SUCCESS_STATUSES
    }

    /**
     * Submits [input] and returns the activity: completed, failed or rejected when Turnkey settled it
     * within the poll, else the last state read. Throws [TurnkeyHttpFailure] when Turnkey refuses
     * the request, with the status and the activity type and never the response body in its message,
     * the shape the session coordinator and the error mapping already classify (401 refreshes the
     * session and retries; a refusal happens before anything is executed, so the retry is safe).
     *
     * Two things keep an accepted send from surfacing as a failure. A request whose answer is lost,
     * a dropped connection before or during the answer or a gateway status, is replayed byte for
     * byte, same body, same timestamp, same stamp: Turnkey's submission API is idempotent on the
     * body's fingerprint, so the replay returns the activity the first attempt created, or creates
     * the one it never did, and never a second send. The replay is bounded ([REPLAY_ATTEMPTS] after
     * the first attempt); past it the last failure surfaces, as it would have through the vendor's
     * client. An activity Turnkey answers with that no longer decodes in full (a value the vendor's
     * models do not know, in the echoed voter for example) is read for the fields the send needs,
     * on the submit answer and on every poll read alike. A poll read that fails after acceptance
     * returns the accepted activity as last seen, and the caller treats it as pending.
     */
    suspend fun submit(input: TSolSendTransactionBody): V1Activity {
        // The session's stamper first: a session that cannot sign fails here, before any other work.
        val sessionStamper = stamper()
        val body = envelope(input)
        // Both stamps are EC arithmetic, so they run off the caller's thread. The first, the vendor's
        // own stamp helper, is computed for its URL alone: the client keeps its base URL private, and
        // the helper is the one public member that reports the endpoint as the client would address
        // it, whatever base URL the host configured. The stamp it computes covers the defective
        // envelope and is dropped; one local signature per send is the price of not restating the
        // vendor's default.
        val (url, stamp) = withContext(ioDispatcher) {
            client.stampSolSendTransaction(input).url to sessionStamper.stamp(body)
        }
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header(stamp.first, stamp.second)
            .header(CLIENT_VERSION_HEADER, Version.VERSION)
            .build()

        val answer = post(request)
        if (!answer.isSuccessful) {
            // The status names what a host can act on; the body names the validation that failed and
            // stays in the debug log, as the module keeps every vendor body out of hosts' reach.
            Timber.w("Rain SDK: wallet backend refused the Solana send with HTTP %d", answer.code)
            Timber.d("Rain SDK: the refusal's body: %s", answer.body.take(TurnkeySendFailures.MAX_VENDOR_MESSAGE_LENGTH))
            throw TurnkeyHttpFailure(answer.code, ACTIVITY_TYPE)
        }
        val initial = decodeActivity(answer.body, input.organizationId)
        return if (initial.status in TERMINAL_STATUSES) initial else pollToSettled(initial)
    }

    /**
     * The activity envelope the vendor's `activity()` helper builds, with the V2 type: every field of
     * the typed body except the organization id and the timestamp under `parameters`, then those two
     * and the type at the top level. Encoding the typed body keeps the parameter set the vendor's
     * types model, so a field the vendor adds to it flows through unchanged.
     */
    private fun envelope(input: TSolSendTransactionBody): String {
        val fields = JSON.encodeToJsonElement(TSolSendTransactionBody.serializer(), input).jsonObject
        val parameters = buildJsonObject {
            fields.filterKeys { it != ORGANIZATION_ID && it != TIMESTAMP_MS }.forEach { (key, value) -> put(key, value) }
        }
        val body = buildJsonObject {
            put(PARAMETERS, parameters)
            put(ORGANIZATION_ID, JsonPrimitive(input.organizationId))
            put(TIMESTAMP_MS, JsonPrimitive(input.timestampMs ?: System.currentTimeMillis().toString()))
            put(TYPE, JsonPrimitive(ACTIVITY_TYPE))
        }
        return JSON.encodeToString(JsonObject.serializer(), body)
    }

    /**
     * Sends [request] and returns the first fully read answer that is not a gateway status. A
     * connection that fails before the answer or while the body is still arriving, or a gateway
     * status, leaves the send's fate unknown: the same request is replayed, up to [REPLAY_ATTEMPTS]
     * times after [replayBackoffMs], which by Turnkey's body-fingerprint idempotency returns the
     * same activity. The caller's cancellation is not a lost answer: it leaves as itself from the
     * suspension. When the replays run out, the last failure is what the caller sees, as it would
     * have been through the vendor's client.
     */
    private suspend fun post(request: Request): Answer {
        var attempt = 1
        while (true) {
            var lost: IOException? = null
            val answer = try {
                read(http.newCall(request).await())
            } catch (e: IOException) {
                if (attempt > REPLAY_ATTEMPTS) throw e
                lost = e
                null
            }
            if (answer != null && (answer.code !in GATEWAY_STATUSES || attempt > REPLAY_ATTEMPTS)) return answer
            Timber.w(
                lost,
                "Rain SDK: no answer to the Solana send request (%s); replaying the identical request, %d of %d",
                answer?.code?.toString() ?: "connection failed",
                attempt,
                REPLAY_ATTEMPTS,
            )
            attempt++
            delay(replayBackoffMs)
        }
    }

    /** Reads and closes [response]; the body read is I/O and runs on [ioDispatcher]. */
    private suspend fun read(response: Response): Answer =
        response.use { Answer(it.code, withContext(ioDispatcher) { it.body.string() }) }

    /**
     * The activity in Turnkey's answer. The vendor's full model first; when that no longer decodes
     * (Turnkey echoes the voting user with its authenticators and keys, whose enumerations grow), the
     * fields the send reads are taken from the JSON tree instead, so a value this build does not know
     * cannot turn an accepted send into a failure. An answer without an activity id is the one thing
     * left to surface, as a [SerializationException] whatever its shape, because nothing can be
     * tracked without it.
     */
    private fun decodeActivity(text: String, organizationId: String): V1Activity = try {
        JSON.decodeFromString(V1ActivityResponse.serializer(), text).activity
    } catch (e: SerializationException) {
        // No throwable in this log line: the decoder's message can quote a slice of the answer, and the
        // answer echoes the voting user's contact details. The class and the message before the quote
        // name the field that failed.
        Timber.w(
            "Rain SDK: the wallet backend's Solana send activity did not decode in full (%s: %s); reading the fields the send needs",
            e.javaClass.simpleName,
            e.message?.substringBefore(JSON_INPUT_MARKER),
        )
        essentialActivity(text, organizationId)
    }

    private fun essentialActivity(text: String, organizationId: String): V1Activity {
        val activity = (JSON.parseToJsonElement(text) as? JsonObject)?.get(ACTIVITY) as? JsonObject
            ?: throw SerializationException("the wallet backend's answer to the Solana send carries no activity")
        val id = activity.string(ID) ?: throw SerializationException("the wallet backend's Solana send activity carries no id")
        val statusId = activity.string(RESULT, RESULT_V2, SEND_TRANSACTION_STATUS_ID)
            ?: activity.string(RESULT, RESULT_V1, SEND_TRANSACTION_STATUS_ID)
        val status = activity.string(STATUS)?.let { name -> V1ActivityStatus.entries.firstOrNull { it.name == name } }
            ?: V1ActivityStatus.ACTIVITY_STATUS_PENDING
        return V1Activity(
            canApprove = false,
            canReject = false,
            createdAt = Externaldatav1Timestamp(nanos = "0", seconds = "0"),
            failure = activity.string(FAILURE, MESSAGE)?.let { RpcStatus(message = it) },
            fingerprint = activity.string(FINGERPRINT).orEmpty(),
            id = id,
            intent = V1Intent(),
            organizationId = activity.string(ORGANIZATION_ID) ?: organizationId,
            result = V1Result(solSendTransactionResultV2 = statusId?.let { V1SolSendTransactionResultV2(sendTransactionStatusId = it) }),
            status = status,
            type = V1ActivityType.ACTIVITY_TYPE_SOL_SEND_TRANSACTION_V2,
            updatedAt = Externaldatav1Timestamp(nanos = "0", seconds = "0"),
            votes = emptyList(),
        )
    }

    /** The string primitive at [path] below this object, or null when any step is missing or not a string. */
    private fun JsonObject.string(vararg path: String): String? {
        val element = path.fold(this as JsonElement?) { current, key -> (current as? JsonObject)?.get(key) }
        return (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }

    /**
     * The vendor's poll: a fixed wait and a read of the activity, `numRetries + 1` times, then one
     * last read without a wait, and the last state read is returned whether or not it settled.
     * Turnkey has accepted the activity by now, so a read that fails ends the poll, not the send: the
     * activity is returned as last seen and the caller reports it pending on its id.
     */
    private suspend fun pollToSettled(accepted: V1Activity): V1Activity {
        var latest = accepted
        var read = 0
        while (latest.status !in TERMINAL_STATUSES && read <= poller.numRetries + 1) {
            if (read <= poller.numRetries) delay(poller.intervalMs)
            latest = readBack(accepted) ?: return latest
            read++
        }
        return latest
    }

    /**
     * One `get_activity` read of [accepted], stamped by the vendor's own stamp helper (the vendor
     * client's key) and decoded with the same tolerance as the submit answer, so a value the vendor's
     * model does not know cannot leave a completed send looking pending. Null when the read failed or
     * Turnkey refused it; the caller stops polling.
     */
    @Suppress("TooGenericExceptionCaught") // a failed read after acceptance ends the poll, never the accepted send
    private suspend fun readBack(accepted: V1Activity): V1Activity? = try {
        val signed = withContext(ioDispatcher) {
            client.stampGetActivity(TGetActivityBody(organizationId = accepted.organizationId, activityId = accepted.id))
        }
        val answer = read(http.newCall(signed.toRequest()).await())
        if (answer.isSuccessful) {
            decodeActivity(answer.body, accepted.organizationId)
        } else {
            Timber.w("Rain SDK: wallet backend accepted the Solana send, but reading the activity back met HTTP %d", answer.code)
            null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "Rain SDK: wallet backend accepted the Solana send, but reading the activity back failed")
        null
    }

    /** The request the vendor's stamp helper described: its URL, body and stamp header, plus the vendor's client version. */
    private fun TSignedRequest.toRequest(): Request = Request.Builder()
        .url(url)
        .post(body.toRequestBody(JSON_MEDIA_TYPE))
        .header(stamp.stampHeaderName, stamp.stampHeaderValue)
        .header(CLIENT_VERSION_HEADER, Version.VERSION)
        .build()

    /**
     * The vendor's callback bridge, with one difference: a response that arrives after the caller
     * cancelled is closed instead of dropped, so its connection returns to the pool. OkHttp calls
     * back exactly once, and a resume after cancellation is discarded by the continuation.
     */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { response.close() }
                }
            }
        )
        continuation.invokeOnCancellation { runCatching { cancel() } }
    }

    internal companion object {
        /** The type Turnkey's reference lists for `/public/v1/submit/sol_send_transaction`. */
        const val ACTIVITY_TYPE = "ACTIVITY_TYPE_SOL_SEND_TRANSACTION_V2"

        /** The type the vendor's 2.1.0 client posts, with the V2 body; the wire test pins the defect on it. */
        const val VENDOR_ACTIVITY_TYPE = "ACTIVITY_TYPE_SOL_SEND_TRANSACTION"

        /** The states the vendor's client stops polling at; anything else is still in flight. */
        val TERMINAL_STATUSES: Set<V1ActivityStatus> = setOf(
            V1ActivityStatus.ACTIVITY_STATUS_COMPLETED,
            V1ActivityStatus.ACTIVITY_STATUS_FAILED,
            V1ActivityStatus.ACTIVITY_STATUS_REJECTED,
        )

        /** Identical replays of a send whose answer was lost, after the first attempt. */
        const val REPLAY_ATTEMPTS = 2
        const val REPLAY_BACKOFF_MS = 500L

        /** Answers from in front of Turnkey, not from it: the request may or may not have arrived. */
        val GATEWAY_STATUSES: Set<Int> = setOf(502, 503, 504)

        /**
         * The client the send goes out on, derived from the provider's shared [client]: the same pool,
         * dispatcher and timeouts, with redirects refused, so a stamped send is never re-posted to
         * wherever a `Location` header points (a redirect means something in front of Turnkey
         * answered, and OkHttp re-sends a POST body on 307 and 308). The provider's other calls, the
         * RPC reads, keep following redirects as core's clients do.
         */
        fun sendClient(client: OkHttpClient): OkHttpClient =
            client.newBuilder().followRedirects(false).followSslRedirects(false).build()

        // The vendor's configuration: unknown fields in a response are ignored, absent optionals
        // omitted. One instance: the request object is rebuilt per call, the parser need not be.
        private val JSON = Json { ignoreUnknownKeys = true }

        private val SUCCESS_STATUSES = 200..299
        private const val PARAMETERS = "parameters"
        private const val ORGANIZATION_ID = "organizationId"
        private const val TIMESTAMP_MS = "timestampMs"
        private const val TYPE = "type"
        private const val ACTIVITY = "activity"
        private const val ID = "id"
        private const val STATUS = "status"
        private const val RESULT = "result"
        private const val RESULT_V2 = "solSendTransactionResultV2"
        private const val RESULT_V1 = "solSendTransactionResult"
        private const val SEND_TRANSACTION_STATUS_ID = "sendTransactionStatusId"
        private const val FAILURE = "failure"
        private const val MESSAGE = "message"
        private const val FINGERPRINT = "fingerprint"
        private const val CLIENT_VERSION_HEADER = "X-Client-Version"

        /** Where kotlinx's decoding exception starts quoting the input it failed on. */
        private const val JSON_INPUT_MARKER = "\nJSON input:"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
