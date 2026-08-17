package com.aure.clustertune.data

interface PrivilegedSysfsReader {
    fun readText(path: String): String?

    /**
     * Reads several paths, ideally in ONE privileged round trip.
     *
     * Why this exists: on the file-based system-daemon transport every call
     * costs a full request/response cycle through /sdcard. Reading three
     * scaling_min_freq nodes one at a time made a single state refresh cost
     * three round trips, and an apply's read-back verification the same again —
     * which is what made profile switching feel slow.
     *
     * The default implementation just loops, so transports with cheap reads
     * (root, PServer) need no change and every existing implementation keeps
     * compiling untouched.
     */
    fun readTexts(paths: List<String>): Map<String, String> {
        return paths.mapNotNull { path ->
            readText(path)?.let { value -> path to value }
        }.toMap()
    }
}
