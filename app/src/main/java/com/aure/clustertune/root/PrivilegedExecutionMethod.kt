package com.aure.clustertune.root

import android.content.Context
import android.content.pm.PackageManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

private const val PROBE_MARKER = "clustertune-exec-probe-ok"

data class ExecutionProbeResult(
    val isAvailable: Boolean,
    val supportsStdout: Boolean,
    val failureReason: String? = null,
)

interface PrivilegedExecutionMethod {
    val id: String

    fun probe(): ExecutionProbeResult

    fun executeScript(
        scriptName: String,
        scriptContents: String,
    ): Result<String?>

    fun readText(path: String): String?
}

class PrivilegedExecutionResolver(
    private val methods: List<PrivilegedExecutionMethod>,
) {
    private var cachedMethod: PrivilegedExecutionMethod? = null
    private var cachedProbe: ExecutionProbeResult? = null

    val isAvailable: Boolean
        get() = selectedMethod() != null

    val selectedMethodId: String?
        get() = selectedMethod()?.id

    fun selectedMethod(forceReprobe: Boolean = false): PrivilegedExecutionMethod? {
        if (!forceReprobe) {
            cachedMethod?.let { return it }
        }
        cachedMethod = null
        cachedProbe = null
        methods.forEach { method ->
            val probe = method.probe()
            if (probe.isAvailable) {
                cachedMethod = method
                cachedProbe = probe
                return method
            }
        }
        return null
    }

    fun executeScript(
        scriptName: String,
        scriptContents: String,
    ): Result<String?> {
        val method = selectedMethod()
            ?: return Result.failure(IllegalStateException("No privileged execution method available"))
        return method.executeScript(scriptName, scriptContents)
    }

    fun readText(path: String): String? {
        return selectedMethod()?.readText(path)
    }

    companion object {
        fun default(context: Context): PrivilegedExecutionResolver {
            val rootExec = RootExec()
            return PrivilegedExecutionResolver(
                listOf(
                    PServerStdoutExecutionMethod(context, rootExec),
                    PServerFileOutputExecutionMethod(context, rootExec),
                    RootShellExecutionMethod(),
                    ShizukuExecutionMethod(),
                ),
            )
        }
    }
}

class PServerStdoutExecutionMethod(
    private val context: Context,
    private val rootExec: RootExec = RootExec(),
) : PrivilegedExecutionMethod {
    override val id: String = "pserver-stdout"

    override fun probe(): ExecutionProbeResult {
        if (!rootExec.pServerAvailable) {
            return ExecutionProbeResult(
                isAvailable = false,
                supportsStdout = false,
                failureReason = "PServerBinder not available",
            )
        }
        val output = rootExec.executeAsRoot("echo $PROBE_MARKER").getOrNull()?.trim()
        return if (output == PROBE_MARKER) {
            ExecutionProbeResult(isAvailable = true, supportsStdout = true)
        } else {
            ExecutionProbeResult(
                isAvailable = false,
                supportsStdout = false,
                failureReason = "PServer did not return stdout",
            )
        }
    }

    override fun executeScript(scriptName: String, scriptContents: String): Result<String?> {
        return runCatching {
            val scriptFile = writeScriptFile(context, scriptName, scriptContents)
            rootExec.executeAsRoot("sh ${shellQuote(scriptFile.absolutePath)}").getOrThrow()
        }
    }

    override fun readText(path: String): String? {
        return rootExec.executeAsRoot("cat ${shellQuote(path)} 2>/dev/null")
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }
}

class PServerFileOutputExecutionMethod(
    private val context: Context,
    private val rootExec: RootExec = RootExec(),
) : PrivilegedExecutionMethod {
    override val id: String = "pserver-file-output"

    override fun probe(): ExecutionProbeResult {
        if (!rootExec.pServerAvailable) {
            return ExecutionProbeResult(
                isAvailable = false,
                supportsStdout = false,
                failureReason = "PServerBinder not available",
            )
        }
        val outputFile = outputFile("probe.out")
        outputFile.delete()
        val command = buildString {
            append("echo $PROBE_MARKER > ${shellQuote(outputFile.absolutePath)}")
            append(" && chmod 666 ${shellQuote(outputFile.absolutePath)}")
        }
        val executed = rootExec.executeAsRoot(command).isSuccess
        val marker = outputFile.takeIf { it.isFile }?.readText()?.trim()
        return if (executed && marker == PROBE_MARKER) {
            ExecutionProbeResult(isAvailable = true, supportsStdout = false)
        } else {
            ExecutionProbeResult(
                isAvailable = false,
                supportsStdout = false,
                failureReason = "PServer could not write readable fallback output",
            )
        }
    }

    override fun executeScript(scriptName: String, scriptContents: String): Result<String?> {
        return runCatching {
            val scriptFile = writeScriptFile(context, scriptName, scriptContents)
            val outputFile = outputFile("${scriptFile.name}.out")
            outputFile.delete()
            val command = buildString {
                append("sh ${shellQuote(scriptFile.absolutePath)}")
                append(" > ${shellQuote(outputFile.absolutePath)} 2>&1")
                append("; status=${'$'}?")
                append("; chmod 666 ${shellQuote(outputFile.absolutePath)} 2>/dev/null")
                append("; exit ${'$'}status")
            }
            rootExec.executeAsRoot(command).getOrThrow()
            outputFile.takeIf { it.isFile }
                ?.readText()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
    }

    override fun readText(path: String): String? {
        val outputFile = outputFile("read-text.out")
        outputFile.delete()
        val command = buildString {
            append("cat ${shellQuote(path)} > ${shellQuote(outputFile.absolutePath)} 2>/dev/null")
            append(" && chmod 666 ${shellQuote(outputFile.absolutePath)}")
        }
        rootExec.executeAsRoot(command).getOrNull() ?: return null
        return outputFile.takeIf { it.isFile }
            ?.readText()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun outputFile(name: String): File {
        val dir = File(context.filesDir, "root-output")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, name)
    }
}

class RootShellExecutionMethod(
    private val runner: RootShellCommandRunner = RootShellCommandRunner(),
) : PrivilegedExecutionMethod {
    override val id: String = "root-shell"

    override fun probe(): ExecutionProbeResult {
        val result = runner.run("echo $PROBE_MARKER", timeoutSeconds = 10)
        return if (result.exitCode == 0 && result.stdout.trim() == PROBE_MARKER) {
            ExecutionProbeResult(isAvailable = true, supportsStdout = true)
        } else {
            ExecutionProbeResult(
                isAvailable = false,
                supportsStdout = false,
                failureReason = result.failureMessage ?: "su did not return expected probe output",
            )
        }
    }

    override fun executeScript(scriptName: String, scriptContents: String): Result<String?> {
        return runner.run(scriptContents, timeoutSeconds = 30).toResult()
    }

    override fun readText(path: String): String? {
        return runner.run("cat ${shellQuote(path)} 2>/dev/null", timeoutSeconds = 10)
            .takeIf { it.exitCode == 0 }
            ?.stdout
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }
}

class ShizukuExecutionMethod(
    private val runner: ShizukuCommandRunner = ShizukuCommandRunner(),
) : PrivilegedExecutionMethod {
    override val id: String = "shizuku"

    override fun probe(): ExecutionProbeResult {
        if (!runner.isBinderAlive()) {
            return ExecutionProbeResult(
                isAvailable = false,
                supportsStdout = false,
                failureReason = "Shizuku binder not available",
            )
        }
        if (!runner.hasPermission()) {
            return ExecutionProbeResult(
                isAvailable = false,
                supportsStdout = false,
                failureReason = "Shizuku permission not granted",
            )
        }
        val result = runner.run("echo $PROBE_MARKER", timeoutSeconds = 10)
        return if (result.exitCode == 0 && result.stdout.trim() == PROBE_MARKER) {
            ExecutionProbeResult(isAvailable = true, supportsStdout = true)
        } else {
            ExecutionProbeResult(
                isAvailable = false,
                supportsStdout = false,
                failureReason = result.failureMessage ?: "Shizuku did not return expected probe output",
            )
        }
    }

    override fun executeScript(scriptName: String, scriptContents: String): Result<String?> {
        return runner.run(scriptContents, timeoutSeconds = 30).toResult()
    }

    override fun readText(path: String): String? {
        return runner.run("cat ${shellQuote(path)} 2>/dev/null", timeoutSeconds = 10)
            .takeIf { it.exitCode == 0 }
            ?.stdout
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }
}

class RootShellCommandRunner {
    fun run(command: String, timeoutSeconds: Long): ShellCommandResult {
        return runCatching {
            val process = ProcessBuilder("su", "-c", command).start()
            process.collectOutput(timeoutSeconds)
        }.getOrElse { throwable ->
            ShellCommandResult(
                exitCode = -1,
                stdout = "",
                stderr = "",
                failureMessage = throwable.message ?: throwable::class.java.simpleName,
            )
        }
    }
}

class ShizukuCommandRunner {
    fun isBinderAlive(): Boolean {
        return runCatching {
            shizukuClass()
                .getMethod("pingBinder")
                .invoke(null) as Boolean
        }.getOrDefault(false)
    }

    fun hasPermission(): Boolean {
        return runCatching {
            val permission = shizukuClass()
                .getMethod("checkSelfPermission")
                .invoke(null) as Int
            permission == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    fun requestPermission(requestCode: Int): Result<Unit> {
        return runCatching {
            shizukuClass()
                .getMethod("requestPermission", Int::class.javaPrimitiveType)
                .invoke(null, requestCode)
            Unit
        }
    }

    fun run(command: String, timeoutSeconds: Long): ShellCommandResult {
        return runCatching {
            val process = newProcess(arrayOf("sh", "-c", command))
            process.collectOutput(timeoutSeconds)
        }.getOrElse { throwable ->
            ShellCommandResult(
                exitCode = -1,
                stdout = "",
                stderr = "",
                failureMessage = throwable.message ?: throwable::class.java.simpleName,
            )
        }
    }

    private fun newProcess(command: Array<String>): Process {
        val method = shizukuClass().getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(null, command, null, null) as Process
    }

    private fun shizukuClass(): Class<*> {
        return Class.forName("rikka.shizuku.Shizuku")
    }
}

data class ShellCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val failureMessage: String? = null,
) {
    fun toResult(): Result<String?> {
        return if (exitCode == 0) {
            Result.success(stdout.trim().takeIf { it.isNotEmpty() })
        } else {
            Result.failure(
                IllegalStateException(
                    failureMessage
                        ?: stderr.trim().takeIf { it.isNotEmpty() }
                        ?: "Command failed with exit code $exitCode",
                ),
            )
        }
    }
}

private fun Process.collectOutput(timeoutSeconds: Long): ShellCommandResult {
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    val stdoutThread = inputStream.copyToInBackground(stdout)
    val stderrThread = errorStream.copyToInBackground(stderr)
    val deadline = System.currentTimeMillis() + timeoutSeconds * 1_000L
    var exitCode: Int? = null
    while (System.currentTimeMillis() < deadline) {
        val value = runCatching { exitValue() }.getOrNull()
        if (value != null) {
            exitCode = value
            break
        }
        Thread.sleep(50)
    }
    if (exitCode == null) {
        destroyForcibly()
        stdoutThread.join(1_000)
        stderrThread.join(1_000)
        return ShellCommandResult(
            exitCode = -1,
            stdout = stdout.toString(),
            stderr = stderr.toString(),
            failureMessage = "Command timed out after ${timeoutSeconds}s",
        )
    }
    stdoutThread.join(1_000)
    stderrThread.join(1_000)
    return ShellCommandResult(
        exitCode = exitCode,
        stdout = stdout.toString(),
        stderr = stderr.toString(),
    )
}

private fun InputStream.copyToInBackground(output: ByteArrayOutputStream): Thread {
    return Thread {
        use { input ->
            input.copyTo(output)
        }
    }.also { thread ->
        thread.isDaemon = true
        thread.start()
    }
}

private fun writeScriptFile(
    context: Context,
    scriptName: String,
    scriptContents: String,
): File {
    val scriptDir = File(context.filesDir, "root-scripts")
    if (!scriptDir.exists()) {
        scriptDir.mkdirs()
    }
    val scriptFile = File(scriptDir, scriptName)
    scriptFile.writeText(scriptContents)
    scriptFile.setReadable(true, false)
    scriptFile.setExecutable(true, false)
    return scriptFile
}

internal fun shellQuote(value: String): String {
    return "'" + value.replace("'", "'\\''") + "'"
}
