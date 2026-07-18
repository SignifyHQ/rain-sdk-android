package com.rain.sdk.internal.helpers

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Thin wrapper around [MockWebServer] that dispatches JSON-RPC requests by `method` name.
 *
 * Tests register per-method stubs (a result value or a forced network failure) and the
 * server replies accordingly. Records every RPC method that has been called so tests can
 * assert which calls were made and in what order.
 *
 * Usage:
 * ```
 * val rpc = MockRpcServer().also { it.start() }
 * rpc.stub(method = "eth_estimateGas", result = "0x5208")
 * rpc.stubNetworkFailure(method = "eth_call")
 * val provider = TurnkeyWalletProvider(rpcEndpoints = mapOf(1 to rpc.urlFor(1)), ...)
 * ...
 * rpc.shutdown()
 * ```
 *
 * Because [MockWebServer] is HTTP and the provider needs a URL keyed by chainId, callers
 * pass [rpc.urlFor(chainId)] into their `rpcEndpoints` map.
 */
internal class MockRpcServer {
    private val server = MockWebServer()

    private data class Stub(val result: Any? = null, val networkFailure: Boolean = false)

    private val stubs = ConcurrentHashMap<String, Stub>()
    private val recorded = mutableListOf<String>()

    fun start() {
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                val method = runCatching { JSONObject(body).optString("method", "") }.getOrDefault("")
                synchronized(recorded) { recorded += method }

                val stub = stubs[method]
                    ?: return MockResponse().setResponseCode(404).setBody(
                        """{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"unstubbed method $method"}}"""
                    )

                if (stub.networkFailure) {
                    // Force a network-level failure: drop the socket so OkHttp surfaces an IOException.
                    return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
                }

                val payload = JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", 1)
                    if (stub.result is Map<*, *> || stub.result is JSONObject) put("result", stub.result)
                    else put("result", stub.result ?: JSONObject.NULL)
                }
                return MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(payload.toString())
            }
        }
    }

    fun shutdown() {
        server.shutdown()
    }

    /**
     * Returns the server URL to use as the RPC endpoint for [chainId]. The wallet provider
     * just sends POSTs to this URL; the dispatcher routes them by JSON-RPC method.
     */
    fun urlFor(chainId: Int): String = server.url("/v1/chain/$chainId").toString()

    /** Stub a successful response for [method]. [result] is placed under the `result` key. */
    fun stub(method: String, result: String) {
        stubs[method] = Stub(result = result)
    }

    /**
     * Stub a successful response whose `result` is an arbitrary JSON value — a [JSONObject],
     * [org.json.JSONArray], or a primitive. Used for Solana RPC, whose results are objects /
     * arrays / numbers rather than the hex strings EVM returns.
     */
    fun stubObject(method: String, result: Any) {
        stubs[method] = Stub(result = result)
    }

    /**
     * Stub a network failure for [method]. The server disconnects the socket so OkHttp
     * surfaces an `IOException` to the caller — used to drive the `RainError.NetworkError`
     * code path in [com.rain.sdk.internal.provider.TurnkeyWalletProvider].
     */
    fun stubNetworkFailure(method: String) {
        stubs[method] = Stub(networkFailure = true)
    }

    /** Methods recorded in dispatch order. Reset by [resetRecordings]. */
    val recordedMethods: List<String>
        get() = synchronized(recorded) { recorded.toList() }

    fun resetRecordings() {
        synchronized(recorded) { recorded.clear() }
    }
}
