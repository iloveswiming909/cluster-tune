package com.aure.clustertune.root

import android.content.Context
import java.io.File

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
