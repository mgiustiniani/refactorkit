package org.refactorkit.c

import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.refactorkit.core.WorkspaceEdit
import java.nio.file.Path

/**
 * Library-level C integration facade. Composes the bounded C planners and
 * exposes preview/apply through the core `PatchEngine`, preserving the local
 * safety gates (preview, diagnostics, approval, rollback).
 */
class CRefactoringFacade(
    private val toolchain: ClangSemanticToolchain,
    private val processManager: ExternalSemanticProcessManager = ExternalSemanticProcessManager(),
) : AutoCloseable {
    private val rename = CRenamePlanner(toolchain, processManager)
    private val renamePrefix = CRenamePrefixPlanner(toolchain, processManager)
    private val move = CMovePlanner()
    private val format = CFormatter(toolchain)
    private val organizeIncludes = COrganizeIncludesPlanner()
    private val safeDelete = CSafeDeletePlanner(toolchain, processManager)
    private val signature = CSignaturePlanner(toolchain, processManager)
    private val extract = CExtractPlanner()
    private val inline = CInlinePlanner()
    private val relocate = CRelocateComponentPlanner()
    private val compilerDiagnostics = CCompilerDiagnostics(toolchain, processManager)

    /**
     * Exact-version compiler diagnostics gate required by PatchEngine for managed C apply.
     *
     * Never reports an unavailable compiler context as clean: an unavailable or
     * failing clang analysis is surfaced as an explicit error diagnostic, so the
     * central managed-apply gate refuses instead of silently approving.
     */
    fun diagnosticsGate(): DiagnosticsGate = DiagnosticsGate.enabled("clang-exact-v1") { candidate ->
        val sources = candidate.files.filter { it.languageId in setOf("c", "cpp", "objective-c") }
        require(sources.isNotEmpty()) { "clang.diagnosticsSourcesEmpty: no C-family sources in the candidate snapshot" }
        val diagnostics = sources.flatMap { source ->
            when (val result = compilerDiagnostics.analyze(candidate, source.path)) {
                is CDiagnosticsResult.Available -> result.diagnostics
                is CDiagnosticsResult.Unavailable ->
                    error("${result.diagnostic.code}: ${result.diagnostic.message}")
            }
        }
        diagnostics
    }

    /**
     * Starts only the clangd-backed C planners required by [operation].
     *
     * Operations that do not consult clangd (move, format, organize includes,
     * extract, inline, relocate) never launch a semantic process. If a start
     * fails part-way, already-started planners are closed so no process leaks.
     */
    fun startFor(snapshot: ProjectSnapshot, operation: String) {
        val starters: List<Pair<String, (ProjectSnapshot) -> Unit>> = when (operation) {
            "renameSymbol" -> listOf("rename" to rename::start)
            "renamePrefix" -> listOf("renamePrefix" to renamePrefix::start)
            "safeDelete" -> listOf("safeDelete" to safeDelete::start)
            "changeSignature" -> listOf("signature" to signature::start)
            else -> emptyList()
        }
        var started = 0
        try {
            starters.forEach { (_, start) -> start(snapshot); started++ }
        } catch (failure: Throwable) {
            closeStarted(starters.take(started).map { it.first })
            throw failure
        }
    }

    /** Starts every clangd-backed C planner against the snapshot, closing them all on failure. */
    fun start(snapshot: ProjectSnapshot) {
        val starters = listOf(
            "rename" to rename::start,
            "renamePrefix" to renamePrefix::start,
            "safeDelete" to safeDelete::start,
            "signature" to signature::start,
        )
        var started = 0
        try {
            starters.forEach { (_, start) -> start(snapshot); started++ }
        } catch (failure: Throwable) {
            closeStarted(starters.take(started).map { it.first })
            throw failure
        }
    }

    private fun closeStarted(names: List<String>) {
        names.forEach { name ->
            runCatching {
                when (name) {
                    "rename" -> rename.close()
                    "renamePrefix" -> renamePrefix.close()
                    "safeDelete" -> safeDelete.close()
                    "signature" -> signature.close()
                }
            }
        }
    }

    fun preview(snapshot: ProjectSnapshot, operation: String, args: Map<String, String>): PatchPlan {
        val file = args["file"]?.let(Path::of)
        return when (operation) {
            "renameSymbol" -> {
                val symbol = args["symbol"] ?: error("Missing arguments.symbol")
                val newName = args["newName"] ?: error("Missing arguments.newName")
                val loc = symbolLocation(snapshot, symbol)
                rename.rename(loc.file, loc.line, loc.character, newName, snapshot).toPlan(snapshot, "renameSymbol")
            }
            "renamePrefix" -> renamePrefix.preview(snapshot, parseMapping(args))
            "moveSource" -> move.preview(snapshot, file ?: error("Missing arguments.file"), Path.of(args["target"] ?: error("Missing arguments.target")))
            "formatFile" -> format.formatWholeFile(snapshot, file ?: error("Missing arguments.file")).toPlan(snapshot, "formatFile")
            "organizeIncludes" -> organizeIncludes.preview(snapshot, file ?: error("Missing arguments.file"))
            "safeDelete" -> safeDelete.preview(snapshot, args["symbol"] ?: error("Missing arguments.symbol"))
            "changeSignature" -> signature.renameParameter(snapshot, file ?: error("Missing arguments.file"), args["oldParam"] ?: error("Missing arguments.oldParam"), args["newParam"] ?: error("Missing arguments.newParam"))
            "extractExpression" -> extract.preview(snapshot, file ?: error("Missing arguments.file"), parseRange(args), args["tempName"] ?: "tmp")
            "inlineFunction" -> inline.preview(snapshot, file ?: error("Missing arguments.file"), args["symbol"] ?: error("Missing arguments.symbol"))
            "relocateComponent" -> relocate.preview(snapshot, file ?: error("Missing arguments.componentDir"), Path.of(args["newDir"] ?: error("Missing arguments.newDir")))
            else -> error("Unknown C operation: $operation")
        }
    }

    /**
     * Applies a previewed C plan through the central PatchEngine.
     *
     * Approval and diagnostics come from the calling surface (CLI/daemon/LSP/MCP),
     * never fabricated inside the adapter. Managed callers pass their retained
     * [diagnosticsGate] so the central gate evaluates the candidate snapshot.
     */
    fun apply(
        snapshot: ProjectSnapshot,
        plan: PatchPlan,
        authorization: ApplyAuthorization,
        diagnosticsGate: DiagnosticsGate,
    ): ApplyResult =
        PatchEngine(snapshot.workspace.root).apply(plan, snapshot, authorization, diagnosticsGate)

    override fun close() {
        rename.close()
        renamePrefix.close()
        safeDelete.close()
        signature.close()
    }

    private fun symbolLocation(snapshot: ProjectSnapshot, symbol: String): Location {
        for (file in snapshot.files.filter { it.languageId in setOf("c", "cpp", "objective-c") }) {
            val tokens = CTokenizer().tokenize(file.content)
            for (token in tokens) {
                if (token.type == CTokenType.IDENTIFIER && token.text == symbol) {
                    val line = token.line - 1
                    val char = file.content.lines().getOrNull(token.line - 1)?.indexOf(symbol) ?: 0
                    return Location(file.path.normalize(), line, char)
                }
            }
        }
        error("Symbol not found: $symbol")
    }

    private fun parseMapping(args: Map<String, String>): Map<String, String> {
        val mapping = mutableMapOf<String, String>()
        for ((k, v) in args) {
            if (k.startsWith("rename.")) mapping[k.removePrefix("rename.")] = v
        }
        return mapping
    }

    private fun parseRange(args: Map<String, String>): SourceRange {
        val startLine = args["startLine"]?.toIntOrNull() ?: error("Missing arguments.startLine")
        val startChar = args["startChar"]?.toIntOrNull() ?: error("Missing arguments.startChar")
        val endLine = args["endLine"]?.toIntOrNull() ?: error("Missing arguments.endLine")
        val endChar = args["endChar"]?.toIntOrNull() ?: error("Missing arguments.endChar")
        return SourceRange(SourcePosition(startLine, startChar), SourcePosition(endLine, endChar))
    }

    private data class Location(val file: Path, val line: Int, val character: Int)

    private fun CRenamePlannerResult.toPlan(snapshot: ProjectSnapshot, operation: String): PatchPlan = when (this) {
        is CRenamePlannerResult.Accepted -> PatchPlan(
            operation = operation,
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            requiresUserApproval = true,
            summary = "C rename preview",
            affectedFiles = normalized.workspaceEdit.affectedFiles(),
            workspaceEdit = normalized.workspaceEdit,
            diagnosticsBefore = emptyList(),
            diagnosticsAfterPreview = emptyList(),
            warnings = emptyList(),
            riskLevel = RiskLevel.MEDIUM,
            evidence = RefactoringEvidence.COMPILER_PROVEN,
        )
        is CRenamePlannerResult.Refused -> refused(snapshot, operation, diagnostics)
    }

    private fun CFormatResult.toPlan(snapshot: ProjectSnapshot, operation: String): PatchPlan = when (this) {
        is CFormatResult.Accepted -> PatchPlan(
            operation = operation,
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            requiresUserApproval = true,
            summary = "C format preview",
            affectedFiles = emptySet(),
            workspaceEdit = WorkspaceEdit(emptyList()),
            diagnosticsBefore = emptyList(),
            diagnosticsAfterPreview = emptyList(),
            warnings = listOf("Formatting edits are idempotent=$idempotent."),
            riskLevel = RiskLevel.MEDIUM,
            evidence = RefactoringEvidence.COMPILER_PROVEN,
        )
        is CFormatResult.Refused -> refused(snapshot, operation, diagnostics)
    }

    private fun refused(snapshot: ProjectSnapshot, operation: String, diagnostics: List<Diagnostic>) = PatchPlan(
        operation = operation,
        status = PatchStatus.REFUSED,
        snapshotHash = snapshot.hash,
        confidence = 0.0,
        requiresUserApproval = false,
        summary = diagnostics.joinToString("; ") { it.message },
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        diagnosticsBefore = emptyList(),
        diagnosticsAfterPreview = emptyList(),
        warnings = diagnostics.map { it.message },
        riskLevel = RiskLevel.HIGH,
        evidence = RefactoringEvidence.STRUCTURAL,
    )
}
