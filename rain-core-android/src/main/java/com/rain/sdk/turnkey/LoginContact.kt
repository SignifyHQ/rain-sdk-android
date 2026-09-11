package com.rain.sdk.turnkey

/**
 * Where a managed-mode login code is delivered, and the identity the account is keyed on. A first
 * login signs the user up under this contact; a later login finds the account by email for [Email]
 * and by phone number for [Sms]. The same person arriving through the other channel is a new
 * account unless the two contacts were linked outside the SDK. `sendLoginCode` canonicalizes the
 * value once (trim for an email; trim, separator removal and an E.164 check for a phone number)
 * and sends that same string on confirm. `toString()` hides the value, which is personal data.
 * Internal API ([InternalRainTurnkeyApi]): hosts see it through the RainWallet provider.
 */
@InternalRainTurnkeyApi
sealed interface LoginContact {
    /** The contact as given; canonicalization happens in `sendLoginCode`, not here. */
    val value: String

    /** An email address. A blank one makes `sendLoginCode` throw `RainError.InvalidConfig`. */
    data class Email(override val value: String) : LoginContact {
        override fun toString(): String = "LoginContact.Email(…)"
    }

    /**
     * A phone number in E.164 form: `+`, the country code and the number, digits only, at most 15
     * of them, for example `+13214567890`. Spaces, dots, hyphens and parentheses are removed before
     * the check; anything else outside E.164 makes `sendLoginCode` throw `RainError.InvalidConfig`,
     * and so does a parenthesised trunk zero (`+44 (0) 20 ...`), which stripping would fold into a
     * different number. A national number without its country code is not converted.
     */
    data class Sms(override val value: String) : LoginContact {
        override fun toString(): String = "LoginContact.Sms(…)"
    }
}
