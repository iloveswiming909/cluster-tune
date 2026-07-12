package com.aure.clustertune.root

import android.content.Context
import android.util.Log
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v5: focused hold-and-measure test for the Qualcomm perflock CPU
 * frequency cap. Earlier probes showed perfLockAcquire returns valid
 * handles with no SELinux denial, and hinted (faintly) that the
 * big-cluster opcode 0x40804000 held policy3's cur_freq down. This
 * version removes the confounders:
 *
 *   Phase A (baseline): drive load on ALL cores, no lock, sample
 *     scaling_cur_freq many times, record the PEAK per cluster.
 *   Phase B (capped): acquire ONE perflock for the big cluster with a
 *     low target and HOLD it, keep driving load, sample peaks again.
 *   Phase C (released): release the lock, keep load, sample peaks.
 *
 * If the cap works: B peaks << A peaks for the big cluster, and C
 * recovers toward A. If it's DVFS noise, peaks look similar across
 * phases.
 *
 * We test the big cluster (policy3) primarily but measure all clusters.
 * Tag: "ClusterTuneBoost".
 */
object BoostFrameworkProbe {

    private const val TAG = "ClusterTuneBoost"
    private val POLICIES = listOf(0, 3, 7)
    private fun curPath(p: Int) = "/sys/devices/system/cpu/cpufreq/policy$p/scaling_cur_freq"
    private fun maxPath(p: Int) = "/sys/devices/system/cpu/cpufreq/policy$p/scaling_max_freq"

    // Big-cluster MAX-freq opcode (best candidate from v4). We also try a
    // couple of alternates in case this one is wrong.
    private val BIG_OPCODES = listOf(0x40804000, 0x40804200, 0x40804100)
    // Try raw-kHz targets AND small index-style values, since v4 hinted the
    // opcode may take a frequency-table index rather than kHz.
    private val BIG_TARGETS = listOf(1113600, 1, 2, 4)

    fun run(context: Context) {
        Log.d(TAG, "===== BoostFramework probe v5 (hold & measure) begin =====")

        runCatching { org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L") }

        val framework = tryConstructBoostFramework(context)
        if (framework == null) {
            Log.d(TAG, "Could not construct BoostFramework. STOP.")
            return
        }
        val acquire = findPerfLockAcquire(framework)
        val release = findMethod(framework, "perfLockRelease")
        if (acquire == null) {
            Log.d(TAG, "perfLockAcquire not found. STOP.")
            return
        }
        Log.d(TAG, "max ceilings: ${POLICIES.joinToString(" ") { "p$it=${read(maxPath(it))}" }}")

        // Start all-core load for the duration of the whole test.
        val stop = AtomicBoolean(false)
        val threads = startAllCoreLoad(stop)
        try {
            // Phase A: baseline peaks, no lock.
            val a = samplePeaks(1500)
            Log.d(TAG, "PHASE A (no lock)    peaks: ${fmt(a)}")

            var solved = false
            for (opcode in BIG_OPCODES) {
                for (target in BIG_TARGETS) {
                    // Phase B: hold a big-cluster cap, measure.
                    val handle = runCatching {
                        acquire.invoke(framework, 20000, intArrayOf(opcode, target))
                    }.getOrNull()
                    val b = samplePeaks(1600)
                    Log.d(TAG, "PHASE B op=0x${opcode.toString(16)} target=$target handle=$handle peaks: ${fmt(b)}")

                    // Phase C: release, measure recovery.
                    if (release != null && handle is Int && handle > 0) {
                        runCatching { release.invoke(framework, handle) }
                    }
                    val c = samplePeaks(1200)

                    val aP3 = a[3] ?: 0; val bP3 = b[3] ?: 0; val cP3 = c[3] ?: 0
                    val capped = bP3 in 1..(aP3 - 100_000)
                    val recovered = cP3 >= bP3 + 100_000
                    Log.d(TAG, "VERDICT op=0x${opcode.toString(16)} target=$target: p3 A=$aP3 B=$bP3 C=$cP3 -> ${if (capped) "*** CAP WORKS ***" else "no cap"}${if (capped && recovered) " (+recovered)" else ""}")
                    if (capped) { solved = true; break }
                }
                if (solved) break
            }
        } finally {
            stop.set(true)
            threads.forEach { runCatching { it.join(500) } }
        }
        Log.d(TAG, "===== probe v5 end =====")
    }

    /** Peak scaling_cur_freq per policy over [durationMs], sampled ~every 25ms. */
    private fun samplePeaks(durationMs: Long): Map<Int, Int> {
        val peaks = HashMap<Int, Int>()
        val end = System.currentTimeMillis() + durationMs
        while (System.currentTimeMillis() < end) {
            for (p in POLICIES) {
                val v = read(curPath(p)).toIntOrNull() ?: continue
                val cur = peaks[p]
                if (cur == null || v > cur) peaks[p] = v
            }
            Thread.sleep(25)
        }
        return peaks
    }

    private fun startAllCoreLoad(stop: AtomicBoolean): List<Thread> {
        val n = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return (0 until n).map {
            Thread {
                var x = 1.0
                while (!stop.get()) {
                    // Non-trivial FP work to keep the core busy.
                    x += Math.sqrt(x * 1.0000001) + Math.cbrt(x + 1.0)
                    if (x > 1e12) x = 1.0
                }
                if (x < 0) Log.d(TAG, "unreachable $x")
            }.apply { isDaemon = true; start() }
        }
    }

    private fun fmt(m: Map<Int, Int>) = POLICIES.joinToString(" ") { "p$it=${m[it] ?: "?"}" }

    private fun tryConstructBoostFramework(context: Context): Any? {
        for (name in listOf("android.util.BoostFramework", "com.qualcomm.qti.Performance")) {
            val clazz = runCatching { Class.forName(name) }.getOrNull() ?: continue
            for (ctor in clazz.declaredConstructors) {
                val args = buildConstructorArgs(ctor.parameterTypes, context) ?: continue
                val inst = runCatching { ctor.isAccessible = true; ctor.newInstance(*args) }.getOrNull()
                if (inst != null) return inst
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
