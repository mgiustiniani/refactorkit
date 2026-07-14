package org.refactorkit.kotlin

import org.refactorkit.core.CapabilityStability
import org.refactorkit.core.CodeSelection
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.LanguageAdapter
import org.refactorkit.core.LanguageAdapterDescriptor
import org.refactorkit.core.LanguageCapability
import org.refactorkit.core.MutationAuthority
import org.refactorkit.core.ParseResult
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.Reference
import org.refactorkit.core.RefactoringDescriptor
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RefactoringRequest
import org.refactorkit.core.RegisteredLanguageAdapter
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SemanticEvidenceKind
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourceLocation
import org.refactorkit.core.SymbolId
import org.refactorkit.core.SymbolIndex
import org.refactorkit.core.SymbolResolution
import org.refactorkit.core.TextEdit
import org.refactorkit.core.WorkspaceEdit

/**
 * Fail-closed Kotlin adapter boundary.
 *
 * This class deliberately exposes no structural or semantic authority until a
 * hash-bound Kotlin compiler/Analysis API backend is qualified.
 */
class KotlinLanguageAdapter : LanguageAdapter {
    override fun languageId(): String = KotlinAdapterRegistration.LANGUAGE_ID

    override fun parse(file: SourceFile): ParseResult = ParseResult(
        file,
        listOf(if (file.languageId == languageId()) backendUnavailable() else Diagnostic(
            message = "Kotlin adapter cannot parse language '${file.languageId}'",
            severity = Diagnostic.Severity.ERROR,
            code = "kotlin.languageMismatch",
            evidence = DiagnosticEvidence.STRUCTURAL,
            category = DiagnosticCategory.PROJECT_STRUCTURE,
        )),
    )

    override fun buildSymbols(project: ProjectSnapshot): SymbolIndex = SymbolIndex(emptyList())

    override fun resolveSymbol(location: SourceLocation): SymbolResolution = SymbolResolution(
        symbol = null,
        diagnostics = listOf(backendUnavailable(location)),
    )

    override fun findReferences(symbolId: SymbolId): List<Reference> = emptyList()

    override fun diagnostics(project: ProjectSnapshot): List<Diagnostic> =
        if (project.files.any { it.languageId == languageId() }) listOf(backendUnavailable()) else emptyList()

    override fun availableRefactorings(selection: CodeSelection): List<RefactoringDescriptor> = emptyList()

    override fun applyRefactoring(request: RefactoringRequest): PatchPlan {
        val message = BACKEND_UNAVAILABLE_MESSAGE
        return PatchPlan(
            operation = request.operation,
            status = PatchStatus.REFUSED,
            snapshotHash = request.snapshot.hash,
            confidence = 0.0,
            requiresUserApproval = false,
            summary = message,
            affectedFiles = emptySet(),
            workspaceEdit = WorkspaceEdit(),
            diagnosticsBefore = listOf(backendUnavailable(request.selection?.location)),
            warnings = listOf(message),
            riskLevel = RiskLevel.HIGH,
            evidence = RefactoringEvidence.STRUCTURAL,
            refusalCode = BACKEND_UNAVAILABLE_CODE,
        )
    }

    override fun formatEdits(edits: List<TextEdit>): List<TextEdit> = edits.toList()

    private fun backendUnavailable(location: SourceLocation? = null) = Diagnostic(
        message = BACKEND_UNAVAILABLE_MESSAGE,
        severity = Diagnostic.Severity.WARNING,
        location = location,
        code = BACKEND_UNAVAILABLE_CODE,
        evidence = DiagnosticEvidence.STRUCTURAL,
        category = DiagnosticCategory.PROJECT_STRUCTURE,
    )

    companion object {
        const val BACKEND_UNAVAILABLE_CODE = "kotlin.semanticBackendUnavailable"
        const val BACKEND_UNAVAILABLE_MESSAGE =
            "Kotlin semantic backend is not configured; compiler-backed operations are refused"
    }
}

/** Registration and capability truth for the initial Kotlin module boundary. */
object KotlinAdapterRegistration {
    const val LANGUAGE_ID = "kotlin"
    const val BACKEND = "kotlin-analysis-unavailable-v1"

    private val kotlinSourceOperations = listOf(
        "changeSignature",
        "definition",
        "diagnostics",
        "documentSymbols",
        "extract",
        "formatFile",
        "inline",
        "moveSymbol",
        "organizeImports",
        "parse",
        "references",
        "renameSymbol",
        "workspaceSymbols",
    )

    fun descriptor() = LanguageAdapterDescriptor(
        languageId = LANGUAGE_ID,
        extensions = setOf("kt", "kts"),
        backend = BACKEND,
        capabilities = (
            kotlinSourceOperations.map { refused(it, setOf("kt")) } +
                refused("scriptSemantics", setOf("kts"))
            ).sortedBy(LanguageCapability::operation),
    )

    fun create(adapter: KotlinLanguageAdapter = KotlinLanguageAdapter()) = RegisteredLanguageAdapter(
        descriptor = descriptor(),
        adapter = adapter,
    )

    private fun refused(operation: String, extensions: Set<String>) = LanguageCapability(
        operation = operation,
        stability = CapabilityStability.REFUSED,
        evidence = SemanticEvidenceKind.NONE,
        mutationAuthority = MutationAuthority.NONE,
        backend = BACKEND,
        extensions = extensions,
    )
}
