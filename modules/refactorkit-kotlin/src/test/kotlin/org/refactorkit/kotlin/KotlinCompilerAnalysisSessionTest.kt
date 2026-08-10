package org.refactorkit.kotlin

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SymbolIndex
import org.refactorkit.core.Workspace
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class KotlinCompilerAnalysisSessionTest {
    @Test
    fun diagnosticsAndSymbolsReuseOneExactSnapshotAnalysis() {
        val calls = AtomicInteger()
        val snapshot = snapshot("class Value")
        val providerDiagnostics = mutableListOf(
            Diagnostic("first", Diagnostic.Severity.WARNING),
            Diagnostic("second", Diagnostic.Severity.INFO),
        )
        val result = available(snapshot, providerDiagnostics)
        val session = KotlinCompilerAnalysisSession { observed ->
            assertSame(snapshot, observed)
            calls.incrementAndGet()
            result
        }

        val diagnostics = session.analyze(snapshot)
        val symbols = session.symbols(snapshot)

        val availableDiagnostics = assertIs<KotlinCompilerDiagnosticsResult.Available>(diagnostics)
        assertIs<KotlinCompilerSymbolsResult.Available>(symbols)
        (availableDiagnostics.diagnostics as MutableList).clear()
        providerDiagnostics.clear()
        val repeated = assertIs<KotlinCompilerDiagnosticsResult.Available>(session.analyze(snapshot))
        assertEquals(listOf("first", "second"), repeated.diagnostics.map { it.message })
        assertEquals(1, calls.get())
        assertEquals(KotlinCompilerAnalysisSessionStatus(entries = 1, hits = 2, misses = 1), session.status())
    }

    @Test
    fun snapshotChangesMissAndLeastRecentlyUsedStateIsBounded() {
        val calls = AtomicInteger()
        val session = KotlinCompilerAnalysisSession(maxEntries = 2) { snapshot ->
            calls.incrementAndGet()
            available(snapshot)
        }
        val first = snapshot("class First")
        val second = snapshot("class Second")
        val third = snapshot("class Third")

        session.analyze(first)
        session.analyze(second)
        session.analyze(first)
        session.analyze(third)
        session.analyze(second)

        assertEquals(4, calls.get())
        assertEquals(1, session.status().hits)
        assertEquals(4, session.status().misses)
        assertEquals(2, session.status().entries)
    }

    @Test
    fun mismatchedProviderAttestationIsNeverCached() {
        val expected = snapshot("class Expected")
        val other = snapshot("class Other")
        val session = KotlinCompilerAnalysisSession { available(other) }

        assertFailsWith<IllegalArgumentException> { session.analyze(expected) }
        assertEquals(0, session.status().entries)
    }

    private fun snapshot(content: String) = ProjectSnapshot(
        workspace = Workspace(Path.of("/workspace")),
        modules = emptyList(),
        files = listOf(SourceFile(Path.of("src/main/kotlin/Value.kt"), content, "kotlin")),
    )

    private fun available(
        snapshot: ProjectSnapshot,
        diagnostics: List<Diagnostic> = emptyList(),
    ) = KotlinCompilerDiagnosticsResult.Available(
        diagnostics = diagnostics,
        attestation = KotlinCompilerDiagnosticsAttestation(
            backend = KotlinCompilerDiagnostics.BACKEND,
            kotlinVersion = "2.0.21",
            javaVersion = "21",
            toolchainProjectionHash = "a".repeat(64),
            buildProjectionHash = "b".repeat(64),
            snapshotHash = snapshot.hash,
            process = null,
        ),
        symbols = SymbolIndex(emptyList()),
    )
}
