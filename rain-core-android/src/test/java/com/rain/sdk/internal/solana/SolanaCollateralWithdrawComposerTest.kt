package com.rain.sdk.internal.solana

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.RainChain
import com.rain.sdk.internal.constants.SolanaPrograms
import com.rain.sdk.internal.error.RainError
import com.rain.sdk.internal.helpers.MockRpcServer
import com.rain.sdk.models.RainAdminSignature
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

    private val devnet = RainChain.SOLANA_DEVNET
    private val owner = "3mhBCgFYhFCAV7LFARCcLuLsQLsyErTRc2axkGJrf8UG"
    private val collateral = "2h5mCXyirbPJZbjGcWf4PTpcA6M7qZ5UCRaWysHjnx31"
    private val coordinator = "FF3tTZ91aRu7XdGc4XK6V7MkDyStJ5ZY2fG4ZKLqFgL5"
    private val programId = "A7oUtbpm2pYNeZGNjit9GCGcAQViBbPCuLEnMbu2h15o"
    private val mint = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"
    private val executor = "8pyuGBnfbCADScManuTtA23mjXmJDbzpgPw2R8tmC6gz"

    // Raw devnet account data (base64), captured at nonce 0 — the state the golden signature
    // was issued against (each withdrawal increments the nonce). The coordinator account is
    // trimmed after its executors vec — the tail is zero padding the parser never reads.
    private val collateralData =
        "Ey1jHcQy5HWnOHdO2T7CRNZhoZwZh0GwPo0mfagjVsGsGY2fH2KNQdOdA0tPmQtwpGjhDAwipRkO8lD4NVfOJV" +
            "/RXV0o8Rpe/xAAAABDb2xsYXRlcmFsU29sYW5hAAAAACkqW0Gb6ozWxe84Me0JM3doSAivumoFOfoMu8/P1wIJ" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    private val coordinatorData =
        "6oU9jK0DCrzAoMFbwn0Q6d70hJIK+xVVS4OxJMbo8DRXrUOATrTXxfui23QolaNZqiC5e3ewk/8MoAZzSNsL9t" +
            "bAQuHebigSnnRMcds6qirVTZcs6HKcRHzH1X4n9VGUYjoe3yFFLRzbsSbOmUylDJFRAmO2VHDQYCYlBDbv92RQ" +
            "WtrjlmDjgMgBAAAAdExx2zqqKtVNlyzocpxEfMfVfif1UZRiOh7fIUUtHNsBAAAAdExx2zqqKtVNlyzocpxEfM" +
            "fVfif1UZRiOh7fIUUtHNs="

    private val adminSignature = RainAdminSignature(
        salt = "TQQ0CmdE6fdJzi6aFl6X68AKTETgYWKmPuoKvWpBb5w=",
        signature = "TRMC8nouPBXzhc4sisYiotNfEbsHApGGr11A7axFYu1rMVKpwZ6iC6XoUfuS7Rywq" +
            "/0sEDGLtUbFM9DRiSVZAw==",
        expiresAt = "2026-07-24T16:54:51.000Z" // epoch 1784912091, as signed
    )

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

    private fun stubHappyPath() {
        rpc.stubObjectFor("getAccountInfo", collateral, contextual(rawAccount(programId, collateralData)))
        rpc.stubObjectFor("getAccountInfo", coordinator, contextual(rawAccount(programId, coordinatorData)))
        rpc.stubObjectFor("getAccountInfo", mint, contextual(mintValue(decimals = 6)))
        // The recipient's USDC token account already exists (any non-null account will do).
        rpc.stubObjectFor(
            "getAccountInfo",
            destinationAta(),
            contextual(rawAccount(SolanaPrograms.TOKEN_ADDRESS, ""))
        )
        rpc.stubObject("getLatestBlockhash", contextual(JSONObject().put("blockhash", mint)))
        rpc.stubObject(
            "simulateTransaction",
            contextual(JSONObject().put("err", JSONObject.NULL).put("logs", JSONArray()))
        )
    }

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
            assertThat(hex).isEqualTo(GOLDEN_WITHDRAW_TX_HEX)
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

    /** The destination ATA for (owner, mint) under the classic token program. */
    private fun destinationAta(): String = Base58.encode(
        SolanaAddresses.associatedTokenAddress(
            owner = Base58.decode(owner),
            mint = Base58.decode(mint),
            tokenProgramId = SolanaPrograms.TOKEN
        )
    )

    private fun contextual(value: Any): JSONObject =
        JSONObject().put("context", JSONObject().put("slot", 1)).put("value", value)

    private fun rawAccount(ownerProgram: String, base64Data: String): JSONObject = JSONObject()
        .put("owner", ownerProgram)
        .put("lamports", 2_122_800L)
        .put("data", JSONArray().put(base64Data).put("base64"))

    private fun mintValue(decimals: Int): JSONObject = JSONObject()
        .put("owner", SolanaPrograms.TOKEN_ADDRESS)
        .put("lamports", 1_461_600L)
        .put(
            "data",
            JSONObject().put(
                "parsed",
                JSONObject().put("type", "mint").put("info", JSONObject().put("decimals", decimals))
            )
        )

    private companion object {
        /** Full serialized withdraw transaction for the fixtures above, pinned byte-for-byte. */
        const val GOLDEN_WITHDRAW_TX_HEX =
        "010000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
        "00000000000000000000000000000000000100070c292a5b419bea8cd6c5ef3831ed093377684808afba6a0539fa0cbb" +
        "cfcfd7020919204dc2efd47006f5f095dfcfff8c2811bdb39f9bd6e7ea149dc2036ea28e589ab92d4c9217f2ea4521db" +
        "018240fdf2b101512b607ae3e0a1346634f799dcf05fe8b40a3afbbe885fda41ee87fa5e3c3c519ae399616b2d492c60" +
        "5ffbe1f9698642eb182389419107cce6e26289dfe20d8884d912cde4a886035373d3351eb1d39d034b4f990b70a468e1" +
        "0c0c22a5190ef250f83557ce255fd15d5d28f11a5e3b442cb3912157f13a933d0134282d032b5ffecd01a2dbf1b77906" +
        "08df002ea706ddf6e1d765a193d9cbe146ceeb79ac1cb485ed5f5b37913a8cf5857eff00a906a7d517187bd16635dad4" +
        "0455fdc2c0c124c68f215675a5dbbacb5f08000000000000000000000000000000000000000000000000000000000000" +
        "0000000000037d46d67c93fbbe12f9428f838d40ff0570744927f48a64fcca70448000000087773927c913f674c8e5fb" +
        "da44d29773d1d3e89532e3aa88de306103d95eca663b442cb3912157f13a933d0134282d032b5ffecd01a2dbf1b77906" +
        "08df002ea7020a00900101003000ffff1000ffff70002000ffff744c71db3aaa2ad54d972ce8729c447cc7d57e27f551" +
        "94623a1edf21452d1cdb4d1302f27a2e3c15f385ce2c8ac622a2d35f11bb07029186af5d40edac4562ed6b3152a9c19e" +
        "a20ba5e851fb92ed1cb0abfd2c10318bb546c533d0d18925590377229dff3a1aca1bf472323ba0cdf247669970593ab8" +
        "f64b31d7e1e74d28e8f40b0b0005010200060304070809380d1940536fb846f10100000000000000db98636a00000000" +
        "4d04340a6744e9f749ce2e9a165e97ebc00a4c44e06162a63eea0abd6a416f9c"
    }
}
