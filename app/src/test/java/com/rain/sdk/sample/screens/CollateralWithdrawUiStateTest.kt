package com.rain.sdk.sample.screens

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.math.BigDecimal

/**
 * The withdraw screen's fail-closed rule: a token whose decimals the SDK could not establish never
 * reaches a money action, and the signature cache key tells chains and amounts apart.
 */
class CollateralWithdrawUiStateTest {

    private val usdc = WithdrawTokenOption(
        name = "USD Coin",
        symbol = "USDC",
        address = USDC,
        decimals = 6,
        balance = BigDecimal("12.5"),
    )

    private fun state(token: WithdrawTokenOption, amount: String) = CollateralWithdrawUiState(
        chainId = 84532,
        availableTokens = listOf(token),
        selectedTokenIndex = 0,
        amount = amount,
    )

    @Test
    fun `known decimals enable a positive amount within the balance`() {
        val state = state(usdc, "1")

        assertThat(state.selectedTokenDecimalsKnown).isTrue()
        assertThat(state.decimalsUnavailableText).isNull()
        assertThat(state.isAmountValid).isTrue()
    }

    @Test
    fun `unknown decimals disable every money action and explain why`() {
        val state = state(usdc.copy(decimals = null), "1")

        assertThat(state.selectedTokenDecimalsKnown).isFalse()
        assertThat(state.isAmountValid).isFalse()
        assertThat(state.decimalsUnavailableText).contains("USDC")
    }

    @Test
    fun `the explanation names the address when the symbol is blank`() {
        val state = state(usdc.copy(decimals = null, symbol = ""), "")

        assertThat(state.decimalsUnavailableText).contains(USDC)
    }

    @Test
    fun `a valid amount is still positive and within the balance`() {
        assertThat(state(usdc, "0").isAmountValid).isFalse()
        assertThat(state(usdc, "abc").isAmountValid).isFalse()
        assertThat(state(usdc, "12.6").isAmountValid).isFalse()
        assertThat(state(usdc, "12.6").isAmountOverBalance).isTrue()
        assertThat(state(usdc, "12.5").isAmountValid).isTrue()
    }

    @Test
    fun `signature keys differ across chains and amounts`() {
        val key = SignatureKey(chainId = 84532, tokenAddress = USDC.lowercase(), amountBaseUnits = "12500000", recipientAddress = RECIPIENT)

        assertThat(key).isEqualTo(key.copy())
        assertThat(key.copy(chainId = 421614)).isNotEqualTo(key)
        assertThat(key.copy(amountBaseUnits = "12500001")).isNotEqualTo(key)
        assertThat(key.copy(recipientAddress = RECIPIENT.uppercase())).isNotEqualTo(key)
    }

    @Test
    fun `the screen opens on the first token with known decimals, or on none`() {
        val unresolved = usdc.copy(address = RECIPIENT, symbol = "T", decimals = null)

        assertThat(defaultWithdrawSelection(listOf(unresolved, usdc))).isEqualTo(1)
        assertThat(defaultWithdrawSelection(listOf(usdc, unresolved))).isEqualTo(0)
        assertThat(defaultWithdrawSelection(listOf(unresolved))).isEqualTo(-1)
        assertThat(defaultWithdrawSelection(emptyList())).isEqualTo(-1)
    }

    private companion object {
        const val USDC = "0x036CbD53842c5426634e7929541eC2318f3dCF7e"
        const val RECIPIENT = "0x4444444444444444444444444444444444444444"
    }
}
