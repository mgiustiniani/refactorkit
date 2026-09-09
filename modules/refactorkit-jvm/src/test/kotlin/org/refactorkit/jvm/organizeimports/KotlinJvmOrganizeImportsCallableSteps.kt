package org.refactorkit.jvm.organizeimports

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.core.Diagnostic
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
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KotlinJvmOrganizeImportsCallableSteps {
    private val temporaryDirectories = mutableListOf<Path>()
    private lateinit var fixtureRoot: Path
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var toolchain: KotlinSemanticToolchain
    private var plan: PatchPlan? = null
    private var replacementText: String? = null

    @After
    fun deleteTemporaryDirectories() {
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

    @Given("a Kotlin JVM file imports the used top-level callable \"fixture.api.greeting\" and the unused type \"fixture.api.Unused\" in one contiguous import block")
    fun fixtureImportsCallableAndUnusedType() {
        val root = temporaryDirectory("rk-jvm-organize-imports-callable")
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
        root.resolve("src/main/kotlin/fixture/api/Greeting.kt").apply {
            parent.createDirectories()
            writeText(
                "package fixture.api\npublic fun greeting(): String = \"hi\"\n" +
                    "public fun greeting2(): String = \"yo\"\npublic class Unused\n",
            )
        }
        root.resolve("src/main/kotlin/fixture/app/Main.kt").apply {
            parent.createDirectories()
            // Two used top-level callables (greeting2 before greeting) deliberately non-sorted,
            // plus one unused type, all in one contiguous import block.
            writeText(
                "package fixture.app\nimport fixture.api.greeting2\nimport fixture.api.greeting\nimport fixture.api.Unused\n" +
                    "fun run(): String = greeting() + greeting2()\n",
            )
        }
        fixtureRoot = root
        toolchain = toolchain(root)
        snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
    }

    @Given("the callable \"fixture.api.greeting\" is compiler-proven used in that file")
    fun callableIsCompilerProvenUsed() {
        assertTrue(snapshot.files.any { it.path == Path.of("src/main/kotlin/fixture/app/Main.kt") })
    }

    @Given("the type \"fixture.api.Unused\" has no reference in that file")
    fun unusedTypeHasNoReference() {
        val content = fixtureRoot.resolve("src/main/kotlin/fixture/app/Main.kt").readText()
        assertTrue(
            "Unused" !in content.substringAfter("import fixture.api.Unused"),
            "expected the unused type to be referenced only by its import directive, got: $content",
        )
    }

    @When("organize-imports previews that Kotlin JVM file")
    fun organizeImportsPreviewsFile() {
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain))
        val preview = KotlinOrganizeImportsPlanner(adapter).preview(
            snapshot, Path.of("src/main/kotlin/fixture/app/Main.kt"),
        )
        plan = preview
        if (preview.status == PatchStatus.PREVIEW) {
            val staged = WorkspaceEditSimulator.apply(snapshot, preview.workspaceEdit)
            val main = staged.files.single { it.path.normalize() == Path.of("src/main/kotlin/fixture/app/Main.kt").normalize() }
            replacementText = main.content
        }
    }

    @Then("the import \"fixture.api.Unused\" is removed from the contiguous import block")
    fun unusedTypeImportRemoved() {
        val observed = replacementText ?: "(no preview replacement)"
        assertTrue(
            plan?.status == PatchStatus.PREVIEW && "import fixture.api.Unused" !in observed,
            "expected the unused type import removed in the preview; status=${plan?.status} replacement=[$observed]",
        )
    }

    @Then("the import \"fixture.api.greeting\" is preserved in the contiguous import block")
    fun callableImportPreserved() {
        val observed = replacementText ?: "(no preview replacement)"
        assertTrue(
            plan?.status == PatchStatus.PREVIEW && "import fixture.api.greeting" in observed &&
                "import fixture.api.greeting2" in observed,
            "expected the compiler-proven callable imports preserved in the preview; status=${plan?.status} replacement=[$observed]",
        )
    }

    @Then("the retained imports are sorted")
    fun retainedImportsSorted() {
        val observed = replacementText ?: "(no preview replacement)"
        val observedDirectives = observed.lineSequence()
            .map(String::trim)
            .filter { it.startsWith("import ") }
            .toList()
        val expectedSorted = listOf("import fixture.api.greeting", "import fixture.api.greeting2")
        assertTrue(
            plan?.status == PatchStatus.PREVIEW && observedDirectives == expectedSorted,
            "expected the retained import directives exactly sorted as $expectedSorted; " +
                "status=${plan?.status} observed=$observedDirectives replacement=[$observed]",
        )
    }

    @Then("no compiler error is introduced by the preview")
    fun noCompilerErrorIntroduced() {
        assertTrue(
            plan?.status == PatchStatus.PREVIEW &&
                (plan?.diagnosticsAfterPreview ?: emptyList()).none {
                    it.severity == Diagnostic.Severity.ERROR
                },
            "expected the organize-imports preview to introduce no compiler error; plan=$plan",
        )
    }

    @Then("the fixture borrows its compiler artifacts directly from the configured test classpath")
    fun borrowsCompilerArtifacts() = BorrowedCompilerArtifacts(toolchain).assertSharedAndUnchanged()

    @Then("fixture cleanup leaves the borrowed compiler artifacts unchanged")
    fun cleanupPreservesCompilerArtifacts() =
        BorrowedCompilerArtifacts(toolchain).assertPreservedBy(::deleteTemporaryDirectories)

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
        // Borrow Gradle-resolved artifacts as read-only inputs; cleanup owns only fixture workspaces.
        val compiler = compilerSource
        val classpath = runtime.filterNot { it == compilerSource }.distinctBy { it.fileName.toString() }
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

    private fun deleteNoFollow(root: Path) {
        if (!root.exists()) return
        val paths = Files.walk(root).use { stream -> stream.toList() }
        paths.sortedByDescending(Path::getNameCount).forEach { path ->
            require(!Files.isSymbolicLink(path)) { "Temporary test cleanup refuses symbolic link: $path" }
            Files.delete(path)
        }
    }
}
