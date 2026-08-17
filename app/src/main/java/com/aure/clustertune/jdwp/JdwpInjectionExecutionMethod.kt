package com.aure.clustertune.jdwp

import android.annotation.SuppressLint
import android.os.Environment
import android.util.Log
import com.aure.clustertune.root.ExecutionProbeResult
import com.aure.clustertune.root.HostLaunchRequest
import com.aure.clustertune.root.PrivilegedExecutionMethod
import com.wuyr.jdwp_injector.adb.AdbClient
import com.wuyr.jdwp_injector.debug.JdwpDebugLog
import com.wuyr.jdwp_injector.debugger.Debugger
import java.io.File

/**
 * A [PrivilegedExecutionMethod] for v1.2.2's host-launch model.
 *
 * On an unrooted device it starts the privileged Binder host by injecting
 * `Runtime.getRuntime().exec("sh <wrapper>")` into a system debuggable
 * process (GameAssistant on Odin) over JDWP via wireless debugging.
 *
 * The wrapper just does what upstream's root methods do:
 * `cd '<workingDirectory>' && sh '<launcherScript>'`.
 */
class JdwpInjectionExecutionMethod(
    private val connectionProvider: () -> AdbConnectionInfo?,
    private val sharedShellProvider: (() -> AdbClient?)? = null,
    private val shellInvalidator: (() -> Unit)? = null,
    private val shellUseLock: Any = Any(),
    private val persistentInjector: ((targetPackage: String, command: String, pid: Int, trigger: () -> Unit) -> Boolean)? = null,
    private val targetPackage: String = GAME_ASSISTANT_PKG,
    private val sharedDir: File = defaultSharedDir(),
) : PrivilegedExecutionMethod {

    override val id: String = "jdwp-inject"

    override fun probe(): ExecutionProbeResult {
        val conn = connectionProvider()
        return if (conn != null) {
            JdwpDebugLog.d("probe(jdwp-inject): conn=${conn.host}:${conn.port} -> available")
            ExecutionProbeResult(isAvailable = true)
        } else {
            JdwpDebugLog.d("probe(jdwp-inject): no wireless connection")
            ExecutionProbeResult(
                isAvailable = false,
                failureReason = "Wireless debugging not connected",
            )
        }
    }

    override fun launchHost(request: HostLaunchRequest): Result<Unit> {
        val conn = connectionProvider()
            ?: return Result.failure(IllegalStateException("Wireless debugging not connected"))
        val shell = sharedShellProvider?.invoke()
            ?: return Result.failure(IllegalStateException("Could not open adb shell"))
        val wrapper = stageWrapper(request)
        val command = "sh ${wrapper.absolutePath}"

        return runCatching {
            val pid: Int
            synchronized(shellUseLock) { pid = findTargetPid(shell) }
            if (pid <= 0) throw IllegalStateException("GameAssistant is not running")

            val trigger = {
                synchronized(shellUseLock) {
                    shell.sendShellCommand("am attach-agent ${targetPackage} /")
                }
            }

            JdwpDebugLog.d("launchHost(jdwp): pid=$pid, command='$command'")
            Log.d(TAG, "launchHost: injecting '$command' into pid=$pid")

            if (persistentInjector != null) {
                val ok = persistentInjector.invoke(targetPackage, command, pid, trigger)
                if (!ok) throw IllegalStateException("Persistent JDWP injection failed")
            } else {
                injectExec(conn, command, pid, trigger)
            }
        }.onFailure {
            Log.w(TAG, "launchHost: failed", it)
            JdwpDebugLog.w("launchHost(jdwp): failed", it)
            runCatching { shellInvalidator?.invoke() }
        }
    }

    private fun stageWrapper(request: HostLaunchRequest): File {
        val dir = sharedDirFile()
        val wrapper = File(dir, "ct-launch-wrapper-${System.nanoTime().toString(16)}.sh")
        val originalLauncher = File(request.workingDirectory, request.launcherScript)
        // Copy the launcher into the shared, world-readable handoff directory. This
        // avoids two problems: (1) the original launcher is deleted immediately after
        // resolver.launchHost() returns, and (2) the injected system process may be
        // denied read access to the app's codeCacheDir (app_data_file).
        val launcherCopy = File(dir, "ct-launch-host-${System.nanoTime().toString(16)}.sh").also {
            it.writeText(originalLauncher.readText())
            it.setReadable(true, false)
        }
        wrapper.writeText(
            buildString {
                append("#!/system/bin/sh\n")
                append("cd ${shellQuote(request.workingDirectory)} && sh ${shellQuote(launcherCopy.absolutePath)}\n")
            },
        )
        wrapper.setReadable(true, false)
        return wrapper
    }

    private fun findTargetPid(adb: AdbClient): Int = runCatching {
        val rawOut = adb.sendShellCommand(
            "echo __CT_READ\"\"_BEGIN__; pidof ${targetPackage}; echo __CT_READ\"\"_END__",
        ).replace("\b", "").replace("\r", "")
        val b = rawOut.lastIndexOf(READ_BEGIN)
        val e = if (b >= 0) rawOut.indexOf(READ_END, b + READ_BEGIN.length) else -1
        val raw = if (b >= 0 && e > b) {
            rawOut.substring(b + READ_BEGIN.length, e)
        } else {
            rawOut
        }
        val pid = raw.split(Regex("\\s+"))
            .mapNotNull { it.trim().toIntOrNull() }
            .firstOrNull { it > 0 }
            ?: 0
        JdwpDebugLog.d(
            "findTargetPid: raw='${raw.replace("\n", "\\n").take(120)}' -> pid=$pid",
        )
        pid
    }.getOrElse { 0 }

    private fun injectExec(
        conn: AdbConnectionInfo,
        command: String,
        pid: Int,
        trigger: () -> Unit,
    ) {
        JdwpDebugLog.d("injectExec: attaching JDWP to pid=$pid")
        Debugger(AdbClient.connect2jdwp(conn.host, conn.port, pid)).use { debugger ->
            val threadId = debugger.setAndWaitForModificationEventArrive(
                "android.os.MessageQueue", "mMessages", "android.os.Message"
            ) { trigger() }
            val runtimeObjectId = debugger.invokeStaticMethod(
                "java.lang.Runtime", "getRuntime",
                returnTypeName = "java.lang.Runtime", threadId = threadId,
            ).second as Long
            debugger.invokeInstanceMethod(
                runtimeObjectId, "java.lang.Runtime", "exec",
                returnTypeName = "java.lang.Process", threadId = threadId,
                "java.lang.String" to command,
            )
        }
    }

    @SuppressLint("SdCardPath")
    private fun sharedDirFile(): File = sharedDir.apply { if (!exists()) mkdirs() }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    companion object {
        private const val TAG = "ClusterTuneJdwp"
        private const val READ_BEGIN = "__CT_READ_BEGIN__"
        private const val READ_END = "__CT_READ_END__"
        const val GAME_ASSISTANT_PKG = "com.odin2.gameassistant"
        private const val SHARED_DIR_NAME = "ClusterScripts"

        @Suppress("DEPRECATION")
        @SuppressLint("SdCardPath")
        fun defaultSharedDir(): File = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            SHARED_DIR_NAME,
        )
    }
}
