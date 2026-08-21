package org.refactorkit.jvm.changesignature

import io.cucumber.java.After
import io.cucumber.java.Scenario
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.Symbol
import org.refactorkit.core.SymbolId
import org.refactorkit.core.SourceLocation
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.java.JavaLanguageAdapter
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.java.JdtJavaSemanticAnalysisResult
import org.refactorkit.java.JdtJavaSemanticAnalyzer
import org.refactorkit.java.JdtJavaSemanticBindingUse
import org.refactorkit.jvm.KotlinJvmChangeSignaturePlanner
import org.refactorkit.jvm.ManagedApplyDiagnosticsGateSelector
import org.refactorkit.kotlin.KotlinChangeSignaturePlanner
import org.refactorkit.kotlin.KotlinCompilerDeclarationEvidence
import org.refactorkit.kotlin.KotlinCompilerDiagnostics
import org.refactorkit.kotlin.KotlinCompilerDiagnosticsResult
import org.refactorkit.kotlin.KotlinCompilerResolvedUsage
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
 * (REQ-KOTLIN-CHANGE-SIGNATURE-001..003; 12 scenarios / 33 expanded cases across 8 scenario outlines:
 * 4 plain + 8 outlines).
 *
 * Glue reconciled to the human-readable domain/business prose rewrite (feature SHA
 * 4ae7c5740f55078ec8f7204b918dec81423e312ebd993c21d7fda890f296373a):
 * every step definition matches the new Given/When/Then wording (e.g. When 'a maintainer renames the
 * parameter "subtotal" to "netAmount"'; Then 'RefactorKit refuses the operation and explains why,
 * reporting the typed code "<refusal code>"'). Technical codes stay as data in the Examples/step
 * assertions only. The 17 inducible branches drive the real production gates (12 refusals + 5
 * positive real-behavior branches); the 16 defensive branches assert SEMANTIC_PREVIEW success +
 * read-only (12 from approved change 011 + 4 REQ-001 family-incompleteness gates from approved
 * change 012); REQ-003 oracles stay strengthened.
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
 *
 * Approved change 011 supersedes 12 NON-INDUCIBLE defensive-gate refusal criteria. Those 12 reframed
 * defensive scenarios are NOT refusals: on a clean compiler-proven fixture the retained defensive
 * gates cannot honestly fire, so the glue drives a genuine SEMANTIC_PREVIEW (read-only, no mutation)
 * and asserts PREVIEW success with no refusal code. The token-identity/staged guards are K2 planner
 * gates (REQ-002); the mixed guards are JVM planner gates (REQ-003) and drive the clean mixed K2+JDT
 * proof. Approved change 012 then supersedes 4 additional REQ-001 family-incompleteness criteria
 * (fewer family functions than family parameters; function and parameter family disagree; crossing an
 * external or unavailable declaration boundary; hierarchy member with fewer than two family functions)
 * to the same defensive SEMANTIC_PREVIEW treatment: those 4 rows drive the clean complete in-workspace
 * family and assert preview success + read-only, never a refusal. The inducible "lacking one exact
 * parameter declaration at the selected ordinal" row (5) KEEPS its refusal
 * kotlin.changeSignatureFamilyIncomplete and is verified with verifyFamilyCondition.
 *
 * Approved change 013 retains the REQ-002 "duplicate ranges refuse" criterion as a NON-INDUCIBLE
 * defensive-gate, not an executable coalescence behavior. Production dedupes token ranges by range
 * start (`KotlinChangeSignaturePlanner` builds the locations list then `.distinctBy { it.first }`)
 * before the range-invalid check, which makes the duplicate-detection branch tautological
 * (`locations.size != locations.map { it.first }.distinct().size` cannot differ after distinctBy), and
 * compiler `parseUsages` enforces unique keys, so no compiler fixture can emit the same token range
 * twice; candidate fixtures that try to induce a duplicate-range refusal fail to compile. There is
 * therefore NO executable coalescence step: no scenario step asserts coalescence/dedupe behavior; the
 * criterion is kept as a defensive-gate note only, matching the feature's defensive-gate note. The
 * composite token-range row drives the inducible missing/generated/mismatched token case to
 * kotlin.changeSignatureRangeInvalid (a real gate), never a duplicate-range refusal. The 17 inducible
 * cases keep their real refusal/positive branches unchanged.
 */
class KotlinJvmChangeSignatureParameterRenameSteps {
    private val temporaryDirectories = mutableListOf<Path>()
    private val observedRefusals = mutableListOf<ObservedRefusal>()
    private val observedPreviews = mutableListOf<ObservedPreview>()

    private enum class PlannerMode { K2, JVM }

    /** Distinguishes the staged K2 defensive outline (PreviewInvalid is a K2 gate) from the mixed
     * JVM defensive outline (PreviewInvalid is a JVM gate) when the shared guard code is ambiguous. */
    private enum class DefensiveFixtureContext { GENERIC, STAGED_K2 }

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

    // Per-scenario cache for the REQ-003 staged-proof oracles. The staged image and its K2
    // catalogue are stable within one scenario, so the strengthened oracle steps share them
    // instead of re-running the compiler for every Then.
    private var stagedSnapshotCache: ProjectSnapshot? = null
    private var stagedCatalogueCache: KotlinCompilerSymbolsResult.Available? = null

    // Content identity of every regular file on the workspace root immediately before the preview.
    // A refusal Then proves the set is byte-for-byte unchanged afterward (no file created, modified,
    // or deleted), which also proves no WAL/transaction record, pending managed-plan, or lock
    // artifact was written into the workspace.
    private var workspaceBaseline: Map<Path, String>? = null

    // Retained defensive-gate context for the 12 reframed defensive scenarios (approved-change-011).
    // defensiveGuard captures the guard code from the feature And step; defensiveFixtureContext
    // disambiguates the shared kotlin.changeSignaturePreviewInvalid guard between the staged K2
    // outline and the mixed JVM outline. Cucumber creates a fresh glue instance per scenario.
    private var defensiveGuard: String? = null
    private var defensiveFixtureContext = DefensiveFixtureContext.GENERIC

    // Declared REQ-001 family-incompleteness condition for the current outline row (set by
    // overrideFamilyIs). The shared refusal Then verifies that the fixture actually induced the
    // EXACT declared condition (not a substitute), failing the row with a precise RED finding when
    // the declared condition is unreachable from a compiler fixture.
    private var familyCondition: String? = null

    // Set by pendingPlanEditSetContainsOnlyKotlinFiles (REQ-003) so the staged-overlay Then can
    // assert the actual pending-plan edit set contains only Kotlin files.
    private var pendingKotlinOnlyEditSet = false

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
        "^a compiler-catalogued Kotlin function \"fixture\\.billing\\.calculateTotal\" is selected, and its parameter \"subtotal\" is identified at ordinal 0$",
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
        // REQ-001 Scenario 1: the combined Given also asserts that the parameter "subtotal" is
        // identified at ordinal 0 of the selected callable identity (previously a separate Given
        // whose step text was removed by the human-readable prose rewrite).
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
        "^the snapshot carries complete error-free compiler evidence and one stable override-family identity from compiler override checking across resolved source hierarchies$",
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

    @Given("^the caller explicitly accepts unknown external-consumer risk because one family member is non-private$")
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
        "^RefactorKit returns a successful read-only preview that renames the parameter declaration at ordinal 0 in every family member atomically$",
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

    @Then("^every compiler-resolved body reference to those parameter symbols is renamed$")
    fun everyFirResolvedBodyReferenceRenamed() {
        val staged = WorkspaceEditSimulator.apply(requireNotNull(snapshot), requireNotNull(plan).workspaceEdit)
        val content = staged.files.single { it.path.normalize() == Path.of("src/main/kotlin/fixture/billing/Calculator.kt").normalize() }.content
        assertTrue("override fun calculateTotal(netAmount: Double): Double = netAmount" in content, content)
        assertTrue("super.calculateTotal(netAmount)" in content, content)
    }

    @Then("^every Kotlin named argument mapped to that parameter with the label \"subtotal\" is renamed$")
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

    @Then("^overload calls remain bound to the same callable identity$")
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
        "^a compiler-catalogued Kotlin function \"([^\"]+)\" is selected, and its parameter \"([^\"]+)\" is at ordinal 0$",
    )
    fun selectedFamilyTarget(targetFunction: String, parameterOldName: String) {
        // Parameterized for the reconciled REQ-001 "family incompleteness refuses" outline whose
        // Examples pass the target function (fixture.billing.calculateTotal or
        // java.util.function.Function.apply) and the old parameter name (subtotal or value).
        // Every literal call site renders calculateTotal/subtotal, so capturing the columns
        // preserves prior behavior; the external-boundary row drives oldName=value, which the
        // overrideFamilyIs("crossing ...") fixture then selects on the external override (apply).
        val root = temporaryDirectory("rk-jvm-change-signature-family")
        buildProject(root, "src/main/kotlin/fixture/billing/Calculator.kt", familySource)
        fixtureRoot = root
        toolchain = toolchain(root)
        snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        plannerMode = PlannerMode.K2
        acceptExternalConsumerRisk = true
        oldName = parameterOldName
        selectFamilyTarget()
    }

    @Given("^the compiler catalogue also contains one \"([^\"]+)\" of that function$")
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
        "^RefactorKit renames only the exact target-family declaration tokens and leaves the \"([^\"]+)\" parameter token and its uses unchanged$",
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
        familyCondition = condition
        val root = temporaryDirectory("rk-jvm-change-signature-family-condition")
        val source = when (condition) {
            // Approved change 012 supersedes 4 non-inducible REQ-001 family-incompleteness refusal
            // criteria (rows 1-4 below). Each declared condition is provably unreachable from a clean
            // compiler fixture, so the fixture is the clean complete in-workspace family and the ACTUAL
            // production behavior is a successful read-only SEMANTIC_PREVIEW, not a refusal. The gates
            // are retained as defensive guards (not removed); the row asserts preview success + read-only,
            // never a refusal.
            "incomplete with fewer family functions than family parameters",
            "ambiguous with a function and parameter family that disagree",
            "crossing an external or unavailable declaration boundary",
            "a hierarchy member with fewer than two family functions" ->
                familySource
            "lacking one exact parameter declaration at the selected ordinal" ->
                // REAL FamilyIncomplete trigger via a distinct shape: base + two impls where one
                // impl's parameter is skipped by a non-JVM backtick name. 3 family functions but only
                // 2 catalogued ordinal-0 parameters -> the size gate fires FamilyIncomplete. This
                // inducible case KEEPS its refusal kotlin.changeSignatureFamilyIncomplete under
                // approved change 012.
                "package fixture.billing\n" +
                    "open class BaseCalculator { open fun calculateTotal(subtotal: Double): Double = subtotal }\n" +
                    "class ImplA : BaseCalculator() { override fun calculateTotal(`c d`: Double): Double = super.calculateTotal(subtotal = 1.0) }\n" +
                    "class ImplB : BaseCalculator() { override fun calculateTotal(subtotal: Double): Double = super.calculateTotal(subtotal) }\n"
            else -> error("unknown family condition: $condition")
        }
        buildProject(root, "src/main/kotlin/fixture/billing/Calculator.kt", source)
        fixtureRoot = root
        toolchain = toolchain(root)
        snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        plannerMode = PlannerMode.K2
        acceptExternalConsumerRisk = true
        // Approved change 012: rows 1-4 are clean compiler-proven fixtures that must yield a
        // successful read-only preview, so no row selects the external override anymore
        // (selectApply stays false for every row). The ordinal-declaration row (5) keeps its
        // refusal on the in-workspace family.
        selectApply = false
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
        "^a maintainer renames the parameter \"subtotal\" to \"netAmount\" without accepting external-consumer risk$",
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
        "^RefactorKit returns a successful read-only preview flagged HIGH risk, with a warning that unknown external named-argument consumers were explicitly accepted$",
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
            "conflicting with another parameter in the exact family" -> {
                // A real conflict fixture: the target function already declares a second parameter
                // named `netAmount` at ordinal 1 in the same JVM identity (owner/name/descriptor), so
                // the planner's new-name conflict gate fires kotlin.changeSignatureParameterConflict
                // before staging.
                val root = requireNotNull(fixtureRoot)
                buildProject(root, "src/main/kotlin/fixture/billing/Calculator.kt",
                    "package fixture.billing\n" +
                        "open class BaseCalculator { open fun calculateTotal(subtotal: Double, netAmount: Double): Double = subtotal }\n")
                snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
            }
            else -> Unit
        }
        selectFamilyTarget()
    }

    @When("^a maintainer renames the parameter \"subtotal\" to the requested new name$")
    fun previewToRequestedName() {
        workspaceBaseline = snapshotWorkspaceContent(requireNotNull(fixtureRoot))
        val snap = requireNotNull(snapshot)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain))
        plan = KotlinChangeSignaturePlanner(adapter).previewRenameParameter(
            snap, requireNotNull(targetId), oldName, requireNotNull(requestedNewName), acceptExternalConsumerRisk = true,
        )
    }

    // ------------------------------------------------------------------ REQ-002 outline: token-range / preview refusals

    @Given("^the parameter declaration and use evidence is (.+)$")
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
            "a non-function target or blank descriptor or blank family" ->
                buildProject(root, "src/main/kotlin/fixture/billing/Calculator.kt", "package fixture.billing\nval calculateTotal: Double = 1.0\n")
            "no unique catalogued parameter named \"subtotal\"" ->
                buildProject(root, "src/main/kotlin/fixture/billing/Calculator.kt", "package fixture.billing\nfun calculateTotal(amount: Double): Double = amount\n")
            "a missing, generated, or mismatched token" ->
                // Approved change 013 retains the REQ-002 "duplicate ranges refuse" criterion as a
                // NON-INDUCIBLE defensive-gate, not an executable coalescence behavior: production
                // dedupes token ranges by range start (`KotlinChangeSignaturePlanner` builds the
                // locations list then `.distinctBy { it.first }`) before the range-invalid check, which
                // makes the duplicate-detection branch tautological (the size-vs-distinct-size check
                // cannot differ after distinctBy), and compiler `parseUsages` enforces unique keys, so a
                // compiler fixture cannot emit the same token range twice; candidate fixtures that try to
                // induce a duplicate-range refusal fail to compile. There is therefore NO executable
                // coalescence step (no scenario step asserts coalescence/dedupe behavior; the criterion
                // is kept as a defensive-gate note only). This composite row drives the inducible
                // missing/generated/mismatched token case: the generated-source fixture below makes
                // production refuse kotlin.changeSignatureRangeInvalid (a real gate), never a
                // duplicate-range refusal.
                buildProject(root, "build/generated/ksp/main/kotlin/fixture/billing/Calculator.kt", familySource)
            else -> error("unknown evidence condition: $condition")
        }
        fixtureRoot = root
        toolchain = toolchain(root)
        snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        plannerMode = PlannerMode.K2
        acceptExternalConsumerRisk = true
        selectFamilyTarget()
        // This outline's guards (DiagnosticsRegression, BindingChanged, PreviewInvalid,
        // PostImageIdentityMissing) are K2 planner gates (REQ-002), so the shared
        // kotlin.changeSignaturePreviewInvalid guard stays on the K2 planner here.
        defensiveFixtureContext = DefensiveFixtureContext.STAGED_K2
    }

    // ------------------------------------------------------------------ REQ-002/REQ-003 outline: defensive-gate retained (approved-change-011)

    @Given("^an otherwise valid compiler-catalogued function \"fixture\\.billing\\.calculateTotal\" is selected, with parameter \"subtotal\" at ordinal 0$")
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

    @Given("^the \"([^\"]+)\" defensive gate is retained by the production planner$")
    fun defensiveGateRetained(guardCode: String) {
        // The 12 defensive gates (approved-change-011) are NON-INDUCIBLE from a clean compiler
        // fixture: each is a branch the production planner retains but a clean rename cannot
        // honestly reach, so this scenario asserts a genuine SEMANTIC_PREVIEW, not a refusal. The
        // token-identity/staged guards are K2 planner gates (REQ-002); the mixed guards are JVM
        // planner gates (REQ-003), so those add the Java caller and switch to the JVM planner so
        // the clean mixed K2+JDT proof actually runs.
        defensiveGuard = guardCode
        if (useJvmPlannerForDefensiveGuard(guardCode)) {
            val root = requireNotNull(fixtureRoot)
            buildProject(root, "src/main/java/fixture/billing/Caller.java", javaCallerSource)
            snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
            plannerMode = PlannerMode.JVM
        }
    }

    private fun useJvmPlannerForDefensiveGuard(guardCode: String): Boolean {
        // The mixed REQ-003 guards live in the JVM planner. kotlin.changeSignaturePreviewInvalid is
        // shared by both the staged K2 outline and the mixed JVM outline, so the fixture context
        // disambiguates which planner the retained guard belongs to.
        val jvmMixedOnly = setOf(
            "kotlin.changeSignatureMixedDiagnosticsRegression",
            "kotlin.changeSignatureJavaBindingChanged",
            "kotlin.changeSignatureBinaryEvidenceUnavailable",
            "kotlin.changeSignatureUsageEvidenceUnavailable",
        )
        return guardCode in jvmMixedOnly ||
            (guardCode == "kotlin.changeSignaturePreviewInvalid" &&
                defensiveFixtureContext != DefensiveFixtureContext.STAGED_K2)
    }

    // ------------------------------------------------------------------ REQ-003 Scenario: mixed K2 + JDT staged proof

    @Given("^the pending plan's final edit set contains only Kotlin files$")
    fun pendingPlanEditSetContainsOnlyKotlinFiles() {
        // Real precondition (the preview plan is created by the later When, so the actual edit set
        // is asserted on the produced plan in the staged-overlay Then): every Kotlin source the
        // planner can edit ends with .kt; the Java caller is added by the next Given and is never
        // edited by the Kotlin-only rename.
        val snap = requireNotNull(snapshot)
        val kotlinFiles = snap.files.filter { it.languageId == "kotlin" }
        assertTrue(
            kotlinFiles.isNotEmpty() && kotlinFiles.all { it.path.fileName.toString().endsWith(".kt") },
            "every Kotlin source the planner can edit must end with .kt: ${kotlinFiles.map { it.path }}",
        )
        pendingKotlinOnlyEditSet = true
    }

    @Given("^the exact operation is a parameter rename$")
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

    @Then("^the staged overlay compiles without new Kotlin compiler errors$")
    fun stagedOverlayCompilesWithoutNewK2Errors() {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.PREVIEW, p.status, p.toString())
        assertTrue(p.diagnosticsAfterPreview.none { it.severity == Diagnostic.Severity.ERROR }, p.toString())
        // Real assertion of the pending plan's final edit set: every edited path ends with .kt and
        // none is Java; the Java caller is never edited. Ties the pendingPlanEditSetContainsOnlyKotlinFiles
        // Given precondition to the actual produced plan.
        assertTrue(pendingKotlinOnlyEditSet, "pendingPlanEditSetContainsOnlyKotlinFiles precondition must have asserted")
        val editedPaths = p.workspaceEdit.edits.mapNotNull { edit ->
            when (edit) {
                is FileEdit.Modify -> edit.path
                is FileEdit.Create -> edit.path
                is FileEdit.Delete -> edit.path
                is FileEdit.Rename -> edit.newPath
            }
        }
        assertTrue(editedPaths.isNotEmpty(), "pending plan must contain edited files: $p")
        assertTrue(
            editedPaths.all { it.fileName.toString().endsWith(".kt") },
            "every edited path must be Kotlin (.kt): ${editedPaths.map { it.toString() }}",
        )
        assertTrue(
            editedPaths.none { it.fileName.toString().endsWith(".java") },
            "no edited path may be Java (.java): ${editedPaths.map { it.toString() }}",
        )
        assertTrue(
            p.affectedFiles.all { it.fileName.toString().endsWith(".kt") } &&
                p.affectedFiles.none { it.fileName.toString().endsWith(".java") },
            "every affected path in the pending plan must be Kotlin and none Java: ${p.affectedFiles}",
        )
    }

    @Then("^the same function and parameter identities and exact usage counts are retained$")
    fun sameJvmIdentitiesAndUsageCountsRetained() {
        val before = compilerCatalogue()
        val after = stagedCatalogue()
        val familyId = before.declarations.getValue(requireNotNull(targetId)).overrideFamilyId
        val beforeParams = before.index.symbols.filter {
            it.kind == Symbol.Kind.PARAMETER && before.declarations.getValue(it.id).overrideFamilyId == familyId
        }
        val afterParams = after.index.symbols.filter {
            it.kind == Symbol.Kind.PARAMETER && after.declarations.getValue(it.id).overrideFamilyId == familyId
        }
        // Exact JVM identities (owner/name/descriptor incl. ordinal) of every family parameter.
        assertEquals(
            beforeParams.map { identityKey(before.declarations.getValue(it.id)) }.sorted(),
            afterParams.map { identityKey(after.declarations.getValue(it.id)) }.sorted(),
            "family parameter JVM identities (owner/name/descriptor/ordinal) must be retained exactly",
        )
        // Exact compiler-resolved usage count per family parameter id, not just parameter counts.
        assertEquals(
            usageCounts(before, beforeParams),
            usageCounts(after, afterParams),
            "exact compiler-resolved usage counts per family parameter must be retained",
        )
        assertTrue(beforeParams.all { before.declarations.getValue(it.id).jvmDescriptor.substringAfterLast('@', "") == "0" })
    }

    @Then("^every non-target binding is retained$")
    fun everyNonTargetK2BindingRetained() {
        val before = compilerCatalogue()
        val after = stagedCatalogue()
        val familyId = before.declarations.getValue(requireNotNull(targetId)).overrideFamilyId
        val familyParamIds = before.index.symbols.filter {
            it.kind == Symbol.Kind.PARAMETER && before.declarations.getValue(it.id).overrideFamilyId == familyId
        }.map { it.id }.toSet()
        // Exact non-target symbol records (id/name/kind/path), not just absence of errors.
        assertEquals(
            before.index.symbols.filterNot { it.id in familyParamIds }.map { symbolRecord(it) }.sorted(),
            after.index.symbols.filterNot { it.id in familyParamIds }.map { symbolRecord(it) }.sorted(),
            "every non-target K2 symbol binding must be retained exactly",
        )
        // Exact non-target usage records (targetId + path + selected text); raw line/character
        // coordinates are omitted because the rename inserts characters, shifting later offsets
        // without any binding change.
        assertEquals(
            before.usages.filterNot { it.targetId in familyParamIds }.map { usageRecord(it, requireNotNull(snapshot)) }.sorted(),
            after.usages.filterNot { it.targetId in familyParamIds }.map { usageRecord(it, stagedSnapshot()) }.sorted(),
            "every non-target K2 usage binding must be retained exactly",
        )
        assertTrue(requireNotNull(plan).diagnosticsAfterPreview.none { it.severity == Diagnostic.Severity.ERROR }, requireNotNull(plan).toString())
    }

    @Then("^all Java sources compile against the staged Kotlin output$")
    fun allJavaSourcesCompileWithJdt() {
        val staged = stagedSnapshot()
        val java = JdtJavaSemanticAnalyzer()
        var analysis: JdtJavaSemanticAnalysisResult? = null
        val result = KotlinCompilerDiagnostics(toolchain).analyzeWithCompiledOutput(staged) { output ->
            analysis = java.analyze(staged, additionalClasspathEntries = listOf(output))
        }
        val available = assertIs<KotlinCompilerDiagnosticsResult.Available>(result, result.toString())
        assertTrue(available.diagnostics.none { it.severity == Diagnostic.Severity.ERROR }, available.toString())
        val jdt = requireNotNull(analysis) { "JDT analysis must run against staged Kotlin output" }
        assertTrue(jdt.warnings.isEmpty(), "JDT must report no warnings for staged Java sources: $jdt")
        assertTrue(requireNotNull(plan).diagnosticsAfterPreview.none { it.severity == Diagnostic.Severity.ERROR }, requireNotNull(plan).toString())
    }

    @Then("^every Java caller binding to the unchanged owner, name, and descriptor is preserved$")
    fun everyExactJavaCallerBindingPreserved() {
        val declaration = compilerCatalogue().declarations.getValue(requireNotNull(targetId))
        val before = javaCallerBindings(requireNotNull(snapshot), declaration.jvmOwner, declaration.jvmName, declaration.jvmDescriptor)
        val after = javaCallerBindings(stagedSnapshot(), declaration.jvmOwner, declaration.jvmName, declaration.jvmDescriptor)
        // Compare the concrete Java caller binding records (path/range/owner/name/descriptor), not
        // just absence of errors.
        assertEquals(before, after, "every exact Java caller binding to the unchanged owner/name/descriptor must be preserved")
        assertTrue(requireNotNull(plan).diagnosticsAfterPreview.none { it.severity == Diagnostic.Severity.ERROR }, requireNotNull(plan).toString())
    }

    @Then("^the preview records baseline and staged Kotlin plus Java compiler diagnostics$")
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

    @Given("^the combined staged proof would \"([^\"]+)\"$")
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

    @Given("^an approved successful preview renames parameter \"subtotal\" to \"netAmount\" across the exact family$")
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
        "^the managed-apply diagnostics gate for a parameter rename is the lazy combined Kotlin and Java change-signature gate$",
    )
    fun managedApplyGateIsLazyChangeSignatureGate() {
        // Real assertion: exercise the production ManagedApplyDiagnosticsGateSelector for the
        // changeSignature.renameParameter Kotlin route and assert it selects/enables the lazy
        // kotlin-k2-java-jdt-change-signature gate (the mixed K2+JDT diagnostics provider).
        val p = requireNotNull(plan)
        val selected = ManagedApplyDiagnosticsGateSelector.select(
            plan = p,
            languageId = "kotlin",
            javaAdapter = JavaLanguageAdapter(),
            kotlinAdapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain)),
            externalGateResolver = { requested ->
                error("changeSignature.renameParameter is a built-in Kotlin route; unexpected external gate lookup: $requested")
            },
        )
        assertEquals(
            "kotlin-k2-java-jdt-change-signature", selected.id,
            "managed-apply selector must select the lazy kotlin-k2-java-jdt-change-signature gate",
        )
        assertNotNull(selected.provider, "the lazy kotlin-k2-java-jdt-change-signature gate must be enabled (non-null provider)")
    }

    @Then("^apply uses the patch engine and writes a transaction rollback record$")
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

    @When("^a maintainer renames the parameter \"([^\"]+)\" to \"netAmount\"$")
    fun previewsRenameParameterToNetAmount(parameterOldName: String) {
        // Parameterized so the reconciled REQ-001 "family incompleteness refuses" outline's
        // external-boundary row (old name "value") drives the real production path; literal call
        // sites render "subtotal", preserving prior behavior.
        oldName = parameterOldName
        workspaceBaseline = snapshotWorkspaceContent(requireNotNull(fixtureRoot))
        drivePreview(acceptExternalConsumerRisk)
    }

    @When("^a maintainer renames the parameter \"subtotal\" to \"netAmount\" on a clean compiler-proven fixture$")
    fun previewsRenameParameterOnCleanCompilerProvenFixture() {
        // Defensive-gate scenario (approved-change-011): the clean compiler-proven fixture cannot
        // honestly induce the retained defensive gates, so the preview must succeed as a genuine
        // SEMANTIC_PREVIEW. The externally-visible family requires external-consumer-risk acceptance
        // to reach that success path (the feature asserts SEMANTIC_PREVIEW success, not a refusal).
        workspaceBaseline = snapshotWorkspaceContent(requireNotNull(fixtureRoot))
        drivePreview(acceptRisk = true)
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
        "^RefactorKit refuses the operation and explains why, reporting the typed code \"([^\"]+)\", and changes no file, plan, lock, or transaction record$",
    )
    fun selectionRefusedWithCode(declaredCode: String) {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.REFUSED, p.status, p.toString())
        assertEquals(
            declaredCode, p.refusalCode,
            "declared refusal code '$declaredCode' did not equal the actual code '${p.refusalCode}'",
        )
        // REQ-001 family-incompleteness outline: verify the fixture induced the EXACT declared
        // condition, not a substitute. Rows whose declared condition is unreachable from a compiler
        // fixture fail here with a precise RED finding (no false-green).
        if (familyCondition != null) {
            val catalogue = tryCompilerCatalogue()
            if (catalogue != null) verifyFamilyCondition(catalogue, requireNotNull(familyCondition))
        }
        assertTrue(p.workspaceEdit.edits.isEmpty(), p.toString())
        assertTrue(p.affectedFiles.isEmpty(), p.toString())
        assertTrue(!p.requiresUserApproval, p.toString())
        assertTrue(p.authorityLease == null, p.toString())
        verifyNoWorkspaceMutation()
        observedRefusals += ObservedRefusal(declaredCode, p.refusalCode, p.status)
    }

    // ------------------------------------------------------------------ REQ-001 family-incompleteness outline (approved change 012)

    // Row 5 (inducible "lacking one exact parameter declaration at the selected ordinal") keeps its
    // refusal kotlin.changeSignatureFamilyIncomplete. The generic outline renders the refusal as two
    // separate steps: this Then asserts the refusal and the EXACT induced family condition; the And
    // 'changes no file, plan, lock, or transaction record' proves no workspace mutation. Rows 1-4
    // (superseded by approved change 012) instead drive the defensive SEMANTIC_PREVIEW Then/And steps.
    @Then("^RefactorKit refuses the operation and explains why, reporting the typed code \"([^\"]+)\"$")
    fun familyIncompleteRefusedWithCode(declaredCode: String) {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.REFUSED, p.status, p.toString())
        assertEquals(
            declaredCode, p.refusalCode,
            "declared refusal code '$declaredCode' did not equal the actual code '${p.refusalCode}'",
        )
        // REQ-001 family-incompleteness ordinal-declaration row: verify the fixture induced the EXACT
        // declared condition, not a substitute.
        if (familyCondition != null) {
            val catalogue = tryCompilerCatalogue()
            if (catalogue != null) verifyFamilyCondition(catalogue, requireNotNull(familyCondition))
        }
        assertTrue(p.workspaceEdit.edits.isEmpty(), p.toString())
        assertTrue(p.affectedFiles.isEmpty(), p.toString())
        assertTrue(!p.requiresUserApproval, p.toString())
        assertTrue(p.authorityLease == null, p.toString())
        observedRefusals += ObservedRefusal(declaredCode, p.refusalCode, p.status)
    }

    // REQ-001 family-incompleteness outline row 5 assertion: no file, plan, lock, or transaction
    // record is changed by the refusal (the Then already asserted the refusal; this And proves the
    // workspace filesystem is byte-for-byte unchanged).
    @Then("^changes no file, plan, lock, or transaction record$")
    fun changesNoFilePlanLockOrTransactionRecord() {
        verifyNoWorkspaceMutation()
    }

    // ------------------------------------------------------------------ shared preview-succeeds Then

    // ------------------------------------------------------------------ defensive-gate Then steps (approved-change-011)

    @Then("^RefactorKit returns a successful read-only semantic preview \\(SEMANTIC_PREVIEW\\) with no refusal$")
    fun defensivePreviewSucceedsAsSemanticPreview() {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.PREVIEW, p.status, p.toString())
        assertTrue(p.refusalCode == null, p.toString())
        observedPreviews += ObservedPreview(p.status, p.riskLevel, p.refusalCode)
    }

    @Then("^the preview is read-only and does not mutate the snapshot or the filesystem$")
    fun defensivePreviewReadOnlyAndNoFilesystemMutation() {
        val snap = requireNotNull(snapshot)
        val p = requireNotNull(plan)
        assertEquals(snap.hash, p.snapshotHash, p.toString())
        assertTrue(
            snap.files.single { it.path.normalize() == Path.of("src/main/kotlin/fixture/billing/Calculator.kt").normalize() }.content.contains("subtotal"),
            "preview must not mutate the snapshot content",
        )
        verifyNoWorkspaceMutation()
        observedPreviews += ObservedPreview(p.status, p.riskLevel, p.refusalCode)
    }

    // ------------------------------------------------------------------ shared When/Then for apply + rollback

    @When("^the preview is applied under explicit authorization$")
    fun previewAppliedUnderExplicitAuthorization() {
        val root = requireNotNull(fixtureRoot)
        val snap = requireNotNull(snapshot)
        val p = requireNotNull(plan)
        // Exercise the production ManagedApplyDiagnosticsGateSelector instead of injecting the gate
        // directly: it selects the lazy kotlin-k2-java-jdt-change-signature gate for this operation
        // whose provider is the mixed K2+JDT KotlinJvmChangeSignaturePlanner::diagnostics.
        val gate = ManagedApplyDiagnosticsGateSelector.select(
            plan = p,
            languageId = "kotlin",
            javaAdapter = JavaLanguageAdapter(),
            kotlinAdapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain)),
            externalGateResolver = { requested ->
                error("changeSignature.renameParameter is a built-in Kotlin route; unexpected external gate lookup: $requested")
            },
        )
        assertEquals("kotlin-k2-java-jdt-change-signature", gate.id, "managed-apply gate for this operation")
        assertNotNull(gate.provider, "managed-apply gate must be enabled")
        applied = assertIs<ApplyResult.Applied>(PatchEngine(root).apply(
            p, snap, ApplyAuthorization.explicit("kotlin-jvm-change-signature-parameter-rename-cucumber"),
            gate,
        ))
    }

    @When("^that transaction is rolled back$")
    fun transactionRolledBack() {
        rolledBack = assertIs<ApplyResult.Applied>(
            PatchEngine(requireNotNull(fixtureRoot)).rollback(requireNotNull(applied).transaction),
        )
    }

    // ------------------------------------------------------------------ REQ-003 staged-proof oracle helpers

    private fun stagedSnapshot(): ProjectSnapshot {
        if (stagedSnapshotCache == null) {
            stagedSnapshotCache = WorkspaceEditSimulator.apply(requireNotNull(snapshot), requireNotNull(plan).workspaceEdit)
        }
        return requireNotNull(stagedSnapshotCache)
    }

    private fun stagedCatalogue(): KotlinCompilerSymbolsResult.Available {
        if (stagedCatalogueCache == null) {
            stagedCatalogueCache = assertIs<KotlinCompilerSymbolsResult.Available>(
                KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain)).compilerSymbols(stagedSnapshot()),
            )
        }
        return requireNotNull(stagedCatalogueCache)
    }

    private fun identityKey(evidence: KotlinCompilerDeclarationEvidence): String =
        listOf(evidence.jvmOwner, evidence.jvmName, evidence.jvmDescriptor).joinToString("\u0000")

    private fun usageCounts(cat: KotlinCompilerSymbolsResult.Available, params: List<Symbol>): List<String> =
        params.map { "${it.id.value}=${cat.usages.count { usage -> usage.targetId == it.id }}" }.sorted()

    private fun symbolRecord(symbol: Symbol): String =
        listOf(symbol.id.value, symbol.name, symbol.kind.name, symbol.location.path.toString()).joinToString("\u0000")

    private fun usageRecord(usage: KotlinCompilerResolvedUsage, source: ProjectSnapshot): String =
        // Semantic binding record (targetId + path + selected text). Raw line/character coordinates are
        // deliberately omitted: a parameter rename inserts characters, so later coordinates shift by
        // the edit delta without any binding change. The production semanticFingerprint compares the
        // same path/targetId/selected-text triple, so this oracle asserts exact non-target binding
        // retention with coordinate invariance.
        listOf(usage.targetId.value, usage.location.path.toString(), selectedTextAt(source, usage.location))
            .joinToString("\u0000")

    private fun selectedTextAt(source: ProjectSnapshot, location: SourceLocation): String {
        val file = source.files.singleOrNull { it.path.normalize() == location.path.normalize() } ?: return ""
        val content = file.content
        val start = runCatching { TextEdits.offsetOf(content, location.range.start) }.getOrNull() ?: return ""
        val end = runCatching { TextEdits.offsetOf(content, location.range.end) }.getOrNull() ?: return ""
        return if (start in 0..end && end <= content.length) content.substring(start, end) else ""
    }

    /** Exact Java caller binding records matching the target owner/name/descriptor, mirroring the JVM planner's javaBindings. */
    private fun javaCallerBindings(snapshot: ProjectSnapshot, owner: String, name: String, descriptor: String): List<String> {
        val java = JdtJavaSemanticAnalyzer()
        var bindings: List<JdtJavaSemanticBindingUse>? = null
        val result = KotlinCompilerDiagnostics(toolchain).analyzeWithCompiledOutput(snapshot) { output ->
            bindings = java.analyze(snapshot, additionalClasspathEntries = listOf(output)).bindingUses
        }
        val available = result as? KotlinCompilerDiagnosticsResult.Available
            ?: return emptyList()
        if (available.diagnostics.any { it.severity == Diagnostic.Severity.ERROR }) return emptyList()
        return bindings.orEmpty().mapNotNull { use ->
            val identity = use.jvmIdentity ?: return@mapNotNull null
            if (identity.ownerBinaryName != owner || identity.memberName != name || identity.descriptor != descriptor) {
                return@mapNotNull null
            }
            listOf(use.path.toString(), use.sourceRange.toString(), identity.ownerBinaryName, identity.memberName, identity.descriptor)
                .joinToString("\u0000")
        }.sorted()
    }

    /** REQ-001 family-incompleteness honesty gate: asserts the fixture induced the EXACT declared
     * condition for the current outline row (not a substitute). Rows whose declared condition is
     * unreachable from a compiler fixture fail here with a precise RED finding. */
    private fun verifyFamilyCondition(catalogue: KotlinCompilerSymbolsResult.Available, condition: String) {
        val targetEvidence = catalogue.declarations.getValue(requireNotNull(targetId))
        val parameters = catalogue.index.symbols.filter {
            it.kind == Symbol.Kind.PARAMETER && catalogue.declarations.getValue(it.id).let { ev ->
                ev.jvmOwner == targetEvidence.jvmOwner && ev.jvmName == targetEvidence.jvmName &&
                    ev.jvmDescriptor.substringBeforeLast('@') == targetEvidence.jvmDescriptor
            }
        }
        val selected = parameters.singleOrNull { it.name == oldName }
        val familyId = selected?.let { catalogue.declarations.getValue(it.id).overrideFamilyId }
        val familyFunctions = catalogue.index.symbols.filter {
            it.kind == Symbol.Kind.FUNCTION && catalogue.declarations.getValue(it.id).overrideFamilyId == familyId
        }
        val ordinal = selected?.let { catalogue.declarations.getValue(it.id).jvmDescriptor.substringAfterLast('@', "").toIntOrNull() }
        val familyParameters = catalogue.index.symbols.filter {
            it.kind == Symbol.Kind.PARAMETER && catalogue.declarations.getValue(it.id).let { ev ->
                ev.overrideFamilyId == familyId && ev.jvmDescriptor.substringAfterLast('@', "").toIntOrNull() == ordinal
            }
        }
        val inWorkspaceCalcTotal = targetEvidence.jvmOwner.startsWith("fixture.billing.") &&
            targetEvidence.jvmName == "calculateTotal"
        when (condition) {
            "incomplete with fewer family functions than family parameters" ->
                assertTrue(
                    familyFunctions.size < familyParameters.size,
                    "REQ-001 row 'incomplete with fewer family functions than family parameters' not induced: " +
                        "familyFunctions=${familyFunctions.size} familyParameters=${familyParameters.size}; compiler emits " +
                        "familyParameters.size <= familyFunctions.size, so 'fewer family functions than family parameters' " +
                        "is unreachable from a compiler fixture",
                )
            "ambiguous with a function and parameter family that disagree" ->
                assertTrue(
                    selected != null && familyId != targetEvidence.overrideFamilyId,
                    "REQ-001 row 'ambiguous with a function and parameter family that disagree' not induced: " +
                        "selected-family=${familyId?.take(24)} target-family=${targetEvidence.overrideFamilyId.take(24)}; " +
                        "a compiler parameter always inherits its function's override family, so the " +
                        "familyId != targetEvidence.overrideFamilyId gate is unreachable from a compiler fixture",
                )
            "crossing an external or unavailable declaration boundary" ->
                assertTrue(
                    inWorkspaceCalcTotal && targetEvidence.hasExternalHierarchyBoundary,
                    "REQ-001 row 'crossing an external or unavailable declaration boundary' not induced: " +
                        "target=${targetEvidence.jvmOwner}.${targetEvidence.jvmName} " +
                        "inWorkspaceCalcTotal=${inWorkspaceCalcTotal} extBoundary=${targetEvidence.hasExternalHierarchyBoundary}; " +
                        "no in-workspace fixture.billing.calculateTotal member carries an external hierarchy boundary " +
                        "(only an actual external override such as java.util.function.Function.apply carries one, " +
                        "which the feature does not declare)",
                )
            "a hierarchy member with fewer than two family functions" ->
                assertTrue(
                    inWorkspaceCalcTotal && targetEvidence.isHierarchyMember && familyFunctions.size < 2,
                    "REQ-001 row 'a hierarchy member with fewer than two family functions' not induced: " +
                        "target=${targetEvidence.jvmOwner}.${targetEvidence.jvmName} " +
                        "inWorkspaceCalcTotal=${inWorkspaceCalcTotal} isHierarchy=${targetEvidence.isHierarchyMember} " +
                        "familyFunctions=${familyFunctions.size}; an in-workspace calculateTotal hierarchy member with " +
                        "fewer than two family functions requires an external base declaring calculateTotal, which is unavailable",
                )
            "lacking one exact parameter declaration at the selected ordinal" ->
                assertTrue(
                    familyParameters.size != familyFunctions.size || familyParameters.isEmpty(),
                    "REQ-001 row 'lacking one exact parameter declaration at the selected ordinal' not induced: " +
                        "familyFunctions=${familyFunctions.size} familyParameters=${familyParameters.size}",
                )
            else -> error("unknown family condition: $condition")
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun drivePreview(acceptRisk: Boolean) {
        val snap = requireNotNull(snapshot)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain))
        // Tolerate a non-Available catalogue: the "baseline K2 errors" row genuinely does not
        // compile, so production returns kotlin.symbolCompilationFailed before resolving the target.
        lastCatalogue = tryCompilerCatalogue()
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
        val catalogue = tryCompilerCatalogue()
        if (catalogue == null) {
            // Baseline K2 errors (e.g. kotlin.symbolCompilationFailed): no Available catalogue to
            // select from. Production refuses before resolving the target, so keep a fallback
            // identity for the planner call; the refusal Then only needs the returned code.
            lastCatalogue = null
            targetId = SymbolId("fixture.billing.calculateTotal")
            targetOwner = null
            return
        }
        lastCatalogue = catalogue
        val candidate = if (selectApply) {
            // The external-boundary rows select the single in-workspace override that crosses an
            // external (non-workspace) declaration boundary. The fixture exposes exactly one such
            // override carrying a parameter named `subtotal`, so firstOrNull is deterministic.
            catalogue.index.symbols.firstOrNull { symbol ->
                symbol.kind == Symbol.Kind.FUNCTION &&
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

    /** Returns null when the compiler catalogue is not Available (baseline K2 errors/incomplete symbols). */
    private fun tryCompilerCatalogue(): KotlinCompilerSymbolsResult.Available? {
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain))
        val result = adapter.compilerSymbols(requireNotNull(snapshot))
        if (result is KotlinCompilerSymbolsResult.Refused || result is KotlinCompilerSymbolsResult.Error) {
            return null
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
        stagedSnapshotCache = null
        stagedCatalogueCache = null
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
