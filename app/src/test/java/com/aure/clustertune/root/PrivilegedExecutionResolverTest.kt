package com.aure.clustertune.root

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedExecutionResolverTest {

    @Test
    fun `selects first available execution method`() {
        val unavailable = FakeExecutionMethod(
            id = "unavailable",
            probeResult = ExecutionProbeResult(false, false),
        )
        val pserver = FakeExecutionMethod(
            id = "pserver-stdout",
            probeResult = ExecutionProbeResult(true, true),
            scriptOutput = "ok",
            reads = mapOf("/sys/test" to "123"),
        )
        val fallback = FakeExecutionMethod(
            id = "pserver-file-output",
            probeResult = ExecutionProbeResult(true, false),
        )
        val resolver = PrivilegedExecutionResolver(
            methods = listOf(unavailable, pserver, fallback),
            autoDetectionOrder = listOf("unavailable", "pserver-stdout", "pserver-file-output"),
        )

        assertTrue(resolver.isAvailable)
        assertEquals("pserver-stdout", resolver.selectedMethodId)
        assertEquals("123", resolver.readText("/sys/test"))
        assertEquals("ok", resolver.executeScript("apply.sh", "echo ok").getOrThrow())
        assertEquals(1, unavailable.probeCount)
        assertEquals(1, pserver.probeCount)
        assertEquals(0, fallback.probeCount)
    }

    @Test
    fun `falls back when stdout method probe fails`() {
        val stdout = FakeExecutionMethod(
            id = "pserver-stdout",
            probeResult = ExecutionProbeResult(false, false, "no stdout"),
        )
        val fileOutput = FakeExecutionMethod(
            id = "pserver-file-output",
            probeResult = ExecutionProbeResult(true, false),
            reads = mapOf("/protected" to "value"),
        )
        val resolver = PrivilegedExecutionResolver(listOf(stdout, fileOutput))

        assertTrue(resolver.isAvailable)
        assertEquals("pserver-file-output", resolver.selectedMethodId)
        assertEquals("value", resolver.readText("/protected"))
    }

    @Test
    fun `reports unavailable when all methods fail`() {
        val resolver = PrivilegedExecutionResolver(
            listOf(
                FakeExecutionMethod("pserver-stdout", ExecutionProbeResult(false, false)),
                FakeExecutionMethod("pserver-file-output", ExecutionProbeResult(false, false)),
            ),
        )

        assertFalse(resolver.isAvailable)
        assertNull(resolver.selectedMethodId)
        assertTrue(resolver.executeScript("apply.sh", "echo ok").isFailure)
    }

    @Test
    fun `shell quotes paths with single quotes`() {
        assertEquals("'/sys/path'", shellQuote("/sys/path"))
        assertEquals("'/data/a'\\''b'", shellQuote("/data/a'b"))
    }

    @Test
    fun `configured method wins over auto detection order`() {
        val pserver = FakeExecutionMethod("pserver-stdout", ExecutionProbeResult(true, true))
        val shizuku = FakeExecutionMethod("shizuku", ExecutionProbeResult(true, true))
        val resolver = PrivilegedExecutionResolver(listOf(pserver, shizuku))

        resolver.setConfiguredMethodId("shizuku")

        assertEquals("shizuku", resolver.selectedMethodId)
        assertEquals(0, pserver.probeCount)
        assertEquals(1, shizuku.probeCount)
    }

    @Test
    fun `auto detect persists best available method`() {
        val pserver = FakeExecutionMethod("pserver-stdout", ExecutionProbeResult(false, false))
        val shizuku = FakeExecutionMethod("shizuku", ExecutionProbeResult(true, true))
        val rootShell = FakeExecutionMethod("root-shell", ExecutionProbeResult(true, true))
        val resolver = PrivilegedExecutionResolver(listOf(rootShell, shizuku, pserver))

        assertEquals("shizuku", resolver.autoDetectBestMethod())
        assertEquals("shizuku", resolver.selectedMethodId)
    }

    @Test
    fun `pserver file output read uses stdout when available`() {
        val method = PServerFileOutputExecutionMethod(
            context = null,
            rootExec = FakePServerRootExecutor(
                mapOf("cat '/sys/value' 2>/dev/null" to "42"),
            ),
            outputDirectory = temporaryDirectory(),
        )

        assertEquals("42", method.readText("/sys/value"))
    }

    @Test
    fun `pserver file output read falls back to readable file when stdout is empty`() {
        val method = PServerFileOutputExecutionMethod(
            context = null,
            rootExec = object : PServerRootExecutor {
                override val pServerAvailable: Boolean = true

                override fun executeAsRoot(cmd: String): Result<String?> {
                    if (cmd == "cat '/sys/value' 2>/dev/null") {
                        return Result.success(null)
                    }
                    val outputPath = Regex("> '([^']+)'").find(cmd)?.groupValues?.get(1)
                    if (outputPath != null) {
                        File(outputPath).writeText("24")
                    }
                    return Result.success(null)
                }
            },
            outputDirectory = temporaryDirectory(),
        )

        assertEquals("24", method.readText("/sys/value"))
    }

    private class FakeExecutionMethod(
        override val id: String,
        private val probeResult: ExecutionProbeResult,
        private val scriptOutput: String? = null,
        private val reads: Map<String, String> = emptyMap(),
    ) : PrivilegedExecutionMethod {
        var probeCount = 0
            private set

        override fun probe(): ExecutionProbeResult {
            probeCount += 1
            return probeResult
        }

        override fun executeScript(scriptName: String, scriptContents: String): Result<String?> {
            return Result.success(scriptOutput)
        }

        override fun readText(path: String): String? {
            return reads[path]
        }
    }

    private class FakePServerRootExecutor(
        private val outputs: Map<String, String?>,
    ) : PServerRootExecutor {
        override val pServerAvailable: Boolean = true

        override fun executeAsRoot(cmd: String): Result<String?> {
            return Result.success(outputs[cmd])
        }
    }

    private fun temporaryDirectory(): File {
        return File(System.getProperty("java.io.tmpdir"), "clustertune-test-${System.nanoTime()}")
            .also { it.mkdirs() }
    }
}
