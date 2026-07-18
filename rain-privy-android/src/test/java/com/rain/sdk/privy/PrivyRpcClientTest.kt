package com.rain.sdk.privy

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.error.RainError
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class PrivyRpcClientTest {

    private lateinit var server: MockWebServer
    private val client = PrivyRpcClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        // Some tests shut the server down mid-body; ignore a redundant shutdown.
        runCatching { server.shutdown() }
    }

    private fun url() = server.url("/").toString()

    @Test
    fun `returns the hex result on a well-formed response`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":"0x2a"}"""))
        val result = client.callForHexResult(url(), "eth_getBalance", listOf("0xabc", "latest"))
        assertThat(result).isEqualTo("0x2a")
    }

    @Test
    fun `maps a JSON-RPC error object to InternalError with code and message`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"boom"}}""")
        )
        val error = runCatching {
            client.callForHexResult(url(), "eth_call", emptyList())
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(RainError.InternalError::class.java)
        assertThat(error!!.message).contains("-32000")
        assertThat(error.message).contains("boom")
    }

    @Test
    fun `maps a non-string result to InternalError`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":{"unexpected":true}}"""))
        val error = runCatching {
            client.callForHexResult(url(), "eth_getBalance", emptyList())
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(RainError.InternalError::class.java)
    }

    @Test
    fun `maps a non-JSON body to NetworkError`() = runBlocking {
        server.enqueue(MockResponse().setBody("not json at all"))
        val error = runCatching {
            client.callForHexResult(url(), "eth_getBalance", emptyList())
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(RainError.NetworkError::class.java)
    }

    @Test
    fun `rejects an unparseable RPC url with InvalidRpcUrl`() = runBlocking {
        val error = runCatching {
            client.callForHexResult("not a url", "eth_getBalance", emptyList())
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(RainError.InvalidRpcUrl::class.java)
    }

    @Test
    fun `maps transport failure to NetworkError`() = runBlocking {
        val dead = url()
        server.shutdown() // nothing listening -> connection refused
        val error = runCatching {
            client.callForHexResult(dead, "eth_getBalance", emptyList())
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(RainError.NetworkError::class.java)
    }
}
