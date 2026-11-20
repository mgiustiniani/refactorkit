package org.refactorkit.c

import org.refactorkit.core.*
import java.nio.file.Path
import java.security.MessageDigest

internal class CTagAuthorityFailure(val diagnostic: Diagnostic) : IllegalStateException(diagnostic.message)
private fun tagFailure(code: String, message: String): Nothing = throw CTagAuthorityFailure(Diagnostic(
    message, Diagnostic.Severity.ERROR, code = code, evidence = DiagnosticEvidence.COMPILER, category = DiagnosticCategory.SAFETY,
))
private fun tagSha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
private fun tagFingerprint(values: List<String>) = tagSha(values.joinToString("") {
    "${it.toByteArray(Charsets.UTF_8).size}:$it"
}.toByteArray(Charsets.UTF_8))

internal sealed interface CTagCompilerResult {
    data class Available(val snapshotHash: String, val facts: CTagRenameFacts,
        val context: CBoundedTranslationUnitCompilationContext) : CTagCompilerResult
    data class Unavailable(val diagnostic: Diagnostic, val refusal: CTagFactsRefusal? = null) : CTagCompilerResult
}

/** Typed pure-domain delegation over shared read-only native acquisition. */
internal class CTagFactsService(toolchain: ClangSemanticToolchain, processes: ExternalSemanticProcessManager) {
    private val compiler = CBoundedTranslationUnitFactsService(toolchain, processes, "c.tagFacts")
    fun requireCurrent(snapshot: ProjectSnapshot) = compiler.requireCurrent(snapshot)
    fun analyze(live: ProjectSnapshot, candidate: ProjectSnapshot, selectedFile: Path, offset: Int): CTagCompilerResult {
        return when (val result = compiler.analyze(live, candidate)) {
            is CBoundedTranslationUnitCompilerResult.Unavailable -> CTagCompilerResult.Unavailable(result.diagnostic)
            is CBoundedTranslationUnitCompilerResult.Available -> {
                val bundle = result.facts
                val selected = bundle.logicalPaths.entries.singleOrNull { it.value == selectedFile }?.key
                    ?: return CTagCompilerResult.Unavailable(Diagnostic("Selected source is outside captured compiler inputs",
                        Diagnostic.Severity.ERROR, code = "c.tagFacts.selectionUnavailable",
                        evidence = DiagnosticEvidence.COMPILER, category = DiagnosticCategory.SAFETY))
                val readout = if (selectedFile.toString().endsWith(".c") && selected in bundle.asts.keys) {
                    CSourceTagMutationAuthority.decode(bundle.asts, bundle.sources, selected, offset, bundle.asts.keys)
                } else CTagRenameFactsDecoder.decode(bundle.asts, bundle.sources, selected, offset, bundle.asts.keys)
                when (readout) {
                    is CTagReadout.Refused -> CTagCompilerResult.Unavailable(Diagnostic(
                        "Compiler rename closure refused: ${readout.reason}", Diagnostic.Severity.ERROR,
                        code = "c.tagFacts.${readout.reason.name.lowercase()}",
                        evidence = DiagnosticEvidence.COMPILER, category = DiagnosticCategory.SAFETY), readout.reason)
                    is CTagReadout.Found -> {
                        val facts = readout.facts
                        fun relative(path: Path): Path = bundle.logicalPaths.getValue(path)
                        CTagCompilerResult.Available(result.snapshotHash, CTagRenameFacts(facts.name, facts.kind,
                            facts.observations.map { it.copy(translationUnit = relative(it.translationUnit),
                            site = it.site.copy(file = relative(it.site.file))) },
                            facts.typeObservations.map { it.copy(translationUnit = relative(it.translationUnit)) },
                            facts.declarationShape, facts.otherTagNames, facts.translationUnits.map(::relative).toSet(),
                            facts.sourceHashes.mapKeys { relative(it.key) }), result.context)
                    }
                }
            }
        }
    }
}

internal sealed interface CTagNamespacePreflight {
    data object OutsideTagSelection : CTagNamespacePreflight
    data object CapturedTagDestination : CTagNamespacePreflight
    data class Unavailable(val diagnostic: Diagnostic) : CTagNamespacePreflight
    data class Refused(val diagnostic: Diagnostic) : CTagNamespacePreflight
}

/** Application authority for one independently reconstructed complete tag family. */
internal class CManagedTagRenameContext(private val toolchain: ClangSemanticToolchain,
    private val processes: ExternalSemanticProcessManager) {
    private val facts = CTagFactsService(toolchain, processes)
    private val diagnostics = CCompilerDiagnostics(toolchain, processes)
    private data class Proof(val plan: PatchPlan, val beforeFacts: CTagRenameFacts,
        val context: CBoundedTranslationUnitCompilationContext, val file: Path, val replacement: String)

    /** Only compiler-proven tag selections may bypass the ordinary-identifier collision policy. */
    fun namespacePreflight(before: ProjectSnapshot, file: Path, line: Int, character: Int,
        destination: String): CTagNamespacePreflight = try {
        val source = before.files.singleOrNull { it.path == file && it.languageId == "c" }
        if (source == null || file.toString().let { !it.endsWith(".h") && !it.endsWith(".c") } || source.content.length > 1_048_576) {
            CTagNamespacePreflight.OutsideTagSelection
        } else {
            val tokens = CTokenizer().tokenize(source.content)
            val index = tokens.indexOfFirst { it.line == line + 1 && it.column == character }
            if (index < 1 || tokens[index - 1].text !in setOf("struct", "union", "enum")) {
                CTagNamespacePreflight.OutsideTagSelection
            } else {
                val offset = TextEdits.offsetOf(source.content, SourcePosition(line, character))
                when (val result = facts.analyze(before, before, file, offset)) {
                    is CTagCompilerResult.Unavailable -> if (result.refusal == CTagFactsRefusal.SOURCE_TYPE_EXPOSURE_UNQUALIFIED) {
                        CTagNamespacePreflight.Refused(result.diagnostic)
                    } else CTagNamespacePreflight.Unavailable(result.diagnostic)
                    is CTagCompilerResult.Available -> {
                        require(result.snapshotHash == before.hash && result.facts.name == tokens[index].text)
                        if (destination in result.facts.otherTagNames) CTagNamespacePreflight.Refused(Diagnostic(
                            "Rename destination '$destination' already belongs to the captured C tag lookup space",
                            Diagnostic.Severity.ERROR, code = "c.renameTagTargetPresent", evidence = DiagnosticEvidence.COMPILER,
                            category = DiagnosticCategory.SAFETY))
                        else CTagNamespacePreflight.CapturedTagDestination
                    }
                }
            }
        }
    } catch (_: Exception) {
        CTagNamespacePreflight.Unavailable(Diagnostic("Compiler tag namespace authority is unavailable",
            Diagnostic.Severity.ERROR, code = "c.tagFacts.selectionUnavailable", evidence = DiagnosticEvidence.COMPILER,
            category = DiagnosticCategory.SAFETY))
    }

    fun preview(before: ProjectSnapshot, plan: PatchPlan, args: Map<String, String>): PatchPlan = try {
        val selected = if (args["file"]?.endsWith(".c") == true) compilerSourcePreview(before, plan, args) else plan
        prove(before, selected, args).plan
    } catch (failure: CTagAuthorityFailure) { throw failure }
      catch (failure: Exception) { tagFailure("c.tag.authorityUnavailable",
          failure.message ?: "Complete tag family authority is unavailable") }

    /** Clangd can merge file-scope C tag USRs across independent TUs; native source ownership stays explicit. */
    private fun compilerSourcePreview(before: ProjectSnapshot, proposed: PatchPlan, args: Map<String, String>): PatchPlan {
        require(proposed.operation == "renameSymbol" && proposed.status == PatchStatus.PREVIEW &&
            proposed.snapshotHash == before.hash && proposed.evidence == RefactoringEvidence.COMPILER_PROVEN &&
            proposed.requiresUserApproval)
        val file = Path.of(requireNotNull(args["file"]))
        require(!file.isAbsolute && file == file.normalize() && !file.startsWith(".."))
        val source = before.files.single { it.path == file && it.languageId == "c" }
        val position = SourcePosition(requireNotNull(args["line"]).toInt(), requireNotNull(args["character"]).toInt())
        val captured = available(facts.analyze(before, before, file, TextEdits.offsetOf(source.content, position)), before).facts
        require(captured.name == args["symbol"])
        val edit = workspaceEdit(before, captured, requireNotNull(args["newName"]))
        return PatchPlan(operation = "renameSymbol", snapshotHash = before.hash, confidence = 1.0,
            requiresUserApproval = true, summary = "C source tag rename from compiler-owned TU declaration and type bindings",
            affectedFiles = edit.affectedFiles(), workspaceEdit = edit, evidence = RefactoringEvidence.COMPILER_PROVEN,
            riskLevel = RiskLevel.MEDIUM, warnings = listOf(
                "Preview uses captured Clang source-tag bindings; unrelated translation-unit homonyms are excluded"))
    }

    fun gate(before: ProjectSnapshot, plan: PatchPlan): DiagnosticsGate = try {
        val lease = requireNotNull(plan.authorityLease)
        require(lease.kind == LEASE_KIND && lease.operation == "renameSymbol" && lease.snapshotHash == before.hash)
        val args = listOf("selectedName", "newName", "file", "line", "character").associate { key ->
            (if (key == "selectedName") "symbol" else key) to requireNotNull(lease.attributes["c.tag.$key"])
        }
        val proof = prove(before, plan, args)
        require(proof.plan.authorityLease == lease) { "Retained declaration, consumer, context or staged edit authority differs" }
        val declaration = proof.beforeFacts.sites.single { it.kind == CTagSiteKind.DECLARATION && it.file == proof.file }
        DiagnosticsGate.enabled(GATE_ID) { candidate ->
            require(candidate.workspace.root.toAbsolutePath().normalize() == before.workspace.root.toAbsolutePath().normalize() &&
                candidate.copy().hash == candidate.hash && candidate.hash in setOf(before.hash, lease.evidenceHash)) {
                "c.tag.authorityInvalid: candidate differs from the two approved images"
            }
            val live = if (runCatching { facts.requireCurrent(before) }.isSuccess) before else candidate
            val observed = available(facts.analyze(live, candidate, proof.file, declaration.span.start), candidate)
            require(observed.context.identity == proof.context.identity) { "Captured TU compiler contexts changed" }
            if (candidate.hash == before.hash) require(identity(observed.facts) == identity(proof.beforeFacts))
            else require(CTagRenameBinding.check(proof.beforeFacts, observed.facts, proof.replacement) == CTagTransition.Verified)
            val result = evaluate(candidate, proof.context)
            facts.requireCurrent(live)
            require(CCompilationSnapshotCapture.compilerIsCurrent(toolchain))
            result
        }
    } catch (failure: Exception) {
        DiagnosticsGate.enabled(GATE_ID) { error("c.tag.authorityInvalid: ${failure.message}") }
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
        require(workspaceEdit(before, original.facts, replacement) == plan.workspaceEdit) {
            "Compiler tag/type closure does not reproduce proposed edits"
        }
        val staged = WorkspaceEditSimulator.apply(before, plan.workspaceEdit)
        val declaration = original.facts.sites.single { it.kind == CTagSiteKind.DECLARATION && it.file == file }
        val after = available(facts.analyze(before, staged, file, declaration.span.start), staged)
        require(after.context.identity == original.context.identity &&
            CTagRenameBinding.check(original.facts, after.facts, replacement) == CTagTransition.Verified) {
            "Staged declaration identity, namespace, consumer set or exact reference family differs"
        }
        val beforeDiagnostics = evaluate(before, original.context)
        val afterDiagnostics = evaluate(staged, original.context)
        facts.requireCurrent(before)
        require(CCompilationSnapshotCapture.compilerIsCurrent(toolchain))
        val lease = OperationAuthorityLease(kind = LEASE_KIND, operation = "renameSymbol", snapshotHash = before.hash,
            evidenceHash = staged.hash, workspaceEditSha256 = WorkspaceEditIdentity.sha256(plan.workspaceEdit),
            requiredFileEvidence = listOf(OperationAuthorityFileEvidence("c.tag.compilationDatabase",
                original.context.databasePath, original.context.databaseSha256)),
            attributes = mapOf(
                "c.tag.selectedName" to name, "c.tag.newName" to replacement,
                "c.tag.file" to file.toString(), "c.tag.line" to line.toString(),
                "c.tag.character" to character.toString(),
                "c.tag.translationUnitCount" to original.facts.translationUnits.size.toString(),
                "c.tag.kind" to original.facts.kind.name,
                "c.tag.validationPolicy" to "iso-pedantic-errors-v1",
                "c.tag.physicalSiteCount" to original.facts.sites.size.toString(),
                "c.tag.bindingSha256" to identity(original.facts),
                "c.tag.compilationContextSha256" to original.context.identity,
                "c.compilationDatabasePath" to original.context.databasePath.toString(),
                "c.compilationDatabaseSha256" to original.context.databaseSha256,
                "c.nativeRuntimeIdentity" to requireNotNull(toolchain.provenance.nativeRuntimeIdentity),
                "c.toolchainIdentity" to toolchain.provenance.toolchainIdentity(),
                "c.clangdSha256" to toolchain.provenance.evidence.single { it.role == "clangd-executable" }.sha256,
            ))
        return Proof(plan.copy(authorityLease = lease, diagnosticsBefore = beforeDiagnostics,
            diagnosticsAfterPreview = afterDiagnostics), original.facts, original.context, file, replacement)
    }
    private fun workspaceEdit(before: ProjectSnapshot, captured: CTagRenameFacts, replacement: String): WorkspaceEdit {
        val proposal = ExternalWorkspaceEditProposal("clang-tag-binding-v1", toolchain.provenance.clangVersion,
            captured.sites.groupBy { it.file }.entries.sortedBy { it.key.toString() }.map { (path, sites) ->
                val content = before.files.single { it.path == path }.content
                ExternalFileEditProposal.Modify(path, sites.map { site -> TextEdit(SourceRange(
                    TextEdits.positionForOffset(content, site.span.start), TextEdits.positionForOffset(content, site.span.endExclusive)), replacement) })
            })
        val normalized = ExternalWorkspaceEditNormalizer().normalize(before, proposal) as? ExternalWorkspaceEditNormalization.Accepted
            ?: error("Independent compiler tag ranges cannot be normalized")
        return normalized.normalized.workspaceEdit
    }
    private fun available(result: CTagCompilerResult, snapshot: ProjectSnapshot): CTagCompilerResult.Available = when (result) {
        is CTagCompilerResult.Available -> { require(result.snapshotHash == snapshot.hash); result }
        is CTagCompilerResult.Unavailable -> throw CTagAuthorityFailure(result.diagnostic)
    }
    private fun evaluate(snapshot: ProjectSnapshot, context: CBoundedTranslationUnitCompilationContext): List<Diagnostic> =
        context.flags.entries.sortedBy { it.key.toString() }.flatMap { (file, flags) ->
            val result = diagnostics.analyze(snapshot, file, flags + "-pedantic-errors") as? CDiagnosticsResult.Available
                ?: error("Exact native TU diagnostics are unavailable")
            require(result.processProvenance?.executableSha256 == toolchain.provenance.evidence.single {
                it.role == "clang-executable"
            }.sha256 && result.diagnostics.none { it.severity == Diagnostic.Severity.ERROR }) { "Compiler diagnostics do not prove the staged TU set" }
            result.diagnostics
        }
    private fun identity(facts: CTagRenameFacts): String = tagFingerprint(listOf(facts.name, facts.kind.name) +
        facts.otherTagNames.sorted() + facts.declarationShape.map { it.toString() } +
        facts.typeObservations.flatMap { listOf(it.translationUnit.toString(), it.astPath, it.property, it.qualified, it.desugared.orEmpty()) } +
        facts.observations.flatMap { listOf(it.translationUnit.toString(), it.witnessPath, it.site.file.toString(),
            it.site.span.start.toString(), it.site.span.endExclusive.toString(), it.site.kind.name) } +
        facts.sourceHashes.entries.sortedBy { it.key.toString() }.flatMap { listOf(it.key.toString(), it.value) })
    companion object {
        const val LEASE_KIND = "c.rename-symbol-tag-v1"
        private const val GATE_ID = "clang-c-tag-v1"
    }
}
