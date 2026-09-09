package org.refactorkit.jvm.movesourceroot

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
import org.refactorkit.core.SourceFile
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.java.JavaLanguageAdapter
import org.refactorkit.java.JavaMoveSourceRootPlanner
import org.refactorkit.java.JavaPackageUtil
import org.refactorkit.java.JavaProjectScanner
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Story BDD glue for features/java-move-source-root-characterization.feature
 * (REQ-JAVA-MOVE-SOURCE-ROOT-CHAR-001, row C-ROOT of the approved finite J1 Java catalogue).
 *
 * It drives the real toolchain: a Maven reactor workspace scanned by JavaProjectScanner into a
 * real build model, the real JavaLanguageAdapter, and the real JavaMoveSourceRootPlanner preview.
 * The feature begins GREEN because production already implements the planner; the glue asserts the
 * ACTUAL behavior rather than inventing it. The successful Scenario 1 asserts the rename-only
 * FileEdit.Rename plan with unchanged package/FQCN/bytes, confidence 1.0, requiresUserApproval,
 * evidence STRUCTURAL, and risk MEDIUM (source and destination are distinct reactor modules).
 *
 * Every refusal condition is a truthful fixture that makes the real planner return the DECLARED
 * typed code — no code is invented and no production branch is weakened:
 *   - sourceRoot.missing:            from is not a recognized safe source root
 *   - sourceRoot.destinationUnrecognized: to is not owned by exactly one recognized/prospective root
 *   - sourceRoot.overlap:            destination nests under source
 *   - sourceRoot.generated:          generated-sources root is read-only
 *   - sourceRoot.symlinkEscape:      destination path traverses a symbolic link
 *   - sourceRoot.packageMismatch:    package declaration does not match root-relative path
 *   - sourceRoot.duplicateType:      relocation retains a duplicate FQCN (test-root twin)
 *   - sourceRoot.destinationCollision: destination path folds into an existing path
 *   - sourceRoot.diagnosticsRegression: record moves into a Java 8 destination source set -> new compiler error
 *   - buildModel.unavailable:        post-image diagnostics throw (destination dir named at the rename
 *                                    target causes the overlay to fail creating its parent as a directory)
 *   - classpath.unavailable:         destination module has an unavailable offline classpath and gains
 *                                    Java files after the move -> new classpath.unavailable regression
 *
 * The refusal Then steps assert REFUSED, the exact declared code, an empty WorkspaceEdit, an empty
 * affected-file set, no approval, no managed-write authority lease, and no pending actionable plan.
 * Observed declared-to-actual mappings are appended to
 * build/reports/cucumber/java-move-source-root-characterization-codes.txt for reconciliation.
 */
class KotlinJvmMoveSourceRootCharacterizationSteps {
    private val temporaryDirectories = mutableListOf<Path>()
    private val observedRefusals = mutableListOf<ObservedRefusal>()

    private var fixtureRoot: Path? = null
    private var snapshot: ProjectSnapshot? = null
    private var plan: PatchPlan? = null

    private val FROM = Path.of("source/src/main/java")
    private val TO = Path.of("destination/src/main/java")

    // ------------------------------------------------------------ Scenario 1 Given

    @Given("^a Java workspace whose build model owns one recognized non-generated main source root$")
    fun workspaceWhoseBuildModelOwnsOneRoot() {
        fixtureRoot = reactorFixture()
        snapshot = JavaProjectScanner().scan(requireNotNull(fixtureRoot))
        assertTrue(requireNotNull(snapshot).buildModels.isNotEmpty(), "fixture must yield a real build model")
    }

    @Given("^the source root contains Java compilation units whose package declarations match the root path$")
    fun sourceRootContainsMatchingCompilationUnits() {
        val snap = requireNotNull(snapshot)
        val javaFiles = snap.files.filter { it.languageId == "java" && it.path.normalize().startsWith(FROM) }
        assertTrue(javaFiles.isNotEmpty(), "source root must contain Java compilation units")
        javaFiles.forEach { file ->
            val relative = FROM.relativize(file.path)
            val fileName = relative.fileName?.toString() ?: error("no file name")
            val expectedPackage = if (fileName == "module-info.java") "" else (relative.parent?.joinToString(".") ?: "")
            assertEquals(
                expectedPackage,
                JavaPackageUtil.extractPackage(file.content),
                "package declaration must match root-relative path for ${file.path}",
            )
        }
    }

    // ------------------------------------------------------------- outline Given

    @Given("^a Java workspace with a recognized non-generated main source root$")
    fun workspaceWithRecognizedMainSourceRoot() {
        fixtureRoot = reactorFixture()
        snapshot = JavaProjectScanner().scan(requireNotNull(fixtureRoot))
    }

    // ----------------------------------------------------------- Scenario 1 When

    @When("^the caller requests a moveSourceRoot preview from that source root to another recognized non-generated root of the same source-set kind$")
    fun previewSuccessfulMove() {
        preview(FROM, TO)
    }

    // ------------------------------------------------------------- outline When

    @When("^the caller requests a moveSourceRoot preview under the condition (.+)$")
    fun previewUnderCondition(condition: String) {
        when (condition.trim()) {
            "the source is not a safe workspace-relative recognized source root" ->
                preview(Path.of("unknown/src/main/java"), TO)

            "the destination is not owned by exactly one recognized or prospective source root of the same source-set kind" ->
                preview(FROM, Path.of("unknown/src/main/java"))

            "the source and destination roots overlap" ->
                preview(FROM, FROM.resolve("nested"))

            "the source or destination root is generated and read-only" -> {
                createFile("source/target/generated-sources/annotations/example/Generated.java", "package example; public class Generated {}")
                preview(Path.of("source/target/generated-sources/annotations"), TO)
            }

            "a root path traverses a symbolic link" -> {
                Files.createSymbolicLink(requireNotNull(fixtureRoot).resolve("destination/src"), requireNotNull(fixtureRoot).resolve("source/src"))
                preview(FROM, TO)
            }

            "a compilation unit package declaration does not match its root-relative path" -> {
                writeFile("source/src/main/java/example/shared/SharedValue.java", "package wrong; public record SharedValue(String value) {}")
                preview(FROM, TO)
            }

            "the relocation would retain a duplicate fully qualified type name" -> {
                createFile("destination/src/test/java/example/shared/SharedValue.java", "package example.shared; public record SharedValue(String value) {}")
                preview(FROM, TO)
            }

            "a destination path collides with an existing or folded path" -> {
                createFile("destination/src/main/java/example/shared/sharedvalue.java", "package example.shared; class other {}")
                preview(FROM, TO)
            }

            "the relocation introduces a new compiler diagnostic" -> {
                // Truthful regression: the source module compiles at Java 21 (the moved unit is a
                // record), the destination module compiles at Java 8. After the move the record lands
                // in a lower-release source set, so the post-image diagnostics report a NEW compiler
                // error that was absent before -> sourceRoot.diagnosticsRegression.
                val destPom = requireNotNull(fixtureRoot).resolve("destination/pom.xml")
                Files.writeString(destPom, destPom.readText().replace(
                    "<artifactId>destination</artifactId>",
                    "<artifactId>destination</artifactId>\n              <properties><maven.compiler.release>8</maven.compiler.release></properties>",
                ))
                preview(FROM, TO)
            }

            "post-image diagnostics cannot be produced" -> {
                // Truthful throw path: a destination DIRECTORY named exactly at the rename target
                // (SharedValue.java) containing inner.java. The collision check only walks regular
                // files, so it passes; the post-image overlay writes the moved SharedValue.java as a
                // file first, then fails to create its parent (SharedValue.java) as a directory when
                // writing inner.java -> diagnostics throw -> buildModel.unavailable.
                val dir = requireNotNull(fixtureRoot).resolve("destination/src/main/java/example/shared/SharedValue.java")
                dir.createDirectories()
                dir.resolve("inner.java").writeText("package example.shared; class inner {}\n")
                preview(FROM, TO)
            }

            "a regression diagnostic reports an unavailable classpath" -> {
                // Truthful regression: the destination module declares a missing offline artifact, so
                // its MAIN source set has java.classpath.status=unavailable. The destination root has
                // no Java files before the move, so no classpath.unavailable error exists in the
                // baseline; after the move the destination source set gains Java files and reports a
                // NEW classpath.unavailable regression -> classpath.unavailable.
                val destPom = requireNotNull(fixtureRoot).resolve("destination/pom.xml")
                Files.writeString(destPom, destPom.readText().replace(
                    "<artifactId>destination</artifactId>",
                    "<artifactId>destination</artifactId>\n              <dependencies><dependency><groupId>fixture.external</groupId><artifactId>missing-api</artifactId><version>1</version></dependency></dependencies>",
                ))
                val emptyRepo = temporaryDirectory("rk-jvm-move-sourceroot-cp-repo")
                preview(FROM, TO, scanner = JavaProjectScanner(localMavenRepository = emptyRepo))
            }

            else -> error("Unknown condition: '$condition'")
        }
    }

    // ------------------------------------------------------------ Scenario 1 Thens

    @Then("^the adapter returns a PREVIEW patch plan for the operation moveSourceRoot$")
    fun adapterReturnsPreviewPlan() {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.PREVIEW, p.status, "${p.refusalCode}: ${p.summary}")
        assertEquals("moveSourceRoot", p.operation, p.toString())
    }

    @Then("^the plan carries a rename-only WorkspaceEdit whose entries are all FileEdit.Rename$")
    fun planCarriesRenameOnlyEdit() {
        val p = requireNotNull(plan)
        val expected = expectedRenames()
        assertEquals(expected.size, p.workspaceEdit.edits.size, "every authored compilation unit must move exactly once")
        assertEquals(expected.toSet(), p.workspaceEdit.edits.toSet(), "only the exact source-root-relative renames are allowed")
    }

    @Then("^the plan declares package declarations, imports, fully qualified type names, and source bytes unchanged$")
    fun planDeclaresIdentityUnchanged() {
        val p = requireNotNull(plan)
        val before = requireNotNull(snapshot)
        val expected = expectedRenames()
        assertEquals(
            expected.map { it.path }.toSet(),
            before.files.filter { it.path.startsWith(FROM) }.map { it.path }.toSet(),
            "the independently authored inventory must cover the whole source root",
        )
        val expectedImage = before.trackedFiles.associate { it.path to it.content }.toMutableMap()
        expected.forEach { rename ->
            assertTrue(rename.newPath !in expectedImage, "fixture destination must be absent: ${rename.newPath}")
            expectedImage[rename.newPath] = requireNotNull(expectedImage.remove(rename.path))
        }
        val staged = WorkspaceEditSimulator.apply(before, p.workspaceEdit)
        assertEquals(expectedImage, staged.trackedFiles.associate { it.path to it.content }, "complete staged image, including unrelated tracked inputs")
        expected.forEach { rename ->
            val beforeFile = requireNotNull(before.files.singleOrNull { it.path == rename.path }) { "missing source ${rename.path}" }
            val afterFile = requireNotNull(staged.files.singleOrNull { it.path == rename.newPath }) { "missing target ${rename.newPath}" }
            assertEquals(beforeFile.content, afterFile.content, "source bytes must be unchanged for ${rename.path}")
            assertEquals(
                JavaPackageUtil.extractPackage(beforeFile.content),
                JavaPackageUtil.extractPackage(afterFile.content),
                "package declaration must be unchanged for ${rename.path}",
            )
            assertEquals(
                primaryTypeIdentity(beforeFile),
                primaryTypeIdentity(afterFile),
                "fully qualified type name must be unchanged for ${rename.path}",
            )
        }
    }

    @Then("^the plan has confidence 1.0, requires user approval, and evidence STRUCTURAL$")
    fun planConfidenceApprovalEvidence() {
        val p = requireNotNull(plan)
        assertEquals(1.0, p.confidence, p.toString())
        assertTrue(p.requiresUserApproval, "expected requiresUserApproval=true: ${p.toString()}")
        assertEquals(RefactoringEvidence.STRUCTURAL, p.evidence, p.toString())
    }

    @Then("^the plan risk is LOW when source and destination share one module and provider, or MEDIUM otherwise$")
    fun planRiskMatchesFixture() {
        val p = requireNotNull(plan)
        // The fixture relocates across two distinct Maven modules (source -> destination), so the
        // owner/provider pair differs and the truthful risk is MEDIUM.
        assertEquals(RiskLevel.MEDIUM, p.riskLevel, p.toString())
    }

    @Then("^the plan lists every affected source path and destination path$")
    fun planListsEveryAffectedPath() {
        val p = requireNotNull(plan)
        val expected = expectedRenames().flatMap { listOf(it.path, it.newPath) }.toSet()
        assertEquals(6, expected.size, "three independently authored compilation units have six distinct paths")
        assertEquals(expected, p.affectedFiles, "affectedFiles must list exactly the authored source and destination paths")
    }

    // -------------------------------------------------------------- outline Thens

    @Then("^the adapter returns a REFUSED patch plan carrying the typed refusal code (.+)$")
    fun adapterReturnsRefusedWithCode(declaredCode: String) {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.REFUSED, p.status, p.toString())
        assertEquals(
            declaredCode.trim(), p.refusalCode,
            "declared refusal code '$declaredCode' did not equal the actual code '${p.refusalCode}'",
        )
        observedRefusals += ObservedRefusal(declaredCode.trim(), p.refusalCode, p.status)
    }

    @Then("^the refusal grants no approval and no managed-write eligibility$")
    fun refusalGrantsNoApprovalNoManagedWrite() {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.REFUSED, p.status, p.toString())
        assertTrue(!p.requiresUserApproval, "expected no user approval on the refusal: ${p.toString()}")
        assertEquals(0.0, p.confidence, "expected zero confidence on a refused plan: ${p.toString()}")
        assertTrue(p.authorityLease == null, "expected no managed-write authority lease: ${p.toString()}")
    }

    @Then("^the refusal leaves an empty WorkspaceEdit, an empty affected-file set, and no pending actionable plan$")
    fun refusalLeavesEmptyEditAndNoPending() {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.REFUSED, p.status, p.toString())
        assertTrue(p.workspaceEdit.edits.isEmpty(), "expected empty WorkspaceEdit: ${p.toString()}")
        assertTrue(p.affectedFiles.isEmpty(), "expected empty affected-file set: ${p.toString()}")
        assertTrue(!p.requiresUserApproval, "expected no pending actionable plan awaiting approval: ${p.toString()}")
    }

    // ------------------------------------------------------------------ helpers

    private fun preview(from: Path, to: Path, scanner: JavaProjectScanner = JavaProjectScanner()) {
        snapshot = scanner.scan(requireNotNull(fixtureRoot))
        plan = JavaMoveSourceRootPlanner(JavaLanguageAdapter()).preview(requireNotNull(snapshot), from, to)
    }

    private fun expectedRenames(): List<FileEdit.Rename> = listOf(
        "example/shared/SharedValue.java",
        "example/shared/package-info.java",
        "module-info.java",
    ).map { relative -> FileEdit.Rename(FROM.resolve(relative), TO.resolve(relative)) }

    private fun primaryTypeIdentity(file: SourceFile): String? {
        val name = file.path.fileName.toString().removeSuffix(".java")
        if (name in setOf("module-info", "package-info")) return null
        return JavaPackageUtil.fqn(JavaPackageUtil.extractPackage(file.content), name)
    }

    private fun createFile(relative: String, content: String) {
        val file = requireNotNull(fixtureRoot).resolve(relative)
        file.parent.createDirectories()
        file.writeText(content)
    }

    private fun writeFile(relative: String, content: String) {
        requireNotNull(fixtureRoot).resolve(relative).writeText(content)
    }

    private fun reactorFixture(): Path {
        val root = temporaryDirectory("rk-jvm-move-sourceroot")
        Files.writeString(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>root</artifactId><version>1</version><packaging>pom</packaging>
              <properties><java.version>21</java.version><maven.compiler.release>${'$'}{java.version}</maven.compiler.release></properties>
              <modules><module>source</module><module>destination</module></modules>
            </project>
        """.trimIndent())
        childPom(root, "source")
        childPom(root, "destination")
        val source = root.resolve(FROM)
        Files.createDirectories(source.resolve("example/shared"))
        Files.writeString(source.resolve("example/shared/SharedValue.java"), "package example.shared;\npublic record SharedValue(String value) {}\n")
        Files.writeString(source.resolve("example/shared/package-info.java"), "@Deprecated\npackage example.shared;\n")
        Files.writeString(source.resolve("module-info.java"), "module example.shared.module { exports example.shared; }\n")
        return root
    }

    private fun childPom(root: Path, module: String) {
        Files.createDirectories(root.resolve(module))
        Files.writeString(root.resolve("$module/pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion>
              <parent><groupId>example</groupId><artifactId>root</artifactId><version>1</version><relativePath>../pom.xml</relativePath></parent>
              <artifactId>$module</artifactId>
            </project>
        """.trimIndent())
    }

    private fun temporaryDirectory(prefix: String): Path {
        val base = Path.of(System.getProperty("user.dir")).resolve("build/test-tmp").toAbsolutePath().normalize()
        Files.createDirectories(base)
        return Files.createTempDirectory(base, prefix).also(temporaryDirectories::add)
    }

    private fun deleteNoFollow(root: Path) {
        if (!root.exists()) return
        // Files.walk without FOLLOW_LINKS lists symlinks as leaf paths but never descends into
        // them. Files.delete on a symlink removes the link itself (not its target), so deleting a
        // symlink encountered in our disposable fixture (e.g. the symlinkEscape case) is safe and
        // never follows outside the tree.
        val paths = Files.walk(root).use { stream -> stream.toList() }
        paths.sortedByDescending(Path::getNameCount).forEach { path ->
            Files.delete(path)
        }
    }

    @After
    fun cleanup(scenario: Scenario) {
        val reportDir = Path.of(System.getProperty("user.dir")).resolve("build/reports/cucumber")
        reportDir.createDirectories()
        val report = reportDir.resolve("java-move-source-root-characterization-codes.txt")
        val lines = mutableListOf<String>()
        lines += "scenario:${scenario.name}"
        observedRefusals.forEach { lines += "  ${it.declaredCode} -> ${it.actualCode} (${it.status})" }
        plan?.let { lines += "  plan:${it.status} refusal=${it.refusalCode ?: "-"} operation=${it.operation}" }
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
