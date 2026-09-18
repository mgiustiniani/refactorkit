package org.refactorkit.c

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SemanticProcessProvenance
import java.nio.file.Path

/** Unified C symbol navigation result, distinguishing absent symbols from unavailable analysis. */
sealed interface CSymbolNavigationResult {
    /** The symbol was found semantically; provenance is the clangd process evidence. */
    data class Found(
        val definition: CSymbolDefinition,
        val provenance: SemanticProcessProvenance?,
    ) : CSymbolNavigationResult

    /** The position is valid but clangd reports no definition (symbol absent). */
    data class NotFound(val diagnostics: List<Diagnostic>) : CSymbolNavigationResult

    /** Semantic analysis was unavailable (not running, timeout, malformed, refused). */
    data class Unavailable(val diagnostics: List<Diagnostic>) : CSymbolNavigationResult
}

/**
 * C symbol navigation surface: structural index search plus clangd-backed semantic
 * definition/references. The clangd client is launched through the bounded process
 * manager; every request is deadline-bound. Absent symbols (NotFound) are distinct
 * from unavailable analysis (Unavailable).
 */
class CSymbolNavigation(
    private val toolchain: ClangSemanticToolchain,
    private val processManager: ExternalSemanticProcessManager = ExternalSemanticProcessManager(),
    private val semanticClient: ClangdSemanticClient = ClangdSemanticClient(toolchain, processManager),
) : AutoCloseable {
    private val units = mutableListOf<CTranslationUnitIndex>()
    private var started = false

    fun processProvenance(): SemanticProcessProvenance? = semanticClient.processProvenance()

    /** Launches clangd and opens every C source file in the snapshot. */
    fun start(snapshot: ProjectSnapshot) {
        require(!started) { "C symbol navigation is already started" }
        semanticClient.start(snapshot.workspace.root)
        val opened = snapshot.files
            .filter { it.languageId in setOf("c", "cpp", "objective-c") }
            .sortedBy { it.path.toString() }
            .all { semanticClient.didOpen(snapshot.workspace.root.resolve(it.path), it.content) }
        if (!opened) {
            close()
            error("clangd did not open every C source file")
        }
        started = true
    }

    /** Adds a structural translation-unit index to the bounded index. */
    fun addStructuralUnit(unit: CTranslationUnitIndex) {
        require(units.size + 1 <= CSymbolIndex.MAX_UNITS) { "C symbol index unit count exceeds the bounded limit" }
        require(units.none { it.file == unit.file }) { "C symbol index contains duplicate translation units" }
        units.add(unit)
    }

    /** Structural search over the bounded index (no semantic process required). */
    fun search(query: String): List<CSymbolIdentity> = units.flatMap { it.search(query) }.take(CSymbolIndex.MAX_RESULTS)

    /** Semantic definition; distinguishes absent (NotFound) from unavailable (Unavailable). */
    fun definition(file: Path, line: Int, character: Int): CSymbolNavigationResult {
        if (!started) return unavailable("clangd.notStarted", "C symbol navigation is not started")
        val result = semanticClient.definition(file, line, character)
        return when (result) {
            is CClangdSemanticResult.Found -> CSymbolNavigationResult.Found(result.definition, semanticClient.processProvenance())
            is CClangdSemanticResult.NotFound -> CSymbolNavigationResult.NotFound(result.diagnostics)
            is CClangdSemanticResult.Refused -> CSymbolNavigationResult.Unavailable(result.diagnostics)
        }
    }

    /**
     * Semantic references; distinguishes absent (NotFound, zero uses) from
     * unavailable analysis (Unavailable). Never returns an empty list for an
     * unavailable semantic session.
     */
    fun references(file: Path, line: Int, character: Int): CReferenceResult {
        if (!started) return CReferenceResult.Unavailable(listOf(Diagnostic(
            message = "C symbol navigation is not started",
            severity = Diagnostic.Severity.ERROR,
            code = "clangd.notStarted",
        )))
        return semanticClient.references(file, line, character)
    }

    override fun close() {
        semanticClient.close()
        started = false
    }

    private fun unavailable(code: String, message: String) = CSymbolNavigationResult.Unavailable(listOf(
        Diagnostic(message = message, severity = Diagnostic.Severity.ERROR, code = code),
    ))
}
