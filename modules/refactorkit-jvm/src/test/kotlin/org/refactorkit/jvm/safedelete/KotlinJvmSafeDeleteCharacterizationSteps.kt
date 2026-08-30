package org.refactorkit.jvm.safedelete

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
import org.refactorkit.java.JavaPackageUtil
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.java.JavaSafeDeletePlanner
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
 * Story BDD glue for features/java-safe-delete-characterization.feature
 * (REQ-JAVA-SAFE-DELETE-CHAR-001, row C-DELETE of the approved finite J1 Java catalogue).
 *
 * It drives the real toolchain: a Java workspace scanned by JavaProjectScanner into a real build
 * model, the real JavaLanguageAdapter, and the real JavaSafeDeletePlanner.preview. The feature
 * begins GREEN because production already implements the planner; the glue asserts the ACTUAL
 * behavior rather than inventing it.
 *
 * Fixtures:
 *  - The standard fixture uses a Maven jar module whose pom declares <sourceDirectory>src/main/java
 *    so real scanned SourceFile paths are the bare src/main/java/... names. Legacy is a recognized
 *    non-generated class with no references and a clean JDT binding.
 *  - The referenced fixture adds Client.java referencing Legacy, so JDT reports exact type-binding
 *    references and the planner refuses without force or force-deletes with a HIGH-risk warning.
 *  - The lexical-fallback fixture adds an unresolved MissingDependency field so JDT reports a
 *    parse/classpath warning, which makes preview fall back to lexical evidence (LEXICAL_FALLBACK).
 *  - The framework fixture carries @Component / @Entity / @JsonProperty on the delete declaration,
 *    elevating risk to HIGH with framework warnings.
 *  - The generated-source fixtures use a pom declaring <sourceDirectory>.</sourceDirectory> so a
 *    root-level Generated.java is scanned as exactly "Generated.java" or the build/generated path,
 *    matching the declared generated-source messages.
 *
 * Every refusal condition is a truthful fixture that makes the real planner return the DECLARED
 * MESSAGE — no message is invented, no typed safeDelete.xxx code is introduced, and no production
 * branch is weakened. Refusal Then steps assert REFUSED, the exact real message in summary and
 * warnings, an empty WorkspaceEdit, an empty affected-file set, no approval, no managed-write
 * eligibility, confidence 0.0, risk HIGH, and no typed refusal code.
 *
 * Unreachable refusal row (pre-approved RED-deferral in the J1 catalogue baseline section 2) is
 * NOT asserted: "Declaration file not found" (the planner builds its symbol index from the same
 * snapshot.files it searches, so a found symbol's location.path is always present).
 */
class KotlinJvmSafeDeleteCharacterizationSteps {
    private val temporaryDirectories = mutableListOf<Path>()
    private val observedRefusals = mutableListOf<ObservedRefusal>()

    private var fixtureRoot: Path? = null
    private var snapshot: ProjectSnapshot? = null
    private var plan: PatchPlan? = null
    private var condition: String? = null
    private var framework: String? = null

    private var symbolFqn: String? = null
    private var declarationPath: Path? = null

    private val adapter = JavaLanguageAdapter()
    private val planner = JavaSafeDeletePlanner(adapter)

    private val STANDARD_DECLARATION = Path.of("src/main/java/com/example/Legacy.java")

    // ------------------------------------------------------------ Shared Given

    @Given("^a Java workspace snapshot that contains a recognized non-generated Java type declaration file$")
    fun workspaceWithRecognizedType() {
        fixtureRoot = buildStandardFixture()
        snapshot = JavaProjectScanner().scan(requireNotNull(fixtureRoot))
        assertTrue(requireNotNull(snapshot).buildModels.isNotEmpty(), "fixture must yield a real build model")
        symbolFqn = "com.example.Legacy"
        declarationPath = STANDARD_DECLARATION
    }

    // ------------------------------------------------------------ Scenario 1 Given

    @Given("^the JDT binding analysis is clean with no parse or classpath warnings$")
    fun jdtBindingCleanNoWarnings() {
        val analysis = JdtJavaSemanticAnalyzer().analyze(requireNotNull(snapshot))
        assertTrue(
            analysis.warnings.isEmpty(),
            "expected clean JDT binding, got warnings: ${analysis.warnings.map { it.message }}",
        )
    }

    @Given("^the type binding resolves to exactly one JDT deleteable type candidate with a binding key$")
    fun typeBindingResolvesToOneCandidate() {
        val analysis = JdtJavaSemanticAnalyzer().analyze(requireNotNull(snapshot))
        val candidates = analysis.symbols.filter { symbol ->
            symbol.qualifiedName == requireNotNull(symbolFqn) && symbol.kind in JDT_DELETEABLE_KINDS
        }
        assertEquals(
            1, candidates.size,
            "expected exactly one JDT deleteable candidate: ${analysis.symbols.map { it.qualifiedName }}",
        )
        assertNotNull(candidates[0].bindingKey, "expected a JDT binding key on the candidate")
    }

    @Given("^the type has no references anywhere in the project$")
    fun typeHasNoReferences() {
        val snap = requireNotNull(snapshot)
        val fqn = requireNotNull(symbolFqn)
        val decl = requireNotNull(declarationPath)
        val simpleName = JavaPackageUtil.simpleName(fqn)
        val referencing = snap.files.filter { it.path != decl && it.languageId == "java" }.filter { file ->
            file.content.contains(fqn) || JavaLexer.findOccurrences(file.content, simpleName).isNotEmpty()
        }
        assertTrue(
            referencing.isEmpty(),
            "expected no references to $fqn, found files: ${referencing.map { it.path }}",
        )
    }

    // ------------------------------------------------------------ Scenario 2 Given

    @Given("^the JDT binding analysis is clean and resolves the type binding$")
    fun jdtBindingCleanAndResolves() {
        val analysis = JdtJavaSemanticAnalyzer().analyze(requireNotNull(snapshot))
        assertTrue(
            analysis.warnings.isEmpty(),
            "expected clean JDT binding, got warnings: ${analysis.warnings.map { it.message }}",
        )
        val candidates = analysis.symbols.filter { symbol ->
            symbol.qualifiedName == requireNotNull(symbolFqn) && symbol.kind in JDT_DELETEABLE_KINDS
        }
        assertEquals(1, candidates.size, "expected exactly one deleteable type binding")
        assertNotNull(candidates[0].bindingKey, "expected a JDT binding key")
    }

    @Given("^the type has at least one reference in the project$")
    fun typeHasAtLeastOneReference() {
        fixtureRoot = buildReferencedFixture()
        snapshot = JavaProjectScanner().scan(requireNotNull(fixtureRoot))
        assertTrue(requireNotNull(snapshot).buildModels.isNotEmpty(), "fixture must yield a real build model")
        assertTrue(
            jdtReferenceCount(requireNotNull(snapshot), requireNotNull(symbolFqn), requireNotNull(declarationPath)) >= 1,
            "expected at least one JDT reference to ${requireNotNull(symbolFqn)}",
        )
    }

    // ------------------------------------------------------------ Scenario 3 Given

    @Given("^the JDT binding analysis reports an error warning or no usable binding for the type$")
    fun jdtBindingReportsErrorWarning() {
        fixtureRoot = buildLexicalFixture()
        snapshot = JavaProjectScanner().scan(requireNotNull(fixtureRoot))
        assertTrue(requireNotNull(snapshot).buildModels.isNotEmpty(), "fixture must yield a real build model")
        val analysis = JdtJavaSemanticAnalyzer().analyze(requireNotNull(snapshot))
        assertTrue(
            analysis.warnings.isNotEmpty(),
            "expected not-clean JDT binding for the lexical fallback fixture",
        )
    }

    // ------------------------------------------------------------ Scenario 4 Given

    @Given("^a Java workspace snapshot whose delete declaration file carries a (.+) annotation$")
    fun workspaceWithFrameworkAnnotation(framework: String) {
        this.framework = framework.trim()
        fixtureRoot = buildFrameworkFixture(requireNotNull(this.framework))
        snapshot = JavaProjectScanner().scan(requireNotNull(fixtureRoot))
        assertTrue(requireNotNull(snapshot).buildModels.isNotEmpty(), "fixture must yield a real build model")
        symbolFqn = "com.example.Legacy"
        declarationPath = STANDARD_DECLARATION
    }

    // ------------------------------------------------------------ Scenario 5 Given

    @Given("^the Java adapter returns a REFUSED patch plan for a safeDelete preview$")
    fun adapterReturnsRefusedSafeDeletePlan() {
        fixtureRoot = buildStandardFixture()
        snapshot = JavaProjectScanner().scan(requireNotNull(fixtureRoot))
        // A real refusal: a symbol absent from the symbol index. The plan is REFUSED and carries the real message.
        plan = planner.preview(requireNotNull(snapshot), "com.example.Missing")
        assertEquals(PatchStatus.REFUSED, requireNotNull(plan).status, requireNotNull(plan).summary)
    }

    // ------------------------------------------------------------ Scenario 6/7 Given

    @Given("^the type has one or more references in the project$")
    fun typeHasOneOrMoreReferences() {
        fixtureRoot = buildReferencedFixture()
        snapshot = JavaProjectScanner().scan(requireNotNull(fixtureRoot))
        assertTrue(requireNotNull(snapshot).buildModels.isNotEmpty(), "fixture must yield a real build model")
        assertTrue(
            jdtReferenceCount(requireNotNull(snapshot), requireNotNull(symbolFqn), requireNotNull(declarationPath)) >= 1,
            "expected at least one JDT reference to ${requireNotNull(symbolFqn)}",
        )
    }

    @Given("^the type has more than twenty references in the project$")
    fun typeHasMoreThanTwentyReferences() {
        fixtureRoot = buildManyReferencesFixture()
        snapshot = JavaProjectScanner().scan(requireNotNull(fixtureRoot))
        assertTrue(requireNotNull(snapshot).buildModels.isNotEmpty(), "fixture must yield a real build model")
        assertTrue(
            jdtReferenceCount(requireNotNull(snapshot), requireNotNull(symbolFqn), requireNotNull(declarationPath)) > 20,
            "expected more than twenty JDT references to ${requireNotNull(symbolFqn)}",
        )
    }

    // ------------------------------------------------------------ Scenario 8 Given

    @Given("^a Java workspace snapshot for a safeDelete preview under the condition (.+)$")
    fun workspaceUnderCondition(condition: String) {
        this.condition = condition.trim()
        fixtureRoot = buildFixture(requireNotNull(this.condition))
        snapshot = JavaProjectScanner().scan(requireNotNull(fixtureRoot))
    }

    // ------------------------------------------------------------ WHEN steps

    @When("^the caller requests a safeDelete preview for that fully qualified type name without force$")
    fun previewWithoutForce() {
        plan = planner.preview(requireNotNull(snapshot), requireNotNull(symbolFqn))
    }

    @When("^the caller requests a safeDelete preview for that fully qualified type name with force allowed$")
    fun previewWithForce() {
        plan = planner.preview(requireNotNull(snapshot), requireNotNull(symbolFqn), force = true)
    }

    @When("^the caller inspects the refusal plan$")
    fun inspectRefusalPlan() {
        requireNotNull(plan)
    }

    @When("^the caller requests a safeDelete preview$")
    fun previewUnderCondition() {
        previewUnderCondition(requireNotNull(condition))
    }

    // ------------------------------------------------------------ PREVIEW Thens

    @Then("^the adapter returns a PREVIEW patch plan for the operation safeDelete$")
    fun adapterReturnsPreviewPlan() {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.PREVIEW, p.status, "${p.summary}")
        assertEquals("safeDelete", p.operation, p.toString())
    }

    @Then("^the plan carries confidence 1.0$")
    fun planCarriesConfidence10() {
        assertEquals(1.0, requireNotNull(plan).confidence, requireNotNull(plan).toString())
    }

    @Then("^the plan carries confidence 0.3$")
    fun planCarriesConfidence03() {
        assertEquals(0.3, requireNotNull(plan).confidence, requireNotNull(plan).toString())
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

    @Then("^the plan evidence is JDT_BINDING when the JDT binding is clean, or LEXICAL_FALLBACK otherwise$")
    fun planEvidenceJdtOrLexical() {
        val p = requireNotNull(plan)
        assertTrue(
            p.evidence == RefactoringEvidence.JDT_BINDING || p.evidence == RefactoringEvidence.LEXICAL_FALLBACK,
            "expected JDT_BINDING or LEXICAL_FALLBACK evidence: ${p.evidence}",
        )
    }

    @Then("^the plan risk is LOW$")
    fun planRiskLow() {
        assertEquals(RiskLevel.LOW, requireNotNull(plan).riskLevel, requireNotNull(plan).toString())
    }

    @Then("^the plan risk is HIGH$")
    fun planRiskHigh() {
        assertEquals(RiskLevel.HIGH, requireNotNull(plan).riskLevel, requireNotNull(plan).toString())
    }

    @Then("^the plan affected-file set lists only the declaration file$")
    fun planAffectedSetOnlyDeclaration() {
        val p = requireNotNull(plan)
        assertEquals(setOf(requireNotNull(declarationPath)), p.affectedFiles, "expected only the declaration file: ${p.affectedFiles}")
    }

    @Then("^the plan carries a WorkspaceEdit with exactly one FileEdit.Delete for the declaration file$")
    fun planWorkspaceEditSingleDelete() {
        val p = requireNotNull(plan)
        val decl = requireNotNull(declarationPath)
        assertEquals(1, p.workspaceEdit.edits.size, "expected exactly one FileEdit: ${p.workspaceEdit.edits}")
        val edit = p.workspaceEdit.edits[0]
        assertTrue(edit is FileEdit.Delete, "expected a FileEdit.Delete: ${edit}")
        assertEquals(decl, edit.path, "expected the Delete on the declaration file")
    }

    // ------------------------------------------------------------ Scenario 1 Thens

    @Then("^the plan reports the adapter diagnostics computed after the declaration file is removed from the snapshot$")
    fun planReportsDiagnosticsAfterDeclarationRemoved() {
        val p = requireNotNull(plan)
        val snap = requireNotNull(snapshot)
        val decl = requireNotNull(declarationPath)
        val expected = adapter.diagnostics(snap.copy(files = snap.files.filterNot { it.path == decl }))
        assertEquals(expected, p.diagnosticsAfterPreview, "expected diagnostics computed after declaration removal: ${p.diagnosticsAfterPreview}")
    }

    @Then("^the plan warns that Java source references were evaluated using exact JDT type-binding evidence$")
    fun planWarnsJdtTypeBindingEvidence() {
        val p = requireNotNull(plan)
        assertTrue(
            p.warnings.any { it.contains("Java source references were evaluated using exact JDT type-binding evidence") },
            "expected the JDT type-binding warning: ${p.warnings}",
        )
    }

    @Then("^the plan warns that build configuration \\(pom.xml, build.gradle\\) is not scanned for references$")
    fun planWarnsBuildConfigNotScanned() {
        val p = requireNotNull(plan)
        assertTrue(
            p.warnings.any {
                it.contains("Build configuration") && it.contains("pom.xml") &&
                    it.contains("build.gradle") && it.contains("not scanned")
            },
            "expected the build-config-not-scanned warning: ${p.warnings}",
        )
    }

    @Then("^the plan summary names the fully qualified type and the declaration file path$")
    fun planSummaryNamesFqnAndDeclaration() {
        val p = requireNotNull(plan)
        assertTrue(
            p.summary.contains(requireNotNull(symbolFqn)) && p.summary.contains(requireNotNull(declarationPath).toString()),
            "expected FQN and declaration path in summary: ${p.summary}",
        )
    }

    // ------------------------------------------------------------ Scenario 2 Thens

    @Then("^the plan warns that the delete is forced and that the found references were ignored and the build will break$")
    fun planWarnsForcedDelete() {
        val p = requireNotNull(plan)
        assertTrue(
            p.warnings.any {
                it.contains("Forced delete") && it.contains("reference(s) were found and ignored") &&
                    it.contains("break the build")
            },
            "expected the forced-delete warning: ${p.warnings}",
        )
    }

    // ------------------------------------------------------------ Scenario 3 Thens

    @Then("^the plan warns that JDT type-binding evidence was unavailable or not clean and that Java source references use lexical fallback and need careful review$")
    fun planWarnsLexicalFallback() {
        val p = requireNotNull(plan)
        assertTrue(
            p.warnings.any {
                it.contains("JDT type-binding evidence was unavailable or not clean") &&
                    it.contains("lexical fallback") && it.contains("Review carefully")
            },
            "expected the lexical-fallback warning: ${p.warnings}",
        )
    }

    // ------------------------------------------------------------ Scenario 4 Thens

    @Then("^the plan carries a warning that names the (.+) framework and the detected annotation$")
    fun planWarnsFramework(framework: String) {
        val p = requireNotNull(plan)
        val enumName = frameworkEnumName(framework.trim())
        val annotation = frameworkAnnotation(framework.trim())
        assertTrue(
            p.warnings.any { it.contains(enumName) && it.contains(annotation) },
            "expected a warning naming $enumName and $annotation: ${p.warnings}",
        )
    }

    @Then("^the plan warns about the framework annotation locations$")
    fun planWarnsFrameworkLocations() {
        val p = requireNotNull(plan)
        assertTrue(
            p.warnings.any { it.contains("Framework annotation locations:") },
            "expected the framework annotation locations warning: ${p.warnings}",
        )
    }

    // ------------------------------------------------------------ Scenario 5 Thens

    @Then("^the refusal operation name is deterministically safeDelete$")
    fun refusalOperationName() {
        val p = requireNotNull(plan)
        assertEquals("safeDelete", p.operation, p.toString())
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

    // ------------------------------------------------------------ Scenario 6/7/8 Thens

    @Then("^the adapter returns a REFUSED patch plan for the operation safeDelete$")
    fun adapterReturnsRefusedSafeDelete() {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.REFUSED, p.status, p.toString())
        assertEquals("safeDelete", p.operation, p.toString())
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

    @Then("^the refusal summary and warning carry a message that begins with Cannot delete and the fully qualified type name and that reports the reference count and the evidence kind$")
    fun refusalSummaryBeginsCannotDelete() {
        val p = requireNotNull(plan)
        val fqn = requireNotNull(symbolFqn)
        assertTrue(
            p.summary.startsWith("Cannot delete $fqn"),
            "expected message to begin with 'Cannot delete $fqn': ${p.summary}",
        )
        assertTrue(
            p.summary.contains("reference(s) found using JDT binding evidence"),
            "expected reference count and evidence kind in message: ${p.summary}",
        )
    }

    @Then("^the refusal message lists each reference as a path and line, up to the first twenty references$")
    fun refusalMessageListsReferencePathsAndLines() {
        val p = requireNotNull(plan)
        val lines = referenceLines(p.summary)
        assertTrue(lines.isNotEmpty(), "expected at least one reference line in the message: ${p.summary}")
        assertTrue(
            lines.all { it.matches(Regex("^\\s{2}.+:\\d+$")) },
            "expected each reference line to be '  <path>:<line>': ${lines}",
        )
    }

    @Then("^the refusal message lists the first twenty references$")
    fun refusalMessageListsFirstTwenty() {
        val p = requireNotNull(plan)
        assertEquals(20, referenceLines(p.summary).size, "expected exactly twenty listed references: ${p.summary}")
    }

    @Then("^the refusal message appends a line that reports the additional references beyond the first twenty$")
    fun refusalMessageAppendsMoreSuffix() {
        val p = requireNotNull(plan)
        val snap = requireNotNull(snapshot)
        val fqn = requireNotNull(symbolFqn)
        val decl = requireNotNull(declarationPath)
        val count = jdtReferenceCount(snap, fqn, decl)
        assertTrue(count > 20, "expected more than twenty references, got $count")
        assertTrue(
            p.summary.contains("... and ${count - 20} more"),
            "expected '... and ${count - 20} more' suffix: ${p.summary}",
        )
    }

    @Then("^the refusal message carries the phrase Use --force to delete anyway \\(dangerous\\).$")
    fun refusalMessageCarriesForcePhrase() {
        val p = requireNotNull(plan)
        assertTrue(
            p.summary.contains("Use --force to delete anyway (dangerous)."),
            "expected the force instruction phrase: ${p.summary}",
        )
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

    // ------------------------------------------------------------------ helpers

    private fun previewUnderCondition(condition: String) {
        val snap = requireNotNull(snapshot)
        when (condition) {
            "the fully qualified type name is absent from the symbol index or is not a deleteable type" ->
                plan = planner.preview(snap, "com.example.Missing")
            "the declaration path is inside a generated-source or build-output location",
            "the declaration source declares a generated-code annotation",
            "the declaration source header identifies generated code",
            -> plan = planner.preview(snap, "com.example.Generated")
            else -> error("Unknown condition: '$condition'")
        }
    }

    private fun buildFixture(condition: String): Path = when (condition) {
        "the fully qualified type name is absent from the symbol index or is not a deleteable type" ->
            buildStandardFixture()
        "the declaration path is inside a generated-source or build-output location" ->
            buildGeneratedPathFixture()
        "the declaration source declares a generated-code annotation" ->
            buildGeneratedAnnotationFixture()
        "the declaration source header identifies generated code" ->
            buildGeneratedHeaderFixture()
        else -> error("Unknown condition: '$condition'")
    }

    private fun buildStandardFixture(): Path {
        val root = temporaryDirectory("rk-jvm-safedelete-standard")
        writePom(root, "src/main/java")
        write(
            root,
            "src/main/java/com/example/Legacy.java",
            "package com.example;\npublic class Legacy {}\n",
        )
        return root
    }

    private fun buildReferencedFixture(): Path {
        val root = temporaryDirectory("rk-jvm-safedelete-referenced")
        writePom(root, "src/main/java")
        write(
            root,
            "src/main/java/com/example/Legacy.java",
            "package com.example;\npublic class Legacy {}\n",
        )
        write(
            root,
            "src/main/java/com/example/Client.java",
            "package com.example;\npublic class Client { Legacy legacy = new Legacy(); }\n",
        )
        return root
    }

    private fun buildLexicalFixture(): Path {
        val root = temporaryDirectory("rk-jvm-safedelete-lexical")
        writePom(root, "src/main/java")
        write(
            root,
            "src/main/java/com/example/Legacy.java",
            "package com.example;\npublic class Legacy {}\n",
        )
        write(
            root,
            "src/main/java/com/example/NeedsDependency.java",
            "package com.example;\npublic class NeedsDependency { private MissingDependency dependency; }\n",
        )
        return root
    }

    private fun buildFrameworkFixture(framework: String): Path {
        val root = temporaryDirectory("rk-jvm-safedelete-framework")
        writePom(root, "src/main/java")
        val annotation = frameworkAnnotation(framework)
        write(
            root,
            "src/main/java/com/example/Legacy.java",
            "package com.example;\n$annotation\npublic class Legacy {}\n",
        )
        return root
    }

    private fun buildManyReferencesFixture(): Path {
        val root = temporaryDirectory("rk-jvm-safedelete-manyrefs")
        writePom(root, "src/main/java")
        write(
            root,
            "src/main/java/com/example/Legacy.java",
            "package com.example;\npublic class Legacy {}\n",
        )
        val fields = (1..21).joinToString("\n") { "    Legacy l$it = new Legacy();" }
        write(
            root,
            "src/main/java/com/example/Client.java",
            "package com.example;\npublic class Client {\n$fields\n}\n",
        )
        return root
    }

    private fun buildGeneratedPathFixture(): Path {
        val root = temporaryDirectory("rk-jvm-safedelete-gen-path")
        writePom(root, ".")
        write(
            root,
            "build/generated/sources/annotationProcessor/java/main/Generated.java",
            "package com.example;\npublic class Generated {}\n",
        )
        return root
    }

    private fun buildGeneratedAnnotationFixture(): Path {
        val root = temporaryDirectory("rk-jvm-safedelete-gen-annotation")
        writePom(root, ".")
        write(
            root,
            "Generated.java",
            "@Generated\npackage com.example;\npublic class Generated {}\n",
        )
        return root
    }

    private fun buildGeneratedHeaderFixture(): Path {
        val root = temporaryDirectory("rk-jvm-safedelete-gen-header")
        writePom(root, ".")
        write(
            root,
            "Generated.java",
            "// generated by a schema processor, do not edit\npackage com.example;\npublic class Generated {}\n",
        )
        return root
    }

    private fun jdtReferenceCount(snap: ProjectSnapshot, symbolFqn: String, declarationPath: Path): Int {
        val analysis = JdtJavaSemanticAnalyzer().analyze(snap)
        val target = analysis.symbols.single { symbol ->
            symbol.qualifiedName == symbolFqn && symbol.kind in JDT_DELETEABLE_KINDS
        }
        val key = requireNotNull(target.bindingKey)
        return analysis.references
            .filter { it.bindingKey == key && it.path != declarationPath }
            .distinctBy {
                "${it.path}:${it.sourceRange.start.line}:${it.sourceRange.start.character}:${it.sourceRange.end.character}"
            }
            .size
    }

    private fun referenceLines(summary: String): List<String> =
        summary.lines().filter { it.startsWith("  ") && !it.startsWith("  ...") }

    private fun frameworkEnumName(framework: String): String = when (framework) {
        "Spring" -> "SPRING"
        "JPA" -> "JPA"
        "Jackson" -> "JACKSON"
        else -> error("Unknown framework: '$framework'")
    }

    private fun frameworkAnnotation(framework: String): String = when (framework) {
        "Spring" -> "@Component"
        "JPA" -> "@Entity"
        "Jackson" -> "@JsonProperty"
        else -> error("Unknown framework: '$framework'")
    }

    private fun writePom(root: Path, sourceDir: String) {
        write(
            root,
            "pom.xml",
            """
            <project><modelVersion>4.0.0</modelVersion>
              <groupId>example</groupId><artifactId>safedelete</artifactId><version>1</version>
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
        val report = reportDir.resolve("java-safe-delete-characterization-messages.txt")
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

    private companion object {
        val JDT_DELETEABLE_KINDS = setOf(
            JdtJavaSemanticSymbolKind.CLASS,
            JdtJavaSemanticSymbolKind.INTERFACE,
            JdtJavaSemanticSymbolKind.ENUM,
            JdtJavaSemanticSymbolKind.RECORD,
            JdtJavaSemanticSymbolKind.ANNOTATION,
        )
    }
}
