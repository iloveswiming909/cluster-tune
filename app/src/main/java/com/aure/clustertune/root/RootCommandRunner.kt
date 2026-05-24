package com.aure.clustertune.root

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RootCommandRunner(
    private val context: Context,
    private val rootExec: RootExec = RootExec(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    val isAvailable: Boolean
        get() = rootExec.pServerAvailable

    suspend fun executeScript(script: String): Result<String?> = withContext(dispatcher) {
        Log.d("ClusterTuneWrite", "RootCommandRunner.executeScript ENTER, script (${script.length} bytes):\n$script")
        val result = runCatching {
            RootSupport.runGeneratedScript(
                context = context,
                scriptName = "apply-frequencies.sh",
                scriptContents = script,
            )
        }
        Log.d(
            "ClusterTuneWrite",
            "RootCommandRunner.executeScript EXIT: success=${result.isSuccess}, value=${result.getOrNull()?.let { "'${it.take(120)}'(${it.length})" } ?: "null"}, exception=${result.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "none"}",
        )
        result
    }
}
