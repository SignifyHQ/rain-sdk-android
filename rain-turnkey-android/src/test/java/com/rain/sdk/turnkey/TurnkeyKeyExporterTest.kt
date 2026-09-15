package com.rain.sdk.turnkey

import com.google.common.truth.Truth.assertThat
import com.rain.sdk.internal.error.RainError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import timber.log.Timber

/**
 * [TurnkeyKeyExporter] against [MockTurnkey] and a real [TurnkeySessionCoordinator]: which
 * account and wallet each export resolves to, the checks on the returned material, the error
 * classes for every refusal, and that no log line or throwable chain ever carries the material.
 *
 * The stubbed key is the published `0102..1f20` seed and the Solana fixture's address is the
 * Base58 of its ed25519 public key, so the Solana path runs the real derivation and cross-check.
 *
 * Gated on JDK 24+ because Turnkey's published AAR is compiled to major class version 68. Vendor
 * types appear inside method bodies only, as [TurnkeyErrorMappingTest] explains.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TurnkeyKeyExporterTest {

    private val seen = StringBuilder()
    private val throwables = mutableListOf<Throwable>()
    private val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            seen.append(message).append('\n')
            if (t != null) throwables += t
        }
    }
    private var configuredCalls = 0
    private var hookCalls = 0

    @Before
    fun setUp() {
        assumeJdk24()
        Timber.plant(tree)
    }

    @After
    fun tearDown() {
        Timber.uproot(tree)
    }

    private fun coordinator(turnkey: TurnkeyContextProtocol) = TurnkeySessionCoordinator(
        turnkey = turnkey,
        onSessionExpired = { hookCalls++ },
        retryDelay = { },
    )

    private fun exporter(
        turnkey: TurnkeyContextProtocol,
        walletAddress: String? = null,
        coordinator: TurnkeySessionCoordinator = coordinator(turnkey),
        ensureConfigured: suspend () -> Unit = { configuredCalls++ },
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ) = TurnkeyKeyExporter(
        turnkey = turnkey,
        sessions = coordinator,
        walletAddressOverride = walletAddress,
        ensureConfigured = ensureConfigured,
        dispatcher = dispatcher,
    )

    // ---------- recovery phrase ----------

    @Test
    fun `the recovery phrase is the wallet holding the Ethereum account and no refresh runs when wallets are loaded`() =
        runTest {
            val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithVectorSolana()))

            val phrase = exporter(turnkey).exportRecoveryPhrase()

            assertThat(phrase).isEqualTo(turnkey.stubbedMnemonic)
            assertThat(turnkey.exportMnemonicCalls).containsExactly("wallet-id")
            assertThat(turnkey.refreshWalletsCallCount).isEqualTo(0)
        }

    @Test
    fun `an empty wallet list is refreshed once before the phrase is exported`() = runTest {
        val turnkey = MockTurnkey(wallets = emptyList())
        turnkey.onRefreshWallets = { turnkey.wallets = listOf(MockTurnkey.defaultWallet()) }

        val phrase = exporter(turnkey).exportRecoveryPhrase()

        assertThat(phrase).isEqualTo(turnkey.stubbedMnemonic)
        assertThat(turnkey.refreshWalletsCallCount).isEqualTo(1)
        assertThat(turnkey.exportMnemonicCalls).containsExactly("wallet-id")
    }

    @Test
    fun `a legacy two-wallet account with the Solana wallet first exports the Ethereum wallet's phrase`() = runTest {
        val turnkey = MockTurnkey(wallets = legacyTwoWallets())

        exporter(turnkey).exportRecoveryPhrase()

        assertThat(turnkey.exportMnemonicCalls).containsExactly("eth-wallet")
        assertThat(turnkey.refreshWalletsCallCount).isEqualTo(0)
    }

    @Test
    fun `an organization with no Ethereum account exports the first wallet's phrase`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(solanaOnlyWallet("sol-only")))

        val phrase = exporter(turnkey).exportRecoveryPhrase()

        assertThat(phrase).isEqualTo(turnkey.stubbedMnemonic)
        // One refresh looked for an Ethereum account; none appeared, so the first wallet anchors.
        assertThat(turnkey.refreshWalletsCallCount).isEqualTo(1)
        assertThat(turnkey.exportMnemonicCalls).containsExactly("sol-only")
    }

    // ---------- private keys ----------

    @Test
    fun `exportPrivateKey ETHEREUM exports the signing account and returns 0x plus the lowercase hex`() = runTest {
        val turnkey = MockTurnkey()
        turnkey.stubbedKeyHex = turnkey.stubbedKeyHex.uppercase()

        val key = exporter(turnkey).exportPrivateKey(TurnkeyKeyFamily.ETHEREUM)

        assertThat(key).isEqualTo("0x" + turnkey.stubbedKeyHex.lowercase())
        assertThat(key).hasLength(66)
        assertThat(turnkey.exportAccountKeyCalls).containsExactly(MockTurnkey.DEFAULT_WALLET_ADDRESS)
        assertThat(turnkey.exportMnemonicCalls).isEmpty()
    }

    @Test
    fun `exportPrivateKey SOLANA exports the Solana account in use and returns the vector keypair string`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithVectorSolana()))

        val key = exporter(turnkey).exportPrivateKey(TurnkeyKeyFamily.SOLANA)

        assertThat(key).isEqualTo(MockTurnkey.VECTOR_SOLANA_KEYPAIR)
        assertThat(turnkey.exportAccountKeyCalls).containsExactly(MockTurnkey.VECTOR_SOLANA_ADDRESS)
    }

    @Test
    fun `a walletAddress override anchors the phrase on that account's wallet and exports that key`() = runTest {
        val other = "0xabcdef0123456789abcdef0123456789abcdef01"
        val account = MockTurnkey.defaultWallet().accounts.single().copy(address = other, walletId = "wallet-b")
        val second = com.turnkey.core.models.Wallet(id = "wallet-b", name = "b", accounts = listOf(account))
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.defaultWallet(), second))
        // The override is matched ignoring case; the vendor call carries the account's own spelling.
        val exporter = exporter(turnkey, walletAddress = other.uppercase().replaceFirst("0X", "0x"))

        exporter.exportRecoveryPhrase()
        exporter.exportPrivateKey(TurnkeyKeyFamily.ETHEREUM)

        assertThat(turnkey.exportMnemonicCalls).containsExactly("wallet-b")
        assertThat(turnkey.exportAccountKeyCalls).containsExactly(other)
    }

    @Test
    fun `a walletAddress override that is not an Ethereum account is InvalidConfig before any vendor call`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithVectorSolana()))
        val exporter = exporter(turnkey, walletAddress = "0x9999999999999999999999999999999999999999")

        val phrase = expectThrows<RainError.InvalidConfig> { exporter.exportRecoveryPhrase() }
        val key = expectThrows<RainError.InvalidConfig> { exporter.exportPrivateKey(TurnkeyKeyFamily.ETHEREUM) }

        assertThat(phrase).hasMessageThat().contains("walletAddress")
        assertThat(key).hasMessageThat().contains("walletAddress")
        assertThat(turnkey.exportMnemonicCalls).isEmpty()
        assertThat(turnkey.exportAccountKeyCalls).isEmpty()
        // Each call refreshed once looking for the account before giving up.
        assertThat(turnkey.refreshWalletsCallCount).isEqualTo(2)
    }

    @Test
    fun `an override naming the Solana address is InvalidConfig not an ed25519 seed behind 0x`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithVectorSolana()))
        val exporter = exporter(turnkey, walletAddress = MockTurnkey.VECTOR_SOLANA_ADDRESS)

        expectThrows<RainError.InvalidConfig> { exporter.exportPrivateKey(TurnkeyKeyFamily.ETHEREUM) }

        assertThat(turnkey.exportAccountKeyCalls).isEmpty()
    }

    @Test
    fun `a missing Solana account after one refresh is WalletUnavailable and touches no export call`() = runTest {
        val turnkey = MockTurnkey() // Ethereum only

        val thrown = expectThrows<RainError.WalletUnavailable> {
            exporter(turnkey).exportPrivateKey(TurnkeyKeyFamily.SOLANA)
        }

        assertThat(thrown).hasMessageThat().contains("Solana")
        assertThat(turnkey.refreshWalletsCallCount).isEqualTo(1)
        assertThat(turnkey.exportAccountKeyCalls).isEmpty()
    }

    @Test
    fun `a stale list is refreshed once when the target account is missing then exports`() = runTest {
        val turnkey = MockTurnkey() // Ethereum only until the refresh lands the Solana account
        turnkey.onRefreshWallets = { turnkey.wallets = listOf(MockTurnkey.walletWithVectorSolana()) }

        val key = exporter(turnkey).exportPrivateKey(TurnkeyKeyFamily.SOLANA)

        assertThat(key).isEqualTo(MockTurnkey.VECTOR_SOLANA_KEYPAIR)
        assertThat(turnkey.refreshWalletsCallCount).isEqualTo(1)
    }

    @Test
    fun `an account whose curve does not match its family is WalletUnavailable`() = runTest {
        val wallet = MockTurnkey.walletWithVectorSolana()
        val mixed = wallet.copy(
            accounts = wallet.accounts.map { account ->
                if (account.addressFormat == com.turnkey.types.V1AddressFormat.ADDRESS_FORMAT_SOLANA) {
                    account.copy(curve = com.turnkey.types.V1Curve.CURVE_SECP256K1)
                } else {
                    account
                }
            }
        )
        val turnkey = MockTurnkey(wallets = listOf(mixed))

        val thrown = expectThrows<RainError.WalletUnavailable> {
            exporter(turnkey).exportPrivateKey(TurnkeyKeyFamily.SOLANA)
        }

        assertThat(thrown).hasMessageThat().contains("curve")
        assertThat(turnkey.exportAccountKeyCalls).isEmpty()
    }

    @Test
    fun `a key that is not 32 bytes is InternalError and nothing is returned`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithVectorSolana()))
        val exporter = exporter(turnkey)

        turnkey.stubbedKeyHex = "ab".repeat(31)
        val short = expectThrows<RainError.InternalError> { exporter.exportPrivateKey(TurnkeyKeyFamily.ETHEREUM) }
        turnkey.stubbedKeyHex = "ab".repeat(33)
        val long = expectThrows<RainError.InternalError> { exporter.exportPrivateKey(TurnkeyKeyFamily.SOLANA) }
        turnkey.stubbedKeyHex = "zz".repeat(32)
        val malformed = expectThrows<RainError.InternalError> { exporter.exportPrivateKey(TurnkeyKeyFamily.ETHEREUM) }

        listOf(short, long, malformed).forEach { thrown ->
            assertThat(thrown).hasMessageThat().contains("32-byte")
            assertThat(thrown).hasMessageThat().doesNotContain("abab")
            assertThat(thrown).hasMessageThat().doesNotContain("zz")
        }
    }

    @Test
    fun `a Solana public key that does not re-encode to the account address is InternalError`() = runTest {
        // The placeholder Solana address is a real 32-byte key's Base58, but not this seed's.
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithEthAndSolana()))

        val thrown = expectThrows<RainError.InternalError> {
            exporter(turnkey).exportPrivateKey(TurnkeyKeyFamily.SOLANA)
        }

        assertThat(thrown).hasMessageThat().contains("address")
        assertThat(thrown).hasMessageThat().doesNotContain(turnkey.stubbedKeyHex)
        assertThat(thrown).hasMessageThat().doesNotContain(MockTurnkey.VECTOR_SOLANA_KEYPAIR)
        assertThat(turnkey.exportAccountKeyCalls).containsExactly(MockTurnkey.DEFAULT_SOLANA_ADDRESS)
    }

    // ---------- sessions and vendor failures ----------

    @Test
    fun `no session is TokenExpired with no vendor call and no expiry hook`() = runTest {
        val turnkey = MockTurnkey(session = null)
        val exporter = exporter(turnkey)

        expectThrows<RainError.TokenExpired> { exporter.exportRecoveryPhrase() }
        expectThrows<RainError.TokenExpired> { exporter.exportPrivateKey(TurnkeyKeyFamily.ETHEREUM) }

        assertThat(turnkey.exportMnemonicCalls).isEmpty()
        assertThat(turnkey.exportAccountKeyCalls).isEmpty()
        assertThat(hookCalls).isEqualTo(0)
    }

    @Test
    fun `a 401 inside the vendor failure refreshes once then surfaces as TokenExpired when the refresh fails`() = runTest {
        val turnkey = MockTurnkey()
        turnkey.exportError = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToExportWallet(
            RuntimeException("HTTP error from /public/v1/submit/export_wallet: 401")
        )
        turnkey.refreshSessionError = RuntimeException("refresh is down")

        expectThrows<RainError.TokenExpired> { exporter(turnkey).exportRecoveryPhrase() }

        assertThat(turnkey.exportMnemonicCalls).hasSize(1)
        assertThat(turnkey.refreshSessionCallCount).isEqualTo(1)
        assertThat(hookCalls).isEqualTo(1)
    }

    @Test
    fun `a 403 is Unauthorized and an arbitrary vendor failure is ProviderError never raw`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithVectorSolana()))
        val exporter = exporter(turnkey)

        turnkey.exportError = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToExportWallet(
            RuntimeException("HTTP error from /public/v1/submit/export_wallet_account: 403")
        )
        expectThrows<RainError.Unauthorized> { exporter.exportPrivateKey(TurnkeyKeyFamily.ETHEREUM) }

        turnkey.exportError = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToExportWallet(
            IllegalStateException("export bundle rejected: OrgIdMismatch")
        )
        expectThrows<RainError.ProviderError> { exporter.exportRecoveryPhrase() }

        turnkey.exportError = RuntimeException("something the adapter never wrapped")
        expectThrows<RainError.ProviderError> { exporter.exportPrivateKey(TurnkeyKeyFamily.SOLANA) }

        assertThat(turnkey.refreshSessionCallCount).isEqualTo(0)
    }

    @Test
    fun `ensureConfigured runs before every export and its failure stops the export`() = runTest {
        val events = mutableListOf<String>()
        val turnkey = MockTurnkey(wallets = emptyList())
        turnkey.onRefreshWallets = {
            events += "refresh"
            turnkey.wallets = listOf(MockTurnkey.walletWithVectorSolana())
        }
        val exporter = exporter(turnkey, ensureConfigured = { events += "configured" })

        exporter.exportRecoveryPhrase()
        exporter.exportPrivateKey(TurnkeyKeyFamily.SOLANA)

        assertThat(events).containsExactly("configured", "refresh", "configured").inOrder()
        assertThat(turnkey.exportMnemonicCalls).hasSize(1)
        assertThat(turnkey.exportAccountKeyCalls).hasSize(1)

        val refusing = exporter(turnkey, ensureConfigured = { throw RainError.InvalidConfig("closed") })
        val thrown = expectThrows<RainError.InvalidConfig> { refusing.exportRecoveryPhrase() }
        assertThat(thrown).hasMessageThat().contains("closed")
        assertThat(turnkey.exportMnemonicCalls).hasSize(1)
    }

    @Test
    fun `a cancellation the vendor wrapped in FailedToExportWallet propagates as CancellationException`() = runTest {
        val turnkey = MockTurnkey()
        lateinit var job: Job
        // The vendor's exportWallet catches Throwable, the caller's cancellation included, and
        // rethrows it inside its own failure type.
        val wrapping = object : TurnkeyContextProtocol by turnkey {
            override suspend fun exportWalletMnemonic(walletId: String): String {
                job.cancel()
                throw com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToExportWallet(
                    CancellationException("wrapped by the vendor")
                )
            }
        }
        val exporter = exporter(wrapping, ensureConfigured = { })
        var thrown: Throwable? = null

        job = launch {
            try {
                exporter.exportRecoveryPhrase()
            } catch (e: Throwable) {
                thrown = e
                throw e
            }
        }
        job.join()

        assertThat(job.isCancelled).isTrue()
        assertThat(thrown).isInstanceOf(CancellationException::class.java)
        assertThat(thrown).isNotInstanceOf(RainError::class.java)
    }

    @Test
    fun `no log line or throwable chain carries the seed the keypair string or the phrase`() = runTest {
        val turnkey = MockTurnkey(wallets = listOf(MockTurnkey.walletWithVectorSolana()))
        val exporter = exporter(turnkey)
        val secrets = listOf(turnkey.stubbedKeyHex, MockTurnkey.VECTOR_SOLANA_KEYPAIR, turnkey.stubbedMnemonic, "stub1")

        // Success paths.
        exporter.exportRecoveryPhrase()
        exporter.exportPrivateKey(TurnkeyKeyFamily.ETHEREUM)
        exporter.exportPrivateKey(TurnkeyKeyFamily.SOLANA)
        // Refusals after the vendor returned material.
        turnkey.wallets = listOf(MockTurnkey.walletWithEthAndSolana())
        expectThrows<RainError.InternalError> { exporter.exportPrivateKey(TurnkeyKeyFamily.SOLANA) }
        turnkey.stubbedKeyHex = "ab".repeat(31)
        expectThrows<RainError.InternalError> { exporter.exportPrivateKey(TurnkeyKeyFamily.ETHEREUM) }
        // Vendor failures, which the coordinator logs with their throwable.
        turnkey.exportError = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToExportWallet(
            RuntimeException("HTTP error from /public/v1/submit/export_wallet_account: 403")
        )
        expectThrows<RainError.Unauthorized> { exporter.exportPrivateKey(TurnkeyKeyFamily.ETHEREUM) }
        turnkey.exportError = com.turnkey.core.models.errors.TurnkeyKotlinError.FailedToExportWallet(
            IllegalStateException("export bundle rejected: SignatureVerificationFailed")
        )
        expectThrows<RainError.ProviderError> { exporter.exportRecoveryPhrase() }

        val logged = seen.toString()
        val chains = throwables.flatMap { t -> generateSequence(t) { it.cause?.takeIf { c -> c !== it } }.toList() }
        secrets.forEach { secret ->
            assertThat(logged).doesNotContain(secret)
            chains.forEach { link -> assertThat(link.message.orEmpty()).doesNotContain(secret) }
        }
        assertThat(throwables).isNotEmpty() // the coordinator did log the vendor failures
    }

    // ---------- fixtures (vendor types inside bodies only) ----------

    private fun legacyTwoWallets(): List<com.turnkey.core.models.Wallet> = listOf(
        solanaOnlyWallet("sol-wallet"),
        com.turnkey.core.models.Wallet(
            id = "eth-wallet",
            name = "eth",
            accounts = listOf(MockTurnkey.defaultWallet().accounts.single().copy(walletId = "eth-wallet"))
        )
    )

    private fun solanaOnlyWallet(id: String): com.turnkey.core.models.Wallet = com.turnkey.core.models.Wallet(
        id = id,
        name = id,
        accounts = listOf(MockTurnkey.solanaAccount(MockTurnkey.VECTOR_SOLANA_ADDRESS).copy(walletId = id))
    )
}
