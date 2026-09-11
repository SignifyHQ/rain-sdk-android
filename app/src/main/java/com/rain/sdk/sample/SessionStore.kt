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

    enum class Provider { Portal, Turnkey, Privy }

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

    var provider: Provider?
        get() = prefs?.getString("provider", null)?.let { name -> Provider.entries.firstOrNull { it.name == name } }
        set(value) { prefs?.edit()?.putString("provider", value?.name)?.apply() }

    var rainApiKey: String by string("rainApiKey")
    var rainUserId: String by string("rainUserId")
    var portalSessionToken: String by string("portalSessionToken")
    var turnkeyOrgId: String by string("turnkeyOrgId")
    var turnkeyAuthProxyConfigId: String by string("turnkeyAuthProxyConfigId")
    var turnkeyEmail: String by string("turnkeyEmail")
    var turnkeyPhone: String by string("turnkeyPhone")

    /** Name of the channel the recorded Turnkey session owner logged in on; blank means none recorded. */
    var turnkeyChannel: String by string("turnkeyChannel")
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
