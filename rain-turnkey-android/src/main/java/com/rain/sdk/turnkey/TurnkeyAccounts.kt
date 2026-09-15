package com.rain.sdk.turnkey

import com.turnkey.core.models.Wallet
import com.turnkey.types.V1AddressFormat
import com.turnkey.types.V1WalletAccount

/**
 * Picks the wallet accounts this module acts on out of a Turnkey organization's wallet list.
 * [TurnkeyManager] signs and reads with the accounts these functions return, and the key exporter
 * exports the same ones, so signing and export can never disagree about which account is in use.
 * The rule is the first account of a chain family across every wallet, in the order the vendor
 * lists them.
 */
internal object TurnkeyAccounts {

    /** The first Ethereum-format account across [wallets] in list order, or null when there is none. */
    fun ethereumAccount(wallets: List<Wallet>): V1WalletAccount? =
        firstOfFormat(wallets, V1AddressFormat.ADDRESS_FORMAT_ETHEREUM)

    /** The first Solana-format account across [wallets] in list order, or null when there is none. */
    fun solanaAccount(wallets: List<Wallet>): V1WalletAccount? =
        firstOfFormat(wallets, V1AddressFormat.ADDRESS_FORMAT_SOLANA)

    /**
     * The account of [format] at [address], or null. An Ethereum address is hex, so a checksummed
     * and a lowercase spelling name the same account and the comparison ignores case. Every other
     * format compares exactly, because Base58 is case-sensitive. An account at that address in
     * another format does not match.
     */
    fun accountAt(wallets: List<Wallet>, address: String, format: V1AddressFormat): V1WalletAccount? =
        wallets.flatMap { it.accounts }.firstOrNull { account ->
            account.addressFormat == format && sameAddress(account.address, address, format)
        }

    /** True when [a] and [b] name the same account: hex addresses compare ignoring case, every other form exactly. */
    fun sameAddress(a: String, b: String): Boolean =
        a == b || (a.startsWith("0x", ignoreCase = true) && a.equals(b, ignoreCase = true))

    private fun firstOfFormat(wallets: List<Wallet>, format: V1AddressFormat): V1WalletAccount? =
        wallets.flatMap { it.accounts }.firstOrNull { it.addressFormat == format }

    private fun sameAddress(a: String, b: String, format: V1AddressFormat): Boolean =
        a.equals(b, ignoreCase = format == V1AddressFormat.ADDRESS_FORMAT_ETHEREUM)
}
