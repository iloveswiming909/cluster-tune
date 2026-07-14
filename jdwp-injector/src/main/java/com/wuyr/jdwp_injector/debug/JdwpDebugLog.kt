package com.wuyr.jdwp_injector.debug

import android.util.Log

/**
 * A tiny in-memory log the wireless-debug setup UI can display live, so
 * pairing/connection issues can be diagnosed on-device without adb or
 * rebuilding. Also mirrors to Logcat.
 *
 * Compose-free (this is a library module). The app observes changes via
 * [setListener] and reads [snapshot].
 */
object JdwpDebugLog {
    private const val TAG = "ClusterTuneJdwpConn"
    private const val MAX = 200

    private val buffer = ArrayDeque<String>()
    private var listener: (() -> Unit)? = null

    /** Register a callback invoked whenever a line is added/cleared (on any thread). */
    fun setListener(l: (() -> Unit)?) { listener = l }

    /** Current lines, oldest first. */
    fun snapshot(): List<String> = synchronized(buffer) { buffer.toList() }

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
        synchronized(buffer) {
            buffer.addLast("[$ts] $message")
            while (buffer.size > MAX) buffer.removeFirst()
        }
        listener?.invoke()
    }

    fun clear() {
        synchronized(buffer) { buffer.clear() }
        listener?.invoke()
    }
}
