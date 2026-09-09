package org.refactorkit.typescript.relocation

import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.ManagedSemanticProcess
import org.refactorkit.core.SemanticProcessSpec
import org.refactorkit.typescript.ManagedNodeVersionProbe
import java.util.concurrent.ConcurrentHashMap
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Operational probe/cleanup checks use explicit executables and never run project code. */
internal class TypeScriptNodeProbeFixture {
    private var result: Result<String>? = null
    private var observations: List<Result<String>> = emptyList()
    private var closure: Result<Unit>? = null
    private var completionObserved = false
    private var completedPid: Long? = null

    private fun nodeExecutable(): Path {
        val name = if (System.getProperty("os.name").startsWith("Windows")) "node.exe" else "node"
        return System.getenv("PATH").split(System.getProperty("path.separator"))
            .map { Path.of(it).resolve(name) }.first { Files.isRegularFile(it) && Files.isExecutable(it) }
    }

    fun runShortLivedProbes() {
        val node = nodeExecutable()
        observations = List(64) { ManagedNodeVersionProbe().probe(node) }
    }

    fun closeDuringCompletion() {
        val root = Files.createTempDirectory("rk-node-close-race-")
        val manager = ExternalSemanticProcessManager(1)
        try {
            val process = manager.launch(SemanticProcessSpec("node-race", nodeExecutable(), listOf("--version"), root))
            try {
                assertTrue(process.awaitExit(2_000))
                assertEquals(0, process.exitCode)
                completedPid = process.provenance.pid
                val deadline = System.nanoTime() + 2_000_000_000L
                while (manager.activeProvenance().isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(1)
                assertTrue(manager.activeProvenance().isEmpty())
                // Schedule the weakly consistent view deterministically; do not add a production test seam.
                val registry = CompletionRegistry { completionObserved = true }
                registry["node-race"] = process
                ExternalSemanticProcessManager::class.java.getDeclaredField("processes").apply {
                    isAccessible = true
                    set(manager, registry)
                }
                closure = runCatching { manager.close() }
            } finally { process.close() }
        } finally {
            manager.close()
            Files.deleteIfExists(root)
        }
    }

    fun assertSafeClose() {
        assertTrue(completionObserved, "The completed child must leave before iteration")
        val result = requireNotNull(closure)
        assertTrue(result.isSuccess, result.exceptionOrNull()?.stackTraceToString())
        assertTrue(ProcessHandle.of(requireNotNull(completedPid)).map { !it.isAlive }.orElse(true))
    }

    private class CompletionRegistry(private val observe: () -> Unit) : ConcurrentHashMap<String, ManagedSemanticProcess>() {
        override val values: MutableCollection<ManagedSemanticProcess>
            get() {
                val backing = super.values
                return object : AbstractMutableCollection<ManagedSemanticProcess>() {
                    override val size: Int get() = backing.size
                    override fun iterator(): MutableIterator<ManagedSemanticProcess> {
                        clearRegistry()
                        observe()
                        return backing.iterator()
                    }
                    override fun add(element: ManagedSemanticProcess): Boolean = error("Read-only view")
                }
            }
        private fun clearRegistry() = clear()
    }

    fun assertSuccessfulSequence() {
        assertEquals(64, observations.size)
        assertTrue(observations.all { it.isSuccess }, observations.mapIndexedNotNull { index, result ->
            result.exceptionOrNull()?.let { "Probe $index: ${it.stackTraceToString()}" }
        }.joinToString("\n"))
        assertTrue(observations.all { it.getOrThrow().matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+")) })
        assertEquals(2, ManagedNodeVersionProbe.MAX_NODE_PROBE_ATTEMPTS)
        assertEquals(2_000L, ManagedNodeVersionProbe.NODE_PROBE_TIMEOUT_MILLIS)
    }

    fun run() {
        val name = if (System.getProperty("os.name").startsWith("Windows")) "serialver.exe" else "serialver"
        result = ManagedNodeVersionProbe().probe(Path.of(System.getProperty("java.home"), "bin", name))
    }

    fun assertExitEvidence() {
        val failure = assertNotNull(requireNotNull(result).exceptionOrNull())
        assertTrue(failure.message.orEmpty().contains("exit=1"), failure.toString())
        assertEquals(2, ManagedNodeVersionProbe.MAX_NODE_PROBE_ATTEMPTS)
        assertEquals(2_000L, ManagedNodeVersionProbe.NODE_PROBE_TIMEOUT_MILLIS)
    }
}
