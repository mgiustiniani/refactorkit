package org.refactorkit.jvm

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceLocation
import org.refactorkit.core.SymbolId
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.java.JdtJavaSemanticAnalysisResult
import org.refactorkit.java.JdtJavaSemanticAnalyzer
import org.refactorkit.kotlin.KotlinChangeSignaturePlanner
import org.refactorkit.kotlin.KotlinCompilerDiagnosticsResult
import org.refactorkit.kotlin.KotlinCompilerSymbolsResult
import org.refactorkit.kotlin.KotlinLanguageAdapter

/** Adds exact JDT Java-caller proof to the K2-owned Kotlin parameter rename. */
class KotlinJvmChangeSignaturePlanner(
    private val kotlin: KotlinLanguageAdapter,
    private val java: JdtJavaSemanticAnalyzer = JdtJavaSemanticAnalyzer(),
) {
    fun diagnostics(snapshot: ProjectSnapshot): List<Diagnostic> = when (val result = analyzeMixed(snapshot)) {
        is MixedEvidence.Available -> result.kotlin.diagnostics + javaDiagnostics(result.java)
        is MixedEvidence.Refused -> listOf(Diagnostic(
            message = result.message,
            severity = Diagnostic.Severity.ERROR,
            code = result.code,
            evidence = DiagnosticEvidence.COMPILER,
            category = DiagnosticCategory.SAFETY,
        ))
    }

    fun previewRenameParameter(
        snapshot: ProjectSnapshot,
        symbolId: SymbolId,
        oldParameterName: String,
        newParameterName: String,
        acceptExternalConsumerRisk: Boolean = false,
    ): PatchPlan {
        val catalogue = when (val result = kotlin.compilerSymbols(snapshot)) {
            is KotlinCompilerSymbolsResult.Available -> result
            is KotlinCompilerSymbolsResult.Refused -> return refused(
                snapshot, result.reason.code ?: "kotlin.changeSignatureEvidenceUnavailable", result.reason.message,
            )
            is KotlinCompilerSymbolsResult.Error -> return refused(
                snapshot, result.failure.code ?: "kotlin.changeSignatureEvidenceUnavailable", result.failure.message,
            )
        }
        val target = catalogue.index.symbols.singleOrNull { it.id == symbolId }
            ?: return refused(snapshot, "kotlin.changeSignatureTargetMissing", "Kotlin callable target is absent")
        val declaration = catalogue.declarations[target.id]
            ?: return refused(snapshot, "kotlin.changeSignatureIdentityMissing", "Kotlin callable target lacks JVM evidence")
        val before = when (val result = analyzeMixed(snapshot)) {
            is MixedEvidence.Available -> result
            is MixedEvidence.Refused -> return refused(snapshot, result.code, result.message)
        }
        if (before.kotlin.diagnostics.any { it.severity == Diagnostic.Severity.ERROR } || before.java.warnings.isNotEmpty()) {
            return refused(
                snapshot, "kotlin.changeSignatureMixedBaselineIncomplete",
                "Kotlin parameter rename requires clean K2 and JDT baseline evidence",
                before.kotlin.diagnostics + javaDiagnostics(before.java),
            )
        }
        val beforeJavaBindings = javaBindings(before.java, declaration.jvmOwner, declaration.jvmName, declaration.jvmDescriptor)
        val base = KotlinChangeSignaturePlanner(kotlin).previewRenameParameter(
            snapshot, symbolId, oldParameterName, newParameterName, acceptExternalConsumerRisk,
        )
        if (base.status != PatchStatus.PREVIEW) return base
        val staged = runCatching { WorkspaceEditSimulator.apply(snapshot, base.workspaceEdit) }.getOrElse {
            return refused(
                snapshot, "kotlin.changeSignaturePreviewInvalid", it.message ?: "Invalid mixed signature preview",
            )
        }
        val after = when (val result = analyzeMixed(staged)) {
            is MixedEvidence.Available -> result
            is MixedEvidence.Refused -> return refused(snapshot, result.code, result.message)
        }
        val introduced = introducedDiagnostics(
            before.kotlin.diagnostics + javaDiagnostics(before.java),
            after.kotlin.diagnostics + javaDiagnostics(after.java),
        )
        if (introduced.isNotEmpty()) return refused(
            snapshot, "kotlin.changeSignatureMixedDiagnosticsRegression",
            "Kotlin parameter rename introduces ${introduced.size} K2/JDT compiler error(s)", introduced,
        )
        val afterJavaBindings = javaBindings(after.java, declaration.jvmOwner, declaration.jvmName, declaration.jvmDescriptor)
        if (beforeJavaBindings != afterJavaBindings) return refused(
            snapshot, "kotlin.changeSignatureJavaBindingChanged",
            "Kotlin parameter rename changes an exact Java caller binding",
            after.kotlin.diagnostics + javaDiagnostics(after.java),
        )
        return base.copy(
            diagnosticsBefore = before.kotlin.diagnostics + javaDiagnostics(before.java),
            diagnosticsAfterPreview = after.kotlin.diagnostics + javaDiagnostics(after.java),
            warnings = base.warnings + if (beforeJavaBindings.isNotEmpty()) listOf(
                "${beforeJavaBindings.size} positional Java binding(s) retain the unchanged JVM descriptor.",
            ) else emptyList(),
        )
    }

    private fun analyzeMixed(snapshot: ProjectSnapshot): MixedEvidence {
        var javaEvidence: JdtJavaSemanticAnalysisResult? = null
        val kotlinResult = kotlin.compilerDiagnosticsWithOutput(snapshot) { output ->
            javaEvidence = java.analyze(snapshot, additionalClasspathEntries = listOf(output))
        }
        return when (kotlinResult) {
            is KotlinCompilerDiagnosticsResult.Available -> kotlinResult.symbolFailure?.let { failure ->
                MixedEvidence.Refused(
                    failure.code ?: "kotlin.changeSignatureUsageEvidenceUnavailable", failure.message,
                )
            } ?: javaEvidence?.let { MixedEvidence.Available(kotlinResult, it) }
                ?: MixedEvidence.Refused(
                    "kotlin.changeSignatureBinaryEvidenceUnavailable",
                    "Kotlin compilation did not publish complete staged JVM binary evidence",
                )
            is KotlinCompilerDiagnosticsResult.Refused -> MixedEvidence.Refused(
                kotlinResult.reason.code ?: "kotlin.changeSignatureEvidenceUnavailable", kotlinResult.reason.message,
            )
            is KotlinCompilerDiagnosticsResult.Error -> MixedEvidence.Refused(
                kotlinResult.failure.code ?: "kotlin.changeSignatureEvidenceUnavailable", kotlinResult.failure.message,
            )
        }
    }

    private fun javaBindings(
        analysis: JdtJavaSemanticAnalysisResult,
        owner: String,
        name: String,
        descriptor: String,
    ): List<String> = analysis.bindingUses.mapNotNull { use ->
        val identity = use.jvmIdentity ?: return@mapNotNull null
        if (identity.ownerBinaryName != owner || identity.memberName != name || identity.descriptor != descriptor) {
            return@mapNotNull null
        }
        listOf(use.path.normalize(), use.sourceRange, identity.ownerBinaryName, identity.memberName, identity.descriptor)
            .joinToString("\u0000")
    }.sorted()

    private fun javaDiagnostics(result: JdtJavaSemanticAnalysisResult) = result.warnings.map { warning ->
        Diagnostic(
            message = warning.message,
            severity = Diagnostic.Severity.ERROR,
            location = SourceLocation(warning.path, warning.sourceRange),
            code = "java.jdt.${warning.problemId}",
            evidence = DiagnosticEvidence.COMPILER,
            category = DiagnosticCategory.TYPE_RESOLUTION,
        )
    }

    private fun introducedDiagnostics(before: List<Diagnostic>, after: List<Diagnostic>): List<Diagnostic> {
        val baseline = before.filter { it.severity == Diagnostic.Severity.ERROR }.map(::diagnosticKey).toSet()
        return after.filter { it.severity == Diagnostic.Severity.ERROR && diagnosticKey(it) !in baseline }
    }

    private fun diagnosticKey(diagnostic: Diagnostic) = listOf(
        diagnostic.code.orEmpty(), diagnostic.location?.path?.toString().orEmpty(),
        diagnostic.location?.range?.toString().orEmpty(), diagnostic.message,
    )

    private fun refused(
        snapshot: ProjectSnapshot,
        code: String,
        message: String,
        diagnostics: List<Diagnostic> = emptyList(),
    ) = PatchPlan(
        operation = KotlinChangeSignaturePlanner.OPERATION,
        status = PatchStatus.REFUSED,
        snapshotHash = snapshot.hash,
        confidence = 0.0,
        requiresUserApproval = false,
        summary = message,
        affectedFiles = emptySet(),
        workspaceEdit = org.refactorkit.core.WorkspaceEdit(),
        diagnosticsAfterPreview = diagnostics,
        warnings = listOf(message),
        riskLevel = org.refactorkit.core.RiskLevel.HIGH,
        evidence = org.refactorkit.core.RefactoringEvidence.NATIVE_AST,
        refusalCode = code,
    )

    private sealed interface MixedEvidence {
        data class Available(
            val kotlin: KotlinCompilerDiagnosticsResult.Available,
            val java: JdtJavaSemanticAnalysisResult,
        ) : MixedEvidence
        data class Refused(val code: String, val message: String) : MixedEvidence
    }
}
