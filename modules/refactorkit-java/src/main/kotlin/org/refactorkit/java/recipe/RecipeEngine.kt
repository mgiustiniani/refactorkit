package org.refactorkit.java.recipe

import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditSimulator
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
    val recipePlan: PatchPlan?

    data class Preview(
        override val stepPlans: List<StepResult>,
        override val recipePlan: PatchPlan,
        val summary: String,
    ) : RecipeResult

    data class Applied(
        override val stepPlans: List<StepResult>,
        override val recipePlan: PatchPlan,
        val transactionIds: List<String>,
        val summary: String,
    ) : RecipeResult

    data class Failed(
        override val stepPlans: List<StepResult>,
        val reason: String,
        override val recipePlan: PatchPlan? = null,
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
 * Supports dry-run (preview-only) and apply mode. Every step is evaluated
 * against one evolving in-memory snapshot; successful steps are reduced to one
 * recipe-wide plan and committed through a single [PatchEngine] transaction.
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

        val initialSnapshot = scanner.scan(workspaceRoot)
        var stagedSnapshot = initialSnapshot
        val stepResults = mutableListOf<StepResult>()

        for ((index, stepDef) in recipe.steps.withIndex()) {
            val step = stepDef.substitute(resolvedParams)
            val result = try {
                executeStep(step, stagedSnapshot)
            } catch (e: Exception) {
                return RecipeResult.Failed(stepResults, "Step ${index + 1} '${step.type}' failed: ${e.message}")
            }
            stepResults += result

            if (result.plan?.status == PatchStatus.REFUSED) {
                return RecipeResult.Failed(
                    stepResults,
                    "Step ${index + 1} '${step.type}' refused: ${result.plan.summary}",
                )
            }
            if (result.plan?.status == PatchStatus.PREVIEW) {
                stagedSnapshot = try {
                    WorkspaceEditSimulator.apply(stagedSnapshot, result.plan.workspaceEdit)
                } catch (e: Exception) {
                    return RecipeResult.Failed(
                        stepResults,
                        "Step ${index + 1} '${step.type}' could not be staged: ${e.message}",
                    )
                }
            }
        }

        val recipePlan = composeRecipePlan(recipe, initialSnapshot, stagedSnapshot, stepResults)
        val transactionCount = if (recipePlan.workspaceEdit.edits.isEmpty()) 0 else 1
        val summary = "Recipe '${recipe.name}' ${if (dryRun) "preview" else "applied"}: " +
            "${stepResults.size} step(s), ${recipePlan.affectedFiles.size} file(s) affected, " +
            "$transactionCount transaction(s)."

        if (dryRun) return RecipeResult.Preview(stepResults, recipePlan, summary)
        if (transactionCount == 0) return RecipeResult.Applied(stepResults, recipePlan, emptyList(), summary)
        return when (val apply = PatchEngine(workspaceRoot).apply(
            recipePlan,
            initialSnapshot,
            ApplyAuthorization.explicit("recipe-engine"),
        )) {
            is ApplyResult.Applied -> RecipeResult.Applied(
                stepResults,
                recipePlan,
                listOf(apply.transaction.id.value),
                summary,
            )
            is ApplyResult.Refused -> RecipeResult.Failed(
                stepResults,
                "Recipe-wide apply refused: ${apply.diagnostics.joinToString("; ") { it.message }}",
                recipePlan,
            )
        }
    }

    // ── step execution ────────────────────────────────────────────────────────

    private fun executeStep(step: StepDef, snap: ProjectSnapshot): StepResult {
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
                val plan = movePackagePlan(snap, fromPkg, toPkg)
                StepResult("movePackage", plan, message = plan.summary)
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

    private fun movePackagePlan(snap: ProjectSnapshot, fromPkg: String, toPkg: String): PatchPlan {
        val symbols = adapter.buildSymbols(snap).symbols.filter { symbol ->
            symbol.id.value.substringBeforeLast('.') == fromPkg && symbol.kind in setOf(
                org.refactorkit.core.Symbol.Kind.CLASS,
                org.refactorkit.core.Symbol.Kind.INTERFACE,
                org.refactorkit.core.Symbol.Kind.ENUM,
                org.refactorkit.core.Symbol.Kind.RECORD,
                org.refactorkit.core.Symbol.Kind.ANNOTATION,
            )
        }.sortedBy { it.id.value }
        var staged = snap
        val plans = mutableListOf<PatchPlan>()
        for (symbol in symbols) {
            val plan = JavaMoveClassPlanner(adapter).preview(staged, symbol.id.value, toPkg)
            plans += plan
            if (plan.status == PatchStatus.REFUSED) return plan
            staged = WorkspaceEditSimulator.apply(staged, plan.workspaceEdit)
        }
        val workspaceEdit = workspaceDelta(snap, staged, plans.flatMap { it.workspaceEdit.edits })
        return PatchPlan(
            operation = "movePackage",
            snapshotHash = snap.hash,
            confidence = plans.minOfOrNull(PatchPlan::confidence) ?: 1.0,
            requiresUserApproval = plans.any(PatchPlan::requiresUserApproval),
            summary = if (plans.isEmpty()) "No classes to process." else
                "Move ${plans.size} class(es) from $fromPkg to $toPkg.",
            affectedFiles = workspaceEdit.affectedFiles(),
            workspaceEdit = workspaceEdit,
            diagnosticsBefore = plans.flatMap(PatchPlan::diagnosticsBefore),
            diagnosticsAfterPreview = plans.flatMap(PatchPlan::diagnosticsAfterPreview),
            warnings = plans.flatMap(PatchPlan::warnings).distinct(),
            riskLevel = plans.map(PatchPlan::riskLevel).maxByOrNull { it.ordinal }
                ?: org.refactorkit.core.RiskLevel.LOW,
        )
    }

    private fun composeRecipePlan(
        recipe: RecipeDefinition,
        initial: ProjectSnapshot,
        staged: ProjectSnapshot,
        steps: List<StepResult>,
    ): PatchPlan {
        val plans = steps.mapNotNull(StepResult::plan)
        val workspaceEdit = workspaceDelta(initial, staged, plans.flatMap { it.workspaceEdit.edits })
        return PatchPlan(
            operation = "recipe:${recipe.id}",
            snapshotHash = initial.hash,
            confidence = plans.minOfOrNull(PatchPlan::confidence) ?: 1.0,
            requiresUserApproval = plans.any(PatchPlan::requiresUserApproval),
            summary = "Execute recipe '${recipe.name}' as one staged transaction.",
            affectedFiles = workspaceEdit.affectedFiles(),
            workspaceEdit = workspaceEdit,
            diagnosticsBefore = plans.flatMap(PatchPlan::diagnosticsBefore),
            diagnosticsAfterPreview = plans.flatMap(PatchPlan::diagnosticsAfterPreview),
            warnings = plans.flatMap(PatchPlan::warnings).distinct(),
            riskLevel = plans.map(PatchPlan::riskLevel).maxByOrNull { it.ordinal }
                ?: org.refactorkit.core.RiskLevel.LOW,
        )
    }

    private fun workspaceDelta(
        initial: ProjectSnapshot,
        staged: ProjectSnapshot,
        stagedEdits: List<FileEdit>,
    ): WorkspaceEdit {
        val initialFiles = initial.files.associateBy { it.path.normalize() }
        val stagedFiles = staged.files.associateBy { it.path.normalize() }
        val currentOrigins = initialFiles.keys.associateWith { it }.toMutableMap()
        stagedEdits.forEach { edit ->
            val path = edit.path.normalize()
            when (edit) {
                is FileEdit.Create -> if (!edit.overwrite) currentOrigins.remove(path)
                is FileEdit.Delete -> currentOrigins.remove(path)
                is FileEdit.Rename -> currentOrigins.remove(path)?.let { origin ->
                    currentOrigins[edit.newPath.normalize()] = origin
                }
                is FileEdit.Modify -> Unit
            }
        }
        val renamed = currentOrigins.entries
            .filter { (current, origin) -> current != origin && current in stagedFiles && origin in initialFiles }
            .associate { (current, origin) -> origin to current }
        val survivingOrigins = currentOrigins.values.toSet()
        val deleted: List<FileEdit> = (initialFiles.keys - survivingOrigins)
            .sortedBy(Path::toString)
            .map { path -> FileEdit.Delete(path) }
        val renames: List<FileEdit> = renamed.entries.sortedBy { it.key.toString() }
            .map { (origin, target) -> FileEdit.Rename(origin, target) }
        val created: List<FileEdit> = (stagedFiles.keys - currentOrigins.keys)
            .sortedBy(Path::toString)
            .map { path -> FileEdit.Create(path, stagedFiles.getValue(path).content) }
        val commonModified: List<FileEdit> = currentOrigins.entries
            .filter { (current, origin) -> current == origin && current in stagedFiles }
            .sortedBy { it.key.toString() }
            .mapNotNull { (path, _) ->
                fullFileReplacement(path, initialFiles.getValue(path).content, stagedFiles.getValue(path).content)
            }
        val renamedModified: List<FileEdit> = renamed.entries.sortedBy { it.value.toString() }
            .mapNotNull { (origin, target) ->
                fullFileReplacement(target, initialFiles.getValue(origin).content, stagedFiles.getValue(target).content)
            }
        return WorkspaceEdit(deleted + renames + created + commonModified + renamedModified)
    }

    private fun fullFileReplacement(path: Path, before: String, after: String): FileEdit.Modify? =
        if (before == after) null else FileEdit.Modify(
            path,
            listOf(TextEdit(
                SourceRange(SourcePosition(0, 0), TextEdits.positionForOffset(before, before.length)),
                after,
            )),
        )

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
}
