package com.rain.sdk.internal.provider

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.helpers.MockChainReader
import com.rain.sdk.internal.helpers.assumeJdk24
import com.rain.sdk.models.RainTransactionOrder
import com.rain.sdk.models.Token
import com.turnkey.core.models.Wallet
import com.turnkey.types.V1AssetBalance
import com.turnkey.types.V1HashFunction
import com.turnkey.types.V1PayloadEncoding
import com.turnkey.types.V1SignRawPayloadResult
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class TurnkeyWalletProviderTest {

    /**
     * The Turnkey Kotlin SDK is published with class-file major version 68 (Java 24).
     * Skip Turnkey-dependent tests on JVMs older than 24 to avoid spurious
     * UnsupportedClassVersionError failures. Android production builds are unaffected
     * (R8/D8 dexes Turnkey's bytecode regardless of host JVM version).
     */
    @Before
    fun requireJdk24() = assumeJdk24()

    private fun makeProvider(
        turnkey: MockTurnkey = MockTurnkey(),
        walletAddressOverride: String? = null,
        rpcEndpoints: Map<Int, String> = mapOf(1 to "https://eth.example/rpc")
    ): TurnkeyWalletProvider = TurnkeyWalletProvider(
        turnkey = turnkey,
        rpcEndpoints = rpcEndpoints,
        walletAddressOverride = walletAddressOverride,
        httpClient = OkHttpClient(),
        // Inject a mock reader so unknown-token enrichment never hits the network.
        chainReader = MockChainReader()
    )

    @Test
    fun `getAddress returns override when provided`() = runBlocking {
        val provider = makeProvider(walletAddressOverride = "0xOVERRIDE")
        assertThat(provider.getAddress()).isEqualTo("0xOVERRIDE")
    }

    @Test
    fun `getAddress returns first ethereum account from wallets`() = runBlocking {
        val provider = makeProvider()
        assertThat(provider.getAddress()).isEqualTo(MockTurnkey.DEFAULT_WALLET_ADDRESS)
    }

    @Test
    fun `getAddress refreshes wallets when initial list has no ethereum account`() = runBlocking {
        val turnkey = MockTurnkey(wallets = emptyList())
        var refreshTriggered = false
        val withRefresh = object : TurnkeyContextProtocol by turnkey {
            override suspend fun refreshWallets() {
                refreshTriggered = true
                turnkey.wallets = listOf(MockTurnkey.defaultWallet())
            }
        }
        val provider = TurnkeyWalletProvider(
            turnkey = withRefresh,
            rpcEndpoints = mapOf(1 to "https://eth.example/rpc"),
            walletAddressOverride = null,
            httpClient = OkHttpClient()
        )
        val addr = provider.getAddress()
        assertThat(refreshTriggered).isTrue()
        assertThat(addr).isEqualTo(MockTurnkey.DEFAULT_WALLET_ADDRESS)
    }

    @Test
    fun `getAddress throws WalletUnavailable when no ethereum wallet exists`() {
        val turnkey = MockTurnkey(wallets = emptyList())
        val provider = makeProvider(turnkey = turnkey)
        assertThrows(RainError.WalletUnavailable::class.java) {
            runBlocking { provider.getAddress() }
        }
    }

    @Test
    fun `signTypedData passes EIP712 encoding and NO_OP hash through to turnkey`() = runBlocking {
        val turnkey = MockTurnkey()
        val provider = makeProvider(turnkey = turnkey)

        val typed = """{"types":{}}"""
        provider.signTypedData(chainId = 1, walletAddress = "0xabc", typedDataJson = typed)

        assertThat(turnkey.signRawPayloadCalls).hasSize(1)
        val call = turnkey.signRawPayloadCalls.single()
        assertThat(call.signWith).isEqualTo("0xabc")
        assertThat(call.payload).isEqualTo(typed)
        assertThat(call.encoding).isEqualTo(V1PayloadEncoding.PAYLOAD_ENCODING_EIP712)
        assertThat(call.hashFunction).isEqualTo(V1HashFunction.HASH_FUNCTION_NO_OP)
    }

    @Test
    fun `signTypedData formats signature as 0x-prefixed 65 bytes`() = runBlocking {
        val turnkey = MockTurnkey(
            mockSignature = V1SignRawPayloadResult(
                r = "1".repeat(64),
                s = "2".repeat(64),
                v = "1c" // 28 in hex
            )
        )
        val provider = makeProvider(turnkey = turnkey)

        val signature = provider.signTypedData(1, "0xabc", "{}")

        assertThat(signature).startsWith("0x")
        // 2 (prefix) + 64 (r) + 64 (s) + 2 (v) = 132
        assertThat(signature).hasLength(132)
        assertThat(signature.takeLast(2)).isEqualTo("1c")
    }

    @Test
    fun `signTypedData normalizes recovery id below 27 by adding 27`() = runBlocking {
        val turnkey = MockTurnkey(
            mockSignature = V1SignRawPayloadResult(
                r = "1".repeat(64),
                s = "2".repeat(64),
                v = "00" // raw 0 should become 27 (0x1b)
            )
        )
        val provider = makeProvider(turnkey = turnkey)

        val signature = provider.signTypedData(1, "0xabc", "{}")
        assertThat(signature.takeLast(2)).isEqualTo("1b")
    }

    @Test
    fun `getBalance native parses slip44 native asset from balances`() = runBlocking {
        val turnkey = MockTurnkey()
        val client = MockTurnkeyClient(
            mockBalances = listOf(
                V1AssetBalance(
                    balance = "1500000000000000000", // 1.5 ETH in wei
                    caip19 = "eip155:1/slip44:60",
                    decimals = 18L,
                    display = null,
                    name = "Ethereum",
                    symbol = "ETH"
                ),
                V1AssetBalance(
                    balance = "100000000",
                    caip19 = "eip155:1/erc20:0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48",
                    decimals = 6L,
                    display = null,
                    name = "USDC",
                    symbol = "USDC"
                )
            )
        )
        turnkey.turnkeyClient = client
        val provider = makeProvider(turnkey = turnkey)

        val balance = provider.getBalance(chainId = 1, token = Token.Native)
        assertThat(balance.token).isEqualTo(Token.Native)
        assertThat(balance.rawAmount).isEqualTo(java.math.BigInteger("1500000000000000000"))
        assertThat(balance.decimalAmount.toDouble()).isWithin(1e-9).of(1.5)
    }

    @Test
    fun `getBalances maps token addresses to balances and includes native`() = runBlocking {
        val turnkey = MockTurnkey()
        val client = MockTurnkeyClient(
            mockBalances = listOf(
                V1AssetBalance(
                    balance = "1500000000000000000",
                    caip19 = "eip155:1/slip44:60",
                    decimals = 18L,
                    display = null, name = null, symbol = null
                ),
                V1AssetBalance(
                    balance = "100500000",
                    caip19 = "eip155:1/erc20:0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48",
                    decimals = 6L,
                    display = null, name = null, symbol = null
                ),
                V1AssetBalance(
                    balance = "2000000000000000000",
                    caip19 = "eip155:1/erc20:0x6b175474e89094c44da98b954eedeac495271d0f",
                    decimals = 18L,
                    display = null, name = null, symbol = null
                )
            )
        )
        turnkey.turnkeyClient = client
        val provider = makeProvider(turnkey = turnkey)

        val balances = provider.getBalances(chainId = 1)
        // Native + 2 ERC-20s.
        assertThat(balances).hasSize(3)
        assertThat(balances.any { it.token is Token.Native }).isTrue()

        val usdc = balances.single { it.token == Token.Contract("0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48") }
        assertThat(usdc.decimalAmount.toDouble()).isWithin(1e-6).of(100.5)

        val dai = balances.single { it.token == Token.Contract("0x6b175474e89094c44da98b954eedeac495271d0f") }
        assertThat(dai.decimalAmount.toDouble()).isWithin(1e-9).of(2.0)
    }

    @Test
    fun `getTransactions filters by chainId, sorts DESC and applies limit_offset`() = runBlocking {
        val turnkey = MockTurnkey()
        val orgId = MockTurnkey.DEFAULT_ORG_ID
        val activities = (1..5).map { i ->
            MockTurnkey.makeActivity(
                id = "act-$i",
                from = "0xfrom",
                to = "0xto-$i",
                caip2 = "eip155:1",
                value = "0",
                data = "0x",
                sendTransactionStatusId = "sid-$i"
            ).copy(
                createdAt = com.turnkey.types.Externaldatav1Timestamp(
                    nanos = "0",
                    seconds = (1_700_000_000 + i).toString()
                )
            )
        } + MockTurnkey.makeActivity(
            id = "other-chain",
            from = "0xfrom",
            to = "0xto",
            caip2 = "eip155:43114",
            value = "0",
            data = "0x",
            sendTransactionStatusId = "ignored"
        )

        val client = MockTurnkeyClient(mockActivities = activities)
        turnkey.turnkeyClient = client
        val provider = makeProvider(turnkey = turnkey)

        val result = provider.getTransactions(
            chainId = 1,
            limit = 2,
            offset = 1,
            order = RainTransactionOrder.DESC
        )

        assertThat(result.transactions).hasSize(2)
        // DESC sort: newest first; act-5 has the largest seconds. With offset=1 we drop the newest,
        // so the visible window is act-4, act-3.
        assertThat(result.transactions[0].chainId).isEqualTo("1")
        assertThat(client.getActivitiesCalls).hasSize(1)
        assertThat(client.getActivitiesCalls.single().organizationId).isEqualTo(orgId)
    }

    @Test
    fun `sendTransaction throws TokenExpired when session missing`() {
        val turnkey = MockTurnkey(session = null)
        val provider = makeProvider(turnkey = turnkey)
        assertThrows(RainError.TokenExpired::class.java) {
            runBlocking {
                provider.sendTransaction(
                    chainId = 1,
                    from = "0xabc",
                    to = "0xdef",
                    data = "0x",
                    value = "0x0"
                )
            }
        }
    }
}
