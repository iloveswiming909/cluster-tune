package com.aure.clustertune.data

fun interface PrivilegedSysfsLister {
    fun listChildrenWithPrefix(directoryPath: String, prefix: String): List<String>?
}
