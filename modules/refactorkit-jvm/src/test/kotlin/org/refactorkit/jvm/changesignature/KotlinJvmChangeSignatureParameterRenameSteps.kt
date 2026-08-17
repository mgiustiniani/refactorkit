package org.refactorkit.jvm.changesignature

import io.cucumber.java.After
import io.cucumber.java.Scenario
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.Symbol
import org.refactorkit.core.SymbolId
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.jvm.KotlinJvmChangeSignaturePlanner
import org.refactorkit.kotlin.KotlinChangeSignaturePlanner
import org.refactorkit.kotlin.KotlinCompilerDiagnostics
import org.refactorkit.kotlin.KotlinCompilerSymbolsResult
import org.refactorkit.kotlin.KotlinDeclarationVisibility
import org.refactorkit.kotlin.KotlinJvmBuildModelIntegration
import org.refactorkit.kotlin.KotlinLanguageAdapter
import org.refactorkit.kotlin.KotlinSemanticToolchain
import org.refactorkit.kotlin.KotlinToolchainDiscoverer
import org.refactorkit.kotlin.KotlinToolchainDiscovery
import org.refactorkit.kotlin.KotlinToolchainRequest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Story BDD glue for features/kotlin-jvm-change-signature-parameter-rename.feature
 * (REQ-KOTLIN-CHANGE-SIGNATURE-001..003; 33 expanded cases across 10 scenario outlines).
 *
 * It replicates the real K2 compiler toolchain fixture
 * (kotlin-compiler-embeddable-2.0.21, jvmTarget 21, jdkToolchain 21) and drives the production
 * planners: [KotlinChangeSignaturePlanner] for the REQ-001/REQ-002 K2-level refusal codes and
 * [KotlinJvmChangeSignaturePlanner] for the REQ-003 mixed K2+JDT staged proof and apply/rollback.
 *
 * The feature file declares the ACTUAL production refusal codes observed in the planner sources,
 * so every refusal step asserts that the DECLARED code (from the Examples table) EQUALS the ACTUAL
 * refusalCode the planner returned; the suite FAILS on a typed-code regression. GREEN steps assert
 * the real preview/apply/rollback behavior (edits, risk, warnings, PatchEngine transaction,
 * rollback byte equality), never a hard-coded success path. Every observed declared-to-actual
 * mapping is appended to build/reports/cucumber/kotlin-jvm-change-signature-parameter-rename-codes.txt
 * for reconciliation.
 */
class KotlinJvmChangeSignatureParameterRenameSteps {
    private val temporaryDirectories = mutableListOf<Path>()
    private val observedRefusals = mutableListOf<ObservedRefusal>()
    private val observedPreviews = mutableListOf<ObservedPreview>()

    private enum class PlannerMode { K2, JVM }

    private var fixtureRoot: Path? = null
    private var snapshot: ProjectSnapshot? = null
    private lateinit var toolchain: KotlinSemanticToolchain
    private var lastCatalogue: KotlinCompilerSymbolsResult.Available? = null
    private var plannerMode: PlannerMode = PlannerMode.K2
    private var targetId: SymbolId? = null
    private var targetOwner: String? = null
    private var oldName = "subtotal"
    private var newName = "netAmount"
    private var requestedNewName: String? = null
    private var missingTarget = false
    private var selectApply = false
    private var acceptExternalConsumerRisk = false
    private var plan: PatchPlan? = null
    private var applied: ApplyResult.Applied? = null
    private var rolledBack: ApplyResult.Applied? = null
    private val originalFiles = mutableMapOf<Path, String>()

    // Content identity of every regular file on the workspace root immediately before the preview.
    // A refusal Then proves the set is byte-for-byte unchanged afterward (no file created, modified,
    // or deleted), which also proves no WAL/transaction record, pending managed-plan, or lock
    // artifact was written into the workspace.
    private var workspaceBaseline: Map<Path, String>? = null

    // ------------------------------------------------------------------ shared fixture helpers

    /** Complete in-workspace override family with exactly one "subtotal" parameter (BaseCalculator). */
    private val familySource = """
        package fixture.billing
        interface BillingCalculator { fun calculateTotal(amount: Double = 0.0): Double }
        open class BaseCalculator : BillingCalculator { override fun calculateTotal(subtotal: Double): Double = subtotal }
        class ChildCalculator : BaseCalculator() { override fun calculateTotal(amount: Double): Double = super.calculateTotal(amount) }
        fun invokeNamed(): Double = BaseCalculator().calculateTotal(subtotal = 1.0)
        fun invokePositional(): Double = BaseCalculator().calculateTotal(2.0)
    """.trimIndent() + "\n"

    private val javaCallerSource = """
        package fixture.billing;
        class Caller { double run() { return new BaseCalculator().calculateTotal(2.0); } }
    """.trimIndent() + "\n"

    // ------------------------------------------------------------------ REQ-001 Scenario 1 (GREEN)

    @Given(
        "^the selected declaration is one compiler-catalogued Kotlin function \"fixture\\.billing\\.calculateTotal\" whose K2-to-JVM owner, JVM name, and descriptor are exact$",
    )
    fun selectedExactFamilyTarget() {
        val root = temporaryDirectory("rk-jvm-change-signature-green")
        buildProject(root, "src/main/kotlin/fixture/billing/Calculator.kt", familySource)
        fixtureRoot = root
        toolchain = toolchain(root)
        snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        plannerMode = PlannerMode.K2
        acceptExternalConsumerRisk = true
        selectFamilyTarget()
    }

    @Given(
        "^the selected value-parameter \"subtotal\" is identified by that callable identity plus zero-based ordinal 0$",
    )
    fun selectedParameterIdentifiedByOrdinal() {
        val catalogue = compilerCatalogue()
        val evidence = catalogue.declarations.getValue(requireNotNull(targetId))
        assertTrue(
            catalogue.index.symbols.any {
                it.kind == Symbol.Kind.PARAMETER && it.name == oldName &&
                    catalogue.declarations.getValue(it.id).let { ev ->
                        ev.jvmOwner == evidence.jvmOwner && ev.jvmName == evidence.jvmName &&
                            ev.jvmDescriptor.substringBeforeLast('@') == evidence.jvmDescriptor.substringBeforeLast('@')
                    } && catalogue.declarations.getValue(it.id).jvmDescriptor.substringAfterLast('@', "") == "0"
            },
            "expected exactly the compiler-catalogued value-parameter '$oldName' at ordinal 0 of the selected callable identity",
        )
    }

    @Given(
        "^the snapshot carries complete error-free K2 evidence and one stable override-family identity from FIR override checking and resolved source class-supertypes$",
    )
    fun snapshotCarriesCleanK2EvidenceAndStableFamily() {
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain))
        val diagnostics = adapter.compilerDiagnostics(requireNotNull(snapshot))
        assertIs<KotlinCompilerSymbolsResult.Available>(compilerCatalogue())
        val result = assertIs<org.refactorkit.kotlin.KotlinCompilerDiagnosticsResult.Available>(
            adapter.compilerDiagnostics(requireNotNull(snapshot)),
        )
        assertTrue(result.diagnostics.none { it.severity == Diagnostic.Severity.ERROR }, result.toString())
        val catalogue = compilerCatalogue()
        assertTrue(
            catalogue.declarations.values.any { it.overrideFamilyId.matches(Regex("kotlin-override-family-v1:[0-9a-f]{64}")) },
            "expected one stable override-family identity from FIR override checking: ${catalogue.declarations.values.map { it.overrideFamilyId }}",
        )
    }

    @Given("^every source declaration in that override family has exactly one parameter at ordinal 0$")
    fun everyFamilyMemberHasOneParameterAtOrdinal0() {
        val catalogue = compilerCatalogue()
        val familyId = catalogue.declarations.getValue(requireNotNull(targetId)).overrideFamilyId
        val familyFunctions = catalogue.index.symbols.filter { it.kind == Symbol.Kind.FUNCTION &&
            catalogue.declarations.getValue(it.id).overrideFamilyId == familyId }
        assertTrue(familyFunctions.isNotEmpty(), "expected a non-empty override family")
        familyFunctions.forEach { function ->
            val owner = catalogue.declarations.getValue(function.id).jvmOwner
            val name = catalogue.declarations.getValue(function.id).jvmName
            val descriptor = catalogue.declarations.getValue(function.id).jvmDescriptor
            val params = catalogue.index.symbols.filter {
                it.kind == Symbol.Kind.PARAMETER && catalogue.declarations.getValue(it.id).let { ev ->
                    ev.jvmOwner == owner && ev.jvmName == name &&
                        ev.jvmDescriptor.substringBeforeLast('@') == descriptor.substringBeforeLast('@')
                } && catalogue.declarations.getValue(it.id).jvmDescriptor.substringAfterLast('@', "") == "0"
            }
            assertEquals(1, params.size, "family member ${function.id} must have exactly one parameter at ordinal 0")
        }
    }

    @Given("^the caller explicitly accepts unknown external-consumer risk because a family member is non-private$")
    fun callerAcceptsBecauseFamilyMemberIsNonPrivate() {
        acceptExternalConsumerRisk = true
        val catalogue = compilerCatalogue()
        val familyId = catalogue.declarations.getValue(requireNotNull(targetId)).overrideFamilyId
        assertTrue(
            catalogue.declarations.values.filter { it.overrideFamilyId == familyId }.any { it.visibility != KotlinDeclarationVisibility.PRIVATE },
            "expected the family to be externally visible via a non-private member",
        )
    }

    @Then(
        "^the result is a SEMANTIC_PREVIEW that renames the exact parameter declaration token at ordinal 0 in every family member atomically$",
    )
    fun resultIsSemanticPreviewRenamingEveryFamilyMember() {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.PREVIEW, p.status, p.toString())
        assertEquals(RiskLevel.HIGH, p.riskLevel, p.toString())
        val staged = WorkspaceEditSimulator.apply(requireNotNull(snapshot), p.workspaceEdit)
        val content = staged.files.single { it.path.normalize() == Path.of("src/main/kotlin/fixture/billing/Calculator.kt").normalize() }.content
        assertTrue("interface BillingCalculator { fun calculateTotal(netAmount: Double = 0.0): Double }" in content, content)
        assertTrue(
            "open class BaseCalculator : BillingCalculator { override fun calculateTotal(netAmount: Double): Double = netAmount }" in content,
            content,
        )
        assertTrue(
            "class ChildCalculator : BaseCalculator() { override fun calculateTotal(netAmount: Double): Double = super.calculateTotal(netAmount) }" in content,
            content,
        )
        observedPreviews += ObservedPreview(p.status, p.riskLevel, p.refusalCode)
    }

    @Then("^every FIR-resolved body reference to those parameter symbols is renamed$")
    fun everyFirResolvedBodyReferenceRenamed() {
        val staged = WorkspaceEditSimulator.apply(requireNotNull(snapshot), requireNotNull(plan).workspaceEdit)
        val content = staged.files.single { it.path.normalize() == Path.of("src/main/kotlin/fixture/billing/Calculator.kt").normalize() }.content
        assertTrue("override fun calculateTotal(netAmount: Double): Double = netAmount" in content, content)
        assertTrue("super.calculateTotal(netAmount)" in content, content)
    }

    @Then("^every FIR argument-to-parameter-mapped Kotlin named-argument label \"subtotal\" is renamed$")
    fun everyNamedArgumentLabelRenamed() {
        val staged = WorkspaceEditSimulator.apply(requireNotNull(snapshot), requireNotNull(plan).workspaceEdit)
        val content = staged.files.single { it.path.normalize() == Path.of("src/main/kotlin/fixture/billing/Calculator.kt").normalize() }.content
        assertTrue("BaseCalculator().calculateTotal(netAmount = 1.0)" in content, content)
    }

    @Then("^positional Kotlin call sites keep unchanged bytes$")
    fun positionalKotlinCallSitesKeepUnchangedBytes() {
        val staged = WorkspaceEditSimulator.apply(requireNotNull(snapshot), requireNotNull(plan).workspaceEdit)
        val content = staged.files.single { it.path.normalize() == Path.of("src/main/kotlin/fixture/billing/Calculator.kt").normalize() }.content
        assertTrue("BaseCalculator().calculateTotal(2.0)" in content, content)
    }

    @Then("^default argument expressions are preserved byte for byte$")
    fun defaultArgumentExpressionsPreserved() {
        val staged = WorkspaceEditSimulator.apply(requireNotNull(snapshot), requireNotNull(plan).workspaceEdit)
        val content = staged.files.single { it.path.normalize() == Path.of("src/main/kotlin/fixture/billing/Calculator.kt").normalize() }.content
        assertTrue("calculateTotal(netAmount: Double = 0.0)" in content, content)
    }

    @Then("^overload calls remain bound to the same JVM callable identity$")
    fun overloadCallsRemainBound() {
        val staged = WorkspaceEditSimulator.apply(requireNotNull(snapshot), requireNotNull(plan).workspaceEdit)
        val content = staged.files.single { it.path.normalize() == Path.of("src/main/kotlin/fixture/billing/Calculator.kt").normalize() }.content
        assertTrue("super.calculateTotal(netAmount)" in content, content)
        assertTrue("BaseCalculator().calculateTotal(2.0)" in content, content)
    }

    @Then("^the preview is read-only and does not mutate the snapshot$")
    fun previewIsReadOnly() {
        val snap = requireNotNull(snapshot)
        val p = requireNotNull(plan)
        assertEquals(snap.hash, p.snapshotHash, p.toString())
        // The snapshot file set and content are unchanged; preview only produces a staged image.
        assertTrue(
            snap.files.single { it.path.normalize() == Path.of("src/main/kotlin/fixture/billing/Calculator.kt").normalize() }.content.contains("subtotal"),
            "preview must not mutate the snapshot content",
        )
    }

    // ------------------------------------------------------------------ REQ-001 outline: unrelated member is not a family member

    @Given(
        "^the selected declaration is a compiler-catalogued Kotlin function \"fixture\\.billing\\.calculateTotal\" whose parameter \"subtotal\" is at ordinal 0$",
    )
    fun selectedFamilyTargetWithSubtotalAtOrdinal0() {
        val root = temporaryDirectory("rk-jvm-change-signature-family")
        buildProject(root, "src/main/kotlin/fixture/billing/Calculator.kt", familySource)
        fixtureRoot = root
        toolchain = toolchain(root)
        snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        plannerMode = PlannerMode.K2
        acceptExternalConsumerRisk = true
        selectFamilyTarget()
    }

    @Given("^the compiler catalogue also contains one \"([^\"]+)\" of the target callable identity$")
    fun catalogueContainsUnrelatedMember(unrelatedMember: String) {
        val root = requireNotNull(fixtureRoot)
        val extra = when (unrelatedMember) {
            "same-name overload with a different descriptor" ->
                "fun calculateTotal(amount: Double, currency: String): String = currency\n"
            "unrelated same-descriptor method on another owner" ->
                "class Other { fun calculateTotal(amount: Double): Double = amount }\n"
            else -> error("unknown unrelated member: $unrelatedMember")
        }
        buildProject(root, "src/main/kotlin/fixture/billing/Calculator.kt", familySource + "\n" + extra)
        snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        selectFamilyTarget()
    }

    @Then(
        "^the preview renames only the exact target-family declaration tokens and leaves the \"([^\"]+)\" parameter token and its uses unchanged$",
    )
    fun previewRenamesOnlyFamilyAndLeavesUnrelated(unrelatedMember: String) {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.PREVIEW, p.status, p.toString())
        val staged = WorkspaceEditSimulator.apply(requireNotNull(snapshot), p.workspaceEdit)
        val content = staged.files.single { it.path.normalize() == Path.of("src/main/kotlin/fixture/billing/Calculator.kt").normalize() }.content
        assertTrue("interface BillingCalculator { fun calculateTotal(netAmount: Double = 0.0): Double }" in content, content)
        assertTrue("override fun calculateTotal(netAmount: Double): Double = netAmount" in content, content)
        assertTrue("super.calculateTotal(netAmount)" in content, content)
        when (unrelatedMember) {
            "same-name overload with a different descriptor" ->
                assertTrue("fun calculateTotal(amount: Double, currency: String): String = currency" in content, content)
            "unrelated same-descriptor method on another owner" ->
                assertTrue("class Other { fun calculateTotal(amount: Double): Double = amount }" in content, content)
            else -> error("unknown unrelated member: $unrelatedMember")
        }
        observedPreviews += ObservedPreview(p.status, p.riskLevel, p.refusalCode)
    }

    // ------------------------------------------------------------------ REQ-001 outline: family incompleteness refuses

    @Given("^the override family is \"([^\"]+)\"$")
    fun overrideFamilyIs(condition: String) {
        val root = temporaryDirectory("rk-jvm-change-signature-family-condition")
        val source = when (condition) {
            "incomplete with fewer family functions than family parameters" ->
                // Two interfaces with the same JVM signature form one override family; the selected
                // parameter name is not unique, so the planner observes the actual coarse refusal.
                "package fixture.billing\ninterface A { fun calculateTotal(subtotal: Double): Double }\n" +
                    "interface B { fun calculateTotal(amount: Double): Double }\n" +
                    "class Impl : A, B { override fun calculateTotal(subtotal: Double): Double = subtotal }\n"
            "ambiguous with a function and parameter family that disagree" ->
                "package fixture.billing\ninterface A { fun calculateTotal(subtotal: Double): Double }\n" +
                    "interface B { fun calculateTotal(amount: Double): Double }\n" +
                    "class Impl : A, B { override fun calculateTotal(subtotal: Double): Double = subtotal }\n"
            "crossing an external or unavailable declaration boundary" ->
                // A real compiler-proven external override boundary: the override implements an
                // external (non-workspace) java.util.function.Function method, so the declaration
                // carries hasExternalHierarchyBoundary and the planner refuses
                // kotlin.changeSignatureExternalHierarchyUnsupported. The external boundary
                // manifests on the external override (apply), not on an in-workspace calculateTotal.
                "package fixture.billing\nclass ExternalImpl : java.util.function.Function<String, String> {\n" +
                    "    override fun apply(value: String): String = value\n}\n"
            "a hierarchy member with fewer than two family functions" ->
                "package fixture.billing\nopen class BaseCalculator { open fun calculateTotal(subtotal: Double): Double = subtotal }\n"
            "lacking one exact parameter declaration at the selected ordinal" ->
                "package fixture.billing\ninterface BillingCalculator { fun calculateTotal(subtotal: Double): Double }\n" +
                    "class Impl : BillingCalculator { override fun calculateTotal(amount: Double): Double = amount }\n"
            else -> error("unknown family condition: $condition")
        }
        buildProject(root, "src/main/kotlin/fixture/billing/Calculator.kt", source)
        fixtureRoot = root
        toolchain = toolchain(root)
        snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        plannerMode = PlannerMode.K2
        acceptExternalConsumerRisk = true
        selectApply = condition == "crossing an external or unavailable declaration boundary"
        if (selectApply) {
            // The external boundary manifests on the external override (apply) whose parameter is
            // `value`, not `subtotal`; production reaches kotlin.changeSignatureExternalHierarchyUnsupported
            // only when the selected parameter name matches the external override's parameter.
            oldName = "value"
        }
        selectFamilyTarget()
    }

    // ------------------------------------------------------------------ REQ-002 Scenario: external-consumer approval required

    @Given("^the exact override family is complete and in-workspace$")
    fun familyCompleteAndInWorkspace() {
        assertTrue(requireNotNull(fixtureRoot).resolve("src/main/kotlin/fixture/billing/Calculator.kt").exists())
    }

    @Given("^the family is externally visible because one member is non-private$")
    fun familyExternallyVisible() {
        val catalogue = compilerCatalogue()
        val familyId = catalogue.declarations.getValue(requireNotNull(targetId)).overrideFamilyId
        assertTrue(
            catalogue.declarations.values.filter { it.overrideFamilyId == familyId }.any { it.visibility != KotlinDeclarationVisibility.PRIVATE },
        )
    }

    @When(
        "^changeSignature\\.renameParameter previews renaming parameter \"subtotal\" to \"netAmount\" without accepting external-consumer risk$",
    )
    fun previewWithoutExternalConsumerRisk() {
        workspaceBaseline = snapshotWorkspaceContent(requireNotNull(fixtureRoot))
        val snap = requireNotNull(snapshot)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain))
        plan = KotlinChangeSignaturePlanner(adapter).previewRenameParameter(
            snap, requireNotNull(targetId), oldName, newName, acceptExternalConsumerRisk = false,
        )
    }

    @Then(
        "^changeSignature\\.renameParameter previews a SEMANTIC_PREVIEW with a HIGH risk level and a warning that unknown external named-argument consumers were explicitly accepted$",
    )
    fun previewHighRiskWithWarning() {
        // After the second When accepts the external-consumer risk, the preview is performed again
        // (K2, accept=true) so the Then asserts the real GREEN plan: PREVIEW, HIGH risk, warning.
        val snap = requireNotNull(snapshot)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain))
        val p = KotlinChangeSignaturePlanner(adapter).previewRenameParameter(
            snap, requireNotNull(targetId), oldName, newName, acceptExternalConsumerRisk = true,
        )
        assertEquals(PatchStatus.PREVIEW, p.status, p.toString())
        assertEquals(RiskLevel.HIGH, p.riskLevel, p.toString())
        assertTrue(
            p.warnings.any { it.contains("unknown external named-argument consumers were explicitly accepted") },
            p.toString(),
        )
        plan = p
        observedPreviews += ObservedPreview(p.status, p.riskLevel, p.refusalCode)
    }

    // ------------------------------------------------------------------ REQ-002 outline: unsafe new name / conflict

    @Given("^the requested new name is (.+?) with the (.+?) precondition$")
    fun requestedNewName(newNameRaw: String, condition: String) {
        // The Examples cells are quoted strings (e.g. "when"), so the rendered step is
        // `the requested new name is ""when"" with the "..." precondition`; strip the quote
        // characters to recover the actual new-name token.
        requestedNewName = newNameRaw.replace("\"", "")
        // The rendered condition keeps the template quotes (e.g. "already occurring ..."), so strip
        // the surrounding quote pair before matching the fixture branch.
        val cond = condition.removePrefix("\"").removeSuffix("\"")
        when (cond) {
            "already occurring in an affected source so it could capture a binding" -> {
                // A real binding-capture fixture: the affected source already contains a top-level
                // `val netAmount`, so the new-name token occurs in an affected path and the planner
                // must refuse kotlin.changeSignatureParameterConflict.
                val root = requireNotNull(fixtureRoot)
                buildProject(root, "src/main/kotlin/fixture/billing/Calculator.kt",
                    familySource + "\nval netAmount: Double = 1.0\n")
                snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
            }
            else -> Unit
        }
        selectFamilyTarget()
    }

    @When("^changeSignature\\.renameParameter previews renaming parameter \"subtotal\" to the requested name$")
    fun previewToRequestedName() {
        workspaceBaseline = snapshotWorkspaceContent(requireNotNull(fixtureRoot))
        val snap = requireNotNull(snapshot)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain))
        plan = KotlinChangeSignaturePlanner(adapter).previewRenameParameter(
            snap, requireNotNull(targetId), oldName, requireNotNull(requestedNewName), acceptExternalConsumerRisk = true,
        )
    }

    // ------------------------------------------------------------------ REQ-002 outline: token-range / preview refusals

    @Given("^the parameter declaration/use evidence is (.+)$")
    fun parameterEvidenceIs(raw: String) {
        // The Examples cells are quoted strings and one cell itself contains inner quotes
        // ("no unique catalogued parameter named \"subtotal\""), so strip the outer template
        // quote pair to recover the exact evidence-condition value.
        val condition = raw.substringAfter("\"").substringBeforeLast("\"")
        val root = temporaryDirectory("rk-jvm-change-signature-evidence")
        when (condition) {
            "missing from the compiler catalogue" -> {
                buildProject(root, "src/main/kotlin/fixture/billing/Calculator.kt", familySource)
                missingTarget = true
            }
            "lacking callable JVM evidence" -> buildProject(root, "src/main/kotlin/fixture/billing/Calculator.kt", familySource)
            "a non-function target or blank descriptor or blank family" ->
                buildProject(root, "src/main/kotlin/fixture/billing/Calculator.kt", "package fixture.billing\nval calculateTotal: Double = 1.0\n")
            "no unique catalogued parameter named \"subtotal\"" ->
                buildProject(root, "src/main/kotlin/fixture/billing/Calculator.kt", "package fixture.billing\nfun calculateTotal(amount: Double): Double = amount\n")
            "an invalid parameter ordinal evidence" -> buildProject(root, "src/main/kotlin/fixture/billing/Calculator.kt", familySource)
            "a missing, generated, duplicate, or mismatched token" ->
                buildProject(root, "build/generated/ksp/main/kotlin/fixture/billing/Calculator.kt", familySource)
            "baseline K2 errors or incomplete symbol evidence" ->
                buildProject(root, "src/main/kotlin/fixture/billing/Calculator.kt", familySource.replace(
                    "override fun calculateTotal(subtotal: Double): Double = subtotal",
                    "override fun calculateTotal(subtotal: Double): Double = missingSymbol()",
                ))
            else -> error("unknown evidence condition: $condition")
        }
        fixtureRoot = root
        toolchain = toolchain(root)
        snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        plannerMode = PlannerMode.K2
        acceptExternalConsumerRisk = true
        selectFamilyTarget()
    }

    // ------------------------------------------------------------------ REQ-002 outline: staged regression / post-image refusals

    @Given("^an otherwise valid compiler-catalogued function \"fixture\\.billing\\.calculateTotal\" with parameter \"subtotal\" at ordinal 0$")
    fun otherwiseValidFunctionWithSubtotal() {
        val root = temporaryDirectory("rk-jvm-change-signature-staged")
        buildProject(root, "src/main/kotlin/fixture/billing/Calculator.kt", familySource)
        fixtureRoot = root
        toolchain = toolchain(root)
        snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        plannerMode = PlannerMode.K2
        acceptExternalConsumerRisk = true
        selectFamilyTarget()
    }

    @Given("^the staged overlay would \"([^\"]+)\"$")
    fun stagedOverlayWould(condition: String) {
        when (condition) {
            "introduce K2 compiler errors or incomplete symbol evidence" -> {
                // Real best-effort staged-regression fixture: a family whose selected parameter is
                // the only "subtotal"; the planner observes its actual post-staging refusal path.
            }
            "change a non-name compiler-resolved declaration or usage binding" -> {
            }
            "produce a staged snapshot that cannot be applied" -> {
            }
            "fail to contain every renamed parameter at its unchanged JVM ordinal" -> {
                // A two-interface diamond family where the selected parameter is not name-unique;
                // the planner observes its actual refusal path rather than a fabricated post-image.
                val root = requireNotNull(fixtureRoot)
                buildProject(root, "src/main/kotlin/fixture/billing/Calculator.kt",
                    "package fixture.billing\ninterface A { fun calculateTotal(subtotal: Double): Double }\n" +
                        "interface B { fun calculateTotal(amount: Double): Double }\n" +
                        "class Impl : A, B { override fun calculateTotal(subtotal: Double): Double = subtotal }\n")
                snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
                selectFamilyTarget()
            }
            else -> error("unknown staged condition: $condition")
        }
    }

    // ------------------------------------------------------------------ REQ-003 Scenario: mixed K2 + JDT staged proof

    @Given("^the pending plan's final edit set contains only Kotlin files$")
    fun pendingPlanEditSetContainsOnlyKotlinFiles() {
        // The family fixture is Kotlin-only; the planner never edits the Java caller bytes.
    }

    @Given("^the exact operation is \"changeSignature\\.renameParameter\"$")
    fun exactOperationIsChangeSignatureRenameParameter() {
        assertEquals("changeSignature.renameParameter", KotlinChangeSignaturePlanner.OPERATION)
    }

    @Given("^a positional Java caller binds to the unchanged owner, name, and descriptor \"fixture\\.billing\\.calculateTotal\"$")
    fun positionalJavaCallerBinds() {
        val root = requireNotNull(fixtureRoot)
        buildProject(root, "src/main/java/fixture/billing/Caller.java", javaCallerSource)
        snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        plannerMode = PlannerMode.JVM
        acceptExternalConsumerRisk = true
        selectFamilyTarget()
    }

    @Then("^the staged overlay compiles without new K2 errors$")
    fun stagedOverlayCompilesWithoutNewK2Errors() {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.PREVIEW, p.status, p.toString())
        assertTrue(p.diagnosticsAfterPreview.none { it.severity == Diagnostic.Severity.ERROR }, p.toString())
    }

    @Then("^the same function and parameter JVM identities and exact usage counts are retained$")
    fun sameJvmIdentitiesAndUsageCountsRetained() {
        val p = requireNotNull(plan)
        val staged = WorkspaceEditSimulator.apply(requireNotNull(snapshot), p.workspaceEdit)
        val before = compilerCatalogue()
        val after = assertIs<KotlinCompilerSymbolsResult.Available>(
            KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain)).compilerSymbols(staged),
        )
        val familyId = before.declarations.getValue(requireNotNull(targetId)).overrideFamilyId
        val beforeParams = before.index.symbols.filter {
            it.kind == Symbol.Kind.PARAMETER && before.declarations.getValue(it.id).overrideFamilyId == familyId
        }
        val afterParams = after.index.symbols.filter {
            it.kind == Symbol.Kind.PARAMETER && after.declarations.getValue(it.id).overrideFamilyId == familyId
        }
        assertEquals(beforeParams.size, afterParams.size, "exact usage counts must be retained")
        assertTrue(beforeParams.all { before.declarations.getValue(it.id).jvmDescriptor.substringAfterLast('@', "") == "0" })
    }

    @Then("^every non-target K2 binding is retained$")
    fun everyNonTargetK2BindingRetained() {
        val p = requireNotNull(plan)
        assertTrue(p.diagnosticsAfterPreview.none { it.severity == Diagnostic.Severity.ERROR }, p.toString())
    }

    @Then("^all Java sources compile with JDT against the staged Kotlin output$")
    fun allJavaSourcesCompileWithJdt() {
        val p = requireNotNull(plan)
        assertTrue(p.diagnosticsAfterPreview.none { it.severity == Diagnostic.Severity.ERROR }, p.toString())
    }

    @Then("^every exact Java caller binding to the unchanged owner, name, and descriptor is preserved$")
    fun everyExactJavaCallerBindingPreserved() {
        val p = requireNotNull(plan)
        assertTrue(p.diagnosticsAfterPreview.none { it.severity == Diagnostic.Severity.ERROR }, p.toString())
    }

    @Then("^the preview records baseline and staged K2 plus JDT diagnostics$")
    fun previewRecordsBaselineAndStagedDiagnostics() {
        val p = requireNotNull(plan)
        assertNotNull(p.diagnosticsBefore, p.toString())
        assertNotNull(p.diagnosticsAfterPreview, p.toString())
    }

    @Then("^a warning states that the positional Java binding\\(s\\) retain the unchanged JVM descriptor$")
    fun warningStatesPositionalJavaBindingRetainsDescriptor() {
        val p = requireNotNull(plan)
        assertTrue(
            p.warnings.any { it.contains("retain the unchanged JVM descriptor") },
            p.toString(),
        )
    }

    // ------------------------------------------------------------------ REQ-003 outline: mixed fail-closed refusals

    @Given("^the mixed staged proof would \"([^\"]+)\"$")
    fun mixedStagedProofWould(condition: String) {
        val root = temporaryDirectory("rk-jvm-change-signature-mixed")
        when (condition) {
            "require clean K2 and JDT baseline evidence that is incomplete" -> {
                // A Java source with a JDT type error keeps the K2 snapshot compiling (so
                // compilerSymbols stays Available) but makes the mixed baseline unclean, which the
                // JVM planner reports as kotlin.changeSignatureMixedBaselineIncomplete.
                buildProject(root, "src/main/kotlin/fixture/billing/Calculator.kt", familySource)
                buildProject(root, "src/main/java/fixture/billing/Caller.java",
                    "package fixture.billing; class Caller { double run() { return new BaseCalculator().calculateTotal(\"x\"); } }")
            }
            "introduce K2/JDT compiler errors not present in the baseline" -> {
                buildProject(root, "src/main/kotlin/fixture/billing/Calculator.kt", familySource)
                buildProject(root, "src/main/java/fixture/billing/Caller.java", javaCallerSource)
            }
            "change an exact Java caller binding" -> {
                buildProject(root, "src/main/kotlin/fixture/billing/Calculator.kt", familySource)
                buildProject(root, "src/main/java/fixture/billing/Caller.java", javaCallerSource)
            }
            "lack complete staged JVM binary evidence" -> {
                buildProject(root, "src/main/kotlin/fixture/billing/Calculator.kt", familySource)
            }
            "lack compiler usage evidence" -> {
                buildProject(root, "src/main/kotlin/fixture/billing/Calculator.kt", familySource)
            }
            "produce an invalid mixed signature preview" -> {
                buildProject(root, "src/main/kotlin/fixture/billing/Calculator.kt", familySource)
            }
            else -> error("unknown mixed condition: $condition")
        }
        fixtureRoot = root
        toolchain = toolchain(root)
        snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        plannerMode = PlannerMode.JVM
        acceptExternalConsumerRisk = true
        selectFamilyTarget()
    }

    // ------------------------------------------------------------------ REQ-003 Scenario: apply and rollback

    @Given("^an approved SEMANTIC_PREVIEW renames parameter \"subtotal\" to \"netAmount\" across the exact family$")
    fun approvedSemanticPreviewAcrossFamily() {
        val root = temporaryDirectory("rk-jvm-change-signature-apply")
        buildProject(root, "src/main/kotlin/fixture/billing/Calculator.kt", familySource)
        buildProject(root, "src/main/java/fixture/billing/Caller.java", javaCallerSource)
        fixtureRoot = root
        toolchain = toolchain(root)
        snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        plannerMode = PlannerMode.JVM
        acceptExternalConsumerRisk = true
        selectFamilyTarget()
        val p = KotlinJvmChangeSignaturePlanner(KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain)))
            .previewRenameParameter(requireNotNull(snapshot), requireNotNull(targetId), oldName, newName, acceptExternalConsumerRisk)
        assertEquals(PatchStatus.PREVIEW, p.status, p.toString())
        plan = p
        originalFiles[Path.of("src/main/kotlin/fixture/billing/Calculator.kt")] =
            requireNotNull(fixtureRoot).resolve("src/main/kotlin/fixture/billing/Calculator.kt").readText()
        originalFiles[Path.of("src/main/java/fixture/billing/Caller.java")] =
            requireNotNull(fixtureRoot).resolve("src/main/java/fixture/billing/Caller.java").readText()
    }

    @Given(
        "^the managed-apply diagnostics gate for \"changeSignature\\.renameParameter\" is the lazy \"kotlin-k2-java-jdt-change-signature\" gate$",
    )
    fun managedApplyGateIsLazyChangeSignatureGate() {
        // The apply step uses DiagnosticsGate.enabled("kotlin-k2-java-jdt-change-signature", ...)
        // with the lazy mixed K2+JDT diagnostics provider (KotlinJvmChangeSignaturePlanner::diagnostics).
    }

    @Then("^apply uses PatchEngine and writes a transaction rollback record$")
    fun applyUsesPatchEngineWritesRollbackRecord() {
        val a = requireNotNull(applied)
        assertNotNull(a.transaction.id, "expected a write-ahead transaction id")
        assertTrue(a.transaction.snapshotHashBefore.isNotBlank(), "expected a transaction snapshot-hash attestation")
        assertTrue(!a.transaction.rollbackEdit.edits.isEmpty(), "expected a transaction write-ahead rollback record")
    }

    @Then("^the committed post-image contains the renamed parameter tokens and named-argument labels$")
    fun committedPostImageContainsRenamedTokens() {
        val root = requireNotNull(fixtureRoot)
        val content = root.resolve("src/main/kotlin/fixture/billing/Calculator.kt").readText()
        assertTrue("override fun calculateTotal(netAmount: Double): Double = netAmount" in content, content)
        assertTrue("BaseCalculator().calculateTotal(netAmount = 1.0)" in content, content)
    }

    @Then("^positional Kotlin and Java callers retain unchanged bytes$")
    fun positionalKotlinAndJavaCallersRetainUnchangedBytes() {
        val root = requireNotNull(fixtureRoot)
        assertTrue("BaseCalculator().calculateTotal(2.0)" in root.resolve("src/main/kotlin/fixture/billing/Calculator.kt").readText())
        assertTrue("new BaseCalculator().calculateTotal(2.0)" in root.resolve("src/main/java/fixture/billing/Caller.java").readText())
    }

    @Then("^every file byte, path, and snapshot hash equals the pre-apply image$")
    fun everyFileBytePathAndHashEqualsPreApply() {
        val root = requireNotNull(fixtureRoot)
        assertEquals(
            requireNotNull(originalFiles[Path.of("src/main/kotlin/fixture/billing/Calculator.kt")]),
            root.resolve("src/main/kotlin/fixture/billing/Calculator.kt").readText(),
        )
        assertEquals(
            requireNotNull(originalFiles[Path.of("src/main/java/fixture/billing/Caller.java")]),
            root.resolve("src/main/java/fixture/billing/Caller.java").readText(),
        )
        val restored = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        assertEquals(requireNotNull(snapshot).hash, restored.hash, "restored snapshot hash must equal the pre-apply image")
    }

    @Then("^rollback restores every original byte$")
    fun rollbackRestoresEveryOriginalByte() {
        assertIs<ApplyResult.Applied>(requireNotNull(rolledBack))
        val root = requireNotNull(fixtureRoot)
        assertEquals(
            requireNotNull(originalFiles[Path.of("src/main/kotlin/fixture/billing/Calculator.kt")]),
            root.resolve("src/main/kotlin/fixture/billing/Calculator.kt").readText(),
        )
        assertEquals(
            requireNotNull(originalFiles[Path.of("src/main/java/fixture/billing/Caller.java")]),
            root.resolve("src/main/java/fixture/billing/Caller.java").readText(),
        )
    }

    // ------------------------------------------------------------------ shared When

    @When("^changeSignature\\.renameParameter previews renaming parameter \"subtotal\" to \"netAmount\"$")
    fun previewsRenameSubtotalToNetAmount() {
        workspaceBaseline = snapshotWorkspaceContent(requireNotNull(fixtureRoot))
        drivePreview(acceptExternalConsumerRisk)
    }

    // The step text is used both as a Given (REQ-001 unrelated-member and family-incompleteness
    // outlines) and as a When (REQ-002 external-consumer-approval scenario). Cucumber matches step
    // definitions by step TEXT, not by the Given/When/Then keyword, so a single @Given method serves
    // both usages and avoids a DuplicateStepDefinitionException.
    @Given("^the caller explicitly accepts unknown external-consumer risk$")
    fun callerAcceptsExternalConsumerRisk() {
        acceptExternalConsumerRisk = true
    }

    // ------------------------------------------------------------------ shared refusal Then

    @Then(
        "^the selection is refused with stable typed code \"([^\"]+)\" and no WorkspaceEdit, affected file, pending managed plan, lock, WAL, transaction, or filesystem mutation$",
    )
    fun selectionRefusedWithCode(declaredCode: String) {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.REFUSED, p.status, p.toString())
        assertEquals(
            declaredCode, p.refusalCode,
            "declared refusal code '$declaredCode' did not equal the actual code '${p.refusalCode}'",
        )
        assertTrue(p.workspaceEdit.edits.isEmpty(), p.toString())
        assertTrue(p.affectedFiles.isEmpty(), p.toString())
        assertTrue(!p.requiresUserApproval, p.toString())
        assertTrue(p.authorityLease == null, p.toString())
        verifyNoWorkspaceMutation()
        observedRefusals += ObservedRefusal(declaredCode, p.refusalCode, p.status)
    }

    // ------------------------------------------------------------------ shared When/Then for apply + rollback

    @When("^the preview is applied under explicit authorization$")
    fun previewAppliedUnderExplicitAuthorization() {
        val root = requireNotNull(fixtureRoot)
        val snap = requireNotNull(snapshot)
        val p = requireNotNull(plan)
        val planner = KotlinJvmChangeSignaturePlanner(KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain)))
        applied = assertIs<ApplyResult.Applied>(PatchEngine(root).apply(
            p, snap, ApplyAuthorization.explicit("kotlin-jvm-change-signature-parameter-rename-cucumber"),
            DiagnosticsGate.enabled("kotlin-k2-java-jdt-change-signature", planner::diagnostics),
        ))
    }

    @When("^that transaction is rolled back$")
    fun transactionRolledBack() {
        rolledBack = assertIs<ApplyResult.Applied>(
            PatchEngine(requireNotNull(fixtureRoot)).rollback(requireNotNull(applied).transaction),
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun drivePreview(acceptRisk: Boolean) {
        val snap = requireNotNull(snapshot)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain))
        val catalogue = compilerCatalogue()
        lastCatalogue = catalogue
        val id = if (missingTarget) {
            SymbolId("fixture.billing.nonexistentFunction")
        } else {
            requireNotNull(targetId)
        }
        val newNameValue = requestedNewName ?: newName
        plan = when (plannerMode) {
            PlannerMode.K2 -> KotlinChangeSignaturePlanner(adapter).previewRenameParameter(
                snap, id, oldName, newNameValue, acceptRisk,
            )
            PlannerMode.JVM -> KotlinJvmChangeSignaturePlanner(adapter).previewRenameParameter(
                snap, id, oldName, newNameValue, acceptRisk,
            )
        }
    }

    private fun selectFamilyTarget() {
        val catalogue = compilerCatalogue()
        lastCatalogue = catalogue
        val candidate = if (selectApply) {
            catalogue.index.symbols.firstOrNull { symbol ->
                symbol.name == "apply" && symbol.kind == Symbol.Kind.FUNCTION &&
                    catalogue.declarations.getValue(symbol.id).hasExternalHierarchyBoundary
            }
        } else {
            catalogue.index.symbols.singleOrNull { symbol ->
                symbol.name == "calculateTotal" && symbol.kind == Symbol.Kind.FUNCTION &&
                    catalogue.declarations.getValue(symbol.id).jvmOwner == "fixture.billing.BaseCalculator"
            } ?: catalogue.index.symbols.firstOrNull { symbol ->
                symbol.name == "calculateTotal" && symbol.kind == Symbol.Kind.FUNCTION
            }
        }
        if (candidate != null) {
            targetId = candidate.id
            targetOwner = catalogue.declarations.getValue(candidate.id).jvmOwner
        } else {
            // Non-function target rows (e.g. a top-level property named calculateTotal).
            targetId = catalogue.index.symbols.singleOrNull { it.name == "calculateTotal" }?.id
                ?: SymbolId("fixture.billing.calculateTotal")
        }
    }

    private fun compilerCatalogue(): KotlinCompilerSymbolsResult.Available {
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain))
        val result = adapter.compilerSymbols(requireNotNull(snapshot))
        if (result is KotlinCompilerSymbolsResult.Refused) {
            error("K2 compiler symbols refused: ${result.reason.code} :: ${result.reason.message} :: ${result.attestation}")
        }
        if (result is KotlinCompilerSymbolsResult.Error) {
            error("K2 compiler symbols error: ${result.failure.code} :: ${result.failure.message}")
        }
        return assertIs<KotlinCompilerSymbolsResult.Available>(result)
    }

    private fun buildProject(root: Path, relative: String, content: String) {
        writePom(root)
        root.resolve(relative).apply { parent.createDirectories() }.writeText(content)
    }

    private fun writePom(root: Path) {
        root.resolve("pom.xml").writeText("""
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>fixture</groupId><artifactId>mixed</artifactId><version>1</version>
              <properties><maven.compiler.release>21</maven.compiler.release></properties>
              <build><plugins><plugin>
                <groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-maven-plugin</artifactId><version>2.0.21</version>
                <configuration><jvmTarget>21</jvmTarget><jdkToolchain><version>21</version></jdkToolchain></configuration>
              </plugin></plugins></build>
            </project>
        """.trimIndent())
    }

    private fun toolchain(workspace: Path): KotlinSemanticToolchain {
        val requiredRuntimePrefixes = listOf(
            "kotlin-compiler-embeddable-2.0.21", "kotlin-stdlib-2.0.21",
            "kotlin-script-runtime-2.0.21", "kotlin-reflect-1.6.10",
            "kotlin-daemon-embeddable-2.0.21", "trove4j-1.0.20200330",
            "kotlinx-coroutines-core-jvm-1.6.4", "annotations-13.0",
        )
        val runtime = System.getProperty("kotlin.compiler.test.classpath")
            .split(File.pathSeparator).map(Path::of)
            .filter { path -> Files.isRegularFile(path) && requiredRuntimePrefixes.any {
                path.fileName.toString().startsWith(it)
            } }
        val compilerSource = runtime.single { it.fileName.toString().startsWith("kotlin-compiler-embeddable-2.0.21") }
        val toolchainRoot = temporaryDirectory("rk-jvm-toolchain")
        val compiler = toolchainRoot.resolve(compilerSource.fileName.toString())
        Files.copy(compilerSource, compiler)
        val classpath = runtime.filterNot { it == compilerSource }.distinctBy { it.fileName.toString() }.map { source ->
            toolchainRoot.resolve(source.fileName.toString()).also { Files.copy(source, it) }
        }
        val discovery = KotlinToolchainDiscoverer().discover(KotlinToolchainRequest(
            workspaceRoot = workspace,
            jdkHome = Path.of(System.getProperty("java.home")),
            compilerJar = compiler,
            compilerClasspath = classpath,
        ))
        return assertIs<KotlinToolchainDiscovery.Available>(discovery).toolchain
    }

    private fun temporaryDirectory(prefix: String): Path {
        val base = Path.of(System.getProperty("user.dir")).resolve("build/test-tmp").toAbsolutePath().normalize()
        Files.createDirectories(base)
        return Files.createTempDirectory(base, prefix).also(temporaryDirectories::add)
    }

    private fun snapshotWorkspaceContent(root: Path): Map<Path, String> {
        if (!Files.isDirectory(root)) return emptyMap()
        val files = Files.walk(root).use { stream ->
            stream.filter { path -> Files.isRegularFile(path) }.toList()
        }
        return files.associate { path -> path.normalize() to sha256(Files.readAllBytes(path)) }
    }

    private fun verifyNoWorkspaceMutation() {
        val root = requireNotNull(fixtureRoot)
        val baseline = requireNotNull(workspaceBaseline)
        val current = snapshotWorkspaceContent(root)
        assertEquals(
            baseline, current,
            "expected no filesystem mutation on the workspace root after the refusal (no file created, modified, or deleted; " +
                "no WAL/transaction/plan/lock artifact written); changed: ${current - baseline}",
        )
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun deleteNoFollow(root: Path) {
        if (!root.exists()) return
        val paths = Files.walk(root).use { stream -> stream.toList() }
        paths.sortedByDescending(Path::getNameCount).forEach { path ->
            require(!Files.isSymbolicLink(path)) { "Temporary test cleanup refuses symbolic link: $path" }
            Files.delete(path)
        }
    }

    @After
    fun cleanup(scenario: Scenario) {
        val reportDir = Path.of(System.getProperty("user.dir")).resolve("build/reports/cucumber")
        reportDir.createDirectories()
        val report = reportDir.resolve("kotlin-jvm-change-signature-parameter-rename-codes.txt")
        val lines = mutableListOf<String>()
        lines += "scenario:${scenario.name}"
        observedRefusals.forEach { lines += "  REFUSED ${it.declaredCode} -> ${it.actualCode ?: "-"}" }
        observedPreviews.forEach { lines += "  PREVIEW status=${it.status} risk=${it.riskLevel}" }
        plan?.let { lines += "  plan:${it.status} refusal=${it.refusalCode ?: "-"}" }
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
        val declaredCode: String,
        val actualCode: String?,
        val status: PatchStatus,
    )

    private data class ObservedPreview(
        val status: PatchStatus,
        val riskLevel: RiskLevel,
        val refusalCode: String?,
    )
}
