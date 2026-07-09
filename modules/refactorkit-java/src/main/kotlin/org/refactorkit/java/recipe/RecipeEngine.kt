package org.refactorkit.java.recipe

import org.refactorkit.core.ApplyResult
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.Transaction
import org.refactorkit.core.TransactionLog
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.java.JavaLanguageAdapter
import org.refactorkit.java.JavaMoveClassPlanner
import org.refactorkit.java.JavaOrganizeImportsPlanner
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.java.JavaRenameClassPlanner
import org.refactorkit.java.JavaSafeDeletePlanner
import java.nio.file.Path
import java.nio.file.Paths

sealed interface RecipeResult {
    val stepPlans: List<StepResult>

    data class Preview(
        override val stepPlans: List<StepResult>,
        val summary: String,
    ) : RecipeResult

    data class Applied(
        override val stepPlans: List<StepResult>,
        val transactionIds: List<String>,
        val summary: String,
    ) : RecipeResult

    data class Failed(
        override val stepPlans: List<StepResult>,
        val reason: String,
    ) : RecipeResult
}

data class StepResult(
    val stepType: String,
    val plan: PatchPlan?,
    val diagnostics: List<Diagnostic> = emptyList(),
    val message: String = "",
)

/**
 * Executes a [RecipeDefinition] against a Java project.
 *
 * Supports dry-run (preview-only) and apply mode.
 * Multi-step operations are applied atomically where possible;
 * on failure the engine stops and returns a [RecipeResult.Failed].
 */
class RecipeEngine(
    private val adapter: JavaLanguageAdapter = JavaLanguageAdapter(),
    private val scanner: JavaProjectScanner = JavaProjectScanner(),
) {
    fun run(
        recipe: RecipeDefinition,
        params: Map<String, String>,
        workspaceRoot: Path,
        dryRun: Boolean = true,
    ): RecipeResult {
        validateRecipe(recipe)
        val resolvedParams = resolveParams(recipe, params)
        validateParams(recipe, resolvedParams)

        var snap = scanner.scan(workspaceRoot)
        val stepResults = mutableListOf<StepResult>()
        val appliedTransactions = mutableListOf<Transaction>()
        val txIds = mutableListOf<String>()
        val transactionLog = TransactionLog(workspaceRoot.toAbsolutePath().normalize().resolve(".refactorkit/transactions"))

        fun fail(reason: String): RecipeResult.Failed {
            val rollbackSummary = if (!dryRun && appliedTransactions.isNotEmpty()) {
                rollbackApplied(workspaceRoot, transactionLog, appliedTransactions)
            } else ""
            val fullReason = if (rollbackSummary.isBlank()) reason else "$reason $rollbackSummary"
            return RecipeResult.Failed(stepResults, fullReason)
        }

        for ((index, stepDef) in recipe.steps.withIndex()) {
            val step = stepDef.substitute(resolvedParams)

            val result = try {
                executeStep(step, snap, workspaceRoot, dryRun)
            } catch (e: Exception) {
                return fail("Step ${index + 1} '${step.type}' failed: ${e.message}")
            }
            stepResults += result

            if (result.plan?.status == PatchStatus.REFUSED) {
                return fail("Step ${index + 1} '${step.type}' refused: ${result.plan.summary}")
            }

            if (!dryRun && result.plan != null && result.plan.status == PatchStatus.PREVIEW) {
                when (val apply = PatchEngine(workspaceRoot).apply(result.plan, snap.hash)) {
                    is ApplyResult.Applied -> {
                        transactionLog.save(apply.transaction)
                        appliedTransactions += apply.transaction
                        txIds += apply.transaction.id.value
                        snap = scanner.scan(workspaceRoot) // refresh after apply
                    }
                    is ApplyResult.Refused -> {
                        val msg = apply.diagnostics.joinToString("; ") { it.message }
                        return fail("Apply refused for step ${index + 1} '${step.type}': $msg")
                    }
                }
            }
        }

        val totalFiles = stepResults.mapNotNull { it.plan?.affectedFiles?.size }.sum()
        val summary = "Recipe '${recipe.name}' ${if (dryRun) "preview" else "applied"}: " +
            "${stepResults.size} step(s), $totalFiles file(s) affected."

        return if (dryRun) RecipeResult.Preview(stepResults, summary)
        else RecipeResult.Applied(stepResults, txIds, summary)
    }

    // ── step execution ────────────────────────────────────────────────────────

    private fun executeStep(step: StepDef, snap: ProjectSnapshot, root: Path, dryRun: Boolean): StepResult {
        return when (step.type) {
            "renameClass" -> {
                val symbol = step.params["symbol"] ?: error("renameClass step requires 'symbol'")
                val newName = step.params["newName"] ?: error("renameClass step requires 'newName'")
                val plan = JavaRenameClassPlanner(adapter).preview(snap, symbol, newName)
                StepResult("renameClass", plan)
            }

            "moveClass" -> {
                val symbol = step.params["symbol"] ?: error("moveClass step requires 'symbol'")
                val targetPkg = step.params["to"] ?: error("moveClass step requires 'to'")
                val plan = JavaMoveClassPlanner(adapter).preview(snap, symbol, targetPkg)
                StepResult("moveClass", plan)
            }

            "movePackage" -> {
                val fromPkg = step.params["from"] ?: error("movePackage step requires 'from'")
                val toPkg = step.params["to"] ?: error("movePackage step requires 'to'")
                val plans = moveAllClassesInPackage(snap, fromPkg, toPkg)
                if (plans.any { it.status == PatchStatus.REFUSED }) {
                    val refused = plans.first { it.status == PatchStatus.REFUSED }
                    StepResult("movePackage", refused, message = "movePackage refused: ${refused.summary}")
                } else {
                    val merged = mergePlans("movePackage", plans, snap)
                    StepResult("movePackage", merged, message = "Move ${plans.size} class(es) from $fromPkg to $toPkg.")
                }
            }

            "organizeImports" -> {
                val fileArg = step.params["file"]
                val paths = if (fileArg != null) {
                    listOf(Paths.get(fileArg))
                } else {
                    snap.files.filter { it.languageId == "java" }.map { it.path }
                }
                val plan = JavaOrganizeImportsPlanner().preview(snap, paths)
                StepResult("organizeImports", plan)
            }

            "safeDelete" -> {
                val symbol = step.params["symbol"] ?: error("safeDelete step requires 'symbol'")
                val force = step.params["force"]?.toBoolean() ?: false
                val plan = JavaSafeDeletePlanner(adapter).preview(snap, symbol, force)
                StepResult("safeDelete", plan)
            }

            "runDiagnostics" -> {
                val diags = adapter.diagnostics(snap)
                val errors = diags.filter { it.severity == Diagnostic.Severity.ERROR }
                if (errors.isNotEmpty()) {
                    val plan = refusedPlan("runDiagnostics", "Diagnostics found ${errors.size} error(s).", snap)
                    StepResult("runDiagnostics", plan, diagnostics = diags)
                } else {
                    StepResult("runDiagnostics", null, diagnostics = diags, message = "No errors found.")
                }
            }

            "summarizePatch" -> StepResult("summarizePatch", null, message = "Summary step (no-op).")

            else -> error("Unknown recipe step type: '${step.type}'")
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun moveAllClassesInPackage(snap: ProjectSnapshot, fromPkg: String, toPkg: String): List<PatchPlan> {
        val index = adapter.buildSymbols(snap)
        val symbols = index.symbols.filter { sym ->
            val pkg = sym.id.value.substringBeforeLast('.')
            pkg == fromPkg && sym.kind in setOf(
                org.refactorkit.core.Symbol.Kind.CLASS,
                org.refactorkit.core.Symbol.Kind.INTERFACE,
                org.refactorkit.core.Symbol.Kind.ENUM,
                org.refactorkit.core.Symbol.Kind.RECORD,
            )
        }
        return symbols.map { sym ->
            JavaMoveClassPlanner(adapter).preview(snap, sym.id.value, toPkg)
        }
    }

    private fun mergePlans(operation: String, plans: List<PatchPlan>, snap: ProjectSnapshot): PatchPlan {
        if (plans.isEmpty()) {
            return org.refactorkit.core.PatchPlan(
                operation = operation,
                status = PatchStatus.PREVIEW,
                snapshotHash = snap.hash,
                confidence = 1.0,
                summary = "No classes to process.",
                affectedFiles = emptySet(),
                workspaceEdit = WorkspaceEdit(),
            )
        }
        val allEdits = plans.flatMap { it.workspaceEdit.edits }
        val allFiles = plans.flatMap { it.affectedFiles }.toSet()
        val allWarnings = plans.flatMap { it.warnings }.distinct()
        return PatchPlan(
            operation = operation,
            status = PatchStatus.PREVIEW,
            snapshotHash = snap.hash,
            confidence = plans.minOf { it.confidence },
            requiresUserApproval = plans.any { it.requiresUserApproval },
            summary = plans.joinToString("; ") { it.summary },
            affectedFiles = allFiles,
            workspaceEdit = WorkspaceEdit(allEdits),
            warnings = allWarnings,
            riskLevel = plans.maxOf { it.riskLevel },
        )
    }

    private fun refusedPlan(operation: String, reason: String, snap: ProjectSnapshot) = PatchPlan(
        operation = operation,
        status = PatchStatus.REFUSED,
        snapshotHash = snap.hash,
        confidence = 0.0,
        summary = reason,
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        warnings = listOf(reason),
    )

    private fun rollbackApplied(
        workspaceRoot: Path,
        transactionLog: TransactionLog,
        appliedTransactions: List<Transaction>,
    ): String {
        val failures = mutableListOf<String>()
        for (transaction in appliedTransactions.asReversed()) {
            when (val rollback = PatchEngine(workspaceRoot).rollback(transaction)) {
                is ApplyResult.Applied -> transactionLog.delete(transaction.id)
                is ApplyResult.Refused -> failures += "${transaction.id.value}: " +
                    rollback.diagnostics.joinToString("; ") { it.message }
            }
        }
        return if (failures.isEmpty()) {
            "Rolled back ${appliedTransactions.size} applied step(s)."
        } else {
            "Rollback attempted; ${failures.size} transaction(s) failed: ${failures.joinToString(" | ")}"
        }
    }

    private fun validateRecipe(recipe: RecipeDefinition) {
        require(recipe.id.isNotBlank()) { "Recipe id must not be blank" }
        require(recipe.steps.isNotEmpty()) { "Recipe '${recipe.id}' must contain at least one step" }
        if (recipe.language != "java") error("Unsupported recipe language '${recipe.language}'. Only 'java' is supported.")
    }

    private fun resolveParams(recipe: RecipeDefinition, provided: Map<String, String>): Map<String, String> {
        val resolved = linkedMapOf<String, String>()
        recipe.parameters.forEach { (name, def) ->
            def.default?.let { resolved[name] = it }
        }
        resolved.putAll(provided)
        return resolved
    }

    private fun validateParams(recipe: RecipeDefinition, provided: Map<String, String>) {
        recipe.parameters.forEach { (name, def) ->
            if (name !in provided && def.default == null) {
                error("Recipe '${recipe.id}' requires parameter '$name' (${def.type})")
            }
            provided[name]?.let { validateParamType(recipe, name, def.type, it) }
        }
    }

    private fun validateParamType(recipe: RecipeDefinition, name: String, type: String, value: String) {
        when (type) {
            "string" -> Unit
            "boolean" -> value.toBooleanStrictOrNull()
                ?: error("Recipe '${recipe.id}' parameter '$name' must be boolean")
            "integer", "int" -> value.toIntOrNull()
                ?: error("Recipe '${recipe.id}' parameter '$name' must be integer")
            else -> error("Recipe '${recipe.id}' parameter '$name' has unsupported type '$type'")
        }
    }

    private fun List<org.refactorkit.core.RiskLevel>.maxOf(selector: (org.refactorkit.core.RiskLevel) -> org.refactorkit.core.RiskLevel) =
        this.maxByOrNull { it.ordinal } ?: org.refactorkit.core.RiskLevel.LOW
}
