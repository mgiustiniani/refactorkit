package org.refactorkit.java

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.FileEdit
import org.refactorkit.core.MutationAuthority
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RefactoringRequest
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditSimulator
import java.nio.file.Path

/** Application composition of independently supported saved-source cleanup plans. */
class JavaBatchCleanupPlanner(private val adapter: JavaLanguageAdapter) {
    companion object {
        /** Outer saved-source authority is checked before acquiring an SDK-bound child context. */
        fun snapshotRefusal(snapshot: ProjectSnapshot, expectedSnapshotHash: String?): Diagnostic? =
            if (expectedSnapshotHash == snapshot.hash) null else Diagnostic(
                "batchCleanup requires the exact current saved-project snapshot hash",
                Diagnostic.Severity.ERROR,
                code = "java.batchCleanup.snapshotStale",
            )
    }

    fun preview(request: RefactoringRequest): PatchPlan {
        fun refused(code: String, message: String, diagnostics: List<Diagnostic> = emptyList()) = PatchPlan(
            operation = request.operation,
            status = PatchStatus.REFUSED,
            snapshotHash = request.snapshot.hash,
            confidence = 0.0,
            requiresUserApproval = false,
            summary = message,
            affectedFiles = emptySet(),
            workspaceEdit = WorkspaceEdit(),
            diagnosticsBefore = diagnostics,
            diagnosticsAfterPreview = listOf(Diagnostic(message, Diagnostic.Severity.ERROR, code = code)),
            riskLevel = RiskLevel.HIGH,
            refusalCode = code,
        )

        val formatterProfile = try {
            JavaFormatterProfile.fromArguments(request.arguments)
        } catch (failure: IllegalArgumentException) {
            return refused("java.batchCleanup.argumentsInvalid", failure.message ?: "Incomplete formatter profile identity.")
        }
        val profileArguments = formatterProfile?.arguments().orEmpty()
        if (request.arguments.keys != setOf("actions") + profileArguments.keys) {
            return refused("java.batchCleanup.argumentsInvalid", "batchCleanup accepts actions and one complete selected formatter profile only.")
        }
        val raw = request.arguments["actions"]
            ?: return refused("java.batchCleanup.argumentsInvalid", "batchCleanup requires arguments.actions.")
        if (raw.toByteArray(Charsets.UTF_8).size !in 2..32_768) {
            return refused("java.batchCleanup.argumentsInvalid", "batchCleanup actions exceed the bounded input size.")
        }
        val actions = try {
            val array = Json.parseToJsonElement(raw).jsonArray
            require(array.size in 2..16) { "batchCleanup requires between 2 and 16 actions." }
            array.map { element ->
                val action = element.jsonObject
                require(action.keys == setOf("operation", "arguments") ||
                    action.keys == setOf("operation", "symbol", "arguments")) { "Action fields are not exact." }
                val operationValue = action.getValue("operation").jsonPrimitive
                require(operationValue.isString && operationValue.content.isNotBlank()) { "Action operation is invalid." }
                val symbolValue = action["symbol"]?.jsonPrimitive?.let {
                    require(it.isString)
                    it.content
                }
                val argumentObject = action.getValue("arguments").jsonObject
                val childArguments = argumentObject.mapValues { (_, value) ->
                    val primitive = value as? JsonPrimitive ?: error("Action arguments must be scalar strings.")
                    require(primitive.isString) { "Action arguments must be scalar strings." }
                    primitive.content
                }
                BatchCleanupAction(operationValue.content, symbolValue, childArguments)
            }
        } catch (failure: Exception) {
            return refused("java.batchCleanup.argumentsInvalid", failure.message ?: "batchCleanup actions are invalid.")
        }

        if (formatterProfile != null && actions.none { it.operation == "formatFile" }) {
            return refused("java.batchCleanup.formatterProfileUnused", "A selected formatter profile requires at least one formatFile action.")
        }
        var staged = request.snapshot
        val seenTargets = mutableSetOf<Path>()
        val childPlans = mutableListOf<PatchPlan>()
        for (action in actions) {
            if (action.operation == "changeVisibility") {
                return refused(
                    "java.batchCleanup.constituentHighRisk",
                    "The batch contains high-risk operation changeVisibility; no child plan was applied.",
                )
            }
            if (action.operation !in BATCH_CLEANUP_LOW_RISK_OPERATIONS) {
                return refused(
                    "java.batchCleanup.constituentUnsupported",
                    "The batch contains unsupported operation ${action.operation}; no child plan was applied.",
                )
            }
            val capability = JavaAdapterRegistration.capability(action.operation)
            val descriptor = JavaAdapterRegistration.descriptors().singleOrNull { it.id == action.operation }
            if (capability?.mutationAuthority != MutationAuthority.MANAGED_STABLE ||
                descriptor == null || descriptor.riskLevel != RiskLevel.LOW
            ) {
                return refused(
                    "java.batchCleanup.constituentUnsupported",
                    "Operation ${action.operation} has no independent low-risk managed authority.",
                )
            }
            if (action.symbol != null || action.arguments.keys != setOf("file")) {
                return refused(
                    "java.batchCleanup.argumentsInvalid",
                    "${action.operation} batch actions require only arguments.file and no symbol.",
                )
            }
            val requestedPath = try {
                Path.of(action.arguments.getValue("file"))
            } catch (failure: Exception) {
                return refused("java.batchCleanup.targetInvalid", "A batch target path is invalid.")
            }
            val root = request.snapshot.workspace.root.toAbsolutePath().normalize()
            if (requestedPath.isAbsolute || requestedPath.toString().isBlank() || requestedPath.normalize() != requestedPath ||
                requestedPath.startsWith("..")
            ) {
                return refused("java.batchCleanup.targetInvalid", "Batch targets must be normalized workspace-relative paths.")
            }
            val absoluteTarget = root.resolve(requestedPath).normalize()
            if (!absoluteTarget.startsWith(root)) {
                return refused("java.batchCleanup.targetInvalid", "A batch target is outside the workspace.")
            }
            val target = root.relativize(absoluteTarget)
            if (!seenTargets.add(target)) {
                return refused(
                    "java.batchCleanup.targetsOverlap",
                    "Multiple batch actions target $target; overlapping target files are refused.",
                )
            }
            val selectedFile = staged.files.singleOrNull { it.path.normalize() == target }
                ?: return refused("java.batchCleanup.targetInvalid", "Batch target is not a unique saved source file: $target.")
            if (selectedFile.languageId != "java") {
                return refused("java.batchCleanup.targetInvalid", "Batch cleanup accepts Java source files only: $target.")
            }
            val child = adapter.applyRefactoring(RefactoringRequest(
                operation = action.operation,
                arguments = if (action.operation == "formatFile") action.arguments + profileArguments else action.arguments,
                snapshot = staged,
            ))
            if (child.status != PatchStatus.PREVIEW) {
                return refused(
                    "java.batchCleanup.constituentRefused",
                    "Constituent ${action.operation} refused: ${child.refusalCode ?: child.summary}",
                    child.diagnosticsBefore + child.diagnosticsAfterPreview,
                )
            }
            if (child.riskLevel != RiskLevel.LOW || child.workspaceEdit.affectedFiles().any { it.normalize() != target } ||
                child.workspaceEdit.edits.any { it !is FileEdit.Modify } || child.workspaceEdit.edits.size > 1
            ) {
                return refused(
                    "java.batchCleanup.constituentScopeUnsupported",
                    "Constituent ${action.operation} changes files outside its selected saved source or has unsupported edit structure.",
                )
            }
            if (child.workspaceEdit.edits.isEmpty()) {
                return refused(
                    "java.batchCleanup.constituentNoOp",
                    "Constituent ${action.operation} has no deterministic change for $target.",
                )
            }
            childPlans += child
            staged = try {
                WorkspaceEditSimulator.apply(staged, child.workspaceEdit)
            } catch (failure: Exception) {
                return refused(
                    "java.batchCleanup.constituentOverlap",
                    "The batch cannot be composed safely: ${failure.message ?: "overlapping or stale edits"}.",
                )
            }
        }

        val before = runCatching { adapter.diagnostics(request.snapshot) }.getOrElse { failure ->
            return refused("java.batchCleanup.diagnosticsUnavailable", failure.message ?: "Baseline diagnostics are unavailable.")
        }
        val after = runCatching { adapter.diagnostics(staged) }.getOrElse { failure ->
            return refused("java.batchCleanup.diagnosticsUnavailable", failure.message ?: "Staged diagnostics are unavailable.")
        }
        val regressions = introducedBatchErrors(before, after)
        if (regressions.isNotEmpty()) {
            return refused(
                "java.batchCleanup.diagnosticsRegression",
                "Staged batch cleanup introduces ${regressions.size} Java diagnostic error(s).",
                regressions,
            )
        }
        val combined = WorkspaceEdit(childPlans.flatMap { it.workspaceEdit.edits })
        return PatchPlan(
            operation = request.operation,
            status = PatchStatus.PREVIEW,
            snapshotHash = request.snapshot.hash,
            confidence = childPlans.minOf(PatchPlan::confidence),
            requiresUserApproval = true,
            summary = "Batch cleanup composes ${childPlans.size} low-risk Java operations over ${seenTargets.size} distinct saved source files.",
            affectedFiles = combined.affectedFiles(),
            workspaceEdit = combined,
            diagnosticsBefore = before,
            diagnosticsAfterPreview = after,
            warnings = childPlans.flatMap(PatchPlan::warnings).distinct(),
            riskLevel = RiskLevel.LOW,
            evidence = if (childPlans.all { it.evidence == RefactoringEvidence.JDT_BINDING }) {
                RefactoringEvidence.JDT_BINDING
            } else RefactoringEvidence.STRUCTURAL,
        )
    }

    private fun introducedBatchErrors(before: List<Diagnostic>, after: List<Diagnostic>): List<Diagnostic> {
        fun identity(diagnostic: Diagnostic) = listOf(
            diagnostic.location?.path?.normalize()?.toString().orEmpty(),
            diagnostic.code.orEmpty(),
            diagnostic.message,
        ).joinToString("\u0000")
        val remaining = before.filter { it.severity == Diagnostic.Severity.ERROR }
            .groupingBy(::identity).eachCount().toMutableMap()
        return after.filter { diagnostic ->
            if (diagnostic.severity != Diagnostic.Severity.ERROR) return@filter false
            val key = identity(diagnostic)
            val count = remaining[key] ?: 0
            if (count > 0) {
                remaining[key] = count - 1
                false
            } else true
        }
    }

    private data class BatchCleanupAction(
        val operation: String,
        val symbol: String?,
        val arguments: Map<String, String>,
    )

    private val BATCH_CLEANUP_LOW_RISK_OPERATIONS = setOf("organizeImports", "formatFile")

}
