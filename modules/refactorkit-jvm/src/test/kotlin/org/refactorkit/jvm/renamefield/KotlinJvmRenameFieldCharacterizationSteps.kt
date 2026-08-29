package org.refactorkit.jvm.renamefield

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
import org.refactorkit.java.JavaLanguageAdapter
import org.refactorkit.java.JavaLexer
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
 * Story BDD glue for features/java-rename-field-characterization.feature
 * (REQ-JAVA-RENAME-FIELD-CHAR-001, row C-RENAME-FIELD of the approved finite J1 Java catalogue).
 *
 * It drives the real toolchain: a Java workspace scanned by JavaProjectScanner into a real build
 * model, the real JavaLanguageAdapter, and the real JavaRenameMemberPlanner.preview. The feature
 * begins GREEN because production already implements the planner; the glue asserts the ACTUAL
 * behavior rather than inventing it.
 *
 * Fixtures:
 *  - The standard fixture uses a Maven jar module whose pom declares <sourceDirectory>src/main/java
 *    so real scanned SourceFile paths are the bare src/main/java/... names. UserService declares
 *    exactly one field amount, and Client.java references it (int a = u.amount) so the JDT owner-bound
 *    field path produces a declaration edit plus a binding-matched reference edit.
 *  - The lexical-fallback fixture adds an unresolved MissingDependency field so JDT reports a
 *    parse/classpath warning, which makes previewJdtFieldRename return null and drives the documented
 *    lexical fallback (confidence 0.88, LEXICAL_FALLBACK, LOW).
 *  - The multiple-members fixture declares a field amount AND a method amount (legal Java) plus the
 *    unresolved MissingDependency, so the lexical fallback reports multiple overloads and elevates
 *    risk to MEDIUM.
 *  - The generated-source fixtures use a pom declaring <sourceDirectory>.</sourceDirectory> so a
 *    root-level Generated.java is scanned as exactly "Generated.java" or the build/generated path,
 *    matching the declared generated-source messages.
 *
 * Every refusal condition is a truthful fixture that makes the real planner return the DECLARED
 * MESSAGE — no message is invented, no typed renameMember.xxx code is introduced, and no production
 * branch is weakened. Refusal Then steps assert REFUSED, the exact real message in summary and
 * warnings, an empty WorkspaceEdit, an empty affected-file set, no approval, no managed-write
 * eligibility, confidence 0.0, risk HIGH, and no typed refusal code.
 *
 * Unreachable refusal rows (pre-approved RED-deferral in the J1 catalogue baseline section 2) are
 * NOT asserted: "Owner declaration file not found" (the planner builds its symbol index from the
 * same snapshot.files it searches, so a found owner symbol's location.path is always present) and
 * "No occurrences of 'amount' found" (the owner declaration file is always in the reference scope
 * and contains the declaration occurrence, so the lexical path always finds at least one).
 */
class KotlinJvmRenameFieldCharacterizationSteps {
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

    @Given("^a Java workspace snapshot whose owner type declares exactly one field matching the requested member name$")
    fun workspaceWithSingleField() {
        fixtureRoot = buildStandardFixture()
        snapshot = JavaProjectScanner().scan(requireNotNull(fixtureRoot))
        assertTrue(requireNotNull(snapshot).buildModels.isNotEmpty(), "fixture must yield a real build model")
        symbolFqnWithMember = "com.example.UserService#amount"
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

    @Given("^the field selector resolves to exactly one JDT field candidate with a JDT binding key$")
    fun fieldSelectorResolvesToOneCandidate() {
        val snap = requireNotNull(snapshot)
        val analysis = JdtJavaSemanticAnalyzer().analyze(snap)
        val candidates = analysis.symbols.filter { symbol ->
            symbol.ownerQualifiedName == "com.example.UserService" &&
                symbol.simpleName == "amount" &&
                symbol.kind == JdtJavaSemanticSymbolKind.FIELD
        }
        assertEquals(
            1, candidates.size,
            "expected exactly one JDT field candidate: ${analysis.symbols.filter { it.ownerQualifiedName == "com.example.UserService" }.map { it.simpleName }}",
        )
        assertNotNull(candidates[0].bindingKey, "expected a JDT binding key on the candidate")
    }

    // ------------------------------------------------------------ Scenario 2 Given

    @Given("^the JDT binding analysis is unavailable or not clean for that field$")
    fun jdtBindingUncleanForField() {
        fixtureRoot = buildLexicalFixture()
        snapshot = JavaProjectScanner().scan(requireNotNull(fixtureRoot))
        assertTrue(requireNotNull(snapshot).buildModels.isNotEmpty(), "fixture must yield a real build model")
        val analysis = JdtJavaSemanticAnalyzer().analyze(requireNotNull(snapshot))
        assertTrue(
            analysis.warnings.isNotEmpty(),
            "expected not-clean JDT binding for the lexical fallback fixture",
        )
    }

    // ------------------------------------------------------------ Scenario 3 Given

    @Given("^a Java workspace snapshot whose owner type declares more than one member matching the requested member name$")
    fun workspaceWithMultipleMembers() {
        fixtureRoot = buildMultipleMembersFixture()
        snapshot = JavaProjectScanner().scan(requireNotNull(fixtureRoot))
        assertTrue(requireNotNull(snapshot).buildModels.isNotEmpty(), "fixture must yield a real build model")
        symbolFqnWithMember = "com.example.UserService#amount"
        newMemberName = "renamed"
        declarationPath = STANDARD_DECLARATION
    }

    @Given("^the JDT binding analysis is unavailable or not clean for that member$")
    fun jdtBindingUncleanForMember() {
        val analysis = JdtJavaSemanticAnalyzer().analyze(requireNotNull(snapshot))
        assertTrue(
            analysis.warnings.isNotEmpty(),
            "expected not-clean JDT binding for the multiple-members fixture",
        )
    }

    // ------------------------------------------------------------ Scenario 4 Given

    @Given("^the Java adapter returns a REFUSED patch plan for a renameMember preview$")
    fun adapterReturnsRefusedRenameMemberPlan() {
        fixtureRoot = buildStandardFixture()
        snapshot = JavaProjectScanner().scan(requireNotNull(fixtureRoot))
        // A real refusal: a symbol with no hash separator. The plan is REFUSED and carries the real message.
        plan = preview(requireNotNull(snapshot), "com.example.UserService", "renamed")
        assertEquals(PatchStatus.REFUSED, requireNotNull(plan).status, requireNotNull(plan).summary)
    }

    // --------------------------------------------------------- Scenario 5 Given

    @Given("^a Java workspace snapshot for a renameMember preview under the condition (.+)$")
    fun workspaceUnderCondition(condition: String) {
        this.condition = condition.trim()
        fixtureRoot = buildFixture(this.condition ?: "default")
        snapshot = JavaProjectScanner().scan(requireNotNull(fixtureRoot))
    }

    // ------------------------------------------------------------ Scenario 1/2 When

    @When("^the caller requests a renameMember preview for that owner-bound field with a valid new field name$")
    fun previewOwnerBoundField() {
        plan = preview(requireNotNull(snapshot), requireNotNull(symbolFqnWithMember), requireNotNull(newMemberName))
    }

    // ------------------------------------------------------------ Scenario 3 When

    @When("^the caller requests a renameMember preview for that member with a valid new name$")
    fun previewForMember() {
        plan = preview(requireNotNull(snapshot), requireNotNull(symbolFqnWithMember), requireNotNull(newMemberName))
    }

    // ------------------------------------------------------------ Scenario 4 When

    @When("^the caller inspects the refusal plan$")
    fun inspectRefusalPlan() {
        requireNotNull(plan)
    }

    // ------------------------------------------------------------ Scenario 5 When

    @When("^the caller requests a renameMember preview$")
    fun previewUnderCondition() {
        previewUnderCondition(requireNotNull(condition))
    }

    // ------------------------------------------------------------ PREVIEW Thens

    @Then("^the adapter returns a PREVIEW patch plan for the operation renameMember$")
    fun adapterReturnsPreviewPlan() {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.PREVIEW, p.status, "${p.summary}")
        assertEquals("renameMember", p.operation, p.toString())
    }

    @Then("^the plan carries confidence 0.95$")
    fun planCarriesConfidence095() {
        assertEquals(0.95, requireNotNull(plan).confidence, requireNotNull(plan).toString())
    }

    @Then("^the plan carries confidence 0.88$")
    fun planCarriesConfidence088() {
        assertEquals(0.88, requireNotNull(plan).confidence, requireNotNull(plan).toString())
    }

    @Then("^the plan requires user approval$")
    fun planRequiresUserApproval() {
        assertTrue(requireNotNull(plan).requiresUserApproval, "expected requiresUserApproval=true: ${requireNotNull(plan)}")
    }

    @Then("^the plan evidence is JDT_BINDING$")
    fun planEvidenceJdtBinding() {
        assertEquals(RefactoringEvidence.JDT_BINDING, requireNotNull(plan).evidence, requireNotNull(plan).toString())
    }

    @Then("^the plan evidence is LEXICAL_FALLBACK$")
    fun planEvidenceLexicalFallback() {
        assertEquals(RefactoringEvidence.LEXICAL_FALLBACK, requireNotNull(plan).evidence, requireNotNull(plan).toString())
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

    // ------------------------------------------------------------ Scenario 1 Thens

    @Then("^the plan lists the field declaration file and every binding-matched referencing file in its affected-file set$")
    fun planListsDeclarationAndBindingMatchedReferences() {
        val p = requireNotNull(plan)
        val decl = requireNotNull(declarationPath)
        assertTrue(p.affectedFiles.contains(decl), "expected declaration $decl in affected files: ${p.affectedFiles}")
        val snap = requireNotNull(snapshot)
        val analysis = JdtJavaSemanticAnalyzer().analyze(snap)
        val candidate = analysis.symbols.single { symbol ->
            symbol.ownerQualifiedName == "com.example.UserService" &&
                symbol.simpleName == "amount" &&
                symbol.kind == JdtJavaSemanticSymbolKind.FIELD
        }
        val key = requireNotNull(candidate.bindingKey)
        val matchedRefs = analysis.references.filter { it.bindingKey == key && it.symbolKind == JdtJavaSemanticSymbolKind.FIELD }
        assertTrue(
            matchedRefs.all { p.affectedFiles.contains(it.path) },
            "expected every binding-matched referencing file in affected files: ${p.affectedFiles}",
        )
    }

    @Then("^the plan carries a WorkspaceEdit whose edits are generated from exact JDT field declaration and reference ranges$")
    fun planWorkspaceEditFromExactJdtRanges() {
        val p = requireNotNull(plan)
        val decl = requireNotNull(declarationPath)
        val modifies = p.workspaceEdit.edits.filterIsInstance<FileEdit.Modify>()
        assertTrue(modifies.isNotEmpty(), "expected JDT source-range modifications: ${p.workspaceEdit.edits}")
        assertTrue(modifies.any { it.path == decl }, "expected a Modify on the declaration file: ${p.workspaceEdit.edits}")
        val snap = requireNotNull(snapshot)
        val analysis = JdtJavaSemanticAnalyzer().analyze(snap)
        val candidate = analysis.symbols.single { symbol ->
            symbol.ownerQualifiedName == "com.example.UserService" &&
                symbol.simpleName == "amount" &&
                symbol.kind == JdtJavaSemanticSymbolKind.FIELD
        }
        val key = requireNotNull(candidate.bindingKey)
        val matchedRefs = analysis.references.filter { it.bindingKey == key && it.symbolKind == JdtJavaSemanticSymbolKind.FIELD }
        assertTrue(
            matchedRefs.all { ref -> modifies.any { it.path == ref.path } },
            "expected a Modify on every binding-matched referencing file: ${p.workspaceEdit.edits}",
        )
    }

    @Then("^the plan warns that the JDT binding selected the exact field and that edits were generated from JDT declaration and reference ranges$")
    fun planWarnsJdtBindingSelectedExactField() {
        val p = requireNotNull(plan)
        assertTrue(
            p.warnings.any { it.contains("JDT binding selected exact field") && it.contains("edits were generated from JDT declaration/reference ranges") },
            "expected the JDT-binding-selected warning: ${p.warnings}",
        )
    }

    @Then("^the plan warns that reflection, serialization names, framework strings, and annotation-processor output are NOT updated and require manual review$")
    fun planWarnsReflectionSerializationFrameworkAnnotationNotUpdated() {
        val p = requireNotNull(plan)
        assertTrue(
            p.warnings.any {
                it.contains("Reflection") &&
                    it.contains("serialization names") &&
                    it.contains("framework strings") &&
                    it.contains("annotation-processor output") &&
                    it.contains("NOT updated") &&
                    it.contains("Review manually")
            },
            "expected the reflection/serialization/framework/annotation warning: ${p.warnings}",
        )
    }

    @Then("^the plan summary names the field, the owner type, and the number of affected files$")
    fun planSummaryNamesFieldOwnerTypeAndFiles() {
        val p = requireNotNull(plan)
        assertTrue(
            p.summary.contains("Rename field") &&
                p.summary.contains("amount") &&
                p.summary.contains("com.example.UserService") &&
                p.summary.contains("file(s) affected"),
            "expected field/owner-type/file-count in summary: ${p.summary}",
        )
    }

    // ------------------------------------------------------------ Scenario 2 Thens

    @Then("^the plan affected-file set is derived from JavaLexer occurrences of the old member name in scope of the owner type$")
    fun planAffectedSetFromJavaLexerOccurrences() {
        val p = requireNotNull(plan)
        val snap = requireNotNull(snapshot)
        val expected = snap.files
            .filter { it.languageId == "java" && JavaLexer.findOccurrences(it.content, "amount").isNotEmpty() }
            .map { it.path }
            .toSet()
        assertEquals(expected, p.affectedFiles, "expected affected-file set from JavaLexer occurrences: ${p.affectedFiles}")
    }

    @Then("^the plan warns that reflection, Spring event or listener names, Jackson property names, and annotation-processor output are NOT updated and require manual review$")
    fun planWarnsReflectionSpringJacksonAnnotationNotUpdated() {
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
            "expected the reflection/Spring/Jackson/annotation warning: ${p.warnings}",
        )
    }

    @Then("^the plan summary names the field kind, the owner type, and the number of affected files$")
    fun planSummaryNamesFieldKindOwnerTypeAndFiles() {
        val p = requireNotNull(plan)
        assertTrue(
            p.summary.contains("Rename field") &&
                p.summary.contains("amount") &&
                p.summary.contains("com.example.UserService") &&
                p.summary.contains("file(s) affected"),
            "expected field-kind/owner-type/file-count in summary: ${p.summary}",
        )
    }

    // ------------------------------------------------------------ Scenario 3 Thens

    @Then("^the plan warns that multiple overloads of the old member name were detected and that all overloads will be renamed$")
    fun planWarnsMultipleOverloadsDetected() {
        val p = requireNotNull(plan)
        assertTrue(
            p.warnings.any { it.contains("Multiple overloads of 'amount' detected") && it.contains("All overloads will be renamed") },
            "expected the multiple-overloads warning: ${p.warnings}",
        )
    }

    // ------------------------------------------------------------ Scenario 4 Thens

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

    // ------------------------------------------------------------ Scenario 5 Thens

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
            "the caller supplies a new member name that is not a valid Java identifier" ->
                plan = preview(snap, "com.example.UserService#amount", "1rename")
            "the caller supplies a new member name identical to the old member name" ->
                plan = preview(snap, "com.example.UserService#amount", "amount")
            "the owner fully qualified name is absent from the symbol index or is not a recognized type" ->
                plan = preview(snap, "com.example.Missing#amount", "renamed")
            "the owner declaration path is inside a generated-source or build-output location" ->
                plan = preview(snap, "com.example.Generated#amount", "renamed")
            "the owner declaration source declares a generated-code annotation" ->
                plan = preview(snap, "com.example.Generated#amount", "renamed")
            "the owner declaration source header identifies generated code" ->
                plan = preview(snap, "com.example.Generated#amount", "renamed")
            "no member with the old member name is declared in the owner type" ->
                plan = preview(snap, "com.example.UserService#amount", "renamed")
            "the owner type already declares a field with the new member name" ->
                plan = preview(snap, "com.example.UserService#amount", "total")
            else -> error("Unknown condition: '$condition'")
        }
    }

    private fun buildFixture(condition: String): Path = when (condition) {
        "the caller supplies a symbol with no hash separator between the fully qualified owner and the member",
        "the member selector is the constructor pseudo name init",
        "the caller supplies a new member name that is not a valid Java identifier",
        "the caller supplies a new member name identical to the old member name",
        "the owner fully qualified name is absent from the symbol index or is not a recognized type",
        -> buildStandardFixture()
        "the owner declaration path is inside a generated-source or build-output location" ->
            buildGeneratedPathFixture()
        "the owner declaration source declares a generated-code annotation" ->
            buildGeneratedAnnotationFixture()
        "the owner declaration source header identifies generated code" ->
            buildGeneratedHeaderFixture()
        "no member with the old member name is declared in the owner type" ->
            buildNoMemberFixture()
        "the owner type already declares a field with the new member name" ->
            buildFieldTargetExistsFixture()
        else -> error("Unknown condition: '$condition'")
    }

    private fun buildStandardFixture(): Path {
        val root = temporaryDirectory("rk-jvm-renamefield-standard")
        writePom(root, "src/main/java")
        write(
            root,
            "src/main/java/com/example/UserService.java",
            "package com.example;\npublic class UserService { int amount; }\n",
        )
        write(
            root,
            "src/main/java/com/example/Client.java",
            "package com.example;\npublic class Client { void x(){ UserService u = new UserService(); int a = u.amount; } }\n",
        )
        return root
    }

    private fun buildLexicalFixture(): Path {
        val root = temporaryDirectory("rk-jvm-renamefield-lexical")
        writePom(root, "src/main/java")
        write(
            root,
            "src/main/java/com/example/UserService.java",
            "package com.example;\npublic class UserService { int amount; MissingDependency d; }\n",
        )
        write(
            root,
            "src/main/java/com/example/Client.java",
            "package com.example;\npublic class Client { void x(){ UserService u = new UserService(); int a = u.amount; } }\n",
        )
        return root
    }

    private fun buildMultipleMembersFixture(): Path {
        val root = temporaryDirectory("rk-jvm-renamefield-multiple")
        writePom(root, "src/main/java")
        write(
            root,
            "src/main/java/com/example/UserService.java",
            "package com.example;\npublic class UserService {\n    int amount;\n    void amount() {}\n    MissingDependency d;\n}\n",
        )
        return root
    }

    private fun buildNoMemberFixture(): Path {
        val root = temporaryDirectory("rk-jvm-renamefield-nomember")
        writePom(root, "src/main/java")
        write(
            root,
            "src/main/java/com/example/UserService.java",
            "package com.example;\npublic class UserService { public void other() {} }\n",
        )
        return root
    }

    private fun buildFieldTargetExistsFixture(): Path {
        val root = temporaryDirectory("rk-jvm-renamefield-target")
        writePom(root, "src/main/java")
        write(
            root,
            "src/main/java/com/example/UserService.java",
            "package com.example;\npublic class UserService { int amount; int total; }\n",
        )
        return root
    }

    private fun buildGeneratedPathFixture(): Path {
        val root = temporaryDirectory("rk-jvm-renamefield-gen-path")
        writePom(root, ".")
        write(
            root,
            "build/generated/sources/annotationProcessor/java/main/Generated.java",
            "package com.example;\npublic class Generated { int amount; }\n",
        )
        return root
    }

    private fun buildGeneratedAnnotationFixture(): Path {
        val root = temporaryDirectory("rk-jvm-renamefield-gen-annotation")
        writePom(root, ".")
        write(
            root,
            "Generated.java",
            "@Generated\npackage com.example;\npublic class Generated { int amount; }\n",
        )
        return root
    }

    private fun buildGeneratedHeaderFixture(): Path {
        val root = temporaryDirectory("rk-jvm-renamefield-gen-header")
        writePom(root, ".")
        write(
            root,
            "Generated.java",
            "// generated by a schema processor, do not edit\npackage com.example;\npublic class Generated { int amount; }\n",
        )
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
        val report = reportDir.resolve("java-rename-field-characterization-messages.txt")
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
