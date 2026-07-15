package org.refactorkit.kotlin

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.Symbol
import org.refactorkit.core.SymbolId
import org.refactorkit.core.TextEdit
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.core.owningBuildSourceRoots

/** Requirement-first bootstrap planner; it grants no transport/apply authority by itself. */
class KotlinPrivateDeclarationRenamePlanner(
    private val adapter: KotlinLanguageAdapter,
) {
    fun preview(snapshot: ProjectSnapshot, symbolId: SymbolId, newName: String): PatchPlan {
        if (!IDENTIFIER.matches(newName) || newName in KEYWORDS) return refused(
            snapshot, "kotlin.renameTargetInvalid", "Kotlin rename target is not a safe non-keyword JVM identifier",
        )
        val symbols = when (val result = adapter.compilerSymbols(snapshot)) {
            is KotlinCompilerSymbolsResult.Available -> result
            is KotlinCompilerSymbolsResult.Refused -> return refused(snapshot, result.reason.code ?: "kotlin.renameEvidenceUnavailable", result.reason.message)
            is KotlinCompilerSymbolsResult.Error -> return refused(snapshot, result.failure.code ?: "kotlin.renameEvidenceUnavailable", result.failure.message)
        }
        val target = symbols.index.symbols.singleOrNull { it.id == symbolId }
            ?: return refused(snapshot, "kotlin.renameTargetMissing", "Kotlin rename target is absent from the attested declaration catalogue")
        if (target.kind !in SUPPORTED_KINDS) return refused(
            snapshot, "kotlin.renameKindUnsupported", "Initial Kotlin rename supports compiler-proven types and direct-call functions only",
        )
        if (snapshot.owningBuildSourceRoots(target.location.path).any { it.generated }) return refused(
            snapshot, "kotlin.renameGeneratedSource", "Kotlin rename target belongs to a generated source root",
        )
        if (snapshot.files.any { it.languageId == "java" }) return refused(
            snapshot, "kotlin.renameCrossLanguageIncomplete", "Initial Kotlin rename refuses snapshots containing Java sources",
        )
        if (symbols.declarations[target.id]?.visibility != KotlinDeclarationVisibility.PRIVATE) return refused(
            snapshot, "kotlin.renameVisibilityUnsupported", "Initial Kotlin rename requires an explicitly private declaration",
        )
        if (newName == target.name) return refused(snapshot, "kotlin.renameNoChange", "Kotlin rename target is unchanged")
        if (symbols.index.symbols.any { it.id != target.id && it.name == newName }) return refused(
            snapshot, "kotlin.renameConflict", "Kotlin rename target collides with an existing declaration",
        )
        val kotlinSources = snapshot.files.filter { it.languageId == "kotlin" }
        if (kotlinSources.any { STAR_IMPORT.containsMatchIn(it.content) || ALIAS_IMPORT.containsMatchIn(it.content) || TYPE_ALIAS.containsMatchIn(it.content) }) {
            return refused(snapshot, "kotlin.renameReferenceCompletenessUnavailable", "Kotlin aliases, star imports or type aliases prevent complete initial rename evidence")
        }
        if (target.kind in setOf(Symbol.Kind.FUNCTION, Symbol.Kind.PROPERTY)) {
            val callableReference = Regex("::\\s*${Regex.escape(target.name)}\\b")
            val importedCallable = Regex("(?m)^\\s*import\\s+[^\\n.]+(?:\\.[^\\n.]+)*\\.${Regex.escape(target.name)}\\s*$")
            val unsupportedModifier = if (target.kind == Symbol.Kind.FUNCTION) {
                Regex("\\b(?:operator|infix|override)\\s+fun\\s+${Regex.escape(target.name)}\\b")
            } else Regex("a^")
            if (kotlinSources.any { source -> listOf(callableReference, unsupportedModifier, importedCallable).any { it.containsMatchIn(source.content) } }) {
                return refused(snapshot, "kotlin.renameReferenceCompletenessUnavailable", "Callable references, imports or unsupported callable shapes prevent complete rename evidence")
            }
        }
        val dynamic = Regex("[\\\"'][^\\\"'\\n]*\\b${Regex.escape(target.name)}\\b[^\\\"'\\n]*[\\\"']")
        if (kotlinSources.any { dynamic.containsMatchIn(it.content) }) return refused(
            snapshot, "kotlin.renameDynamicReference", "Quoted or reflective Kotlin name candidates prevent initial managed rename",
        )
        val locations = (listOf(target.location) + symbols.usages.filter { it.targetId == target.id }.map { it.location })
            .distinct().sortedWith(compareBy({ it.path.toString() }, { it.range.start.line }, { it.range.start.character }))
        if (locations.any { location -> snapshot.owningBuildSourceRoots(location.path).any { it.generated } }) return refused(
            snapshot, "kotlin.renameGeneratedSource", "Kotlin rename reference belongs to a generated source root",
        )
        if (locations.isEmpty() || locations.any { location ->
                val source = snapshot.files.singleOrNull { it.path.normalize() == location.path.normalize() } ?: return@any true
                selectedText(source.content, location.range) != target.name
            }) return refused(snapshot, "kotlin.renameRangeInvalid", "Kotlin rename evidence contains an invalid source token")
        val edit = WorkspaceEdit(locations.groupBy { it.path.normalize() }.toSortedMap(compareBy { it.toString() }).map { (path, ranges) ->
            FileEdit.Modify(path, ranges.map { TextEdit(it.range, newName) })
        })
        val before = when (val result = adapter.compilerDiagnostics(snapshot)) {
            is KotlinCompilerDiagnosticsResult.Available -> result.diagnostics
            is KotlinCompilerDiagnosticsResult.Refused -> return refused(snapshot, result.reason.code ?: "kotlin.renameDiagnosticsUnavailable", result.reason.message)
            is KotlinCompilerDiagnosticsResult.Error -> return refused(snapshot, result.failure.code ?: "kotlin.renameDiagnosticsUnavailable", result.failure.message)
        }
        val staged = runCatching { WorkspaceEditSimulator.apply(snapshot, edit) }.getOrElse {
            return refused(snapshot, "kotlin.renamePreviewInvalid", it.message ?: "Kotlin rename preview is invalid")
        }
        val after = when (val result = adapter.compilerDiagnostics(staged)) {
            is KotlinCompilerDiagnosticsResult.Available -> result.diagnostics
            is KotlinCompilerDiagnosticsResult.Refused -> return refused(snapshot, result.reason.code ?: "kotlin.renameDiagnosticsUnavailable", result.reason.message)
            is KotlinCompilerDiagnosticsResult.Error -> return refused(snapshot, result.failure.code ?: "kotlin.renameDiagnosticsUnavailable", result.failure.message)
        }
        val baseline = before.filter { it.severity == Diagnostic.Severity.ERROR }.map(::diagnosticKey).toSet()
        val introduced = after.filter { it.severity == Diagnostic.Severity.ERROR && diagnosticKey(it) !in baseline }
        if (introduced.isNotEmpty()) return refused(
            snapshot, "kotlin.renameDiagnosticsRegression", "Kotlin rename introduces ${introduced.size} compiler error(s)", introduced,
        )
        return PatchPlan(
            operation = "renameSymbol", status = PatchStatus.PREVIEW, snapshotHash = snapshot.hash,
            confidence = 0.9, requiresUserApproval = true,
            summary = "Rename private Kotlin ${target.kind.name.lowercase()} '${target.name}' to '$newName' across ${locations.size} compiler-proven token(s).",
            affectedFiles = edit.affectedFiles(), workspaceEdit = edit,
            diagnosticsBefore = before, diagnosticsAfterPreview = after,
            warnings = listOf("K4 bootstrap row: Kotlin-only private declaration with bounded complete reference evidence."),
            riskLevel = RiskLevel.MEDIUM, evidence = RefactoringEvidence.NATIVE_AST,
        )
    }

    private fun selectedText(content: String, range: org.refactorkit.core.SourceRange): String {
        val lines = content.split('\n')
        if (range.start.line != range.end.line || range.start.line !in lines.indices) return ""
        val line = lines[range.start.line]
        if (range.start.character !in 0..line.length || range.end.character !in 0..line.length) return ""
        return line.substring(range.start.character, range.end.character)
    }

    private fun diagnosticKey(diagnostic: Diagnostic) = listOf(
        diagnostic.code.orEmpty(), diagnostic.location?.path?.toString().orEmpty(),
        diagnostic.location?.range?.toString().orEmpty(), diagnostic.message,
    )

    private fun refused(snapshot: ProjectSnapshot, code: String, message: String, diagnostics: List<Diagnostic> = emptyList()) = PatchPlan(
        operation = "renameSymbol", status = PatchStatus.REFUSED, snapshotHash = snapshot.hash,
        confidence = 0.0, requiresUserApproval = false, summary = message,
        affectedFiles = emptySet(), workspaceEdit = WorkspaceEdit(), diagnosticsAfterPreview = diagnostics,
        warnings = listOf(message), riskLevel = RiskLevel.HIGH, evidence = RefactoringEvidence.NATIVE_AST,
        refusalCode = code,
    )

    companion object {
        private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]{0,511}")
        private val STAR_IMPORT = Regex("(?m)^\\s*import\\s+[^\\n]*\\*\\s*$")
        private val ALIAS_IMPORT = Regex("(?m)^\\s*import\\s+[^\\n]+\\s+as\\s+")
        private val TYPE_ALIAS = Regex("(?m)^\\s*(?:public|private|internal)?\\s*typealias\\s+")
        private val SUPPORTED_KINDS = setOf(
            Symbol.Kind.CLASS, Symbol.Kind.INTERFACE, Symbol.Kind.OBJECT, Symbol.Kind.ENUM,
            Symbol.Kind.ANNOTATION, Symbol.Kind.FUNCTION, Symbol.Kind.PROPERTY, Symbol.Kind.PARAMETER, Symbol.Kind.TYPE_PARAMETER,
        )
        private val KEYWORDS = setOf("class", "object", "interface", "fun", "val", "var", "when", "is", "in", "as", "private", "public", "internal", "protected", "return", "package", "import", "typealias")
    }
}
