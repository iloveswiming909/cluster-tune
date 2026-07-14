package com.aure.clustertune.jdwp

import androidx.compose.runtime.mutableStateListOf
import android.util.Log

/**
 * A tiny in-memory, observable log the wireless-debug setup UI can display
 * live, so pairing/connection issues can be diagnosed on-device without adb
 * or rebuilding. Also mirrors to Logcat.
 */
object JdwpDebugLog {
    private const val TAG = "ClusterTuneJdwpConn"
    private const val MAX = 200

    // Observable by Compose.
    val lines = mutableStateListOf<String>()

    fun d(message: String) {
        Log.d(TAG, message)
        add(message)
    }

    fun w(message: String, t: Throwable? = null) {
        Log.w(TAG, message, t)
        add("! " + message + (t?.message?.let { ": $it" } ?: ""))
    }

    private fun add(message: String) {
        val ts = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())
        lines.add("[$ts] $message")
        while (lines.size > MAX) lines.removeAt(0)
    }

    fun clear() = lines.clear()
}
