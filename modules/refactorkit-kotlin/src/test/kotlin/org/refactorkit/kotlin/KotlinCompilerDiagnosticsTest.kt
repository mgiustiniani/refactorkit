package org.refactorkit.kotlin

import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.kotlin.bridge.KotlinCompilerBridgeMain
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KotlinCompilerDiagnosticsTest {
    @Test
    fun realK2WorkerReturnsCompilerDiagnosticsWithoutMutatingSources() {
        val root = project("class Broken(val missing: MissingType)\n")
        val source = root.resolve("src/main/kotlin/fixture/Broken.kt")
        val original = source.readText()
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)

        val result = KotlinCompilerDiagnostics(toolchain).analyze(snapshot)

        val available = assertIs<KotlinCompilerDiagnosticsResult.Available>(result)
        val unresolved = available.diagnostics.firstOrNull { it.message.contains("unresolved", ignoreCase = true) }
            ?: error(available.toString())
        assertEquals(DiagnosticEvidence.COMPILER, unresolved.evidence)
        assertEquals(org.refactorkit.core.DiagnosticLocationPrecision.LINE_ONLY, unresolved.locationPrecision)
        assertEquals(Path.of("src/main/kotlin/fixture/Broken.kt"), unresolved.location?.path)
        assertEquals(1, unresolved.location?.range?.start?.line)
        assertEquals(original, source.readText())
        assertEquals(snapshot.hash, available.attestation.snapshotHash)
        assertTrue(available.attestation.process != null)
        assertEquals(KotlinCompilerDiagnostics.BACKEND, available.attestation.backend)
    }

    @Test
    fun validKotlinSourceProducesAvailableEmptyDiagnostics() {
        val root = project("class Valid\n")
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)

        val analyzed = KotlinCompilerDiagnostics(toolchain).analyze(snapshot)
        val result = assertIs<KotlinCompilerDiagnosticsResult.Available>(analyzed, analyzed.toString())

        assertTrue(result.diagnostics.none { it.severity == org.refactorkit.core.Diagnostic.Severity.ERROR })
    }

    @Test
    fun workerTimeoutTerminatesProcessAndReturnsTypedAttestation() {
        val root = project("class Valid\n")
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)

        val result = assertIs<KotlinCompilerDiagnosticsResult.Error>(
            KotlinCompilerDiagnostics(toolchain, 100, SlowKotlinCompilerBridge::class.java).analyze(snapshot),
        )

        assertEquals("kotlin.compilerDiagnosticsTimeout", result.failure.code)
        assertTrue(result.attestation.process != null)
    }

    @Test
    fun malformedIncompleteAndMismatchedWorkerOutputFailClosed() {
        val root = project("class Valid\n")
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        val diagnostics = KotlinCompilerDiagnostics(toolchain)

        val malformed = assertIs<KotlinCompilerDiagnosticsResult.Error>(
            diagnostics.parseWorkerOutputForTest("not-json", snapshot),
        )
        assertEquals("kotlin.compilerDiagnosticsInvalid", malformed.failure.code)
        val incomplete = assertIs<KotlinCompilerDiagnosticsResult.Error>(
            diagnostics.parseWorkerOutputForTest(
                """{"schema":1,"complete":false,"failure":"kotlin.compilerOutputLimit"}""", snapshot,
            ),
        )
        assertEquals("kotlin.compilerOutputLimit", incomplete.failure.code)
        val mismatch = assertIs<KotlinCompilerDiagnosticsResult.Error>(
            diagnostics.parseWorkerOutputForTest(
                """{"schema":1,"complete":true,"snapshotHash":"${"0".repeat(64)}","exitCode":"OK","xmlBase64":""}""",
                snapshot,
            ),
        )
        assertEquals("kotlin.compilerDiagnosticsSnapshotMismatch", mismatch.failure.code)
    }

    @Test
    fun bridgeRejectsCompilerPluginsAndScriptsBeforeLoadingCompiler() {
        val root = Files.createTempDirectory("refactorkit-kotlin-bridge-rejection")
        val source = root.resolve("Source.kt").also { it.writeText("class Source\n") }
        val java = Path.of(System.getProperty("java.home"), "bin", if (isWindows()) "java.exe" else "java")
        val bridgeLocation = Path.of(KotlinCompilerBridgeMain::class.java.protectionDomain.codeSource.location.toURI())
        val process = ProcessBuilder(
            java.toString(), "-cp", bridgeLocation.toString(), KotlinCompilerBridgeMain::class.java.name,
            "a".repeat(64), root.toString(), "--", "-Xplugin=untrusted.jar", source.toString(),
        ).redirectErrorStream(true).start()

        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor())
        assertTrue(output.contains("\"complete\":false"))
        assertTrue(output.contains("kotlin.bridgeArgumentsInvalid"))
    }

    @Test
    fun scriptsPartialModelsAndToolchainDriftRefuseBeforeCompilerExecution() {
        val root = project("class Valid\n")
        root.resolve("src/main/kotlin/fixture/setup.kts").writeText("println(\"not executed\")\n")
        val toolchain = toolchain(root)
        val snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        val scripts = assertIs<KotlinCompilerDiagnosticsResult.Refused>(KotlinCompilerDiagnostics(toolchain).analyze(snapshot))
        assertEquals("kotlin.scriptSemanticsUnsupported", scripts.reason.code)
        assertEquals(null, scripts.attestation.process)

        val evidence = toolchain.provenance.evidence.single { it.role == "compiler-classpath-000" }
        Files.write(evidence.path, byteArrayOf(0), java.nio.file.StandardOpenOption.APPEND)
        val drift = assertIs<KotlinCompilerDiagnosticsResult.Refused>(KotlinCompilerDiagnostics(toolchain).analyze(snapshot))
        assertEquals("kotlin.toolchainEvidenceChanged", drift.reason.code)
        assertEquals(null, drift.attestation.process)
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows")

    private fun project(source: String): Path {
        val root = Files.createTempDirectory("refactorkit-kotlin-compiler-project")
        root.resolve("pom.xml").writeText("""
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>fixture</groupId><artifactId>compiler-fixture</artifactId><version>1</version>
              <properties><maven.compiler.release>21</maven.compiler.release></properties>
              <build><plugins><plugin>
                <groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-maven-plugin</artifactId><version>2.0.21</version>
                <configuration><jvmTarget>21</jvmTarget><jdkToolchain><version>21</version></jdkToolchain></configuration>
              </plugin></plugins></build>
            </project>
        """.trimIndent())
        root.resolve("src/main/kotlin/fixture").createDirectories()
        root.resolve("src/main/kotlin/fixture/Broken.kt").writeText("package fixture\n$source")
        return root
    }

    private fun toolchain(workspace: Path): KotlinSemanticToolchain {
        val requiredRuntimePrefixes = listOf(
            "kotlin-compiler-embeddable-2.0.21", "kotlin-stdlib-2.0.21",
            "kotlin-script-runtime-2.0.21", "kotlin-reflect-1.6.10",
            "kotlin-daemon-embeddable-2.0.21", "trove4j-1.0.20200330",
            "kotlinx-coroutines-core-jvm-1.6.4",
        )
        val runtime = System.getProperty("kotlin.compiler.test.classpath")
            .split(File.pathSeparator).map(Path::of)
            .filter { path -> Files.isRegularFile(path) && requiredRuntimePrefixes.any {
                path.fileName.toString().startsWith(it)
            } }
        val compilerSource = runtime.single { it.fileName.toString().startsWith("kotlin-compiler-embeddable-2.0.21") }
        assertEquals(requiredRuntimePrefixes.size, runtime.distinctBy { it.fileName.toString() }.size)
        val toolchainRoot = Files.createTempDirectory("refactorkit-kotlin-real-toolchain")
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
        return when (discovery) {
            is KotlinToolchainDiscovery.Available -> discovery.toolchain
            is KotlinToolchainDiscovery.Refused -> error(discovery.diagnostics.joinToString { "${it.code}: ${it.message}" })
        }
    }
}

object SlowKotlinCompilerBridge {
    @JvmStatic
    fun main(arguments: Array<String>) {
        Thread.sleep(30_000)
    }
}
