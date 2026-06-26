package com.aure.clustertune.data

fun interface PrivilegedSysfsReader {
    fun readText(path: String): String?

    fun makeReadable(path: String): Boolean = false
}
