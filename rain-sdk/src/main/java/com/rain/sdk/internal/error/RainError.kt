package com.rain.sdk.internal.error

/**
 * Standardized Error Codes for Rain SDK.
 */
enum class RainErrorCode(val code: String) {
  SDK_NOT_INITIALIZED("RAIN_101"),
  INVALID_CONFIG("RAIN_102"),

  TOKEN_EXPIRED("RAIN_201"),
  UNAUTHORIZED("RAIN_202"),

  NETWORK_ERROR("RAIN_301"),

  USER_REJECTED("RAIN_401"),
  INSUFFICIENT_FUNDS("RAIN_402"),

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

  // --- 5xx Internal ---
  class ProviderError(cause: Throwable?) :
    RainError(RainErrorCode.PROVIDER_ERROR, "Provider Error: ${cause?.message}", cause)

  class InternalError(details: String, cause: Throwable? = null) :
    RainError(RainErrorCode.INTERNAL_LOGIC_ERROR, details, cause)
}
