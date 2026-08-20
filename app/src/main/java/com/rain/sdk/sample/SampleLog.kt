package com.rain.sdk.sample

import android.util.Log

object SampleLog {
    const val TAG = "RainSample"

    fun d(area: String, msg: String) {
        Log.d(TAG, "[$area] $msg")
    }

    fun i(area: String, msg: String) {
        Log.i(TAG, "[$area] $msg")
    }

    fun w(area: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.w(TAG, "[$area] $msg", t) else Log.w(TAG, "[$area] $msg")
    }

    fun e(area: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.e(TAG, "[$area] $msg", t) else Log.e(TAG, "[$area] $msg")
    }

    fun maskToken(value: String?): String {
        if (value.isNullOrBlank()) return "<empty>"
        if (value.length <= 8) return "***"
        return "${value.take(4)}…${value.takeLast(4)} (len=${value.length})"
    }

    fun maskEmail(value: String?): String {
        if (value.isNullOrBlank()) return "<empty>"
        val at = value.indexOf('@')
        if (at <= 1) return "***"
        val name = value.substring(0, at)
        val domain = value.substring(at)
        val head = name.take(1)
        return "$head${"*".repeat((name.length - 1).coerceAtLeast(1))}$domain"
    }
}
