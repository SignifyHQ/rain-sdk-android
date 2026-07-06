package com.rain.sdk.internal.error

/**
 * Standardized Error Codes for Rain SDK.
 */
enum class RainErrorCode(val code: String) {
  SDK_NOT_INITIALIZED("RAIN_101"),
  INVALID_CONFIG("RAIN_102"),
  INVALID_RPC_URL("RAIN_103"),

  TOKEN_EXPIRED("RAIN_201"),
  UNAUTHORIZED("RAIN_202"),

  NETWORK_ERROR("RAIN_301"),

  USER_REJECTED("RAIN_401"),
  INSUFFICIENT_FUNDS("RAIN_402"),
  TRANSACTION_SIMULATION_FAILED("RAIN_403"),
  WALLET_UNAVAILABLE("RAIN_404"),
  WITHDRAWAL_REVERTED_BY_NETWORK("RAIN_405"),

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

  /** RPC URL could not be parsed as a valid URL (no chain-ID context). */
  class InvalidRpcUrl(rpcUrl: String) :
    RainError(RainErrorCode.INVALID_RPC_URL, "Invalid RPC URL: $rpcUrl")

  // --- 2xx Authentication ---
  class TokenExpired : RainError(RainErrorCode.TOKEN_EXPIRED)

  class Unauthorized(details: String) :
    RainError(RainErrorCode.UNAUTHORIZED, details)

  // --- 3xx Network ---
  class NetworkError(message: String? = null, cause: Throwable? = null) :
    RainError(RainErrorCode.NETWORK_ERROR, message, cause)

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

  // --- 5xx Internal ---
  class ProviderError(cause: Throwable?) :
    RainError(RainErrorCode.PROVIDER_ERROR, "Provider Error: ${cause?.message}", cause)

  class InternalError(details: String, cause: Throwable? = null) :
    RainError(RainErrorCode.INTERNAL_LOGIC_ERROR, details, cause)
}
