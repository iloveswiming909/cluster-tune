package com.aure.clustertune.root

import android.content.Context
import java.io.File

object RootSupport {

    fun runRootCommand(command: String): String? {
        return RootExec().executeAsRoot(command).getOrNull()
    }

    fun runGeneratedScript(
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

        val command = "sh ${scriptFile.absolutePath}"
        return runRootCommand(command)
    }
}
