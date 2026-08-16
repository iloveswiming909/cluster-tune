package com.aure.clustertune.daemon

import com.aure.clustertune.root.ExecutionProbeResult
import com.aure.clustertune.root.PrivilegedExecutionMethod
import com.wuyr.jdwp_injector.debug.JdwpDebugLog
import java.io.File
import java.util.UUID

/**
 * Executes privileged scripts by handing them to the resident system-uid
 * daemon (see [SystemDaemonProtocol]) through files.
 *
 * Needs no network, no adb, no wireless debugging and no JDWP session — only a
 * daemon that was bootstrapped earlier this boot. That is the entire point:
 * per-app profile switching keeps working with Wi-Fi off.
 *
 * supportsStdout = TRUE, unlike the JDWP method. The daemon captures the
 * script's combined stdout+stderr and its exit code, so:
 *
 *  - the COMPLETION_MARKER contract added in 1.0.2 is satisfied normally
 *    rather than needing the fire-and-forget carve-out in RootCommandRunner;
 *  - the per-policy `ct-policy-failed:<id>` lines that v11 writes to stderr
 *    actually come back to the app instead of vanishing;
 *  - readText() works on nodes that are not world-readable (cpuinfo_min_freq
 *    is `-rw-rw---- system system` on the Odin 2 Mini) via a plain `cat` as
 *    system — no per-read JDWP injection.
 */
class SystemDaemonExecutionMethod(
    private val dir: File = SystemDaemonProtocol.daemonDir(),
    /** Bounds how long an apply may wait for the daemon before failing. */
    private val timeoutMs: Long = 8_000L,
) : PrivilegedExecutionMethod {

    override val id: String = METHOD_ID

    /**
     * Serialises request/response pairs. The daemon processes requests one at a
     * time in a single loop; letting several applies interleave here would work
     * but makes the logs unreadable and complicates timeout attribution.
     */
    private val requestLock = Any()

    /** Result of one daemon round-trip. */
    private data class DaemonResult(val exitCode: Int, val output: String)

    /** True if a daemon wrote a heartbeat recently enough to still be alive. */
    fun isDaemonAlive(): Boolean {
        val hb = File(dir, SystemDaemonProtocol.HEARTBEAT_FILE)
        if (!hb.isFile) return false
        // Trust the file's own mtime rather than the epoch seconds it contains:
        // mtime cannot be stale-but-plausible if the daemon died mid-write.
        val age = System.currentTimeMillis() - hb.lastModified()
        return age in 0..SystemDaemonProtocol.HEARTBEAT_STALE_MS
    }

    /** Version of the daemon currently running, or null if none/unknown. */
    fun runningDaemonVersion(): Int? =
        runCatching { File(dir, SystemDaemonProtocol.VERSION_FILE).readText().trim().toInt() }
            .getOrNull()

    /** Ask a running daemon to exit (e.g. before bootstrapping a newer one). */
    fun requestStop() {
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            File(dir, SystemDaemonProtocol.STOP_FILE).writeText("1")
        }
    }

    override fun probe(): ExecutionProbeResult {
        return if (isDaemonAlive()) {
            ExecutionProbeResult(isAvailable = true, supportsStdout = true)
        } else {
            ExecutionProbeResult(
                isAvailable = false,
                supportsStdout = false,
                failureReason = "System daemon not running",
            )
        }
    }

    /**
     * Runs [scriptContents] as system and returns its combined output.
     *
     * A non-zero exit code is logged but does NOT fail this call. That is
     * deliberate: 1.0.2/v11 decide success from the completion marker and from
     * the sysfs read-back verification (which now names the offending policy in
     * buildVerificationFailureDetail). Throwing here on rc != 0 would replace
     * those precise diagnostics with a generic exception and would also fight
     * v11's per-policy subshell isolation, whose whole purpose is to let some
     * policies fail while the rest still apply.
     */
    override fun executeScript(
        scriptName: String,
        scriptContents: String,
        captureResult: Boolean,
    ): Result<String?> {
        if (!isDaemonAlive()) {
            return Result.failure(IllegalStateException("System daemon not running"))
        }
        return synchronized(requestLock) {
            runCatching {
                val result = dispatch(scriptContents)
                if (result.exitCode != 0) {
                    JdwpDebugLog.w(
                        "daemon: '$scriptName' exited rc=${result.exitCode}; " +
                            "leaving success/failure to the marker + verification check",
                    )
                }
                result.output
            }
        }
    }

    override fun readText(path: String): String? {
        if (!isDaemonAlive()) return null
        val script = buildString {
            appendLine("#!/system/bin/sh")
            appendLine("cat ${shellQuote(path)} 2>/dev/null")
        }
        return synchronized(requestLock) {
            runCatching { dispatch(script) }
                .getOrNull()
                ?.takeIf { it.exitCode == 0 }
                ?.output
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
    }

    // ---- internals ----------------------------------------------------------

    /**
     * Writes one request, waits for its result, returns exit code + output.
     * Throws only if the daemon did not answer within [timeoutMs].
     */
    private fun dispatch(scriptContents: String): DaemonResult {
        if (!dir.exists()) dir.mkdirs()
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val tmp = File(dir, "tmp-req-$id.sh")
        val req = File(dir, "req-$id.sh")
        val outFile = File(dir, "res-$id.out")
        val rcFile = File(dir, "res-$id.rc")

        try {
            // Write-then-rename: the daemon must never see a partial script.
            tmp.writeText(scriptContents)
            tmp.setReadable(true, false)
            if (!tmp.renameTo(req)) {
                tmp.delete()
                throw IllegalStateException("Could not stage request $id")
            }
            JdwpDebugLog.d("daemon: dispatched request $id (${scriptContents.length} bytes)")

            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                // rcFile is written LAST by the daemon, so its presence means the
                // .out file is already complete. Never poll on .out.
                if (rcFile.isFile) {
                    val rc = runCatching { rcFile.readText().trim().toInt() }.getOrDefault(-1)
                    val out = runCatching { outFile.readText() }.getOrDefault("")
                    JdwpDebugLog.d("daemon: request $id finished rc=$rc (${out.length} bytes out)")
                    return DaemonResult(rc, out)
                }
                Thread.sleep(POLL_MS)
            }
            JdwpDebugLog.w("daemon: request $id TIMED OUT after ${timeoutMs}ms")
            throw IllegalStateException("System daemon did not respond in ${timeoutMs}ms")
        } finally {
            // Never leave debris behind; a stale req-*.sh would be re-executed by
            // the daemon on its next loop.
            runCatching { tmp.delete() }
            runCatching { req.delete() }
            runCatching { outFile.delete() }
            runCatching { rcFile.delete() }
        }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    companion object {
        const val METHOD_ID = "system-daemon"
        private const val POLL_MS = 100L
    }
}
