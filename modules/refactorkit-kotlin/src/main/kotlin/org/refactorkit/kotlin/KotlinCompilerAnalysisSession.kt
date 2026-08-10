package org.refactorkit.kotlin

import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SymbolIndex
import java.util.LinkedHashMap

/** Bounded session-owned normalized K2 state keyed by exact immutable snapshot identity. */
data class KotlinCompilerAnalysisSessionStatus(
    val entries: Int,
    val hits: Long,
    val misses: Long,
)

class KotlinCompilerAnalysisSession(
    private val maxEntries: Int = 2,
    private val analyze: (ProjectSnapshot) -> KotlinCompilerDiagnosticsResult,
) {
    constructor(diagnostics: KotlinCompilerDiagnostics, maxEntries: Int = 2) : this(maxEntries, diagnostics::analyze)

    private val entries = LinkedHashMap<String, KotlinCompilerDiagnosticsResult>(maxEntries, 0.75f, true)
    private var hits = 0L
    private var misses = 0L

    init {
        require(maxEntries in 1..8) { "K2 semantic-session entry limit is invalid" }
    }

    @Synchronized
    fun analyze(snapshot: ProjectSnapshot): KotlinCompilerDiagnosticsResult {
        entries[snapshot.hash]?.let { cached ->
            hits++
            return detached(cached)
        }
        misses++
        val result = detached(analyze.invoke(snapshot))
        require(result.attestation.snapshotHash == snapshot.hash) {
            "K2 semantic-session result attests another snapshot"
        }
        entries[snapshot.hash] = result
        if (entries.size > maxEntries) entries.entries.iterator().run { next(); remove() }
        return detached(result)
    }

    fun symbols(snapshot: ProjectSnapshot): KotlinCompilerSymbolsResult = when (val result = analyze(snapshot)) {
        is KotlinCompilerDiagnosticsResult.Available -> {
            val attestation = result.attestation.copy(backend = KotlinCompilerDiagnostics.SYMBOL_BACKEND)
            val symbols = result.symbols
            when {
                symbols != null -> KotlinCompilerSymbolsResult.Available(
                    symbols,
                    attestation,
                    result.usages,
                    result.externalTypeUsages,
                    result.externalCallableUsages,
                    result.declarations,
                )
                result.symbolFailure?.code == "kotlin.compilerSymbolsInvalid" ->
                    KotlinCompilerSymbolsResult.Error(result.symbolFailure, attestation)
                else -> KotlinCompilerSymbolsResult.Refused(
                    result.symbolFailure ?: org.refactorkit.core.Diagnostic(
                        message = "Kotlin compiler symbols are unavailable for this snapshot",
                        severity = org.refactorkit.core.Diagnostic.Severity.ERROR,
                        code = "kotlin.symbolsUnavailable",
                        evidence = org.refactorkit.core.DiagnosticEvidence.COMPILER,
                        category = org.refactorkit.core.DiagnosticCategory.SAFETY,
                    ),
                    attestation,
                )
            }
        }
        is KotlinCompilerDiagnosticsResult.Refused -> KotlinCompilerSymbolsResult.Refused(
            result.reason,
            result.attestation.copy(backend = KotlinCompilerDiagnostics.SYMBOL_BACKEND),
        )
        is KotlinCompilerDiagnosticsResult.Error -> KotlinCompilerSymbolsResult.Error(
            result.failure,
            result.attestation.copy(backend = KotlinCompilerDiagnostics.SYMBOL_BACKEND),
        )
    }

    @Synchronized
    fun status(): KotlinCompilerAnalysisSessionStatus = KotlinCompilerAnalysisSessionStatus(
        entries.size,
        hits,
        misses,
    )

    @Synchronized
    fun clear() {
        entries.clear()
    }

    private fun detached(result: KotlinCompilerDiagnosticsResult): KotlinCompilerDiagnosticsResult = when (result) {
        is KotlinCompilerDiagnosticsResult.Available -> result.copy(
            diagnostics = result.diagnostics.toList(),
            symbols = result.symbols?.let { SymbolIndex(it.symbols.toList()) },
            usages = result.usages.toList(),
            externalTypeUsages = result.externalTypeUsages.toList(),
            externalCallableUsages = result.externalCallableUsages.toList(),
            declarations = result.declarations.toMap(),
        )
        is KotlinCompilerDiagnosticsResult.Refused -> result.copy()
        is KotlinCompilerDiagnosticsResult.Error -> result.copy()
    }
}
