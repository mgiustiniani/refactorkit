package org.refactorkit.jvm.javaorganize

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.java.JavaOrganizeImportsPlanner
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.java.JdtJavaSemanticAnalyzer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Cucumber glue for the Java organize-imports characterization feature.
 *
 * The glue drives the real production stack: [JavaProjectScanner] build-model
 * discovery + [JdtJavaSemanticAnalyzer] binding evidence + [JavaOrganizeImportsPlanner]
 * preview. It asserts real plan fields, warnings, and messages; it does not invent
 * granular codes beyond the single typed refusal code java.generatedSource.
 */
class KotlinJvmJavaOrganizeImportsCharacterizationSteps {
    private val temporaryDirectories = mutableListOf<Path>()
    private lateinit var fixtureRoot: Path
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var filePath: Path
    private var plan: PatchPlan? = null
    private var replacementText: String? = null

    @After
    fun deleteTemporaryDirectories() {
        var cleanupFailure: Throwable? = null
        temporaryDirectories.asReversed().forEach { directory ->
            try {
                deleteRecursively(directory)
            } catch (failure: Throwable) {
                cleanupFailure?.addSuppressed(failure) ?: run { cleanupFailure = failure }
            }
        }
        temporaryDirectories.clear()
        cleanupFailure?.let { throw it }
    }

    // ── Scenario 1 and 2 shared base fixture ─────────────────────────────────

    @Given("a Java workspace snapshot that contains one recognized non-generated Java source file with an import block")
    fun snapshotWithOneNonGeneratedJavaFile() {
        fixtureRoot = temporaryDirectory("rk-java-organize-imports-char")
        val file = fixtureRoot.resolve("src/main/java/com/example/Foo.java")
        file.parent.createDirectories()
        file.writeText(
            """
            package com.example;

            import java.util.List;
            import java.util.Map;
            import java.util.Set;
            import java.util.List;
            import com.example.Other;

            public class Foo {
                List<String> values;
                Map<String, String> index;
            }
            """.trimIndent(),
        )
        val other = fixtureRoot.resolve("src/main/java/com/example/Other.java")
        other.parent.createDirectories()
        other.writeText("package com.example; public class Other {}\n")
        filePath = Paths.get("src/main/java/com/example/Foo.java")
        snapshot = JavaProjectScanner().scan(fixtureRoot)
    }

    @Given("the JDT binding analysis is clean for that file with no error warnings")
    fun jdtBindingCleanForFile() {
        val analysis = JdtJavaSemanticAnalyzer().analyze(snapshot)
        assertTrue(
            analysis.warnings.none { it.path == filePath },
            "expected clean JDT binding for $filePath, got warnings: ${analysis.warnings.filter { it.path == filePath }.map { it.message }}",
        )
    }

    @Given("the import block contains duplicate imports, a same-package import, and unused exact imports")
    fun importBlockHasDuplicatesSamePackageAndUnused() {
        val content = fixtureRoot.resolve(filePath).readText()
        val listImportCount = Regex("import java\\.util\\.List;")
            .findAll(content).count()
        assertTrue(listImportCount >= 2, "expected a duplicate import, count=$listImportCount")
        assertTrue(content.contains("import com.example.Other;"), "expected a same-package import")
        assertTrue(content.contains("import java.util.Set;"), "expected an unused exact import")
        assertTrue("Set" !in content.substringAfter("import java.util.Set;"), "expected java.util.Set to be unused")
    }

    @Given("the JDT binding analysis reports an error warning for that file")
    fun jdtBindingReportsErrorWarning() {
        val file = fixtureRoot.resolve(filePath)
        val original = file.readText()
        file.writeText(
            original.replace(
                "import com.example.Other;",
                "import com.example.Other;\nimport com.unknown.Missing;",
            ),
        )
        snapshot = JavaProjectScanner().scan(fixtureRoot)
        val analysis = JdtJavaSemanticAnalyzer().analyze(snapshot)
        assertTrue(
            analysis.warnings.any { it.path == filePath },
            "expected an error warning for $filePath, got: ${analysis.warnings.filter { it.path == filePath }.map { it.message }}",
        )
    }

    // ── Scenario 3 sorting outline ───────────────────────────────────────────

    @Given("^a single non-generated Java source file whose import block contains the imports (.+) and (.+)$")
    fun importBlockContainsTwoImports(first: String, second: String) {
        fixtureRoot = temporaryDirectory("rk-java-organize-imports-sort")
        val firstSimple = first.substringAfterLast('.')
        val secondSimple = second.substringAfterLast('.')
        val file = fixtureRoot.resolve("src/main/java/fixture/app/Foo.java")
        file.parent.createDirectories()
        // Deliberately write the two imports in reverse of the documented group order.
        file.writeText(
            """
            package fixture.app;

            import $second;
            import $first;

            public class Foo {
                $firstSimple firstValue = null;
                $secondSimple secondValue = null;
            }
            """.trimIndent(),
        )
        filePath = Paths.get("src/main/java/fixture/app/Foo.java")
        snapshot = JavaProjectScanner().scan(fixtureRoot)
    }

    @Given("every import in the block is used by the file")
    fun everyImportInBlockUsed() {
        val content = fixtureRoot.resolve(filePath).readText()
        assertTrue(content.contains("firstValue"), "expected the first import simple name to be used")
        assertTrue(content.contains("secondValue"), "expected the second import simple name to be used")
    }

    // ── Scenario 4 rules ─────────────────────────────────────────────────────

    @Given("a single non-generated Java source file whose import block contains duplicate imports, a same-package import, used exact imports, unused exact imports, and a wildcard import")
    fun importBlockWithAllRules() {
        fixtureRoot = temporaryDirectory("rk-java-organize-imports-rules")
        val file = fixtureRoot.resolve("src/main/java/com/example/Foo.java")
        file.parent.createDirectories()
        file.writeText(
            """
            package com.example;

            import java.util.List;
            import java.util.Map;
            import java.util.Set;
            import java.util.List;
            import java.time.*;
            import com.example.Other;
            import static java.util.Collections.emptyList;
            import static java.util.Collections.singletonList;

            public class Foo {
                List<String> values = emptyList();
                Map<String, String> index = null;
            }
            """.trimIndent(),
        )
        val other = fixtureRoot.resolve("src/main/java/com/example/Other.java")
        other.parent.createDirectories()
        other.writeText("package com.example; public class Other {}\n")
        filePath = Paths.get("src/main/java/com/example/Foo.java")
        snapshot = JavaProjectScanner().scan(fixtureRoot)
    }

    @Given("the JDT binding analysis is clean and proves the unused exact imports have no non-import use")
    fun jdtBindingCleanProvesUnused() {
        val analysis = JdtJavaSemanticAnalyzer().analyze(snapshot)
        assertTrue(
            analysis.warnings.none { it.path == filePath },
            "expected clean JDT binding for $filePath, got warnings: ${analysis.warnings.filter { it.path == filePath }.map { it.message }}",
        )
    }

    // ── Scenario 5 already-organized ─────────────────────────────────────────

    @Given("a Java workspace snapshot that contains one recognized non-generated Java source file whose import block is already organized")
    fun snapshotWithAlreadyOrganizedImportBlock() {
        fixtureRoot = temporaryDirectory("rk-java-organize-imports-already")
        val file = fixtureRoot.resolve("src/main/java/com/example/Foo.java")
        file.parent.createDirectories()
        file.writeText(
            """
            package com.example;

            import java.util.List;
            import java.util.Map;

            public class Foo {
                List<String> values;
                Map<String, String> index;
            }
            """.trimIndent(),
        )
        filePath = Paths.get("src/main/java/com/example/Foo.java")
        snapshot = JavaProjectScanner().scan(fixtureRoot)
    }

    // ── Scenario 6 generated-source refusal ──────────────────────────────────

    @Given("a Java workspace snapshot that contains a generated Java source file that {string}")
    fun snapshotWithGeneratedSource(reason: String) {
        fixtureRoot = temporaryDirectory("rk-java-organize-imports-generated")
        val file = when (reason) {
            "path is inside a generated-source or build-output location" ->
                fixtureRoot.resolve("build/generated/sources/annotationProcessor/java/main/Generated.java")
            "source declares a generated-code annotation" ->
                fixtureRoot.resolve("src/main/java/Generated.java")
            "source header identifies generated code" ->
                fixtureRoot.resolve("src/main/java/Generated.java")
            else -> error("unknown generated-source reason: $reason")
        }
        file.parent.createDirectories()
        val content = when (reason) {
            "path is inside a generated-source or build-output location" ->
                "package com.example;\nimport java.util.List;\npublic class Generated { List<String> v; }\n"
            "source declares a generated-code annotation" ->
                "package com.example;\n@Generated\nimport java.util.List;\npublic class Generated { List<String> v; }\n"
            "source header identifies generated code" ->
                "// generated by a schema processor, do not edit\npackage com.example;\nimport java.util.List;\npublic class Generated { List<String> v; }\n"
            else -> error("unknown generated-source reason: $reason")
        }
        file.writeText(content)
        filePath = when (reason) {
            "path is inside a generated-source or build-output location" ->
                Paths.get("build/generated/sources/annotationProcessor/java/main/Generated.java")
            else -> Paths.get("src/main/java/Generated.java")
        }
        snapshot = JavaProjectScanner().scan(fixtureRoot)
    }

    // ── When steps ───────────────────────────────────────────────────────────

    @When("the caller requests an organizeImports preview for that single file")
    fun previewOrganizeImportsSingleFile() {
        val preview = JavaOrganizeImportsPlanner().preview(snapshot, listOf(filePath))
        plan = preview
        if (preview.status == PatchStatus.PREVIEW) {
            val staged = WorkspaceEditSimulator.apply(snapshot, preview.workspaceEdit)
            replacementText = staged.files.single { it.path.normalize() == filePath.normalize() }.content
        }
    }

    @When("the caller requests an organizeImports preview for that file")
    fun previewOrganizeImportsFile() {
        val preview = JavaOrganizeImportsPlanner().preview(snapshot, listOf(filePath))
        plan = preview
        if (preview.status == PatchStatus.PREVIEW) {
            val staged = WorkspaceEditSimulator.apply(snapshot, preview.workspaceEdit)
            replacementText = staged.files.single { it.path.normalize() == filePath.normalize() }.content
        }
    }

    @When("the caller requests an organizeImports preview for that generated file")
    fun previewOrganizeImportsGeneratedFile() {
        plan = JavaOrganizeImportsPlanner().preview(snapshot, listOf(filePath))
    }

    // ── Then steps: shared PREVIEW assertions ────────────────────────────────

    @Then("the adapter returns a PREVIEW patch plan for the operation organizeImports")
    fun previewPatchPlanForOrganizeImports() {
        val observed = assertNotNull(plan)
        assertEquals(PatchStatus.PREVIEW, observed.status)
        assertEquals("organizeImports", observed.operation)
    }

    @Then("the plan has confidence 1.0")
    fun planConfidence() {
        assertEquals(1.0, assertNotNull(plan).confidence)
    }

    @Then("the plan does not require user approval")
    fun planNoUserApproval() {
        assertEquals(false, assertNotNull(plan).requiresUserApproval)
    }

    @Then("the plan risk is LOW")
    fun planRiskLow() {
        assertEquals(RiskLevel.LOW, assertNotNull(plan).riskLevel)
    }

    @Then("the plan evidence is JDT_BINDING")
    fun planEvidenceJdtBinding() {
        assertEquals(RefactoringEvidence.JDT_BINDING, assertNotNull(plan).evidence)
    }

    @Then("the plan evidence is STRUCTURAL")
    fun planEvidenceStructural() {
        assertEquals(RefactoringEvidence.STRUCTURAL, assertNotNull(plan).evidence)
    }

    @Then("the plan lists the source file in its affected-file set")
    fun planListsSourceFile() {
        assertTrue(assertNotNull(plan).affectedFiles.contains(filePath), "expected $filePath in affected files")
    }

    @Then("the plan carries a WorkspaceEdit with exactly one FileEdit.Modify for that file")
    fun planSingleFileEditModify() {
        val edits = assertNotNull(plan).workspaceEdit.edits
        assertEquals(1, edits.size, "expected exactly one FileEdit")
        assertIs<FileEdit.Modify>(edits.single())
        assertEquals(filePath.normalize(), (edits.single() as FileEdit.Modify).path.normalize())
    }

    @Then("that FileEdit.Modify carries a single replace TextEdit over the import block range")
    fun singleReplaceTextEditOverImportBlock() {
        val modify = assertNotNull(plan).workspaceEdit.edits.single() as FileEdit.Modify
        assertEquals(1, modify.textEdits.size, "expected a single replace TextEdit")
        // Authored fixture has five imports on lines2..6; the block includes its final newline.
        assertEquals(
            TextEdit(SourceRange(SourcePosition(2, 0), SourcePosition(7, 0)),
                "import java.util.List;\nimport java.util.Map;\n"),
            modify.textEdits.single(),
        )
        assertEquals(setOf(filePath), assertNotNull(plan).affectedFiles)
        val expected = """
            package com.example;

            import java.util.List;
            import java.util.Map;

            public class Foo {
                List<String> values;
                Map<String, String> index;
            }
        """.trimIndent()
        val staged = WorkspaceEditSimulator.apply(snapshot, assertNotNull(plan).workspaceEdit)
        assertEquals(snapshot.files.associate { it.path to if (it.path == filePath) expected else it.content },
            staged.files.associate { it.path to it.content })
    }

    @Then("the plan warns that JDT binding evidence checked exact imports and removed the unused import count")
    fun planWarnsJdtBindingChecked() {
        val warnings = assertNotNull(plan).warnings
        assertTrue(
            warnings.any { it.contains("JDT binding evidence checked exact imports") && it.contains("removed") },
            "expected the JDT-binding-checked warning, got: $warnings",
        )
    }

    @Then("the plan warns that JDT evidence was unclean and unused imports were preserved while lexical sorting, deduplication, and same-package removal continued")
    fun planWarnsJdtUncleanFallback() {
        val warnings = assertNotNull(plan).warnings
        assertTrue(
            warnings.any {
                it.contains("JDT evidence was unclean") &&
                    it.contains("unused imports were preserved") &&
                    it.contains("deduplication") &&
                    it.contains("same-package removal")
            },
            "expected the unclean-fallback warning, got: $warnings",
        )
    }

    @Then("the plan warns that wildcard and unresolved imports are preserved because their usage cannot be proven safely")
    fun planWarnsWildcardAndUnresolvedPreserved() {
        val warnings = assertNotNull(plan).warnings
        assertTrue(
            warnings.any { it.contains("Wildcard and unresolved imports are preserved") && it.contains("cannot be proven safely") },
            "expected the wildcard/unresolved preservation warning, got: $warnings",
        )
    }

    @Then("the plan does not warn that JDT evidence was unclean")
    fun planDoesNotWarnJdtUnclean() {
        assertTrue(
            assertNotNull(plan).warnings.none { it.contains("JDT evidence was unclean") },
            "expected no unclean-JDT warning",
        )
    }

    // ── Scenario 3 sort assertions ───────────────────────────────────────────

    @Then("the single FileEdit.Modify rewrites the import block")
    fun singleFileEditModifyRewrites() {
        val edits = assertNotNull(plan).workspaceEdit.edits
        assertEquals(1, edits.size)
        assertIs<FileEdit.Modify>(edits.single())
    }

    @Then("^the rewritten block places the import (.+) before the import (.+)$")
    fun rewrittenBlockPlacesFirstBeforeSecond(first: String, second: String) {
        val observed = replacementText ?: "(no preview replacement)"
        val importLines = observed.lineSequence().map(String::trim).filter { it.startsWith("import ") }.toList()
        val firstIndex = importLines.indexOfFirst { it.contains(first) }
        val secondIndex = importLines.indexOfFirst { it.contains(second) }
        assertTrue(
            firstIndex >= 0 && secondIndex >= 0 && firstIndex < secondIndex,
            "expected import $first before import $second in rewritten block; observed=$importLines",
        )
    }

    // ── Scenario 4 rule assertions ───────────────────────────────────────────

    @Then("the resulting single FileEdit.Modify rewrites the import block")
    fun resultingSingleModifyRewrites() {
        val edits = assertNotNull(plan).workspaceEdit.edits
        assertEquals(1, edits.size)
        assertIs<FileEdit.Modify>(edits.single())
    }

    @Then("the rewritten block keeps no duplicate import")
    fun rewrittenBlockNoDuplicate() {
        val observed = replacementText ?: "(no preview replacement)"
        val importLines = observed.lineSequence().map(String::trim).filter { it.startsWith("import ") }.toList()
        assertEquals(importLines.distinct(), importLines, "expected no duplicate import in rewritten block; observed=$importLines")
    }

    @Then("the rewritten block keeps no same-package import")
    fun rewrittenBlockNoSamePackage() {
        val observed = replacementText ?: "(no preview replacement)"
        assertTrue(
            !observed.lineSequence().any { it.trim().startsWith("import com.example.") },
            "expected no same-package import in rewritten block; observed=[$observed]",
        )
    }

    @Then("the rewritten block keeps the used exact imports")
    fun rewrittenBlockKeepsUsedExact() {
        val observed = replacementText ?: "(no preview replacement)"
        assertTrue(observed.contains("import java.util.List;"), "expected used exact import java.util.List kept")
        assertTrue(observed.contains("import java.util.Map;"), "expected used exact import java.util.Map kept")
    }

    @Then("the rewritten block drops the unused exact imports proven by JDT binding")
    fun rewrittenBlockDropsUnusedExact() {
        val observed = replacementText ?: "(no preview replacement)"
        assertTrue(!observed.contains("import java.util.Set;"), "expected unused exact import java.util.Set dropped")
        assertTrue(!observed.contains("import static java.util.Collections.singletonList;"), "expected unused static import dropped")
    }

    @Then("the rewritten block keeps the wildcard import")
    fun rewrittenBlockKeepsWildcard() {
        val observed = replacementText ?: "(no preview replacement)"
        assertTrue(observed.contains("import java.time.*;"), "expected wildcard import java.time.* kept")
    }

    @Then("the rewritten block places static imports last in their own group")
    fun rewrittenBlockStaticLast() {
        val observed = replacementText ?: "(no preview replacement)"
        val importLines = observed.lineSequence().map(String::trim).filter { it.startsWith("import ") }.toList()
        val staticLines = importLines.filter { it.startsWith("import static ") }
        val nonStaticLast = importLines.indexOfFirst { it.startsWith("import static ") }
        assertTrue(
            staticLines.isNotEmpty() &&
                importLines.takeWhile { !it.startsWith("import static ") }.all { !it.startsWith("import static ") } &&
                importLines.drop(importLines.indexOfFirst { it.startsWith("import static ") }).all { it.startsWith("import static ") },
            "expected static imports last in their own group; observed=$importLines",
        )
    }

    // ── Scenario 5 empty-result assertions ───────────────────────────────────

    @Then("the plan carries an empty WorkspaceEdit")
    fun planEmptyWorkspaceEdit() {
        assertEquals(0, assertNotNull(plan).workspaceEdit.edits.size)
    }

    @Then("the plan carries an empty affected-file set")
    fun planEmptyAffectedFiles() {
        assertTrue(assertNotNull(plan).affectedFiles.isEmpty())
    }

    @Then("the plan lists zero rewritten files in its summary")
    fun planSummaryZeroFiles() {
        assertTrue(assertNotNull(plan).summary.contains("0 file(s)"), "expected summary to list zero rewritten files")
    }

    // ── Scenario 6 refusal assertions ────────────────────────────────────────

    @Then("the adapter returns a REFUSED patch plan for the operation organizeImports")
    fun refusedPatchPlanForOrganizeImports() {
        val observed = assertNotNull(plan)
        assertEquals(PatchStatus.REFUSED, observed.status)
        assertEquals("organizeImports", observed.operation)
    }

    @Then("the refusal carries an empty WorkspaceEdit")
    fun refusalEmptyWorkspaceEdit() {
        assertEquals(0, assertNotNull(plan).workspaceEdit.edits.size)
    }

    @Then("the refusal carries an empty affected-file set")
    fun refusalEmptyAffectedFiles() {
        assertTrue(assertNotNull(plan).affectedFiles.isEmpty())
    }

    @Then("the refusal grants no approval and no managed-write eligibility")
    fun refusalNoApproval() {
        assertTrue(!assertNotNull(plan).requiresUserApproval, "expected no user approval")
    }

    @Then("the refusal leaves no pending actionable plan")
    fun refusalNoPendingPlan() {
        assertEquals(PatchStatus.REFUSED, assertNotNull(plan).status)
    }

    @Then("the refusal carries confidence 0.0 and risk HIGH")
    fun refusalConfidenceAndRisk() {
        val observed = assertNotNull(plan)
        assertEquals(0.0, observed.confidence)
        assertEquals(RiskLevel.HIGH, observed.riskLevel)
    }

    @Then("the refusal carries a Diagnostic with severity ERROR and code {string}")
    fun refusalDiagnostic(code: String) {
        assertEquals("java.generatedSource", code)
        val observed = assertNotNull(plan)
        assertTrue(
            observed.diagnosticsBefore.any { it.severity == Diagnostic.Severity.ERROR && it.code == "java.generatedSource" },
            "expected Diagnostic ERROR java.generatedSource, got: ${observed.diagnosticsBefore}",
        )
    }

    @Then("the refusal summary carries the message {string}")
    fun refusalSummaryMessage(message: String) {
        assertEquals(message, assertNotNull(plan).summary)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun temporaryDirectory(prefix: String): Path {
        val base = Path.of(System.getProperty("user.dir")).resolve("build/test-tmp").toAbsolutePath().normalize()
        Files.createDirectories(base)
        return Files.createTempDirectory(base, prefix).also(temporaryDirectories::add)
    }

    private fun deleteRecursively(root: Path) {
        if (!root.exists()) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
