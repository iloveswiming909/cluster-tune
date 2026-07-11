package com.aure.clustertune.root

import android.content.Context
import android.util.Log
import java.io.File
import java.lang.reflect.Method

/**
 * Diagnostic probe for Qualcomm's BoostFramework / vendor.perfservice
 * (com.qualcomm.qti.IPerfManager) as an alternative privileged write
 * path on devices where PServer is SELinux-blocked for untrusted_app
 * (e.g. Odin 2 Mini).
 *
 * perfservice runs as the `system` user and can write the cpufreq
 * max-frequency sysnodes. The documented MPCTLV3 opcodes set per-cluster
 * MAX frequency, which is exactly a downward cap:
 *   little (policy0): 0x40804100
 *   big    (policy3): 0x40804000
 *   prime  (policy7): 0x40804200
 *
 * This probe:
 *  1. Reflectively constructs android.util.BoostFramework.
 *  2. Reads current scaling_max_freq for policy7 via File API.
 *  3. Calls perfLockAcquire with the prime-cluster MAX-freq opcode and a
 *     low target value, trying several value encodings (raw kHz, MHz,
 *     and "level" forms) since the unit varies by release.
 *  4. Re-reads scaling_max_freq and logs whether it moved.
 *
 * All output under logcat tag "ClusterTuneBoost". If a value encoding
 * moves the frequency, we've found a no-root, no-PServer write path.
 */
object BoostFrameworkProbe {

    private const val TAG = "ClusterTuneBoost"

    // Documented MPCTLV3 per-cluster MAX-frequency opcodes.
    private const val OPCODE_MAX_FREQ_LITTLE = 0x40804100 // policy0, CPUs 0-2
    private const val OPCODE_MAX_FREQ_BIG = 0x40804000    // policy3, CPUs 3-6
    private const val OPCODE_MAX_FREQ_PRIME = 0x40804200  // policy7, CPU 7

    private const val PRIME_PATH = "/sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq"

    fun run(context: Context) {
        Log.d(TAG, "===== BoostFramework probe begin =====")

        val framework = tryConstructBoostFramework(context)
        if (framework == null) {
            Log.d(TAG, "Could not construct BoostFramework - class missing or blocked. STOP.")
            Log.d(TAG, "===== BoostFramework probe end =====")
            return
        }
        Log.d(TAG, "BoostFramework constructed OK: ${framework.javaClass.name}")

        val acquire = findPerfLockAcquire(framework)
        val release = findPerfLockRelease(framework)
        if (acquire == null) {
            Log.d(TAG, "perfLockAcquire method not found. STOP.")
            Log.d(TAG, "===== BoostFramework probe end =====")
            return
        }
        Log.d(TAG, "perfLockAcquire found: $acquire")

        val before = readFreq()
        Log.d(TAG, "policy7 scaling_max_freq BEFORE = $before")

        // The target value unit is unknown; try several encodings for a
        // ~2.0 GHz cap on the prime core. After each, read back and log.
        val candidates = listOf(
            "raw-kHz-2016000" to 2016000,
            "MHz-2016" to 2016,
            "raw-kHz-1958400" to 1958400,
            "level-0xFFF-min? " to 0x0,      // 0 sometimes = lowest level
            "value-20 (level)" to 20,
        )

        for ((label, value) in candidates) {
            try {
                // perfLockAcquire(duration_ms, int... args)
                // args = pairs of (opcode, value). Hold for 8s so we can
                // observe the effect before it releases.
                val args = intArrayOf(OPCODE_MAX_FREQ_PRIME, value)
                val handle = invokeAcquire(acquire, framework, 8000, args)
                Thread.sleep(300)
                val after = readFreq()
                Log.d(TAG, "TRY $label: opcode=0x${OPCODE_MAX_FREQ_PRIME.toString(16)} value=$value -> handle=$handle, scaling_max_freq AFTER = $after ${if (after != before) "*** CHANGED ***" else "(no change)"}")
                // Release before next attempt if we got a handle
                if (release != null && handle is Int && handle > 0) {
                    runCatching { release.invoke(framework, handle) }
                }
                Thread.sleep(200)
            } catch (t: Throwable) {
                Log.d(TAG, "TRY $label threw: ${t.javaClass.simpleName}: ${t.message}")
            }
        }

        val finalVal = readFreq()
        Log.d(TAG, "policy7 scaling_max_freq FINAL = $finalVal (started at $before)")
        Log.d(TAG, "===== BoostFramework probe end =====")
    }

    private fun tryConstructBoostFramework(context: Context): Any? {
        // Try android.util.BoostFramework(Context) then no-arg, then
        // com.qualcomm.qti.performance variants.
        val classNames = listOf(
            "android.util.BoostFramework",
            "com.qualcomm.qti.Performance",
        )
        for (name in classNames) {
            val clazz = runCatching { Class.forName(name) }.getOrNull() ?: continue
            // Try (Context)
            runCatching {
                val ctor = clazz.getConstructor(Context::class.java)
                return ctor.newInstance(context)
            }
            // Try no-arg
            runCatching {
                val ctor = clazz.getConstructor()
                return ctor.newInstance()
            }
            Log.d(TAG, "Found class $name but no usable constructor")
        }
        return null
    }

    private fun findPerfLockAcquire(framework: Any): Method? {
        return framework.javaClass.methods.firstOrNull {
            it.name == "perfLockAcquire" &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1] == IntArray::class.java
        } ?: framework.javaClass.methods.firstOrNull { it.name == "perfLockAcquire" }
    }

    private fun findPerfLockRelease(framework: Any): Method? {
        return framework.javaClass.methods.firstOrNull { it.name == "perfLockRelease" }
    }

    private fun invokeAcquire(acquire: Method, framework: Any, duration: Int, args: IntArray): Any? {
        // Handle both (int, int[]) and (int, int...) which reflects as (int, int[])
        return if (acquire.parameterTypes.size == 2 && acquire.parameterTypes[1] == IntArray::class.java) {
            acquire.invoke(framework, duration, args)
        } else {
            // varargs form: (int, int...) -> pass boxed array
            acquire.invoke(framework, duration, args)
        }
    }

    private fun readFreq(): String {
        return runCatching {
            File(PRIME_PATH).readText().trim()
        }.getOrDefault("<read-failed>")
    }
}
