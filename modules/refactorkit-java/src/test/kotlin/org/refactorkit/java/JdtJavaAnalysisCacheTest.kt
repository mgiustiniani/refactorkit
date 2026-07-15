package org.refactorkit.java

import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SemanticCancellationToken
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JdtJavaAnalysisCacheTest {
    @Test
    fun cachesExactSnapshotsAndEvictsLeastRecentlyUsedEntry() {
        val analyses = AtomicInteger()
        val cache = JdtJavaAnalysisCache(maxEntries = 2) { _, _ ->
            analyses.incrementAndGet()
            JdtJavaSemanticAnalysisResult(emptyList())
        }
        val first = snapshot("class First {}")
        val second = snapshot("class Second {}")
        val third = snapshot("class Third {}")

        cache.get(first)
        cache.get(first)
        cache.get(second)
        cache.get(third)
        cache.get(first)

        assertEquals(4, analyses.get())
        assertEquals(1, cache.status().hits)
        assertEquals(4, cache.status().misses)
        assertEquals(2, cache.status().entries)
    }

    @Test
    fun refusesSourceOverflowBeforeStartingAnalysis() {
        val cache = JdtJavaAnalysisCache(analyze = { _, _ -> error("must not run") })
        val snapshot = ProjectSnapshot(
            workspace = Workspace(Path.of("/workspace")), modules = emptyList(),
            files = (0..JdtJavaAnalysisCache.MAX_JAVA_SOURCE_FILES).map { index ->
                SourceFile(Path.of("src/main/java/Type$index.java"), "class Type$index {}", "java")
            },
        )
        assertFailsWith<JdtJavaAnalysisLimitException> { cache.get(snapshot) }
        assertEquals(0, cache.status().misses)
    }

    @Test
    fun refusesCancellationBeforeStartingAnalysis() {
        val cache = JdtJavaAnalysisCache(analyze = { _, _ -> error("must not run") })
        assertFailsWith<JdtJavaAnalysisCancelledException> {
            cache.get(snapshot("class Cancelled {}"), SemanticCancellationToken { true })
        }
        assertEquals(0, cache.status().misses)
    }

    private fun snapshot(content: String) = ProjectSnapshot(
        workspace = Workspace(Path.of("/workspace")), modules = emptyList(),
        files = listOf(SourceFile(Path.of("src/main/java/Sample.java"), content, "java")),
    )
}
