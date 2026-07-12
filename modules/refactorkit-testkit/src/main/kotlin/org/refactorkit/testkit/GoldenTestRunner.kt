package org.refactorkit.testkit

import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProtocolPath
import org.refactorkit.java.JavaChangeSignaturePlanner
import org.refactorkit.java.JavaExtractMethodPlanner
import org.refactorkit.java.JavaFormatFilePlanner
import org.refactorkit.java.JavaLanguageAdapter
import org.refactorkit.java.JavaMoveClassPlanner
import org.refactorkit.java.JavaOrganizeImportsPlanner
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.java.JavaRenameClassPlanner
import org.refactorkit.java.JavaRenameMemberPlanner
import org.refactorkit.java.JavaSafeDeletePlanner
import org.refactorkit.webimporter.ExternalJavaClassImporter
import org.refactorkit.webimporter.ImportRequest
import org.refactorkit.webimporter.LicensePolicy
import org.refactorkit.webimporter.SourceKind
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText

/**
 * Runs a [GoldenTestCase] end-to-end:
 *
 * 1. Copy `before/` to a temporary directory.
 * 2. Parse `request.json` → [GoldenRequest].
 * 3. Scan the project with [JavaProjectScanner].
 * 4. Execute the requested operation and obtain a [PatchPlan].
 * 5. Validate the plan against `expected-plan.json` (if present).
 * 6. If plan status is PREVIEW: apply the plan.
 * 7. If `after/` directory exists: compare the resulting file tree.
 * 8. Return a [GoldenTestResult] with all errors.
 */
class GoldenTestRunner(
    private val scanner: JavaProjectScanner = JavaProjectScanner(),
    private val adapter: JavaLanguageAdapter = JavaLanguageAdapter(),
) {
    fun run(testCase: GoldenTestCase): GoldenTestResult {
        val tempDir = Files.createTempDirectory("golden-${testCase.name}-")
        return try {
            runInDir(testCase, tempDir)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    private fun runInDir(testCase: GoldenTestCase, workDir: Path): GoldenTestResult {
        // 1. Copy before/ into work dir
        copyDirectory(testCase.beforeDir, workDir)

        // 2. Parse request
        val request = GoldenJson.parseRequest(testCase.requestFile.readText())

        // 3. Scan
        val snap = scanner.scan(workDir)

        // 4. Build plan
        val plan = try {
            buildPlan(request, snap, workDir)
        } catch (e: Exception) {
            return GoldenTestResult(testCase.name, null, planErrors = listOf("Plan execution threw: ${e.message}"))
        }

        // 5. Check plan against expected-plan.json
        val planErrors = mutableListOf<String>()
        if (testCase.expectedPlanFile.exists()) {
            val expected = GoldenJson.parseExpectedPlan(testCase.expectedPlanFile.readText())
            planErrors += validatePlan(plan, expected)
        }

        // 6. Apply if PREVIEW
        val afterErrors = mutableListOf<String>()
        if (plan.status == PatchStatus.PREVIEW) {
            when (val result = PatchEngine(workDir).apply(
                plan,
                snap,
                ApplyAuthorization.explicit("golden-testkit"),
                DiagnosticsGate.enabled("java-jdt", adapter::diagnostics),
            )) {
                is ApplyResult.Applied -> Unit
                is ApplyResult.Refused -> {
                    planErrors += "PatchEngine refused apply: " +
                        result.diagnostics.joinToString("; ") { it.message }
                }
            }

            // 7. Compare after/ if present
            if (testCase.afterDir.exists() && planErrors.isEmpty()) {
                afterErrors += compareDirectories(testCase.afterDir, workDir)
            }
        } else if (plan.status == PatchStatus.REFUSED) {
            // For REFUSED plans, if after/ exists compare with before/ (nothing should change)
            if (testCase.afterDir.exists()) {
                afterErrors += compareDirectories(testCase.afterDir, workDir)
            }
        }

        return GoldenTestResult(testCase.name, plan, planErrors, afterErrors)
    }

    // ── plan builder ──────────────────────────────────────────────────────────

    private fun buildPlan(request: GoldenRequest, snap: org.refactorkit.core.ProjectSnapshot, root: Path): PatchPlan =
        when (request.operation) {
            "renameClass" -> {
                val newName = request.arguments["newName"] ?: error("renameClass needs 'newName' in arguments")
                JavaRenameClassPlanner(adapter).preview(snap, requireSymbol(request), newName)
            }
            "moveClass" -> {
                val to = request.arguments["to"] ?: error("moveClass needs 'to' in arguments")
                JavaMoveClassPlanner(adapter).preview(snap, requireSymbol(request), to)
            }
            "renameMember" -> {
                val newName = request.arguments["newName"] ?: error("renameMember needs 'newName' in arguments")
                JavaRenameMemberPlanner(adapter).preview(snap, requireSymbol(request), newName)
            }
            "organizeImports" -> {
                val filePaths = (request.arguments["file"] ?: request.arguments["files"]
                    ?: error("organizeImports needs 'file' in arguments"))
                    .split(",").map { rawPath ->
                        val path = Paths.get(rawPath.trim())
                        if (path.isAbsolute) root.relativize(path) else path
                    }
                JavaOrganizeImportsPlanner().preview(snap, filePaths)
            }
            "formatFile" -> JavaFormatFilePlanner(adapter).preview(
                snap,
                Paths.get(requireArgument(request, "file")),
            )
            "safeDelete" -> {
                val force = request.arguments["force"]?.toBoolean() ?: false
                JavaSafeDeletePlanner(adapter).preview(snap, requireSymbol(request), force)
            }
            "extractMethod" -> {
                val file = requireArgument(request, "file")
                val filePath = Paths.get(file.trim()).let { if (it.isAbsolute) root.relativize(it) else it }
                JavaExtractMethodPlanner().preview(
                    snap,
                    filePath,
                    requireArgument(request, "startLine").toInt(),
                    requireArgument(request, "endLine").toInt(),
                    requireArgument(request, "methodName"),
                )
            }
            "changeSignature.renameParameter" -> JavaChangeSignaturePlanner(adapter).previewRenameParameter(
                snap,
                requireSymbol(request),
                requireArgument(request, "oldParameterName"),
                requireArgument(request, "newParameterName"),
            )
            "changeSignature.addParameter" -> JavaChangeSignaturePlanner(adapter).previewAddParameter(
                snap,
                requireSymbol(request),
                requireArgument(request, "parameterType"),
                requireArgument(request, "parameterName"),
                requireArgument(request, "defaultExpression"),
            )
            "changeSignature.reorderParameters" -> JavaChangeSignaturePlanner(adapter).previewReorderParameters(
                snap,
                requireSymbol(request),
                requireArgument(request, "newOrder").split(',').map { it.trim() }.filter { it.isNotEmpty() },
            )
            "changeSignature.removeParameter" -> JavaChangeSignaturePlanner(adapter).previewRemoveParameter(
                snap,
                requireSymbol(request),
                requireArgument(request, "parameterName"),
            )
            "importExternalJavaClass" -> ExternalJavaClassImporter().preview(ImportRequest(
                code = requireArgument(request, "code"),
                targetPackage = requireArgument(request, "targetPackage"),
                targetModule = request.arguments["targetModule"],
                sourceUrl = request.arguments["sourceUrl"],
                sourceKind = request.arguments["sourceKind"]?.let { SourceKind.valueOf(it.uppercase()) } ?: SourceKind.SNIPPET,
                licensePolicy = request.arguments["licensePolicy"]?.let { LicensePolicy.valueOf(it.uppercase().replace('-', '_')) } ?: LicensePolicy.WARN,
                snapshot = snap,
            ))
            else -> error("Unknown operation: '${request.operation}'")
        }

    private fun requireSymbol(request: GoldenRequest): String =
        request.symbol ?: error("Operation '${request.operation}' requires 'symbol' in request.json")

    private fun requireArgument(request: GoldenRequest, name: String): String =
        request.arguments[name] ?: error("${request.operation} needs '$name' in arguments")

    // ── plan validation ───────────────────────────────────────────────────────

    private fun validatePlan(plan: PatchPlan, expected: GoldenExpectedPlan): List<String> {
        val errors = mutableListOf<String>()
        expected.status?.let { expStatus ->
            if (!plan.status.name.equals(expStatus, ignoreCase = true)) {
                errors += "Expected plan status '$expStatus' but got '${plan.status.name}'"
            }
        }
        expected.operation?.let { expOp ->
            if (plan.operation != expOp) {
                errors += "Expected plan operation '$expOp' but got '${plan.operation}'"
            }
        }
        expected.summaryContains?.let { sub ->
            if (!plan.summary.contains(sub, ignoreCase = true)) {
                errors += "Expected plan summary to contain '$sub' but got: ${plan.summary}"
            }
        }
        if (plan.affectedFiles.size < expected.minAffectedFiles) {
            errors += "Expected at least ${expected.minAffectedFiles} affected files but got ${plan.affectedFiles.size}"
        }
        expected.warningContains?.let { sub ->
            if (plan.warnings.none { it.contains(sub, ignoreCase = true) }) {
                errors += "Expected at least one plan warning to contain '$sub' but got: ${plan.warnings}"
            }
        }
        return errors
    }

    // ── directory helpers ─────────────────────────────────────────────────────

    private fun copyDirectory(src: Path, dst: Path) {
        if (!src.exists()) return
        src.toFile().walkTopDown().forEach { file ->
            val relative = src.toFile().toPath().relativize(file.toPath())
            val target = dst.resolve(relative)
            if (file.isDirectory) Files.createDirectories(target)
            else {
                Files.createDirectories(target.parent)
                Files.copy(file.toPath(), target)
            }
        }
    }

    /**
     * Compare files in [expectedDir] against files in [actualDir].
     * Returns a list of error descriptions (empty = match).
     *
     * Rules:
     * - Every file in [expectedDir] must exist in [actualDir] with equal content.
     * - Files in [actualDir] not present in [expectedDir] are flagged as unexpected,
     *   unless their path starts with an ignored prefix (`.refactorkit/`).
     */
    private fun compareDirectories(expectedDir: Path, actualDir: Path): List<String> {
        val errors = mutableListOf<String>()
        val expectedFiles = mutableSetOf<String>()

        expectedDir.toFile().walkTopDown()
            .filter { it.isFile }
            .forEach { expectedFile ->
                val relativePath = expectedDir.relativize(expectedFile.toPath())
                val rel = ProtocolPath.serialize(relativePath)
                expectedFiles += rel
                val actualFile = actualDir.resolve(relativePath)
                when {
                    !actualFile.toFile().exists() ->
                        errors += "Missing expected file: $rel"
                    actualFile.readText() != expectedFile.readText() ->
                        errors += "Content mismatch in: $rel"
                }
            }

        // Check for unexpected files in actual
        actualDir.toFile().walkTopDown()
            .onEnter { dir -> !isIgnoredDir(dir.name) }
            .filter { it.isFile }
            .forEach { actualFile ->
                val rel = ProtocolPath.serialize(actualDir.relativize(actualFile.toPath()))
                if (rel !in expectedFiles) {
                    errors += "Unexpected file in actual output: $rel"
                }
            }

        return errors
    }

    private fun isIgnoredDir(name: String): Boolean = name in setOf(".refactorkit", ".git")
}
