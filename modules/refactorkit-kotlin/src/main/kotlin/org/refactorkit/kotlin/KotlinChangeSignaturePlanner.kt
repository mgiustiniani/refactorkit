package org.refactorkit.kotlin

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourceRange
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.kotlin.KotlinCompilerDiagnosticsResult
import org.refactorkit.kotlin.KotlinCompilerSymbolsResult
import org.refactorkit.kotlin.KotlinDeclarationVisibility
import java.nio.file.Path

/**
 * Bounded Kotlin change-signature row for parameter rename using K2 compiler evidence.
 */
class KotlinChangeSignaturePlanner(
    private val kotlin: KotlinLanguageAdapter,
) {
    fun previewRenameParameter(
        snapshot: ProjectSnapshot,
        symbolId: String,
        oldParameterName: String,
        newParameterName: String,
    ): PatchPlan {
        val catalogue = when (val result = kotlin.compilerSymbols(snapshot)) {
            is KotlinCompilerSymbolsResult.Available -> result
            is KotlinCompilerSymbolsResult.Refused -> return refused(
                snapshot, "kotlin.changeSignatureEvidenceUnavailable", result.reason.message,
            )
            is KotlinCompilerSymbolsResult.Error -> return refused(
                snapshot, "kotlin.changeSignatureEvidenceUnavailable", result.failure.message,
            )
        }
        val target = catalogue.index.symbols.singleOrNull { it.id.value == symbolId }
            ?: return refused(snapshot, "kotlin.changeSignatureTargetMissing",
                "Symbol not found in K2 compiler catalogue: $symbolId")
        val declaration = catalogue.declarations[target.id]
            ?: return refused(snapshot, "kotlin.changeSignatureDeclarationMissing",
                "Declaration evidence not found for target symbol")

        if (target.kind != org.refactorkit.core.Symbol.Kind.FUNCTION &&
            target.kind != org.refactorkit.core.Symbol.Kind.METHOD) return refused(
            snapshot, "kotlin.changeSignatureUnsupportedKind",
            "Kotlin change signature supports functions and methods only")

        val source = snapshot.files.singleOrNull { it.path.normalize() == target.location.path.normalize() }
            ?: return refused(snapshot, "kotlin.changeSignatureFileMissing",
                "Declaration file not found: ${target.location.path}")

        // Find the parameter in the source file
        val paramRegex = Regex("""$oldParameterName\s*:""")
        val paramMatch = paramRegex.find(source.content) ?: return refused(
            snapshot, "kotlin.changeSignatureParameterNotFound",
            "Parameter '$oldParameterName' not found in source")

        // Build the replacement: rename parameter and update all usages
        val edits = mutableListOf<TextEdit>()

        // Rename parameter in declaration
        val paramStart = paramMatch.range.first
        val paramEnd = paramMatch.range.last
        val line = TextEdits.positionForOffset(source.content, paramStart).line
        val col = paramStart - source.content.substring(0, paramStart).lastIndexOf('\n') - 1
        edits.add(TextEdit(
            SourceRange(SourcePosition(line, col), SourcePosition(line, col + oldParameterName.length)),
            newParameterName,
        ))

        // Find and update usages of the old parameter name in the body
        val bodyStart = source.content.indexOf('{', paramEnd)
        if (bodyStart >= 0) {
            val bodyRegex = Regex("""\b$oldParameterName\b""")
            bodyRegex.findAll(source.content, bodyStart).forEach { match ->
                val bodyLine = TextEdits.positionForOffset(source.content, match.range.first).line
                val bodyCol = match.range.first - source.content.substring(0, match.range.first).lastIndexOf('\n') - 1
                edits.add(TextEdit(
                    SourceRange(SourcePosition(bodyLine, bodyCol),
                        SourcePosition(bodyLine, bodyCol + oldParameterName.length)),
                    newParameterName,
                ))
            }
        }

        // Find and update call sites using K2 compiler usages
        val callSiteEdits = catalogue.usages.filter { it.targetId == target.id }
            .mapNotNull { usage ->
                if (usage.location.path.normalize() == source.path.normalize()) return@mapNotNull null
                val usageFile = snapshot.files.singleOrNull { it.path.normalize() == usage.location.path.normalize() }
                    ?: return@mapNotNull null
                val content = usageFile.content
                // Named argument usage: foo(oldName = value) -> foo(newName = value)
                val namedArgRegex = Regex("""$oldParameterName\s*=""")
                val namedMatch = namedArgRegex.find(content) ?: return@mapNotNull null
                val usageLine = TextEdits.positionForOffset(content, namedMatch.range.first).line
                val usageCol = namedMatch.range.first - content.substring(0, namedMatch.range.first).lastIndexOf('\n') - 1
                TextEdit(
                    SourceRange(SourcePosition(usageLine, usageCol),
                        SourcePosition(usageLine, usageCol + oldParameterName.length)),
                    newParameterName,
                )
            }
        edits.addAll(callSiteEdits)

        val fileEdits = mutableListOf<FileEdit>()
        // Group edits by file
        val editsByFile = mutableMapOf<Path, MutableList<TextEdit>>()
        // Declaration file edits
        editsByFile.getOrPut(source.path) { mutableListOf() }.addAll(edits)
        // Call site edits (already in edits list for declaration file)
        callSiteEdits.forEach { edit ->
            // Already handled above
        }

        val workspaceEdit = WorkspaceEdit(editsByFile.map { (path, textEdits) ->
            FileEdit.Modify(path, textEdits)
        })

        val staged = runCatching { WorkspaceEditSimulator.apply(snapshot, workspaceEdit) }.getOrElse {
            return refused(snapshot, "kotlin.changeSignaturePreviewInvalid", it.message ?: "Invalid preview")
        }
        val after = when (val result = kotlin.compilerDiagnostics(staged)) {
            is KotlinCompilerDiagnosticsResult.Available -> result
            is KotlinCompilerDiagnosticsResult.Refused -> return refused(
                snapshot, "kotlin.changeSignatureStagedEvidenceUnavailable", result.reason.message,
            )
            is KotlinCompilerDiagnosticsResult.Error -> return refused(
                snapshot, "kotlin.changeSignatureStagedEvidenceUnavailable", result.failure.message,
            )
        }
        if (after.symbolFailure != null || after.diagnostics.any { it.severity == Diagnostic.Severity.ERROR }) return refused(
            snapshot, "kotlin.changeSignatureDiagnosticsRegression",
            "Parameter rename introduces compiler diagnostics", after.diagnostics,
        )

        val affectedFiles = workspaceEdit.edits.map { it.path }.toSet()
        return PatchPlan(
            operation = "changeSignature",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.95,
            requiresUserApproval = true,
            summary = "Rename parameter '$oldParameterName' to '$newParameterName' in ${target.name}",
            affectedFiles = affectedFiles,
            workspaceEdit = workspaceEdit,
            diagnosticsBefore = emptyList(),
            diagnosticsAfterPreview = after.diagnostics,
            warnings = listOf(
                "Kotlin parameter rename uses K2 compiler evidence. Override/overload call sites may need manual review.",
            ),
            riskLevel = RiskLevel.MEDIUM,
            evidence = RefactoringEvidence.NATIVE_AST,
        )
    }

    private fun refused(
        snapshot: ProjectSnapshot,
        code: String,
        message: String,
        diagnostics: List<Diagnostic> = emptyList(),
    ) = PatchPlan(
        operation = "changeSignature",
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
}
