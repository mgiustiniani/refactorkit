package org.refactorkit.core

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ExternalSemanticProcessManagerTest {
    @Test
    fun launchesWithClearedExplicitEnvironmentAndRecordsProvenance() {
        val manager = ExternalSemanticProcessManager()
        val process = manager.launch(spec("env", "env", "RK_SAFE", environment = mapOf("RK_SAFE" to "visible")))

        assertEquals(listOf("env"), manager.activeProvenance().map(SemanticProcessProvenance::id))
        val output = process.output.bufferedReader().readText().trim()
        assertTrue(process.awaitExit(10_000))

        assertEquals("visible|<missing>", output)
        assertTrue(process.provenance.executable.isAbsolute)
        assertEquals(64, process.provenance.executableSha256.length)
        assertEquals(64, process.provenance.argumentsSha256.length)
        assertTrue(process.provenance.pid > 0)
        eventually { manager.activeProvenance().isEmpty() }
        process.close()
        assertTrue(manager.activeProvenance().isEmpty())
    }

    @Test
    fun rejectsSecretShapedEnvironmentAndDuplicateProcessIds() {
        assertFailsWith<IllegalArgumentException> {
            spec("bad", "sleep", environment = mapOf("API_TOKEN" to "secret"))
        }
        val manager = ExternalSemanticProcessManager(maxProcesses = 1)
        val process = manager.launch(spec("one", "sleep"))
        assertFailsWith<IllegalArgumentException> { manager.launch(spec("two", "sleep")) }
        assertFailsWith<IllegalArgumentException> { manager.launch(spec("one", "sleep")) }
        process.close()
    }

    @Test
    fun stdoutOverflowTerminatesProcessAndRaisesTypedFailure() {
        val manager = ExternalSemanticProcessManager()
        val process = manager.launch(spec(
            "stdout-limit", "stdout", "4096",
            limits = SemanticProcessLimits(maxStdoutBytes = 128, maxStderrBytes = 1_024, gracefulShutdownMillis = 100),
        ))

        assertFailsWith<SemanticProcessLimitException> { process.output.readBytes() }
        eventually { !process.isAlive }
        process.close()
    }

    @Test
    fun stderrOverflowIsTruncatedAndTerminatesProcess() {
        val manager = ExternalSemanticProcessManager()
        val process = manager.launch(spec(
            "stderr-limit", "stderr", "4096",
            limits = SemanticProcessLimits(maxStdoutBytes = 1_024, maxStderrBytes = 128, gracefulShutdownMillis = 100),
        ))

        eventually { process.stderrTruncated() && !process.isAlive }
        assertTrue(process.stderrText().toByteArray().size <= 128)
        process.close()
    }

    @Test
    fun cancellationTerminatesDescendantProcessTree() {
        val manager = ExternalSemanticProcessManager()
        val process = manager.launch(spec("tree", "spawn"))
        val childPid = BufferedReader(InputStreamReader(process.output)).readLine().trim().toLong()
        val child = ProcessHandle.of(childPid).orElseThrow()
        assertTrue(child.isAlive)

        assertTrue(manager.cancel("tree"))

        eventually { !child.isAlive }
        assertFalse(process.isAlive)
        assertFalse(manager.cancel("tree"))
    }

    @Test
    fun cancellationIsIdempotentAndArgumentsAffectProvenance() {
        val manager = ExternalSemanticProcessManager()
        val first = manager.launch(spec("first", "sleep"))
        val firstHash = first.provenance.argumentsSha256
        first.cancel()
        first.cancel()
        val second = manager.launch(spec("second", "sleep", "different"))
        assertNotEquals(firstHash, second.provenance.argumentsSha256)
        second.close()
        manager.close()
    }

    private fun spec(
        id: String,
        vararg fixtureArguments: String,
        environment: Map<String, String> = emptyMap(),
        limits: SemanticProcessLimits = SemanticProcessLimits(gracefulShutdownMillis = 200),
    ): SemanticProcessSpec = SemanticProcessSpec(
        id = id,
        executable = javaExecutable(),
        arguments = listOf("-cp", System.getProperty("java.class.path"), SemanticProcessFixture::class.java.name) + fixtureArguments,
        workingDirectory = Files.createTempDirectory("refactorkit-semantic-process"),
        environment = environment,
        limits = limits,
    )

    private fun javaExecutable(): Path {
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        return Path.of(System.getProperty("java.home"), "bin", executable).toAbsolutePath().normalize()
    }

    private fun eventually(condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            Thread.sleep(25)
        }
        assertTrue(condition(), "condition did not become true")
    }
}

object SemanticProcessFixture {
    @JvmStatic
    fun main(arguments: Array<String>) {
        when (arguments.firstOrNull()) {
            "env" -> {
                val key = arguments[1]
                print("${System.getenv(key) ?: "<missing>"}|${System.getenv("JAVA_HOME") ?: "<missing>"}")
            }
            "stdout" -> {
                val size = arguments[1].toInt()
                System.out.write(ByteArray(size) { 'x'.code.toByte() })
                System.out.flush()
                Thread.sleep(30_000)
            }
            "stderr" -> {
                val size = arguments[1].toInt()
                System.err.write(ByteArray(size) { 'e'.code.toByte() })
                System.err.flush()
                Thread.sleep(30_000)
            }
            "spawn" -> {
                val executable = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
                val child = ProcessBuilder(
                    executable.toString(), "-cp", System.getProperty("java.class.path"),
                    SemanticProcessFixture::class.java.name, "sleep",
                ).start()
                println(child.pid())
                System.out.flush()
                Thread.sleep(30_000)
            }
            "sleep" -> Thread.sleep(30_000)
            else -> error("unknown fixture mode")
        }
    }
}
