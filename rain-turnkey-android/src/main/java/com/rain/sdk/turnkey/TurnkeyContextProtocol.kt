package com.rain.sdk.turnkey

import com.turnkey.core.TurnkeyContext
import com.turnkey.core.models.AuthState
import com.turnkey.core.models.CreateSubOrgParams
import com.turnkey.core.models.CustomWallet
import com.turnkey.core.models.OtpType
import com.turnkey.core.models.Session
import com.turnkey.core.models.Wallet
import com.turnkey.core.models.errors.TurnkeyKotlinError
import com.turnkey.http.TurnkeyClient
import com.turnkey.types.TCreateWalletAccountsBody
import com.turnkey.types.TEthSendTransactionBody
import com.turnkey.types.TEthSendTransactionResponse
import com.turnkey.types.TGetActivitiesBody
import com.turnkey.types.TGetActivitiesResponse
import com.turnkey.types.TGetNoncesBody
import com.turnkey.types.TGetNoncesResponse
import com.turnkey.types.TGetSendTransactionStatusBody
import com.turnkey.types.TGetSendTransactionStatusResponse
import com.turnkey.types.TGetWalletAddressBalancesBody
import com.turnkey.types.TGetWalletAddressBalancesResponse
import com.turnkey.types.TSolSendTransactionBody
import com.turnkey.types.TSolSendTransactionResponse
import com.turnkey.types.V1AddressFormat
import com.turnkey.types.V1Curve
import com.turnkey.types.V1HashFunction
import com.turnkey.types.V1PathFormat
import com.turnkey.types.V1PayloadEncoding
import com.turnkey.types.V1SignRawPayloadResult
import com.turnkey.types.V1WalletAccountParams
import kotlinx.coroutines.flow.StateFlow

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

    suspend fun getNonces(
        input: TGetNoncesBody
    ): TGetNoncesResponse
}

/**
 * The channel a one-time code travels on. Module-owned so the vendor's enum stays inside the
 * adapter; [toVendorOtpType] is the only mapping.
 */
internal enum class OtpChannel { EMAIL, SMS }

/**
 * An in-flight one-time code: the id and the TEE-signed encryption bundle from `initOtp`, plus the
 * channel the code was issued on, which the completion repeats because the vendor looks the account
 * up by it. Module-owned so test doubles never construct the vendor's result type.
 */
internal data class OtpChallenge(val otpId: String, val encryptionTargetBundle: String, val channel: OtpChannel)

/** One account to create on a wallet — the module-owned shape of `V1WalletAccountParams`. */
internal data class TurnkeyAccountSpec(
    val addressFormat: V1AddressFormat,
    val curve: V1Curve,
    val path: String,
)

/** One wallet with its accounts — the module-owned shape of the vendor's wallet parameters. */
internal data class TurnkeyWalletSpec(val name: String, val accounts: List<TurnkeyAccountSpec>)

@Suppress("TooManyFunctions") // vendor seam: one member per Turnkey call the SDK makes
internal interface TurnkeyContextProtocol {
    val wallets: List<Wallet>
    val session: Session?
    val turnkeyClient: TurnkeyClientProtocol?
    val authState: StateFlow<AuthState>
    val sessionFlow: StateFlow<Session?>

    suspend fun refreshWallets()

    /** Refreshes the selected session; null [expirationSeconds] uses Turnkey's default TTL. */
    suspend fun refreshSession(expirationSeconds: String?)

    suspend fun signRawPayload(
        signWith: String,
        payload: String,
        encoding: V1PayloadEncoding,
        hashFunction: V1HashFunction
    ): V1SignRawPayloadResult

    // ---- Managed authentication (email or SMS OTP through Turnkey's auth proxy) ----

    /** Suspends until the vendor singleton has initialized and restored any persisted session. */
    suspend fun awaitReady()

    /** Starts a one-time code for [contact] on [channel]; the returned challenge completes it. */
    suspend fun sendOtp(contact: String, channel: OtpChannel): OtpChallenge

    /**
     * Completes the [challenge] from [sendOtp] with the user's code (sign-up or login, the vendor
     * decides) on the channel the challenge was issued on, and stores the new session under
     * [sessionKey]. [contact] must be the string [sendOtp] was given. On the sign-up path [signupWallet]
     * is created inside the same request as the organization, so a new account never exists
     * without its wallet; the login path ignores it. Fixed arity and a distinct name so it cannot
     * collide with the vendor's defaulted overloads.
     */
    suspend fun completeOtp(
        challenge: OtpChallenge,
        otpCode: String,
        contact: String,
        sessionKey: String,
        signupWallet: TurnkeyWalletSpec,
    )

    /** The key of the session the vendor currently treats as selected, or null when none is. */
    val selectedSessionKey: String?

    /** Makes the stored session under [sessionKey] the selected one. */
    suspend fun selectSession(sessionKey: String)

    /** Clears the selected session only — never every stored session. No-op when none is selected. */
    suspend fun clearSelectedSession()

    /** Removes the stored session under [sessionKey], selected or not. */
    suspend fun clearSession(sessionKey: String)

    /** Creates one wallet holding [accounts] on the authenticated organization. */
    suspend fun createWallet(walletName: String, accounts: List<TurnkeyAccountSpec>)

    /** Adds [accounts] to the existing wallet [walletId] — no new wallet, no new mnemonic. */
    suspend fun createWalletAccounts(walletId: String, accounts: List<TurnkeyAccountSpec>)
}

/** The one place the module's channel meets the vendor's enum. */
internal fun OtpChannel.toVendorOtpType(): OtpType = when (this) {
    OtpChannel.EMAIL -> OtpType.OTP_TYPE_EMAIL
    OtpChannel.SMS -> OtpType.OTP_TYPE_SMS
}

/**
 * Default adapter that bridges the real Turnkey singleton to the test-only interfaces.
 * Production code holds the singleton via this wrapper so the wallet provider doesn't
 * depend on `TurnkeyContext` statics directly.
 */
@Suppress("TooManyFunctions") // vendor seam: one member per Turnkey call the SDK makes
internal class TurnkeyContextAdapter(
    private val context: TurnkeyContext = TurnkeyContext
) : TurnkeyContextProtocol {

    override val wallets: List<Wallet>
        get() = context.wallets.value.orEmpty()

    override val session: Session?
        get() = context.session.value

    override val turnkeyClient: TurnkeyClientProtocol?
        get() = runCatching { TurnkeyClientAdapter(context.client) }.getOrNull()

    override val authState: StateFlow<AuthState>
        get() = context.authState

    override val sessionFlow: StateFlow<Session?>
        get() = context.session

    override suspend fun refreshWallets() {
        context.refreshWallets()
    }

    override suspend fun refreshSession(expirationSeconds: String?) {
        if (expirationSeconds == null) {
            context.refreshSession()
        } else {
            context.refreshSession(expirationSeconds = expirationSeconds)
        }
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

    override suspend fun awaitReady() = context.awaitReady()

    override suspend fun sendOtp(contact: String, channel: OtpChannel): OtpChallenge {
        val result = context.initOtp(otpType = channel.toVendorOtpType(), contact = contact)
        return OtpChallenge(
            otpId = result.otpId,
            encryptionTargetBundle = result.otpEncryptionTargetBundle,
            channel = channel,
        )
    }

    override suspend fun completeOtp(
        challenge: OtpChallenge,
        otpCode: String,
        contact: String,
        sessionKey: String,
        signupWallet: TurnkeyWalletSpec,
    ) {
        context.loginOrSignUpWithOtp(
            otpId = challenge.otpId,
            otpCode = otpCode,
            otpEncryptionTargetBundle = challenge.encryptionTargetBundle,
            contact = contact,
            otpType = challenge.channel.toVendorOtpType(),
            // Revokes this user's other Turnkey sessions server-side on a successful login; a
            // rejected code never reaches this point.
            invalidateExisting = true,
            sessionKey = sessionKey,
            // Sign-up only (the vendor ignores it on login): the wallet is created inside the
            // signup request; the vendor fills in the contact and verification token. `CustomWallet`
            // carries no mnemonic length, so the seed gets Turnkey's default of 12 words — the
            // length the createWallet fallback pins.
            createSubOrgParams = CreateSubOrgParams(
                customWallet = CustomWallet(
                    walletName = signupWallet.name,
                    walletAccounts = signupWallet.accounts.toVendorParams(),
                )
            ),
        )
    }

    override val selectedSessionKey: String?
        get() = context.selectedSessionKey.value

    override suspend fun selectSession(sessionKey: String) {
        context.setSelectedSession(sessionKey)
    }

    override suspend fun clearSelectedSession() {
        // clearSession(null) throws when nothing is selected; resolve the key first.
        val key = context.selectedSessionKey.value ?: return
        context.clearSession(key)
    }

    override suspend fun clearSession(sessionKey: String) {
        context.clearSession(sessionKey)
    }

    override suspend fun createWallet(walletName: String, accounts: List<TurnkeyAccountSpec>) {
        context.createWallet(
            walletName = walletName,
            accounts = accounts.toVendorParams(),
            mnemonicLength = MANAGED_WALLET_MNEMONIC_LENGTH
        )
    }

    override suspend fun createWalletAccounts(walletId: String, accounts: List<TurnkeyAccountSpec>) {
        // The high-level context has no wrapper for this activity; the typed client submits it
        // and polls it to completion like every other activity.
        val organizationId = context.session.value?.organizationId ?: throw TurnkeyKotlinError.InvalidSession()
        context.client.createWalletAccounts(
            TCreateWalletAccountsBody(
                organizationId = organizationId,
                walletId = walletId,
                accounts = accounts.toVendorParams()
            )
        )
    }

    private fun List<TurnkeyAccountSpec>.toVendorParams(): List<V1WalletAccountParams> = map {
        V1WalletAccountParams(
            addressFormat = it.addressFormat,
            curve = it.curve,
            path = it.path,
            pathFormat = V1PathFormat.PATH_FORMAT_BIP32
        )
    }

    private companion object {
        const val MANAGED_WALLET_MNEMONIC_LENGTH = 12L
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

    override suspend fun getNonces(
        input: TGetNoncesBody
    ): TGetNoncesResponse = client.getNonces(input)
}
