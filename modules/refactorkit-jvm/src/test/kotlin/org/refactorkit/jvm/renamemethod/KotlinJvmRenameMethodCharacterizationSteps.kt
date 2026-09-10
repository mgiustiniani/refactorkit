package org.refactorkit.jvm.renamemethod

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
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.java.JavaLanguageAdapter
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.java.JavaRenameMemberPlanner
import org.refactorkit.java.JdtJavaSemanticAnalyzer
import org.refactorkit.java.JdtJavaSemanticSymbolKind
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
 * Story BDD glue for features/java-rename-method-characterization.feature
 * (REQ-JAVA-RENAME-METHOD-CHAR-001, row C-RENAME-METHOD of the approved finite J1 Java catalogue).
 *
 * It drives the real toolchain: a Java workspace scanned by JavaProjectScanner into a real build
 * model, the real JavaLanguageAdapter, and the real JavaRenameMemberPlanner.preview. The feature
 * begins GREEN because production already implements the planner; the glue asserts the ACTUAL
 * behavior rather than inventing it.
 *
 * Fixtures:
 *  - The standard fixture uses a Maven jar module whose pom declares <sourceDirectory>src/main/java
 *    so real scanned SourceFile paths are the bare src/main/java/... names the feature messages
 *    declare (e.g. src/main/java/com/example/UserService.java). UserService declares exactly one
 *    signed method validate(String), and Client.java references it so the signed-JDT path produces
 *    a declaration edit plus a reference edit.
 *  - The generated-source fixtures use a pom declaring <sourceDirectory>.</sourceDirectory> so a
 *    root-level Generated.java is scanned as exactly "Generated.java", matching the declared
 *    generated-source messages.
 *  - The override-family fixture uses a Base/Child inheritance hierarchy plus a referencing Client;
 *    JDT detects the override relation structurally, so the family has two source declarations and
 *    the plan is MEDIUM risk with confidence 0.91.
 *  - The "override family outside the workspace" fixture compiles com.example.lib.Base (with
 *    validate(String)) into a small JAR and declares it as a system-scoped Maven dependency, so the
 *    workspace method overrides a supertype method that is NOT in the scanned source workspace. The
 *    real planner refuses with the declared "outside the scanned source workspace" message.
 *
 * Every refusal condition is a truthful fixture that makes the real planner return the DECLARED
 * MESSAGE — no message is invented, no typed renameMember.xxx code is introduced, and no production
 * branch is weakened. Refusal Then steps assert REFUSED, the exact real message in summary and
 * warnings, an empty WorkspaceEdit, an empty affected-file set, no approval, no managed-write
 * eligibility, confidence 0.0, risk HIGH, and no typed refusal code.
 *
 * Removed RED rows (reconciled at candidate d902107): four refusal Examples rows were unreachable
 * through a truthful fixture because they are dead code in the planner or are preempted by JDT
 * behavior, so the feature reconcile removed them and this glue no longer asserts them:
 *  - "Owner declaration file not found": the planner builds its symbol index from the same
 *    snapshot.files it searches, so a found owner symbol's location.path is always present.
 *  - "No occurrences of 'validate' found": the owner declaration file is always in the reference
 *    scope and contains the declaration occurrence, so the lexical path always finds at least one.
 *  - "signed selector ... did not resolve to exactly one ... found 2": two methods with the same
 *    signature in one class are a Java compile error, so JDT reports warnings and the earlier
 *    "requires clean JDT semantic evidence" refusal fires instead.
 *  - "signed ... has no JDT binding key": clean JDT semantics imply the resolved method candidate
 *    carries a binding key, so this branch is not reachable through the public API.
 */
class KotlinJvmRenameMethodCharacterizationSteps {
    private val temporaryDirectories = mutableListOf<Path>()
    private val observedRefusals = mutableListOf<ObservedRefusal>()

    private var fixtureRoot: Path? = null
    private var snapshot: ProjectSnapshot? = null
    private var plan: PatchPlan? = null
    private var condition: String? = null

    private var symbolFqnWithMember: String? = null
    private var newMemberName: String? = null
    private var declarationPath: Path? = null

    private val adapter = JavaLanguageAdapter()
    private val planner = JavaRenameMemberPlanner(adapter)

    private val STANDARD_DECLARATION = Path.of("src/main/java/com/example/UserService.java")

    // ------------------------------------------------------------ Scenario 1 Given

    @Given("^a Java workspace snapshot whose owner type declares exactly one signed method matching the requested selector$")
    fun workspaceWithSingleSignedMethod() {
        fixtureRoot = buildStandardFixture()
        snapshot = JavaProjectScanner().scan(requireNotNull(fixtureRoot))
        assertTrue(requireNotNull(snapshot).buildModels.isNotEmpty(), "fixture must yield a real build model")
        symbolFqnWithMember = "com.example.UserService#validate(java.lang.String)"
        newMemberName = "renamed"
        declarationPath = STANDARD_DECLARATION
    }

    @Given("^the JDT binding analysis is clean with no parse or classpath warnings$")
    fun jdtBindingCleanNoWarnings() {
        val snap = requireNotNull(snapshot)
        val analysis = JdtJavaSemanticAnalyzer().analyze(snap)
        assertTrue(
            analysis.warnings.isEmpty(),
            "expected clean JDT binding, got warnings: ${analysis.warnings.map { it.message }}",
        )
    }

    @Given("^the signed member selector resolves to exactly one JDT method candidate with a JDT binding key$")
    fun signedSelectorResolvesToOneCandidate() {
        val snap = requireNotNull(snapshot)
        val analysis = JdtJavaSemanticAnalyzer().analyze(snap)
        val symbol = requireNotNull(symbolFqnWithMember)
        val owner = symbol.substringBefore('#')
        val selector = symbol.substringAfter('#')
        val candidates = analysis.symbols.filter { symbol ->
            symbol.ownerQualifiedName == owner &&
                symbol.memberSignature == selector &&
                symbol.kind == JdtJavaSemanticSymbolKind.METHOD
        }
        assertEquals(1, candidates.size, "expected exactly one JDT method candidate: ${analysis.symbols.filter { it.ownerQualifiedName == owner }.map { it.memberSignature }}")
        assertNotNull(candidates[0].bindingKey, "expected a JDT binding key on the candidate")
    }

    @Given("^the method is not part of an override family with more than one declaration$")
    fun methodNotInOverrideFamily() {
        val snap = requireNotNull(snapshot)
        val analysis = JdtJavaSemanticAnalyzer().analyze(snap)
        val owner = "com.example.UserService"
        val candidate = analysis.symbols.singleOrNull { symbol ->
            symbol.ownerQualifiedName == owner &&
                symbol.memberSignature == "validate(java.lang.String)" &&
                symbol.kind == JdtJavaSemanticSymbolKind.METHOD
        }
        assertNotNull(candidate, "expected the validate(java.lang.String) candidate")
        val key = requireNotNull(candidate.bindingKey)
        val family = analysis.overrideRelations.filter { relation ->
            relation.overridingBindingKey == key || relation.overriddenBindingKey == key
        }
        assertTrue(family.isEmpty(), "expected no override family, got ${family.size} relations")
    }

    // ------------------------------------------------------------ Scenario 2 Given

    @Given("^a Java workspace snapshot whose signed method is part of an override family with more than one declaration across the inheritance hierarchy$")
    fun workspaceWithOverrideFamily() {
        fixtureRoot = buildOverrideFixture()
        snapshot = JavaProjectScanner().scan(requireNotNull(fixtureRoot))
        assertTrue(requireNotNull(snapshot).buildModels.isNotEmpty(), "fixture must yield a real build model")
        symbolFqnWithMember = "com.example.Base#validate(java.lang.String)"
        newMemberName = "renamed"
        declarationPath = Path.of("src/main/java/com/example/Base.java")
    }

    @Given("^the JDT binding analysis is clean and resolves the override family$")
    fun jdtBindingCleanAndResolvesOverrideFamily() {
        val snap = requireNotNull(snapshot)
        val analysis = JdtJavaSemanticAnalyzer().analyze(snap)
        assertTrue(
            analysis.warnings.isEmpty(),
            "expected clean JDT binding, got warnings: ${analysis.warnings.map { it.message }}",
        )
        assertTrue(
            analysis.overrideRelations.isNotEmpty(),
            "expected an override relation in the fixture: ${analysis.overrideRelations}",
        )
    }

    // ------------------------------------------------------------ Scenario 3 Given

    @Given("^the Java adapter returns a REFUSED patch plan for a renameMember preview$")
    fun adapterReturnsRefusedRenameMemberPlan() {
        fixtureRoot = buildStandardFixture()
        snapshot = JavaProjectScanner().scan(requireNotNull(fixtureRoot))
        // A real refusal: a symbol with no hash separator. The plan is REFUSED and carries the real message.
        plan = preview(requireNotNull(snapshot), "com.example.UserService", "renamed")
        assertEquals(PatchStatus.REFUSED, requireNotNull(plan).status, requireNotNull(plan).summary)
    }

    // --------------------------------------------------------- Scenario 4 Given

    @Given("^a Java workspace snapshot for a renameMember preview under the condition (.+)$")
    fun workspaceUnderCondition(condition: String) {
        this.condition = condition.trim()
        fixtureRoot = buildFixture(this.condition ?: "default")
        snapshot = JavaProjectScanner().scan(requireNotNull(fixtureRoot))
    }

    // ------------------------------------------------------------ Scenario 1 When

    @When("^the caller requests a renameMember preview with a signed member selector that contains a left parenthesis and a valid new method name$")
    fun previewSignedRename() {
        plan = preview(requireNotNull(snapshot), requireNotNull(symbolFqnWithMember), requireNotNull(newMemberName))
    }

    // ------------------------------------------------------------ Scenario 2 When

    @When("^the caller requests a renameMember preview for that signed method selector$")
    fun previewForSignedMethodSelector() {
        plan = preview(requireNotNull(snapshot), requireNotNull(symbolFqnWithMember), requireNotNull(newMemberName))
    }

    // ------------------------------------------------------------ Scenario 3 When

    @When("^the caller inspects the refusal plan$")
    fun inspectRefusalPlan() {
        requireNotNull(plan)
    }

    // ------------------------------------------------------------ Scenario 4 When

    @When("^the caller requests a renameMember preview$")
    fun previewUnderCondition() {
        previewUnderCondition(requireNotNull(condition))
    }

    // ------------------------------------------------------------ Scenario 1 Thens

    @Then("^the adapter returns a PREVIEW patch plan for the operation renameMember$")
    fun adapterReturnsPreviewPlan() {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.PREVIEW, p.status, "${p.summary}")
        assertEquals("renameMember", p.operation, p.toString())
    }

    @Then("^the plan carries confidence 0.93$")
    fun planCarriesConfidence093() {
        assertEquals(0.93, requireNotNull(plan).confidence, requireNotNull(plan).toString())
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

    @Then("^the plan lists every declaration file and referencing file in its affected-file set$")
    fun planListsDeclarationAndReferencingFiles() {
        val p = requireNotNull(plan)
        val decl = requireNotNull(declarationPath)
        assertTrue(p.affectedFiles.contains(decl), "expected declaration $decl in affected files: ${p.affectedFiles}")
        val snap = requireNotNull(snapshot)
        val referencingFiles = snap.files.filter { it.path != decl && it.languageId == "java" }
        assertTrue(
            referencingFiles.all { p.affectedFiles.contains(it.path) },
            "expected every referencing file in affected files: ${p.affectedFiles}",
        )
    }

    @Then("^the plan carries a WorkspaceEdit whose edits are generated from exact JDT declaration and reference ranges$")
    fun planWorkspaceEditFromExactJdtRanges() {
        val p = requireNotNull(plan)
        val decl = requireNotNull(declarationPath)
        val modifies = p.workspaceEdit.edits.filterIsInstance<FileEdit.Modify>()
        assertTrue(modifies.isNotEmpty(), "expected JDT source-range modifications: ${p.workspaceEdit.edits}")
        assertTrue(
            modifies.any { it.path == decl },
            "expected a Modify on the declaration file: ${p.workspaceEdit.edits}",
        )
        val snap = requireNotNull(snapshot)
        val referencingFiles = snap.files.filter { it.path != decl && it.languageId == "java" }
        assertTrue(
            referencingFiles.all { ref -> modifies.any { it.path == ref.path } },
            "expected a Modify on every referencing file: ${p.workspaceEdit.edits}",
        )
        fun at(column: Int) = TextEdit(SourceRange(SourcePosition(1, column), SourcePosition(1, column + 8)), "renamed")
        val client = Path.of("src/main/java/com/example/Client.java")
        val expectedEdits = mapOf(STANDARD_DECLARATION to listOf(at(39)), client to listOf(at(50)))
        assertEquals(2, p.workspaceEdit.edits.size, "exactly the declaration and caller modifications")
        assertEquals(2, modifies.size, "no structural or unrelated edits")
        assertEquals(expectedEdits, modifies.associate { it.path to it.textEdits }, "independently authored declaration/call ranges and replacement text")
        assertEquals(expectedEdits.keys, p.affectedFiles, "exactly the two authored affected files")
        val expectedImage = snap.trackedFiles.associate { it.path to it.content } + mapOf(
            STANDARD_DECLARATION to "package com.example;\npublic class UserService { public void renamed(String s) {} }\n",
            client to "package com.example;\npublic class Client { void x(){ new UserService().renamed(\"a\"); } }\n",
        )
        val staged = WorkspaceEditSimulator.apply(snap, p.workspaceEdit)
        assertEquals(expectedImage, staged.trackedFiles.associate { it.path to it.content }, "complete independently authored post-image")
    }

    @Then("^the plan warns that the JDT binding selected the exact member signature and that edits were generated from JDT declaration and reference ranges$")
    fun planWarnsJdtBindingSelectedExactSignature() {
        val p = requireNotNull(plan)
        assertTrue(
            p.warnings.any { it.contains("JDT binding selected exact member signature") && it.contains("edits were generated from JDT declaration/reference ranges") },
            "expected the JDT-binding-selected warning: ${p.warnings}",
        )
    }

    @Then("^the plan warns that reflection, Spring event or listener names, Jackson property names, and annotation processor output are NOT updated and require manual review$")
    fun planWarnsReflectionAndAnnotationProcessorNotUpdated() {
        val p = requireNotNull(plan)
        assertTrue(
            p.warnings.any {
                it.contains("Reflection") &&
                    it.contains("Spring event/listener names") &&
                    it.contains("Jackson property names") &&
                    it.contains("annotation-processor output") &&
                    it.contains("NOT updated") &&
                    it.contains("Review manually")
            },
            "expected the reflection/annotation-processor warning: ${p.warnings}",
        )
    }

    // ------------------------------------------------------------ Scenario 2 Thens

    @Then("^the plan carries confidence 0.91$")
    fun planCarriesConfidence091() {
        assertEquals(0.91, requireNotNull(plan).confidence, requireNotNull(plan).toString())
    }

    @Then("^the plan warns that override aware propagation selected the family declarations and their binding matched call sites across the inheritance hierarchy$")
    fun planWarnsOverrideAwarePropagation() {
        val p = requireNotNull(plan)
        assertTrue(
            p.warnings.any { it.contains("Override-aware propagation selected") && it.contains("binding-matched call sites") },
            "expected the override-aware propagation warning: ${p.warnings}",
        )
    }

    @Then("^the plan summary reports the number of declarations and the number of affected files$")
    fun planSummaryReportsDeclarationsAndFiles() {
        val p = requireNotNull(plan)
        assertTrue(p.summary.contains("2 declaration(s)") && p.summary.contains("3 file(s) affected"), p.summary)
        val base = Path.of("src/main/java/com/example/Base.java")
        val child = Path.of("src/main/java/com/example/Child.java")
        val client = Path.of("src/main/java/com/example/Client.java")
        fun at(column: Int) = TextEdit(SourceRange(SourcePosition(1, column), SourcePosition(1, column + 8)), "renamed")
        val expected = mapOf(base to listOf(at(32)), child to listOf(at(46)), client to listOf(at(44)))
        val modifies = p.workspaceEdit.edits.filterIsInstance<FileEdit.Modify>()
        assertEquals(3, p.workspaceEdit.edits.size)
        assertEquals(3, modifies.size)
        assertEquals(expected, modifies.associate { it.path to it.textEdits })
        assertEquals(expected.keys, p.affectedFiles)
        val before = requireNotNull(snapshot)
        val expectedImage = before.trackedFiles.associate { it.path to it.content } + mapOf(
            base to "package com.example;\npublic class Base { public void renamed(String s) {} }\n",
            child to "package com.example;\npublic class Child extends Base { public void renamed(String s) {} }\n",
            client to "package com.example;\npublic class Client { void x(){ new Child().renamed(\"a\"); } }\n",
        )
        assertEquals(expectedImage, WorkspaceEditSimulator.apply(before, p.workspaceEdit).trackedFiles.associate { it.path to it.content })
    }

    // ------------------------------------------------------------ Scenario 3 Thens

    @Then("^the refusal operation name is deterministically renameMember$")
    fun refusalOperationName() {
        val p = requireNotNull(plan)
        assertEquals("renameMember", p.operation, p.toString())
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

    @Then("^the refusal carries no typed refusal code because refusals are message-based$")
    fun refusalCarriesNoTypedCode() {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.REFUSED, p.status, p.toString())
        assertTrue(p.refusalCode == null, "expected no typed refusal code: ${p.refusalCode}")
    }

    // ------------------------------------------------------------ Scenario 4 Thens

    @Then("^the adapter returns a REFUSED patch plan for the operation renameMember$")
    fun adapterReturnsRefusedRenameMember() {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.REFUSED, p.status, p.toString())
        assertEquals("renameMember", p.operation, p.toString())
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

    private fun preview(snap: ProjectSnapshot, symbolFqnWithMember: String, newMemberName: String): PatchPlan =
        planner.preview(snap, symbolFqnWithMember, newMemberName)

    private fun previewUnderCondition(condition: String) {
        val snap = requireNotNull(snapshot)
        when (condition) {
            "the caller supplies a symbol with no hash separator between the fully qualified owner and the member" ->
                plan = preview(snap, "com.example.UserService", "renamed")
            "the member selector is the constructor pseudo name init" ->
                plan = preview(snap, "com.example.UserService#<init>", "renamed")
            "the caller supplies a new method name that is not a valid Java identifier" ->
                plan = preview(snap, "com.example.UserService#validate", "1rename")
            "the caller supplies a new method name identical to the old method name" ->
                plan = preview(snap, "com.example.UserService#validate", "validate")
            "the owner fully qualified name is absent from the symbol index or is not a recognized type" ->
                plan = preview(snap, "com.example.Missing#validate", "renamed")
            "the owner declaration path is inside a generated-source or build-output location" ->
                plan = preview(snap, "com.example.Generated#validate", "renamed")
            "the owner declaration source declares a generated-code annotation" ->
                plan = preview(snap, "com.example.Generated#validate", "renamed")
            "the owner declaration source header identifies generated code" ->
                plan = preview(snap, "com.example.Generated#validate", "renamed")
            "no member with the old method name is declared in the owner type" ->
                plan = preview(snap, "com.example.UserService#validate", "renamed")
            "the JDT semantic analysis reports one parse or classpath warning" ->
                plan = preview(snap, "com.example.UserService#validate(java.lang.String)", "renamed")
            "the override family contains declarations outside the scanned source workspace" ->
                plan = preview(snap, "com.example.UserService#validate(java.lang.String)", "renamed")
            else -> error("Unknown condition: '$condition'")
        }
    }

    private fun buildFixture(condition: String): Path = when (condition) {
        "the caller supplies a symbol with no hash separator between the fully qualified owner and the member",
        "the member selector is the constructor pseudo name init",
        "the caller supplies a new method name that is not a valid Java identifier",
        "the caller supplies a new method name identical to the old method name",
        "the owner fully qualified name is absent from the symbol index or is not a recognized type",
        -> buildStandardFixture()
        "the owner declaration path is inside a generated-source or build-output location" ->
            buildGeneratedPathFixture()
        "the owner declaration source declares a generated-code annotation" ->
            buildGeneratedAnnotationFixture()
        "the owner declaration source header identifies generated code" ->
            buildGeneratedHeaderFixture()
        "no member with the old method name is declared in the owner type" ->
            buildNoMemberFixture()
        "the JDT semantic analysis reports one parse or classpath warning" ->
            buildJdtWarningFixture()
        "the override family contains declarations outside the scanned source workspace" ->
            buildOverrideOutsideFixture()
        else -> error("Unknown condition: '$condition'")
    }

    private fun buildStandardFixture(): Path {
        val root = temporaryDirectory("rk-jvm-renamemethod-standard")
        writePom(root, "src/main/java")
        write(
            root,
            "src/main/java/com/example/UserService.java",
            "package com.example;\npublic class UserService { public void validate(String s) {} }\n",
        )
        write(
            root,
            "src/main/java/com/example/Client.java",
            "package com.example;\npublic class Client { void x(){ new UserService().validate(\"a\"); } }\n",
        )
        return root
    }

    private fun buildNoMemberFixture(): Path {
        val root = temporaryDirectory("rk-jvm-renamemethod-nomember")
        writePom(root, "src/main/java")
        write(
            root,
            "src/main/java/com/example/UserService.java",
            "package com.example;\npublic class UserService { public void other(String s) {} }\n",
        )
        return root
    }

    private fun buildJdtWarningFixture(): Path {
        val root = temporaryDirectory("rk-jvm-renamemethod-jdt-warning")
        writePom(root, "src/main/java")
        write(
            root,
            "src/main/java/com/example/UserService.java",
            "package com.example;\npublic class UserService { public void validate(String s) { MissingDependency d; } }\n",
        )
        return root
    }

    private fun buildOverrideFixture(): Path {
        val root = temporaryDirectory("rk-jvm-renamemethod-override")
        writePom(root, "src/main/java")
        write(
            root,
            "src/main/java/com/example/Base.java",
            "package com.example;\npublic class Base { public void validate(String s) {} }\n",
        )
        write(
            root,
            "src/main/java/com/example/Child.java",
            "package com.example;\npublic class Child extends Base { public void validate(String s) {} }\n",
        )
        write(
            root,
            "src/main/java/com/example/Client.java",
            "package com.example;\npublic class Client { void x(){ new Child().validate(\"a\"); } }\n",
        )
        return root
    }

    private fun buildOverrideOutsideFixture(): Path {
        val root = temporaryDirectory("rk-jvm-renamemethod-override-outside")
        val libDir = root.resolve("lib")
        write(
            libDir,
            "com/example/lib/Base.java",
            "package com.example.lib;\npublic class Base { public void validate(String s) {} }\n",
        )
        val outDir = libDir.resolve("out")
        outDir.createDirectories()
        val javac = ProcessBuilder(
            jdkBin("javac"), "-d", outDir.toString(),
            libDir.resolve("com/example/lib/Base.java").toString(),
        ).redirectErrorStream(true).start()
        javac.waitFor()
        val jarFile = root.resolve("lib.jar")
        val jar = ProcessBuilder(
            jdkBin("jar"), "cf", jarFile.toString(), "-C", outDir.toString(), "com/example/lib/Base.class",
        ).redirectErrorStream(true).start()
        jar.waitFor()
        write(
            root,
            "pom.xml",
            """
            <project><modelVersion>4.0.0</modelVersion>
              <groupId>example</groupId><artifactId>rename</artifactId><version>1</version>
              <build><sourceDirectory>src/main/java</sourceDirectory></build>
              <dependencies><dependency>
                <groupId>com.example.lib</groupId><artifactId>lib</artifactId><version>1</version>
                <scope>system</scope><systemPath>$jarFile</systemPath>
              </dependency></dependencies>
            </project>
            """.trimIndent(),
        )
        write(
            root,
            "src/main/java/com/example/UserService.java",
            "package com.example;\nimport com.example.lib.Base;\npublic class UserService extends Base { public void validate(String s) {} }\n",
        )
        return root
    }

    private fun buildGeneratedPathFixture(): Path {
        val root = temporaryDirectory("rk-jvm-renamemethod-gen-path")
        writePom(root, ".")
        write(
            root,
            "build/generated/sources/annotationProcessor/java/main/Generated.java",
            "package com.example;\npublic class Generated { public void validate(String s) {} }\n",
        )
        return root
    }

    private fun buildGeneratedAnnotationFixture(): Path {
        val root = temporaryDirectory("rk-jvm-renamemethod-gen-annotation")
        writePom(root, ".")
        write(
            root,
            "Generated.java",
            "package com.example;\n@Generated\npublic class Generated { public void validate(String s) {} }\n",
        )
        return root
    }

    private fun buildGeneratedHeaderFixture(): Path {
        val root = temporaryDirectory("rk-jvm-renamemethod-gen-header")
        writePom(root, ".")
        write(
            root,
            "Generated.java",
            "// generated by a schema processor, do not edit\npackage com.example;\npublic class Generated { public void validate(String s) {} }\n",
        )
        return root
    }

    private fun jdkBin(tool: String): String =
        Path.of(System.getProperty("java.home"), "bin", tool).toAbsolutePath().normalize().toString()

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
        val report = reportDir.resolve("java-rename-method-characterization-messages.txt")
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
