package org.refactorkit.kotlin

import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.core.owningBuildSourceRoots
import java.nio.file.Path

/**
 * Extracts one compiler-PSI-proven, top-level, zero-argument integer expression body.
 * The deliberately narrow shape has no inputs, writes, jumps, receivers, or inferred free variables.
 */
class KotlinExtractMethodPlanner(
    private val kotlin: KotlinLanguageAdapter,
) {
    fun preview(
        snapshot: ProjectSnapshot,
        filePath: Path,
        startLine: Int,
        endLine: Int,
        methodName: String,
    ): PatchPlan {
        val normalized = filePath.normalize()
        val source = snapshot.files.singleOrNull { it.path.normalize() == normalized }
            ?: return refused(snapshot, "kotlin.extractFileMissing", "File not found: $filePath")
        if (source.languageId != "kotlin") return refused(
            snapshot, "kotlin.extractUnsupported", "Kotlin extract requires one saved .kt file",
        )
        val ownership = snapshot.owningBuildSourceRoots(source.path)
        if (ownership.isEmpty() || ownership.map { it.root.normalize() }.distinct().size != 1 ||
            ownership.any { it.generated || it.modelStatus != BuildModelStatus.AVAILABLE }) return refused(
            snapshot, "kotlin.extractSourceOwnershipUnavailable",
            "Kotlin extract requires one authoritative non-generated source root",
        )
        if (!IDENTIFIER.matches(methodName) || methodName in KEYWORDS) return refused(
            snapshot, "kotlin.extractInvalidName", "Invalid Kotlin helper name: $methodName",
        )
        if (startLine < 1 || endLine < startLine) return refused(
            snapshot, "kotlin.extractInvalidRange", "Kotlin extract uses a non-empty one-based line range",
        )

        val before = available(snapshot, "kotlin.extract") ?: return refused(
            snapshot, "kotlin.extractEvidenceUnavailable",
            "Kotlin extract requires complete error-free K2 declaration and usage evidence",
        )
        val candidates = before.symbols.symbols.mapNotNull { symbol ->
            val evidence = before.declarations[symbol.id] ?: return@mapNotNull null
            val body = evidence.bodyRange ?: return@mapNotNull null
            if (symbol.location.path.normalize() == normalized && evidence.isSimpleIntegerExpressionBody &&
                body.start.line + 1 == startLine && body.end.line + 1 == endLine) {
                symbol to evidence
            } else null
        }
        if (candidates.size != 1) return refused(
            snapshot, "kotlin.extractSelectionUnsupported",
            "Kotlin extract accepts exactly one K2-proven top-level zero-argument integer expression body",
        )
        if (before.symbols.symbols.any { it.name == methodName }) return refused(
            snapshot, "kotlin.extractNameConflict", "A Kotlin declaration already uses helper name '$methodName'",
        )
        val (target, evidence) = candidates.single()
        val bodyRange = requireNotNull(evidence.bodyRange)
        val declarationRange = evidence.declarationRange ?: return refused(
            snapshot, "kotlin.extractRangeUnavailable", "K2 omitted the exact enclosing declaration range",
        )
        val bodyStart = TextEdits.offsetOf(source.content, bodyRange.start)
        val bodyEnd = TextEdits.offsetOf(source.content, bodyRange.end)
        val declarationEnd = TextEdits.offsetOf(source.content, declarationRange.end)
        if (bodyStart !in 0 until bodyEnd || bodyEnd > source.content.length ||
            declarationEnd !in bodyEnd..source.content.length) return refused(
            snapshot, "kotlin.extractRangeUnavailable", "K2 returned an invalid extraction range",
        )
        val expression = source.content.substring(bodyStart, bodyEnd)
        val newline = if ("\r\n" in source.content) "\r\n" else "\n"
        val helper = "$newline${newline}private fun $methodName() = $expression"
        val workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(source.path, listOf(
            TextEdit(bodyRange, "$methodName()"),
            TextEdit(SourceRange(declarationRange.end, declarationRange.end), helper),
        ))))
        val staged = runCatching { WorkspaceEditSimulator.apply(snapshot, workspaceEdit) }.getOrElse {
            return refused(snapshot, "kotlin.extractPreviewInvalid", it.message ?: "Invalid Kotlin extract preview")
        }
        val after = available(staged, "kotlin.extractStaged") ?: return refused(
            snapshot, "kotlin.extractStagedEvidenceUnavailable",
            "K2 did not return complete error-free evidence for the extracted post-image",
        )
        val targetAfter = after.declarations.values.singleOrNull { it.jvmIdentity == evidence.jvmIdentity }
        val helperAfter = after.symbols.symbols.singleOrNull { symbol ->
            symbol.name == methodName && symbol.location.path.normalize() == normalized &&
                after.declarations[symbol.id]?.isSimpleIntegerExpressionBody == true
        }
        if (targetAfter == null || helperAfter == null ||
            after.declarations.keys - helperAfter.id != before.declarations.keys) return refused(
            snapshot, "kotlin.extractPostImageIdentityMissing",
            "K2 did not prove exact retention of every old identity plus one new bounded helper",
        )
        val targetBodyAfter = targetAfter.bodyRange ?: return refused(
            snapshot, "kotlin.extractPostImageIdentityMissing", "K2 omitted the extracted target call range",
        )
        val stagedSource = staged.files.singleOrNull { it.path.normalize() == normalized } ?: return refused(
            snapshot, "kotlin.extractPostImageIdentityMissing", "Extracted post-image source is absent",
        )
        val targetBodyStart = TextEdits.offsetOf(stagedSource.content, targetBodyAfter.start)
        val targetBodyEnd = TextEdits.offsetOf(stagedSource.content, targetBodyAfter.end)
        val helperCalls = after.usages.filter { usage ->
            if (usage.targetId != helperAfter.id || usage.location.path.normalize() != normalized) return@filter false
            val start = TextEdits.offsetOf(stagedSource.content, usage.location.range.start)
            val end = TextEdits.offsetOf(stagedSource.content, usage.location.range.end)
            start >= targetBodyStart && end <= targetBodyEnd &&
                stagedSource.content.substring(start, end) == methodName
        }
        if (helperCalls.size != 1) return refused(
            snapshot, "kotlin.extractCallBindingChanged",
            "The inserted extraction call does not resolve exactly once to the new private helper",
        )
        if (usageFingerprint(before, snapshot, null) != usageFingerprint(after, staged, helperCalls.single())) {
            return refused(
                snapshot, "kotlin.extractBindingChanged",
                "Extracting changes a non-target compiler-resolved binding",
            )
        }
        return PatchPlan(
            operation = OPERATION,
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.99,
            requiresUserApproval = true,
            summary = "Extract integer expression from '${target.name}' into private top-level '$methodName'.",
            affectedFiles = workspaceEdit.affectedFiles(),
            workspaceEdit = workspaceEdit,
            diagnosticsBefore = before.diagnostics,
            diagnosticsAfterPreview = after.diagnostics,
            warnings = listOf(
                "Bounded extraction accepts only a compiler-PSI-proven zero-input integer expression body.",
            ),
            riskLevel = RiskLevel.LOW,
            evidence = RefactoringEvidence.NATIVE_AST,
        )
    }

    private fun usageFingerprint(
        evidence: Evidence,
        snapshot: ProjectSnapshot,
        excluded: KotlinCompilerResolvedUsage?,
    ): List<String> {
        val sources = snapshot.files.associateBy { it.path.normalize() }
        fun selected(path: Path, range: SourceRange): String {
            val source = sources.getValue(path.normalize())
            return source.content.substring(
                TextEdits.offsetOf(source.content, range.start), TextEdits.offsetOf(source.content, range.end),
            )
        }
        return buildList {
            evidence.usages.filterNot { it == excluded }.forEach {
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

    private fun available(snapshot: ProjectSnapshot, @Suppress("UNUSED_PARAMETER") prefix: String): Evidence? = when (
        val result = kotlin.compilerDiagnostics(snapshot)
    ) {
        is KotlinCompilerDiagnosticsResult.Available -> if (
            result.symbolFailure == null && result.symbols != null &&
            result.diagnostics.none { it.severity == Diagnostic.Severity.ERROR }
        ) Evidence(
            result.symbols, result.declarations, result.usages,
            result.externalTypeUsages, result.externalCallableUsages, result.diagnostics,
        ) else null
        is KotlinCompilerDiagnosticsResult.Refused -> null
        is KotlinCompilerDiagnosticsResult.Error -> null
    }

    private fun refused(
        snapshot: ProjectSnapshot,
        code: String,
        message: String,
        diagnostics: List<Diagnostic> = emptyList(),
    ) = PatchPlan(
        operation = OPERATION,
        status = PatchStatus.REFUSED,
        snapshotHash = snapshot.hash,
        confidence = 0.0,
        requiresUserApproval = false,
        summary = message,
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        diagnosticsAfterPreview = diagnostics,
        warnings = listOf(message),
        riskLevel = RiskLevel.HIGH,
        evidence = RefactoringEvidence.NATIVE_AST,
        refusalCode = code,
    )

    private data class Evidence(
        val symbols: org.refactorkit.core.SymbolIndex,
        val declarations: Map<org.refactorkit.core.SymbolId, KotlinCompilerDeclarationEvidence>,
        val usages: List<KotlinCompilerResolvedUsage>,
        val externalTypes: List<KotlinCompilerExternalTypeUsage>,
        val externalCallables: List<KotlinCompilerExternalCallableUsage>,
        val diagnostics: List<Diagnostic>,
    )

    companion object {
        const val OPERATION = "extractMethod"
        private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]{0,511}")
        private val KEYWORDS = setOf(
            "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
            "interface", "is", "null", "object", "package", "return", "super", "this", "throw", "true",
            "try", "typealias", "typeof", "val", "var", "when", "while",
        )
    }
}
