package org.refactorkit.kotlin

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourceRange
import org.refactorkit.core.SymbolId
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditSimulator

/** Inline one private top-level zero-argument integer-expression helper at its only direct call. */
class KotlinInlineMethodPlanner(
    private val kotlin: KotlinLanguageAdapter,
) {
    fun preview(snapshot: ProjectSnapshot, symbolId: SymbolId): PatchPlan {
        val before = available(snapshot) ?: return refused(
            snapshot, "kotlin.inlineEvidenceUnavailable",
            "Kotlin inline requires complete error-free K2 declaration and usage evidence",
        )
        val target = before.symbols.symbols.singleOrNull { it.id == symbolId }
            ?: return refused(snapshot, "kotlin.inlineTargetMissing", "Kotlin inline target is absent")
        val declaration = before.declarations[target.id]
            ?: return refused(snapshot, "kotlin.inlineIdentityMissing", "Kotlin inline target lacks K2 evidence")
        if (!declaration.isSimpleIntegerExpressionBody || !declaration.isTopLevelFunction ||
            declaration.visibility != KotlinDeclarationVisibility.PRIVATE) return refused(
            snapshot, "kotlin.inlineShapeUnsupported",
            "Kotlin inline accepts one private top-level zero-argument integer-expression helper",
        )
        if (snapshot.files.any { "::" in it.content }) return refused(
            snapshot, "kotlin.inlineCallableReferenceUnsupported",
            "Kotlin inline refuses snapshots containing an unqualified callable-reference token",
        )
        val calls = before.usages.filter { it.targetId == target.id }
        if (calls.size != 1) return refused(
            snapshot, "kotlin.inlineUsageCardinalityUnsupported",
            "Kotlin inline requires exactly one compiler-resolved direct call",
        )
        val call = calls.single()
        if (call.location.path.normalize() != target.location.path.normalize()) return refused(
            snapshot, "kotlin.inlineCrossFileUnsupported", "Private bounded inline requires a same-file call",
        )
        val source = snapshot.files.singleOrNull { it.path.normalize() == target.location.path.normalize() }
            ?: return refused(snapshot, "kotlin.inlineFileMissing", "Kotlin inline source file is absent")
        val bodyRange = declaration.bodyRange
            ?: return refused(snapshot, "kotlin.inlineRangeUnavailable", "K2 omitted the helper expression range")
        val declarationRange = declaration.declarationRange
            ?: return refused(snapshot, "kotlin.inlineRangeUnavailable", "K2 omitted the helper declaration range")
        val bodyStart = TextEdits.offsetOf(source.content, bodyRange.start)
        val bodyEnd = TextEdits.offsetOf(source.content, bodyRange.end)
        val callStart = TextEdits.offsetOf(source.content, call.location.range.start)
        val callNameEnd = TextEdits.offsetOf(source.content, call.location.range.end)
        val declarationStart = TextEdits.offsetOf(source.content, declarationRange.start)
        var declarationEnd = TextEdits.offsetOf(source.content, declarationRange.end)
        if (callStart !in 0 until callNameEnd || bodyStart !in 0 until bodyEnd ||
            declarationStart !in 0 until declarationEnd || declarationEnd > source.content.length ||
            callNameEnd + 2 > source.content.length || source.content.substring(callNameEnd, callNameEnd + 2) != "()") {
            return refused(
                snapshot, "kotlin.inlineDirectCallUnsupported",
                "The compiler-resolved use is not an exact no-whitespace zero-argument call",
            )
        }
        if (declarationEnd + 2 <= source.content.length &&
            source.content.substring(declarationEnd, declarationEnd + 2) == "\r\n") declarationEnd += 2
        else if (declarationEnd < source.content.length && source.content[declarationEnd] == '\n') declarationEnd++
        val expression = source.content.substring(bodyStart, bodyEnd)
        val callRange = SourceRange(
            call.location.range.start,
            TextEdits.positionForOffset(source.content, callNameEnd + 2),
        )
        val deleteRange = SourceRange(
            TextEdits.positionForOffset(source.content, declarationStart),
            TextEdits.positionForOffset(source.content, declarationEnd),
        )
        val workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(source.path, listOf(
            TextEdit(callRange, "($expression)"),
            TextEdit(deleteRange, ""),
        ))))
        val staged = runCatching { WorkspaceEditSimulator.apply(snapshot, workspaceEdit) }.getOrElse {
            return refused(snapshot, "kotlin.inlinePreviewInvalid", it.message ?: "Invalid Kotlin inline preview")
        }
        val after = available(staged) ?: return refused(
            snapshot, "kotlin.inlineStagedEvidenceUnavailable",
            "K2 did not return complete error-free evidence for the inlined post-image",
        )
        if (target.id in after.declarations ||
            before.declarations.keys.filterNot { it == target.id }.toSet() != after.declarations.keys) return refused(
            snapshot, "kotlin.inlinePostImageIdentityMismatch",
            "K2 did not prove exact removal of only the private helper identity",
        )
        if (usageFingerprint(before, snapshot, target.id) != usageFingerprint(after, staged, target.id)) return refused(
            snapshot, "kotlin.inlineBindingChanged",
            "Inlining changes a non-target compiler-resolved binding",
        )
        return PatchPlan(
            operation = OPERATION,
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.99,
            requiresUserApproval = true,
            summary = "Inline private integer-expression helper '${target.name}' at its only K2-proven call.",
            affectedFiles = workspaceEdit.affectedFiles(),
            workspaceEdit = workspaceEdit,
            diagnosticsBefore = before.diagnostics,
            diagnosticsAfterPreview = after.diagnostics,
            warnings = listOf("Bounded inline removes exactly one private helper and preserves all other K2 identities."),
            riskLevel = RiskLevel.LOW,
            evidence = RefactoringEvidence.NATIVE_AST,
        )
    }

    private fun usageFingerprint(
        evidence: Evidence,
        snapshot: ProjectSnapshot,
        excluded: SymbolId,
    ): List<String> {
        val sources = snapshot.files.associateBy { it.path.normalize() }
        fun selected(path: java.nio.file.Path, range: SourceRange): String {
            val source = sources.getValue(path.normalize())
            return source.content.substring(
                TextEdits.offsetOf(source.content, range.start), TextEdits.offsetOf(source.content, range.end),
            )
        }
        return buildList {
            evidence.usages.filterNot { it.targetId == excluded }.forEach {
                add("I\u0000${it.location.path.normalize()}\u0000${it.targetId.value}\u0000${selected(it.location.path, it.location.range)}")
            }
            evidence.externalTypes.forEach {
                add("T\u0000${it.location.path.normalize()}\u0000${it.jvmBinaryName}\u0000${selected(it.location.path, it.location.range)}")
            }
            evidence.externalCallables.forEach {
                add("C\u0000${it.location.path.normalize()}\u0000${it.jvmOwner}\u0000${it.callableName}\u0000${it.jvmDescriptor}\u0000${selected(it.location.path, it.location.range)}")
            }
        }.sorted()
    }

    private fun available(snapshot: ProjectSnapshot): Evidence? = when (val result = kotlin.compilerDiagnostics(snapshot)) {
        is KotlinCompilerDiagnosticsResult.Available -> if (
            result.symbolFailure == null && result.symbols != null &&
            result.diagnostics.none { it.severity == Diagnostic.Severity.ERROR }
        ) Evidence(
            result.symbols, result.declarations, result.usages, result.externalTypeUsages,
            result.externalCallableUsages, result.diagnostics,
        ) else null
        is KotlinCompilerDiagnosticsResult.Refused -> null
        is KotlinCompilerDiagnosticsResult.Error -> null
    }

    private fun refused(snapshot: ProjectSnapshot, code: String, message: String) = PatchPlan(
        operation = OPERATION,
        status = PatchStatus.REFUSED,
        snapshotHash = snapshot.hash,
        confidence = 0.0,
        requiresUserApproval = false,
        summary = message,
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        warnings = listOf(message),
        riskLevel = RiskLevel.HIGH,
        evidence = RefactoringEvidence.NATIVE_AST,
        refusalCode = code,
    )

    private data class Evidence(
        val symbols: org.refactorkit.core.SymbolIndex,
        val declarations: Map<SymbolId, KotlinCompilerDeclarationEvidence>,
        val usages: List<KotlinCompilerResolvedUsage>,
        val externalTypes: List<KotlinCompilerExternalTypeUsage>,
        val externalCallables: List<KotlinCompilerExternalCallableUsage>,
        val diagnostics: List<Diagnostic>,
    )

    companion object {
        const val OPERATION = "inlineMethod"
    }
}
