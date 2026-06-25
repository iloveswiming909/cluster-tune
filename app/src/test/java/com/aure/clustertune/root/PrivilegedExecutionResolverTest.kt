package com.aure.clustertune.root

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
        val resolver = PrivilegedExecutionResolver(listOf(unavailable, pserver, fallback))

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
}
