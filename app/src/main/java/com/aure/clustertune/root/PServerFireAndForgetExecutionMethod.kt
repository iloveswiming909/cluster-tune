package com.aure.clustertune.root

import android.content.Context
import java.io.File

/**
 * PServer execution that never relies on stdout capture.
 *
 * This is the mechanism Odin's own Settings app uses (see decompiled
 * com.odin2.common.PServiceBridgeV2 + ExecuteScriptTask): send each
 * command to PServer as a separate fire-and-forget transaction with the
 * "no stdout" flag. On the Odin 2 Mini, PServer's stdout-capture path is
 * broken — requesting stdout returns empty AND the command may not
 * execute — but fire-and-forget writes land correctly as root
 * (u:r:pservice:s0, full caps).
 *
 * Because there is no usable stdout on such devices:
 *  - probe() verifies execution by writing a marker to an app-owned file
 *    via `echo > file` and reading it back with the File API.
 *  - executeScript() runs the apply script one line at a time, exactly
 *    like Odin's ExecuteScriptTask loop.
 *  - readText() uses direct File I/O (sysfs cpufreq nodes are
 *    world-readable), not PServer stdout.
 *
 * This method is ordered after "pserver-stdout" so that devices whose
 * PServer stdout works keep using the richer path; the Mini and any
 * similarly-affected AYN device fall through to this one.
 */
class PServerFireAndForgetExecutionMethod(
    private val context: Context,
    private val rootExec: PServerRootExecutor = RootExec(),
) : PrivilegedExecutionMethod {
    override val id: String = "pserver-noout"

    override fun probe(): ExecutionProbeResult {
        if (!rootExec.pServerAvailable) {
            return ExecutionProbeResult(
                isAvailable = false,
                supportsStdout = false,
                failureReason = "PServerBinder not available",
            )
        }
        val markerFile = File(probeDirectory(), "noout-probe.txt")
        markerFile.delete()
        val quotedPath = shellQuote(markerFile.absolutePath)
        // Fire-and-forget: write the marker, then make it app-readable.
        rootExec.executeAsRoot("echo $PROBE_MARKER > $quotedPath", captureStdout = false)
        rootExec.executeAsRoot("chmod 666 $quotedPath", captureStdout = false)
        val readBack = markerFile.takeIf { it.isFile }?.readText()?.trim()
        return if (readBack == PROBE_MARKER) {
            ExecutionProbeResult(isAvailable = true, supportsStdout = false)
        } else {
            ExecutionProbeResult(
                isAvailable = false,
                supportsStdout = false,
                failureReason = "PServer fire-and-forget write did not land",
            )
        }
    }

    override fun executeScript(scriptName: String, scriptContents: String): Result<String?> {
        return runCatching {
            // Match Odin's ExecuteScriptTask: run the script line by line,
            // each as a separate fire-and-forget PServer command. Skip the
            // shebang and blank lines.
            scriptContents
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .forEach { line ->
                    rootExec.executeAsRoot(line, captureStdout = false).getOrThrow()
                }
            null
        }
    }

    override fun readText(path: String): String? {
        // Direct File I/O — the sysfs cpufreq nodes we care about are
        // world-readable, and PServer stdout is unreliable on affected
        // devices.
        return runCatching {
            File(path).readText().trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    override fun makeReadable(path: String): Boolean {
        return rootExec.executeAsRoot("chmod 444 ${shellQuote(path)}", captureStdout = false).isSuccess
    }

    private fun probeDirectory(): File {
        val dir = File(context.filesDir, "pserver-noout")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private companion object {
        const val PROBE_MARKER = "clustertune-exec-probe-ok"
    }
}
