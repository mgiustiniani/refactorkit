package org.refactorkit.c

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.refactorkit.core.ExternalFileEditProposal
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.ExternalWorkspaceEditNormalization
import org.refactorkit.core.ExternalWorkspaceEditNormalizer
import org.refactorkit.core.ExternalWorkspaceEditProposal
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.FileEdit
import org.refactorkit.core.OperationAuthorityLease
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditIdentity
import org.refactorkit.core.WorkspaceEditSimulator
import java.nio.file.Path
import java.util.Locale

/**
 * Bounded C function-signature changes for fully prototyped, non-variadic
 * direct-call families.
 *
 * First positive target: rename a parameter, updating the prototype, definition
 * and body references through clangd's semantic rename. Refusals cover variadics,
 * old-style declarations, address-taking/callback families, side-effectful
 * argument reordering and unavailable binary consumers.
 */
class CSignaturePlanner(
    private val toolchain: ClangSemanticToolchain,
    private val processManager: ExternalSemanticProcessManager = ExternalSemanticProcessManager(),
    private val normalizer: ExternalWorkspaceEditNormalizer = ExternalWorkspaceEditNormalizer(),
) : AutoCloseable {
    private val client = ClangdSemanticClient(toolchain, processManager)
    private var started = false
    private var startFailure: String? = null

    fun start(snapshot: ProjectSnapshot) {
        require(!started) { "C signature planner is already started" }
        client.start(snapshot.workspace.root)
        val opened = snapshot.files
            .filter { it.languageId in setOf("c", "cpp", "objective-c") }
            .sortedBy { it.path.toString() }
            .all { client.didOpen(snapshot.workspace.root.resolve(it.path), it.content) }
        if (!opened) {
            close()
            startFailure = "clangd did not open every C source file"
            return
        }
        started = true
    }

    /** Returns the typed start failure, or null when the planner started successfully. */
    fun startFailure(): String? = startFailure

    /** Bind a caller-supplied expression to every captured direct call; no expression is inferred. */
    fun addParameter(snapshot: ProjectSnapshot, file: Path, functionName: String, parameterName: String,
        ordinal: Int, callerExpression: String): PatchPlan {
        if (callerExpression.isBlank() || callerExpression.length > 4096) {
            return addRefused(snapshot, "c.signatureAdd.invalid_argument", "Supply a nonempty explicit caller expression of at most 4096 characters")
        }
        val facts = when (val result = CFunctionFactsService(toolchain, processManager).analyze(snapshot, file, functionName)) {
            is CFunctionFactsResult.Available -> result.facts
            is CFunctionFactsResult.NotFound -> return addRefused(snapshot, "c.signatureAdd.notFound", "Selected function has no captured declaration")
            is CFunctionFactsResult.Unavailable -> return addRefused(snapshot, result.diagnostic.code ?: "c.signatureAdd.factsUnavailable", result.diagnostic.message)
        }
        return addParameter(snapshot, file, functionName, parameterName, ordinal,
            facts.calls.map { CExplicitCallArgument(it.span, callerExpression) })
    }

    /** One explicit expression bound to each captured caller; no C default argument exists. */
    fun addParameter(snapshot: ProjectSnapshot, file: Path, functionName: String, parameterName: String,
        ordinal: Int, arguments: List<CExplicitCallArgument>): PatchPlan {
        val runtimeIdentity = toolchain.provenance.nativeRuntimeIdentity?.takeIf { identity ->
            (toolchain.runtime as? ClangToolchainRuntimeCaptureResult.Captured)?.facts?.identity == identity
        } ?: return addRefused(snapshot, "c.signatureAdd.runtimeUnavailable", "A current resolved native runtime identity is required")
        if (arguments.size > 8192) return addRefused(snapshot, "c.signatureAdd.input_limit", "Caller expression mapping exceeds the supported limit")
        val capturedArguments = arguments.toList()
        val service = CFunctionFactsService(toolchain, processManager)
        val before = when (val result = service.analyze(snapshot, file, functionName)) {
            is CFunctionFactsResult.Available -> result.facts
            is CFunctionFactsResult.NotFound -> return addRefused(snapshot, "c.signatureAdd.notFound", "Selected function has no captured declaration")
            is CFunctionFactsResult.Unavailable -> return addRefused(snapshot, result.diagnostic.code ?: "c.signatureAdd.factsUnavailable", result.diagnostic.message)
        }
        val source = snapshot.files.single()
        val edits = when (val result = CParameterInsertionEdits.render(source.content, before, parameterName, ordinal, capturedArguments)) {
            is CParameterInsertionEditsResult.Accepted -> result.edits
            is CParameterInsertionEditsResult.Refused -> return addRefused(snapshot, "c.signatureAdd.${result.reason.name.lowercase(Locale.ROOT)}", "Parameter insertion refused: ${result.reason}")
        }
        val workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(source.path.normalize(), edits)))
        val candidate = try { WorkspaceEditSimulator.apply(snapshot, workspaceEdit) }
        catch (_: Exception) { return addRefused(snapshot, "c.signatureAdd.previewInvalid", "Parameter edits could not be staged") }
        val after = when (val result = service.analyzePreview(snapshot, candidate, file, functionName)) {
            is CFunctionFactsResult.Available -> result.facts
            is CFunctionFactsResult.NotFound -> return addRefused(snapshot, "c.signatureAdd.previewFamilyMissing", "The staged function family is unavailable")
            is CFunctionFactsResult.Unavailable -> return addRefused(snapshot, result.diagnostic.code ?: "c.signatureAdd.previewUnavailable", result.diagnostic.message)
        }
        when (val validation = CParameterInsertionEdits.validateCandidate(source.content, before, candidate.files.single().content, after,
            parameterName, ordinal, capturedArguments)) {
            CParameterInsertionValidation.Accepted -> Unit
            is CParameterInsertionValidation.Refused -> return addRefused(snapshot, "c.signatureAdd.${validation.reason.name.lowercase(Locale.ROOT)}", "Staged argument evidence refused: ${validation.reason}")
        }
        val expressions = JsonArray(capturedArguments.sortedBy { it.callSpan.start }.map { argument -> buildJsonObject {
            put("start", argument.callSpan.start); put("endExclusive", argument.callSpan.endExclusive); put("expression", argument.expression)
        } }).toString()
        return PatchPlan(
            operation = "addParameter", snapshotHash = snapshot.hash, confidence = 1.0, requiresUserApproval = true,
            summary = "Insert integer parameter '$parameterName' at ordinal $ordinal in '$functionName' and update ${before.calls.size} explicit callers",
            affectedFiles = workspaceEdit.affectedFiles(), workspaceEdit = workspaceEdit,
            warnings = listOf("Captured one-file static integer family; every caller supplies an explicit expression and proven absent argument effects. No C default parameter is created."),
            riskLevel = RiskLevel.MEDIUM, evidence = RefactoringEvidence.COMPILER_PROVEN,
            authorityLease = OperationAuthorityLease(kind = "c.signature-add-v1", operation = "addParameter", snapshotHash = snapshot.hash,
                evidenceHash = candidate.hash, workspaceEditSha256 = WorkspaceEditIdentity.sha256(workspaceEdit),
                attributes = mapOf("c.file" to source.path.normalize().toString(), "c.functionName" to functionName,
                    "c.parameterName" to parameterName, "c.parameterOrdinal" to ordinal.toString(), "c.callArguments" to expressions,
                    "c.resourceDirectoryIdentity" to (toolchain.resources as ClangResourceCaptureResult.Captured).snapshot.identity,
                    "c.nativeRuntimeIdentity" to runtimeIdentity,
                    "c.compilerSha256" to toolchain.provenance.evidence.single { it.role == "clang-executable" }.sha256)),
        )
    }

    private fun addRefused(snapshot: ProjectSnapshot, code: String, message: String) =
        reorderRefused(snapshot, code, message).copy(operation = "addParameter")

    /** Source-only complete original-index permutation; compiler execution is cold and bounded. */
    fun reorderParameters(snapshot: ProjectSnapshot, file: Path, functionName: String, originalIndices: List<Int>): PatchPlan {
        if (originalIndices.size > 64) return reorderRefused(snapshot, "c.signatureReorder.input_limit", "Parameter mapping exceeds the supported limit")
        val runtimeIdentity = toolchain.provenance.nativeRuntimeIdentity?.takeIf { identity ->
            (toolchain.runtime as? ClangToolchainRuntimeCaptureResult.Captured)?.facts?.identity == identity
        } ?: return reorderRefused(snapshot, "c.signatureReorder.runtimeUnavailable", "A current resolved native runtime identity is required")
        val order = originalIndices.toList()
        val factsService = CFunctionFactsService(toolchain, processManager)
        val before = when (val result = factsService.analyze(snapshot, file, functionName)) {
            is CFunctionFactsResult.Available -> result.facts
            is CFunctionFactsResult.NotFound -> return reorderRefused(snapshot, "c.signatureReorder.notFound", "Selected function has no captured declaration")
            is CFunctionFactsResult.Unavailable -> return reorderRefused(snapshot, result.diagnostic.code ?: "c.signatureReorder.factsUnavailable", result.diagnostic.message)
        }
        val source = snapshot.files.single()
        val edits = when (val result = CParameterReorderingEdits.render(source.content, before, order)) {
            is CParameterReorderingEditsResult.Accepted -> result.edits
            is CParameterReorderingEditsResult.Refused -> {
                val reason = result.reason
                val code = if (reason is CParameterReorderingEditError.PolicyDenied) reason.reason.name.lowercase(Locale.ROOT) else when (reason) {
                    CParameterReorderingEditError.SourceChanged -> "source_changed"
                    CParameterReorderingEditError.InvalidEncoding -> "invalid_encoding"
                    CParameterReorderingEditError.InvalidFacts -> "invalid_facts"
                    CParameterReorderingEditError.InvalidRange -> "invalid_range"
                    CParameterReorderingEditError.OverlappingRanges -> "overlapping_ranges"
                    CParameterReorderingEditError.InputLimit -> "input_limit"
                    CParameterReorderingEditError.EditLimit -> "edit_limit"
                    is CParameterReorderingEditError.PolicyDenied -> error("Policy refusal is handled above")
                }
                return reorderRefused(snapshot, "c.signatureReorder.$code", "Parameter reordering refused: $reason")
            }
        }
        val workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(source.path.normalize(), edits)))
        val candidate = try { WorkspaceEditSimulator.apply(snapshot, workspaceEdit) }
        catch (_: Exception) { return reorderRefused(snapshot, "c.signatureReorder.previewInvalid", "Parameter edits could not be staged") }
        when (val result = factsService.analyzePreview(snapshot, candidate, file, functionName)) {
            is CFunctionFactsResult.Available -> Unit
            is CFunctionFactsResult.NotFound -> return reorderRefused(snapshot, "c.signatureReorder.previewFamilyMissing", "The staged function family is unavailable")
            is CFunctionFactsResult.Unavailable -> return reorderRefused(snapshot, result.diagnostic.code ?: "c.signatureReorder.previewUnavailable", result.diagnostic.message)
        }
        return PatchPlan(
            operation = "reorderParameters", snapshotHash = snapshot.hash, confidence = 1.0,
            requiresUserApproval = true, summary = "Reorder parameters of '$functionName' in ${source.path} and update ${before.calls.size} direct calls",
            affectedFiles = workspaceEdit.affectedFiles(), workspaceEdit = workspaceEdit,
            warnings = listOf("Captured one-file static integer family; external, indirect and observable-argument consumers are refused."),
            riskLevel = RiskLevel.MEDIUM, evidence = RefactoringEvidence.COMPILER_PROVEN,
            authorityLease = OperationAuthorityLease(
                kind = "c.signature-reorder-v1", operation = "reorderParameters", snapshotHash = snapshot.hash,
                evidenceHash = candidate.hash, workspaceEditSha256 = WorkspaceEditIdentity.sha256(workspaceEdit),
                attributes = mapOf("c.file" to source.path.normalize().toString(), "c.functionName" to functionName,
                    "c.originalIndices" to order.joinToString(","),
                    "c.nativeRuntimeIdentity" to runtimeIdentity,
                    "c.resourceDirectoryIdentity" to (toolchain.resources as ClangResourceCaptureResult.Captured).snapshot.identity,
                    "c.compilerSha256" to toolchain.provenance.evidence.single { it.role == "clang-executable" }.sha256),
            ),
        )
    }

    private fun reorderRefused(snapshot: ProjectSnapshot, code: String, message: String) = PatchPlan(
        operation = "reorderParameters", status = PatchStatus.REFUSED, snapshotHash = snapshot.hash,
        confidence = 0.0, requiresUserApproval = false, summary = message,
        affectedFiles = emptySet(), workspaceEdit = WorkspaceEdit(), refusalCode = code,
        diagnosticsAfterPreview = listOf(Diagnostic(message, Diagnostic.Severity.ERROR, code = code,
            evidence = DiagnosticEvidence.COMPILER, category = DiagnosticCategory.SAFETY)),
        riskLevel = RiskLevel.HIGH, evidence = RefactoringEvidence.COMPILER_PROVEN,
    )

    /**
     * Renames one compiler-selected parameter in a complete, file-static C function family.
     * The caller must identify the function; spelling-only workspace scans are never used to
     * choose a rename target.
    */
    fun renameParameter(snapshot: ProjectSnapshot, file: Path, functionName: String, oldParam: String, newParam: String): PatchPlan {
        if (!started) return refused(snapshot, startFailure ?: "C signature planner is not started", "c.signatureRename.plannerUnavailable")
        val runtimeIdentity = toolchain.provenance.nativeRuntimeIdentity?.takeIf { identity ->
            (toolchain.runtime as? ClangToolchainRuntimeCaptureResult.Captured)?.facts?.identity == identity
        } ?: return refused(snapshot, "A current resolved native runtime identity is required", "c.signatureRename.runtimeUnavailable")
        val identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")
        if (!identifier.matches(functionName) || !identifier.matches(oldParam) || !identifier.matches(newParam) || oldParam == newParam) {
            return refused(snapshot, "Function and parameter rename identifiers must be distinct C identifiers", "c.signatureRename.invalidMapping")
        }
        val source = snapshot.files.singleOrNull { it.path.normalize() == file.normalize() }
            ?: return refused(snapshot, "File '$file' is not a single captured C source", "c.signatureRename.sourceUnavailable")
        val factsService = CFunctionFactsService(toolchain, processManager)
        val before = when (val result = factsService.analyze(snapshot, file, functionName)) {
            is CFunctionFactsResult.Available -> result
            is CFunctionFactsResult.NotFound -> return refused(snapshot, "Compiler facts did not find function '$functionName'", "c.signatureRename.functionNotFound")
            is CFunctionFactsResult.Unavailable -> return refused(snapshot, result.diagnostic.message,
                result.diagnostic.code ?: "c.signatureRename.factsUnavailable")
        }
        val selectedPath = (if (file.isAbsolute) file else snapshot.workspace.root.resolve(file)).toAbsolutePath().normalize()
        if (before.snapshotHash != snapshot.hash || before.facts.sourcePath.toAbsolutePath().normalize() != selectedPath) {
            return refused(snapshot, "Compiler facts do not belong to the selected snapshot and file", "c.signatureRename.staleFacts")
        }
        val family = before.facts
        if (family.family != CDirectCallFamilyEvidence.COMPLETE_FILE_STATIC) {
            return refused(snapshot,
                "Function '$functionName' has address-taking, incomplete consumers, or unproven external consumers",
                "c.signatureRename.familyUnavailable")
        }
        val arities = family.declarations.map { it.parameters.size }.toSet()
        if (family.declarations.isEmpty() || family.declarations.none { it.isDefinition } || arities.size != 1 ||
            family.declarations.any { it.parameterClause == CFunctionParameterClauseEvidence.Unavailable }) {
            return refused(snapshot, "Function '$functionName' has no complete prototyped declaration family", "c.signatureRename.incompleteFamily")
        }
        val matches = family.declarations.flatMapIndexed { declarationIndex, declaration ->
            declaration.parameters.mapIndexedNotNull { ordinal, parameter ->
                if (parameter.name == oldParam) Triple(declarationIndex, ordinal, parameter) else null
            }
        }
        if (matches.isEmpty()) {
            return refused(snapshot, "Parameter '$oldParam' was not found in compiler-captured function '$functionName'", "c.signatureRename.parameterNotFound")
        }
        val ordinals = matches.map { it.second }.distinct()
        if (ordinals.size != 1) {
            return refused(snapshot, "Parameter '$oldParam' maps to multiple positions in function '$functionName'", "c.signatureRename.parameterAmbiguous")
        }
        val ordinal = ordinals.single()
        val definitions = family.declarations.filter { it.isDefinition }
        val definitionScopes = definitions.mapNotNull { it.parameterScope as? CFunctionParameterScopeEvidence.Available }
        if (definitionScopes.size != definitions.size) {
            return refused(snapshot, "Compiler did not capture the parameter scope for '$functionName'", "c.signatureRename.scopeUnavailable")
        }
        if (definitionScopes.any { newParam in it.ordinaryNames }) {
            return refused(snapshot, "Parameter name '$newParam' conflicts with an existing name in '$functionName'", "c.signatureRename.nameConflict")
        }
        val selected = matches.firstOrNull { family.declarations[it.first].isDefinition } ?: matches.first()
        val parameterSpan = selected.third.span
        val sourceBytes = source.content.toByteArray(Charsets.UTF_8)
        val oldBytes = oldParam.toByteArray(Charsets.UTF_8)
        val nameStart = parameterSpan.endExclusive - oldBytes.size
        if (parameterSpan.start < 0 || parameterSpan.endExclusive > sourceBytes.size || nameStart < parameterSpan.start ||
            !sourceBytes.copyOfRange(nameStart, parameterSpan.endExclusive).contentEquals(oldBytes) ||
            (nameStart > 0 && isIdentifierByte(sourceBytes[nameStart - 1])) ||
            (parameterSpan.endExclusive < sourceBytes.size && isIdentifierByte(sourceBytes[parameterSpan.endExclusive]))) {
            return refused(snapshot, "Compiler parameter range does not identify the selected declarator", "c.signatureRename.rangeInvalid")
        }
        val position = sourcePosition(source.content, nameStart)
            ?: return refused(snapshot, "Compiler parameter range is not a valid UTF-8 source boundary", "c.signatureRename.rangeInvalid")
        val result = client.rename(file.normalize(), position.line, position.character, newParam)
        return when (result) {
            is CRenameResult.Found -> {
                if (result.edits.isEmpty()) return refused(snapshot, "clangd returned no edits for '$functionName.$oldParam'", "c.signatureRename.emptyEdit")
                val semanticEdits = result.edits.map { edit ->
                    (if (edit.file.isAbsolute) edit.file else snapshot.workspace.root.resolve(edit.file)).toAbsolutePath().normalize() to TextEdit(
                        SourceRange(SourcePosition(edit.startLine, edit.startCharacter), SourcePosition(edit.endLine, edit.endCharacter)),
                        edit.newText,
                    )
                }
                val declarationEdits = mutableListOf<Pair<Path, TextEdit>>()
                for (declaration in family.declarations) {
                    val parameter = declaration.parameters.getOrNull(ordinal)
                        ?: return refused(snapshot, "Compiler declaration has no parameter at position $ordinal", "c.signatureRename.incompleteFamily")
                    if (parameter.name == newParam) continue
                    val range = parameterNameRange(source.content, parameter.span, parameter.name)
                        ?: return refused(snapshot, "Compiler parameter range does not identify a complete declarator", "c.signatureRename.rangeInvalid")
                    val matchingSemanticEdit = semanticEdits.any { (path, edit) ->
                        path == selectedPath && edit.range == range && edit.newText == newParam
                    }
                    if (!matchingSemanticEdit) {
                        val overlap = semanticEdits.any { (path, edit) -> path == selectedPath && edit.range.overlaps(range) }
                        if (overlap) return refused(snapshot, "clangd edits overlap compiler-owned declaration ranges", "c.signatureRename.overlappingEdits")
                        declarationEdits += selectedPath to TextEdit(range, newParam)
                    }
                }
                val proposal = ExternalWorkspaceEditProposal(
                    providerId = "clangd-rename-parameter-v1",
                    providerVersion = toolchain.provenance.clangdVersion,
                    edits = (semanticEdits + declarationEdits).groupBy { it.first }.map { (path, edits) ->
                        ExternalFileEditProposal.Modify(path, edits.map { it.second })
                    },
                )
                val normalization = normalizer.normalize(snapshot, proposal)
                when (normalization) {
                    is ExternalWorkspaceEditNormalization.Accepted -> {
                        val workspaceEdit = normalization.normalized.workspaceEdit
                        val candidate = try { WorkspaceEditSimulator.apply(snapshot, workspaceEdit) }
                        catch (_: Exception) { return refused(snapshot, "Compiler-selected rename edits could not be staged", "c.signatureRename.previewInvalid") }
                        val after = when (val checked = factsService.analyzePreview(snapshot, candidate, file, functionName)) {
                            is CFunctionFactsResult.Available -> checked.facts
                            is CFunctionFactsResult.NotFound -> return refused(snapshot, "Staged rename lost function '$functionName'", "c.signatureRename.previewFamilyMissing")
                            is CFunctionFactsResult.Unavailable -> return refused(snapshot, checked.diagnostic.message,
                                checked.diagnostic.code ?: "c.signatureRename.previewUnavailable")
                        }
                        if (after.family != CDirectCallFamilyEvidence.COMPLETE_FILE_STATIC) {
                            return refused(snapshot, "Staged rename changed the family classification to ${after.family}", "c.signatureRename.previewMismatch")
                        }
                        val stagedNames = after.declarations.map { it.parameters.getOrNull(ordinal)?.name }
                        if (stagedNames.any { it != newParam }) {
                            return refused(snapshot, "Staged parameter position $ordinal resolves to $stagedNames instead of '$newParam'", "c.signatureRename.previewMismatch")
                        }
                        PatchPlan(
                            operation = "changeSignature",
                            status = PatchStatus.PREVIEW,
                            snapshotHash = snapshot.hash,
                            confidence = 1.0,
                            requiresUserApproval = true,
                            summary = "Rename parameter '$oldParam' to '$newParam' of '$functionName' in $file",
                            affectedFiles = workspaceEdit.affectedFiles(),
                            workspaceEdit = workspaceEdit,
                            diagnosticsBefore = emptyList(),
                            diagnosticsAfterPreview = emptyList(),
                            warnings = listOf("Compiler facts selected one complete file-static function family; clangd renamed the parameter binding and call sites remain positional."),
                            riskLevel = RiskLevel.MEDIUM,
                            evidence = RefactoringEvidence.COMPILER_PROVEN,
                            authorityLease = OperationAuthorityLease(
                                kind = "c.signature-rename-v1", operation = "changeSignature", snapshotHash = snapshot.hash,
                                evidenceHash = candidate.hash, workspaceEditSha256 = WorkspaceEditIdentity.sha256(workspaceEdit),
                                attributes = mapOf(
                                    "c.file" to source.path.normalize().toString(),
                                    "c.functionName" to functionName,
                                    "c.oldParameter" to oldParam,
                                    "c.newParameter" to newParam,
                                    "c.parameterOrdinal" to ordinal.toString(),
                                    "c.nativeRuntimeIdentity" to runtimeIdentity,
                                    "c.resourceDirectoryIdentity" to (toolchain.resources as ClangResourceCaptureResult.Captured).snapshot.identity,
                                    "c.compilerSha256" to toolchain.provenance.evidence.single { it.role == "clang-executable" }.sha256,
                                ),
                            ),
                        )
                    }
                    is ExternalWorkspaceEditNormalization.Refused -> refused(snapshot, normalization.diagnostics.joinToString("; ") { it.message },
                        "c.signatureRename.normalizationRefused")
                }
            }
            is CRenameResult.NotFound -> refused(snapshot, "clangd did not find parameter '$oldParam' in '$functionName'", "c.signatureRename.parameterNotFound")
            is CRenameResult.Refused -> refused(snapshot, result.diagnostics.firstOrNull()?.message ?: "clangd refused renaming '$oldParam'",
                "c.signatureRename.semanticRenameRefused")
        }
    }

    /**
     * Legacy source-compatible preview route. New public protocol requests must use the
     * function-selected overload; this route accepts only one lexical signature candidate
     * and still delegates references to clangd's binding-aware rename.
     */
    @Deprecated("Use the overload that takes functionName so compiler facts can bind the family")
    fun renameParameter(snapshot: ProjectSnapshot, file: Path, oldParam: String, newParam: String): PatchPlan {
        if (!started) return refused(snapshot, startFailure ?: "C signature planner is not started")
        if (oldParam.isBlank() || newParam.isBlank() || oldParam == newParam) return refused(snapshot, "Parameter rename mapping is invalid")
        val analysis = analyzeSignature(snapshot, file, oldParam)
        if (analysis is SignatureAnalysis.Refused) return refused(snapshot, analysis.message)
        analysis as SignatureAnalysis.Found
        val path = file.normalize()
        val result = client.rename(path, analysis.line, analysis.character, newParam)
        return when (result) {
            is CRenameResult.Found -> {
                val proposal = ExternalWorkspaceEditProposal(
                    providerId = "clangd-legacy-rename-parameter-v1",
                    providerVersion = toolchain.provenance.clangdVersion,
                    edits = result.edits.groupBy { it.file.normalize() }.map { (editPath, edits) ->
                        ExternalFileEditProposal.Modify(editPath, edits.map { edit ->
                            TextEdit(SourceRange(SourcePosition(edit.startLine, edit.startCharacter), SourcePosition(edit.endLine, edit.endCharacter)), edit.newText)
                        })
                    },
                )
                when (val normalized = normalizer.normalize(snapshot, proposal)) {
                    is ExternalWorkspaceEditNormalization.Accepted -> PatchPlan(
                        operation = "changeSignature", snapshotHash = snapshot.hash, confidence = 1.0,
                        requiresUserApproval = true, summary = "Rename parameter '$oldParam' to '$newParam' in $file",
                        affectedFiles = normalized.normalized.workspaceEdit.affectedFiles(),
                        workspaceEdit = normalized.normalized.workspaceEdit,
                        warnings = listOf("Legacy unique-signature preview; use an explicit function symbol for compiler-family admission."),
                        riskLevel = RiskLevel.MEDIUM, evidence = RefactoringEvidence.COMPILER_PROVEN,
                    )
                    is ExternalWorkspaceEditNormalization.Refused -> refused(snapshot,
                        normalized.diagnostics.joinToString("; ") { it.message })
                }
            }
            is CRenameResult.NotFound -> refused(snapshot, "Parameter '$oldParam' was not found by clangd")
            is CRenameResult.Refused -> refused(snapshot, result.diagnostics.firstOrNull()?.message ?: "clangd refused renaming '$oldParam'")
        }
    }

    override fun close() {
        client.close()
        started = false
    }

    /**
     * Validates a parameter rename against the function signature in [file] and
     * locates the parameter inside the parameter list, not the first same-name
     * identifier in the body.
     *
     * Refusals cover variadic and old-style (K&R) declarations, which cannot be
     * renamed safely, and a parameter that is absent from the signature.
     */
    internal fun analyzeSignature(snapshot: ProjectSnapshot, file: Path, paramName: String): SignatureAnalysis {
        val source = snapshot.files.singleOrNull { it.path.normalize() == file.normalize() }
            ?: return SignatureAnalysis.Refused("File '$file' is not part of the snapshot")
        val text = source.content
        val lines = text.lines()
        // Collect every candidate signature line that declares paramName as a parameter.
        // A single unambiguous signature is required; silently renaming the first match
        // could target the wrong function when the same parameter name recurs.
        val candidates = mutableListOf<SignatureAnalysis>()
        for (lineIndex in lines.indices) {
            val line = lines[lineIndex]
            val open = line.indexOf('(')
            if (open < 0) continue
            val close = line.indexOf(')', open)
            if (close < 0) continue
            val region = line.substring(open + 1, close)
            val segments = topLevelParamSegments(region)
            if (segments.isEmpty()) continue
            val segTexts = segments.map { region.substring(it).trim() }
            // The parameter name is the declarator token: the last whitespace-separated
            // token of the segment, with pointer/type qualifiers stripped.
            val names = segTexts.map { it.split(' ').lastOrNull()?.replace("*", "")?.trim() ?: "" }
            val paramIndex = names.indexOf(paramName)
            if (paramIndex < 0) continue
            val isVariadic = segTexts.any { it == "..." || it.startsWith("...") }
            val isOldStyle = segTexts.any { !it.startsWith("...") && it.split(' ').size < 2 }
            // Seed at the parameter's own declarator position within its segment, not the
            // first substring of paramName in the line: an earlier parameter whose type or
            // name contains paramName (e.g. 'ext' before 'x') would otherwise bind clangd
            // to the wrong declarator and rename the wrong parameter.
            val seg = segments[paramIndex]
            val localIdx = region.substring(seg).lastIndexOf(paramName)
            if (localIdx < 0) continue
            val char = open + 1 + seg.first + localIdx
            candidates += when {
                isVariadic -> SignatureAnalysis.Refused("Variadic function signature cannot be safely renamed")
                isOldStyle -> SignatureAnalysis.Refused("Old-style (K&R) signature cannot be safely renamed")
                else -> SignatureAnalysis.Found(lineIndex, char, paramIndex, segments.size)
            }
        }
        if (candidates.isEmpty()) {
            return SignatureAnalysis.Refused("Parameter '$paramName' was not found in the function signature")
        }
        if (candidates.size > 1) {
            return SignatureAnalysis.Refused(
                "Parameter '$paramName' appears in ${candidates.size} function signatures; the target is ambiguous",
            )
        }
        return candidates.single()
    }

    internal sealed interface SignatureAnalysis {
        data class Found(val line: Int, val character: Int, val paramIndex: Int, val paramCount: Int) : SignatureAnalysis
        data class Refused(val message: String) : SignatureAnalysis
    }

    /**
     * Splits a parameter region into top-level segments (respecting nested () and [])
     * and drops blank segments, returning their ranges within [region].
     */
    private fun topLevelParamSegments(region: String): List<IntRange> {
        val segments = mutableListOf<IntRange>()
        var depth = 0
        var start = 0
        for (k in region.indices) {
            when (region[k]) {
                '(', '[' -> depth++
                ']', ')' -> depth--
                ',' -> if (depth == 0) {
                    if (region.substring(start, k).isNotBlank()) segments += start until k
                    start = k + 1
                }
            }
        }
        if (region.substring(start, region.length).isNotBlank()) segments += start until region.length
        return segments
    }

    private fun isIdentifierByte(value: Byte): Boolean {
        val char = value.toInt().toChar()
        return char == '_' || char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9'
    }

    private fun parameterNameRange(source: String, span: CFunctionByteSpan, name: String): SourceRange? {
        val bytes = source.toByteArray(Charsets.UTF_8)
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val start = span.endExclusive - nameBytes.size
        if (span.start < 0 || span.endExclusive > bytes.size || start < span.start ||
            !bytes.copyOfRange(start, span.endExclusive).contentEquals(nameBytes) ||
            (start > 0 && isIdentifierByte(bytes[start - 1])) ||
            (span.endExclusive < bytes.size && isIdentifierByte(bytes[span.endExclusive]))) return null
        val startPosition = sourcePosition(source, start) ?: return null
        val endPosition = sourcePosition(source, span.endExclusive) ?: return null
        return SourceRange(startPosition, endPosition)
    }

    private fun sourcePosition(source: String, targetByteOffset: Int): SourcePosition? {
        if (targetByteOffset < 0) return null
        var charOffset = 0
        var byteOffset = 0
        var line = 0
        var column = 0
        while (byteOffset < targetByteOffset && charOffset < source.length) {
            val point = source.codePointAt(charOffset)
            val byteLength = when { point <= 0x7f -> 1; point <= 0x7ff -> 2; point <= 0xffff -> 3; else -> 4 }
            if (byteOffset + byteLength > targetByteOffset) return null
            byteOffset += byteLength
            charOffset += Character.charCount(point)
            if (point == '\n'.code) { line++; column = 0 } else column += Character.charCount(point)
        }
        return if (byteOffset == targetByteOffset) SourcePosition(line, column) else null
    }

    private fun refused(snapshot: ProjectSnapshot, message: String, code: String? = null) = PatchPlan(
        operation = "changeSignature",
        status = PatchStatus.REFUSED,
        snapshotHash = snapshot.hash,
        confidence = 0.0,
        requiresUserApproval = false,
        summary = message,
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        refusalCode = code,
        diagnosticsBefore = emptyList(),
        diagnosticsAfterPreview = emptyList(),
        warnings = listOf(message),
        riskLevel = RiskLevel.HIGH,
        evidence = RefactoringEvidence.STRUCTURAL,
    )
}
