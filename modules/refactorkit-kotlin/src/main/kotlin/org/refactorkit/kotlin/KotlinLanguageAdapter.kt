package org.refactorkit.kotlin

import org.refactorkit.core.CapabilityStability
import org.refactorkit.core.AdapterExecutionMode
import org.refactorkit.core.CodeSelection
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.DiagnosticSnapshotMode
import org.refactorkit.core.LanguageAdapter
import org.refactorkit.core.LanguageAdapterDescriptor
import org.refactorkit.core.LanguageAdapterResourceLimits
import org.refactorkit.core.LanguageAdapterRuntime
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
import java.nio.file.Path
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
class KotlinLanguageAdapter(
    private val compilerDiagnostics: KotlinCompilerDiagnostics? = null,
) : LanguageAdapter {
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

    override fun buildSymbols(project: ProjectSnapshot): SymbolIndex = when (val result = compilerSymbols(project)) {
        is KotlinCompilerSymbolsResult.Available -> result.index
        is KotlinCompilerSymbolsResult.Refused, is KotlinCompilerSymbolsResult.Error -> SymbolIndex(emptyList())
    }

    fun compilerSymbols(project: ProjectSnapshot): KotlinCompilerSymbolsResult = compilerDiagnostics?.analyzeSymbols(project)
        ?: KotlinCompilerSymbolsResult.Refused(
            compilerNotConfigured(),
            unconfiguredAttestation(project, KotlinCompilerDiagnostics.SYMBOL_BACKEND),
        )

    fun findSymbol(project: ProjectSnapshot, symbolId: SymbolId) = when (val result = compilerSymbols(project)) {
        is KotlinCompilerSymbolsResult.Available -> result.index.symbols.singleOrNull { it.id == symbolId }
        is KotlinCompilerSymbolsResult.Refused, is KotlinCompilerSymbolsResult.Error -> null
    }

    override fun resolveSymbol(location: SourceLocation): SymbolResolution = SymbolResolution(
        symbol = null,
        diagnostics = listOf(backendUnavailable(location)),
    )

    override fun findReferences(symbolId: SymbolId): List<Reference> = emptyList()

    override fun diagnostics(project: ProjectSnapshot): List<Diagnostic> = when {
        project.files.none { it.languageId == languageId() } -> emptyList()
        compilerDiagnostics == null -> listOf(compilerNotConfigured())
        else -> compilerDiagnostics.analyze(project).diagnostics
    }

    fun compilerDiagnosticsWithAdditionalClasspath(
        project: ProjectSnapshot,
        additionalClasspath: List<java.nio.file.Path>,
    ): KotlinCompilerDiagnosticsResult = compilerDiagnostics?.analyzeWithAdditionalClasspath(project, additionalClasspath)
        ?: KotlinCompilerDiagnosticsResult.Refused(
            compilerNotConfigured(),
            unconfiguredAttestation(project, KotlinCompilerDiagnostics.BACKEND),
        )

    fun compilerDiagnosticsWithOutput(
        project: ProjectSnapshot,
        consumer: (java.nio.file.Path) -> Unit,
    ): KotlinCompilerDiagnosticsResult = compilerDiagnostics?.analyzeWithCompiledOutput(project, consumer)
        ?: KotlinCompilerDiagnosticsResult.Refused(
            compilerNotConfigured(),
            unconfiguredAttestation(project, KotlinCompilerDiagnostics.BACKEND),
        )

    fun compilerDiagnostics(project: ProjectSnapshot): KotlinCompilerDiagnosticsResult = compilerDiagnostics?.analyze(project)
        ?: KotlinCompilerDiagnosticsResult.Refused(
            compilerNotConfigured(),
            unconfiguredAttestation(project, KotlinCompilerDiagnostics.BACKEND),
        )

    private fun unconfiguredAttestation(project: ProjectSnapshot, backend: String) = KotlinCompilerDiagnosticsAttestation(
        backend = backend,
        kotlinVersion = "unconfigured",
        javaVersion = "unconfigured",
        toolchainProjectionHash = "",
        buildProjectionHash = "",
        snapshotHash = project.hash,
        process = null,
    )

    override fun availableRefactorings(selection: CodeSelection): List<RefactoringDescriptor> = listOf(
        RefactoringDescriptor("changeSignature", "Change Kotlin method signature (parameter rename)", RiskLevel.MEDIUM),
        RefactoringDescriptor("extractMethod", "Extract Kotlin method/function", RiskLevel.MEDIUM),
        RefactoringDescriptor("inlineMethod", "Inline Kotlin method/function call", RiskLevel.MEDIUM),
    )

    override fun applyRefactoring(request: RefactoringRequest): PatchPlan {
        if (request.operation == "changeSignature") {
            val oldName = request.arguments["oldName"] ?: request.arguments["oldParameterName"]
                ?: return PatchPlan(operation = "changeSignature", status = PatchStatus.REFUSED, snapshotHash = request.snapshot.hash, confidence = 0.0, requiresUserApproval = false, summary = "changeSignature requires oldName", affectedFiles = emptySet(), workspaceEdit = WorkspaceEdit(), warnings = listOf("changeSignature requires oldName"), riskLevel = RiskLevel.HIGH, evidence = RefactoringEvidence.NATIVE_AST, refusalCode = "kotlin.changeSignatureOldNameMissing")
            val newName = request.arguments["newName"] ?: request.arguments["newParameterName"]
                ?: return PatchPlan(operation = "changeSignature", status = PatchStatus.REFUSED, snapshotHash = request.snapshot.hash, confidence = 0.0, requiresUserApproval = false, summary = "changeSignature requires newName", affectedFiles = emptySet(), workspaceEdit = WorkspaceEdit(), warnings = listOf("changeSignature requires newName"), riskLevel = RiskLevel.HIGH, evidence = RefactoringEvidence.NATIVE_AST, refusalCode = "kotlin.changeSignatureNewNameMissing")
            val symbol = request.symbolId?.value ?: request.arguments["symbol"]
                ?: return PatchPlan(operation = "changeSignature", status = PatchStatus.REFUSED, snapshotHash = request.snapshot.hash, confidence = 0.0, requiresUserApproval = false, summary = "changeSignature requires symbol", affectedFiles = emptySet(), workspaceEdit = WorkspaceEdit(), warnings = listOf("changeSignature requires symbol"), riskLevel = RiskLevel.HIGH, evidence = RefactoringEvidence.NATIVE_AST, refusalCode = "kotlin.changeSignatureSymbolMissing")
            return KotlinChangeSignaturePlanner(this).previewRenameParameter(request.snapshot, symbol, oldName, newName)
        }
        if (request.operation == "extractMethod") {
            val file = request.arguments["file"] ?: request.selection?.location?.path?.toString()
                ?: return PatchPlan(operation = "extractMethod", status = PatchStatus.REFUSED, snapshotHash = request.snapshot.hash, confidence = 0.0, requiresUserApproval = false, summary = "extractMethod requires file", affectedFiles = emptySet(), workspaceEdit = WorkspaceEdit(), warnings = listOf("extractMethod requires file"), riskLevel = RiskLevel.HIGH, evidence = RefactoringEvidence.NATIVE_AST, refusalCode = "kotlin.extractFileMissing")
            val startLine = request.arguments["startLine"]?.toIntOrNull() ?: request.selection?.location?.range?.start?.line?.plus(1)
                ?: return PatchPlan(operation = "extractMethod", status = PatchStatus.REFUSED, snapshotHash = request.snapshot.hash, confidence = 0.0, requiresUserApproval = false, summary = "extractMethod requires startLine", affectedFiles = emptySet(), workspaceEdit = WorkspaceEdit(), warnings = listOf("extractMethod requires startLine"), riskLevel = RiskLevel.HIGH, evidence = RefactoringEvidence.NATIVE_AST, refusalCode = "kotlin.extractStartMissing")
            val endLine = request.arguments["endLine"]?.toIntOrNull() ?: request.selection?.location?.range?.end?.line?.plus(1)
                ?: return PatchPlan(operation = "extractMethod", status = PatchStatus.REFUSED, snapshotHash = request.snapshot.hash, confidence = 0.0, requiresUserApproval = false, summary = "extractMethod requires endLine", affectedFiles = emptySet(), workspaceEdit = WorkspaceEdit(), warnings = listOf("extractMethod requires endLine"), riskLevel = RiskLevel.HIGH, evidence = RefactoringEvidence.NATIVE_AST, refusalCode = "kotlin.extractEndMissing")
            val methodName = request.arguments["methodName"] ?: "extracted"
            return KotlinExtractMethodPlanner(this).preview(request.snapshot, Path.of(file), startLine, endLine, methodName)
        }
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

    private fun compilerNotConfigured() = Diagnostic(
        message = "Kotlin compiler diagnostics require an explicitly configured semantic toolchain",
        severity = Diagnostic.Severity.ERROR,
        code = "kotlin.toolchainNotConfigured",
        evidence = DiagnosticEvidence.COMPILER,
        category = DiagnosticCategory.SAFETY,
    )

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

    // Capability matrices for Kotlin language shapes
    private val kotlinShapeMatrices = listOf(
        LanguageCapability(
            operation = "extensionReceiver",
            stability = CapabilityStability.REFUSED,
            evidence = SemanticEvidenceKind.NONE,
            mutationAuthority = MutationAuthority.NONE,
            backend = BACKEND,
            extensions = setOf("kt"),
        ),
        LanguageCapability(
            operation = "suspendFunction",
            stability = CapabilityStability.REFUSED,
            evidence = SemanticEvidenceKind.NONE,
            mutationAuthority = MutationAuthority.NONE,
            backend = BACKEND,
            extensions = setOf("kt"),
        ),
        LanguageCapability(
            operation = "expectActual",
            stability = CapabilityStability.REFUSED,
            evidence = SemanticEvidenceKind.NONE,
            mutationAuthority = MutationAuthority.NONE,
            backend = BACKEND,
            extensions = setOf("kt"),
        ),
        LanguageCapability(
            operation = "multiplatform",
            stability = CapabilityStability.REFUSED,
            evidence = SemanticEvidenceKind.NONE,
            mutationAuthority = MutationAuthority.NONE,
            backend = BACKEND,
            extensions = setOf("kt"),
        ),
        LanguageCapability(
            operation = "companionObject",
            stability = CapabilityStability.EXPERIMENTAL,
            evidence = SemanticEvidenceKind.COMPILER,
            mutationAuthority = MutationAuthority.PROPOSAL_ONLY,
            backend = KotlinCompilerDiagnostics.SYMBOL_BACKEND,
            runtime = LanguageAdapterRuntime(
                executionMode = AdapterExecutionMode.EXTERNAL_PROCESS,
                supportsTimeout = true,
                supportsCancellation = true,
                usesWorkspaceOverlay = true,
                recordsProcessProvenance = true,
                limits = LanguageAdapterResourceLimits(
                    requestTimeoutMillis = KotlinCompilerDiagnostics.REQUEST_TIMEOUT_MILLIS,
                    maxInputBytes = 128L * 1024L * 1024L,
                    maxOutputBytes = KotlinCompilerDiagnostics.MAX_OUTPUT_BYTES,
                    maxProcesses = 1,
                ),
            ),
            extensions = setOf("kt"),
        ),
        LanguageCapability(
            operation = "sealedClass",
            stability = CapabilityStability.REFUSED,
            evidence = SemanticEvidenceKind.NONE,
            mutationAuthority = MutationAuthority.NONE,
            backend = BACKEND,
            extensions = setOf("kt"),
        ),
        LanguageCapability(
            operation = "dataClass",
            stability = CapabilityStability.EXPERIMENTAL,
            evidence = SemanticEvidenceKind.COMPILER,
            mutationAuthority = MutationAuthority.PROPOSAL_ONLY,
            backend = KotlinCompilerDiagnostics.SYMBOL_BACKEND,
            runtime = LanguageAdapterRuntime(
                executionMode = AdapterExecutionMode.EXTERNAL_PROCESS,
                supportsTimeout = true,
                supportsCancellation = true,
                usesWorkspaceOverlay = true,
                recordsProcessProvenance = true,
                limits = LanguageAdapterResourceLimits(
                    requestTimeoutMillis = KotlinCompilerDiagnostics.REQUEST_TIMEOUT_MILLIS,
                    maxInputBytes = 128L * 1024L * 1024L,
                    maxOutputBytes = KotlinCompilerDiagnostics.MAX_OUTPUT_BYTES,
                    maxProcesses = 1,
                ),
            ),
            extensions = setOf("kt"),
        ),
        LanguageCapability(
            operation = "valueClass",
            stability = CapabilityStability.REFUSED,
            evidence = SemanticEvidenceKind.NONE,
            mutationAuthority = MutationAuthority.NONE,
            backend = BACKEND,
            extensions = setOf("kt"),
        ),
    )

    fun descriptor() = LanguageAdapterDescriptor(
        languageId = LANGUAGE_ID,
        extensions = setOf("kt", "kts"),
        backend = BACKEND,
        capabilities = (
            kotlinShapeMatrices +
            kotlinSourceOperations.map { operation ->
                when (operation) {
                    "diagnostics" -> diagnosticsCapability()
                    "workspaceSymbols", "documentSymbols", "definition" -> symbolCapability(operation)
                    "renameSymbol", "organizeImports" -> mutationCapability(operation)
                    else -> refused(operation, setOf("kt"))
                }
            } + refused("scriptSemantics", setOf("kts"))
            ).sortedBy(LanguageCapability::operation),
    )

    fun create(adapter: KotlinLanguageAdapter = KotlinLanguageAdapter()) = RegisteredLanguageAdapter(
        descriptor = descriptor(),
        adapter = adapter,
    )

    private fun diagnosticsCapability() = LanguageCapability(
        operation = "diagnostics",
        stability = CapabilityStability.EXPERIMENTAL,
        evidence = SemanticEvidenceKind.COMPILER,
        mutationAuthority = MutationAuthority.NONE,
        backend = KotlinCompilerDiagnostics.BACKEND,
        runtime = LanguageAdapterRuntime(
            executionMode = AdapterExecutionMode.EXTERNAL_PROCESS,
            supportsTimeout = true,
            supportsCancellation = true,
            usesWorkspaceOverlay = true,
            recordsProcessProvenance = true,
            limits = LanguageAdapterResourceLimits(
                requestTimeoutMillis = KotlinCompilerDiagnostics.REQUEST_TIMEOUT_MILLIS,
                maxInputBytes = 128L * 1024L * 1024L,
                maxOutputBytes = KotlinCompilerDiagnostics.MAX_OUTPUT_BYTES,
                maxProcesses = 1,
            ),
        ),
        extensions = setOf("kt"),
        diagnosticSnapshotModes = setOf(DiagnosticSnapshotMode.SAVED_DISK),
    )

    private fun symbolCapability(operation: String) = LanguageCapability(
        operation = operation,
        stability = CapabilityStability.EXPERIMENTAL,
        evidence = SemanticEvidenceKind.COMPILER,
        mutationAuthority = MutationAuthority.NONE,
        backend = KotlinCompilerDiagnostics.SYMBOL_BACKEND,
        runtime = LanguageAdapterRuntime(
            executionMode = AdapterExecutionMode.EXTERNAL_PROCESS,
            supportsTimeout = true,
            supportsCancellation = true,
            usesWorkspaceOverlay = true,
            recordsProcessProvenance = true,
            limits = LanguageAdapterResourceLimits(
                requestTimeoutMillis = KotlinCompilerDiagnostics.REQUEST_TIMEOUT_MILLIS,
                maxInputBytes = 128L * 1024L * 1024L,
                maxOutputBytes = KotlinCompilerDiagnostics.MAX_OUTPUT_BYTES,
                maxProcesses = 1,
            ),
        ),
        extensions = setOf("kt"),
    )

    private fun mutationCapability(operation: String) = LanguageCapability(
        operation = operation,
        stability = CapabilityStability.EXPERIMENTAL,
        evidence = SemanticEvidenceKind.COMPILER,
        mutationAuthority = MutationAuthority.PROPOSAL_ONLY,
        backend = KotlinCompilerDiagnostics.SYMBOL_BACKEND,
        runtime = LanguageAdapterRuntime(
            executionMode = AdapterExecutionMode.EXTERNAL_PROCESS,
            supportsTimeout = true,
            supportsCancellation = true,
            usesWorkspaceOverlay = true,
            recordsProcessProvenance = true,
            limits = LanguageAdapterResourceLimits(
                requestTimeoutMillis = KotlinCompilerDiagnostics.REQUEST_TIMEOUT_MILLIS,
                maxInputBytes = 128L * 1024L * 1024L,
                maxOutputBytes = KotlinCompilerDiagnostics.MAX_OUTPUT_BYTES,
                maxProcesses = 1,
            ),
        ),
        extensions = setOf("kt"),
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
