package com.rain.sdk.internal.solana

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.constants.SolanaPrograms
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.helpers.MockRpcServer
import com.rain.sdk.internal.helpers.SolanaWithdrawFixtures
import java.math.BigInteger
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * End-to-end composition test against real devnet fixtures: the account data, addresses,
 * signature, and salt are the ones from the first successful withdrawal on devnet
 * (tx `3ByKrsXW…`, 2026-07-24), so the asserted bytes are known-good on chain.
 */
class SolanaCollateralWithdrawComposerTest {

    private val devnet = SolanaWithdrawFixtures.DEVNET
    private val owner = SolanaWithdrawFixtures.OWNER
    private val collateral = SolanaWithdrawFixtures.COLLATERAL
    private val coordinator = SolanaWithdrawFixtures.COORDINATOR
    private val programId = SolanaWithdrawFixtures.PROGRAM_ID
    private val mint = SolanaWithdrawFixtures.MINT
    private val executor = SolanaWithdrawFixtures.EXECUTOR
    private val coordinatorData = SolanaWithdrawFixtures.COORDINATOR_DATA
    private val adminSignature = SolanaWithdrawFixtures.adminSignature

    private lateinit var rpc: MockRpcServer

    @Before
    fun setUp() {
        rpc = MockRpcServer().also { it.start() }
    }

    @After
    fun tearDown() = rpc.shutdown()

    private fun composer() = SolanaCollateralWithdrawComposer(
        solanaRpcClient = SolanaRpcClient(),
        rpcUrlResolver = { rpc.urlFor(it) }
    )

    private fun stubHappyPath() = SolanaWithdrawFixtures.stubHappyPath(rpc)

    @Test
    fun `composes the two-instruction withdrawal the program accepted on devnet`(): Unit =
        runBlocking {
            stubHappyPath()

            val unsigned = composer().composeWithdraw(
                chainId = devnet,
                ownerAddress = owner,
                collateralAddress = collateral,
                mintAddress = mint,
                recipientAddress = owner,
                amountBaseUnits = BigInteger.ONE,
                adminSignature = adminSignature
            )

            val hex = unsigned.transactionHex
            // Golden bytes: pins the full serialization so composition drift is caught here
            // rather than on chain. Deterministic because the stubbed blockhash is fixed.
            assertThat(hex).isEqualTo(SolanaWithdrawFixtures.GOLDEN_WITHDRAW_TX_HEX)
            // The ed25519 instruction embeds the executor key, Rain's signature, and the exact
            // 32-byte message the executor signed — verified against the live signature.
            assertThat(hex).contains(SolanaTransactionBuilder.hexEncode(Base58.decode(executor)))
            assertThat(hex)
                .contains("77229dff3a1aca1bf472323ba0cdf247669970593ab8f64b31d7e1e74d28e8f4")
            // The withdraw instruction: discriminator, then amount=1 and expiry 1784912091
            // (0x6A6398DB) as LE u64/i64.
            assertThat(hex).contains("0d1940536fb846f1" + "0100000000000000" + "db98636a00000000")
            // Both programs are in the account table; the owner is the fee payer.
            assertThat(hex).contains(SolanaTransactionBuilder.hexEncode(Base58.decode(programId)))
            assertThat(hex).contains(
                SolanaTransactionBuilder.hexEncode(SolanaPrograms.ED25519_VERIFY)
            )
            assertThat(unsigned.createsRecipientAccount).isFalse()
        }

    @Test
    fun `skips the self-paid dry run when the fee is sponsored`(): Unit = runBlocking {
        // The dry run charges the fee to the owner, so it would false-fail a zero-SOL wallet whose
        // sponsor pays the real send. Composition itself is unchanged: same golden bytes.
        stubHappyPath()

        val unsigned = composer().composeWithdraw(
            chainId = devnet,
            ownerAddress = owner,
            collateralAddress = collateral,
            mintAddress = mint,
            recipientAddress = owner,
            amountBaseUnits = BigInteger.ONE,
            adminSignature = adminSignature,
            sponsoredFees = true
        )

        assertThat(unsigned.transactionHex).isEqualTo(SolanaWithdrawFixtures.GOLDEN_WITHDRAW_TX_HEX)
        assertThat(rpc.recordedMethods).doesNotContain("simulateTransaction")
    }

    @Test
    fun `demands rent up front when the recipient token account must be created`(): Unit = runBlocking {
        // A withdrawal to a recipient without a token account creates it with the owner as payer.
        // Fee sponsorship does not cover that rent, and without the dry run nothing else would
        // catch a wallet that cannot pay it before Turnkey does, so it is checked up front.
        stubHappyPath()
        rpc.stubObjectFor("getAccountInfo", SolanaWithdrawFixtures.destinationAta(), contextual(JSONObject.NULL))
        rpc.stubObject("getBalance", contextual(0L))

        assertThrows(RainError.InsufficientFunds::class.java) {
            runBlocking {
                composer().composeWithdraw(
                    chainId = devnet,
                    ownerAddress = owner,
                    collateralAddress = collateral,
                    mintAddress = mint,
                    recipientAddress = owner,
                    amountBaseUnits = BigInteger.ONE,
                    adminSignature = adminSignature,
                    sponsoredFees = true
                )
            }
        }
        assertThat(rpc.recordedMethods).doesNotContain("simulateTransaction")
    }

    @Test
    fun `rejects a wallet that does not own the collateral`(): Unit = runBlocking {
        stubHappyPath()

        val error = assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking {
                composer().composeWithdraw(
                    chainId = devnet,
                    ownerAddress = executor, // any wallet that isn't the collateral's owner
                    collateralAddress = collateral,
                    mintAddress = mint,
                    recipientAddress = owner,
                    amountBaseUnits = BigInteger.ONE,
                    adminSignature = adminSignature
                )
            }
        }
        assertThat(error.message).contains("not the owner")
    }

    @Test
    fun `rejects a collateral account that is not single-signer`(): Unit = runBlocking {
        stubHappyPath()
        // The coordinator account has a different Anchor discriminator — a realistic wrong type.
        rpc.stubObjectFor("getAccountInfo", collateral, contextual(rawAccount(programId, coordinatorData)))

        val error = assertThrows(RainError.InvalidConfig::class.java) {
            runBlocking {
                composer().composeWithdraw(
                    chainId = devnet,
                    ownerAddress = owner,
                    collateralAddress = collateral,
                    mintAddress = mint,
                    recipientAddress = owner,
                    amountBaseUnits = BigInteger.ONE,
                    adminSignature = adminSignature
                )
            }
        }
        assertThat(error.message).contains("single-signer")
    }

    @Test
    fun `surfaces a simulation failure instead of handing the transaction out`(): Unit =
        runBlocking {
            stubHappyPath()
            rpc.stubObject(
                "simulateTransaction",
                contextual(
                    JSONObject()
                        .put("err", JSONObject().put("InstructionError", JSONArray()))
                        .put("logs", JSONArray().put("Program log: signature expired"))
                )
            )

            assertThrows(RainError.TransactionSimulationFailed::class.java) {
                runBlocking {
                    composer().composeWithdraw(
                        chainId = devnet,
                        ownerAddress = owner,
                        collateralAddress = collateral,
                        mintAddress = mint,
                        recipientAddress = owner,
                        amountBaseUnits = BigInteger.ONE,
                        adminSignature = adminSignature
                    )
                }
            }
        }

    // ---------- fixtures ----------

    private fun contextual(value: Any): JSONObject = SolanaWithdrawFixtures.contextual(value)

    private fun rawAccount(ownerProgram: String, base64Data: String): JSONObject =
        SolanaWithdrawFixtures.rawAccount(ownerProgram, base64Data)
}
