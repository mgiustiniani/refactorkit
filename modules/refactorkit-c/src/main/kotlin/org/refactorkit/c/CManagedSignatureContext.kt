package org.refactorkit.c

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.WorkspaceEditIdentity
import java.nio.file.Path

/** Application gate retaining one exact compiler-bound signature change across the core transaction. */
internal class CManagedSignatureContext(
    private val toolchain: ClangSemanticToolchain,
    private val processManager: ExternalSemanticProcessManager,
) {
    fun gate(before: ProjectSnapshot, plan: PatchPlan): DiagnosticsGate {
        val renaming = plan.operation == "changeSignature"
        val insertion = plan.operation == "addParameter"
        val prefix = when {
            renaming -> "c.signatureRename"
            insertion -> "c.signatureAdd"
            else -> "c.signatureReorder"
        }
        val gateId = when {
            renaming -> "clang-signature-rename-v1"
            insertion -> "clang-signature-add-v1"
            else -> "clang-signature-reorder-v1"
        }
        val expectedKind = when {
            renaming -> "c.signature-rename-v1"
            insertion -> "c.signature-add-v1"
            else -> "c.signature-reorder-v1"
        }
        val expectedOperation = when {
            renaming -> "changeSignature"
            insertion -> "addParameter"
            else -> "reorderParameters"
        }
        val lease = plan.authorityLease
        if (plan.status != PatchStatus.PREVIEW || !plan.requiresUserApproval || plan.evidence != RefactoringEvidence.COMPILER_PROVEN ||
            lease?.kind != expectedKind || lease.operation != expectedOperation ||
            plan.operation != lease.operation || lease.snapshotHash != before.hash ||
            lease.workspaceEditSha256 != WorkspaceEditIdentity.sha256(plan.workspaceEdit)) return refusedGate(prefix, gateId)
        val currentRuntimeIdentity = toolchain.provenance.nativeRuntimeIdentity?.takeIf { identity ->
            (toolchain.runtime as? ClangToolchainRuntimeCaptureResult.Captured)?.facts?.identity == identity
        }
        if (currentRuntimeIdentity == null || lease.attributes["c.nativeRuntimeIdentity"] != currentRuntimeIdentity) {
            return refusedGate(prefix, gateId)
        }
        val file: Path
        val functionName: String
        try {
            file = Path.of(requireNotNull(lease.attributes["c.file"]))
            functionName = requireNotNull(lease.attributes["c.functionName"])
            // Rebuild the native preview, so a fabricated lease cannot authorize unrelated edits.
            val rebuilt = CSignaturePlanner(toolchain, processManager).use { planner ->
                if (renaming) {
                    val oldParameter = requireNotNull(lease.attributes["c.oldParameter"])
                    val newParameter = requireNotNull(lease.attributes["c.newParameter"])
                    val rawOrdinal = requireNotNull(lease.attributes["c.parameterOrdinal"])
                    val ordinal = rawOrdinal.toInt()
                    require(ordinal >= 0 && ordinal.toString() == rawOrdinal)
                    planner.start(before)
                    planner.renameParameter(before, file, functionName, oldParameter, newParameter)
                } else if (insertion) {
                    val parameterName = requireNotNull(lease.attributes["c.parameterName"])
                    val rawOrdinal = requireNotNull(lease.attributes["c.parameterOrdinal"])
                    val ordinal = rawOrdinal.toInt()
                    require(ordinal.toString() == rawOrdinal)
                    val arguments = decodeExplicitCallArguments(requireNotNull(lease.attributes["c.callArguments"]))
                    planner.addParameter(before, file, functionName, parameterName, ordinal, arguments)
                } else {
                    val mapping = requireNotNull(lease.attributes["c.originalIndices"])
                    require(mapping.length <= 255)
                    val order = mapping.split(',').map(String::toInt)
                    require(order.size <= 64 && order.joinToString(",") == mapping)
                    planner.reorderParameters(before, file, functionName, order)
                }
            }
            require(rebuilt.status == PatchStatus.PREVIEW && rebuilt.authorityLease == lease)
        } catch (_: Exception) { return refusedGate(prefix, gateId) }
        val root = before.workspace.root.toAbsolutePath().normalize()
        val service = CFunctionFactsService(toolchain, processManager)
        return DiagnosticsGate.enabled(gateId) { candidate ->
            require(candidate.workspace.root.toAbsolutePath().normalize() == root && candidate.copy().hash == candidate.hash) {
                "$prefix.authorityInvalid: candidate workspace or immutable content identity differs"
            }
            val result = when (candidate.hash) {
                before.hash -> service.analyze(candidate, file, functionName)
                lease.evidenceHash -> if (CCompilationSnapshotCapture.isCurrent(before)) {
                    service.analyzePreview(before, candidate, file, functionName)
                } else {
                    // Post-commit/rollback checks still require exact saved candidate bytes and configuration.
                    service.analyze(candidate, file, functionName)
                }
                else -> error("$prefix.authorityInvalid: candidate differs from the approved before/after images")
            }
            when (result) {
                is CFunctionFactsResult.Available -> {
                    require(result.snapshotHash == candidate.hash) { "$prefix.authorityInvalid: compiler facts identify a different candidate" }
                    if (renaming) {
                        val ordinal = requireNotNull(lease.attributes["c.parameterOrdinal"]).toInt()
                        val expectedName = if (candidate.hash == before.hash) {
                            requireNotNull(lease.attributes["c.oldParameter"])
                        } else {
                            requireNotNull(lease.attributes["c.newParameter"])
                        }
                        val stagedNames = result.facts.declarations.map { it.parameters.getOrNull(ordinal)?.name }
                        require(stagedNames.isNotEmpty() && stagedNames.all { it == expectedName }) {
                            "$prefix.authorityInvalid: compiler facts resolved parameter ordinal $ordinal as $stagedNames instead of '$expectedName'"
                        }
                    }
                    emptyList<Diagnostic>()
                }
                is CFunctionFactsResult.NotFound -> error("$prefix.familyUnavailable: approved function family disappeared")
                is CFunctionFactsResult.Unavailable -> error("${result.diagnostic.code}: ${result.diagnostic.message}")
            }
        }
    }

    private fun refusedGate(prefix: String, gateId: String) = DiagnosticsGate.enabled(gateId) {
        error("$prefix.authorityInvalid: unchanged compiler-bound preview, explicit approval and complete caller mapping are required")
    }
}

/** Bounded typed argument extraction for retained/public application requests; no semantic rules here. */
internal fun decodeExplicitCallArguments(encoded: String): List<CExplicitCallArgument> {
    require(encoded.length <= 2_097_152) { "Caller argument JSON exceeds the input limit" }
    var depth = 0; var quoted = false; var escaped = false; var records = 0; var separators = 0; var fields = 0
    encoded.forEach { character ->
        if (quoted) {
            if (escaped) escaped = false else if (character == '\\') escaped = true else if (character == '"') quoted = false
        } else when (character) {
            '"' -> quoted = true
            '[', '{' -> {
                if (depth == 1 && character == '{') { require(++records <= 8192); fields = 0 }
                require(++depth <= 2) { "Caller argument JSON must be a shallow array of records" }
            }
            ']', '}' -> require(--depth >= 0)
            ',' -> if (depth == 1) require(++separators < 8192) else if (depth == 2) require(++fields <= 2)
        }
    }
    require(depth == 0 && !quoted && !escaped)
    val array = Json.parseToJsonElement(encoded) as? JsonArray ?: error("Caller arguments must be an array")
    require(array.size <= 8192)
    return array.map { element ->
        val value = element as? JsonObject ?: error("Caller argument must be a record")
        require(value.keys == setOf("start", "endExclusive", "expression"))
        fun offset(key: String): Int = requireNotNull((value[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull)
        val expression = requireNotNull((value["expression"] as? JsonPrimitive)?.takeIf { it.isString }?.content)
        require(expression.length <= 4096)
        CExplicitCallArgument(CFunctionByteSpan(offset("start"), offset("endExclusive")), expression)
    }
}
