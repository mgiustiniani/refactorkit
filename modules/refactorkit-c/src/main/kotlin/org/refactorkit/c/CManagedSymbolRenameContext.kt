package org.refactorkit.c

import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.OperationAuthorityEvidenceCompleteness
import org.refactorkit.core.OperationAuthorityFileEvidence
import org.refactorkit.core.OperationAuthorityLease
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEditIdentity
import org.refactorkit.core.WorkspaceEditSimulator
import java.nio.file.Path
import java.security.MessageDigest

/** Managed authority for one selected, closed, source-owned static function family. */
internal class CManagedSymbolRenameContext(
    private val toolchain: ClangSemanticToolchain,
    private val processes: ExternalSemanticProcessManager,
) {
    private data class Proof(val plan: PatchPlan, val mappingPlan: PatchPlan)

    fun preview(before: ProjectSnapshot, plan: PatchPlan, args: Map<String, String>): PatchPlan {
        if (plan.status != PatchStatus.PREVIEW) return plan
        return try { prove(before, plan, args).plan }
        catch (failure: Exception) {
            if (CManagedTypedefRenameContext.isTypedefSelection(before, args)) {
                return try { CManagedTypedefRenameContext(toolchain, processes).preview(before, plan, args) }
                catch (typedefFailure: CTypedefAuthorityFailure) {
                    plan.copy(warnings = plan.warnings + "Managed typedef Apply unavailable: ${typedefFailure.diagnostic.message}",
                        diagnosticsAfterPreview = plan.diagnosticsAfterPreview + typedefFailure.diagnostic.copy(
                            severity = org.refactorkit.core.Diagnostic.Severity.WARNING))
                }
            }
            try { return CManagedVariableRenameContext(toolchain, processes).preview(before, plan, args) }
            catch (_: Exception) { /* Unsupported bindings keep their existing preview without write authority. */ }
            // Broader symbol kinds keep their existing semantic preview, without write authority.
            try { CManagedHeaderFieldRenameContext(toolchain, processes).preview(before, plan, args) }
            catch (fieldFailure: CHeaderFieldAuthorityFailure) {
                try { CManagedOrdinaryHeaderRenameContext(toolchain, processes).preview(before, plan, args) }
                catch (ordinaryFailure: COrdinaryHeaderAuthorityFailure) {
                    try { CManagedTagRenameContext(toolchain, processes).preview(before, plan, args) }
                    catch (tagFailure: CTagAuthorityFailure) {
                        plan.copy(warnings = plan.warnings + "Managed single-symbol Apply unavailable: ${failure.message}; ${fieldFailure.diagnostic.message}; ${ordinaryFailure.diagnostic.message}; ${tagFailure.diagnostic.message}",
                            diagnosticsAfterPreview = plan.diagnosticsAfterPreview + tagFailure.diagnostic.copy(severity = org.refactorkit.core.Diagnostic.Severity.WARNING))
                    }
                }
            }
        }
    }

    fun gate(before: ProjectSnapshot, plan: PatchPlan): DiagnosticsGate =
        if (plan.authorityLease?.kind == CManagedTypedefRenameContext.LEASE_KIND)
            CManagedTypedefRenameContext(toolchain, processes).gate(before, plan)
        else if (plan.authorityLease?.kind == CManagedVariableRenameContext.LEASE_KIND)
            CManagedVariableRenameContext(toolchain, processes).gate(before, plan)
        else if (plan.authorityLease?.kind == CManagedHeaderFieldRenameContext.LEASE_KIND)
            CManagedHeaderFieldRenameContext(toolchain, processes).gate(before, plan)
        else if (plan.authorityLease?.kind == CManagedOrdinaryHeaderRenameContext.LEASE_KIND)
            CManagedOrdinaryHeaderRenameContext(toolchain, processes).gate(before, plan)
        else if (plan.authorityLease?.kind == CManagedTagRenameContext.LEASE_KIND)
            CManagedTagRenameContext(toolchain, processes).gate(before, plan)
        else try {
        val lease = requireNotNull(plan.authorityLease) { "retained single-symbol lease is missing" }
        require(lease.kind == LEASE_KIND && lease.operation == OPERATION &&
            lease.snapshotHash == before.hash && lease.evidenceCompleteness == OperationAuthorityEvidenceCompleteness.COMPLETE)
        val args = mapOf(
            "symbol" to requireNotNull(lease.attributes["c.renameSymbol.selectedName"]),
            "newName" to requireNotNull(lease.attributes["c.renameSymbol.newName"]),
            "file" to requireNotNull(lease.attributes["c.renameSymbol.file"]),
            "line" to requireNotNull(lease.attributes["c.renameSymbol.line"]),
            "character" to requireNotNull(lease.attributes["c.renameSymbol.character"]),
        )
        val rebuilt = prove(before, plan, args)
        val expected = requireNotNull(rebuilt.plan.authorityLease)
        require(lease.attributes == expected.attributes && lease.evidenceHash == expected.evidenceHash &&
            lease.workspaceEditSha256 == expected.workspaceEditSha256 &&
            lease.requiredFileEvidenceSha256 == expected.requiredFileEvidenceSha256 &&
            lease.requiredClasspathEvidence == expected.requiredClasspathEvidence) {
            "selected function, compiler inputs or staged edit differ from the retained preview"
        }
        // This is a real separately reconstructed mapping operation, with its own lease.
        // Neither the public single-symbol plan nor either lease is relabelled as prefix rename.
        val compilerGate = CManagedRenamePrefixContext(toolchain, processes).gate(before, rebuilt.mappingPlan)
        DiagnosticsGate.enabled(GATE_ID) { candidate -> requireNotNull(compilerGate.provider).invoke(candidate) }
    } catch (failure: Exception) {
        DiagnosticsGate.enabled(GATE_ID) { error("c.renameSymbol.authorityInvalid: ${failure.message}") }
    }

    private fun prove(before: ProjectSnapshot, plan: PatchPlan, args: Map<String, String>): Proof {
        require(plan.operation == OPERATION && plan.status == PatchStatus.PREVIEW && plan.requiresUserApproval &&
            plan.evidence == RefactoringEvidence.COMPILER_PROVEN && plan.snapshotHash == before.hash &&
            before.copy().hash == before.hash) { "an immutable compiler-proven single-symbol preview is required" }
        val file = Path.of(requireNotNull(args["file"]))
        val name = requireNotNull(args["symbol"])
        val replacement = requireNotNull(args["newName"])
        val line = requireNotNull(args["line"]).toInt()
        val character = requireNotNull(args["character"]).toInt()
        require(!file.isAbsolute && file.normalize() == file && !file.startsWith(".."))
        val source = before.files.single()
        require(source.languageId == "c" && source.path == file) { "one captured C source is required" }
        val facts = (CFunctionFactsService(toolchain, processes).analyze(before, file, name) as? CFunctionFactsResult.Available)?.facts
            ?: error("selected source-owned function facts are unavailable")
        require(facts.family == CDirectCallFamilyEvidence.COMPLETE_FILE_STATIC) {
            "only a complete fully-prototyped internal direct-call family is admitted"
        }
        val offset = TextEdits.offsetOf(source.content, SourcePosition(line, character))
        val byteOffset = source.content.substring(0, offset).toByteArray(Charsets.UTF_8).size
        require(facts.declarations.any { it.nameSpan.start == byteOffset }) {
            "the selected token is not a declaration in the captured function family"
        }
        val mapping = mapOf(name to replacement)
        val mappingContext = CManagedRenamePrefixContext(toolchain, processes)
        require(mappingContext.preflight(before, mapping) == null) { "captured mapping compiler context is unavailable" }
        val rawMapping = CRenamePrefixPlanner(toolchain, processes).use { planner ->
            planner.start(before)
            planner.preview(before, mapping)
        }
        require(rawMapping.status == PatchStatus.PREVIEW && rawMapping.workspaceEdit == plan.workspaceEdit) {
            "independent compiler reference closure does not reproduce the selected-symbol edits"
        }
        val mappingPlan = mappingContext.preview(before, rawMapping, mapping)
        require(mappingPlan.status == PatchStatus.PREVIEW) { mappingPlan.summary }
        val mappingLease = requireNotNull(mappingPlan.authorityLease)
        val staged = WorkspaceEditSimulator.apply(before, plan.workspaceEdit)
        require(staged.hash == mappingLease.evidenceHash)
        val database = mappingLease.requiredFileEvidence.single()
        val lease = OperationAuthorityLease(
            kind = LEASE_KIND, operation = OPERATION, snapshotHash = before.hash,
            evidenceHash = staged.hash, workspaceEditSha256 = WorkspaceEditIdentity.sha256(plan.workspaceEdit),
            requiredFileEvidence = listOf(OperationAuthorityFileEvidence(
                kind = "c.renameSymbol.compilationDatabase", path = database.path,
                expectedContentSha256 = database.expectedContentSha256,
                attributes = mapOf("c.renameSymbol.compilationDatabase" to database.path.toString()),
            )),
            attributes = mapOf(
                "c.renameSymbol.selectedName" to name, "c.renameSymbol.newName" to replacement,
                "c.renameSymbol.file" to file.toString(), "c.renameSymbol.line" to line.toString(),
                "c.renameSymbol.character" to character.toString(),
                "c.renameSymbol.familySha256" to familyIdentity(facts),
                "c.nativeRuntimeIdentity" to requireNotNull(mappingLease.attributes["c.nativeRuntimeIdentity"]),
                "c.toolchainIdentity" to requireNotNull(mappingLease.attributes["c.toolchainIdentity"]),
                "c.clangdSha256" to requireNotNull(mappingLease.attributes["c.clangdSha256"]),
                "c.compilationDatabasePath" to database.path.toString(),
                "c.compilationDatabaseSha256" to database.expectedContentSha256,
            ),
        )
        return Proof(plan.copy(authorityLease = lease, diagnosticsBefore = mappingPlan.diagnosticsBefore,
            diagnosticsAfterPreview = mappingPlan.diagnosticsAfterPreview), mappingPlan)
    }

    private fun familyIdentity(facts: CFunctionFamilyFacts): String {
        val fields = listOf(facts.name, facts.sourcePath.toString(), facts.sourceSha256, facts.family.name) +
            facts.declarations.map { declaration ->
                "${declaration.nameSpan.start}:${declaration.nameSpan.endExclusive}:${declaration.isDefinition}:" +
                    declaration.parameters.joinToString(",") { "${it.name}:${it.span.start}:${it.span.endExclusive}" }
            } + facts.calls.map { call ->
                "${call.span.start}:${call.span.endExclusive}:" +
                    call.arguments.joinToString(",") { "${it.span.start}:${it.span.endExclusive}" }
            }
        val encoded = fields.joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" }.toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val OPERATION = "renameSymbol"
        const val LEASE_KIND = "c.rename-symbol-function-v1"
        const val GATE_ID = "clang-c-rename-symbol-function-v1"
    }
}
