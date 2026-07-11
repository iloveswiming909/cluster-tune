package com.aure.clustertune.root

import android.content.Context
import android.util.Log
import java.io.File
import java.lang.reflect.Method

/**
 * Diagnostic probe for Qualcomm BoostFramework / vendor.perfservice as an
 * alternative privileged CPU-frequency-cap path on the Odin 2 Mini
 * (PServer is SELinux-blocked; perfservice is not).
 *
 * v4: we can already construct BoostFramework (via hidden-API bypass) and
 * perfLockAcquire returns valid handles, but scaling_max_freq didn't move
 * with opcode 0x40804200. This version:
 *   - Dumps ALL BoostFramework fields whose names look like freq opcodes,
 *     so we use the device's OWN constants instead of guessing.
 *   - Tries a range of candidate MAX-freq opcodes.
 *   - After each acquire, reads a BROAD set of nodes (all policies'
 *     scaling_max_freq AND scaling_cur_freq, plus cpu_max_freq), because
 *     perflock caps often apply in the HAL/governor layer and show up in
 *     cur_freq under load rather than in scaling_max_freq.
 *   - Spins CPU load during the measurement so cur_freq is meaningful.
 *
 * Tag: "ClusterTuneBoost".
 */
object BoostFrameworkProbe {

    private const val TAG = "ClusterTuneBoost"

    private val POLICIES = listOf(0, 3, 7)
    private fun maxPath(p: Int) = "/sys/devices/system/cpu/cpufreq/policy$p/scaling_max_freq"
    private fun curPath(p: Int) = "/sys/devices/system/cpu/cpufreq/policy$p/scaling_cur_freq"
    private const val CPU_MAX_FREQ = "/sys/kernel/msm_performance/parameters/cpu_max_freq"

    fun run(context: Context) {
        Log.d(TAG, "===== BoostFramework probe v4 begin =====")

        val exempted = runCatching {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L")
        }
        Log.d(TAG, "hidden-API exemption: ${exempted.getOrNull() ?: exempted.exceptionOrNull()?.message}")

        val framework = tryConstructBoostFramework(context)
        if (framework == null) {
            Log.d(TAG, "Could not construct BoostFramework. STOP.")
            Log.d(TAG, "===== probe end =====")
            return
        }
        Log.d(TAG, "BoostFramework OK: ${framework.javaClass.name}")

        // Dump fields that look like frequency/cluster opcodes so we can
        // use the device's real constants.
        dumpFreqOpcodeFields(framework.javaClass)

        val acquire = findPerfLockAcquire(framework) ?: run {
            Log.d(TAG, "perfLockAcquire not found. STOP.")
            Log.d(TAG, "===== probe end =====")
            return
        }
        val release = findMethod(framework, "perfLockRelease")

        // Candidate opcodes for per-cluster MAX frequency. First the ones
        // discovered from fields (if any), then documented guesses.
        val discovered = discoverMaxFreqOpcodes(framework.javaClass)
        val guesses = listOf(
            0x40804200, // plus/prime (our earlier guess)
            0x40804000, // big
            0x40800000, // base max-freq family
            0x41000000,
            0x40C00000,
            0x42C20000,
        )
        val opcodes = (discovered + guesses).distinct()
        Log.d(TAG, "Testing ${opcodes.size} candidate opcode(s): ${opcodes.joinToString { "0x" + it.toString(16) }}")

        logNodes("BASELINE")

        // Target ~1.5 GHz on prime; try kHz and a few index-like values.
        val values = listOf(1497600, 1500000, 1478400, 10, 5)

        for (opcode in opcodes) {
            for (value in values) {
                testOne(acquire, release, framework, opcode, value)
            }
        }

        logNodes("FINAL")
        Log.d(TAG, "===== probe end =====")
    }

    private fun testOne(acquire: Method, release: Method?, framework: Any, opcode: Int, value: Int) {
        try {
            val args = intArrayOf(opcode, value)
            val handle = acquire.invoke(framework, 12000, args)
            // Spin some load so scaling_cur_freq rises toward the cap if
            // uncapped, or is held down if the cap works.
            val loadFreqs = spinAndSampleCur()
            val maxNow = POLICIES.joinToString(" ") { "p$it=${read(maxPath(it))}" }
            val cpuMax = read(CPU_MAX_FREQ)
            Log.d(
                TAG,
                "OP 0x${opcode.toString(16)} v=$value handle=$handle | max[$maxNow] cpu_max=$cpuMax | curUnderLoad=${loadFreqs.joinToString(",") { "p${it.policy}:${it.value}" }}",
            )
            if (release != null && handle is Int && handle > 0) {
                runCatching { release.invoke(framework, handle) }
            }
            Thread.sleep(120)
        } catch (t: Throwable) {
            Log.d(TAG, "OP 0x${opcode.toString(16)} v=$value threw ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private data class CurSample(val policy: Int, val value: String, val baseline: String)

    private fun spinAndSampleCur(): List<CurSample> {
        // Record baseline cur, then spin ~200ms of load, then sample cur.
        val baseline = POLICIES.associateWith { read(curPath(it)) }
        val end = System.currentTimeMillis() + 200
        var x = 0.0
        while (System.currentTimeMillis() < end) {
            x += Math.sqrt((x + 1.0)) * 1.0000001
        }
        // touch x so JIT doesn't elide the loop
        if (x < 0) Log.d(TAG, "unreachable $x")
        return POLICIES.map { CurSample(it, read(curPath(it)), baseline[it] ?: "?") }
    }

    private fun logNodes(label: String) {
        val maxs = POLICIES.joinToString(" ") { "p$it=${read(maxPath(it))}" }
        val curs = POLICIES.joinToString(" ") { "p$it=${read(curPath(it))}" }
        Log.d(TAG, "$label max[$maxs] cur[$curs] cpu_max=${read(CPU_MAX_FREQ)}")
    }

    private fun dumpFreqOpcodeFields(clazz: Class<*>) {
        val fields = runCatching { clazz.declaredFields }.getOrNull() ?: return
        val interesting = fields.filter { f ->
            val n = f.name.uppercase()
            (n.contains("FREQ") || n.contains("CLUSTER") || n.contains("MPCTL") || n.contains("CPUFREQ")) &&
                (f.type == Int::class.javaPrimitiveType || f.type == Integer::class.java)
        }
        if (interesting.isEmpty()) {
            Log.d(TAG, "No freq/cluster opcode fields found on ${clazz.name}")
            return
        }
        interesting.forEach { f ->
            val v = runCatching {
                f.isAccessible = true
                f.getInt(null)
            }.getOrNull()
            Log.d(TAG, "FIELD ${f.name} = ${v?.let { "0x" + it.toString(16) } ?: "?"}")
        }
    }

    private fun discoverMaxFreqOpcodes(clazz: Class<*>): List<Int> {
        val fields = runCatching { clazz.declaredFields }.getOrNull() ?: return emptyList()
        return fields.mapNotNull { f ->
            val n = f.name.uppercase()
            if (n.contains("MAX") && n.contains("FREQ") && n.contains("CLUSTER")) {
                runCatching { f.isAccessible = true; f.getInt(null) }.getOrNull()
            } else null
        }
    }

    private fun tryConstructBoostFramework(context: Context): Any? {
        for (name in listOf("android.util.BoostFramework", "com.qualcomm.qti.Performance")) {
            val clazz = runCatching { Class.forName(name) }.getOrNull() ?: continue
            val ctors = clazz.declaredConstructors
            Log.d(TAG, "Class $name has ${ctors.size} constructor(s)")
            for (ctor in ctors) {
                val args = buildConstructorArgs(ctor.parameterTypes, context) ?: continue
                val instance = runCatching {
                    ctor.isAccessible = true
                    ctor.newInstance(*args)
                }.getOrNull()
                if (instance != null) return instance
            }
        }
        return null
    }

    private fun buildConstructorArgs(paramTypes: Array<Class<*>>, context: Context): Array<Any?>? {
        val args = arrayOfNulls<Any?>(paramTypes.size)
        for (i in paramTypes.indices) {
            val t = paramTypes[i]
            args[i] = when {
                Context::class.java.isAssignableFrom(t) -> context
                t == Boolean::class.javaPrimitiveType -> false
                t == Int::class.javaPrimitiveType -> 0
                t == Long::class.javaPrimitiveType -> 0L
                t.isPrimitive -> return null
                else -> null
            }
        }
        return args
    }

    private fun findPerfLockAcquire(framework: Any): Method? =
        framework.javaClass.methods.firstOrNull {
            it.name == "perfLockAcquire" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1] == IntArray::class.java
        } ?: framework.javaClass.methods.firstOrNull { it.name == "perfLockAcquire" }

    private fun findMethod(framework: Any, name: String): Method? =
        framework.javaClass.methods.firstOrNull { it.name == name }

    private fun read(path: String): String =
        runCatching { File(path).readText().trim() }.getOrDefault("<na>")
}
