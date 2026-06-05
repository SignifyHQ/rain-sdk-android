package com.rain.sdk.internal.provider

import com.turnkey.core.TurnkeyContext
import com.turnkey.core.models.Session
import com.turnkey.core.models.Wallet
import com.turnkey.types.Externaldatav1Timestamp
import com.turnkey.types.TEthSendTransactionBody
import com.turnkey.types.TEthSendTransactionResponse
import com.turnkey.types.TGetActivitiesBody
import com.turnkey.types.TGetActivitiesResponse
import com.turnkey.types.TGetSendTransactionStatusBody
import com.turnkey.types.TGetSendTransactionStatusResponse
import com.turnkey.types.TGetWalletAddressBalancesBody
import com.turnkey.types.TGetWalletAddressBalancesResponse
import com.turnkey.types.TSolSendTransactionBody
import com.turnkey.types.TSolSendTransactionResponse
import com.turnkey.types.V1Activity
import com.turnkey.types.V1ActivityStatus
import com.turnkey.types.V1ActivityType
import com.turnkey.types.V1AddressFormat
import com.turnkey.types.V1AssetBalance
import com.turnkey.types.V1Curve
import com.turnkey.types.V1EthSendTransactionIntent
import com.turnkey.types.V1EthSendTransactionResult
import com.turnkey.types.V1EthSendTransactionStatus
import com.turnkey.types.V1SolanaSendTransactionStatus
import com.turnkey.types.V1HashFunction
import com.turnkey.types.V1Intent
import com.turnkey.types.V1PathFormat
import com.turnkey.types.V1PayloadEncoding
import com.turnkey.types.V1Result
import com.turnkey.types.V1SignRawPayloadResult
import com.turnkey.types.V1SolSendTransactionIntent
import com.turnkey.types.V1SolSendTransactionResult
import com.turnkey.types.V1WalletAccount
import java.util.UUID

internal class MockTurnkeyClient(
    var mockBalances: List<V1AssetBalance> = emptyList(),
    var mockSendTransactionStatusId: String = "send-status-id",
    var mockTransactionHash: String = "0x" + "d".repeat(64),
    var mockActivities: List<V1Activity> = emptyList(),
    var mockSolSendTransactionStatusId: String = "sol-send-status-id"
) : TurnkeyClientProtocol {

    /**
     * Status response fixture for `getSendTransactionStatus`. Use the factory methods on
     * the companion object to produce typical results (broadcasted / pending / failed).
     */
    data class StatusFixture(
        val txHash: String? = null,
        val txStatus: String = "TX_STATUS_BROADCASTED",
        val txError: String? = null,
        val errorMessage: String? = null,
        val solanaSignature: String? = null
    ) {
        companion object {
            fun broadcasted(hash: String) = StatusFixture(txHash = hash, txStatus = "TX_STATUS_BROADCASTED")
            fun pending() = StatusFixture(txHash = null, txStatus = "TX_STATUS_PENDING")
            fun failed(message: String = "broadcast failed") =
                StatusFixture(txHash = null, txStatus = "TX_STATUS_FAILED", txError = message)
        }
    }

    /**
     * Optional queue of status responses returned sequentially by `getSendTransactionStatus`.
     * When non-empty, each call consumes the next entry; the final entry is reused for
     * subsequent calls. When empty (default), a single BROADCASTED status containing
     * [mockTransactionHash] is returned.
     */
    var sendTransactionStatusQueue: MutableList<StatusFixture> = mutableListOf()

    /** When set, [getWalletAddressBalances] throws this instead of producing a response. */
    var walletAddressBalancesError: Exception? = null

    /** When set, [ethSendTransaction] throws this instead of producing a response. */
    var ethSendTransactionError: Exception? = null

    /** When set, [solSendTransaction] throws this instead of producing a response. */
    var solSendTransactionError: Exception? = null

    /** When set, [getSendTransactionStatus] throws this instead of producing a response. */
    var sendTransactionStatusError: Exception? = null

    /** When set, [getActivities] throws this instead of producing a response. */
    var getActivitiesError: Exception? = null

    val walletAddressBalanceCalls = mutableListOf<TGetWalletAddressBalancesBody>()
    val ethSendTransactionCalls = mutableListOf<TEthSendTransactionBody>()
    val solSendTransactionCalls = mutableListOf<TSolSendTransactionBody>()
    val sendTransactionStatusCalls = mutableListOf<TGetSendTransactionStatusBody>()
    val getActivitiesCalls = mutableListOf<TGetActivitiesBody>()

    override suspend fun getWalletAddressBalances(
        input: TGetWalletAddressBalancesBody
    ): TGetWalletAddressBalancesResponse {
        walletAddressBalanceCalls += input
        walletAddressBalancesError?.let { throw it }
        return TGetWalletAddressBalancesResponse(balances = mockBalances)
    }

    override suspend fun ethSendTransaction(
        input: TEthSendTransactionBody
    ): TEthSendTransactionResponse {
        ethSendTransactionCalls += input
        ethSendTransactionError?.let { throw it }
        return TEthSendTransactionResponse(
            activity = MockTurnkey.makeActivity(
                id = UUID.randomUUID().toString(),
                from = input.from,
                to = input.to,
                caip2 = input.caip2,
                value = input.value,
                data = input.data,
                sendTransactionStatusId = mockSendTransactionStatusId
            ),
            result = V1EthSendTransactionResult(sendTransactionStatusId = mockSendTransactionStatusId)
        )
    }

    override suspend fun solSendTransaction(
        input: TSolSendTransactionBody
    ): TSolSendTransactionResponse {
        solSendTransactionCalls += input
        solSendTransactionError?.let { throw it }
        return TSolSendTransactionResponse(
            activity = MockTurnkey.makeActivity(
                id = UUID.randomUUID().toString(),
                from = input.signWith,
                to = input.signWith,
                caip2 = input.caip2,
                value = null,
                data = null,
                sendTransactionStatusId = mockSolSendTransactionStatusId
            ),
            result = V1SolSendTransactionResult(sendTransactionStatusId = mockSolSendTransactionStatusId)
        )
    }

    override suspend fun getSendTransactionStatus(
        input: TGetSendTransactionStatusBody
    ): TGetSendTransactionStatusResponse {
        sendTransactionStatusCalls += input
        sendTransactionStatusError?.let { throw it }

        val fixture: StatusFixture = when {
            sendTransactionStatusQueue.isEmpty() -> StatusFixture.broadcasted(mockTransactionHash)
            sendTransactionStatusQueue.size == 1 -> sendTransactionStatusQueue[0]
            else -> sendTransactionStatusQueue.removeAt(0)
        }
        return TGetSendTransactionStatusResponse(
            error = null,
            eth = fixture.txHash?.let { V1EthSendTransactionStatus(txHash = it) },
            solana = fixture.solanaSignature?.let { V1SolanaSendTransactionStatus(signature = it) },
            txError = fixture.txError,
            txStatus = fixture.txStatus
        )
    }

    override suspend fun getActivities(
        input: TGetActivitiesBody
    ): TGetActivitiesResponse {
        getActivitiesCalls += input
        getActivitiesError?.let { throw it }
        return TGetActivitiesResponse(activities = mockActivities)
    }
}

/**
 * Returns a `RainSdkManager.turnkeyContextFactory` lambda that resolves to the supplied [mock]
 * regardless of which real `TurnkeyContext` singleton is passed in. Lives here (not in the test
 * file) so the Kotlin compiler emits the lambda's synthetic body on this class — keeping
 * `TurnkeyContext` out of the test class's method signatures so JDK 21's reflection scan can
 * succeed and `Assume.assumeTrue(jdk24+)` can skip cleanly.
 */
internal fun mockTurnkeyFactory(mock: TurnkeyContextProtocol): (TurnkeyContext) -> TurnkeyContextProtocol =
    { _: TurnkeyContext -> mock }

internal class MockTurnkey(
    override var wallets: List<Wallet> = listOf(defaultWallet()),
    override var session: Session? = defaultSession(),
    override var turnkeyClient: TurnkeyClientProtocol? = MockTurnkeyClient(),
    var mockSignature: V1SignRawPayloadResult = V1SignRawPayloadResult(
        r = "1".repeat(64),
        s = "2".repeat(64),
        v = "28"
    )
) : TurnkeyContextProtocol {

    data class SignRawPayloadCall(
        val signWith: String,
        val payload: String,
        val encoding: V1PayloadEncoding,
        val hashFunction: V1HashFunction
    )

    var refreshWalletsCallCount: Int = 0
    val signRawPayloadCalls = mutableListOf<SignRawPayloadCall>()

    /** When set, [signRawPayload] throws this instead of returning [mockSignature]. */
    var signRawPayloadError: Exception? = null

    override suspend fun refreshWallets() {
        refreshWalletsCallCount++
    }

    override suspend fun signRawPayload(
        signWith: String,
        payload: String,
        encoding: V1PayloadEncoding,
        hashFunction: V1HashFunction
    ): V1SignRawPayloadResult {
        signRawPayloadCalls += SignRawPayloadCall(signWith, payload, encoding, hashFunction)
        signRawPayloadError?.let { throw it }
        return mockSignature
    }

    companion object {
        const val DEFAULT_WALLET_ADDRESS = "0x1234567890123456789012345678901234567890"
        // Valid 32-byte base58 pubkeys (wrapped-SOL mint and USDC mint) reused as test addresses.
        const val DEFAULT_SOLANA_ADDRESS = "So11111111111111111111111111111111111111112"
        const val DEFAULT_SOLANA_RECIPIENT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        const val DEFAULT_ORG_ID = "org-id"

        fun defaultSession(): Session = Session(
            userId = "user-id",
            organizationId = DEFAULT_ORG_ID,
            expiry = System.currentTimeMillis() / 1000.0 + 3600,
            expirationSeconds = "3600",
            publicKey = "pubkey",
            token = "jwt",
            sessionType = "read_write"
        )

        fun defaultWallet(): Wallet = Wallet(
            id = "wallet-id",
            name = "wallet",
            accounts = listOf(
                V1WalletAccount(
                    address = DEFAULT_WALLET_ADDRESS,
                    addressFormat = V1AddressFormat.ADDRESS_FORMAT_ETHEREUM,
                    createdAt = Externaldatav1Timestamp(nanos = "0", seconds = "0"),
                    curve = V1Curve.CURVE_SECP256K1,
                    organizationId = DEFAULT_ORG_ID,
                    path = "m/44'/60'/0'/0/0",
                    pathFormat = V1PathFormat.PATH_FORMAT_BIP32,
                    publicKey = null,
                    updatedAt = Externaldatav1Timestamp(nanos = "0", seconds = "0"),
                    walletAccountId = "wallet-account-id",
                    walletDetails = null,
                    walletId = "wallet-id"
                )
            )
        )

        /** A Turnkey wallet account in Solana (ed25519) format. */
        fun solanaAccount(address: String = DEFAULT_SOLANA_ADDRESS): V1WalletAccount =
            V1WalletAccount(
                address = address,
                addressFormat = V1AddressFormat.ADDRESS_FORMAT_SOLANA,
                createdAt = Externaldatav1Timestamp(nanos = "0", seconds = "0"),
                curve = V1Curve.CURVE_ED25519,
                organizationId = DEFAULT_ORG_ID,
                path = "m/44'/501'/0'/0'",
                pathFormat = V1PathFormat.PATH_FORMAT_BIP32,
                publicKey = null,
                updatedAt = Externaldatav1Timestamp(nanos = "0", seconds = "0"),
                walletAccountId = "wallet-account-id-sol",
                walletDetails = null,
                walletId = "wallet-id"
            )

        /** A wallet holding both an Ethereum and a Solana account, like the demo provisions. */
        fun walletWithEthAndSolana(
            solanaAddress: String = DEFAULT_SOLANA_ADDRESS
        ): Wallet {
            val eth = defaultWallet().accounts
            return Wallet(
                id = "wallet-id",
                name = "wallet",
                accounts = eth + solanaAccount(solanaAddress)
            )
        }

        fun makeActivity(
            id: String,
            from: String,
            to: String,
            caip2: String,
            value: String?,
            data: String?,
            sendTransactionStatusId: String
        ): V1Activity = V1Activity(
            canApprove = false,
            canReject = false,
            createdAt = Externaldatav1Timestamp(nanos = "0", seconds = "1714521600"),
            fingerprint = "fingerprint",
            id = id,
            intent = V1Intent(
                ethSendTransactionIntent = V1EthSendTransactionIntent(
                    caip2 = caip2,
                    data = data,
                    from = from,
                    gasLimit = "21000",
                    gasStationNonce = null,
                    maxFeePerGas = "1000000000",
                    maxPriorityFeePerGas = "1000000000",
                    nonce = "1",
                    sponsor = false,
                    to = to,
                    value = value
                )
            ),
            organizationId = DEFAULT_ORG_ID,
            result = V1Result(
                ethSendTransactionResult = V1EthSendTransactionResult(
                    sendTransactionStatusId = sendTransactionStatusId
                )
            ),
            status = V1ActivityStatus.ACTIVITY_STATUS_COMPLETED,
            type = V1ActivityType.ACTIVITY_TYPE_ETH_SEND_TRANSACTION,
            updatedAt = Externaldatav1Timestamp(nanos = "0", seconds = "1714521600"),
            votes = emptyList()
        )

        /** A completed `sol_send_transaction` activity (history fixture). */
        fun makeSolanaActivity(
            id: String,
            signWith: String,
            caip2: String,
            unsignedTransaction: String,
            sendTransactionStatusId: String,
            createdAtSeconds: String = "1714521600"
        ): V1Activity = V1Activity(
            canApprove = false,
            canReject = false,
            createdAt = Externaldatav1Timestamp(nanos = "0", seconds = createdAtSeconds),
            fingerprint = "fingerprint",
            id = id,
            intent = V1Intent(
                solSendTransactionIntent = V1SolSendTransactionIntent(
                    caip2 = caip2,
                    signWith = signWith,
                    unsignedTransaction = unsignedTransaction
                )
            ),
            organizationId = DEFAULT_ORG_ID,
            result = V1Result(
                solSendTransactionResult = V1SolSendTransactionResult(
                    sendTransactionStatusId = sendTransactionStatusId
                )
            ),
            status = V1ActivityStatus.ACTIVITY_STATUS_COMPLETED,
            type = V1ActivityType.ACTIVITY_TYPE_SOL_SEND_TRANSACTION,
            updatedAt = Externaldatav1Timestamp(nanos = "0", seconds = createdAtSeconds),
            votes = emptyList()
        )
    }
}
