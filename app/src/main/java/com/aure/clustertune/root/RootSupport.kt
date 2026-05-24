package com.aure.clustertune.root

import android.content.Context
import android.util.Log
import java.io.File

object RootSupport {

    private const val WRITE_TAG = "ClusterTuneWrite"

    fun runRootCommand(command: String): String? {
        return RootExec().executeAsRoot(command).getOrNull()
    }

    /**
     * Executes a script via PServer.
     *
     * On the Odin 3, ClusterTune historically wrote the script to the
     * app's filesDir and shelled it (`sh /data/data/.../apply-frequencies.sh`).
     * On the Odin 2 Mini we have evidence the apply step silently no-ops
     * via that path. Two likely causes: the Mini's pservice SELinux domain
     * cannot read app_data_file inodes, or the Mini's pservice mangles
     * the multi-line script transport.
     *
     * To work around both, we try inline execution first: collapse the
     * script body into a single `sh -c "..."` invocation with the lines
     * joined by `;` so it goes through PServer as ONE simple command,
     * exactly like the per-file `cat` reads that we know already work
     * end-to-end on the Mini. If that returns null/empty (the inline
     * path didn't reach the kernel), we fall back to the original
     * file-based approach for the Odin 3 case.
     */
    fun runGeneratedScript(
        context: Context,
        scriptName: String,
        scriptContents: String,
    ): String? {
        // Strip shebang and empty lines, join with `;` for inline exec.
        val lines = scriptContents
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()

        Log.d(WRITE_TAG, "runGeneratedScript called with ${lines.size} effective lines, name=$scriptName")
        lines.forEachIndexed { index, line ->
            Log.d(WRITE_TAG, "  line[$index] = $line")
        }

        if (lines.isNotEmpty()) {
            // Wrap in `sh -c '...'`. Quote each line with single quotes
            // for the outer shell. We escape any single quote inside the
            // line itself with the classic '\'' trick.
            val inlineBody = lines.joinToString("; ") { it }
            val escaped = inlineBody.replace("'", "'\\''")
            val inlineCommand = "sh -c '$escaped'"
            Log.d(WRITE_TAG, "Trying inline path: $inlineCommand")
            val inlineResult = runRootCommand(inlineCommand)
            Log.d(WRITE_TAG, "Inline result = ${inlineResult?.let { "'${it.take(120)}'(${it.length})" } ?: "null"}")
            // The frequency-apply script produces no stdout on success,
            // so we cannot distinguish "succeeded silently" from "did
            // nothing" just from the return value. Always also try the
            // file-based path so at least one of the two definitely ran
            // — overwriting scaling_max_freq twice with the same value
            // is harmless.
        }

        return runFileBasedScript(context, scriptName, scriptContents)
    }

    private fun runFileBasedScript(
        context: Context,
        scriptName: String,
        scriptContents: String,
    ): String? {
        val scriptDir = File(context.filesDir, "root-scripts")
        if (!scriptDir.exists()) {
            scriptDir.mkdirs()
        }
        val scriptFile = File(scriptDir, scriptName)
        scriptFile.writeText(scriptContents)
        scriptFile.setReadable(true, false)
        scriptFile.setExecutable(true, false)

        Log.d(
            WRITE_TAG,
            "File-based path: wrote ${scriptFile.absolutePath} (${scriptContents.length} bytes), exists=${scriptFile.exists()}, canRead=${scriptFile.canRead()}, canExec=${scriptFile.canExecute()}",
        )
        val command = "sh ${scriptFile.absolutePath}"
        val result = runRootCommand(command)
        Log.d(WRITE_TAG, "File-based result = ${result?.let { "'${it.take(120)}'(${it.length})" } ?: "null"}")
        return result
    }
}
