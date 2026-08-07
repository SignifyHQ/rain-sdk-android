package com.rain.sdk.internal.core

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.RainAuthPullChains
import com.rain.sdk.RainChain
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.helpers.MockChainReader
import com.rain.sdk.internal.helpers.StubWalletProvider
import com.rain.sdk.internal.helpers.TestFixtures
import com.rain.sdk.internal.helpers.TestManagers
import com.rain.sdk.models.RainTokenAllowance
import com.rain.sdk.models.TokenInfo
import java.math.BigDecimal
import java.math.BigInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Manager-level contract for the Auth Pull approval surface: what gets encoded, what gets
 * broadcast, what is read back, and which failures surface as which typed error.
 */
class RainSdkManagerApprovalTest {

    // A sandbox Auth Pull chain: the manager defaults to the sandbox chain set, and approvals off
    // that set are rejected before anything is encoded.
    private val chainId = RainChain.BASE_SEPOLIA
    private val usdc = TestFixtures.USDC_ADDRESS
    private val spender = "0x5a6E6b0d5Ea051CfFF9b3dcC2Aa8Dac226458f29"
    private val unknownToken = "0x000000000000000000000000000000000000dEaD"

    private fun usdcInfo() = TokenInfo(chainId, usdc, "USDC", 6, "USDC")

    private fun rpcFailure() = RainError.NetworkError("rpc down")

    // ---- approveTokenAllowance ----------------------------------------------------

    @Test
    fun `an omitted amount approves uint256 max`(): Unit = runBlocking {
        val (manager, stub, _) = TestManagers.approvalManager()
        stub.sendTransactionHashToReturn = "0xapprovalhash"

        val result = manager.approveTokenAllowance(chainId, usdc, spender)

        assertThat(result.transactionHash).isEqualTo("0xapprovalhash")
        val sent = stub.sendTransactionCalls.single()
        assertThat(sent.chainId).isEqualTo(chainId)
        assertThat(sent.to).isEqualTo(usdc)
        assertThat(sent.from).isEqualTo(TestFixtures.WALLET_ADDRESS)
        assertThat(sent.value).isEqualTo("0x0")
        assertThat(sent.data).isEqualTo(
            "0x095ea7b3" +
                "0000000000000000000000005a6e6b0d5ea051cfff9b3dcc2aa8dac226458f29" +
                "f".repeat(64)
        )
    }

    @Test
    fun `an unlimited approval needs no decimals lookup`(): Unit = runBlocking {
        val (manager, _, reader) = TestManagers.approvalManager()

        manager.approveTokenAllowance(chainId, usdc, spender)

        assertThat(reader.decimalsCalls).isEmpty()
    }

    @Test
    fun `a decimal amount is scaled by the token's decimals`(): Unit = runBlocking {
        val (manager, stub, _) = TestManagers.approvalManager(seedTokens = listOf(usdcInfo()))

        manager.approveTokenAllowance(chainId, usdc, spender, BigDecimal("250.5"))

        // 250.5 USDC at 6 decimals = 250_500_000 = 0xEEE53A0.
        assertThat(stub.sendTransactionCalls.single().data).endsWith("0eee53a0")
    }

    @Test
    fun `caller-supplied decimals win over the registry`(): Unit = runBlocking {
        val (manager, stub, reader) = TestManagers.approvalManager(seedTokens = listOf(usdcInfo()))

        manager.approveTokenAllowance(chainId, usdc, spender, BigDecimal.ONE, decimals = 18)

        assertThat(stub.sendTransactionCalls.single().data)
            .isEqualTo(
                "0x095ea7b3" +
                    "0000000000000000000000005a6e6b0d5ea051cfff9b3dcc2aa8dac226458f29" +
                    "0000000000000000000000000000000000000000000000000de0b6b3a7640000"
            )
        assertThat(reader.decimalsCalls).isEmpty()
    }

    @Test
    fun `a zero amount is a revoke, not an invalid amount`(): Unit = runBlocking {
        val (manager, stub, _) = TestManagers.approvalManager(seedTokens = listOf(usdcInfo()))

        val result = manager.approveTokenAllowance(chainId, usdc, spender, BigDecimal.ZERO)

        assertThat(result.transactionHash).isNotEmpty()
        assertThat(stub.sendTransactionCalls.single().data).endsWith("0".repeat(64))
    }

    // ---- validation ---------------------------------------------------------------

    @Test
    fun `a negative amount is rejected before any provider call`(): Unit = runBlocking {
        val (manager, stub, _) = TestManagers.approvalManager(seedTokens = listOf(usdcInfo()))

        assertThrows(RainError.InvalidAmount::class.java) {
            runBlocking { manager.approveTokenAllowance(chainId, usdc, spender, BigDecimal("-1")) }
        }
        assertThat(stub.sendTransactionCalls).isEmpty()
    }

    @Test
    fun `an amount finer than the token's scale is rejected, not truncated`(): Unit = runBlocking {
        val (manager, stub, _) = TestManagers.approvalManager(seedTokens = listOf(usdcInfo()))

        assertThrows(RainError.InvalidAmount::class.java) {
            runBlocking {
                manager.approveTokenAllowance(chainId, usdc, spender, BigDecimal("1.2345678"))
            }
        }
        assertThat(stub.sendTransactionCalls).isEmpty()
    }

    @Test
    fun `a malformed spender is rejected before any provider call`(): Unit = runBlocking {
        val (manager, stub, _) = TestManagers.approvalManager()

        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { manager.approveTokenAllowance(chainId, usdc, "not-an-address") }
        }
        assertThat(stub.sendTransactionCalls).isEmpty()
    }

    @Test
    fun `a malformed token contract is rejected before any provider call`(): Unit = runBlocking {
        val (manager, stub, _) = TestManagers.approvalManager()

        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { manager.approveTokenAllowance(chainId, "0x1234", spender) }
        }
        assertThat(stub.sendTransactionCalls).isEmpty()
    }

    @Test
    fun `a non-positive chain id is rejected`(): Unit = runBlocking {
        val (manager, _, _) = TestManagers.approvalManager()

        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { manager.approveTokenAllowance(0, usdc, spender) }
        }
    }

    @Test
    fun `a Solana chain id is rejected instead of taking the EVM path`(): Unit = runBlocking {
        val (manager, stub, _) = TestManagers.approvalManager()

        assertThrows(RainError.InternalError::class.java) {
            runBlocking { manager.approveTokenAllowance(RainChain.SOLANA_DEVNET, usdc, spender) }
        }
        assertThat(stub.sendTransactionCalls).isEmpty()
    }

    // ---- environment / chain pairing ----------------------------------------------

    @Test
    fun `a production chain is rejected on a sandbox-configured client`(): Unit = runBlocking {
        val (manager, stub, _) = TestManagers.approvalManager()

        // Base mainnet is an Auth Pull chain, but not this environment's — approving here would
        // mine a real mainnet allowance for the sandbox operator.
        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { manager.approveTokenAllowance(RainChain.BASE_MAINNET, usdc, spender) }
        }
        assertThat(stub.sendTransactionCalls).isEmpty()
    }

    @Test
    fun `a sandbox chain is rejected on a production-configured client`(): Unit = runBlocking {
        val (manager, stub, _) = TestManagers.approvalManager(
            authPullChainIds = RainAuthPullChains.PRODUCTION
        )

        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { manager.approveTokenAllowance(RainChain.BASE_SEPOLIA, usdc, spender) }
        }
        assertThat(stub.sendTransactionCalls).isEmpty()
    }

    @Test
    fun `a non-Auth-Pull chain is rejected even though it is a valid EVM chain`(): Unit =
        runBlocking {
            val (manager, stub, _) = TestManagers.approvalManager()

            assertThrows(RainError.InvalidConfig::class.java) {
                runBlocking { manager.approveTokenAllowance(1, usdc, spender) }
            }
            assertThat(stub.sendTransactionCalls).isEmpty()
        }

    @Test
    fun `the allowance read applies the same environment gate`(): Unit = runBlocking {
        val (manager, _, reader) = TestManagers.approvalManager()

        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { manager.getTokenAllowance(RainChain.BASE_MAINNET, usdc, spender) }
        }
        assertThat(reader.allowanceCalls).isEmpty()
    }

    @Test
    fun `every production Auth Pull chain is accepted on a production client`(): Unit =
        runBlocking {
            for (chain in RainAuthPullChains.PRODUCTION) {
                val (manager, stub, _) = TestManagers.approvalManager(
                    authPullChainIds = RainAuthPullChains.PRODUCTION
                )
                stub.sendTransactionHashToReturn = "0xok"

                val result = manager.approveTokenAllowance(chain, usdc, spender)

                assertThat(result.transactionHash).isEqualTo("0xok")
            }
        }

    // ---- decimals must never be guessed -------------------------------------------

    @Test
    fun `an unresolvable decimals refuses to guess rather than over-approving`(): Unit =
        runBlocking {
            // Not in the registry, and `decimals()` reverts. Guessing 18 against a 6-decimal token
            // would approve 10^12 times the requested amount, and `approve` has no balance to fail
            // against.
            val reader = MockChainReader(metadataError = rpcFailure())
            val (manager, stub, _) = TestManagers.approvalManager(reader = reader)

            assertThrows(RainError.TokenNotFound::class.java) {
                runBlocking {
                    manager.approveTokenAllowance(chainId, unknownToken, spender, BigDecimal("250"))
                }
            }
            assertThat(stub.sendTransactionCalls).isEmpty()
        }

    @Test
    fun `an unlimited approval still needs no decimals, even for an unknown token`(): Unit =
        runBlocking {
            val reader = MockChainReader(metadataError = rpcFailure())
            val (manager, stub, _) = TestManagers.approvalManager(reader = reader)

            manager.approveTokenAllowance(chainId, unknownToken, spender)

            assertThat(stub.sendTransactionCalls.single().data).endsWith("f".repeat(64))
        }

    @Test
    fun `an allowance read refuses to report a scale it had to guess`(): Unit = runBlocking {
        val reader = MockChainReader(
            metadataError = rpcFailure(),
            allowance = BigInteger.valueOf(250_000_000)
        )
        val (manager, _, _) = TestManagers.approvalManager(reader = reader)

        assertThrows(RainError.TokenNotFound::class.java) {
            runBlocking { manager.getTokenAllowance(chainId, unknownToken, spender) }
        }
    }

    @Test
    fun `absurd decimals are rejected instead of blowing up the scaling math`(): Unit =
        runBlocking {
            val (manager, stub, _) = TestManagers.approvalManager()

            assertThrows(RainError.InvalidConfig::class.java) {
                runBlocking {
                    manager.approveTokenAllowance(
                        chainId, usdc, spender, BigDecimal.ONE, decimals = 40_000
                    )
                }
            }
            assertThat(stub.sendTransactionCalls).isEmpty()
        }

    @Test
    fun `a token reporting absurd decimals cannot break the allowance read either`(): Unit =
        runBlocking {
            val reader = MockChainReader(decimals = 40_000, allowance = BigInteger.ONE)
            val (manager, _, _) = TestManagers.approvalManager(reader = reader)

            assertThrows(RainError.InvalidConfig::class.java) {
                runBlocking { manager.getTokenAllowance(chainId, unknownToken, spender) }
            }
        }

    // ---- provider failures ---------------------------------------------------------

    @Test
    fun `a user rejection in the wallet surfaces as userRejected`(): Unit = runBlocking {
        val stub = StubWalletProvider().apply { sendTransactionError = RainError.UserRejected() }
        val (manager, _, _) = TestManagers.approvalManager(stub = stub)

        assertThrows(RainError.UserRejected::class.java) {
            runBlocking { manager.approveTokenAllowance(chainId, usdc, spender) }
        }
    }

    @Test
    fun `a simulation revert keeps its own code rather than the withdrawal one`(): Unit =
        runBlocking {
            val stub = StubWalletProvider().apply {
                sendTransactionError = RainError.TransactionSimulationFailed(null)
            }
            val (manager, _, _) = TestManagers.approvalManager(stub = stub)

            val ex = assertThrows(RainError.TransactionSimulationFailed::class.java) {
                runBlocking { manager.approveTokenAllowance(chainId, usdc, spender) }
            }
            assertThat(ex.errorCode.code).isEqualTo("RAIN_403")
        }

    // ---- getTokenAllowance ----------------------------------------------------------

    @Test
    fun `the allowance is read for the wallet's own address by default`(): Unit = runBlocking {
        val reader = MockChainReader(allowance = BigInteger.valueOf(250_000_000))
        val (manager, _, _) = TestManagers.approvalManager(
            reader = reader, seedTokens = listOf(usdcInfo())
        )

        val allowance = manager.getTokenAllowance(chainId, usdc, spender)

        assertThat(reader.allowanceCalls.single()).isEqualTo(
            MockChainReader.AllowanceCall(chainId, usdc, TestFixtures.WALLET_ADDRESS, spender)
        )
        assertThat(allowance.rawAmount).isEqualTo(BigInteger.valueOf(250_000_000))
        assertThat(allowance.decimals).isEqualTo(6)
        assertThat(allowance.decimalAmount).isEqualTo(BigDecimal("250.000000"))
        assertThat(allowance.formatted).isEqualTo("250")
        assertThat(allowance.isUnlimited).isFalse()
        assertThat(allowance.isZero).isFalse()
    }

    @Test
    fun `an explicit owner is read instead of the wallet's own address`(): Unit = runBlocking {
        val other = "0x3cA8ac240F6ebeA8684b3E629A8e8C1f0E3bC0Ff"
        val (manager, _, reader) = TestManagers.approvalManager(seedTokens = listOf(usdcInfo()))

        manager.getTokenAllowance(chainId, usdc, spender, owner = other, decimals = 6)

        assertThat(reader.allowanceCalls.single().owner).isEqualTo(other)
    }

    @Test
    fun `a malformed explicit owner is rejected before the chain read`(): Unit = runBlocking {
        val (manager, _, reader) = TestManagers.approvalManager(seedTokens = listOf(usdcInfo()))

        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { manager.getTokenAllowance(chainId, usdc, spender, owner = "0xnope") }
        }
        assertThat(reader.allowanceCalls).isEmpty()
    }

    @Test
    fun `an unlimited allowance is exact in rawAmount`(): Unit = runBlocking {
        val reader = MockChainReader(allowance = RainTokenAllowance.UNLIMITED_RAW_AMOUNT)
        val (manager, _, _) = TestManagers.approvalManager(
            reader = reader, seedTokens = listOf(usdcInfo())
        )

        val allowance = manager.getTokenAllowance(chainId, usdc, spender)

        assertThat(allowance.isUnlimited).isTrue()
        assertThat(allowance.rawAmount).isEqualTo(RainTokenAllowance.UNLIMITED_RAW_AMOUNT)
        assertThat(allowance.covers(BigDecimal("1000000"))).isTrue()
    }

    @Test
    fun `a zero allowance is reported as revoked and covers nothing`(): Unit = runBlocking {
        val (manager, _, _) = TestManagers.approvalManager(seedTokens = listOf(usdcInfo()))

        val allowance = manager.getTokenAllowance(chainId, usdc, spender)

        assertThat(allowance.isZero).isTrue()
        assertThat(allowance.covers(BigDecimal("0.000001"))).isFalse()
        assertThat(allowance.covers(BigDecimal.ZERO)).isTrue()
    }

    @Test
    fun `covers compares against the exact base units the amount needs`() {
        val allowance = RainTokenAllowance(
            chainId = chainId,
            tokenAddress = usdc,
            owner = TestFixtures.WALLET_ADDRESS,
            spender = spender,
            rawAmount = BigInteger.valueOf(250_000_000),
            decimals = 6
        )

        assertThat(allowance.covers(BigDecimal("250"))).isTrue()
        assertThat(allowance.covers(BigDecimal("249.999999"))).isTrue()
        assertThat(allowance.covers(BigDecimal("250.000001"))).isFalse()
        // Finer than the token's scale can't be represented, so it isn't covered.
        assertThat(allowance.covers(BigDecimal("0.0000001"))).isFalse()
    }

    @Test
    fun `a failed allowance read surfaces as a typed SDK error`(): Unit = runBlocking {
        val reader = MockChainReader(allowanceError = rpcFailure())
        val (manager, _, _) = TestManagers.approvalManager(
            reader = reader, seedTokens = listOf(usdcInfo())
        )

        assertThrows(RainError.NetworkError::class.java) {
            runBlocking { manager.getTokenAllowance(chainId, usdc, spender) }
        }
    }

    @Test
    fun `a Solana chain id is rejected on the allowance read too`(): Unit = runBlocking {
        val (manager, _, reader) = TestManagers.approvalManager()

        assertThrows(RainError.InternalError::class.java) {
            runBlocking { manager.getTokenAllowance(RainChain.SOLANA_DEVNET, usdc, spender) }
        }
        assertThat(reader.allowanceCalls).isEmpty()
    }

    // ---- estimateApprovalFee ---------------------------------------------------------

    @Test
    fun `fee estimation prices the same calldata the approval would send`(): Unit = runBlocking {
        val stub = StubWalletProvider().apply {
            estimateTransactionFeeToReturn = BigDecimal("0.00042")
        }
        val (manager, _, _) = TestManagers.approvalManager(stub = stub)

        val fee = manager.estimateApprovalFee(chainId, usdc, spender)

        assertThat(fee).isEqualTo(BigDecimal("0.00042"))
        val priced = stub.estimateTransactionFeeCalls.single()
        assertThat(priced.to).isEqualTo(usdc)
        assertThat(priced.value).isEqualTo("0x0")
        assertThat(priced.data).endsWith("f".repeat(64))
        // Nothing was broadcast on the way there.
        assertThat(stub.sendTransactionCalls).isEmpty()
    }

    @Test
    fun `fee estimation applies the same validation as the approval`(): Unit = runBlocking {
        val (manager, stub, _) = TestManagers.approvalManager()

        assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking { manager.estimateApprovalFee(chainId, usdc, "0xnope") }
        }
        assertThat(stub.estimateTransactionFeeCalls).isEmpty()
    }
}
