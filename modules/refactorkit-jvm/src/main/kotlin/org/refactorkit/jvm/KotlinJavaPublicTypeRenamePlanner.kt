package org.refactorkit.jvm

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourceLocation
import org.refactorkit.core.Symbol
import org.refactorkit.core.SymbolId
import org.refactorkit.core.TextEdit
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.core.owningBuildSourceRoots
import org.refactorkit.java.JdtJavaSemanticAnalysisResult
import org.refactorkit.java.JdtJavaSemanticAnalyzer
import org.refactorkit.kotlin.KotlinCompilerDeclarationEvidence
import org.refactorkit.kotlin.KotlinCompilerDiagnosticsResult
import org.refactorkit.kotlin.KotlinCompilerSymbolsResult
import org.refactorkit.kotlin.KotlinDeclarationVisibility
import org.refactorkit.kotlin.KotlinLanguageAdapter

/** First shared-JVM mutation row: a public top-level Kotlin type used by Java. */
class KotlinJavaPublicTypeRenamePlanner(
    private val kotlin: KotlinLanguageAdapter,
    private val java: JdtJavaSemanticAnalyzer = JdtJavaSemanticAnalyzer(),
) {
    /** Combined K2/JDT diagnostics gate for mixed plans produced by this planner. */
    fun diagnostics(snapshot: ProjectSnapshot): List<Diagnostic> = when (val evidence = analyzeMixed(snapshot)) {
        is MixedEvidence.Available -> evidence.kotlin.diagnostics + javaDiagnostics(evidence.java)
        is MixedEvidence.Refused -> listOf(Diagnostic(
            message = evidence.message,
            severity = Diagnostic.Severity.ERROR,
            code = evidence.code,
            evidence = DiagnosticEvidence.COMPILER,
            category = DiagnosticCategory.SAFETY,
        ))
    }

    fun preview(
        snapshot: ProjectSnapshot,
        symbolId: SymbolId,
        newName: String,
        acceptExternalConsumerRisk: Boolean = false,
    ): PatchPlan {
        if (!IDENTIFIER.matches(newName) || newName in KEYWORDS) return refused(
            snapshot, "jvm.renameTargetInvalid", "Shared JVM rename target is not a safe non-keyword JVM identifier",
        )
        if (snapshot.files.none { it.languageId == "java" }) return refused(
            snapshot, "jvm.renameMixedSnapshotRequired", "Shared JVM rename requires Java and Kotlin sources",
        )
        val symbols = when (val result = kotlin.compilerSymbols(snapshot)) {
            is KotlinCompilerSymbolsResult.Available -> result
            is KotlinCompilerSymbolsResult.Refused -> return refused(snapshot, result.reason.code ?: "jvm.renameEvidenceUnavailable", result.reason.message)
            is KotlinCompilerSymbolsResult.Error -> return refused(snapshot, result.failure.code ?: "jvm.renameEvidenceUnavailable", result.failure.message)
        }
        val target = symbols.index.symbols.singleOrNull { it.id == symbolId }
            ?: return refused(snapshot, "jvm.renameTargetMissing", "Kotlin target is absent from the attested declaration catalogue")
        val declaration = symbols.declarations[target.id]
            ?: return refused(snapshot, "jvm.renameIdentityUnavailable", "Kotlin target lacks JVM declaration evidence")
        val supportedType = target.kind in TYPE_KINDS && declaration.jvmIdentity == declaration.jvmOwner &&
            declaration.jvmDescriptor.isEmpty() && '$' !in declaration.jvmIdentity
        val supportedFunction = target.kind == Symbol.Kind.FUNCTION && '$' !in declaration.jvmOwner &&
            declaration.jvmDescriptor.startsWith("(") &&
            declaration.jvmIdentity == "${declaration.jvmOwner}#${target.name}${declaration.jvmDescriptor}"
        if (declaration.visibility != KotlinDeclarationVisibility.PUBLIC || (!supportedType && !supportedFunction)) {
            return refused(
                snapshot, "jvm.renamePublicDeclarationUnsupported",
                "Initial shared JVM rename requires a public top-level Kotlin type or one non-overloaded direct JVM function",
            )
        }
        if (!acceptExternalConsumerRisk) return refused(
            snapshot, "jvm.renameExternalConsumerApprovalRequired",
            "Public JVM rename requires explicit acceptance of unknown external-consumer risk",
        )
        if (newName == target.name) return refused(snapshot, "jvm.renameNoChange", "Shared JVM rename target is unchanged")
        if (symbols.index.symbols.any { it.id != target.id && it.name == newName }) return refused(
            snapshot, "jvm.renameConflict", "Shared JVM rename target collides with an existing Kotlin declaration",
        )
        if (snapshot.owningBuildSourceRoots(target.location.path).any { it.generated }) return refused(
            snapshot, "jvm.renameGeneratedSource", "Shared JVM rename target belongs to generated source",
        )
        dynamicRisk(snapshot, target.name)?.let { return refused(snapshot, "jvm.renameDynamicReference", it) }
        if (supportedFunction) {
            val callableReference = Regex("::\\s*${Regex.escape(target.name)}\\b")
            val unsupportedModifier = Regex("\\b(?:operator|infix|override)\\s+fun\\s+${Regex.escape(target.name)}\\b")
            if (snapshot.files.filter { it.languageId == "kotlin" }.any {
                    callableReference.containsMatchIn(it.content) || unsupportedModifier.containsMatchIn(it.content)
                }) return refused(
                snapshot, "jvm.renameReferenceCompletenessUnavailable",
                "Callable references or unsupported Kotlin function shapes prevent complete shared JVM rename",
            )
        }

        val beforeEvidence = analyzeMixed(snapshot)
        val before = when (beforeEvidence) {
            is MixedEvidence.Available -> beforeEvidence
            is MixedEvidence.Refused -> return refused(snapshot, beforeEvidence.code, beforeEvidence.message)
        }
        if (before.kotlin.diagnostics.any { it.severity == Diagnostic.Severity.ERROR } || before.java.warnings.isNotEmpty()) return refused(
            snapshot, "jvm.renameBaselineIncomplete", "Initial shared JVM rename requires a clean K2 and JDT baseline",
            before.kotlin.diagnostics + javaDiagnostics(before.java),
        )
        val javaUses = before.java.bindingUses.filter {
            if (supportedType) it.symbolQualifiedName == declaration.jvmIdentity
            else it.symbolQualifiedName?.startsWith("${declaration.jvmOwner}#${target.name}(") == true
        }
            .distinctBy { Triple(it.path.normalize(), it.sourceRange.start, it.sourceRange.end) }
        if (javaUses.isEmpty()) return refused(
            snapshot, "jvm.renameCrossLanguageReferenceMissing", "JDT found no Java binding use for the Kotlin binary identity",
        )
        val locations = (listOf(target.location) + symbols.usages.filter { it.targetId == target.id }.map { it.location } +
            javaUses.map { SourceLocation(it.path, it.sourceRange) })
            .distinct().sortedWith(compareBy({ it.path.toString() }, { it.range.start.line }, { it.range.start.character }))
        if (locations.any { snapshot.owningBuildSourceRoots(it.path).any { owner -> owner.generated } }) return refused(
            snapshot, "jvm.renameGeneratedSource", "Shared JVM rename reference belongs to generated source",
        )
        if (locations.any { location ->
                val source = snapshot.files.singleOrNull { it.path.normalize() == location.path.normalize() } ?: return@any true
                selectedText(source.content, location) != target.name
            }) return refused(snapshot, "jvm.renameRangeInvalid", "Shared JVM evidence contains an invalid exact token")

        val edit = WorkspaceEdit(locations.groupBy { it.path.normalize() }.toSortedMap(compareBy { it.toString() }).map { (path, ranges) ->
            FileEdit.Modify(path, ranges.map { TextEdit(it.range, newName) })
        })
        val staged = runCatching { WorkspaceEditSimulator.apply(snapshot, edit) }.getOrElse {
            return refused(snapshot, "jvm.renamePreviewInvalid", it.message ?: "Shared JVM preview is invalid")
        }
        val afterEvidence = analyzeMixed(staged)
        val after = when (afterEvidence) {
            is MixedEvidence.Available -> afterEvidence
            is MixedEvidence.Refused -> return refused(snapshot, afterEvidence.code, afterEvidence.message)
        }
        val introduced = introducedDiagnostics(
            before.kotlin.diagnostics + javaDiagnostics(before.java),
            after.kotlin.diagnostics + javaDiagnostics(after.java),
        )
        if (introduced.isNotEmpty()) return refused(
            snapshot, "jvm.renameDiagnosticsRegression", "Shared JVM rename introduces ${introduced.size} compiler error(s)", introduced,
        )
        val renamedIdentity = if (supportedType) declaration.jvmIdentity.substringBeforeLast('.', "")
            .let { if (it.isEmpty()) newName else "$it.$newName" }
        else "${declaration.jvmOwner}#$newName("
        if (after.java.bindingUses.none {
                if (supportedType) it.symbolQualifiedName == renamedIdentity
                else it.symbolQualifiedName?.startsWith(renamedIdentity) == true
            }) return refused(
            snapshot, "jvm.renamePostImageIdentityMissing", "JDT did not resolve the renamed Kotlin binary identity in the staged snapshot",
        )
        return PatchPlan(
            operation = "renameSymbol", status = PatchStatus.PREVIEW, snapshotHash = snapshot.hash,
            confidence = 0.92, requiresUserApproval = true,
            summary = "Rename public Kotlin ${if (supportedType) "type" else "function"} '${target.name}' to '$newName' across ${locations.size} K2/JDT-proven token(s).",
            affectedFiles = edit.affectedFiles(), workspaceEdit = edit,
            diagnosticsBefore = before.kotlin.diagnostics + javaDiagnostics(before.java),
            diagnosticsAfterPreview = after.kotlin.diagnostics + javaDiagnostics(after.java),
            warnings = listOf("Public JVM API rename: unknown external consumers were explicitly accepted for this preview."),
            riskLevel = RiskLevel.HIGH, evidence = RefactoringEvidence.NATIVE_AST,
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
                    failure.code ?: "jvm.renameKotlinUsageEvidenceUnavailable", failure.message,
                )
            } ?: javaEvidence?.let { MixedEvidence.Available(kotlinResult, it) }
                ?: MixedEvidence.Refused("jvm.renameBinaryEvidenceUnavailable", "Kotlin compilation did not publish complete JVM binary evidence")
            is KotlinCompilerDiagnosticsResult.Refused -> MixedEvidence.Refused(
                kotlinResult.reason.code ?: "jvm.renameEvidenceUnavailable", kotlinResult.reason.message,
            )
            is KotlinCompilerDiagnosticsResult.Error -> MixedEvidence.Refused(
                kotlinResult.failure.code ?: "jvm.renameEvidenceUnavailable", kotlinResult.failure.message,
            )
        }
    }

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

    private fun dynamicRisk(snapshot: ProjectSnapshot, oldName: String): String? {
        val quoted = Regex("[\\\"'][^\\\"'\\n]*\\b${Regex.escape(oldName)}\\b[^\\\"'\\n]*[\\\"']")
        return if (snapshot.files.any { it.languageId in setOf("java", "kotlin") && quoted.containsMatchIn(it.content) }) {
            "Quoted reflection, serialization, framework or configuration name candidate prevents shared JVM rename"
        } else null
    }

    private fun selectedText(content: String, location: SourceLocation): String {
        val range = location.range
        val lines = content.split('\n')
        if (range.start.line != range.end.line || range.start.line !in lines.indices) return ""
        val line = lines[range.start.line]
        if (range.start.character !in 0..line.length || range.end.character !in 0..line.length) return ""
        return line.substring(range.start.character, range.end.character)
    }

    private fun refused(
        snapshot: ProjectSnapshot,
        code: String,
        message: String,
        diagnostics: List<Diagnostic> = emptyList(),
    ) = PatchPlan(
        operation = "renameSymbol", status = PatchStatus.REFUSED, snapshotHash = snapshot.hash,
        confidence = 0.0, requiresUserApproval = false, summary = message,
        affectedFiles = emptySet(), workspaceEdit = WorkspaceEdit(), diagnosticsAfterPreview = diagnostics,
        warnings = listOf(message), riskLevel = RiskLevel.HIGH, evidence = RefactoringEvidence.NATIVE_AST,
        refusalCode = code,
    )

    private sealed interface MixedEvidence {
        data class Available(
            val kotlin: KotlinCompilerDiagnosticsResult.Available,
            val java: JdtJavaSemanticAnalysisResult,
        ) : MixedEvidence
        data class Refused(val code: String, val message: String) : MixedEvidence
    }

    companion object {
        private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]{0,511}")
        private val TYPE_KINDS = setOf(
            Symbol.Kind.CLASS, Symbol.Kind.INTERFACE, Symbol.Kind.OBJECT, Symbol.Kind.ENUM, Symbol.Kind.ANNOTATION,
        )
        private val KEYWORDS = setOf(
            "class", "object", "interface", "fun", "val", "var", "when", "is", "in", "as", "private",
            "public", "internal", "protected", "return", "package", "import", "typealias",
        )
    }
}
