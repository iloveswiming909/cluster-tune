package com.aure.clustertune.root

import android.content.Context
import android.util.Log
import java.io.File

/**
 * PServer execution that never relies on stdout capture.
 *
 * Mechanism mirrors Odin's own Settings app (decompiled
 * com.odin2.common.PServiceBridgeV2 + ExecuteScriptTask): each command
 * is sent to PServer as a separate fire-and-forget transaction with the
 * "no stdout" flag ("0"). On the Odin 2 Mini, PServer's stdout-capture
 * path ("1") is broken, but fire-and-forget writes land as root
 * (u:r:pservice:s0, full caps).
 *
 * Because there is no usable stdout on such devices:
 *  - probe() verifies execution by writing a marker to an app-owned
 *    EXTERNAL file (via echo, flag 0) and reading it back with the File
 *    API, retrying briefly since fire-and-forget may return before the
 *    child process finishes.
 *  - executeScript() runs the script line by line, each fire-and-forget.
 *  - readText() uses direct File I/O (sysfs cpufreq nodes are
 *    world-readable).
 *
 * Ordered after "pserver-stdout" so stdout-capable devices are
 * unaffected.
 */
class PServerFireAndForgetExecutionMethod(
    private val context: Context,
    private val rootExec: PServerRootExecutor = RootExec(),
) : PrivilegedExecutionMethod {
    override val id: String = "pserver-noout"

    override fun probe(): ExecutionProbeResult {
        if (!rootExec.pServerAvailable) {
            Log.d(TAG, "probe: PServerBinder not available")
            return ExecutionProbeResult(
                isAvailable = false,
                supportsStdout = false,
                failureReason = "PServerBinder not available",
            )
        }
        val markerFile = File(probeDirectory(), "noout-probe.txt")
        runCatching { markerFile.delete() }
        val quotedPath = shellQuote(markerFile.absolutePath)

        // Fire-and-forget write + make it app-readable. Combine into one
        // command so ordering is guaranteed on PServer's side.
        val command = "echo $PROBE_MARKER > $quotedPath && chmod 666 $quotedPath"
        val execResult = rootExec.executeAsRoot(command, captureStdout = false)
        Log.d(TAG, "probe: wrote marker to ${markerFile.absolutePath}, execSuccess=${execResult.isSuccess}, exc=${execResult.exceptionOrNull()?.message}")

        // Fire-and-forget may return before the child finishes writing.
        // Poll the file briefly.
        var readBack: String? = null
        for (attempt in 1..10) {
            readBack = runCatching {
                markerFile.takeIf { it.isFile }?.readText()?.trim()
            }.getOrNull()
            if (readBack == PROBE_MARKER) break
            Thread.sleep(50)
        }
        Log.d(TAG, "probe: readBack='$readBack' (expected '$PROBE_MARKER'), fileExists=${markerFile.isFile}, canRead=${markerFile.canRead()}")

        return if (readBack == PROBE_MARKER) {
            Log.d(TAG, "probe: AVAILABLE")
            ExecutionProbeResult(isAvailable = true, supportsStdout = false)
        } else {
            Log.d(TAG, "probe: NOT available - write did not land or unreadable")
            ExecutionProbeResult(
                isAvailable = false,
                supportsStdout = false,
                failureReason = "PServer fire-and-forget write did not land",
            )
        }
    }

    override fun executeScript(scriptName: String, scriptContents: String): Result<String?> {
        return runCatching {
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
        return runCatching {
            File(path).readText().trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    override fun makeReadable(path: String): Boolean {
        return rootExec.executeAsRoot("chmod 444 ${shellQuote(path)}", captureStdout = false).isSuccess
    }

    private fun probeDirectory(): File {
        // App-owned EXTERNAL storage: when PServer (root) writes here the
        // file is still readable by the app UID, unlike root-owned files
        // dropped into internal filesDir.
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "pserver-noout")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private companion object {
        const val TAG = "ClusterTuneNoout"
        const val PROBE_MARKER = "clustertune-exec-probe-ok"
    }
}
