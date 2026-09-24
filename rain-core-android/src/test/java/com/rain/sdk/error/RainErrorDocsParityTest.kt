package com.rain.sdk.error

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * `docs/METHODS.md` is the host's reference for the error contract: its Errors table names every code
 * and every error class, and its "Removed without a shim" table names every symbol this SDK dropped
 * without a replacement, and its sponsorship prose never promises the zero quote the SDK no longer
 * returns. A failure here means the document drifted from the code; fix the document.
 */
class RainErrorDocsParityTest {

    private val methods: String = doc("docs/METHODS.md")
    private val turnkeySupport: String = doc("docs/TURNKEY_SUPPORT.md")
    private val readme: String = doc("README.md")

    private fun doc(relative: String): String = sequenceOf("../$relative", relative)
        .map(::File)
        .firstOrNull { it.isFile }
        ?.readText()
        ?: error("$relative not found from ${File(".").absolutePath}")

    private val errorsSection: String =
        methods.substringAfter("\n## Errors").substringBefore("\n### Error handling example")

    private val removedSection: String =
        methods.substringAfter("\n### Removed without a shim").substringBefore("\n---")

    @Test
    fun `the Errors table names every code`() {
        RainErrorCode.entries.forEach { code ->
            assertWithMessage(code.name).that(errorsSection).contains("`${code.code}`")
        }
    }

    @Test
    fun `the Errors table names every error class`() {
        RainError::class.sealedSubclasses.forEach { subclass ->
            assertWithMessage(subclass.simpleName).that(errorsSection).contains("`RainError.${subclass.simpleName}`")
        }
    }

    @Test
    fun `every symbol removed without a shim has a row`() {
        listOf(
            "RainSdk.isRainApiConfigured",
            "configureRainApi",
            "fetchCollateralContracts",
            "fetchCollateralContract",
            "fetchAdminSignature",
            "rainApiEnvironment",
            "rainApiCredentials",
            "RainAuthPullChains.supported",
            "isSupported",
            "RainApiEnvironment",
            "RainCollateralContract",
            "RainCollateralToken",
            "RainError.ApiNotConfigured",
            "RainError.ApiError",
            "RainError.SignatureNotReady",
            "RainError.NoCollateralContracts",
            "RainErrorCode.API_NOT_CONFIGURED",
            "API_ERROR",
            "NO_COLLATERAL_CONTRACTS",
            "RainErrorCode.SIGNATURE_NOT_READY",
            "TRANSACTION_PENDING",
            "com.rain.sdk.internal.error",
            "com.rain.sdk.internal.solana.UnsignedSolanaTransfer",
            "INTERNAL_LOGIC_ERROR",
            "INTERNAL_ERROR",
            "RainSdk.descriptors",
            "RainSdk.providers",
            "RainWalletContact.Sms",
            "RainWalletContact.Phone",
            "LoginContact.Sms",
            "LoginContact.Phone",
            "sendLoginCode(email",
            "Reserved",
            "getAddress",
            "sendNativeToken",
            "amount: Double",
            "getNativeBalance",
            "getERC20Balance",
            "getERC20Balances",
            "getBalances",
            "generateAddressQRCode(address, width, height)",
            "composeTransactionParameters",
            "RainSdk.transactionBuilder",
            "DEFAULT_ERC20_DECIMALS",
            "convertWeiHexToDouble",
            "parseHexToBigInteger",
        ).forEach { name ->
            assertWithMessage(name).that(removedSection).contains(name)
        }
    }

    @Test
    fun `sponsored fee estimates are never documented as zero or as skipping the signature`() {
        val stale = listOf(
            "estimates return 0",
            "estimates return zero",
            "result is `0`",
            "quotes `0`",
            "fee estimate `0`",
            "nothing is signed or estimated",
            "signing step of a withdrawal fee estimate",
        )
        mapOf("docs/METHODS.md" to methods, "docs/TURNKEY_SUPPORT.md" to turnkeySupport, "README.md" to readme)
            .forEach { (name, text) ->
                stale.forEach { phrase -> assertWithMessage("$name: $phrase").that(text).doesNotContain(phrase) }
            }
    }
}
