package org.refactorkit.c

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.ExternalFileEditProposal
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.ExternalWorkspaceEditNormalization
import org.refactorkit.core.ExternalWorkspaceEditNormalizer
import org.refactorkit.core.ExternalWorkspaceEditProposal
import org.refactorkit.core.OperationAuthorityFileEvidence
import org.refactorkit.core.OperationAuthorityLease
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEditIdentity
import org.refactorkit.core.WorkspaceEditSimulator
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Application authority for one compiler-closed source-owned variable binding. */
internal class CManagedVariableRenameContext(
    private val toolchain: ClangSemanticToolchain,
    private val processes: ExternalSemanticProcessManager,
) {
    private val facts = CFunctionFactsService(toolchain, processes)
    private val diagnostics = CCompilerDiagnostics(toolchain, processes)
    private data class Proof(val plan: PatchPlan, val beforeFacts: CVariableRenameFacts,
        val file: Path, val replacement: String, val flags: List<String>)

    fun preview(before: ProjectSnapshot, plan: PatchPlan, args: Map<String, String>): PatchPlan = prove(before, plan, args).plan

    fun gate(before: ProjectSnapshot, plan: PatchPlan): DiagnosticsGate = try {
        val lease = requireNotNull(plan.authorityLease)
        require(lease.kind == LEASE_KIND && lease.operation == "renameSymbol" && lease.snapshotHash == before.hash)
        val args = listOf("selectedName", "newName", "file", "line", "character").associate { key ->
            (if (key == "selectedName") "symbol" else key) to requireNotNull(lease.attributes["c.renameVariable.$key"])
        }
        val proof = prove(before, plan, args)
        require(proof.plan.authorityLease == lease) { "Retained variable binding or native authority differs from the preview" }
        val root = before.workspace.root.toAbsolutePath().normalize()
        DiagnosticsGate.enabled(GATE_ID) { candidate ->
            require(candidate.workspace.root.toAbsolutePath().normalize() == root && candidate.copy().hash == candidate.hash)
            require(candidate.hash == before.hash || candidate.hash == lease.evidenceHash) { "Unapproved variable rename image" }
            val observed = if (candidate.hash == before.hash || !CCompilationSnapshotCapture.isCurrent(before)) {
                available(facts.analyzeVariable(candidate, proof.file, proof.beforeFacts.declaration.start), candidate)
            } else {
                available(facts.analyzeVariablePreview(before, candidate, proof.file, proof.beforeFacts.declaration.start), candidate)
            }
            if (candidate.hash == before.hash) {
                require(identity(observed) == identity(proof.beforeFacts)) { "Captured variable binding changed" }
            } else require(CVariableRenameBinding.check(proof.beforeFacts, observed, proof.replacement) == CVariableRenameTransition.Verified) {
                "Staged variable binding no longer reproduces the approved rename"
            }
            evaluate(candidate, proof.file, proof.flags)
        }
    } catch (failure: Exception) {
        DiagnosticsGate.enabled(GATE_ID) { error("c.renameVariable.authorityInvalid: ${failure.message}") }
    }

    private fun prove(before: ProjectSnapshot, plan: PatchPlan, args: Map<String, String>): Proof {
        require(plan.operation == "renameSymbol" && plan.status == PatchStatus.PREVIEW && plan.requiresUserApproval &&
            plan.evidence == RefactoringEvidence.COMPILER_PROVEN && plan.snapshotHash == before.hash && before.copy().hash == before.hash)
        val file = Path.of(requireNotNull(args["file"]))
        require(!file.isAbsolute && file == file.normalize() && !file.startsWith(".."))
        val source = before.files.single()
        require(source.path == file && source.languageId == "c")
        val name = requireNotNull(args["symbol"])
        val replacement = requireNotNull(args["newName"])
        require(replacement != name && replacement.length in 1..128 && replacement.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
        val line = requireNotNull(args["line"]).toInt()
        val character = requireNotNull(args["character"]).toInt()
        val offset = TextEdits.offsetOf(source.content, SourcePosition(line, character))
        val byteOffset = source.content.substring(0, offset).toByteArray(Charsets.UTF_8).size
        val original = available(facts.analyzeVariable(before, file, byteOffset), before)
        require(original.name == name)
        val occurrences = (listOf(original.declaration) + original.references).sortedBy { it.start }
        val edits = occurrences.map { span -> TextEdit(SourceRange(
            TextEdits.positionForOffset(source.content, span.start), TextEdits.positionForOffset(source.content, span.endExclusive)), replacement) }
        val normalized = ExternalWorkspaceEditNormalizer().normalize(before, ExternalWorkspaceEditProposal(
            "clang-variable-binding-v1", toolchain.provenance.clangVersion, listOf(ExternalFileEditProposal.Modify(file, edits)),
        )) as? ExternalWorkspaceEditNormalization.Accepted ?: error("Independent variable ranges could not be normalized")
        require(normalized.normalized.workspaceEdit == plan.workspaceEdit) { "Compiler declaration/reference closure does not reproduce clangd edits" }
        val staged = WorkspaceEditSimulator.apply(before, plan.workspaceEdit)
        val after = available(facts.analyzeVariablePreview(before, staged, file, original.declaration.start), staged)
        require(CVariableRenameBinding.check(original, after, replacement) == CVariableRenameTransition.Verified) {
            "The staged declaration, type, scope or reference family differs"
        }
        val database = before.auxiliaryFiles.single { it.languageId == "c-compilation-database" }
        val databaseSha = sha(database.content.toByteArray(Charsets.UTF_8))
        val flags = compilerFlags(before, file, database.content)
        val beforeDiagnostics = evaluate(before, file, flags)
        val afterDiagnostics = evaluate(staged, file, flags)
        val runtime = requireNotNull(toolchain.provenance.nativeRuntimeIdentity)
        require(CCompilationSnapshotCapture.compilerIsCurrent(toolchain))
        val lease = OperationAuthorityLease(kind = LEASE_KIND, operation = "renameSymbol", snapshotHash = before.hash,
            evidenceHash = staged.hash, workspaceEditSha256 = WorkspaceEditIdentity.sha256(plan.workspaceEdit),
            requiredFileEvidence = listOf(OperationAuthorityFileEvidence("c.renameVariable.compilationDatabase",
                database.path, databaseSha)),
            attributes = mapOf(
                "c.renameVariable.selectedName" to name, "c.renameVariable.newName" to replacement,
                "c.renameVariable.file" to file.toString(), "c.renameVariable.line" to line.toString(),
                "c.renameVariable.character" to character.toString(), "c.renameVariable.kind" to original.kind.name,
                "c.renameVariable.bindingSha256" to identity(original), "c.nativeRuntimeIdentity" to runtime,
                "c.toolchainIdentity" to toolchain.provenance.toolchainIdentity(),
                "c.clangdSha256" to toolchain.provenance.evidence.single { it.role == "clangd-executable" }.sha256,
                "c.compilationDatabasePath" to database.path.toString(), "c.compilationDatabaseSha256" to databaseSha,
            ))
        return Proof(plan.copy(authorityLease = lease, diagnosticsBefore = beforeDiagnostics,
            diagnosticsAfterPreview = afterDiagnostics), original, file, replacement, flags)
    }

    private fun compilerFlags(before: ProjectSnapshot, file: Path, captured: String): List<String> {
        val root = before.workspace.root.toAbsolutePath().normalize()
        val parsed = CCompilationDatabaseParser(CCompilationDatabasePolicy(requireArgumentsArray = true))
            .parse(root, captured.toByteArray(Charsets.UTF_8)) as? CCompilationDatabaseDiscovery.Available
            ?: error("Captured compilation database is unavailable")
        val unit = parsed.database.units.single()
        require(unit.standard in setOf("c11", "c17") && unit.file == root.resolve(file) &&
            Path.of(unit.arguments.first()).toAbsolutePath().normalize() == toolchain.clangExecutable.toAbsolutePath().normalize())
        CClangdLaunchContext.capture(root, toolchain.clangExecutable).use { context ->
            require(context.isCurrent() && CCompilationSnapshotCapture.isCurrent(before))
            val normalized = Json.parseToJsonElement(Files.readString(context.root.resolve("compile_commands.json"))) as JsonArray
            val entry = normalized.single() as JsonObject
            val arguments = (entry.getValue("arguments") as JsonArray).map { (it as JsonPrimitive).content }
            require(arguments.last() == root.resolve(file).toString())
            return arguments.drop(1).dropLast(1)
        }
    }

    private fun evaluate(snapshot: ProjectSnapshot, file: Path, flags: List<String>): List<Diagnostic> {
        val available = diagnostics.analyze(snapshot, file, flags) as? CDiagnosticsResult.Available
            ?: error("Exact captured variable compiler diagnostics are unavailable")
        require(available.diagnostics.none { it.severity == Diagnostic.Severity.ERROR }) { "Compiler diagnostics contain errors" }
        return available.diagnostics
    }

    private fun available(result: CVariableRenameFactsResult, snapshot: ProjectSnapshot): CVariableRenameFacts = when (result) {
        is CVariableRenameFactsResult.Available -> { require(result.snapshotHash == snapshot.hash); result.facts }
        is CVariableRenameFactsResult.Unavailable -> error("${result.diagnostic.code}: ${result.diagnostic.message}")
    }

    private fun identity(facts: CVariableRenameFacts): String {
        val fields = listOf(facts.sourcePath.toString(), facts.sourceSha256, facts.name, facts.type, facts.kind.name,
            facts.declaration.toString(), facts.declarationAstPath, facts.scopeAstPath) +
            facts.references.map(CFunctionByteSpan::toString) + facts.referenceAstPaths
        return sha(fields.joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" }.toByteArray(Charsets.UTF_8))
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val LEASE_KIND = "c.rename-symbol-variable-v1"
        private const val GATE_ID = "clang-c-rename-variable-v1"
    }
}
