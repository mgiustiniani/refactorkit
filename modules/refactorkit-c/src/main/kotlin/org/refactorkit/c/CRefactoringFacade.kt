package org.refactorkit.c

import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
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
                val symbol = args["symbol"] ?: return missingArgument(snapshot, operation, "arguments.symbol")
                val newName = args["newName"] ?: return missingArgument(snapshot, operation, "arguments.newName")
                val resolved = resolveSymbol(snapshot, symbol)
                when (resolved) {
                    is SymbolResolution.Found ->
                        rename.rename(resolved.location.file, resolved.location.line, resolved.location.character, newName, snapshot).toPlan(snapshot, "renameSymbol")
                    is SymbolResolution.Refused -> refused(snapshot, "renameSymbol", resolved.diagnostics)
                }
            }
            "renamePrefix" -> renamePrefix.preview(snapshot, parseMapping(args))
            "moveSource" -> move.preview(snapshot, file ?: return missingArgument(snapshot, operation, "arguments.file"), Path.of(args["target"] ?: return missingArgument(snapshot, operation, "arguments.target")))
            "formatFile" -> format.formatWholeFile(snapshot, file ?: return missingArgument(snapshot, operation, "arguments.file")).toPlan(snapshot, "formatFile", file ?: return missingArgument(snapshot, operation, "arguments.file"))
            "organizeIncludes" -> organizeIncludes.preview(snapshot, file ?: return missingArgument(snapshot, operation, "arguments.file"))
            "safeDelete" -> safeDelete.preview(snapshot, args["symbol"] ?: return missingArgument(snapshot, operation, "arguments.symbol"))
            "changeSignature" -> signature.renameParameter(snapshot, file ?: return missingArgument(snapshot, operation, "arguments.file"), args["oldParam"] ?: return missingArgument(snapshot, operation, "arguments.oldParam"), args["newParam"] ?: return missingArgument(snapshot, operation, "arguments.newParam"))
            "extractExpression" -> {
                val range = parseRange(args) ?: return invalidRange(snapshot, operation)
                extract.preview(snapshot, file ?: return missingArgument(snapshot, operation, "arguments.file"), range, args["tempName"] ?: "tmp")
            }
            "inlineFunction" -> inline.preview(snapshot, file ?: return missingArgument(snapshot, operation, "arguments.file"), args["symbol"] ?: return missingArgument(snapshot, operation, "arguments.symbol"))
            "relocateComponent" -> relocate.preview(snapshot, args["componentDir"]?.let(Path::of) ?: return missingArgument(snapshot, operation, "arguments.componentDir"), Path.of(args["newDir"] ?: return missingArgument(snapshot, operation, "arguments.newDir")))
            else -> unknownOperation(snapshot, operation)
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

    private sealed interface SymbolResolution {
        data class Found(val location: Location) : SymbolResolution
        data class Refused(val diagnostics: List<Diagnostic>) : SymbolResolution
    }

    /**
     * Resolves a symbol to a single binding location, refusing ambiguity.
     *
     * A name that resolves to distinct declarations in more than one file is
     * ambiguous (same-name/shadowing risk): the rename must not guess the first
     * textual occurrence. Absent names are refused explicitly instead of throwing.
     */
    private fun resolveSymbol(snapshot: ProjectSnapshot, symbol: String): SymbolResolution {
        val candidates = mutableListOf<Location>()
        for (file in snapshot.files.filter { it.languageId in setOf("c", "cpp", "objective-c") }.sortedBy { it.path.toString() }) {
            val tokens = CTokenizer().tokenize(file.content)
            for (token in tokens) {
                if (token.type != CTokenType.IDENTIFIER || token.text != symbol) continue
                val line = token.line - 1
                // Use the token's own column; a first-substring indexOf(symbol) would
                // seed into an earlier identifier that merely contains the symbol name.
                val char = token.column
                candidates += Location(file.path.normalize(), line, char)
            }
        }
        val distinctFiles = candidates.map { it.file }.distinct()
        return when {
            candidates.isEmpty() -> SymbolResolution.Refused(listOf(Diagnostic(
                message = "Symbol '$symbol' has no definition in this snapshot",
                severity = Diagnostic.Severity.ERROR,
                code = "c.renameSymbolNotFound",
                evidence = DiagnosticEvidence.STRUCTURAL,
                category = DiagnosticCategory.TYPE_RESOLUTION,
            )))
            distinctFiles.size > 1 -> SymbolResolution.Refused(listOf(Diagnostic(
                message = "Symbol '$symbol' is ambiguous across ${distinctFiles.size} files; rename is refused",
                severity = Diagnostic.Severity.ERROR,
                code = "c.renameSymbolAmbiguous",
                evidence = DiagnosticEvidence.STRUCTURAL,
                category = DiagnosticCategory.TYPE_RESOLUTION,
            )))
            else -> SymbolResolution.Found(candidates.first())
        }
    }

    private fun parseMapping(args: Map<String, String>): Map<String, String> {
        val mapping = mutableMapOf<String, String>()
        for ((k, v) in args) {
            if (k.startsWith("rename.")) mapping[k.removePrefix("rename.")] = v
        }
        return mapping
    }

    private fun parseRange(args: Map<String, String>): SourceRange? {
        val startLine = args["startLine"]?.toIntOrNull() ?: return null
        val startChar = args["startChar"]?.toIntOrNull() ?: return null
        val endLine = args["endLine"]?.toIntOrNull() ?: return null
        val endChar = args["endChar"]?.toIntOrNull() ?: return null
        return SourceRange(SourcePosition(startLine, startChar), SourcePosition(endLine, endChar))
    }

    private fun missingArgument(snapshot: ProjectSnapshot, operation: String, argument: String) =
        refusedDiagnostic(snapshot, operation, "c.missingArgument", "Missing $argument")

    private fun invalidRange(snapshot: ProjectSnapshot, operation: String) =
        refusedDiagnostic(snapshot, operation, "c.invalidRange", "Invalid or missing extract range arguments")

    private fun unknownOperation(snapshot: ProjectSnapshot, operation: String) =
        refusedDiagnostic(snapshot, operation, "c.unknownOperation", "Unknown C operation: $operation")

    private fun refusedDiagnostic(snapshot: ProjectSnapshot, operation: String, code: String, message: String) =
        refused(snapshot, operation, listOf(Diagnostic(
            message = message,
            severity = Diagnostic.Severity.ERROR,
            code = code,
            evidence = DiagnosticEvidence.STRUCTURAL,
            category = DiagnosticCategory.SAFETY,
        )))

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

    private fun CFormatResult.toPlan(snapshot: ProjectSnapshot, operation: String, file: Path): PatchPlan = when (this) {
        is CFormatResult.Accepted -> {
            val relFile = snapshot.workspace.root.relativize(snapshot.workspace.root.resolve(file).normalize()).normalize()
            val workspaceEdit = if (edits.isEmpty()) WorkspaceEdit(emptyList())
            else WorkspaceEdit(listOf(FileEdit.Modify(relFile, edits)))
            PatchPlan(
                operation = operation,
                status = PatchStatus.PREVIEW,
                snapshotHash = snapshot.hash,
                confidence = 1.0,
                requiresUserApproval = true,
                summary = if (edits.isEmpty()) "C format preview (no changes)" else "C format preview",
                affectedFiles = workspaceEdit.affectedFiles(),
                workspaceEdit = workspaceEdit,
                diagnosticsBefore = emptyList(),
                diagnosticsAfterPreview = emptyList(),
                warnings = listOf("Formatting edits are idempotent=$idempotent."),
                riskLevel = RiskLevel.MEDIUM,
                evidence = RefactoringEvidence.COMPILER_PROVEN,
            )
        }
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
