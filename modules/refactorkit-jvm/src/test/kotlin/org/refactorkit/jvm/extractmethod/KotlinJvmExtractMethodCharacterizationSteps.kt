package org.refactorkit.jvm.extractmethod

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.TransactionLog
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.core.TextEdit
import org.refactorkit.java.JavaLanguageAdapter
import org.refactorkit.java.JavaAdapterRegistration
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.daemon.DaemonSession
import org.refactorkit.mcp.McpSession
import org.refactorkit.core.CapabilityStability
import org.refactorkit.core.MutationAuthority
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import javax.tools.ToolProvider
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
    private var managedPlanEvidence: Map<String, String> = emptyMap()
    private var daemonApplied = false
    private var daemonRolledBack = false
    private var mcpApplied = false
    private var mcpRolledBack = false
    private var lexicalDaemonRefused = false
    private var lexicalMcpRefused = false

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

    @When("^the caller requests a binding-aware extractMethod preview for the fixture's checked-exception multi-statement selection as \"readContents\" through daemon and MCP$")
    fun previewBindingAwareCheckedExceptionExtraction() {
        val root = requireNotNull(fixtureRoot)
        val scanned = requireNotNull(snapshot)
        plan = preview(TARGET, 9, 13, "readContents")
        val baseline = workspaceImage(root)
        val daemon = DaemonSession()
        try {
            val opened = daemon.dispatch("project.open", buildJsonObject {
                put("root", root.toString())
            }).jsonObject
            val daemonPreview = daemon.dispatch("refactor.preview", buildJsonObject {
                put("operation", "extractMethod")
                put("languageId", "java")
                put("expectedSnapshotHash", opened.getValue("snapshotHash").jsonPrimitive.content)
                put("arguments", buildJsonObject {
                    put("file", TARGET.toString()); put("startLine", 9); put("endLine", 13); put("methodName", "readContents")
                })
            }).jsonObject
            kotlin.test.assertEquals("PREVIEW", daemonPreview.getValue("status").jsonPrimitive.content, daemonPreview.toString())
            kotlin.test.assertEquals("JDT_BINDING", daemonPreview.getValue("evidence").jsonPrimitive.content)
            kotlin.test.assertTrue(daemonPreview.getValue("diagnosticsAfterPreview").jsonArray.none {
                it.jsonObject.getValue("severity").jsonPrimitive.content == "ERROR"
            })
            val applied = daemon.dispatch("refactor.apply", buildJsonObject {
                put("planId", daemonPreview.getValue("planId").jsonPrimitive.content)
                put("expectedSnapshotHash", opened.getValue("snapshotHash").jsonPrimitive.content)
            }).jsonObject
            daemonApplied = applied.getValue("status").jsonPrimitive.content == "applied"
            val rolledBack = daemon.dispatch("patch.rollback", buildJsonObject {
                put("transactionId", applied.getValue("transactionId").jsonPrimitive.content)
            }).jsonObject
            daemonRolledBack = rolledBack["rolledBack"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true ||
                rolledBack["status"]?.jsonPrimitive?.content == "rolledBack"
            assertWorkspaceImage(baseline, root)
            managedPlanEvidence = managedPlanEvidence + ("daemon" to daemonPreview.getValue("evidence").jsonPrimitive.content)
        } finally {
            daemon.close()
        }

        val mcp = McpSession()
        try {
            val scan = mcpCall(mcp, "project_scan", buildJsonObject {
                put("root", root.toString())
            })
            kotlin.test.assertFalse(scan.getValue("isError").jsonPrimitive.content.toBooleanStrict(), scan.toString())
            val previewResult = mcpCall(mcp, "preview_refactoring", buildJsonObject {
                put("operation", "extractMethod"); put("languageId", "java")
                put("arguments", buildJsonObject {
                    put("file", TARGET.toString()); put("startLine", 9); put("endLine", 13); put("methodName", "readContents")
                })
            })
            val previewText = toolText(previewResult)
            kotlin.test.assertTrue(previewText.contains("Status   : PREVIEW"), previewText)
            kotlin.test.assertTrue(previewText.contains("Evidence : JDT_BINDING"), previewText)
            kotlin.test.assertFalse(previewText.contains("[ERROR]"), previewText)
            val planId = Regex("Plan ID\\s*:\\s*(\\S+)").find(previewText)?.groupValues?.get(1)
                ?: error("MCP extractMethod preview omitted its plan ID: $previewText")
            val apply = mcpCall(mcp, "apply_refactoring", buildJsonObject { put("planId", planId) })
            val applyText = toolText(apply)
            mcpApplied = applyText.startsWith("Applied successfully.")
            val transactionId = Regex("Transaction ID: ([^\\s]+)").find(applyText)?.groupValues?.get(1)
                ?: error("MCP extractMethod apply omitted its transaction ID: $applyText")
            val rollback = mcpCall(mcp, "rollback_refactoring", buildJsonObject {
                put("transactionId", transactionId)
            })
            mcpRolledBack = toolText(rollback).startsWith("Rolled back transaction")
            assertWorkspaceImage(baseline, root)
            managedPlanEvidence = managedPlanEvidence + ("MCP" to "JDT_BINDING")
        } finally {
            mcp.close()
        }
        verifyLexicalCompatibilityIsReviewOnly(buildFixture("default"))
    }

    // ------------------------------------------------------------ Scenario 1 Thens

    @Then("^the adapter returns a PREVIEW patch plan for the operation extractMethod$")
    fun adapterReturnsPreviewPlan() {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.PREVIEW, p.status, "${p.summary}")
        assertEquals("extractMethod", p.operation, p.toString())
    }

    @Then("^the single live-out preview has the exact returned-local image and preserves compiled output$")
    fun singleLiveOutHasExactImageAndCompiledResult() {
        val original = requireNotNull(snapshot)
        val preview = requireNotNull(plan)
        assertEquals(org.refactorkit.core.RefactoringEvidence.JDT_BINDING, preview.evidence)
        assertTrue(preview.requiresUserApproval)
        assertEquals(setOf(TARGET), preview.affectedFiles)
        assertTrue(preview.diagnosticsAfterPreview.none { it.severity.name == "ERROR" })
        val staged = WorkspaceEditSimulator.apply(original, preview.workspaceEdit)
        val expected = """
            package example.extract;

            public final class ExtractTarget {
                public void run() {
                    int total = extract();
                    System.out.println(total);
                }

                private int extract() {
                    int total = 1;
                    return total;
                }
            }
        """.trimIndent() + "\n"
        assertEquals(expected, staged.files.single { it.path == TARGET }.content)
        val launcher = mapOf(Path.of("fixture/InvokeExtract.java") to
            "package example.extract; public final class InvokeExtract { public static void main(String[] args) { new ExtractTarget().run(); } }\n")
        val beforeOutput = compileAndRun(original.trackedFiles.associate { it.path to it.content } + launcher,
            "", "liveout-before", "example.extract.InvokeExtract")
        val afterOutput = compileAndRun(staged.trackedFiles.associate { it.path to it.content } + launcher,
            "", "liveout-after", "example.extract.InvokeExtract")
        assertEquals("1" + System.lineSeparator(), beforeOutput)
        assertEquals(beforeOutput, afterOutput)
        assertEquals(original.trackedFiles.associate { it.path to it.content }, workspaceImage(requireNotNull(fixtureRoot)))
        assertTrue(!Files.exists(requireNotNull(fixtureRoot).resolve(".refactorkit/transactions")))
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
        assertEquals(
            TextEdit(SourceRange(SourcePosition(4, 0), SourcePosition(6, 0)), "        extract();\n"),
            replace,
            "the exact two selected complete lines must be replaced by exactly one indented call",
        )
    }

    @Then("^the insert edit inserts a no-argument private void method declaration before the final class closing brace$")
    fun insertEditInsertsMethodBeforeFinalBrace() {
        val p = requireNotNull(plan)
        val modify = p.workspaceEdit.edits.single() as FileEdit.Modify
        val insert = requireNotNull(modify.textEdits.singleOrNull { it.newText.contains("private void extract() {") }) {
            "missing insert edit"
        }
        val expectedMethod = "\n     private void extract() {\n" +
            "        System.out.println(\"alpha\");\n" +
            "        System.out.println(\"beta\");\n" +
            "    }\n"
        assertEquals(
            TextEdit(SourceRange(SourcePosition(7, 0), SourcePosition(7, 0)), expectedMethod),
            insert,
            "the complete selected statements must appear exactly once in the inserted method at the final brace",
        )
        val snap = requireNotNull(snapshot)
        val expectedTarget = """|package example.extract;
            |
            |public final class ExtractTarget {
            |    public void run() {
            |        extract();
            |    }
            |
            |     private void extract() {
            |        System.out.println("alpha");
            |        System.out.println("beta");
            |    }
            |}
            |""".trimMargin()
        val expectedImage = snap.trackedFiles.associate { it.path to it.content } + (TARGET to expectedTarget)
        val staged = WorkspaceEditSimulator.apply(snap, p.workspaceEdit)
        assertEquals(expectedImage, staged.trackedFiles.associate { it.path to it.content }, "exact authored post-image; no omitted, duplicated or residual statements")
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

    @Then("^the preview passes the input parameters and returns the selected local from the multi-statement control-flow block with its checked exception$")
    fun previewPassesParameterReturnsLocalAndDeclaresCheckedException() {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.PREVIEW, p.status, p.summary)
        val modify = p.workspaceEdit.edits.single() as? FileEdit.Modify ?: error("expected one source modification")
        assertEquals(TARGET, modify.path)
        assertEquals(2, modify.textEdits.size)
        val staged = WorkspaceEditSimulator.apply(requireNotNull(snapshot), p.workspaceEdit)
        val content = staged.files.single { it.path == TARGET }.content
        assertTrue("String normalized = readContents(path, fallback, prefix);" in content, "the extracted call must retain the live-out local and pass the method parameters and preceding local input:\n$content")
        assertTrue(
            "private static java.lang.String readContents(java.nio.file.Path path, java.lang.String fallback, java.lang.String prefix) throws java.io.IOException {" in content,
            "the helper must return the live-out type, accept the resolved method parameters and preceding local, and propagate IOException:\n$content",
        )
        assertTrue("String raw = Files.readString(path);" in content, "the helper must preserve the first selected statement:\n$content")
        assertTrue("String normalized = prefix + raw.trim();" in content, "the helper must preserve the captured local read:\n$content")
        assertTrue("if (normalized.isEmpty())" in content && "normalized = fallback;" in content,
            "the helper must preserve the selected conditional data flow:\n$content")
        assertTrue("return normalized;" in content, "the helper must return the selected live-out local:\n$content")
        assertEquals(null, p.authorityLease, "the planner preview itself must not create a transaction authority lease")
        assertEquals(setOf(TARGET), p.affectedFiles)
    }

    @Then("^the staged before and after Java fixtures compile and preserve the method result$")
    fun stagedJavaFixturesCompileAndPreserveResult() {
        val original = requireNotNull(snapshot).trackedFiles.associate { it.path to it.content }
        val staged = WorkspaceEditSimulator.apply(requireNotNull(snapshot), requireNotNull(plan).workspaceEdit)
            .trackedFiles.associate { it.path to it.content }
        val input = "  extracted value  "
        val before = compileAndRun(original, input, "extract-before")
        val after = compileAndRun(staged, input, "extract-after")
        assertEquals("extracted value", before, "original fixture output")
        assertEquals(before, after, "extracting the local result must preserve observable output")
        val blankBefore = compileAndRun(original, "   ", "extract-before-branch")
        val blankAfter = compileAndRun(staged, "   ", "extract-after-branch")
        assertEquals("fallback", blankBefore, "original fixture conditional output")
        assertEquals(blankBefore, blankAfter, "the extracted helper must preserve the selected conditional branch")
    }

    @Then("^extractMethod is advertised as stable managed mutation for binding-backed plans$")
    fun extractMethodCapabilityIsStableForBindingBackedPlans() {
        val capability = requireNotNull(JavaAdapterRegistration.capability("extractMethod"))
        assertEquals(CapabilityStability.STABLE, capability.stability)
        assertEquals(MutationAuthority.MANAGED_STABLE, capability.mutationAuthority)
        assertEquals(org.refactorkit.core.RefactoringEvidence.JDT_BINDING, requireNotNull(plan).evidence)
        assertTrue(requireNotNull(plan).diagnosticsBefore.none { it.severity.name == "ERROR" })
        assertTrue(requireNotNull(plan).diagnosticsAfterPreview.none { it.severity.name == "ERROR" })
        assertEquals(mapOf("daemon" to "JDT_BINDING", "MCP" to "JDT_BINDING"), managedPlanEvidence)
    }

    @Then("^daemon and MCP apply and roll back the plan with exact source restoration while line-based fallback remains non-applicable$")
    fun daemonAndMcpRestoreExactSourceAfterManagedApply() {
        assertTrue(daemonApplied, "daemon must apply the binding-backed plan")
        assertTrue(daemonRolledBack, "daemon must roll back the exact transaction")
        assertTrue(mcpApplied, "MCP must apply the binding-backed plan")
        assertTrue(mcpRolledBack, "MCP must roll back the exact transaction")
        assertTrue(lexicalDaemonRefused, "daemon must refuse managed Apply for lexical fallback evidence")
        assertTrue(lexicalMcpRefused, "MCP must refuse managed Apply for lexical fallback evidence")
    }

    @Then("^the managed planner refuses a selected write to a preceding local without edits$")
    fun managedPlannerRefusesWritesToPrecedingLocal() {
        val root = buildFixture("the selection writes to a preceding direct local")
        snapshot = JavaProjectScanner().scan(root)
        val before = Files.readString(root.resolve(TARGET))
        plan = preview(TARGET, 6, 7, "incrementPrior")
        val refused = requireNotNull(plan)
        assertEquals(PatchStatus.REFUSED, refused.status, refused.summary)
        assertEquals("Writes to values declared outside the selected statements are not supported", refused.summary)
        assertTrue(refused.workspaceEdit.edits.isEmpty(), "a write to a preceding local must not produce edits")
        assertTrue(refused.affectedFiles.isEmpty(), "a refusal must not claim affected files")
        assertEquals(before, Files.readString(root.resolve(TARGET)), "planning a refusal must leave source bytes unchanged")
    }

    private fun verifyLexicalCompatibilityIsReviewOnly(root: Path) {
        val target = root.resolve(TARGET)
        val original = Files.readString(target)
        val baseline = workspaceImage(root)
        val daemon = DaemonSession()
        try {
            val opened = daemon.dispatch("project.open", buildJsonObject { put("root", root.toString()) }).jsonObject
            val preview = daemon.dispatch("refactor.preview", buildJsonObject {
                put("operation", "extractMethod"); put("languageId", "java")
                put("expectedSnapshotHash", opened.getValue("snapshotHash").jsonPrimitive.content)
                put("arguments", buildJsonObject {
                    put("file", TARGET.toString()); put("startLine", 5); put("endLine", 6); put("methodName", "extract")
                })
            }).jsonObject
            assertEquals("PREVIEW", preview.getValue("status").jsonPrimitive.content)
            assertEquals("LEXICAL_FALLBACK", preview.getValue("evidence").jsonPrimitive.content)
            val txBefore = TransactionLog(root.resolve(".refactorkit/transactions")).listRecords().size
            val refusal = runCatching {
                daemon.dispatch("refactor.apply", buildJsonObject {
                    put("planId", preview.getValue("planId").jsonPrimitive.content)
                    put("expectedSnapshotHash", opened.getValue("snapshotHash").jsonPrimitive.content)
                })
            }.exceptionOrNull()
            lexicalDaemonRefused = refusal is JsonRpcException && refusal.message.orEmpty().contains("Lexical fallback previews")
            assertTrue(lexicalDaemonRefused, refusal?.toString().orEmpty())
            assertEquals(txBefore, TransactionLog(root.resolve(".refactorkit/transactions")).listRecords().size)
            assertEquals(original, Files.readString(target))
        } finally {
            daemon.close()
        }

        val mcp = McpSession()
        try {
            val scan = mcpCall(mcp, "project_scan", buildJsonObject { put("root", root.toString()) })
            assertEquals("false", scan.getValue("isError").jsonPrimitive.content)
            val preview = mcpCall(mcp, "preview_refactoring", buildJsonObject {
                put("operation", "extractMethod"); put("languageId", "java")
                put("arguments", buildJsonObject {
                    put("file", TARGET.toString()); put("startLine", 5); put("endLine", 6); put("methodName", "extract")
                })
            })
            val text = toolText(preview)
            assertTrue(text.contains("Status   : PREVIEW"), text)
            assertTrue(text.contains("Evidence : LEXICAL_FALLBACK"), text)
            val planId = Regex("Plan ID\\s*:\\s*(\\S+)").find(text)?.groupValues?.get(1)
                ?: error("MCP lexical preview omitted plan ID: $text")
            val txBefore = TransactionLog(root.resolve(".refactorkit/transactions")).listRecords().size
            val result = runCatching {
                toolText(mcpCall(mcp, "apply_refactoring", buildJsonObject { put("planId", planId) }))
            }.getOrElse { it.message.orEmpty() }
            lexicalMcpRefused = result.contains("evidence.insufficient") || result.contains("Lexical fallback previews")
            assertTrue(lexicalMcpRefused, result)
            assertEquals(txBefore, TransactionLog(root.resolve(".refactorkit/transactions")).listRecords().size)
            assertWorkspaceImage(baseline, root)
        } finally {
            mcp.close()
        }
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
        val bindingAware = condition == "the selection spans multiple statements, reads method parameters and a preceding direct local, updates one local through a conditional branch and invokes a checked-exception method" ||
            condition == "the selection writes to a preceding direct local"
        val sourceDirectory = if (bindingAware) "" else "<sourceDirectory>.</sourceDirectory>"
        val release = if (bindingAware) "<maven.compiler.release>21</maven.compiler.release>" else ""
        Files.writeString(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion>
              <groupId>example</groupId><artifactId>extract</artifactId><version>1</version>
              <properties>$release</properties>
              <build>$sourceDirectory</build>
            </project>
        """.trimIndent())
        val target = root.resolve("src/main/java/example/extract/ExtractTarget.java")
        target.parent.createDirectories()
        target.writeText(extractTargetContent(condition))
        root.resolve("Util.kt").writeText("package example\nfun util() {}\n")
        root.resolve("Generated.java").writeText("package example.generated;\n@javax.annotation.processing.Generated(\"fixture\")\npublic class Generated {}\n")
        return root
    }

    private fun extractTargetContent(condition: String): String = when (condition) {
        "the selection spans multiple statements, reads method parameters and a preceding direct local, updates one local through a conditional branch and invokes a checked-exception method" ->
            """|package example.extract;
               |import java.io.IOException;
               |import java.nio.file.Files;
               |import java.nio.file.Path;
               |
               |public final class ExtractTarget {
               |    public static String normalize(Path path, String fallback) throws IOException {
               |        String prefix = "";
               |        String raw = Files.readString(path);
               |        String normalized = prefix + raw.trim();
               |        if (normalized.isEmpty()) {
               |            normalized = fallback;
               |        }
               |        return normalized;
               |    }
               |
               |    public static void main(String[] args) throws IOException {
               |        System.out.print(normalize(Path.of(args[0]), "fallback"));
               |    }
               |}
               |""".trimMargin()
        "the selection writes to a preceding direct local" ->
            """|package example.extract;
               |
               |public final class ExtractTarget {
               |    public int run() {
               |        int total = 1;
               |        total += 1;
               |        int result = total;
               |        return result;
               |    }
               |}
               |""".trimMargin()
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

    private fun compileAndRun(image: Map<Path, String>, input: String, label: String,
                              entryPoint: String = "example.extract.ExtractTarget"): String {
        val directory = temporaryDirectory("$label-")
        val sourceRoot = directory.resolve("src")
        val classes = directory.resolve("classes")
        Files.createDirectories(sourceRoot)
        Files.createDirectories(classes)
        val sourceFiles = image.filterKeys { it.toString().endsWith(".java") }.map { (relative, content) ->
            val path = sourceRoot.resolve(relative).normalize()
            require(path.startsWith(sourceRoot)) { "fixture source escaped its temporary root: $relative" }
            Files.createDirectories(requireNotNull(path.parent))
            Files.writeString(path, content)
            path
        }
        assertTrue(sourceFiles.isNotEmpty(), "expected at least one Java compilation unit")
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) { "tests require a full JDK compiler" }
        val diagnostics = javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>()
        val fileManager = compiler.getStandardFileManager(diagnostics, null, Charsets.UTF_8)
        val success = fileManager.use { manager ->
            val units = manager.getJavaFileObjectsFromPaths(sourceFiles)
            compiler.getTask(null, manager, diagnostics, listOf("--release", "21", "-d", classes.toString()), null, units).call()
        }
        assertTrue(success, "$label fixture did not compile: ${diagnostics.diagnostics.joinToString("\n")}")
        Files.writeString(directory.resolve("input.txt"), input)
        val java = Path.of(System.getProperty("java.home")).resolve("bin").resolve(if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val process = ProcessBuilder(java.toString(), "-cp", classes.toString(), entryPoint, directory.resolve("input.txt").toString())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        assertEquals(0, exit, "$label fixture failed at runtime: $output")
        return output
    }

    private fun deleteNoFollow(root: Path) {
        if (!root.exists()) return
        Files.walk(root).use { stream -> stream.toList() }
            .sortedByDescending(Path::getNameCount)
            .forEach { path -> Files.delete(path) }
    }

    private fun workspaceImage(root: Path): Map<Path, String> = Files.walk(root).use { files ->
        files.toList().filter(Files::isRegularFile)
            .filter { !it.startsWith(root.resolve(".refactorkit")) }
            .associate { path -> root.relativize(path) to Files.readString(path) }
    }

    private fun assertWorkspaceImage(expected: Map<Path, String>, root: Path) {
        assertEquals(expected, workspaceImage(root), "managed rollback must restore every original workspace input")
    }

    private fun mcpCall(session: McpSession, name: String, arguments: JsonObject): JsonObject =
        session.dispatch("tools/call", buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        }).jsonObject

    private fun toolText(result: JsonObject): String = result.getValue("content").jsonArray.single()
        .jsonObject.getValue("text").jsonPrimitive.content

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
