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
        val cache = JdtJavaAnalysisCache(maxEntries = 2) { snapshot, _ ->
            analyses.incrementAndGet()
            JdtJavaSemanticAnalysisResult(emptyList(), snapshotHash = snapshot.hash)
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
    fun reusesNormalizedJdtStateAcrossUnrelatedLanguageSnapshotChanges() {
        val analyses = AtomicInteger()
        val cache = JdtJavaAnalysisCache { snapshot, _ ->
            analyses.incrementAndGet()
            JdtJavaSemanticAnalysisResult(emptyList(), snapshotHash = snapshot.hash)
        }
        val javaOnly = snapshot("class Stable {}")
        val withKotlinEdit = javaOnly.copy(files = javaOnly.files + SourceFile(
            Path.of("src/main/kotlin/Other.kt"), "class Other", "kotlin",
        ))

        val first = cache.get(javaOnly)
        val reused = cache.get(withKotlinEdit)

        assertEquals(1, analyses.get())
        assertEquals(first.analysis.symbols, reused.analysis.symbols)
        assertFailsWith<UnsupportedOperationException> {
            (first.analysis.symbols as MutableList).clear()
        }
        assertEquals(withKotlinEdit.hash, reused.snapshotHash)
        assertEquals(withKotlinEdit.hash, reused.analysis.snapshotHash)
        assertEquals(first.semanticInputHash, reused.semanticInputHash)
        assertEquals(1, cache.status().crossSnapshotHits)
    }

    @Test
    fun refusesProviderResultAttestedToAnotherSnapshot() {
        val snapshot = snapshot("class Current {}")
        val cache = JdtJavaAnalysisCache { _, _ ->
            JdtJavaSemanticAnalysisResult(emptyList(), snapshotHash = "0".repeat(64))
        }

        assertFailsWith<JdtJavaAnalysisAttestationException> { cache.get(snapshot) }
        assertEquals(0, cache.status().entries)
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
