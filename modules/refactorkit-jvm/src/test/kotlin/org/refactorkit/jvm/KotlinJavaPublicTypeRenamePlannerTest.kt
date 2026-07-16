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
    fun ephemeralJavaCompilationFailurePublishesNoConsumerEvidence() {
        val fixture = javaDeclarationFixture()
        fixture.root.resolve("src/main/java/fixture/PublicAccount.java").writeText(
            "package fixture; public class PublicAccount { syntax }\n",
        )
        val broken = JavaProjectScanner().scan(fixture.root)
        var consumed = false

        val result = JavaEphemeralCompiler().compile(broken) { consumed = true }

        val refusal = assertIs<JavaEphemeralCompilationResult.Refused>(result)
        assertEquals("jvm.javaCompilationFailed", refusal.code)
        assertTrue(!consumed)
    }

    @Test
    fun ephemeralJavaClassesLetK2ProveKotlinUsesOfPublicJavaType() {
        val fixture = javaDeclarationFixture()
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        var kotlinResult: org.refactorkit.kotlin.KotlinCompilerDiagnosticsResult? = null

        val compilation = JavaEphemeralCompiler().compile(fixture.snapshot) { output ->
            kotlinResult = adapter.compilerDiagnosticsWithAdditionalClasspath(fixture.snapshot, listOf(output))
        }

        val compiled = assertIs<JavaEphemeralCompilationResult.Available>(compilation, compilation.toString())
        assertTrue(compiled.outputHash.matches(Regex("[0-9a-f]{64}")))
        val available = assertIs<org.refactorkit.kotlin.KotlinCompilerDiagnosticsResult.Available>(kotlinResult, kotlinResult.toString())
        assertEquals(null, available.symbolFailure)
        val usages = available.externalTypeUsages.filter { it.jvmBinaryName == "fixture.PublicAccount" }
        assertTrue(usages.size >= 2, "expected exact K2 uses of Java binary identity, got $usages")
        assertTrue(available.attestation.ephemeralClasspathHash.matches(Regex("[0-9a-f]{64}")))
        assertTrue(available.externalCallableUsages.any {
            it.jvmOwner == "fixture.PublicAccount" && it.callableName == "label"
        }, "expected exact K2 Java-method call evidence: ${available.externalCallableUsages}")
    }

    @Test
    fun publicJavaMethodRenameUpdatesKotlinCallerAndRollsBack() {
        val fixture = javaDeclarationFixture()
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val planner = JavaKotlinPublicTypeRenamePlanner(adapter)
        val plan = planner.preview(
            fixture.snapshot,
            org.refactorkit.core.SymbolId("fixture.PublicAccount#label(java.lang.String)"),
            "describe",
            acceptExternalConsumerRisk = true,
        )

        assertEquals(PatchStatus.PREVIEW, plan.status, plan.toString())
        assertTrue(plan.workspaceEdit.edits.none { it is FileEdit.Rename })
        val applied = assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).apply(
            plan, fixture.snapshot, ApplyAuthorization.explicit("jvm-java-method-integration-test"),
            DiagnosticsGate.enabled("java-ecj-kotlin-k2-java-jdt", planner::diagnostics),
        ))
        assertTrue(Files.readString(fixture.root.resolve("src/main/java/fixture/PublicAccount.java")).contains("String describe("))
        assertTrue(Files.readString(fixture.root.resolve("src/main/kotlin/fixture/UseAccount.kt")).contains("account.describe("))
        assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).rollback(applied.transaction))
        assertTrue(Files.readString(fixture.root.resolve("src/main/java/fixture/PublicAccount.java")).contains("String label("))
        assertTrue(Files.readString(fixture.root.resolve("src/main/kotlin/fixture/UseAccount.kt")).contains("account.label("))
    }

    @Test
    fun publicJavaTypeRenameUsesJdtAndK2ExactTokens() {
        val fixture = javaDeclarationFixture()
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val plan = JavaKotlinPublicTypeRenamePlanner(adapter).preview(
            fixture.snapshot,
            org.refactorkit.core.SymbolId("fixture.PublicAccount"),
            "CustomerAccount",
            acceptExternalConsumerRisk = true,
        )

        assertEquals(PatchStatus.PREVIEW, plan.status, plan.toString())
        assertTrue(plan.workspaceEdit.edits.filterIsInstance<FileEdit.Rename>().single().let {
            it.path == Path.of("src/main/java/fixture/PublicAccount.java") &&
                it.newPath == Path.of("src/main/java/fixture/CustomerAccount.java")
        })
        assertEquals(
            setOf(
                Path.of("src/main/java/fixture/PublicAccount.java"),
                Path.of("src/main/java/fixture/CustomerAccount.java"),
                Path.of("src/main/kotlin/fixture/UseAccount.kt"),
            ),
            plan.affectedFiles,
        )
        assertTrue(plan.diagnosticsAfterPreview.none { it.severity == org.refactorkit.core.Diagnostic.Severity.ERROR })
    }

    @Test
    fun publicJavaTypeRenameAppliesAndRollsBackOneMixedTransaction() {
        val fixture = javaDeclarationFixture()
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val planner = JavaKotlinPublicTypeRenamePlanner(adapter)
        val plan = planner.preview(
            fixture.snapshot, org.refactorkit.core.SymbolId("fixture.PublicAccount"), "CustomerAccount", true,
        )
        assertEquals(PatchStatus.PREVIEW, plan.status, plan.toString())

        val applied = assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).apply(
            plan, fixture.snapshot, ApplyAuthorization.explicit("jvm-symmetric-integration-test"),
            DiagnosticsGate.enabled("java-ecj-kotlin-k2-java-jdt", planner::diagnostics),
        ))
        assertTrue(!Files.exists(fixture.root.resolve("src/main/java/fixture/PublicAccount.java")))
        assertTrue(Files.readString(fixture.root.resolve("src/main/java/fixture/CustomerAccount.java")).contains("CustomerAccount"))
        assertTrue(Files.readString(fixture.root.resolve("src/main/kotlin/fixture/UseAccount.kt")).contains("CustomerAccount"))

        assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).rollback(applied.transaction))
        assertTrue(Files.exists(fixture.root.resolve("src/main/java/fixture/PublicAccount.java")))
        assertTrue(!Files.exists(fixture.root.resolve("src/main/java/fixture/CustomerAccount.java")))
        assertTrue(Files.readString(fixture.root.resolve("src/main/kotlin/fixture/UseAccount.kt")).contains("PublicAccount"))
    }

    @Test
    fun publicKotlinFunctionRenameUpdatesJavaCallerAndRollsBack() {
        val fixture = fixture()
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(fixture.toolchain))
        val target = assertIs<KotlinCompilerSymbolsResult.Available>(adapter.compilerSymbols(fixture.snapshot))
            .index.symbols.single { it.name == "render" }
        val planner = KotlinJavaPublicTypeRenamePlanner(adapter)
        val plan = planner.preview(
            fixture.snapshot, target.id, "display", acceptExternalConsumerRisk = true,
        )

        assertEquals(PatchStatus.PREVIEW, plan.status, plan.toString())
        assertEquals(
            setOf(
                Path.of("src/main/kotlin/fixture/PublicGreeting.kt"),
                Path.of("src/main/java/fixture/Caller.java"),
            ),
            plan.affectedFiles,
        )
        val applied = assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).apply(
            plan, fixture.snapshot, ApplyAuthorization.explicit("jvm-function-integration-test"),
            DiagnosticsGate.enabled("kotlin-k2-java-jdt", planner::diagnostics),
        ))
        assertTrue(Files.readString(fixture.root.resolve("src/main/kotlin/fixture/PublicGreeting.kt")).contains("fun display"))
        assertTrue(Files.readString(fixture.root.resolve("src/main/java/fixture/Caller.java")).contains(".display("))
        assertIs<ApplyResult.Applied>(PatchEngine(fixture.root).rollback(applied.transaction))
        assertTrue(Files.readString(fixture.root.resolve("src/main/kotlin/fixture/PublicGreeting.kt")).contains("fun render"))
        assertTrue(Files.readString(fixture.root.resolve("src/main/java/fixture/Caller.java")).contains(".render("))
    }

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

    private fun javaDeclarationFixture(): Fixture {
        val root = temporaryDirectory("rk-jvm-java-declaration")
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
        root.resolve("src/main/java/fixture/PublicAccount.java").apply {
            parent.createDirectories()
            writeText("package fixture; public class PublicAccount { public String label(String value) { return value; } }\n")
        }
        root.resolve("src/main/kotlin/fixture/UseAccount.kt").apply {
            parent.createDirectories()
            writeText("package fixture\r\nfun account(): PublicAccount = PublicAccount()\r\nfun label(account: PublicAccount): String = account.label(\"x\")\r\n")
        }
        val toolchain = toolchain(root)
        return Fixture(root, KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain), toolchain)
    }

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
                public class PublicGreeting {
                    public fun render(value: String): String = value
                }
                public fun localGreeting(): PublicGreeting = PublicGreeting()
            """.trimIndent() + "\n")
        }
        root.resolve("src/main/java/fixture/Caller.java").apply {
            parent.createDirectories()
            writeText("""
                package fixture;
                class Caller {
                    PublicGreeting value = new PublicGreeting();
                    String render() { return value.render("x"); }
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
