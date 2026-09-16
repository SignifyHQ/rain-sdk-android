package com.rain.sdk.wallet

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RainWalletBackendTest {

    @Test
    fun `the embedded identity is the sandbox pair shared by Rain's SDKs`() {
        assertThat(RainWalletBackend.ORGANIZATION_ID).isEqualTo("63495e45-8e64-42b5-b602-c68f019ca806")
        assertThat(RainWalletBackend.AUTH_CONFIG_ID).isEqualTo("1d8aac5e-f236-4800-bab7-98a9e27b4b2a")
    }
}
