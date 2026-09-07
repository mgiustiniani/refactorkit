package org.refactorkit.jvm.renameclass

import io.cucumber.java.After
import io.cucumber.java.Scenario
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.java.JavaFrameworkDetector
import org.refactorkit.java.JavaLanguageAdapter
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.java.JavaRenameClassPlanner
import org.refactorkit.java.JdtJavaSemanticAnalyzer
import org.refactorkit.jvm.refusals.KotlinJvmRefusalScenarioContext
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
 * Story BDD glue for features/java-rename-class-characterization.feature
 * (REQ-JAVA-RENAME-CLASS-CHAR-001, row C-RENAME-TYPE of the approved finite J1 Java catalogue).
 *
 * It drives the real toolchain: a Java workspace scanned by JavaProjectScanner into a real build
 * model, the real JavaLanguageAdapter, and the real JavaRenameClassPlanner.preview. The feature
 * begins GREEN because production already implements the planner; the glue asserts the ACTUAL
 * behavior rather than inventing it.
 *
 * Fixtures:
 *  - The standard fixture uses a Maven jar module whose pom declares <sourceDirectory>src/main/java
 *    so real scanned SourceFile paths are the bare src/main/java/... names the feature messages
 *    declare (e.g. src/main/java/com/example/UserService.java).
 *  - The generated-source fixtures use a pom declaring <sourceDirectory>.</sourceDirectory> so a
 *    root-level Generated.java is scanned as exactly "Generated.java", matching the declared
 *    generated-source messages.
 *  - The framework-annotation fixtures include lightweight stub annotation classes on the same
 *    source root so the JDT binding analysis is genuinely clean (resolved imports), matching the
 *    declared "JDT binding analysis is clean" precondition while keeping risk HIGH via the
 *    framework findings.
 *
 * Every refusal condition is a truthful fixture that makes the real planner return the DECLARED
 * MESSAGE — no message is invented, no typed renameClass.xxx code is introduced, and no production
 * branch is weakened. Refusal Then steps assert REFUSED, the exact real message in summary and
 * warnings, an empty WorkspaceEdit, an empty affected-file set, no approval, no managed-write
 * eligibility, confidence 0.0, risk HIGH, and no typed refusal code.
 *
 * Reconciliation (candidate 051ed05, feature SHA 88d58a29):
 *  - The dead-code "Declaration file not found" refusal case was REMOVED from the feature: the
 *    planner builds its symbol index from the same snapshot.files it searches, so a found symbol's
 *    location.path is always present and that branch is unreachable through the public API.
 *  - The generated-source refusal message is reconciled to the full scanned path:
 *    "Generated source cannot be rewritten: build/generated/sources/annotationProcessor/java/main/
 *    Generated.java (path is inside a generated-source or build-output location)" — a file inside
 *    build/generated/... scans with the full generated path, never the bare "Generated.java".
 * The glue now asserts the reconciled full-path message for the generated-source condition.
 *
 * Slice glue-shared-refusal-r005 (migration to shared refusal glue): the three granular
 * Scenario 5 refusal-inspection steps — the caller inspects the refusal plan, the refusal
 * carries an empty WorkspaceEdit, and the refusal carries an empty affected-file set — moved
 * verbatim into the shared glue package org.refactorkit.jvm.refusals (KotlinJvmSharedRefusalSteps
 * over the KotlinJvmRefusalScenarioContext port). adapterReturnsRefusedRenameClassPlan, the
 * leaf's single plan-assignment site for the refusal preview, publishes the produced plan into
 * the injected scenario context, and the leaf plan field remains for every other leaf scenario.
 * The shared steps assert emptiness only; the REFUSED status of the refusal stays asserted in
 * this scenario by the leaf Given, the combined no-approval/no-managed-write step, and the
 * no-typed-refusal-code step. Step semantics and the @After report/cleanup behavior are
 * unchanged; the leaf's other duplicate groups (combined and characterization vocabulary) are
 * intentionally kept for later shared-glue ports.
 */
class KotlinJvmRenameClassCharacterizationSteps(private val context: KotlinJvmRefusalScenarioContext) {
    private val temporaryDirectories = mutableListOf<Path>()
    private val observedRefusals = mutableListOf<ObservedRefusal>()

    private var fixtureRoot: Path? = null
    private var snapshot: ProjectSnapshot? = null
    private var plan: PatchPlan? = null
    private var condition: String? = null

    private var symbolFqn: String? = null
    private var newName: String? = null
    private var declarationPath: Path? = null

    private val adapter = JavaLanguageAdapter()
    private val planner = JavaRenameClassPlanner(adapter)

    private val STANDARD_DECLARATION = Path.of("src/main/java/com/example/UserService.java")

    // ------------------------------------------------------------ Scenario 1 Given

    @Given("^a Java workspace snapshot that contains a recognized non-generated Java type declaration file and at least one referencing Java file$")
    fun workspaceWithRecognizedNonGeneratedTypeDeclaration() {
        fixtureRoot = buildStandardFixture()
        snapshot = JavaProjectScanner().scan(requireNotNull(fixtureRoot))
        assertTrue(requireNotNull(snapshot).buildModels.isNotEmpty(), "fixture must yield a real build model")
        symbolFqn = "com.example.UserService"
        newName = "AccountManager"
        declarationPath = STANDARD_DECLARATION
    }

    @Given("^the JDT binding analysis is clean and resolves the type binding with no error warnings$")
    fun jdtBindingCleanAndResolvesTypeBinding() {
        val snap = requireNotNull(snapshot)
        val analysis = JdtJavaSemanticAnalyzer().analyze(snap)
        assertTrue(
            analysis.warnings.isEmpty(),
            "expected clean JDT binding, got warnings: ${analysis.warnings.map { it.message }}",
        )
        val target = analysis.symbols.singleOrNull { it.qualifiedName == requireNotNull(symbolFqn) }
        assertNotNull(target, "expected the JDT type binding to resolve $symbolFqn")
        assertNotNull(target.bindingKey, "expected a resolved type binding key")
    }

    @Given("^the rename touches fewer than eleven files and the declaration carries no framework annotation$")
    fun renameTouchesFewerThanElevenFilesAndNoFrameworkAnnotation() {
        val snap = requireNotNull(snapshot)
        val decl = requireNotNull(snap.files.singleOrNull { it.path == requireNotNull(declarationPath) }) {
            "missing declaration file $declarationPath"
        }
        assertTrue(
            JavaFrameworkDetector.assess(decl).hasFindings.not(),
            "expected no framework annotation on the declaration",
        )
        assertTrue(snap.files.size < 11, "fixture must touch fewer than eleven files")
    }

    // ---------------------------------------------------------- Scenario 2 outline Given

    @Given("^a Java workspace snapshot whose rename declaration file carries a (.+) annotation$")
    fun workspaceWithFrameworkAnnotation(framework: String) {
        val fw = framework.trim()
        fixtureRoot = buildFrameworkFixture(fw)
        snapshot = JavaProjectScanner().scan(requireNotNull(fixtureRoot))
        when (fw) {
            "Spring" -> {
                symbolFqn = "com.example.SpringBean"
                newName = "RenamedBean"
                declarationPath = Path.of("src/main/java/com/example/SpringBean.java")
            }
            "JPA" -> {
                symbolFqn = "com.example.JpaEntity"
                newName = "RenamedEntity"
                declarationPath = Path.of("src/main/java/com/example/JpaEntity.java")
            }
            "Jackson" -> {
                symbolFqn = "com.example.JsonType"
                newName = "RenamedType"
                declarationPath = Path.of("src/main/java/com/example/JsonType.java")
            }
            else -> error("Unknown framework: '$framework'")
        }
    }

    @Given("^the JDT binding analysis is clean for that declaration$")
    fun jdtBindingCleanForDeclaration() {
        val snap = requireNotNull(snapshot)
        val analysis = JdtJavaSemanticAnalyzer().analyze(snap)
        assertTrue(
            analysis.warnings.none { it.path == requireNotNull(declarationPath) },
            "expected clean JDT binding for $declarationPath, got warnings: ${analysis.warnings.filter { it.path == declarationPath }.map { it.message }}",
        )
    }

    // ----------------------------------------------------------- Scenario 3 Given

    @Given("^a Java workspace snapshot whose rename declaration file carries no framework annotation$")
    fun workspaceWithNoFrameworkAnnotation() {
        fixtureRoot = buildLargeFixture()
        snapshot = JavaProjectScanner().scan(requireNotNull(fixtureRoot))
        symbolFqn = "com.example.UserService"
        newName = "AccountManager"
        declarationPath = STANDARD_DECLARATION
    }

    @Given("^the rename touches twelve files across the declaration and its referencing files$")
    fun renameTouchesTwelveFiles() {
        val snap = requireNotNull(snapshot)
        val referencingFiles = snap.files.filter { it.path != requireNotNull(declarationPath) && it.languageId == "java" }
        assertTrue(referencingFiles.size == 10, "fixture must provide ten referencing files, got ${referencingFiles.size}")
    }

    // ----------------------------------------------------------- Scenario 4 Given

    @Given("^the JDT binding analysis reports an error warning or no usable binding for the type$")
    fun jdtBindingReportsErrorOrNoUsableBinding() {
        // Truthfully make the JDT binding unclean: add an unresolved dependency to the declaration
        // file, then rescan. The real planner falls back to a lexical rename (LEXICAL_FALLBACK).
        val root = requireNotNull(fixtureRoot)
        val decl = root.resolve("src/main/java/com/example/UserService.java")
        decl.writeText("package com.example;\npublic class UserService { private MissingDependency dependency; }\n")
        snapshot = JavaProjectScanner().scan(root)
        val analysis = JdtJavaSemanticAnalyzer().analyze(requireNotNull(snapshot))
        assertTrue(
            analysis.warnings.isNotEmpty(),
            "expected an error warning or no usable binding, got clean JDT: ${analysis.symbols.map { it.qualifiedName }}",
        )
    }

    // ----------------------------------------------------------- Scenario 5 Given

    @Given("^the Java adapter returns a REFUSED patch plan for a renameClass preview$")
    fun adapterReturnsRefusedRenameClassPlan() {
        fixtureRoot = buildStandardFixture()
        snapshot = JavaProjectScanner().scan(requireNotNull(fixtureRoot))
        // A real refusal: an invalid Java method name. The plan is REFUSED and carries the real message.
        plan = preview(requireNotNull(snapshot), "com.example.UserService", "1rename")
        context.plan = plan
        assertEquals(PatchStatus.REFUSED, requireNotNull(plan).status, requireNotNull(plan).summary)
    }

    // --------------------------------------------------------- Scenario 6 outline Given

    @Given("^a Java workspace snapshot for a renameClass preview under the condition (.+)$")
    fun workspaceUnderCondition(condition: String) {
        this.condition = condition.trim()
        fixtureRoot = buildFixture(this.condition ?: "default")
        snapshot = JavaProjectScanner().scan(requireNotNull(fixtureRoot))
    }

    // ----------------------------------------------------------- Scenario 1 When

    @When("^the caller requests a renameClass preview from the current fully qualified type name to a valid new simple name$")
    fun previewSuccessfulRename() {
        plan = preview(requireNotNull(snapshot), requireNotNull(symbolFqn), requireNotNull(newName))
    }

    // ------------------------------------------------------ Scenarios 2 & 3 When

    @When("^the caller requests a renameClass preview for that declaration$")
    fun previewForDeclaration() {
        plan = preview(requireNotNull(snapshot), requireNotNull(symbolFqn), requireNotNull(newName))
    }

    // -------------------------------------------------------- Scenario 6 When

    @When("^the caller requests a renameClass preview$")
    fun previewUnderCondition() {
        previewUnderCondition(requireNotNull(condition))
    }

    // ------------------------------------------------------------ Scenario 1 Thens

    @Then("^the adapter returns a PREVIEW patch plan for the operation renameClass$")
    fun adapterReturnsPreviewPlan() {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.PREVIEW, p.status, "${p.summary}")
        assertEquals("renameClass", p.operation, p.toString())
    }

    @Then("^the plan carries confidence 0.96$")
    fun planCarriesConfidence096() {
        assertEquals(0.96, requireNotNull(plan).confidence, requireNotNull(plan).toString())
    }

    @Then("^the plan requires user approval$")
    fun planRequiresUserApproval() {
        assertTrue(requireNotNull(plan).requiresUserApproval, "expected requiresUserApproval=true: ${requireNotNull(plan)}")
    }

    @Then("^the plan evidence is JDT_BINDING$")
    fun planEvidenceJdtBinding() {
        assertEquals(RefactoringEvidence.JDT_BINDING, requireNotNull(plan).evidence, requireNotNull(plan).toString())
    }

    @Then("^the plan risk is LOW$")
    fun planRiskLow() {
        assertEquals(RiskLevel.LOW, requireNotNull(plan).riskLevel, requireNotNull(plan).toString())
    }

    @Then("^the plan risk is MEDIUM$")
    fun planRiskMedium() {
        assertEquals(RiskLevel.MEDIUM, requireNotNull(plan).riskLevel, requireNotNull(plan).toString())
    }

    @Then("^the plan risk is HIGH$")
    fun planRiskHigh() {
        assertEquals(RiskLevel.HIGH, requireNotNull(plan).riskLevel, requireNotNull(plan).toString())
    }

    @Then("^the plan lists the declaration file, every referencing file, and the new file in its affected-file set$")
    fun planListsDeclarationReferencingAndNewFile() {
        val p = requireNotNull(plan)
        val decl = requireNotNull(declarationPath)
        assertTrue(p.affectedFiles.contains(decl), "expected declaration $decl in affected files: ${p.affectedFiles}")
        val snap = requireNotNull(snapshot)
        val referencingFiles = snap.files.filter { it.path != decl && it.languageId == "java" }
        assertTrue(
            referencingFiles.all { p.affectedFiles.contains(it.path) },
            "expected every referencing file in affected files: ${p.affectedFiles}",
        )
        val newFile = decl.resolveSibling("${requireNotNull(newName)}.java")
        assertTrue(p.affectedFiles.contains(newFile), "expected new file $newFile in affected files: ${p.affectedFiles}")
    }

    @Then("^the plan carries a WorkspaceEdit that combines the JDT source-range modifications and one FileEdit.Rename from the declaration file to the new file name$")
    fun planWorkspaceEditCombinesJdtModificationsAndRename() {
        val p = requireNotNull(plan)
        val decl = requireNotNull(declarationPath)
        val newFile = decl.resolveSibling("${requireNotNull(newName)}.java")
        val modifies = p.workspaceEdit.edits.filterIsInstance<FileEdit.Modify>()
        assertTrue(modifies.isNotEmpty(), "expected JDT source-range modifications: ${p.workspaceEdit.edits}")
        assertTrue(
            modifies.any { it.path == decl },
            "expected a Modify on the declaration file: ${p.workspaceEdit.edits}",
        )
        val rename = p.workspaceEdit.edits.filterIsInstance<FileEdit.Rename>()
        assertEquals(1, rename.size, "expected exactly one FileEdit.Rename: ${p.workspaceEdit.edits}")
        assertEquals(decl, rename[0].path, "the Rename must move the declaration file")
        assertEquals(newFile, rename[0].newPath, "the Rename must target the new file name")
    }

    @Then("^the plan warns that the JDT type binding was selected and that declaration, constructor, and reference edits use exact JDT source ranges$")
    fun planWarnsJdtBindingSelected() {
        val p = requireNotNull(plan)
        assertTrue(
            p.warnings.any { it.contains("JDT type binding selected") && it.contains("exact JDT source ranges") },
            "expected the JDT-binding-selected warning: ${p.warnings}",
        )
    }

    @Then("^the plan warns that string literals and comments are NOT scanned and that reflection and annotation processor output require manual review$")
    fun planWarnsStringsAndReflection() {
        val p = requireNotNull(plan)
        assertTrue(
            p.warnings.any {
                it.contains("String literals and comments are NOT scanned") &&
                    it.contains("Reflection and annotation processor output require manual review")
            },
            "expected the string-literal/reflection warning: ${p.warnings}",
        )
    }

    // ---------------------------------------------------------- Scenario 2 Thens

    @Then("^the plan carries a warning that names the (.+) framework and the detected annotation$")
    fun planWarnsFrameworkAndAnnotation(framework: String) {
        val fw = framework.trim()
        // Production warns with the framework enum name (SPRING/JPA/JACKSON), not the human-readable
        // Examples label (Spring/JPA/Jackson). Map to the enum-name form to assert the real warning text.
        val enumName = when (fw) {
            "Spring" -> "SPRING"
            "JPA" -> "JPA"
            "Jackson" -> "JACKSON"
            else -> fw
        }
        val p = requireNotNull(plan)
        assertTrue(
            p.warnings.any { it.contains("$enumName annotation(s) detected") && it.contains("@") },
            "expected a warning naming the $fw framework and the detected annotation: ${p.warnings}",
        )
    }

    @Then("^the plan warns about the framework annotation locations$")
    fun planWarnsFrameworkAnnotationLocations() {
        val p = requireNotNull(plan)
        assertTrue(
            p.warnings.any { it.contains("Framework annotation locations") },
            "expected the framework annotation locations warning: ${p.warnings}",
        )
    }

    // ---------------------------------------------------------- Scenario 3 Thens

    @Then("^the plan warns that the rename is large and reports that 12 files are affected$")
    fun planWarnsLargeRename12Files() {
        val p = requireNotNull(plan)
        assertTrue(
            p.warnings.any { it.contains("Large rename") && it.contains("12 files affected") },
            "expected the large-rename warning reporting 12 files: ${p.warnings}",
        )
        assertEquals(12, p.affectedFiles.size, "expected 12 affected files: ${p.affectedFiles}")
    }

    // ---------------------------------------------------------- Scenario 4 Thens

    @Then("^the plan carries confidence 0.92$")
    fun planCarriesConfidence092() {
        assertEquals(0.92, requireNotNull(plan).confidence, requireNotNull(plan).toString())
    }

    @Then("^the plan evidence is LEXICAL_FALLBACK$")
    fun planEvidenceLexicalFallback() {
        assertEquals(RefactoringEvidence.LEXICAL_FALLBACK, requireNotNull(plan).evidence, requireNotNull(plan).toString())
    }

    @Then("^the plan warns that JDT type-binding evidence was unavailable or not clean and that the rename uses lexical fallback and needs careful review$")
    fun planWarnsLexicalFallback() {
        val p = requireNotNull(plan)
        assertTrue(
            p.warnings.any {
                it.contains("JDT type-binding evidence was unavailable or not clean") &&
                    it.contains("lexical fallback") &&
                    it.contains("Review carefully")
            },
            "expected the lexical-fallback warning: ${p.warnings}",
        )
    }

    // ------------------------------------------------------------ Scenario 5 Thens

    @Then("^the refusal operation name is deterministically renameClass$")
    fun refusalOperationName() {
        val p = requireNotNull(plan)
        assertEquals("renameClass", p.operation, p.toString())
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

    @Then("^the refusal carries no typed refusal code because refusals are message-based$")
    fun refusalCarriesNoTypedCode() {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.REFUSED, p.status, p.toString())
        assertTrue(p.refusalCode == null, "expected no typed refusal code: ${p.refusalCode}")
    }

    // ------------------------------------------------------------ Scenario 6 Thens

    @Then("^the adapter returns a REFUSED patch plan for the operation renameClass$")
    fun adapterReturnsRefusedRenameClass() {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.REFUSED, p.status, p.toString())
        assertEquals("renameClass", p.operation, p.toString())
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

    private fun preview(snap: ProjectSnapshot, fqn: String, newName: String): PatchPlan =
        planner.preview(snap, fqn, newName)

    private fun previewUnderCondition(condition: String) {
        val snap = requireNotNull(snapshot)
        when (condition) {
            "the caller supplies a new simple name that is not a valid Java identifier" ->
                plan = preview(snap, "com.example.UserService", "1rename")
            "the caller supplies a new simple name identical to the old simple name" ->
                plan = preview(snap, "com.example.UserService", "UserService")
            "the symbol is absent from the index or is not a renameable type" ->
                plan = preview(snap, "com.example.Missing", "New")
            "the declaration path is inside a generated-source or build-output location" ->
                plan = preview(snap, "com.example.Generated", "Renamed")
            "the declaration source declares a generated-code annotation" ->
                plan = preview(snap, "com.example.Generated", "Renamed")
            "the declaration source header identifies generated code" ->
                plan = preview(snap, "com.example.Generated", "Renamed")
            "the new fully qualified name or the new file path already exists" ->
                plan = preview(snap, "com.example.UserService", "AccountService")
            else -> error("Unknown condition: '$condition'")
        }
    }

    private fun buildFixture(condition: String): Path = when (condition) {
        "the caller supplies a new simple name that is not a valid Java identifier",
        "the caller supplies a new simple name identical to the old simple name",
        "the symbol is absent from the index or is not a renameable type",
        -> buildStandardFixture()
        "the declaration path is inside a generated-source or build-output location" ->
            buildGeneratedPathFixture()
        "the declaration source declares a generated-code annotation" ->
            buildGeneratedAnnotationFixture()
        "the declaration source header identifies generated code" ->
            buildGeneratedHeaderFixture()
        "the new fully qualified name or the new file path already exists" ->
            buildTargetExistsFixture()
        else -> error("Unknown condition: '$condition'")
    }

    private fun buildStandardFixture(): Path {
        val root = temporaryDirectory("rk-jvm-renameclass-standard")
        writePom(root, "src/main/java")
        write(
            root,
            "src/main/java/com/example/UserService.java",
            "package com.example;\npublic class UserService { public UserService() {} }\n",
        )
        write(
            root,
            "src/main/java/com/example/Client.java",
            "package com.example;\npublic class Client { UserService s = new UserService(); }\n",
        )
        return root
    }

    private fun buildGeneratedPathFixture(): Path {
        val root = temporaryDirectory("rk-jvm-renameclass-gen-path")
        writePom(root, ".")
        write(
            root,
            "build/generated/sources/annotationProcessor/java/main/Generated.java",
            "package com.example;\npublic class Generated {}\n",
        )
        return root
    }

    private fun buildGeneratedAnnotationFixture(): Path {
        val root = temporaryDirectory("rk-jvm-renameclass-gen-annotation")
        writePom(root, ".")
        write(
            root,
            "Generated.java",
            "package com.example;\n@Generated\npublic class Generated {}\n",
        )
        return root
    }

    private fun buildGeneratedHeaderFixture(): Path {
        val root = temporaryDirectory("rk-jvm-renameclass-gen-header")
        writePom(root, ".")
        write(
            root,
            "Generated.java",
            "// generated by a schema processor, do not edit\npackage com.example;\npublic class Generated {}\n",
        )
        return root
    }

    private fun buildTargetExistsFixture(): Path {
        val root = temporaryDirectory("rk-jvm-renameclass-target-exists")
        writePom(root, "src/main/java")
        write(
            root,
            "src/main/java/com/example/UserService.java",
            "package com.example;\npublic class UserService {}\n",
        )
        write(
            root,
            "src/main/java/com/example/AccountService.java",
            "package com.example;\npublic class AccountService {}\n",
        )
        return root
    }

    private fun buildFrameworkFixture(framework: String): Path {
        val root = temporaryDirectory("rk-jvm-renameclass-framework")
        writePom(root, "src/main/java")
        when (framework) {
            "Spring" -> {
                write(
                    root,
                    "src/main/java/org/springframework/stereotype/Component.java",
                    "package org.springframework.stereotype;\npublic @interface Component {}\n",
                )
                write(
                    root,
                    "src/main/java/com/example/SpringBean.java",
                    "package com.example;\nimport org.springframework.stereotype.Component;\n@Component\npublic class SpringBean {}\n",
                )
            }
            "JPA" -> {
                write(
                    root,
                    "src/main/java/jakarta/persistence/Entity.java",
                    "package jakarta.persistence;\npublic @interface Entity {}\n",
                )
                write(
                    root,
                    "src/main/java/com/example/JpaEntity.java",
                    "package com.example;\nimport jakarta.persistence.Entity;\n@Entity\npublic class JpaEntity {}\n",
                )
            }
            "Jackson" -> {
                write(
                    root,
                    "src/main/java/com/fasterxml/jackson/annotation/JsonProperty.java",
                    "package com.fasterxml.jackson.annotation;\npublic @interface JsonProperty { String value() default \"\"; }\n",
                )
                write(
                    root,
                    "src/main/java/com/example/JsonType.java",
                    "package com.example;\nimport com.fasterxml.jackson.annotation.JsonProperty;\n@JsonProperty(\"x\")\npublic class JsonType {}\n",
                )
            }
            else -> error("Unknown framework: '$framework'")
        }
        return root
    }

    private fun buildLargeFixture(): Path {
        val root = temporaryDirectory("rk-jvm-renameclass-large")
        writePom(root, "src/main/java")
        write(
            root,
            "src/main/java/com/example/UserService.java",
            "package com.example;\npublic class UserService { public UserService() {} }\n",
        )
        for (i in 1..10) {
            write(
                root,
                "src/main/java/com/example/ref/Ref$i.java",
                "package com.example.ref;\nimport com.example.UserService;\npublic class Ref$i { UserService s = new UserService(); }\n",
            )
        }
        return root
    }

    private fun writePom(root: Path, sourceDir: String) {
        write(
            root,
            "pom.xml",
            """
            <project><modelVersion>4.0.0</modelVersion>
              <groupId>example</groupId><artifactId>rename</artifactId><version>1</version>
              <build><sourceDirectory>$sourceDir</sourceDirectory></build>
            </project>
            """.trimIndent(),
        )
    }

    private fun write(root: Path, rel: String, content: String) {
        val p = root.resolve(rel)
        p.parent.createDirectories()
        p.writeText(content)
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
        val report = reportDir.resolve("java-rename-class-characterization-messages.txt")
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
