package org.refactorkit.typescript

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.ExternalWorkspaceEditNormalization
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RefactoringRequest
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.WorkspaceEdit
import java.nio.file.Path

enum class TypeScriptCompilerRefactorKind(val operation: String, val protocolKind: String) {
    EXTRACT_FUNCTION("extractFunction", "refactor.extract.function"),
    EXTRACT_CONSTANT("extractConstant", "refactor.extract.constant"),
    INLINE_VARIABLE("inlineVariable", "refactor.inline.variable"),
    MOVE_DECLARATION("moveDeclaration", "refactor.move.file"),
}

/** Typed selection and explicit action identity, shared unchanged by discovery and edit requests. */
data class TypeScriptCompilerRefactorRequest(
    val kind: TypeScriptCompilerRefactorKind,
    val file: Path,
    val range: SourceRange,
    val refactor: String,
    val action: String,
    val formatting: TypeScriptCompilerFormatting,
    val targetFile: Path? = null,
) {
    // TypeScript 5.9.3 requires this preference even for an existing move target.
    // The normalizer still refuses modifications to files absent from the snapshot.
    val compilerPreferences: String get() = if (targetFile == null) formatting.preferences else
        JsonObject((Json.parseToJsonElement(formatting.preferences) as JsonObject) +
            mapOf("allowTextChangesInNewFiles" to JsonPrimitive(true))).toString()

    fun evidence(): Map<String, String> = formatting.evidence() + mapOf(
        "preferences" to compilerPreferences,
        "file" to file.normalize().toString(), "refactor" to refactor, "action" to action,
        "kind" to kind.protocolKind, "selectionEncoding" to "utf-16-zero-based",
        "startLine" to range.start.line.toString(), "startCharacter" to range.start.character.toString(),
        "endLine" to range.end.line.toString(), "endCharacter" to range.end.character.toString(),
    ) + (targetFile?.let { mapOf("targetFile" to it.normalize().toString()) } ?: emptyMap())

    companion object {
        fun from(request: RefactoringRequest): TypeScriptCompilerRefactorRequest {
            val args = request.arguments
            require("newName" !in args && "methodName" !in args) { "Compiler-generated names are retained; follow-up rename requires separate authority" }
            val selection = request.selection?.location
            val file = args["file"]?.let(Path::of) ?: requireNotNull(selection).path
            val range = if (listOf("startLine", "startCharacter", "endLine", "endCharacter").any(args::containsKey)) {
                SourceRange(SourcePosition(args.getValue("startLine").toInt(), args.getValue("startCharacter").toInt()),
                    SourcePosition(args.getValue("endLine").toInt(), args.getValue("endCharacter").toInt()))
            } else requireNotNull(selection).range
            require(selection == null || (selection.path.normalize() == file.normalize() && selection.range == range)) {
                "Conflicting compiler selections"
            }
            val source = request.snapshot.files.single { it.path.normalize() == file.normalize() }
            require(source.languageId in setOf("typescript", "javascript")) { "Refactor selection is not a TypeScript/JavaScript source" }
            val lines = source.content.split('\n').map { it.removeSuffix("\r") }
            for (position in listOf(range.start, range.end)) {
                require(position.line in lines.indices && position.character in 0..lines[position.line].length) { "Selection is outside source bounds" }
                val line = lines[position.line]
                require(position.character == 0 || position.character == line.length ||
                    !(Character.isHighSurrogate(line[position.character - 1]) && Character.isLowSurrogate(line[position.character]))) {
                    "Selection splits a UTF-16 surrogate pair"
                }
            }
            val kind = TypeScriptCompilerRefactorKind.entries.single { it.operation == request.operation }
            val target = if (kind == TypeScriptCompilerRefactorKind.MOVE_DECLARATION) {
                Path.of(args.getValue("targetFile")).normalize().also { path ->
                    require(path != file.normalize() && request.snapshot.files.any {
                        it.path.normalize() == path && it.languageId in setOf("typescript", "javascript")
                    }) { "Declaration move requires a distinct existing TypeScript/JavaScript target in the snapshot" }
                }
            } else {
                require("targetFile" !in args) { "This compiler action does not accept an interactive target" }
                null
            }
            return TypeScriptCompilerRefactorRequest(
                kind, file.normalize(), range,
                args.getValue("refactor").also { require(it.isNotBlank()) },
                args.getValue("action").also { require(it.isNotBlank()) }, TypeScriptCompilerFormatting.from(args), target,
            )
        }
    }
}

internal class TypeScriptCompilerRefactoringPlanner(
    private val client: TypeScriptSemanticClient,
    private val compilerPreview: TypeScriptCompilerPreview,
) {
    fun preview(request: RefactoringRequest): PatchPlan {
        val selected = runCatching { TypeScriptCompilerRefactorRequest.from(request) }.getOrElse {
            return refused(request.snapshot, request.operation, "typescript.refactorRequestInvalid", it.message ?: "Invalid compiler refactor selection")
        }
        return compilerPreview.preview(request.snapshot, request.operation, "getEditsForRefactor", selected.evidence()) {
            when (val result = client.requestCompilerRefactorEdit(selected, request.snapshot)) {
                is ExternalWorkspaceEditNormalization.Refused -> refused(request.snapshot, request.operation,
                    result.diagnostics.firstOrNull()?.code ?: "typescript.refactorRefused",
                    result.diagnostics.joinToString("; ") { it.message })
                is ExternalWorkspaceEditNormalization.Accepted -> {
                    val edit = result.normalized.workspaceEdit
                    if (edit.edits.isEmpty()) refused(request.snapshot, request.operation, "typescript.refactorNoChange", "Compiler returned no refactor edits")
                    else PatchPlan(
                        operation = request.operation, status = PatchStatus.PREVIEW, snapshotHash = request.snapshot.hash,
                        confidence = 1.0, requiresUserApproval = true,
                        summary = "${selected.refactor} / ${selected.action} in ${selected.file}",
                        affectedFiles = edit.affectedFiles(), workspaceEdit = edit,
                        warnings = listOf("Compiler-generated names are retained; no follow-up rename or external command was executed."),
                        riskLevel = RiskLevel.MEDIUM, evidence = RefactoringEvidence.COMPILER_PROVEN,
                    )
                }
            }
        }
    }

    private fun refused(snapshot: ProjectSnapshot, operation: String, code: String, reason: String) = PatchPlan(
        operation = operation, status = PatchStatus.REFUSED, snapshotHash = snapshot.hash,
        confidence = 0.0, requiresUserApproval = false, summary = reason,
        affectedFiles = emptySet(), workspaceEdit = WorkspaceEdit(),
        diagnosticsAfterPreview = listOf(Diagnostic(reason, Diagnostic.Severity.ERROR, code = code)),
        warnings = listOf(reason), riskLevel = RiskLevel.HIGH,
        evidence = RefactoringEvidence.LANGUAGE_SERVER, refusalCode = code,
    )
}
