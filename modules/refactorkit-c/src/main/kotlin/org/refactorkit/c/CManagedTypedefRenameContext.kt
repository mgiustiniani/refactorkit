package org.refactorkit.c

import org.refactorkit.core.*
import java.nio.file.Path
import java.security.MessageDigest

internal class CTypedefAuthorityFailure(val diagnostic: Diagnostic) : IllegalStateException(diagnostic.message)
private fun typedefFailure(code: String, message: String): Nothing = throw CTypedefAuthorityFailure(Diagnostic(
    message, Diagnostic.Severity.ERROR, code = code, evidence = DiagnosticEvidence.COMPILER, category = DiagnosticCategory.SAFETY,
))
private fun typedefSha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
private fun typedefFingerprint(values: List<String>) = typedefSha(values.joinToString("") {
    "${it.toByteArray(Charsets.UTF_8).size}:$it"
}.toByteArray(Charsets.UTF_8))

internal sealed interface CTypedefCompilerResult {
    data class Available(val snapshotHash: String, val facts: CTypedefRenameFacts,
        val context: CBoundedTranslationUnitCompilationContext) : CTypedefCompilerResult
    data class Unavailable(val diagnostic: Diagnostic, val refusal: CTypedefFactsRefusal? = null) : CTypedefCompilerResult
}

/** Typed pure-domain delegation over shared read-only native acquisition. */
internal class CTypedefFactsService(toolchain: ClangSemanticToolchain, processes: ExternalSemanticProcessManager) {
    private val compiler = CBoundedTranslationUnitFactsService(toolchain, processes, "c.typedefFacts")
    fun requireCurrent(snapshot: ProjectSnapshot) = compiler.requireCurrent(snapshot)
    fun analyze(live: ProjectSnapshot, candidate: ProjectSnapshot, selectedFile: Path, offset: Int, preservedSpelling: String? = null): CTypedefCompilerResult {
        return when (val result = compiler.analyze(live, candidate)) {
            is CBoundedTranslationUnitCompilerResult.Unavailable -> CTypedefCompilerResult.Unavailable(result.diagnostic)
            is CBoundedTranslationUnitCompilerResult.Available -> {
                val bundle = result.facts
                val selected = bundle.logicalPaths.entries.singleOrNull { it.value == selectedFile }?.key
                    ?: return CTypedefCompilerResult.Unavailable(Diagnostic("Selected source is outside captured compiler inputs",
                        Diagnostic.Severity.ERROR, code = "c.typedefFacts.selectionUnavailable",
                        evidence = DiagnosticEvidence.COMPILER, category = DiagnosticCategory.SAFETY))
                val readout = if (selectedFile.toString().endsWith(".c") && selected in bundle.asts.keys) {
                    CTypedefRenameFactsDecoder.decodeSource(bundle.asts, bundle.sources, selected, offset, bundle.asts.keys, preservedSpelling)
                } else CTypedefRenameFactsDecoder.decode(bundle.asts, bundle.sources, selected, offset, bundle.asts.keys, preservedSpelling)
                when (readout) {
                    is CTypedefReadout.Refused -> CTypedefCompilerResult.Unavailable(Diagnostic(
                        "Compiler rename closure refused: ${readout.reason}", Diagnostic.Severity.ERROR,
                        code = "c.typedefFacts.${readout.reason.name.lowercase()}",
                        evidence = DiagnosticEvidence.COMPILER, category = DiagnosticCategory.SAFETY), readout.reason)
                    is CTypedefReadout.Found -> {
                        val facts = readout.facts
                        fun relative(path: Path): Path = bundle.logicalPaths.getValue(path)
                        CTypedefCompilerResult.Available(result.snapshotHash, CTypedefRenameFacts(facts.name,
                            facts.underlyingQualified, facts.underlyingCanonical,
                            facts.observations.map { it.copy(translationUnit = relative(it.translationUnit),
                                site = it.site.copy(file = relative(it.site.file))) },
                            facts.typeObservations.map { it.copy(translationUnit = relative(it.translationUnit)) },
                            facts.otherObservations.map { it.copy(translationUnit = relative(it.translationUnit), file = relative(it.file)) },
                            facts.otherOrdinaryNames, facts.translationUnits.map(::relative).toSet(),
                            facts.sourceHashes.mapKeys { relative(it.key) }), result.context)
                    }
                }
            }
        }
    }
}

internal sealed interface CTypedefNamespacePreflight {
    data object OutsideTypedefSelection : CTypedefNamespacePreflight
    data object CapturedTypedefDestination : CTypedefNamespacePreflight
    data class Unavailable(val diagnostic: Diagnostic) : CTypedefNamespacePreflight
    data class Refused(val diagnostic: Diagnostic) : CTypedefNamespacePreflight
}

/** Application authority for one independently reconstructed complete typedef family. */
internal class CManagedTypedefRenameContext(private val toolchain: ClangSemanticToolchain,
    private val processes: ExternalSemanticProcessManager) {
    private val facts = CTypedefFactsService(toolchain, processes)
    private val diagnostics = CCompilerDiagnostics(toolchain, processes)
    private data class Proof(val plan: PatchPlan, val beforeFacts: CTypedefRenameFacts,
        val context: CBoundedTranslationUnitCompilationContext, val file: Path, val replacement: String)

    /** Only compiler-proven typedef selections may bypass the ordinary-identifier collision policy. */
    fun namespacePreflight(before: ProjectSnapshot, file: Path, line: Int, character: Int,
        destination: String): CTypedefNamespacePreflight = try {
        val source = before.files.singleOrNull { it.path == file && it.languageId == "c" }
        if (source == null || (!file.toString().endsWith(".h") && !file.toString().endsWith(".c")) ||
            source.content.length > 1_048_576) {
            CTypedefNamespacePreflight.OutsideTypedefSelection
        } else {
            val tokens = CTokenizer().tokenize(source.content)
            val index = tokens.indexOfFirst { it.line == line + 1 && it.column == character }
            if (!looksLikeAliasDeclaration(tokens, index)) {
                CTypedefNamespacePreflight.OutsideTypedefSelection
            } else {
                val offset = TextEdits.offsetOf(source.content, SourcePosition(line, character))
                when (val result = facts.analyze(before, before, file, offset)) {
                    is CTypedefCompilerResult.Unavailable -> if (file.toString().endsWith(".c") &&
                        result.refusal != CTypedefFactsRefusal.SELECTION_UNAVAILABLE) {
                        CTypedefNamespacePreflight.Refused(result.diagnostic)
                    } else CTypedefNamespacePreflight.Unavailable(result.diagnostic)
                    is CTypedefCompilerResult.Available -> {
                        require(result.snapshotHash == before.hash && result.facts.name == tokens[index].text)
                        if (destination in result.facts.otherOrdinaryNames) CTypedefNamespacePreflight.Refused(Diagnostic(
                            "Rename destination '$destination' already belongs to the captured C ordinary lookup space",
                            Diagnostic.Severity.ERROR, code = "c.renameTypedefTargetPresent", evidence = DiagnosticEvidence.COMPILER,
                            category = DiagnosticCategory.SAFETY))
                        else CTypedefNamespacePreflight.CapturedTypedefDestination
                    }
                }
            }
        }
    } catch (_: Exception) {
        CTypedefNamespacePreflight.Unavailable(Diagnostic("Compiler typedef namespace authority is unavailable",
            Diagnostic.Severity.ERROR, code = "c.typedefFacts.selectionUnavailable", evidence = DiagnosticEvidence.COMPILER,
            category = DiagnosticCategory.SAFETY))
    }

    /** Produce edits from native declaration/type bindings when clangd rejects a legal C lookup-space destination. */
    fun compilerNamespacePreview(before: ProjectSnapshot, args: Map<String, String>): PatchPlan {
        require(isTypedefSelection(before, args))
        val file = Path.of(requireNotNull(args["file"]))
        val source = before.files.single { it.path == file }
        val position = SourcePosition(requireNotNull(args["line"]).toInt(), requireNotNull(args["character"]).toInt())
        val captured = available(facts.analyze(before, before, file, TextEdits.offsetOf(source.content, position)), before).facts
        val replacement = requireNotNull(args["newName"])
        require(captured.name == args["symbol"] && replacement !in captured.otherOrdinaryNames)
        val edit = workspaceEdit(before, captured, replacement)
        return preview(before, PatchPlan(operation = "renameSymbol", snapshotHash = before.hash,
            confidence = 1.0, requiresUserApproval = true, summary = "C typedef rename from compiler-owned declaration and type bindings",
            affectedFiles = edit.affectedFiles(), workspaceEdit = edit, evidence = RefactoringEvidence.COMPILER_PROVEN,
            riskLevel = RiskLevel.MEDIUM, warnings = listOf(
                "Preview uses captured Clang typedef bindings after clangd refused this C lookup-space destination")), args)
    }

    private fun workspaceEdit(before: ProjectSnapshot, captured: CTypedefRenameFacts, replacement: String): WorkspaceEdit {
        val proposal = ExternalWorkspaceEditProposal("clang-typedef-binding-v1", toolchain.provenance.clangVersion,
            captured.sites.groupBy { it.file }.entries.sortedBy { it.key.toString() }.map { (path, sites) ->
                val content = before.files.single { it.path == path }.content
                ExternalFileEditProposal.Modify(path, sites.map { site -> TextEdit(SourceRange(
                    TextEdits.positionForOffset(content, site.span.start), TextEdits.positionForOffset(content, site.span.endExclusive)), replacement) })
            })
        val normalized = ExternalWorkspaceEditNormalizer().normalize(before, proposal) as? ExternalWorkspaceEditNormalization.Accepted
            ?: error("Independent compiler typedef ranges cannot be normalized")
        return normalized.normalized.workspaceEdit
    }

    fun preview(before: ProjectSnapshot, plan: PatchPlan, args: Map<String, String>): PatchPlan = try {
        prove(before, plan, args).plan
    } catch (failure: CTypedefAuthorityFailure) { throw failure }
      catch (failure: Exception) { typedefFailure("c.typedef.authorityUnavailable",
          failure.message ?: "Complete typedef family authority is unavailable") }

    fun gate(before: ProjectSnapshot, plan: PatchPlan): DiagnosticsGate = try {
        val lease = requireNotNull(plan.authorityLease)
        require(lease.kind == LEASE_KIND && lease.operation == "renameSymbol" && lease.snapshotHash == before.hash)
        val args = listOf("selectedName", "newName", "file", "line", "character").associate { key ->
            (if (key == "selectedName") "symbol" else key) to requireNotNull(lease.attributes["c.typedef.$key"])
        }
        val proof = prove(before, plan, args)
        require(proof.plan.authorityLease == lease) { "Retained declaration, consumer, context or staged edit authority differs" }
        val declaration = proof.beforeFacts.sites.single { it.kind == CTypedefSiteKind.DECLARATION && it.file == proof.file }
        DiagnosticsGate.enabled(GATE_ID) { candidate ->
            require(candidate.workspace.root.toAbsolutePath().normalize() == before.workspace.root.toAbsolutePath().normalize() &&
                candidate.copy().hash == candidate.hash && candidate.hash in setOf(before.hash, lease.evidenceHash)) {
                "c.typedef.authorityInvalid: candidate differs from the two approved images"
            }
            val live = if (runCatching { facts.requireCurrent(before) }.isSuccess) before else candidate
            val observed = available(facts.analyze(live, candidate, proof.file, declaration.span.start, proof.beforeFacts.name), candidate)
            require(observed.context.identity == proof.context.identity) { "Captured TU compiler contexts changed" }
            if (candidate.hash == before.hash) require(identity(observed.facts) == identity(proof.beforeFacts))
            else require(CTypedefRenameBinding.check(proof.beforeFacts, observed.facts, proof.replacement) == CTypedefTransition.Verified)
            val result = evaluate(candidate, proof.context)
            facts.requireCurrent(live)
            require(CCompilationSnapshotCapture.compilerIsCurrent(toolchain))
            result
        }
    } catch (failure: Exception) {
        DiagnosticsGate.enabled(GATE_ID) { error("c.typedef.authorityInvalid: ${failure.message}") }
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
            "Compiler typedef/type closure does not reproduce proposed edits"
        }
        val staged = WorkspaceEditSimulator.apply(before, plan.workspaceEdit)
        val declaration = original.facts.sites.single { it.kind == CTypedefSiteKind.DECLARATION && it.file == file }
        val after = available(facts.analyze(before, staged, file, declaration.span.start, name), staged)
        require(after.context.identity == original.context.identity &&
            CTypedefRenameBinding.check(original.facts, after.facts, replacement) == CTypedefTransition.Verified) {
            "Staged declaration identity, namespace, consumer set or exact reference family differs"
        }
        val beforeDiagnostics = evaluate(before, original.context)
        val afterDiagnostics = evaluate(staged, original.context)
        facts.requireCurrent(before)
        require(CCompilationSnapshotCapture.compilerIsCurrent(toolchain))
        val lease = OperationAuthorityLease(kind = LEASE_KIND, operation = "renameSymbol", snapshotHash = before.hash,
            evidenceHash = staged.hash, workspaceEditSha256 = WorkspaceEditIdentity.sha256(plan.workspaceEdit),
            requiredFileEvidence = listOf(OperationAuthorityFileEvidence("c.typedef.compilationDatabase",
                original.context.databasePath, original.context.databaseSha256)),
            attributes = mapOf(
                "c.typedef.selectedName" to name, "c.typedef.newName" to replacement,
                "c.typedef.file" to file.toString(), "c.typedef.line" to line.toString(),
                "c.typedef.character" to character.toString(),
                "c.typedef.translationUnitCount" to original.facts.translationUnits.size.toString(),
                "c.typedef.underlyingCanonical" to original.facts.underlyingCanonical,
                "c.typedef.preservedSiteCount" to original.facts.otherObservations.map { it.file to it.span }.distinct().size.toString(),
                "c.typedef.validationPolicy" to "iso-pedantic-errors-v1",
                "c.typedef.physicalSiteCount" to original.facts.sites.size.toString(),
                "c.typedef.bindingSha256" to identity(original.facts),
                "c.typedef.compilationContextSha256" to original.context.identity,
                "c.compilationDatabasePath" to original.context.databasePath.toString(),
                "c.compilationDatabaseSha256" to original.context.databaseSha256,
                "c.nativeRuntimeIdentity" to requireNotNull(toolchain.provenance.nativeRuntimeIdentity),
                "c.toolchainIdentity" to toolchain.provenance.toolchainIdentity(),
                "c.clangdSha256" to toolchain.provenance.evidence.single { it.role == "clangd-executable" }.sha256,
            ))
        return Proof(plan.copy(authorityLease = lease, diagnosticsBefore = beforeDiagnostics,
            diagnosticsAfterPreview = afterDiagnostics), original.facts, original.context, file, replacement)
    }
    private fun available(result: CTypedefCompilerResult, snapshot: ProjectSnapshot): CTypedefCompilerResult.Available = when (result) {
        is CTypedefCompilerResult.Available -> { require(result.snapshotHash == snapshot.hash); result }
        is CTypedefCompilerResult.Unavailable -> throw CTypedefAuthorityFailure(result.diagnostic)
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
    private fun identity(facts: CTypedefRenameFacts): String = typedefFingerprint(
        listOf(facts.name, facts.underlyingQualified, facts.underlyingCanonical) + facts.otherOrdinaryNames.sorted() +
        facts.translationUnits.map { it.toString() }.sorted() +
        facts.typeObservations.flatMap { listOf(it.translationUnit.toString(), it.astPath, it.property, it.qualified, it.desugared.orEmpty()) } +
        facts.observations.flatMap { listOf(it.translationUnit.toString(), it.astPath, it.site.file.toString(),
            it.site.span.start.toString(), it.site.span.endExclusive.toString(), it.site.kind.name) } +
        facts.otherObservations.flatMap { listOf(it.translationUnit.toString(), it.astPath, it.targetPath, it.space.name,
            it.file.toString(), it.span.start.toString(), it.span.endExclusive.toString()) } +
        facts.sourceHashes.entries.sortedBy { it.key.toString() }.flatMap { listOf(it.key.toString(), it.value) })
    companion object {
        const val LEASE_KIND = "c.rename-symbol-typedef-v1"
        private const val GATE_ID = "clang-c-typedef-v1"

        /** Dispatch hint only. Native declaration/type closure is always required for authority. */
        fun isTypedefSelection(before: ProjectSnapshot, args: Map<String, String>): Boolean = runCatching {
            val file = Path.of(requireNotNull(args["file"]))
            val source = before.files.single { it.path == file && it.languageId == "c" }
            if ((!file.toString().endsWith(".h") && !file.toString().endsWith(".c")) ||
            source.content.length > 1_048_576) return@runCatching false
            val line = requireNotNull(args["line"]).toInt()
            val character = requireNotNull(args["character"]).toInt()
            val tokens = CTokenizer().tokenize(source.content)
            looksLikeAliasDeclaration(tokens, tokens.indexOfFirst { it.line == line + 1 && it.column == character })
        }.getOrDefault(false)

        private fun looksLikeAliasDeclaration(tokens: List<CToken>, index: Int): Boolean = index > 0 &&
            tokens.take(index).takeLastWhile { it.text !in setOf(";", "{", "}") }.any { it.text == "typedef" }
    }
}
