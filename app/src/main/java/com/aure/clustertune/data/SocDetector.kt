package com.aure.clustertune.data

import android.os.Build
import java.io.BufferedReader
import java.io.InputStreamReader

open class SocDetector {

    companion object {
        @Volatile
        private var processCachedSocModel: String? = null
    }

    open fun detectSocModel(): String? {
        processCachedSocModel?.let { return it }
        val candidates = listOf(
            readProperty("ro.soc.model"),
            readProperty("ro.vendor.qti.soc_model"),
            readProperty("ro.fota.platform"),
            Build.SOC_MODEL,
            Build.HARDWARE,
            Build.BOARD,
        )
        return candidates.firstOrNull { !it.isNullOrBlank() }
            ?.trim()
            ?.also { processCachedSocModel = it }
    }

    private fun readProperty(name: String): String? {
        return runCatching {
            val process = ProcessBuilder("getprop", name)
                .redirectErrorStream(true)
                .start()
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            process.waitFor()
            output.trim().takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
