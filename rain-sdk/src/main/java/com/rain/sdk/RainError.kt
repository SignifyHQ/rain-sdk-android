package com.rain.sdk

sealed class RainError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    object PortalNotInitialized : RainError("Portal is not initialized")
    
    data class InvalidPortalToken(val token: String) : 
        RainError("Invalid Portal token: $token")
        
    data class InvalidRPCUrl(val chainId: Int, val url: String) : 
        RainError("Invalid RPC URL for chainId $chainId: $url")
        
    data class PortalError(val underlying: Throwable) : 
        RainError("Portal SDK error: ${underlying.message}", underlying)
}
