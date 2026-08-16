package com.aure.clustertune.daemon

import com.aure.clustertune.root.PrivilegedExecutionMethod
import com.wuyr.jdwp_injector.debug.JdwpDebugLog
import java.io.File

/**
 * Starts the resident system-uid daemon, once per boot, using the existing
 * JDWP injection path.
 *
 * This is the ONLY moment Wi-Fi and wireless debugging are required. After it
 * succeeds, applies go through [SystemDaemonExecutionMethod] and work offline
 * until the next reboot (or until the daemon is killed).
 *
 * The vendor app's involvement is exactly one Runtime.exec of `sh <path>` —
 * byte-for-byte the same shape of command ClusterTune already injects for a
 * normal apply. Nothing is added to GameAssistant.
 */
class SystemDaemonBootstrapper(
    private val dir: File = SystemDaemonProtocol.daemonDir(),
    private val daemonMethod: SystemDaemonExecutionMethod = SystemDaemonExecutionMethod(),
) {

    sealed interface Result {
        /** A daemon of the current version was already running. */
        data object AlreadyRunning : Result
        /** Daemon was launched and its heartbeat was observed. */
        data object Started : Result
        /** Injection ran but no heartbeat appeared — see [reason]. */
        data class Failed(val reason: String) : Result
    }

    /**
     * Ensure a current-version daemon is running.
     *
     * [injector] is the JDWP execution method (or anything that can run a script
     * as system). Blocking; call off the main thread.
     */
    fun ensureRunning(injector: PrivilegedExecutionMethod): Result {
        if (daemonMethod.isDaemonAlive()) {
            val running = daemonMethod.runningDaemonVersion()
            if (running == SystemDaemonProtocol.DAEMON_VERSION) {
                JdwpDebugLog.d("daemon: already running (v$running)")
                return Result.AlreadyRunning
            }
            // An older daemon is serving requests with stale logic. Retire it
            // before starting the new one, otherwise the single-instance guard
            // in the new script would just make it exit immediately.
            JdwpDebugLog.d("daemon: v$running running, want v${SystemDaemonProtocol.DAEMON_VERSION} — stopping old")
            daemonMethod.requestStop()
            waitForDaemonGone(STOP_TIMEOUT_MS)
        }

        if (!dir.exists() && !dir.mkdirs()) {
            return Result.Failed("Could not create ${dir.absolutePath}")
        }

        // Stage both scripts as the app. They are only ever READ by sh (never
        // exec'd) because /sdcard is noexec — hence `sh <path>` everywhere.
        val daemonFile = File(dir, SystemDaemonProtocol.DAEMON_SCRIPT_NAME)
        val bootstrapFile = File(dir, SystemDaemonProtocol.BOOTSTRAP_SCRIPT_NAME)
        runCatching {
            daemonFile.writeText(SystemDaemonProtocol.daemonScript(dir.absolutePath))
            daemonFile.setReadable(true, false)
            bootstrapFile.writeText(SystemDaemonProtocol.bootstrapScript(daemonFile.absolutePath))
            bootstrapFile.setReadable(true, false)
        }.onFailure {
            return Result.Failed("Could not stage daemon scripts: ${it.message}")
        }

        // Clear any stale heartbeat so we cannot mistake a dead daemon's leftover
        // file for proof that the new one started.
        runCatching { File(dir, SystemDaemonProtocol.HEARTBEAT_FILE).delete() }

        JdwpDebugLog.d("daemon: bootstrapping via ${injector.id}")
        val exec = injector.executeScript(
            scriptName = SystemDaemonProtocol.BOOTSTRAP_SCRIPT_NAME,
            scriptContents = bootstrapFile.readText(),
            captureResult = false,
        )
        if (exec.isFailure) {
            val why = exec.exceptionOrNull()?.message ?: "unknown"
            JdwpDebugLog.w("daemon: bootstrap injection FAILED: $why")
            return Result.Failed("Injection failed: $why")
        }

        // The injection is fire-and-forget, so success above proves nothing about
        // the daemon. The heartbeat is the only real evidence it is alive.
        return if (waitForHeartbeat(START_TIMEOUT_MS)) {
            JdwpDebugLog.d("daemon: heartbeat observed — daemon is live")
            Result.Started
        } else {
            JdwpDebugLog.w("daemon: no heartbeat within ${START_TIMEOUT_MS}ms after injection")
            Result.Failed("Daemon did not start (no heartbeat in ${START_TIMEOUT_MS}ms)")
        }
    }

    private fun waitForHeartbeat(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (daemonMethod.isDaemonAlive()) return true
            Thread.sleep(POLL_MS)
        }
        return false
    }

    private fun waitForDaemonGone(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!daemonMethod.isDaemonAlive()) return
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        const val POLL_MS = 200L
        const val START_TIMEOUT_MS = 6_000L
        const val STOP_TIMEOUT_MS = 4_000L
    }
}
