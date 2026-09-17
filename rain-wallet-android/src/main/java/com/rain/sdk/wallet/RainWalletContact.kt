package com.rain.sdk.wallet

import com.rain.sdk.turnkey.LoginContact

/**
 * Where a login code is delivered, and the identity the account is keyed on. A first login signs
 * the user up under this contact; a later login finds the account by email for [Email] and by
 * phone number for [Sms]. The same person arriving through the other channel is a new account.
 * [RainProvider.sendLoginCode] canonicalizes the value once (trim for an email; trim, separator
 * removal and an E.164 check for a phone number) and sends that same string on confirm.
 * `toString()` hides the value, which is personal data.
 */
sealed interface RainWalletContact {
    /** The contact as given; canonicalization happens in [RainProvider.sendLoginCode], not here. */
    val value: String

    /** An email address. A blank one makes [RainProvider.sendLoginCode] throw `RainError.InvalidConfig`. */
    class Email(override val value: String) : RainWalletContact {
        override fun equals(other: Any?): Boolean = other is Email && other.value == value

        override fun hashCode(): Int = value.hashCode()

        override fun toString(): String = "RainWalletContact.Email(…)"
    }

    /**
     * A phone number in E.164 form: `+`, the country code and the number, digits only, at most 15
     * of them, for example `+13214567890`. Spaces, dots, hyphens and parentheses are removed
     * before the check; anything else outside E.164 makes [RainProvider.sendLoginCode] throw
     * `RainError.InvalidConfig`, and so does a parenthesised trunk zero (`+44 (0) 20 ...`), which
     * stripping would fold into a different number. A national number without its country code
     * is not converted.
     */
    class Sms(override val value: String) : RainWalletContact {
        override fun equals(other: Any?): Boolean = other is Sms && other.value == value

        override fun hashCode(): Int = value.hashCode() + SMS_HASH_OFFSET

        override fun toString(): String = "RainWalletContact.Sms(…)"
    }
}

// Keeps an email and a phone number with the same text from hashing alike. File-private rather than
// a companion constant, which Kotlin would hoist onto the published interface as a public field.
private const val SMS_HASH_OFFSET = 31

@JvmSynthetic
internal fun RainWalletContact.toBacking(): LoginContact = when (this) {
    is RainWalletContact.Email -> LoginContact.Email(value)
    is RainWalletContact.Sms -> LoginContact.Sms(value)
}
