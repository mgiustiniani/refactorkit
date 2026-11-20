package org.refactorkit.c

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.ExternalFileEditProposal
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.ExternalWorkspaceEditNormalization
import org.refactorkit.core.ExternalWorkspaceEditNormalizer
import org.refactorkit.core.ExternalWorkspaceEditProposal
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
 * Rename an explicitly selected set of compiler-proven C API symbols using a
 * per-symbol old/new mapping, with complete binding-matched local consumers.
 *
 * The mapping is explicit (not prefix-matched), so same-prefix unrelated symbols
 * are never captured. Each symbol is renamed through clangd's semantic rename
 * (which does not rewrite strings/comments); a symbol with no semantic occurrence
 * or a clangd refusal is refused. No hidden textual fallback.
 */
class CRenamePrefixPlanner(
    private val toolchain: ClangSemanticToolchain,
    private val processManager: ExternalSemanticProcessManager = ExternalSemanticProcessManager(),
    private val normalizer: ExternalWorkspaceEditNormalizer = ExternalWorkspaceEditNormalizer(),
) : AutoCloseable {
    private val client = ClangdSemanticClient(toolchain, processManager)
    private var started = false
    private var startFailure: String? = null

    fun start(snapshot: ProjectSnapshot) {
        require(!started) { "C prefix rename planner is already started" }
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

    /** Renames the explicit [mapping] (old symbol name -> new name) into one preview plan. */
    fun preview(snapshot: ProjectSnapshot, mapping: Map<String, String>): PatchPlan =
        preview(snapshot, mapping, definitionPaths = null)

    internal fun preview(snapshot: ProjectSnapshot, mapping: Map<String, String>,
        definitionPaths: Set<Path>?): PatchPlan {
        if (!started) return refused(snapshot, startFailure ?: "C prefix rename planner is not started")
        if (mapping.isEmpty()) return refused(snapshot, "No symbol mapping provided")
        if (mapping.size > MAX_SYMBOLS) return refused(snapshot, "Symbol mapping exceeds $MAX_SYMBOLS entries")
        if (mapping.any { (old, new) -> !C_IDENTIFIER.matches(old) || !C_IDENTIFIER.matches(new) || old == new }) {
            return refused(snapshot, "Symbol mapping contains invalid entries")
        }
        if (mapping.keys.distinct().size != mapping.size) {
            return refused(snapshot, "Symbol mapping contains duplicate old names")
        }

        val editsByFile = mutableMapOf<Path, MutableList<TextEdit>>()
        for ((oldName, newName) in mapping) {
            val resolved = resolveCompilerBoundOccurrence(snapshot, oldName)
            val occurrence = when (resolved) {
                is OccurrenceResolution.Found -> resolved.occurrence
                is OccurrenceResolution.Refused -> return refused(snapshot, resolved.message)
            }
            if (definitionPaths != null) {
                val definition = snapshot.workspace.root.resolve(occurrence.file).toAbsolutePath().normalize()
                if (definition !in definitionPaths) {
                    return refused(snapshot, "Compiler-resolved declaration for '$oldName' is not owned by the moved component")
                }
            }
            val result = client.rename(occurrence.file, occurrence.line, occurrence.character, newName)
            when (result) {
                is CRenameResult.Found -> {
                    for (edit in result.edits) {
                        editsByFile.getOrPut(edit.file.normalize()) { mutableListOf() } += TextEdit(
                            SourceRange(SourcePosition(edit.startLine, edit.startCharacter), SourcePosition(edit.endLine, edit.endCharacter)),
                            edit.newText,
                        )
                    }
                }
                is CRenameResult.NotFound -> return refused(snapshot, "Symbol '$oldName' was not found by clangd")
                is CRenameResult.Refused -> {
                    val first = result.diagnostics.firstOrNull()
                    return refused(snapshot, first?.message ?: "clangd refused renaming '$oldName'")
                }
            }
        }

        val modifications = editsByFile.map { (file, textEdits) ->
            ExternalFileEditProposal.Modify(file, textEdits)
        }
        val proposal = ExternalWorkspaceEditProposal(
            providerId = "clangd-rename-prefix-v1",
            providerVersion = toolchain.provenance.clangdVersion,
            edits = modifications,
        )
        val normalization = normalizer.normalize(snapshot, proposal)
        return when (normalization) {
            is ExternalWorkspaceEditNormalization.Accepted -> PatchPlan(
                operation = "renameCSymbolPrefix",
                status = PatchStatus.PREVIEW,
                snapshotHash = snapshot.hash,
                confidence = 1.0,
                requiresUserApproval = true,
                summary = "Rename ${mapping.size} compiler-proven C API symbol(s)",
                affectedFiles = normalization.normalized.workspaceEdit.affectedFiles(),
                workspaceEdit = normalization.normalized.workspaceEdit,
                diagnosticsBefore = emptyList(),
                diagnosticsAfterPreview = emptyList(),
                warnings = listOf("Explicit per-symbol mapping; strings/comments and external ABI consumers are not rewritten."),
                riskLevel = RiskLevel.MEDIUM,
                evidence = RefactoringEvidence.COMPILER_PROVEN,
            )
            is ExternalWorkspaceEditNormalization.Refused -> refused(snapshot, normalization.diagnostics.joinToString("; ") { it.message })
        }
    }

    override fun close() {
        client.close()
        started = false
    }

    /**
     * Resolves a symbol name to a single declaration occurrence, refusing ambiguity.
     *
     * A name declared in more than one file is ambiguous (same-name/unrelated symbols),
     * so the prefix rename must not guess the first textual occurrence; an absent name is
     * refused explicitly instead of being treated as a semantic occurrence.
     */
    internal fun resolveOccurrence(snapshot: ProjectSnapshot, name: String): OccurrenceResolution {
        val candidates = occurrenceCandidates(snapshot, name)
        val distinctFiles = candidates.map { it.file }.distinct()
        return when {
            candidates.isEmpty() -> OccurrenceResolution.Refused("Symbol '$name' has no semantic occurrence")
            distinctFiles.size > 1 -> OccurrenceResolution.Refused("Symbol '$name' is ambiguous across ${distinctFiles.size} files; rename is refused")
            else -> OccurrenceResolution.Found(candidates.first())
        }
    }

    private fun resolveCompilerBoundOccurrence(snapshot: ProjectSnapshot, name: String): OccurrenceResolution {
        val candidates = occurrenceCandidates(snapshot, name)
        if (candidates.isEmpty()) return OccurrenceResolution.Refused("Symbol '$name' has no semantic occurrence")
        if (candidates.size > MAX_CANDIDATE_OCCURRENCES) {
            return OccurrenceResolution.Refused("Symbol '$name' exceeds the bounded local occurrence inventory")
        }

        val workspace = snapshot.workspace.root.toAbsolutePath().normalize()
        fun absolute(path: Path): Path = (if (path.isAbsolute) path else workspace.resolve(path)).normalize()
        val lexicalLocations = candidates.map { occurrence ->
            ReferenceIdentity(absolute(occurrence.file), occurrence.line, occurrence.character)
        }.toSet()
        val seed = candidates.first()
        val definition = when (val result = client.definition(seed.file, seed.line, seed.character)) {
            is CClangdSemanticResult.Found -> result.definition
            is CClangdSemanticResult.NotFound -> return OccurrenceResolution.Refused(
                "Symbol '$name' has no compiler-resolved declaration",
            )
            is CClangdSemanticResult.Refused -> return OccurrenceResolution.Refused(
                result.diagnostics.firstOrNull()?.message ?: "clangd could not resolve symbol '$name'",
            )
        }
        val definitionIdentity = ReferenceIdentity(
            absolute(definition.file), definition.startLine, definition.startCharacter,
        )
        if (definitionIdentity !in lexicalLocations) {
            return OccurrenceResolution.Refused(
                "clangd declaration for '$name' is outside the captured source token inventory",
            )
        }

        val references = when (val result = client.references(
            definition.file, definition.startLine, definition.startCharacter,
        )) {
            is CReferenceResult.Found -> result.references
            is CReferenceResult.NotFound -> return OccurrenceResolution.Refused(
                "Symbol '$name' has no complete compiler reference family",
            )
            is CReferenceResult.Unavailable -> return OccurrenceResolution.Refused(
                result.diagnostics.firstOrNull()?.message ?: "clangd reference closure for '$name' is unavailable",
            )
        }
        val compilerLocations = references.map { reference ->
            if (reference.startLine != reference.endLine ||
                reference.endCharacter - reference.startCharacter != name.length
            ) {
                return OccurrenceResolution.Refused(
                    "clangd returned a non-exact source range for '$name'",
                )
            }
            ReferenceIdentity(absolute(reference.file), reference.startLine, reference.startCharacter)
        }.toSet()
        if (compilerLocations != lexicalLocations) {
            val lexicalOnly = (lexicalLocations - compilerLocations).sortedWith(compareBy<ReferenceIdentity> { it.file.toString() }
                .thenBy(ReferenceIdentity::line).thenBy(ReferenceIdentity::character)).take(8)
            val compilerOnly = (compilerLocations - lexicalLocations).sortedWith(compareBy<ReferenceIdentity> { it.file.toString() }
                .thenBy(ReferenceIdentity::line).thenBy(ReferenceIdentity::character)).take(8)
            return OccurrenceResolution.Refused(
                "Symbol '$name' does not have one complete binding-matched local consumer family " +
                    "(snapshot=${lexicalLocations.size}, clangd=${compilerLocations.size}, " +
                    "snapshotOnly=$lexicalOnly, clangdOnly=$compilerOnly)",
            )
        }
        return OccurrenceResolution.Found(Occurrence(definition.file.normalize(), definition.startLine, definition.startCharacter))
    }

    private fun occurrenceCandidates(snapshot: ProjectSnapshot, name: String): List<Occurrence> {
        val candidates = mutableListOf<Occurrence>()
        for (source in snapshot.files.sortedBy { it.path.toString() }) {
            if (source.languageId !in setOf("c", "cpp", "objective-c")) continue
            val tokens = CTokenizer().tokenize(source.content)
            for (token in tokens) {
                if (token.type != CTokenType.IDENTIFIER || token.text != name) continue
                // Seed at the token's own column, not the first substring of the name in
                // the line: a preceding longer identifier sharing the name as a prefix
                // (e.g. foo_prefix on the same line as foo) would otherwise yield a wrong
                // seed position and bind clangd to the wrong symbol.
                candidates += Occurrence(source.path.normalize(), token.line - 1, token.column)
            }
        }
        return candidates
    }

    private data class ReferenceIdentity(val file: Path, val line: Int, val character: Int)

    internal sealed interface OccurrenceResolution {
        data class Found(val occurrence: Occurrence) : OccurrenceResolution
        data class Refused(val message: String) : OccurrenceResolution
    }

    internal data class Occurrence(val file: Path, val line: Int, val character: Int)

    private fun refused(snapshot: ProjectSnapshot, message: String) = PatchPlan(
        operation = "renameCSymbolPrefix",
        status = PatchStatus.REFUSED,
        snapshotHash = snapshot.hash,
        confidence = 0.0,
        requiresUserApproval = false,
        summary = message,
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        diagnosticsBefore = emptyList(),
        diagnosticsAfterPreview = emptyList(),
        warnings = listOf(message),
        riskLevel = RiskLevel.HIGH,
        evidence = RefactoringEvidence.STRUCTURAL,
        refusalCode = "c.renamePrefix.refused",
    )

    companion object {
        private const val MAX_SYMBOLS = 256
        private const val MAX_CANDIDATE_OCCURRENCES = 4_096
        private val C_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")
    }
}
