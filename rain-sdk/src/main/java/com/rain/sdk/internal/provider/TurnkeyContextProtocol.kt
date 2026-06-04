package com.rain.sdk.internal.provider

import com.turnkey.core.TurnkeyContext
import com.turnkey.core.models.Session
import com.turnkey.core.models.Wallet
import com.turnkey.http.TurnkeyClient
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
import com.turnkey.types.V1HashFunction
import com.turnkey.types.V1PayloadEncoding
import com.turnkey.types.V1SignRawPayloadResult

/**
 * Narrow internal abstractions over the Turnkey Kotlin SDK so the wallet provider can be
 * unit-tested without standing up a real `TurnkeyContext` singleton. The split is
 * deliberately small: only the methods the provider actually invokes are exposed here, so
 * tests can mock them without re-stating Turnkey's full surface area.
 */
internal interface TurnkeyClientProtocol {
    suspend fun getWalletAddressBalances(
        input: TGetWalletAddressBalancesBody
    ): TGetWalletAddressBalancesResponse

    suspend fun ethSendTransaction(
        input: TEthSendTransactionBody
    ): TEthSendTransactionResponse

    suspend fun solSendTransaction(
        input: TSolSendTransactionBody
    ): TSolSendTransactionResponse

    suspend fun getSendTransactionStatus(
        input: TGetSendTransactionStatusBody
    ): TGetSendTransactionStatusResponse

    suspend fun getActivities(
        input: TGetActivitiesBody
    ): TGetActivitiesResponse
}

internal interface TurnkeyContextProtocol {
    val wallets: List<Wallet>
    val session: Session?
    val turnkeyClient: TurnkeyClientProtocol?

    suspend fun refreshWallets()

    suspend fun signRawPayload(
        signWith: String,
        payload: String,
        encoding: V1PayloadEncoding,
        hashFunction: V1HashFunction
    ): V1SignRawPayloadResult
}

/**
 * Default adapter that bridges the real Turnkey singleton to the test-only interfaces.
 * Production code holds the singleton via this wrapper so the wallet provider doesn't
 * depend on `TurnkeyContext` statics directly.
 */
internal class TurnkeyContextAdapter(
    private val context: TurnkeyContext = TurnkeyContext
) : TurnkeyContextProtocol {

    override val wallets: List<Wallet>
        get() = context.wallets.value.orEmpty()

    override val session: Session?
        get() = context.session.value

    override val turnkeyClient: TurnkeyClientProtocol?
        get() = runCatching { TurnkeyClientAdapter(context.client) }.getOrNull()

    override suspend fun refreshWallets() {
        context.refreshWallets()
    }

    override suspend fun signRawPayload(
        signWith: String,
        payload: String,
        encoding: V1PayloadEncoding,
        hashFunction: V1HashFunction
    ): V1SignRawPayloadResult {
        return context.signRawPayload(
            signWith = signWith,
            payload = payload,
            encoding = encoding,
            hashFunction = hashFunction
        )
    }
}

internal class TurnkeyClientAdapter(
    private val client: TurnkeyClient
) : TurnkeyClientProtocol {

    override suspend fun getWalletAddressBalances(
        input: TGetWalletAddressBalancesBody
    ): TGetWalletAddressBalancesResponse = client.getWalletAddressBalances(input)

    override suspend fun ethSendTransaction(
        input: TEthSendTransactionBody
    ): TEthSendTransactionResponse = client.ethSendTransaction(input)

    override suspend fun solSendTransaction(
        input: TSolSendTransactionBody
    ): TSolSendTransactionResponse = client.solSendTransaction(input)

    override suspend fun getSendTransactionStatus(
        input: TGetSendTransactionStatusBody
    ): TGetSendTransactionStatusResponse = client.getSendTransactionStatus(input)

    override suspend fun getActivities(
        input: TGetActivitiesBody
    ): TGetActivitiesResponse = client.getActivities(input)
}
