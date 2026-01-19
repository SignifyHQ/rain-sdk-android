package com.rain.sdk

import com.rain.sdk.internal.RainSdkManager
import com.rain.sdk.utils.RainUtils

object Rain {
    /**
     * The main instance of Rain SDK.
     */
    val instance: RainSdk by lazy { RainSdkManager() }
    
    /**
     * Web3 Utilities (Standalone)
     */
    val Utils = RainUtils
}
