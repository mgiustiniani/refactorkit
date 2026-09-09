package org.refactorkit.java.recipe

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RefactoringRequest
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.LanguageAdapter
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.WorkspaceEdit

/** Owning semantic session supplied by the existing CLI/daemon composition root. */
data class ManagedRecipeContext(
    val snapshot: ProjectSnapshot,
    val adapter: LanguageAdapter,
    val diagnosticsGate: DiagnosticsGate,
)

/** Public composition roots supply their actual adapter/gate; success returns the untouched child. */
object ManagedRecipePreview {
    fun preview(request: RefactoringRequest, context: ManagedRecipeContext, expectedSnapshotHash: String?, suppliedLease: String?, owningLease: String?): PatchPlan {
        if (owningLease.isNullOrBlank() || suppliedLease != owningLease || expectedSnapshotHash != context.snapshot.hash || request.snapshot != context.snapshot) {
            return refused(request, "recipe.contextMismatch", "Recipe requires the exact owning snapshot and semantic lease")
        }
        return try {
            val yaml = requireNotNull(request.arguments["recipeYaml"]) { "recipeYaml is required" }
            require(yaml.toByteArray(Charsets.UTF_8).size in 1..65_536) { "Recipe YAML exceeds its bounded input size" }
            require(request.arguments.keys.all { it == "recipeYaml" || it.startsWith("param.") && it.length > 6 }) { "Unknown recipe argument" }
            val recipe = RecipeLoader.load(yaml.byteInputStream())
            require(recipe.language in setOf("typescript", "javascript") && recipe.language == context.adapter.languageId()) { "Recipe language differs from its owning adapter" }
            val parameters = request.arguments.filterKeys { it.startsWith("param.") }.mapKeys { it.key.removePrefix("param.") }
            when (val result = RecipeEngine().run(recipe, parameters, context.snapshot.workspace.root, dryRun = true, managedContext = context)) {
                is RecipeResult.Preview -> result.recipePlan
                is RecipeResult.Failed -> {
                    val diagnostics = result.stepPlans.flatMap { it.diagnostics }
                    refused(request, diagnostics.lastOrNull { it.code?.startsWith("recipe.") == true }?.code ?: "recipe.previewRefused", result.reason, diagnostics)
                }
                else -> refused(request, "recipe.childAuthorityRequired", "Recipe did not produce a retained compiler-owned preview")
            }
        } catch (failure: Exception) {
            refused(request, "recipe.argumentsInvalid", failure.message ?: "Recipe preview failed safely")
        }
    }

    private fun refused(request: RefactoringRequest, code: String, message: String, diagnostics: List<Diagnostic> = emptyList()) = PatchPlan(
        operation = "recipe", status = PatchStatus.REFUSED, snapshotHash = request.snapshot.hash, confidence = 0.0, requiresUserApproval = false,
        summary = message, affectedFiles = emptySet(), workspaceEdit = WorkspaceEdit(), riskLevel = RiskLevel.HIGH, refusalCode = code,
        diagnosticsAfterPreview = diagnostics + Diagnostic(message, Diagnostic.Severity.ERROR, code = code),
    )
}

/** Bounded recipe evaluation; it never owns a replacement plan or filesystem apply gate. */
internal class ManagedRecipePlanner {
    fun preview(recipe: RecipeDefinition, parameters: Map<String, String>, context: ManagedRecipeContext): RecipeResult {
        if (context.adapter.languageId() !in setOf("typescript", "javascript") || context.diagnosticsGate.provider == null ||
            context.diagnosticsGate.id != "typescript-compiler-exact-v1") {
            return failed(emptyList(), "recipe.semanticContextRequired", "Recipe requires its owning TypeScript semantic session and diagnostics gate")
        }
        val steps = recipe.steps.map { it.substitute(parameters) }
        val projections = setOf("runDiagnostics", "summarizePatch")
        if (steps.size !in 1..16 || steps.count { it.type !in projections } != 1) {
            return failed(emptyList(), "recipe.singleAuthorityRequired", "A semantic recipe requires exactly one mutation and at most 16 total steps")
        }
        if (steps.any { it.type in projections && it.params.isNotEmpty() } ||
            steps.any { step -> step.params.values.any { "{{" in it || "}}" in it } }) {
            return failed(emptyList(), "recipe.argumentsInvalid", "Recipe has unresolved parameters or unsupported projection arguments")
        }
        var staged = context.snapshot
        var child: PatchPlan? = null
        val results = mutableListOf<StepResult>()
        for (step in steps) {
            when (step.type) {
                "runDiagnostics" -> {
                    val diagnostics = requireNotNull(context.diagnosticsGate.provider).invoke(staged)
                    results += StepResult(step.type, null, diagnostics)
                    if (diagnostics.any { it.severity == Diagnostic.Severity.ERROR }) {
                        return failed(results, "recipe.diagnosticsNotClean", "Owning compiler diagnostics are not clean")
                    }
                }
                "summarizePatch" -> results += StepResult(step.type, null, message = child?.summary ?: "No mutation previewed yet")
                else -> {
                    val plan = context.adapter.applyRefactoring(RefactoringRequest(step.type, arguments = step.params, snapshot = staged))
                    results += StepResult(step.type, plan)
                    if (plan.status != PatchStatus.PREVIEW || plan.evidence != RefactoringEvidence.COMPILER_PROVEN ||
                        plan.authorityLease == null || plan.authorityLease?.operation != plan.operation ||
                        plan.snapshotHash != context.snapshot.hash || !plan.requiresUserApproval || plan.workspaceEdit.edits.isEmpty()) {
                        return failed(results, "recipe.childAuthorityRequired", "Child must be a compiler-owned, approval-requiring plan for the exact snapshot")
                    }
                    staged = WorkspaceEditSimulator.apply(staged, plan.workspaceEdit)
                    child = plan
                }
            }
        }
        // Keep the actual child object, including operation, lease, diagnostics and auxiliary evidence.
        return RecipeResult.Preview(results, requireNotNull(child),
            "Recipe '${recipe.name}': one retained compiler-owned child and ${steps.size - 1} non-mutating step(s)")
    }

    private fun failed(results: List<StepResult>, code: String, message: String) = RecipeResult.Failed(
        results.map { it.copy(plan = null, diagnostics = it.diagnostics +
            it.plan?.let { plan -> plan.diagnosticsBefore + plan.diagnosticsAfterPreview }.orEmpty()) } +
            StepResult("recipe", null, listOf(Diagnostic(message, Diagnostic.Severity.ERROR, code = code))),
        "$code: $message",
    )
}
