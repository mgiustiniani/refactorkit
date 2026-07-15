package org.refactorkit.jvm

import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchStatus
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.kotlin.KotlinCompilerDiagnostics
import org.refactorkit.kotlin.KotlinCompilerSymbolsResult
import org.refactorkit.kotlin.KotlinJvmBuildModelIntegration
import org.refactorkit.kotlin.KotlinLanguageAdapter
import org.refactorkit.kotlin.KotlinSemanticToolchain
import org.refactorkit.kotlin.KotlinToolchainDiscoverer
import org.refactorkit.kotlin.KotlinToolchainDiscovery
import org.refactorkit.kotlin.KotlinToolchainRequest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KotlinJavaPublicTypeRenamePlannerTest {
    @Test
    fun publicKotlinTypeRenameRequiresExplicitExternalConsumerAcceptance() {
        val fixture = fixture()
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val symbols = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(fixture.snapshot))
        val target = symbols.index.symbols.single { it.name == "PublicGreeting" }

        val plan = KotlinJavaPublicTypeRenamePlanner(adapter).preview(
            fixture.snapshot,
            target.id,
            "RenamedGreeting",
        )

        assertEquals(PatchStatus.REFUSED, plan.status)
        assertEquals("jvm.renameExternalConsumerApprovalRequired", plan.refusalCode)
        assertTrue(plan.workspaceEdit.edits.isEmpty())
    }

    @Test
    fun publicKotlinTypeRenameUsesK2AndJdtExactTokens() {
        val fixture = fixture()
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val symbols = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(fixture.snapshot))
        val target = symbols.index.symbols.single { it.name == "PublicGreeting" }

        val plan = KotlinJavaPublicTypeRenamePlanner(adapter).preview(
            fixture.snapshot,
            target.id,
            "RenamedGreeting",
            acceptExternalConsumerRisk = true,
        )

        assertEquals(PatchStatus.PREVIEW, plan.status, plan.toString())
        assertEquals(
            setOf(
                Path.of("src/main/kotlin/fixture/PublicGreeting.kt"),
                Path.of("src/main/java/fixture/Caller.java"),
            ),
            plan.affectedFiles,
        )
        val edits = plan.workspaceEdit.edits.filterIsInstance<FileEdit.Modify>().flatMap { it.textEdits }
        assertTrue(edits.size >= 5, "expected declaration plus Kotlin and Java uses, got $edits")
        assertTrue(plan.diagnosticsAfterPreview.none { it.severity == org.refactorkit.core.Diagnostic.Severity.ERROR })
    }

    @Test
    fun publicKotlinTypeRenameAppliesAndRollsBackOneMixedTransaction() {
        val fixture = fixture()
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val target = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(fixture.snapshot))
            .index.symbols.single { it.name == "PublicGreeting" }
        val planner = KotlinJavaPublicTypeRenamePlanner(adapter)
        val plan = planner.preview(
            fixture.snapshot, target.id, "RenamedGreeting", acceptExternalConsumerRisk = true,
        )
        assertEquals(PatchStatus.PREVIEW, plan.status, plan.toString())

        val applied = assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).apply(
            plan,
            fixture.snapshot,
            ApplyAuthorization.explicit("jvm-integration-test"),
            DiagnosticsGate.enabled("kotlin-k2-java-jdt", planner::diagnostics),
        ))
        assertTrue(fixture.root.resolve("src/main/kotlin/fixture/PublicGreeting.kt").toFile().readText().contains("RenamedGreeting"))
        assertTrue(fixture.root.resolve("src/main/java/fixture/Caller.java").toFile().readText().contains("RenamedGreeting"))

        assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).rollback(applied.transaction))
        assertTrue(fixture.root.resolve("src/main/kotlin/fixture/PublicGreeting.kt").toFile().readText().contains("PublicGreeting"))
        assertTrue(fixture.root.resolve("src/main/java/fixture/Caller.java").toFile().readText().contains("PublicGreeting"))
    }

    private data class Fixture(
        val root: Path,
        val snapshot: org.refactorkit.core.ProjectSnapshot,
        val toolchain: KotlinSemanticToolchain,
    )

    private fun fixture(): Fixture {
        val root = temporaryDirectory("rk-jvm-public-rename")
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
        root.resolve("src/main/kotlin/fixture/PublicGreeting.kt").apply {
            parent.createDirectories()
            writeText("""
                package fixture
                public class PublicGreeting
                public fun localGreeting(): PublicGreeting = PublicGreeting()
            """.trimIndent() + "\n")
        }
        root.resolve("src/main/java/fixture/Caller.java").apply {
            parent.createDirectories()
            writeText("""
                package fixture;
                class Caller {
                    PublicGreeting value = new PublicGreeting();
                }
            """.trimIndent() + "\n")
        }
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        return Fixture(root, snapshot, toolchain)
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
        return Files.createTempDirectory(base, prefix)
    }
}
