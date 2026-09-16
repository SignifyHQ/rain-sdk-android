package com.rain.sdk.sample

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Encrypted copy of the last working credentials, read once at launch to resume the session. */
@Suppress("DEPRECATION")
class SessionStore(context: Context) {

    enum class Provider { Portal, RainWallet, Turnkey, Privy }

    // Unavailable store (broken keystore) behaves like a first run instead of crashing.
    private val prefs: SharedPreferences? = runCatching {
        EncryptedSharedPreferences.create(
            context,
            "rain_sample_session",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.onFailure { SampleLog.w("SessionStore", "unavailable: ${it.message}") }.getOrNull()

    init {
        // Keys an earlier sample wrote for the managed one-time-code flow before it moved to the
        // Rain Wallet card. Nothing reads them any more, and one holds a phone number, so they go.
        prefs?.edit()?.remove("turnkeyPhone")?.remove("turnkeyChannel")?.apply()
    }

    var provider: Provider?
        get() = prefs?.getString("provider", null)?.let { name -> Provider.entries.firstOrNull { it.name == name } }
        set(value) { prefs?.edit()?.putString("provider", value?.name)?.apply() }

    var rainApiKey: String by string("rainApiKey")
    var rainUserId: String by string("rainUserId")
    var portalSessionToken: String by string("portalSessionToken")
    var turnkeyOrgId: String by string("turnkeyOrgId")
    var turnkeyAuthProxyConfigId: String by string("turnkeyAuthProxyConfigId")
    var turnkeyEmail: String by string("turnkeyEmail")
    var rainWalletEmail: String by string("rainWalletEmail")
    var rainWalletPhone: String by string("rainWalletPhone")

    /** Name of the channel the recorded Rain Wallet session owner logged in on; blank means none recorded. */
    var rainWalletChannel: String by string("rainWalletChannel")
    var privyAppId: String by string("privyAppId")
    var privyAppClientId: String by string("privyAppClientId")
    var privyEmail: String by string("privyEmail")

    fun clear() {
        prefs?.edit()?.clear()?.apply()
    }

    private fun string(key: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): String =
            prefs?.getString(key, "") ?: ""

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            prefs?.edit()?.putString(key, value)?.apply()
        }
    }
}
