package org.refactorkit.jvm.extractmethod

import io.cucumber.java.After
import io.cucumber.java.Scenario
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringRequest
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.TextEdit
import org.refactorkit.java.JavaLanguageAdapter
import org.refactorkit.java.JavaProjectScanner
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Story BDD glue for features/java-extract-method-characterization.feature
 * (REQ-JAVA-EXTRACT-METHOD-CHAR-001, row C-EXTRACT of the approved finite J1 Java catalogue).
 *
 * It drives the real toolchain: a Java workspace scanned by JavaProjectScanner into a real
 * build model, the real JavaLanguageAdapter, and the real JavaExtractMethodPlanner preview
 * through a RefactoringRequest. The feature begins GREEN because production already implements
 * the planner; the glue asserts the ACTUAL behavior rather than inventing it.
 *
 * The fixture is a single Maven jar module whose pom declares <sourceDirectory>.</sourceDirectory>,
 * so the scanned root is the workspace root itself. That lets real scanned SourceFile paths be
 * the bare names the planner interpolates into its refusal messages (Util.kt, Generated.java),
 * while the Java target file lives under src/main/java/... and is scanned as
 * src/main/java/example/extract/ExtractTarget.java. This is the only way the real
 * JavaProjectScanner can yield the exact message strings the feature declares.
 *
 * Every refusal condition is a truthful fixture that makes the real planner return the DECLARED
 * MESSAGE — no message is invented, no typed extractMethod.xxx code is introduced, and no
 * production branch is weakened. The success Scenario 1 asserts the PREVIEW plan with operation
 * extractMethod, confidence 0.70, requiresUserApproval, risk MEDIUM, affectedFiles {filePath},
 * exactly one FileEdit.Modify with a replace + an insert TextEdit, and the two limited-MVP
 * warnings. Refusal Then steps assert REFUSED, the exact real message in summary and warnings,
 * an empty WorkspaceEdit, an empty affected-file set, no approval, no managed-write eligibility,
 * confidence 0.0, and risk HIGH.
 */
class KotlinJvmExtractMethodCharacterizationSteps {
    private val temporaryDirectories = mutableListOf<Path>()
    private val observedRefusals = mutableListOf<ObservedRefusal>()

    private var fixtureRoot: Path? = null
    private var snapshot: ProjectSnapshot? = null
    private var plan: PatchPlan? = null
    private var condition: String? = null

    private val TARGET = Path.of("src/main/java/example/extract/ExtractTarget.java")

    // ------------------------------------------------------------ Scenario 1 Given

    @Given("^a Java workspace snapshot that contains a recognized non-generated Java source file$")
    fun workspaceWithRecognizedNonGeneratedJavaSource() {
        fixtureRoot = buildFixture("default")
        snapshot = JavaProjectScanner().scan(requireNotNull(fixtureRoot))
        assertTrue(requireNotNull(snapshot).buildModels.isNotEmpty(), "fixture must yield a real build model")
    }

    @Given("^the file declares a final class whose body ends with a single class closing brace$")
    fun fileDeclaresFinalClassWithSingleClosingBrace() {
        val snap = requireNotNull(snapshot)
        val target = requireNotNull(snap.files.singleOrNull { it.path == TARGET }) { "missing $TARGET" }
        assertEquals("java", target.languageId, "target must be a Java source file")
        assertTrue(target.content.contains("public final class ExtractTarget {"), "target must declare a final class")
        assertTrue(target.content.trimEnd().endsWith("}"), "target must end with a single class closing brace")
    }

    // ---------------------------------------------------------- Scenario 2 Given

    @Given("^the Java adapter returns a REFUSED patch plan for an extractMethod preview$")
    fun adapterReturnsRefusedExtractMethodPlan() {
        fixtureRoot = buildFixture("default")
        snapshot = JavaProjectScanner().scan(requireNotNull(fixtureRoot))
        // A real refusal: an invalid Java method name. The plan is REFUSED and carries the real message.
        plan = preview(TARGET, 5, 6, "1extract")
        assertEquals(PatchStatus.REFUSED, requireNotNull(plan).status, requireNotNull(plan).summary)
    }

    // ------------------------------------------------------------- outline Given

    @Given("^a Java workspace snapshot for an extractMethod preview under the condition (.+)$")
    fun workspaceUnderCondition(condition: String) {
        this.condition = condition.trim()
        fixtureRoot = buildFixture(this.condition ?: "default")
        snapshot = JavaProjectScanner().scan(requireNotNull(fixtureRoot))
    }

    // ----------------------------------------------------------- Scenario 1 When

    @When("^the caller requests an extractMethod preview for a valid method name and a straight-line complete-line range of simple statements that use no local variables and no return, throw, break, continue, or yield$")
    fun previewSuccessfulExtraction() {
        plan = preview(TARGET, 5, 6, "extract")
    }

    // --------------------------------------------------------- Scenarios 2 & 3 When

    @When("^the caller inspects the refusal plan$")
    fun inspectRefusalPlan() {
        requireNotNull(plan)
    }

    @When("^the caller requests an extractMethod preview$")
    fun previewUnderCondition() {
        previewUnderCondition(requireNotNull(condition))
    }

    // ------------------------------------------------------------ Scenario 1 Thens

    @Then("^the adapter returns a PREVIEW patch plan for the operation extractMethod$")
    fun adapterReturnsPreviewPlan() {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.PREVIEW, p.status, "${p.summary}")
        assertEquals("extractMethod", p.operation, p.toString())
    }

    @Then("^the plan carries confidence 0.70$")
    fun planCarriesConfidence() {
        assertEquals(0.70, requireNotNull(plan).confidence, requireNotNull(plan).toString())
    }

    @Then("^the plan requires user approval$")
    fun planRequiresUserApproval() {
        assertTrue(requireNotNull(plan).requiresUserApproval, "expected requiresUserApproval=true: ${requireNotNull(plan)}")
    }

    @Then("^the plan risk is MEDIUM$")
    fun planRiskIsMedium() {
        assertEquals(RiskLevel.MEDIUM, requireNotNull(plan).riskLevel, requireNotNull(plan).toString())
    }

    @Then("^the plan lists the source file in its affected-file set$")
    fun planListsSourceFileInAffectedSet() {
        assertEquals(setOf(TARGET), requireNotNull(plan).affectedFiles, "affectedFiles must list the source file")
    }

    @Then("^the plan carries a WorkspaceEdit with exactly one FileEdit.Modify for that file$")
    fun planCarriesSingleModifyForFile() {
        val p = requireNotNull(plan)
        assertEquals(1, p.workspaceEdit.edits.size, "expected exactly one FileEdit: ${p.workspaceEdit.edits}")
        val modify = p.workspaceEdit.edits.single() as? FileEdit.Modify ?: error("expected a FileEdit.Modify")
        assertEquals(TARGET, modify.path, "the single Modify must target the source file")
    }

    @Then("^that FileEdit.Modify contains a replace TextEdit and an insert TextEdit$")
    fun modifyContainsReplaceAndInsert() {
        val p = requireNotNull(plan)
        val modify = p.workspaceEdit.edits.single() as FileEdit.Modify
        assertEquals(2, modify.textEdits.size, "expected one replace + one insert TextEdit: ${modify.textEdits}")
        assertTrue(modify.textEdits.any { it.newText.contains("extract();") }, "expected a replace edit rewriting to a call")
        assertTrue(modify.textEdits.any { it.newText.contains("private void extract() {") }, "expected an insert edit with the new method")
    }

    @Then("^the replace edit rewrites the selected block with a call to the new method at the original indentation$")
    fun replaceEditRewritesWithCallAtIndentation() {
        val p = requireNotNull(plan)
        val modify = p.workspaceEdit.edits.single() as FileEdit.Modify
        val replace = requireNotNull(modify.textEdits.singleOrNull { it.newText.contains("extract();") }) {
            "missing replace edit"
        }
        assertTrue(replace.newText.trim().startsWith("extract();"), "replace edit must call the new method")
        assertTrue(replace.newText.trimEnd().startsWith("        "), "replace edit must keep the original indentation: '${replace.newText}'")
    }

    @Then("^the insert edit inserts a no-argument private void method declaration before the final class closing brace$")
    fun insertEditInsertsMethodBeforeFinalBrace() {
        val p = requireNotNull(plan)
        val modify = p.workspaceEdit.edits.single() as FileEdit.Modify
        val insert = requireNotNull(modify.textEdits.singleOrNull { it.newText.contains("private void extract() {") }) {
            "missing insert edit"
        }
        assertTrue(insert.newText.contains("private void extract() {"), "insert edit must declare a private void method")
        val snap = requireNotNull(snapshot)
        val target = requireNotNull(snap.files.singleOrNull { it.path == TARGET })
        // The insert point is on the final class closing brace line (the last non-blank line of the file).
        val finalBraceLine = target.content.trimEnd().lines().size - 1
        assertTrue(
            insert.range.start.line == finalBraceLine,
            "insert position must be on the final class closing brace line: ${insert.range.start}",
        )
        assertTrue(
            insert.newText.contains("private void extract() {\n"),
            "insert edit must open a no-argument private void method body",
        )
    }

    @Then("^the plan warns that the limited MVP supports only no-argument private void methods$")
    fun planWarnsLimitedMvp() {
        val p = requireNotNull(plan)
        assertTrue(p.warnings.any { it == "Limited extract-method MVP: only no-argument private void methods are supported." },
            "expected the limited-MVP warning: ${p.warnings}")
    }

    @Then("^the plan warns that the selection was refused if local variables, return values, exceptions, or complex control flow were detected$")
    fun planWarnsSelectionRefusedOnComplexFlow() {
        val p = requireNotNull(plan)
        assertTrue(p.warnings.any { it == "Selection was refused if local variables, return values, exceptions, or complex control flow were detected." },
            "expected the complex-flow warning: ${p.warnings}")
    }

    // -------------------------------------------------------------- Scenario 2 Thens

    @Then("^the refusal operation name is deterministically extractMethod$")
    fun refusalOperationName() {
        val p = requireNotNull(plan)
        assertEquals("extractMethod", p.operation, p.toString())
    }

    @Then("^the refusal carries an empty WorkspaceEdit$")
    fun refusalCarriesEmptyWorkspaceEdit() {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.REFUSED, p.status, p.toString())
        assertTrue(p.workspaceEdit.edits.isEmpty(), "expected empty WorkspaceEdit: ${p.toString()}")
    }

    @Then("^the refusal carries an empty affected-file set$")
    fun refusalCarriesEmptyAffectedSet() {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.REFUSED, p.status, p.toString())
        assertTrue(p.affectedFiles.isEmpty(), "expected empty affected-file set: ${p.toString()}")
    }

    @Then("^the refusal grants no approval and no managed-write eligibility$")
    fun refusalGrantsNoApprovalNoManagedWrite() {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.REFUSED, p.status, p.toString())
        assertTrue(!p.requiresUserApproval, "expected no user approval on the refusal: ${p.toString()}")
        assertTrue(p.authorityLease == null, "expected no managed-write authority lease: ${p.toString()}")
    }

    @Then("^the refusal carries confidence 0.0 and risk HIGH$")
    fun refusalCarriesZeroConfidenceHighRisk() {
        val p = requireNotNull(plan)
        assertEquals(0.0, p.confidence, "expected zero confidence on a refused plan: ${p.toString()}")
        assertEquals(RiskLevel.HIGH, p.riskLevel, p.toString())
    }

    @Then("^the refusal carries its real reason message in both the summary and the warnings$")
    fun refusalCarriesRealReasonInSummaryAndWarnings() {
        val p = requireNotNull(plan)
        assertTrue(p.summary.isNotBlank(), "refusal summary must carry the real reason")
        assertEquals(listOf(p.summary), p.warnings, "refusal warnings must carry the same real reason")
    }

    // -------------------------------------------------------------- outline Thens

    @Then("^the adapter returns a REFUSED patch plan for the operation extractMethod$")
    fun adapterReturnsRefusedExtractMethod() {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.REFUSED, p.status, p.toString())
        assertEquals("extractMethod", p.operation, p.toString())
    }

    @Then("^the refusal summary and warning carry the message \"([^\"]+)\"$")
    fun refusalCarriesMessage(declaredMessage: String) {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.REFUSED, p.status, p.toString())
        assertEquals(
            declaredMessage, p.summary,
            "declared message '$declaredMessage' did not equal the actual summary '${p.summary}'",
        )
        assertEquals(
            listOf(declaredMessage), p.warnings,
            "declared message '$declaredMessage' did not equal the actual warnings '${p.warnings}'",
        )
        observedRefusals += ObservedRefusal(declaredMessage, p.summary, p.status)
    }

    @Then("^the refusal carries an empty WorkspaceEdit and an empty affected-file set$")
    fun refusalCarriesEmptyEditAndAffectedSet() {
        val p = requireNotNull(plan)
        assertTrue(p.workspaceEdit.edits.isEmpty(), "expected empty WorkspaceEdit: ${p.toString()}")
        assertTrue(p.affectedFiles.isEmpty(), "expected empty affected-file set: ${p.toString()}")
    }

    @Then("^the refusal grants no approval and carries confidence 0.0 with risk HIGH$")
    fun refusalGrantsNoApprovalZeroConfidenceHighRisk() {
        val p = requireNotNull(plan)
        assertTrue(!p.requiresUserApproval, "expected no user approval on the refusal: ${p.toString()}")
        assertEquals(0.0, p.confidence, "expected zero confidence on a refused plan: ${p.toString()}")
        assertEquals(RiskLevel.HIGH, p.riskLevel, p.toString())
    }

    // ------------------------------------------------------------------ helpers

    private fun preview(file: Path, startLine: Int, endLine: Int, methodName: String): PatchPlan {
        val snap = requireNotNull(snapshot)
        val request = RefactoringRequest(
            operation = "extractMethod",
            snapshot = snap,
            arguments = mapOf(
                "file" to file.toString(),
                "startLine" to startLine.toString(),
                "endLine" to endLine.toString(),
                "methodName" to methodName,
            ),
        )
        return JavaLanguageAdapter().applyRefactoring(request)
    }

    private fun previewUnderCondition(condition: String) {
        when (condition) {
            "the caller supplies a method name that is not a valid Java identifier" ->
                plan = preview(TARGET, 5, 6, "1extract")
            "the caller supplies a line range that is not 1-based and inclusive" ->
                plan = preview(TARGET, 0, 5, "extract")
            "the requested file is absent from the snapshot" ->
                plan = preview(Path.of("Missing.java"), 5, 6, "extract")
            "the requested file is not a Java source file" ->
                plan = preview(Path.of("Util.kt"), 5, 6, "extract")
            "the requested file is generated and read-only" ->
                plan = preview(Path.of("Generated.java"), 5, 6, "extract")
            "a method named extract already exists or is already called in the file" ->
                plan = preview(TARGET, 5, 6, "extract")
            "the line range exceeds the file line count" ->
                plan = preview(TARGET, 10, 20, "extract")
            "the selected range contains only whitespace" ->
                plan = preview(TARGET, 2, 2, "extract")
            "the selection reads a local variable or parameter declared before the range" ->
                plan = preview(TARGET, 6, 6, "extract")
            "the selection declares a local variable used after the range" ->
                plan = preview(TARGET, 5, 5, "extract")
            "the selection contains a return statement" ->
                plan = preview(TARGET, 5, 5, "extract")
            "the selection contains a throw statement" ->
                plan = preview(TARGET, 5, 5, "extract")
            "the selection contains break control flow" ->
                plan = preview(TARGET, 5, 5, "extract")
            "the selection contains a continue statement" ->
                plan = preview(TARGET, 5, 5, "extract")
            "the selection contains a yield statement" ->
                plan = preview(TARGET, 5, 5, "extract")
            "the selection contains a class, interface, enum, or record declaration" ->
                plan = preview(TARGET, 5, 5, "extract")
            "the selection contains a method or control block declaration" ->
                plan = preview(TARGET, 5, 5, "extract")
            "the selection has unmatched opening or closing braces" ->
                plan = preview(TARGET, 5, 5, "extract")
            "the file has no final class closing brace" ->
                plan = preview(TARGET, 5, 6, "extract")
            "the selected range includes the class closing brace" ->
                // The real planner's findFinalClassBrace uses the LAST '}' in the file. A trailing
                // balanced static-block brace makes a straight-line balanced selection end at the
                // final brace, truthfully exercising the insertOffset <= endOffset refusal branch.
                plan = preview(TARGET, 10, 11, "extract")
            else -> error("Unknown condition: '$condition'")
        }
    }

    private fun buildFixture(condition: String): Path {
        val root = temporaryDirectory("rk-jvm-extractmethod")
        Files.writeString(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion>
              <groupId>example</groupId><artifactId>extract</artifactId><version>1</version>
              <build><sourceDirectory>.</sourceDirectory></build>
            </project>
        """.trimIndent())
        val target = root.resolve("src/main/java/example/extract/ExtractTarget.java")
        target.parent.createDirectories()
        target.writeText(extractTargetContent(condition))
        root.resolve("Util.kt").writeText("package example\nfun util() {}\n")
        root.resolve("Generated.java").writeText("package example.generated;\n@Generated\npublic class Generated {}\n")
        return root
    }

    private fun extractTargetContent(condition: String): String = when (condition) {
        "a method named extract already exists or is already called in the file" ->
            """|package example.extract;
               |
               |public final class ExtractTarget {
               |    public void run() {
               |        System.out.println("alpha");
               |        System.out.println("beta");
               |    }
               |
               |    void extract() {
               |    }
               |}
               |""".trimMargin()
        "the selection reads a local variable or parameter declared before the range" ->
            """|package example.extract;
               |
               |public final class ExtractTarget {
               |    public void run() {
               |        int count = 1;
               |        System.out.println(count);
               |    }
               |}
               |""".trimMargin()
        "the selection declares a local variable used after the range" ->
            """|package example.extract;
               |
               |public final class ExtractTarget {
               |    public void run() {
               |        int total = 1;
               |        System.out.println(total);
               |    }
               |}
               |""".trimMargin()
        "the selection contains a return statement" ->
            """|package example.extract;
               |
               |public final class ExtractTarget {
               |    public void run() {
               |        return;
               |    }
               |}
               |""".trimMargin()
        "the selection contains a throw statement" ->
            """|package example.extract;
               |
               |public final class ExtractTarget {
               |    public void run() {
               |        throw new RuntimeException("x");
               |    }
               |}
               |""".trimMargin()
        "the selection contains break control flow" ->
            """|package example.extract;
               |
               |public final class ExtractTarget {
               |    public void run() {
               |        break;
               |    }
               |}
               |""".trimMargin()
        "the selection contains a continue statement" ->
            """|package example.extract;
               |
               |public final class ExtractTarget {
               |    public void run() {
               |        continue;
               |    }
               |}
               |""".trimMargin()
        "the selection contains a yield statement" ->
            """|package example.extract;
               |
               |public final class ExtractTarget {
               |    public void run() {
               |        yield;
               |    }
               |}
               |""".trimMargin()
        "the selection contains a class, interface, enum, or record declaration" ->
            """|package example.extract;
               |
               |public final class ExtractTarget {
               |    public void run() {
               |        class Inner {}
               |    }
               |}
               |""".trimMargin()
        "the selection contains a method or control block declaration" ->
            """|package example.extract;
               |
               |public final class ExtractTarget {
               |    public void run() {
               |        if (true) {
               |        }
               |    }
               |}
               |""".trimMargin()
        "the selection has unmatched opening or closing braces" ->
            """|package example.extract;
               |
               |public final class ExtractTarget {
               |    public void run() {
               |        {
               |        System.out.println("alpha");
               |    }
               |}
               |""".trimMargin()
        "the file has no final class closing brace" ->
            """|package example.extract;
               |
               |public final class ExtractTarget {
               |    public void run() {
               |        System.out.println("alpha");
               |        System.out.println("beta");
               |""".trimMargin()
        "the selected range includes the class closing brace" ->
            """|package example.extract;
               |
               |public final class ExtractTarget {
               |    public void run() {
               |        System.out.println("alpha");
               |        System.out.println("beta");
               |    }
               |}
               |
               |static {
               |}
               |""".trimMargin()
        else ->
            """|package example.extract;
               |
               |public final class ExtractTarget {
               |    public void run() {
               |        System.out.println("alpha");
               |        System.out.println("beta");
               |    }
               |}
               |""".trimMargin()
    }

    private fun temporaryDirectory(prefix: String): Path {
        val base = Path.of(System.getProperty("user.dir")).resolve("build/test-tmp").toAbsolutePath().normalize()
        Files.createDirectories(base)
        return Files.createTempDirectory(base, prefix).also(temporaryDirectories::add)
    }

    private fun deleteNoFollow(root: Path) {
        if (!root.exists()) return
        Files.walk(root).use { stream -> stream.toList() }
            .sortedByDescending(Path::getNameCount)
            .forEach { path -> Files.delete(path) }
    }

    @After
    fun cleanup(scenario: Scenario) {
        val reportDir = Path.of(System.getProperty("user.dir")).resolve("build/reports/cucumber")
        reportDir.createDirectories()
        val report = reportDir.resolve("java-extract-method-characterization-messages.txt")
        val lines = mutableListOf<String>()
        lines += "scenario:${scenario.name}"
        observedRefusals.forEach { lines += "  ${it.declaredMessage} -> ${it.actualMessage} (${it.status})" }
        plan?.let { lines += "  plan:${it.status} operation=${it.operation} confidence=${it.confidence} risk=${it.riskLevel}" }
        Files.write(report, lines, StandardOpenOption.CREATE, StandardOpenOption.APPEND)

        var cleanupFailure: Throwable? = null
        temporaryDirectories.asReversed().forEach { directory ->
            try {
                deleteNoFollow(directory)
            } catch (failure: Throwable) {
                cleanupFailure?.addSuppressed(failure) ?: run { cleanupFailure = failure }
            }
        }
        temporaryDirectories.clear()
        cleanupFailure?.let { throw it }
    }

    private data class ObservedRefusal(
        val declaredMessage: String,
        val actualMessage: String,
        val status: PatchStatus,
    )
}
