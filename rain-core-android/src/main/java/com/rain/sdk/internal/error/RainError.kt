package com.rain.sdk.internal.error

/**
 * Standardized Error Codes for Rain SDK.
 */
enum class RainErrorCode(val code: String) {
  SDK_NOT_INITIALIZED("RAIN_101"),
  INVALID_CONFIG("RAIN_102"),
  INVALID_RPC_URL("RAIN_103"),
  API_NOT_CONFIGURED("RAIN_104"),

  TOKEN_EXPIRED("RAIN_201"),
  UNAUTHORIZED("RAIN_202"),

  NETWORK_ERROR("RAIN_301"),
  API_ERROR("RAIN_302"),
  SIGNATURE_NOT_READY("RAIN_303"),
  NO_COLLATERAL_CONTRACTS("RAIN_304"),

  USER_REJECTED("RAIN_401"),
  INSUFFICIENT_FUNDS("RAIN_402"),
  TRANSACTION_SIMULATION_FAILED("RAIN_403"),
  WALLET_UNAVAILABLE("RAIN_404"),
  WITHDRAWAL_REVERTED_BY_NETWORK("RAIN_405"),
  INVALID_AMOUNT("RAIN_406"),
  WALLET_NOT_AUTHORIZED("RAIN_407"),

  PROVIDER_ERROR("RAIN_501"),
  INTERNAL_LOGIC_ERROR("RAIN_502")
}

/**
 * Base Exception class for all Rain SDK errors.
 */
@Suppress("SerializableHasSerializationMethods")
sealed class RainError(
  val errorCode: RainErrorCode,
  message: String? = null,
  cause: Throwable? = null
) : Exception("RainSDK Error [${errorCode.code}]: ${message ?: "See docs for details"}", cause) {

  // --- 1xx Initialization ---
  class SdkNotInitialized : RainError(RainErrorCode.SDK_NOT_INITIALIZED)

  class InvalidConfig(details: String) :
    RainError(RainErrorCode.INVALID_CONFIG, "Invalid Config: $details")

  /**
   * No provider was registered for the requested id, or none matched the requested capability.
   * Reuses [RainErrorCode.INVALID_CONFIG] — it is a configuration mistake, not a new failure mode.
   */
  class ProviderNotRegistered(val details: String) :
    RainError(RainErrorCode.INVALID_CONFIG, "Provider not registered: $details")

  /** RPC URL could not be parsed as a valid URL (no chain-ID context). */
  class InvalidRpcUrl(rpcUrl: String) :
    RainError(RainErrorCode.INVALID_RPC_URL, "Invalid RPC URL: $rpcUrl")

  /** A Rain API method was called before an Api-Key and userId were supplied. */
  class ApiNotConfigured :
    RainError(
      RainErrorCode.API_NOT_CONFIGURED,
      "Rain API is not configured — call configureRainApi(apiKey, userId) first"
    )

  // --- 2xx Authentication ---
  class TokenExpired : RainError(RainErrorCode.TOKEN_EXPIRED)

  class Unauthorized(details: String) :
    RainError(RainErrorCode.UNAUTHORIZED, details)

  // --- 3xx Network ---
  class NetworkError(message: String? = null, cause: Throwable? = null) :
    RainError(RainErrorCode.NETWORK_ERROR, message, cause)

  /** The Rain API returned a non-success HTTP status (other than 401/403 → [Unauthorized]). */
  class ApiError(val statusCode: Int, details: String? = null) :
    RainError(
      RainErrorCode.API_ERROR,
      "Rain API error $statusCode${details?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}"
    )

  /**
   * The withdrawal admin signature is not ready yet. Retry the fetch after [retryAfter]
   * seconds, when the API provides one.
   */
  class SignatureNotReady(val status: String, val retryAfter: Int? = null) :
    RainError(
      RainErrorCode.SIGNATURE_NOT_READY,
      "Withdrawal signature not ready: status=$status${retryAfter?.let { " (retry after ${it}s)" } ?: ""}"
    )

  /** The contracts endpoint returned no collateral contracts for the configured user. */
  class NoCollateralContracts :
    RainError(RainErrorCode.NO_COLLATERAL_CONTRACTS, "No collateral contracts returned for user")

  // --- 4xx User Action ---
  class UserRejected : RainError(RainErrorCode.USER_REJECTED)

  class InsufficientFunds : RainError(RainErrorCode.INSUFFICIENT_FUNDS)

  class TransactionSimulationFailed(cause: Throwable?) :
    RainError(RainErrorCode.TRANSACTION_SIMULATION_FAILED, "Transaction simulation failed: ${cause?.message}", cause)

  /**
   * The active wallet provider returned no usable wallet address — e.g. the user has not
   * created or connected a wallet, or the Turnkey context contains no Ethereum account.
   */
  class WalletUnavailable(details: String = "No wallet address from the wallet provider") :
    RainError(RainErrorCode.WALLET_UNAVAILABLE, details)

  /**
   * Withdrawal transaction reverted on-chain (e.g. duplicate withdrawal in a short window,
   * already-used signature, contract guard tripped).
   */
  class WithdrawalRevertedByNetwork(details: String = "Withdrawal reverted by the network", cause: Throwable? = null) :
    RainError(RainErrorCode.WITHDRAWAL_REVERTED_BY_NETWORK, details, cause)

  /**
   * The amount is invalid for the token — more decimal places than the token supports, or
   * negative/unrepresentable.
   */
  class InvalidAmount(val amount: String, val reason: String) :
    RainError(RainErrorCode.INVALID_AMOUNT, "Invalid amount ($amount): $reason")

  /**
   * The signing wallet is not an admin of the collateral contract. `withdrawAsset` verifies the
   * withdrawal signature against the collateral's admin set, so a non-admin signer reverts
   * on-chain with `InvalidSignature()`.
   */
  class WalletNotAuthorized(val walletAddress: String, val proxyAddress: String) :
    RainError(
      RainErrorCode.WALLET_NOT_AUTHORIZED,
      "Wallet $walletAddress is not an admin of collateral contract $proxyAddress"
    )

  // The four cases below are token-transfer failures that callers need to tell apart in the UI.
  // They carry their own type (and fields) but deliberately reuse existing [RainErrorCode]s: the
  // code map is a published contract host apps switch on (see RainErrorCodeParityTest), so adding
  // codes here would break it. Promote them to codes of their own in a deliberate version bump.

  /**
   * The wallet holds less of the token than the transfer asks for. A subtype of
   * [InsufficientFunds]'s code, though the shortfall is in the token rather than in the
   * chain's native currency.
   */
  class InsufficientTokenBalance(
    val requested: String,
    val available: String,
    val token: String
  ) : RainError(
    RainErrorCode.INSUFFICIENT_FUNDS,
    "Insufficient balance for $token: requested $requested, available $available"
  )

  /**
   * The wallet has no account for this token, so there is nothing to send. On Solana a balance
   * lives in a per-mint token account that only exists once the wallet has received the token.
   */
  class TokenAccountNotFound(val walletAddress: String, val token: String) :
    RainError(
      RainErrorCode.INSUFFICIENT_FUNDS,
      "Wallet $walletAddress holds no account for token $token"
    )

  /** No token exists at this address on this chain (wrong address, or wrong cluster/network). */
  class TokenNotFound(val token: String, val chainId: Int) :
    RainError(RainErrorCode.INVALID_CONFIG, "No token found at $token on chainId=$chainId")

  /** The recipient address cannot receive this transfer — see [reason]. */
  class InvalidRecipient(val address: String, val reason: String) :
    RainError(RainErrorCode.INVALID_CONFIG, "Invalid recipient $address: $reason")

  // --- 5xx Internal ---
  class ProviderError(cause: Throwable?) :
    RainError(RainErrorCode.PROVIDER_ERROR, "Provider Error: ${cause?.message}", cause)

  class InternalError(details: String, cause: Throwable? = null) :
    RainError(RainErrorCode.INTERNAL_LOGIC_ERROR, details, cause)
}
