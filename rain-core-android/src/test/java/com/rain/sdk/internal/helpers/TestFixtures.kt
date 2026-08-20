package com.rain.sdk.internal.helpers

import com.rain.sdk.interfaces.RainTransactionBuilder
import com.rain.sdk.internal.core.RainSdkManager
import com.rain.sdk.internal.core.RainTransactionBuilderImpl
import com.rain.sdk.internal.tokenstore.TokenMetadataStore
import com.rain.sdk.models.RainAdminSignature
import org.junit.Assume.assumeTrue
import java.util.Base64

/**
 * Skips the calling test when the host JVM is older than JDK 24.
 *
 * Turnkey's published AAR — and `ErrorMapper`, which references `TurnkeyKotlinError` — are
 * compiled to class-file major version 68 (Java 24). Any test that transitively touches
 * those classes must gate on this so it skips cleanly on the JDK 21 bundled with Android
 * Studio rather than blowing up with `UnsupportedClassVersionError`.
 */
internal fun assumeJdk24() {
    val major = System.getProperty("java.version")?.substringBefore('.')?.toIntOrNull() ?: 0
    assumeTrue(
        "Turnkey SDK / ErrorMapper transitively load JDK-24 classes. Current: $major",
        major >= 24
    )
}

/**
 * Shared test fixtures: canonical addresses, salts, and signatures so manager-contract
 * tests don't have to invent valid bytes themselves.
 */
internal object TestFixtures {
    const val WALLET_ADDRESS = "0x1234567890123456789012345678901234567890"
    const val CONTRACT_ADDRESS = "0x1234567890123456789012345678901234567890"
    const val PROXY_ADDRESS = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
    const val CONTROLLER_ADDRESS = "0x5555555555555555555555555555555555555555"
    const val RECIPIENT_ADDRESS = "0xfedcbafedcbafedcbafedcbafedcbafedcbafedc"

    /** EIP-55 checksummed form of [RECIPIENT_ADDRESS] — what the send paths forward downstream. */
    const val RECIPIENT_ADDRESS_CHECKSUMMED = "0xfeDcbaFEdcBaFEDcbAfedcBAfeDCBAFeDCBafEdc"
    const val TOKEN_ADDRESS = "0x9876543210987654321098765432109876543210"
    const val USDC_ADDRESS = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"

    /** 32-byte salt encoded as base64. Used by withdrawCollateral / estimateWithdrawalFee. */
    val validSaltBase64: String = Base64.getEncoder().encodeToString(ByteArray(32) { 0xAA.toByte() })

    /** 65-byte signature encoded as hex with `0x` prefix. */
    val validSignatureHex: String = "0x" + "01".repeat(65)

    /** Rain-issued withdrawal authorization. */
    fun adminSignature(
        salt: String = validSaltBase64,
        signature: String = validSignatureHex,
        expiresAt: String = "2030-12-31T23:59:59Z"
    ): RainAdminSignature = RainAdminSignature(salt, signature, expiresAt)
}

/**
 * Manager factories for tests.
 *
 * Constructs [RainSdkManager] directly with a fake [WalletProvider] (the modular SDK binds a
 * manager to a resolved provider), so tests can exercise routing without standing up a real
 * Portal or Turnkey context or driving the [com.rain.sdk.RainSdk] builder.
 */
internal object TestManagers {

    /**
     * Returns a manager bound to [stub], optionally configured with a specific set of
     * [rpcEndpoints] (whose keys become `configuredChainIds`, used by `getAllBalances`), an
     * optional [tokenStore] (used by `sendToken` decimal resolution), and an optional
     * [transactionBuilder] (pass one over a mocked Web3j to exercise the withdrawal paths).
     */
    fun stubProviderManager(
        stub: StubWalletProvider = StubWalletProvider(),
        rpcEndpoints: Map<Int, String> = mapOf(1 to "https://rpc.test"),
        tokenStore: TokenMetadataStore? = null,
        transactionBuilder: RainTransactionBuilder = RainTransactionBuilderImpl(rpcEndpoints),
    ): Pair<RainSdkManager, StubWalletProvider> {
        val manager = RainSdkManager(
            walletProvider = stub,
            rpcEndpoints = rpcEndpoints,
            tokenStore = tokenStore,
            transactionBuilder = transactionBuilder,
        )
        return manager to stub
    }

}
