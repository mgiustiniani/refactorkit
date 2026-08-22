package org.refactorkit.jvm.organizeimports.styleaware

import io.cucumber.java.After
import io.cucumber.java.Scenario
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.ClasspathEvidence
import org.refactorkit.core.ClasspathEvidenceKind
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.kotlin.KotlinCompilerDiagnostics
import org.refactorkit.kotlin.KotlinJvmBuildModelIntegration
import org.refactorkit.kotlin.KotlinLanguageAdapter
import org.refactorkit.kotlin.KotlinOrganizeImportsPlanner
import org.refactorkit.kotlin.KotlinSemanticToolchain
import org.refactorkit.kotlin.KotlinToolchainDiscoverer
import org.refactorkit.kotlin.KotlinToolchainDiscovery
import org.refactorkit.kotlin.KotlinToolchainRequest
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Story BDD glue for features/kotlin-jvm-project-style-aware-formatting.feature
 * (REQ-KOTLIN-IMPORT-STYLE-001, @not-implemented).
 *
 * It replicates the K2 toolchain fixture from the other organize-imports slices
 * (kotlin-compiler-embeddable-2.0.21, jvmTarget 21, jdkToolchain 21) and drives the
 * real KotlinOrganizeImportsPlanner. Style files (.editorconfig / gradle.properties)
 * are captured as no-follow DECLARATION_FILE ClasspathEvidence so the planner's
 * projectStyle boundary is genuinely exercised.
 *
 * The feature declares the actual production refusal codes (kotlin.organizeImportsStyleUnsupported,
 * kotlin.organizeImportsStyleStale, kotlin.organizeImportsNoChange). Every refusal step asserts that
 * the DECLARED code EQUALS the ACTUAL refusalCode the planner returned; the suite FAILS on a
 * typed-code regression. Every observed declared-code to actual-code mapping is written to
 * build/reports/cucumber/kotlin-jvm-project-style-aware-formatting-codes.txt for reconciliation.
 */
class KotlinJvmProjectStyleAwareFormattingSteps {
    private val temporaryDirectories = mutableListOf<Path>()
    private val observedRefusals = mutableListOf<ObservedRefusal>()

    private var fixtureRoot: Path? = null
    private var mainPath: Path? = null
    private var snapshot: ProjectSnapshot? = null
    private lateinit var toolchain: KotlinSemanticToolchain
    private var plan: PatchPlan? = null
    private var applied: ApplyResult.Applied? = null
    private var rolledBack: ApplyResult.Applied? = null
    private var gatePlanner: KotlinOrganizeImportsPlanner? = null

    private var originalMain: String? = null
    private var replacementText: String? = null
    private var previewBaseline: Map<String, List<Byte>>? = null
    private var expectedGroups: List<List<String>>? = null

    // ------------------------------------------------------------------ fixtures

    @Given("^one saved authoritative non-generated Kotlin JVM file with retained imports in one contiguous import block$")
    fun defaultContiguousImportsFixture() {
        val root = temporaryDirectory("rk-jvm-style-default")
        writePom(root)
        writeApiHelpers(root)
        writeUtilHelpers(root)
        writeMainUnsorted(root)
        fixtureRoot = root
        mainPath = Path.of("src/main/kotlin/fixture/app/Main.kt")
        toolchain = toolchain(root)
        snapshot = attach(root)
        expectedGroups = listOf(
            listOf("import fixture.api.Bar", "import fixture.api.Foo", "import fixture.util.Baz"),
        )
    }

    @Given("^one saved authoritative non-generated Kotlin JVM file with retained imports in one contiguous import block using \"([^\"]+)\" line endings$")
    fun lineEndingFixture(lineEnding: String) {
        val root = temporaryDirectory("rk-jvm-style-line-ending")
        writePom(root)
        writeApiHelpers(root)
        writeUtilHelpers(root)
        val newline = if (lineEnding == "CRLF") "\r\n" else "\n"
        root.resolve("src/main/kotlin/fixture/app/Main.kt").apply { parent.createDirectories() }.writeText(
            "package fixture.app$newline" +
                "import fixture.util.Baz$newline" +
                "import fixture.api.Bar$newline" +
                "import fixture.api.Foo$newline" +
                "fun run(): String = Foo().run() + Bar().run() + Baz().run()$newline",
        )
        fixtureRoot = root
        mainPath = Path.of("src/main/kotlin/fixture/app/Main.kt")
        toolchain = toolchain(root)
        snapshot = attach(root)
        expectedGroups = listOf(
            listOf("import fixture.api.Bar", "import fixture.api.Foo", "import fixture.util.Baz"),
        )
    }

    @Given("^one saved authoritative non-generated Kotlin JVM file under a nested source path$")
    fun nestedSourcePathFixture() {
        val root = temporaryDirectory("rk-jvm-style-nested")
        writePom(root)
        writeApiHelpers(root)
        writeUtilHelpers(root)
        // The retained file lives under a nested source path so both ancestor style
        // configurations are ancestors; the nearer (deeper) one must win.
        root.resolve("src/main/kotlin/fixture/app/deep/Main.kt").apply { parent.createDirectories() }.writeText(
            "package fixture.app.deep\n" +
                "import java.time.Clock\n" +
                "import fixture.api.Bar\n" +
                "import fixture.api.Foo\n" +
                "fun run(): String {\n" +
                "    val clock: Clock = Clock.systemUTC()\n" +
                "    return Foo().run() + Bar().run() + clock.toString()\n" +
                "}\n",
        )
        // farther ancestor (root) .editorconfig
        root.resolve(".editorconfig").writeText("[*.kt]\nij_kotlin_imports_layout = java.**,*\n")
        // nearer ancestor (deeper) .editorconfig
        val nearer = root.resolve("src/main/kotlin/fixture/.editorconfig")
        nearer.parent.createDirectories()
        nearer.writeText("[*.kt]\nij_kotlin_imports_layout = fixture.api.**,*\n")
        fixtureRoot = root
        mainPath = Path.of("src/main/kotlin/fixture/app/deep/Main.kt")
        toolchain = toolchain(root)
        val scanned = JavaProjectScanner().scan(root)
        // The scanner only captures style files at the module root; the deeper nearer
        // .editorconfig is captured explicitly as no-follow DECLARATION_FILE evidence.
        val nearerEvidence = ClasspathEvidence.capture(root, root.relativize(nearer), ClasspathEvidenceKind.DECLARATION_FILE)
        snapshot = KotlinJvmBuildModelIntegration.attach(
            scanned.copy(classpathEvidence = scanned.classpathEvidence + nearerEvidence),
            toolchain,
        )
        expectedGroups = listOf(
            listOf("import fixture.api.Bar", "import fixture.api.Foo"),
            listOf("import java.time.Clock"),
        )
    }

    @Given("^one saved authoritative non-generated Kotlin JVM file whose retained directives are already in the default official order$")
    fun alreadyOrderedFixture() {
        val root = temporaryDirectory("rk-jvm-style-noop")
        writePom(root)
        writeApiHelpers(root)
        writeUtilHelpers(root)
        root.resolve("src/main/kotlin/fixture/app/Main.kt").apply { parent.createDirectories() }.writeText(
            "package fixture.app\n" +
                "import fixture.api.Bar\n" +
                "import fixture.api.Foo\n" +
                "import fixture.util.Baz\n" +
                "fun run(): String = Foo().run() + Bar().run() + Baz().run()\n",
        )
        fixtureRoot = root
        mainPath = Path.of("src/main/kotlin/fixture/app/Main.kt")
        toolchain = toolchain(root)
        snapshot = attach(root)
        expectedGroups = listOf(
            listOf("import fixture.api.Bar", "import fixture.api.Foo", "import fixture.util.Baz"),
        )
    }

    @Given("^an approved successful preview of a snapshot-bound import layout$")
    fun approvedSuccessfulPreview() {
        val root = temporaryDirectory("rk-jvm-style-apply")
        writePom(root)
        writeApiHelpers(root)
        writeUtilHelpers(root)
        writeMainUnsorted(root)
        fixtureRoot = root
        mainPath = Path.of("src/main/kotlin/fixture/app/Main.kt")
        toolchain = toolchain(root)
        snapshot = attach(root)
        expectedGroups = listOf(
            listOf("import fixture.api.Bar", "import fixture.api.Foo", "import fixture.util.Baz"),
        )
        originalMain = root.resolve(requireNotNull(mainPath)).readText()
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain))
        val planner = KotlinOrganizeImportsPlanner(adapter)
        val preview = planner.preview(requireNotNull(snapshot), requireNotNull(mainPath))
        assertEquals(PatchStatus.PREVIEW, preview.status, preview.toString())
        plan = preview
        gatePlanner = planner
    }

    // ------------------------------------------------------------------ scenario And steps

    @Given("^the snapshot carries complete error-free compiler evidence and the retained import directives$")
    fun snapshotCarriesCompilerEvidenceAndRetainedDirectives() {
        val snap = requireNotNull(snapshot)
        val main = snap.files.singleOrNull { it.path.normalize() == requireNotNull(mainPath).normalize() }
        assertNotNull(main, "expected the retained Kotlin JVM file in the snapshot")
        assertTrue(main.content.lines().any { it.trim().startsWith("import ") }, main.content)
    }

    @Given("^the snapshot carries complete error-free compiler evidence$")
    fun snapshotCarriesCompilerEvidence() {
        assertTrue(
            requireNotNull(snapshot).files.any { it.path.normalize() == requireNotNull(mainPath).normalize() },
            "expected the retained Kotlin JVM file in the snapshot",
        )
    }

    @Given("^no captured project style file overrides the layout$")
    fun noCapturedStyleFileOverridesLayout() {
        val snap = requireNotNull(snapshot)
        assertTrue(
            snap.classpathEvidence.none {
                it.kind == ClasspathEvidenceKind.DECLARATION_FILE &&
                    it.path.fileName?.toString() in setOf(".editorconfig", "gradle.properties")
            },
            "expected no captured style evidence to override the layout",
        )
    }

    @Given("^a captured \\.editorconfig declares ij_kotlin_imports_layout = kotlin\\.\\*\\*,java\\.\\*\\*,\\*$")
    fun editorconfigKotlinJavaCatchAll() {
        val root = requireFixtureRoot()
        root.resolve(".editorconfig").writeText("[*.kt]\nij_kotlin_imports_layout = kotlin.**,java.**,*\n")
        writeMainKotlinJavaApi(root)
        expectedGroups = listOf(
            listOf("import kotlin.time.Duration"),
            listOf("import java.time.Clock"),
            listOf("import fixture.api.Foo"),
        )
        rescan(root)
    }

    @Given("^the style file is captured as no-follow ClasspathEvidence and matches the preview snapshot$")
    fun styleFileCapturedAsNoFollowEvidence() {
        val snap = requireNotNull(snapshot)
        val evidence = snap.classpathEvidence.singleOrNull {
            it.kind == ClasspathEvidenceKind.DECLARATION_FILE &&
                it.path.fileName?.toString() == ".editorconfig"
        }
        assertNotNull(evidence, "expected the .editorconfig DECLARATION_FILE evidence")
        assertEquals(
            evidence.fingerprint,
            ClasspathEvidence.fingerprint(requireFixtureRoot().resolve(evidence.path), evidence.kind),
            "the .editorconfig evidence fingerprint must still match the preview snapshot",
        )
    }

    @Given("^a captured \\.editorconfig declares ij_kotlin_imports_layout = \\^,fixture\\.api\\.\\*\\*,fixture\\.util\\.\\*\\*,\\*$")
    fun editorconfigAliasGroup() {
        val root = requireFixtureRoot()
        root.resolve(".editorconfig").writeText("[*.kt]\nij_kotlin_imports_layout = ^,fixture.api.**,fixture.util.**,*\n")
        writeMainAlias(root)
        expectedGroups = listOf(
            listOf("import fixture.util.Baz as B"),
            listOf("import fixture.api.Bar", "import fixture.api.Foo"),
            listOf("import fixture.util.Thing"),
            listOf("import java.time.Clock"),
        )
        rescan(root)
    }

    @Given("^the retained imports include an aliased import and imports under the fixture\\.api and fixture\\.util package prefixes$")
    fun retainedImportsIncludeAliasedAndPrefixes() {
        val content = requireNotNull(snapshot).files
            .single { it.path.normalize() == requireNotNull(mainPath).normalize() }.content
        assertTrue(" as " in content, "expected an aliased import")
        assertTrue("fixture.api." in content && "fixture.util." in content, "expected api and util prefix imports")
    }

    @Given("^a captured \\.editorconfig at a nearer ancestor declares ij_kotlin_imports_layout = fixture\\.api\\.\\*\\*,\\*$")
    fun nearerAncestorEditorconfig() {
        assertTrue(
            requireNotNull(snapshot).classpathEvidence.any {
                it.kind == ClasspathEvidenceKind.DECLARATION_FILE &&
                    it.path.toString().contains("src/main/kotlin/fixture/.editorconfig")
            },
            "expected the nearer-ancestor .editorconfig captured as DECLARATION_FILE evidence",
        )
    }

    @Given("^a captured \\.editorconfig at a farther ancestor declares ij_kotlin_imports_layout = java\\.\\*\\*,\\*$")
    fun fartherAncestorEditorconfig() {
        assertTrue(
            requireNotNull(snapshot).classpathEvidence.any {
                it.kind == ClasspathEvidenceKind.DECLARATION_FILE &&
                    it.path.fileName?.toString() == ".editorconfig" &&
                    !it.path.toString().contains("src/main")
            },
            "expected the farther-ancestor (root) .editorconfig captured as DECLARATION_FILE evidence",
        )
    }

    @Given("^a captured gradle\\.properties declares exactly kotlin\\.code\\.style=official$")
    fun gradlePropertiesOfficial() {
        val root = requireFixtureRoot()
        root.resolve("gradle.properties").writeText("kotlin.code.style=official\n")
        writeMainGradle(root)
        expectedGroups = listOf(
            listOf("import fixture.api.Bar", "import fixture.api.Foo"),
        )
        rescan(root)
    }

    @Given("^the style file is re-fingerprinted before use and matches the preview snapshot$")
    fun styleFileReFingerprinted() {
        val snap = requireNotNull(snapshot)
        val evidence = snap.classpathEvidence.singleOrNull {
            it.kind == ClasspathEvidenceKind.DECLARATION_FILE &&
                it.path.fileName?.toString() == "gradle.properties"
        }
        assertNotNull(evidence, "expected the gradle.properties DECLARATION_FILE evidence")
        assertEquals(
            evidence.fingerprint,
            ClasspathEvidence.fingerprint(requireFixtureRoot().resolve(evidence.path), evidence.kind),
            "the gradle.properties evidence fingerprint must still match the preview snapshot",
        )
    }

    @Given("^the managed-apply diagnostics gate for organize-imports is the operation-owned layout gate$")
    fun managedApplyGateIsOperationOwned() {
        assertNotNull(gatePlanner, "expected the organize-imports planner to own the managed-apply gate")
    }

    @Given("^the captured Kotlin layout configuration is \"([^\"]+)\"$")
    fun capturedLayoutConfiguration(condition: String) {
        val root = requireFixtureRoot()
        val editor = root.resolve(".editorconfig")
        when (condition) {
            "an empty token making the declared token count invalid" ->
                editor.writeText("[*.kt]\nij_kotlin_imports_layout = kotlin.**,\n")
            "ij_kotlin_imports_layout declared more than once" ->
                editor.writeText("[*.kt]\nij_kotlin_imports_layout = kotlin.**,*\nij_kotlin_imports_layout = java.**,*\n")
            "a repeated token that breaks the unique-token and one-star rule" ->
                editor.writeText("[*.kt]\nij_kotlin_imports_layout = kotlin.**,kotlin.**\n")
            "a token that escapes the supported grammar such as an escaped star" ->
                editor.writeText("[*.kt]\nij_kotlin_imports_layout = kotlin.\\*\\*\n")
            "a style evidence file larger than 64 KiB" ->
                editor.writeText("[*.kt]\nij_kotlin_imports_layout = kotlin.**,*\n" + "x".repeat(64 * 1024 + 1) + "\n")
            "a style evidence file containing a NUL byte" ->
                editor.writeText("[*.kt]\nij_kotlin_imports_layout = kotlin.**,*\n\u0000\n")
            "an unsupported token outside the supported grammar" ->
                editor.writeText("[*.kt]\nij_kotlin_imports_layout = foo.**,bar\n")
            "a gradle.properties value other than the exact official value" ->
                root.resolve("gradle.properties").writeText("kotlin.code.style=community\n")
            else -> error("unknown layout condition: $condition")
        }
        rescan(root)
    }

    @Given("^the captured Kotlin layout evidence is \"([^\"]+)\"$")
    fun capturedLayoutEvidence(condition: String) {
        val root = requireFixtureRoot()
        val editor = root.resolve(".editorconfig")
        when (condition) {
            "stale because the style fingerprint changed after the snapshot" -> {
                editor.writeText("[*.kt]\nij_kotlin_imports_layout = kotlin.**,*\n")
                rescan(root) // captures fingerprint F1
                editor.writeText("[*.kt]\nij_kotlin_imports_layout = java.**,*\n") // now F2 != F1
            }
            "a symbolic link instead of a no-follow regular file" -> {
                editor.writeText("[*.kt]\nij_kotlin_imports_layout = kotlin.**,*\n")
                rescan(root) // captures the real no-follow regular file
                Files.delete(editor)
                Files.createSymbolicLink(editor, Path.of("src/main/kotlin/fixture/api/Foo.kt"))
            }
            else -> error("unknown evidence condition: $condition")
        }
    }

    // ------------------------------------------------------------------ When

    @When("^organize-imports previews that Kotlin JVM file$")
    fun organizeImportsPreviews() {
        val root = requireFixtureRoot()
        previewBaseline = observeWorkspace(root)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain))
        val planner = KotlinOrganizeImportsPlanner(adapter)
        val preview = planner.preview(requireNotNull(snapshot), requireNotNull(mainPath))
        plan = preview
        if (preview.status == PatchStatus.PREVIEW) {
            val staged = WorkspaceEditSimulator.apply(requireNotNull(snapshot), preview.workspaceEdit)
            val main = staged.files.single { it.path.normalize() == requireNotNull(mainPath).normalize() }
            replacementText = main.content
        } else {
            replacementText = null
        }
        originalMain = root.resolve(requireNotNull(mainPath)).readText()
    }

    @When("^the preview is applied under explicit authorization$")
    fun previewAppliedUnderExplicitAuthorization() {
        val root = requireFixtureRoot()
        val preview = requireNotNull(plan)
        val snap = requireNotNull(snapshot)
        val planner = requireNotNull(gatePlanner)
        applied = assertIs<ApplyResult.Applied>(PatchEngine(root).apply(
            preview,
            snap,
            ApplyAuthorization.explicit("kotlin-organize-imports-style-cucumber"),
            DiagnosticsGate.enabled("kotlin-k2", planner::diagnostics),
        ))
    }

    @When("^that transaction is rolled back$")
    fun transactionRolledBack() {
        rolledBack = assertIs<ApplyResult.Applied>(
            PatchEngine(requireFixtureRoot()).rollback(requireNotNull(applied).transaction),
        )
    }

    // ------------------------------------------------------------------ Then

    @Then("^the retained directives are reordered into one Unicode-code-point-sorted group$")
    fun retainedDirectivesReorderedIntoOneGroup() {
        val p = requireNotNull(plan)
        assertTrue(p.status == PatchStatus.PREVIEW, p.toString())
        assertEquals(expectedDirectiveOrder(), replacementDirectives(), "expected one sorted group")
    }

    @Then("^each retained directive is preserved byte for byte$")
    fun eachRetainedDirectivePreservedByteForByte() {
        val p = requireNotNull(plan)
        assertTrue(p.status == PatchStatus.PREVIEW, p.toString())
        val replacement = requireNotNull(replacementText)
        val originalDirectives = requireNotNull(originalMain).lineSequence()
            .map(String::trim).filter { it.startsWith("import ") }.toList()
        assertEquals(originalDirectives.toSet(), replacementDirectives().toSet(), "retained directive set must be unchanged")
        originalDirectives.forEach { directive ->
            assertTrue(directive in replacement, "directive '$directive' must be preserved byte for byte")
        }
    }

    @Then("^only directive order and configured blank-line separators change$")
    fun onlyDirectiveOrderAndBlankLineSeparatorsChange() {
        val p = requireNotNull(plan)
        assertTrue(p.status == PatchStatus.PREVIEW, p.toString())
        val originalDirectives = requireNotNull(originalMain).lineSequence()
            .map(String::trim).filter { it.startsWith("import ") }.toSet()
        assertEquals(originalDirectives, replacementDirectives().toSet(), "the retained directive set must be unchanged")
    }

    @Then("^the preview is read-only and does not mutate the snapshot or the filesystem$")
    fun previewIsReadOnly() {
        val p = requireNotNull(plan)
        assertTrue(p.status == PatchStatus.PREVIEW, p.toString())
        assertEquals(p.snapshotHash, requireNotNull(snapshot).hash, "preview must not mutate the snapshot")
        assertEquals(
            requireNotNull(previewBaseline), observeWorkspace(requireFixtureRoot()),
            "preview must not mutate the filesystem",
        )
    }

    @Then("^the \"([^\"]+)\" line endings are preserved byte for byte$")
    fun lineEndingsPreservedByteForByte(lineEnding: String) {
        val p = requireNotNull(plan)
        assertTrue(p.status == PatchStatus.PREVIEW, p.toString())
        val replacement = requireNotNull(replacementText)
        if (lineEnding == "CRLF") {
            assertTrue("\r\n" in replacement, "expected CRLF line endings preserved")
        } else {
            assertTrue("\r\n" !in replacement, "expected LF line endings preserved without CR")
            assertTrue("\n" in replacement, "expected LF line endings preserved")
        }
    }

    @Then("^the retained directives are grouped by the tokens kotlin\\.\\*\\*, java\\.\\*\\*, and \\* in declared order$")
    fun groupedByTokensInDeclaredOrder() {
        assertEquals(expectedDirectiveOrder(), replacementDirectives(), "expected kotlin, java, catch-all grouping")
    }

    @Then("^each group is separated by one empty line$")
    fun eachGroupSeparatedByOneEmptyLine() {
        val replacement = requireNotNull(replacementText)
        val groups = requireNotNull(expectedGroups)
        val lines = replacement.lineSequence().toList()
        val firstImport = lines.indexOfFirst { it.trim().startsWith("import ") }
        val lastImport = lines.indexOfLast { it.trim().startsWith("import ") }
        require(firstImport >= 0 && lastImport >= firstImport) { "no import block in replacement" }
        val region = lines.subList(firstImport, lastImport + 1)
        val blankLines = region.count(String::isBlank)
        assertEquals(groups.size - 1, blankLines, "expected one empty line between each group")
    }

    @Then("^the preview warns that the import layout is bound to the \\.editorconfig path$")
    fun previewWarnsImportLayoutBoundToEditorconfig() {
        val p = requireNotNull(plan)
        assertTrue(
            p.status == PatchStatus.PREVIEW && p.warnings.any { it.startsWith("Kotlin import layout is bound to ") },
            "expected a warning binding the layout to the .editorconfig path; warnings=${p.warnings}",
        )
    }

    @Then("^the retained directives are grouped by the alias group, the fixture\\.api\\.\\*\\* prefix, the fixture\\.util\\.\\*\\* prefix, and the catch-all in declared order$")
    fun groupedByAliasAndPrefixesInDeclaredOrder() {
        assertEquals(expectedDirectiveOrder(), replacementDirectives(), "expected alias, api, util, catch-all grouping")
    }

    @Then("^the aliased import is placed in the alias group$")
    fun aliasedImportPlacedInAliasGroup() {
        val directives = replacementDirectives()
        assertTrue(directives.first().contains(" as "), "expected the aliased import in the leading alias group")
        assertEquals("import fixture.util.Baz as B", directives.first(), "alias group must come first")
    }

    @Then("^imports matching a package prefix are placed in the matching prefix group$")
    fun prefixImportsPlacedInMatchingPrefixGroup() {
        val directives = replacementDirectives()
        assertEquals(
            expectedDirectiveOrder(), directives,
            "expected api/util prefix imports placed in their matching prefix groups",
        )
    }

    @Then("^imports matching no prefix are placed in the catch-all group$")
    fun noPrefixImportsPlacedInCatchAllGroup() {
        val directives = replacementDirectives()
        val groups = requireNotNull(expectedGroups)
        assertTrue(groups.size == 4, "expected alias, api, util, and catch-all groups")
        assertEquals(groups.last().single(), directives.last(), "expected the no-prefix import in the catch-all group")
    }

    @Then("^the nearest captured ancestor layout is selected and the farther ancestor layout is ignored$")
    fun nearestAncestorLayoutSelected() {
        assertEquals(expectedDirectiveOrder(), replacementDirectives(), "expected the nearest ancestor tokens")
        assertTrue(requireNotNull(expectedGroups).size == 2, "the farther java.** layout must be ignored")
    }

    @Then("^the retained directives are grouped by the nearest tokens in declared order$")
    fun groupedByNearestTokensInDeclaredOrder() {
        assertEquals(expectedDirectiveOrder(), replacementDirectives(), "expected nearest-token grouping")
    }

    @Then("^the explicit official-style declaration is recognized$")
    fun explicitOfficialStyleRecognized() {
        val p = requireNotNull(plan)
        assertTrue(p.status == PatchStatus.PREVIEW, p.toString())
        assertEquals(expectedDirectiveOrder(), replacementDirectives(), "expected the official single sorted group")
    }

    // ------------------------------------------------------------------ refusal Then

    @Then("^RefactorKit refuses the operation and explains why, reporting the typed code \"kotlin\\.organizeImportsNoChange\"$")
    fun refusesNoChange() {
        val p = requireNotNull(plan)
        recordRefusal("kotlin.organizeImportsNoChange", p.refusalCode, p.status)
        assertEquals(PatchStatus.REFUSED, p.status, p.toString())
        assertEquals(
            "kotlin.organizeImportsNoChange", p.refusalCode,
            "declared refusal code 'kotlin.organizeImportsNoChange' did not equal the actual code '${p.refusalCode}'",
        )
        assertTrue(p.workspaceEdit.edits.isEmpty(), p.toString())
    }

    @Then("^the preview performs no workspace or transaction write$")
    fun previewPerformsNoWorkspaceOrTransactionWrite() {
        val p = requireNotNull(plan)
        assertTrue(p.status == PatchStatus.REFUSED, p.toString())
        assertTrue(p.workspaceEdit.edits.isEmpty(), p.toString())
        assertEquals(
            requireNotNull(previewBaseline), observeWorkspace(requireFixtureRoot()),
            "the preview must perform no workspace or transaction write",
        )
    }

    @Then("^the refusal changes no file, plan, lock, or transaction record$")
    fun refusalChangesNoFilePlanLockOrTransactionRecord() {
        val p = requireNotNull(plan)
        assertTrue(p.status == PatchStatus.REFUSED, p.toString())
        assertTrue(p.workspaceEdit.edits.isEmpty(), p.toString())
        assertTrue(p.affectedFiles.isEmpty(), p.toString())
        assertEquals(
            requireNotNull(previewBaseline), observeWorkspace(requireFixtureRoot()),
            "the refusal must change no file, plan, lock, or transaction record",
        )
    }

    @Then("^no file, plan, lock, or transaction record changes$")
    fun noFilePlanLockOrTransactionRecordChanges() {
        val p = requireNotNull(plan)
        assertTrue(p.status == PatchStatus.REFUSED, p.toString())
        assertTrue(p.workspaceEdit.edits.isEmpty(), p.toString())
        assertTrue(p.affectedFiles.isEmpty(), p.toString())
        assertEquals(
            requireNotNull(previewBaseline), observeWorkspace(requireFixtureRoot()),
            "no file, plan, lock, or transaction record may change",
        )
    }

    @Then("^RefactorKit refuses the operation before planning and explains why, reporting the typed code \"kotlin\\.organizeImportsStyleUnsupported\"$")
    fun refusesStyleUnsupported() {
        val p = requireNotNull(plan)
        recordRefusal("kotlin.organizeImportsStyleUnsupported", p.refusalCode, p.status)
        assertEquals(PatchStatus.REFUSED, p.status, p.toString())
        assertEquals(
            "kotlin.organizeImportsStyleUnsupported", p.refusalCode,
            "declared refusal code 'kotlin.organizeImportsStyleUnsupported' did not equal the actual code '${p.refusalCode}'",
        )
        assertTrue(p.workspaceEdit.edits.isEmpty(), p.toString())
    }

    @Then("^RefactorKit refuses the operation before planning and explains why, reporting the typed code \"kotlin\\.organizeImportsStyleStale\"$")
    fun refusesStyleStale() {
        val p = requireNotNull(plan)
        recordRefusal("kotlin.organizeImportsStyleStale", p.refusalCode, p.status)
        assertEquals(PatchStatus.REFUSED, p.status, p.toString())
        assertEquals(
            "kotlin.organizeImportsStyleStale", p.refusalCode,
            "declared refusal code 'kotlin.organizeImportsStyleStale' did not equal the actual code '${p.refusalCode}'",
        )
        assertTrue(p.workspaceEdit.edits.isEmpty(), p.toString())
    }

    // ------------------------------------------------------------------ apply/rollback Then

    @Then("^apply uses the patch engine and writes a transaction rollback record$")
    fun applyUsesPatchEngineAndWritesRollbackRecord() {
        val a = requireNotNull(applied)
        assertNotNull(a.transaction.id, "expected a write-ahead transaction id")
        assertTrue(a.transaction.snapshotHashBefore.isNotBlank(), "expected a transaction snapshot-hash attestation")
        assertTrue(!a.transaction.rollbackEdit.edits.isEmpty(), "expected a transaction write-ahead rollback record")
    }

    @Then("^the committed post-image contains the reordered retained directives with unchanged bytes$")
    fun committedPostImageContainsReorderedDirectives() {
        val content = requireFixtureRoot().resolve(requireNotNull(mainPath)).readText()
        assertEquals(expectedDirectiveOrder(), contentDirectives(content), "expected the committed reordered directives")
        requireNotNull(originalMain).lineSequence()
            .map(String::trim).filter { it.startsWith("import ") }.forEach { directive ->
                assertTrue(directive in content, "directive '$directive' must remain byte-for-byte in the committed image")
            }
    }

    @Then("^every file byte, path, and snapshot hash equals the pre-apply image$")
    fun everyFileBytePathAndSnapshotHashEqualsPreApply() {
        val root = requireFixtureRoot()
        assertEquals(requireNotNull(originalMain), root.resolve(requireNotNull(mainPath)).readText())
        val restored = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        assertEquals(requireNotNull(snapshot).hash, restored.hash, "restored snapshot hash must equal the pre-apply image")
    }

    @Then("^rollback restores every original byte$")
    fun rollbackRestoresEveryOriginalByte() {
        assertIs<ApplyResult.Applied>(requireNotNull(rolledBack))
        assertEquals(requireNotNull(originalMain), requireFixtureRoot().resolve(requireNotNull(mainPath)).readText())
    }

    // ------------------------------------------------------------------ helpers

    private fun attach(root: Path): ProjectSnapshot =
        KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)

    private fun rescan(root: Path) {
        snapshot = attach(root)
    }

    private fun expectedDirectiveOrder(): List<String> =
        requireNotNull(expectedGroups).flatten()

    private fun replacementDirectives(): List<String> =
        requireNotNull(replacementText).lineSequence().map(String::trim).filter { it.startsWith("import ") }.toList()

    private fun contentDirectives(content: String): List<String> =
        content.lineSequence().map(String::trim).filter { it.startsWith("import ") }.toList()

    private fun requireFixtureRoot(): Path {
        fixtureRoot?.let { return it }
        error("no fixture root")
    }

    private fun recordRefusal(declaredCode: String, actualCode: String?, status: PatchStatus) {
        observedRefusals += ObservedRefusal(declaredCode, actualCode, status)
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

    private fun writeApiHelpers(root: Path) {
        root.resolve("src/main/kotlin/fixture/api/Foo.kt").apply { parent.createDirectories() }.writeText(
            "package fixture.api\npublic class Foo { fun run(): String = \"foo\" }\n",
        )
        root.resolve("src/main/kotlin/fixture/api/Bar.kt").apply { parent.createDirectories() }.writeText(
            "package fixture.api\npublic class Bar { fun run(): String = \"bar\" }\n",
        )
    }

    private fun writeUtilHelpers(root: Path) {
        root.resolve("src/main/kotlin/fixture/util/Baz.kt").apply { parent.createDirectories() }.writeText(
            "package fixture.util\npublic class Baz { fun run(): String = \"baz\" }\n",
        )
        root.resolve("src/main/kotlin/fixture/util/Thing.kt").apply { parent.createDirectories() }.writeText(
            "package fixture.util\npublic class Thing { fun run(): String = \"thing\" }\n",
        )
    }

    private fun writeMainUnsorted(root: Path) {
        root.resolve("src/main/kotlin/fixture/app/Main.kt").apply { parent.createDirectories() }.writeText(
            "package fixture.app\n" +
                "import fixture.util.Baz\n" +
                "import fixture.api.Bar\n" +
                "import fixture.api.Foo\n" +
                "fun run(): String = Foo().run() + Bar().run() + Baz().run()\n",
        )
    }

    private fun writeMainKotlinJavaApi(root: Path) {
        root.resolve("src/main/kotlin/fixture/app/Main.kt").apply { parent.createDirectories() }.writeText(
            "package fixture.app\n" +
                "import fixture.api.Foo\n" +
                "import java.time.Clock\n" +
                "import kotlin.time.Duration\n" +
                "fun run(): String {\n" +
                "    val clock: Clock = Clock.systemUTC()\n" +
                "    val duration: Duration = Duration.ZERO\n" +
                "    return Foo().run() + duration.toString() + clock.toString()\n" +
                "}\n",
        )
    }

    private fun writeMainAlias(root: Path) {
        root.resolve("src/main/kotlin/fixture/app/Main.kt").apply { parent.createDirectories() }.writeText(
            "package fixture.app\n" +
                "import java.time.Clock\n" +
                "import fixture.util.Baz as B\n" +
                "import fixture.api.Bar\n" +
                "import fixture.util.Thing\n" +
                "import fixture.api.Foo\n" +
                "fun run(): String {\n" +
                "    val clock: Clock = Clock.systemUTC()\n" +
                "    return B().run() + Bar().run() + Foo().run() + Thing().run() + clock.toString()\n" +
                "}\n",
        )
    }

    private fun writeMainGradle(root: Path) {
        root.resolve("src/main/kotlin/fixture/app/Main.kt").apply { parent.createDirectories() }.writeText(
            "package fixture.app\n" +
                "import fixture.api.Foo\n" +
                "import fixture.api.Bar\n" +
                "fun run(): String = Foo().run() + Bar().run()\n",
        )
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

    private fun observeWorkspace(root: Path): Map<String, List<Byte>> =
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .sorted()
                .toList()
                .associate { path ->
                    root.relativize(path).normalize().toString() to Files.readAllBytes(path).toList()
                }
        }

    private fun deleteNoFollow(root: Path) {
        if (!root.exists()) return
        val paths = Files.walk(root).use { stream -> stream.toList() }
        paths.sortedByDescending(Path::getNameCount).forEach { path ->
            // Files.delete on a symbolic link removes only the link, never the target;
            // the stale/symlinked style-evidence scenarios create a symlink in the fixture.
            Files.delete(path)
        }
    }

    @After
    fun cleanup(scenario: Scenario) {
        val reportDir = Path.of(System.getProperty("user.dir")).resolve("build/reports/cucumber")
        reportDir.createDirectories()
        val report = reportDir.resolve("kotlin-jvm-project-style-aware-formatting-codes.txt")
        val lines = mutableListOf<String>()
        lines += "scenario:${scenario.name}"
        observedRefusals.forEach { lines += "  ${it.declaredCode} -> ${it.actualCode ?: "-"} (${it.status})" }
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
}
