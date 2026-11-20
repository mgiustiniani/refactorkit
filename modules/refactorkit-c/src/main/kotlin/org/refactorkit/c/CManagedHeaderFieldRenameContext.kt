package org.refactorkit.c

import org.refactorkit.core.*
import java.nio.file.Path
import java.security.MessageDigest

internal class CHeaderFieldAuthorityFailure(val diagnostic: Diagnostic) : IllegalStateException(diagnostic.message)
private fun headerFailure(code: String, message: String): Nothing = throw CHeaderFieldAuthorityFailure(Diagnostic(
    message, Diagnostic.Severity.ERROR, code = code, evidence = DiagnosticEvidence.COMPILER, category = DiagnosticCategory.SAFETY,
))
private fun headerSha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
private fun headerFingerprint(values: List<String>) = headerSha(values.joinToString("") {
    "${it.toByteArray(Charsets.UTF_8).size}:$it"
}.toByteArray(Charsets.UTF_8))

internal typealias CHeaderFieldCompilationContext = CBoundedTranslationUnitCompilationContext
internal sealed interface CHeaderFieldCompilerResult {
    data class Available(val snapshotHash: String, val facts: CHeaderFieldRenameFacts,
        val context: CHeaderFieldCompilationContext) : CHeaderFieldCompilerResult
    data class Unavailable(val diagnostic: Diagnostic) : CHeaderFieldCompilerResult
}

/** Typed pure-domain delegation over shared read-only native acquisition. */
internal class CHeaderFieldFactsService(toolchain: ClangSemanticToolchain, processes: ExternalSemanticProcessManager) {
    private val compiler = CBoundedTranslationUnitFactsService(toolchain, processes, "c.headerFieldFacts")
    fun requireCurrent(snapshot: ProjectSnapshot) = compiler.requireCurrent(snapshot)
    fun analyze(live: ProjectSnapshot, candidate: ProjectSnapshot, selectedFile: Path, offset: Int): CHeaderFieldCompilerResult {
        return when (val result = compiler.analyze(live, candidate)) {
            is CBoundedTranslationUnitCompilerResult.Unavailable -> CHeaderFieldCompilerResult.Unavailable(result.diagnostic)
            is CBoundedTranslationUnitCompilerResult.Available -> {
                val bundle = result.facts
                val selected = bundle.logicalPaths.entries.singleOrNull { it.value == selectedFile }?.key
                    ?: return CHeaderFieldCompilerResult.Unavailable(Diagnostic("Selected source is outside captured compiler inputs",
                        Diagnostic.Severity.ERROR, code = "c.headerFieldFacts.selectionUnavailable",
                        evidence = DiagnosticEvidence.COMPILER, category = DiagnosticCategory.SAFETY))
                when (val readout = CHeaderFieldRenameFactsDecoder.decode(bundle.asts, bundle.sources, selected, offset, bundle.asts.keys, bundle.readOnlySources)) {
                    is CHeaderFieldReadout.Refused -> CHeaderFieldCompilerResult.Unavailable(Diagnostic(
                        "Compiler rename closure refused: ${readout.reason}", Diagnostic.Severity.ERROR,
                        code = "c.headerFieldFacts.${readout.reason.name.lowercase()}",
                        evidence = DiagnosticEvidence.COMPILER, category = DiagnosticCategory.SAFETY))
                    is CHeaderFieldReadout.Found -> {
                        val facts = readout.facts
                        fun relative(path: Path): Path = bundle.logicalPaths.getValue(path)
                        CHeaderFieldCompilerResult.Available(result.snapshotHash, CHeaderFieldRenameFacts(facts.name, facts.recordName, facts.recordKind, facts.fieldIndex, facts.fields,
                            facts.observations.map { it.copy(translationUnit = relative(it.translationUnit),
                            site = it.site.copy(file = relative(it.site.file))) },
                            facts.translationUnits.map(::relative).toSet(), facts.sourceHashes.mapKeys { relative(it.key) }), result.context)
                    }
                }
            }
        }
    }
}

/** Application authority for one independently reconstructed complete header field family. */
internal class CManagedHeaderFieldRenameContext(private val toolchain: ClangSemanticToolchain,
    private val processes: ExternalSemanticProcessManager) {
    private val facts = CHeaderFieldFactsService(toolchain, processes)
    private val diagnostics = CCompilerDiagnostics(toolchain, processes)
    private data class Proof(val plan: PatchPlan, val beforeFacts: CHeaderFieldRenameFacts,
        val context: CHeaderFieldCompilationContext, val file: Path, val replacement: String)

    fun preview(before: ProjectSnapshot, plan: PatchPlan, args: Map<String, String>): PatchPlan = try {
        prove(before, plan, args).plan
    } catch (failure: CHeaderFieldAuthorityFailure) { throw failure }
      catch (failure: Exception) { headerFailure("c.headerField.authorityUnavailable",
          failure.message ?: "Complete header field authority is unavailable") }

    fun gate(before: ProjectSnapshot, plan: PatchPlan): DiagnosticsGate = try {
        val lease = requireNotNull(plan.authorityLease)
        require(lease.kind == LEASE_KIND && lease.operation == "renameSymbol" && lease.snapshotHash == before.hash)
        val args = listOf("selectedName", "newName", "file", "line", "character").associate { key ->
            (if (key == "selectedName") "symbol" else key) to requireNotNull(lease.attributes["c.headerField.$key"])
        }
        val proof = prove(before, plan, args)
        require(proof.plan.authorityLease == lease) { "Retained declaration, consumer, context or staged edit authority differs" }
        val declaration = proof.beforeFacts.sites.single { it.kind == CHeaderFieldSiteKind.DECLARATION }
        DiagnosticsGate.enabled(GATE_ID) { candidate ->
            require(candidate.workspace.root.toAbsolutePath().normalize() == before.workspace.root.toAbsolutePath().normalize() &&
                candidate.copy().hash == candidate.hash && candidate.hash in setOf(before.hash, lease.evidenceHash)) {
                "c.headerField.authorityInvalid: candidate differs from the two approved images"
            }
            val live = if (runCatching { facts.requireCurrent(before) }.isSuccess) before else candidate
            val observed = available(facts.analyze(live, candidate, proof.file, declaration.span.start), candidate)
            require(observed.context.identity == proof.context.identity) { "Captured TU compiler contexts changed" }
            if (candidate.hash == before.hash) require(identity(observed.facts) == identity(proof.beforeFacts))
            else require(CHeaderFieldRenameBinding.check(proof.beforeFacts, observed.facts, proof.replacement) == CHeaderFieldTransition.Verified)
            val result = evaluate(candidate, proof.context)
            facts.requireCurrent(live)
            require(CCompilationSnapshotCapture.compilerIsCurrent(toolchain))
            result
        }
    } catch (failure: Exception) {
        DiagnosticsGate.enabled(GATE_ID) { error("c.headerField.authorityInvalid: ${failure.message}") }
    }

    private fun prove(before: ProjectSnapshot, plan: PatchPlan, args: Map<String, String>): Proof {
        require(plan.operation == "renameSymbol" && plan.status == PatchStatus.PREVIEW && plan.requiresUserApproval &&
            plan.evidence == RefactoringEvidence.COMPILER_PROVEN && plan.snapshotHash == before.hash && before.copy().hash == before.hash)
        val file = Path.of(requireNotNull(args["file"]))
        require(!file.isAbsolute && file == file.normalize() && !file.startsWith(".."))
        val source = before.files.single { it.path == file && it.languageId == "c" }
        val name = requireNotNull(args["symbol"])
        val replacement = requireNotNull(args["newName"])
        require(replacement != name && replacement.matches(Regex("[A-Za-z_][A-Za-z_0-9]{0,127}")))
        val line = requireNotNull(args["line"]).toInt()
        val character = requireNotNull(args["character"]).toInt()
        val offset = TextEdits.offsetOf(source.content, SourcePosition(line, character))
        val original = available(facts.analyze(before, before, file,
            source.content.substring(0, offset).toByteArray(Charsets.UTF_8).size), before)
        require(original.facts.name == name && original.facts.sourceHashes.keys == before.files.map { it.path }.toSet())
        val proposal = ExternalWorkspaceEditProposal("clang-header-field-binding-v1", toolchain.provenance.clangVersion,
            original.facts.sites.groupBy { it.file }.entries.sortedBy { it.key.toString() }.map { (path, sites) ->
                val content = before.files.single { it.path == path }.content
                ExternalFileEditProposal.Modify(path, sites.map { site -> TextEdit(SourceRange(
                    TextEdits.positionForOffset(content, site.span.start), TextEdits.positionForOffset(content, site.span.endExclusive)), replacement) })
            })
        val normalized = ExternalWorkspaceEditNormalizer().normalize(before, proposal) as? ExternalWorkspaceEditNormalization.Accepted
            ?: error("Independent compiler declaration ranges cannot be normalized")
        require(normalized.normalized.workspaceEdit == plan.workspaceEdit) { "Compiler member/designator closure does not reproduce clangd edits" }
        val staged = WorkspaceEditSimulator.apply(before, plan.workspaceEdit)
        val declaration = original.facts.sites.single { it.kind == CHeaderFieldSiteKind.DECLARATION }
        val after = available(facts.analyze(before, staged, file, declaration.span.start), staged)
        require(after.context.identity == original.context.identity &&
            CHeaderFieldRenameBinding.check(original.facts, after.facts, replacement) == CHeaderFieldTransition.Verified) {
            "Staged field layout, namespace, consumer set or exact reference family differs"
        }
        val beforeDiagnostics = evaluate(before, original.context)
        val afterDiagnostics = evaluate(staged, original.context)
        facts.requireCurrent(before)
        require(CCompilationSnapshotCapture.compilerIsCurrent(toolchain))
        val lease = OperationAuthorityLease(kind = LEASE_KIND, operation = "renameSymbol", snapshotHash = before.hash,
            evidenceHash = staged.hash, workspaceEditSha256 = WorkspaceEditIdentity.sha256(plan.workspaceEdit),
            requiredFileEvidence = listOf(OperationAuthorityFileEvidence("c.headerField.compilationDatabase",
                original.context.databasePath, original.context.databaseSha256)),
            attributes = mapOf(
                "c.headerField.selectedName" to name, "c.headerField.newName" to replacement,
                "c.headerField.file" to file.toString(), "c.headerField.line" to line.toString(),
                "c.headerField.character" to character.toString(),
                "c.headerField.translationUnitCount" to original.facts.translationUnits.size.toString(),
                "c.headerField.physicalSiteCount" to original.facts.sites.size.toString(),
                "c.headerField.bindingSha256" to identity(original.facts),
                "c.headerField.compilationContextSha256" to original.context.identity,
                "c.compilationDatabasePath" to original.context.databasePath.toString(),
                "c.compilationDatabaseSha256" to original.context.databaseSha256,
                "c.nativeRuntimeIdentity" to requireNotNull(toolchain.provenance.nativeRuntimeIdentity),
                "c.toolchainIdentity" to toolchain.provenance.toolchainIdentity(),
                "c.clangdSha256" to toolchain.provenance.evidence.single { it.role == "clangd-executable" }.sha256,
            ))
        return Proof(plan.copy(authorityLease = lease, diagnosticsBefore = beforeDiagnostics,
            diagnosticsAfterPreview = afterDiagnostics), original.facts, original.context, file, replacement)
    }
    private fun available(result: CHeaderFieldCompilerResult, snapshot: ProjectSnapshot): CHeaderFieldCompilerResult.Available = when (result) {
        is CHeaderFieldCompilerResult.Available -> { require(result.snapshotHash == snapshot.hash); result }
        is CHeaderFieldCompilerResult.Unavailable -> throw CHeaderFieldAuthorityFailure(result.diagnostic)
    }
    private fun evaluate(snapshot: ProjectSnapshot, context: CHeaderFieldCompilationContext): List<Diagnostic> =
        context.flags.entries.sortedBy { it.key.toString() }.flatMap { (file, flags) ->
            val result = diagnostics.analyze(snapshot, file, flags) as? CDiagnosticsResult.Available
                ?: error("Exact native TU diagnostics are unavailable")
            require(result.processProvenance?.executableSha256 == toolchain.provenance.evidence.single {
                it.role == "clang-executable"
            }.sha256 && result.diagnostics.none { it.severity == Diagnostic.Severity.ERROR }) { "Compiler diagnostics do not prove the staged TU set" }
            result.diagnostics
        }
    private fun identity(facts: CHeaderFieldRenameFacts): String = headerFingerprint(listOf(facts.name,
        facts.recordName, facts.recordKind, facts.fieldIndex.toString()) + facts.fields.flatMap { listOf(it.name, it.type) } +
        facts.observations.flatMap { listOf(it.translationUnit.toString(), it.astPath, it.site.file.toString(),
            it.site.span.start.toString(), it.site.span.endExclusive.toString(), it.site.kind.name) } +
        facts.sourceHashes.entries.sortedBy { it.key.toString() }.flatMap { listOf(it.key.toString(), it.value) })
    companion object {
        const val LEASE_KIND = "c.rename-symbol-header-field-v1"
        private const val GATE_ID = "clang-c-header-field-v1"
    }
}
