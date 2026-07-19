package org.refactorkit.java.recipe

import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.java.JavaGeneratedSourcePolicy
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

data class DiagnosticDelta(
    val baselineErrors: Int,
    val stagedErrors: Int,
    val introducedErrors: Int,
    val resolvedErrors: Int,
    val unchangedErrors: Int,
)

data class StepResult(
    val stepType: String,
    val plan: PatchPlan?,
    val diagnostics: List<Diagnostic> = emptyList(),
    val message: String = "",
    val diagnosticDelta: DiagnosticDelta? = null,
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
    private val diagnosticsProvider: ((ProjectSnapshot) -> List<Diagnostic>)? = null,
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
        val touchedPaths = linkedSetOf<Path>()

        for ((index, stepDef) in recipe.steps.withIndex()) {
            val step = stepDef.substitute(resolvedParams)
            val result = try {
                executeStep(step, initialSnapshot, stagedSnapshot, touchedPaths)
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
                touchedPaths += result.plan.affectedFiles.map(Path::normalize)
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
            DiagnosticsGate.enabled("java-jdt", ::diagnostics),
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

    private fun executeStep(
        step: StepDef,
        initial: ProjectSnapshot,
        snap: ProjectSnapshot,
        touchedPaths: Set<Path>,
    ): StepResult {
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
                    val initialByPath = initial.files.associateBy { it.path.normalize() }
                    snap.files.filter { file ->
                        val original = initialByPath[file.path.normalize()]
                        file.languageId == "java" &&
                            file.path.normalize() in touchedPaths &&
                            JavaGeneratedSourcePolicy.reason(file) == null &&
                            (original == null || importSurface(original.content) != importSurface(file.content))
                    }.map { it.path }
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
                val baseline = diagnostics(initial)
                val staged = diagnostics(snap)
                val delta = diagnosticDelta(baseline, staged)
                val strict = step.params["strict"]?.let { value ->
                    value.toBooleanStrictOrNull() ?: error("runDiagnostics 'strict' must be true or false")
                } ?: false
                val refuses = delta.introducedErrors > 0 || (strict && delta.stagedErrors > 0)
                val policy = if (strict) "strict" else "introduced-error"
                val message = "Diagnostics ($policy): baseline=${delta.baselineErrors}, " +
                    "staged=${delta.stagedErrors}, introduced=${delta.introducedErrors}, " +
                    "resolved=${delta.resolvedErrors}, unchanged=${delta.unchangedErrors}."
                if (refuses) {
                    val plan = refusedPlan("runDiagnostics", message, snap)
                    StepResult("runDiagnostics", plan, diagnostics = staged, message = message, diagnosticDelta = delta)
                } else {
                    StepResult("runDiagnostics", null, diagnostics = staged, message = message, diagnosticDelta = delta)
                }
            }

            "summarizePatch" -> StepResult("summarizePatch", null, message = "Summary step (no-op).")

            else -> error("Unknown recipe step type: '${step.type}'")
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun movePackagePlan(snap: ProjectSnapshot, fromPkg: String, toPkg: String): PatchPlan {
        if (!isValidPackageName(fromPkg) || !isValidPackageName(toPkg)) {
            return refusedPlan("movePackage", "Invalid Java package name: '$fromPkg' -> '$toPkg'.", snap)
        }
        if (fromPkg == toPkg) {
            return refusedPlan("movePackage", "Source and target package are identical.", snap)
        }
        val packageRegex = Regex("(?m)^\\s*package\\s+${Regex.escape(fromPkg)}\\s*;")
        val movedFiles = snap.files.filter { file ->
            file.languageId == "java" && packageRegex.containsMatchIn(file.content) &&
                JavaGeneratedSourcePolicy.reason(file) == null
        }.sortedBy { it.path.toString() }
        if (movedFiles.isEmpty()) {
            return PatchPlan(
                operation = "movePackage", snapshotHash = snap.hash, confidence = 1.0,
                summary = "No exact-package Java compilation units to process.",
                affectedFiles = emptySet(), workspaceEdit = WorkspaceEdit(),
                evidence = RefactoringEvidence.STRUCTURAL,
            )
        }

        val targetBySource = linkedMapOf<Path, Path>()
        for (file in movedFiles) {
            val target = packageTargetPath(file.path, fromPkg, toPkg)
                ?: return refusedPlan(
                    "movePackage",
                    "Cannot safely derive a package path for ${file.path}; its path does not match $fromPkg.",
                    snap,
                )
            targetBySource[file.path.normalize()] = target
        }
        val existing = snap.files.map { it.path.normalize() }.toSet()
        val movingSources = targetBySource.keys
        val conflict = targetBySource.values.firstOrNull { target ->
            target in existing && target !in movingSources
        }
        if (conflict != null || targetBySource.values.toSet().size != targetBySource.size) {
            return refusedPlan(
                "movePackage",
                "Target package conflict: ${conflict ?: "multiple units map to one target path"} already exists.",
                snap,
            )
        }

        val typeKinds = setOf(
            org.refactorkit.core.Symbol.Kind.CLASS,
            org.refactorkit.core.Symbol.Kind.INTERFACE,
            org.refactorkit.core.Symbol.Kind.ENUM,
            org.refactorkit.core.Symbol.Kind.RECORD,
            org.refactorkit.core.Symbol.Kind.ANNOTATION,
        )
        val symbolKinds = adapter.buildSymbols(snap).symbols
            .filter {
                it.kind in typeKinds && it.location.path.normalize() in movingSources &&
                    it.id.value.substringBeforeLast('.') == fromPkg
            }
            .groupingBy { it.kind.name.lowercase() }
            .eachCount()
        val declaredTypes = movedFiles.flatMap(::topLevelTypeNames).toSortedSet()
        val qualifiedNames = declaredTypes.map { "$fromPkg.$it" to "$toPkg.$it" }
        val sourcePathReferences = targetBySource.mapNotNull { (source, target) ->
            val oldPath = source.toString().replace('\\', '/')
            val newPath = target.toString().replace('\\', '/')
            val marker = Regex("(?:^|/)src/(?:main|test)/java/").find(oldPath) ?: return@mapNotNull null
            oldPath.substring(marker.range.first + if (oldPath[marker.range.first] == '/') 1 else 0) to
                newPath.substring(marker.range.first + if (newPath[marker.range.first] == '/') 1 else 0)
        }
        val wildcardImport = Regex(
            "(?m)^(\\s*import\\s+(?:static\\s+)?)${Regex.escape(fromPkg)}\\.(\\*\\s*;)",
        )
        val modifications = mutableListOf<FileEdit>()
        for (file in snap.files.sortedBy { it.path.toString() }) {
            if (file.languageId != "java" || JavaGeneratedSourcePolicy.reason(file) != null) continue
            var after = file.content
            if (file.path.normalize() in movingSources) {
                after = packageRegex.replaceFirst(after, "package $toPkg;")
            }
            qualifiedNames.forEach { (oldName, newName) ->
                after = after.replace(
                    Regex("(?<![A-Za-z0-9_$])${Regex.escape(oldName)}(?![A-Za-z0-9_$])"),
                    newName,
                )
            }
            sourcePathReferences.forEach { (oldPath, newPath) -> after = after.replace(oldPath, newPath) }
            after = after.replace("\"$fromPkg\"", "\"$toPkg\"")
            after = wildcardImport.replace(after, "$1$toPkg.$2")
            fullFileReplacement(file.path.normalize(), file.content, after)?.let(modifications::add)
        }
        val renames = targetBySource.entries.sortedBy { it.key.toString() }
            .map { (source, target) -> FileEdit.Rename(source, target) }
        val workspaceEdit = WorkspaceEdit(modifications + renames)
        val packageInfoCount = movedFiles.count { it.path.fileName.toString() == "package-info.java" }
        val kindSummary = symbolKinds.toSortedMap().entries.joinToString(", ") { "${it.key}=${it.value}" }
            .ifBlank { "types=${declaredTypes.size}" }
        return PatchPlan(
            operation = "movePackage",
            snapshotHash = snap.hash,
            confidence = 0.92,
            summary = "Move package $fromPkg -> $toPkg: units=${movedFiles.size}, " +
                "$kindSummary, package-info=$packageInfoCount.",
            affectedFiles = workspaceEdit.affectedFiles(),
            workspaceEdit = workspaceEdit,
            warnings = listOf(
                "Java compilation units, imports, fully qualified references, exact package-name literals, and exact moved-unit source-path literals are in scope; other strings and build metadata are not migrated.",
                "Generated sources and build outputs are excluded from mutation planning.",
            ),
            riskLevel = org.refactorkit.core.RiskLevel.MEDIUM,
            evidence = RefactoringEvidence.STRUCTURAL,
        )
    }

    private fun importSurface(content: String): List<String> =
        content.lineSequence()
            .map(String::trim)
            .filter { it.startsWith("package ") || it.startsWith("import ") }
            .toList()

    private fun diagnostics(snapshot: ProjectSnapshot): List<Diagnostic> =
        diagnosticsProvider?.invoke(snapshot) ?: adapter.diagnostics(snapshot)

    private fun diagnosticDelta(
        baselineDiagnostics: List<Diagnostic>,
        stagedDiagnostics: List<Diagnostic>,
    ): DiagnosticDelta {
        fun counts(diagnostics: List<Diagnostic>) = diagnostics
            .filter { it.severity == Diagnostic.Severity.ERROR }
            .groupingBy(::diagnosticIdentity)
            .eachCount()
        val baseline = counts(baselineDiagnostics)
        val staged = counts(stagedDiagnostics)
        val identities = baseline.keys + staged.keys
        val unchanged = identities.sumOf { minOf(baseline[it] ?: 0, staged[it] ?: 0) }
        return DiagnosticDelta(
            baselineErrors = baseline.values.sum(),
            stagedErrors = staged.values.sum(),
            introducedErrors = identities.sumOf { maxOf(0, (staged[it] ?: 0) - (baseline[it] ?: 0)) },
            resolvedErrors = identities.sumOf { maxOf(0, (baseline[it] ?: 0) - (staged[it] ?: 0)) },
            unchangedErrors = unchanged,
        )
    }

    private fun diagnosticIdentity(diagnostic: Diagnostic): String {
        val location = diagnostic.location
        return listOf(
            diagnostic.severity.name,
            diagnostic.code.orEmpty(),
            diagnostic.evidence?.name.orEmpty(),
            diagnostic.category?.name.orEmpty(),
            diagnostic.locationPrecision.name,
            location?.path?.normalize()?.toString()?.replace('\\', '/').orEmpty(),
            location?.range?.start?.line?.toString().orEmpty(),
            location?.range?.start?.character?.toString().orEmpty(),
            location?.range?.end?.line?.toString().orEmpty(),
            location?.range?.end?.character?.toString().orEmpty(),
            diagnostic.message,
        ).joinToString("\u0000")
    }

    private fun isValidPackageName(packageName: String): Boolean =
        packageName.isNotBlank() && packageName.split('.').all { segment ->
            segment.isNotEmpty() &&
                (segment.first().isLetter() || segment.first() == '_' || segment.first() == '$') &&
                segment.all { it.isLetterOrDigit() || it == '_' || it == '$' }
        }

    private fun packageTargetPath(path: Path, fromPkg: String, toPkg: String): Path? {
        val parent = path.parent ?: return null
        val parentSegments = (0 until parent.nameCount).map { parent.getName(it).toString() }
        val oldSegments = fromPkg.split('.')
        if (parentSegments.size < oldSegments.size || parentSegments.takeLast(oldSegments.size) != oldSegments) return null
        var target = path.root ?: Path.of("")
        parentSegments.dropLast(oldSegments.size).forEach { target = target.resolve(it) }
        toPkg.split('.').forEach { target = target.resolve(it) }
        return target.resolve(path.fileName).normalize()
    }

    private fun topLevelTypeNames(file: org.refactorkit.core.SourceFile): List<String> =
        Regex("(?m)^(?:\\s*(?:public|protected|private|abstract|final|sealed|non-sealed|static|strictfp)\\s+)*" +
            "(?:class|interface|enum|record|@interface)\\s+([A-Za-z_$][A-Za-z0-9_$]*)")
            .findAll(file.content)
            .map { it.groupValues[1] }
            .toList()

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
            evidence = plans.map(PatchPlan::evidence).maxByOrNull { it.ordinal }
                ?: RefactoringEvidence.STRUCTURAL,
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
