package com.rain.sdk.sample

import android.app.Application
import android.content.pm.ApplicationInfo
import timber.log.Timber

/** Plants a Timber tree so the SDK's internal diagnostics reach logcat in debug builds. */
class RainSampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable) {
            Timber.plant(Timber.DebugTree())
        }
    }
}
