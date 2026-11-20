package org.refactorkit.jvm.kotlinmatrix

import org.refactorkit.core.BuildModel
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SymbolId
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.jvm.KotlinJvmMoveDeclarationPlanner
import org.refactorkit.kotlin.KotlinCompilerDiagnostics
import org.refactorkit.kotlin.KotlinCompilerDiagnosticsResult
import org.refactorkit.kotlin.KotlinCompilerSymbolsResult
import org.refactorkit.kotlin.KotlinJvmBuildModelIntegration
import org.refactorkit.kotlin.KotlinJvmBuildModelProjector
import org.refactorkit.kotlin.KotlinLanguageAdapter
import org.refactorkit.kotlin.KotlinSemanticToolchain
import org.refactorkit.kotlin.KotlinToolchainDiscoverer
import org.refactorkit.kotlin.KotlinToolchainDiscovery
import org.refactorkit.kotlin.KotlinToolchainRequest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.io.path.writeText

/** Scenario-scoped fixture and response probe for Kotlin script host refusal Stories. */
class KotlinScriptSemanticsMatrixProbe {
    private var workspaceRoot: Path? = null
    private var scriptPath: Path? = null
    private var executionMarker: Path? = null
    private var baseline: Map<Path, String>? = null
    private var snapshot: ProjectSnapshot? = null
    private var buildModel: BuildModel? = null
    private var adapter: KotlinLanguageAdapter? = null
    private var adapterDiagnostics: List<org.refactorkit.core.Diagnostic>? = null
    private var diagnostics: KotlinCompilerDiagnosticsResult? = null
    private var symbols: KotlinCompilerSymbolsResult? = null
    private var plan: org.refactorkit.core.PatchPlan? = null
    private var expectGradleModel = false

    fun createStandaloneDependencyScript() {
        val root = Files.createTempDirectory("refactorkit-kotlin-standalone-script-")
        workspaceRoot = root
        root.resolve("pom.xml").writeText(
            """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>fixture</groupId><artifactId>script-host-refusal</artifactId><version>1</version>
              <properties><maven.compiler.release>21</maven.compiler.release></properties>
              <build><plugins><plugin>
                <groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-maven-plugin</artifactId><version>2.0.21</version>
                <configuration><jvmTarget>21</jvmTarget><jdkToolchain><version>21</version></jdkToolchain></configuration>
              </plugin></plugins></build>
            </project>
            """.trimIndent(),
        )
        val sourceRoot = root.resolve("src/main/kotlin/fixture")
        Files.createDirectories(sourceRoot)
        sourceRoot.resolve("Valid.kt").writeText("package fixture\nclass Valid\n")
        val script = sourceRoot.resolve("setup.kts")
        val marker = root.resolve("unexpected-script-execution")
        scriptPath = script
        executionMarker = marker
        val quotedMarker = marker.toString().replace("\\", "\\\\").replace("\"", "\\\"")
        script.writeText(
            """
            @file:DependsOn("com.example:unresolved-script-host:1.0")
            java.io.File("$quotedMarker").writeText("executed")
            """.trimIndent() + "\n",
        )
        baseline = workspaceDigest(root)
    }

    fun createStandaloneImplicitReceiverScript() {
        createStandaloneDependencyScript()
        val root = requireNotNull(workspaceRoot)
        val script = requireNotNull(scriptPath)
        val marker = requireNotNull(executionMarker)
        val quotedMarker = marker.toString().replace("\\", "\\\\").replace("\"", "\\\"")
        script.writeText(
            """
            hostProvidedReceiver.resolve("script-dependency")
            java.io.File("$quotedMarker").writeText("executed")
            """.trimIndent() + "\n",
        )
        baseline = workspaceDigest(root)
    }

    fun createGradleSettingsScript() {
        val root = Files.createTempDirectory("refactorkit-kotlin-settings-script-")
        workspaceRoot = root
        expectGradleModel = true
        root.resolve("build.gradle").writeText("plugins { id 'java' }\n")
        val settings = root.resolve("settings.gradle.kts")
        val marker = root.resolve("unexpected-settings-execution")
        scriptPath = settings
        executionMarker = marker
        val quotedMarker = marker.toString().replace("\\", "\\\\").replace("\"", "\\\"")
        settings.writeText(
            """
            pluginManagement { repositories { gradlePluginPortal() } }
            rootProject.name = "settings-script-refusal"
            java.io.File("$quotedMarker").writeText("executed")
            """.trimIndent() + "\n",
        )
        baseline = workspaceDigest(root)
    }

    fun createGradleProjectScript() {
        val root = Files.createTempDirectory("refactorkit-kotlin-project-script-")
        workspaceRoot = root
        expectGradleModel = true
        root.resolve("settings.gradle").writeText("rootProject.name = 'project-script-refusal'\n")
        val build = root.resolve("build.gradle.kts")
        val marker = root.resolve("unexpected-project-script-execution")
        scriptPath = build
        executionMarker = marker
        val quotedMarker = marker.toString().replace("\\", "\\\\").replace("\"", "\\\"")
        build.writeText(
            """
            plugins {
                id("org.jetbrains.kotlin.jvm") version "2.0.21"
            }
            java.io.File("$quotedMarker").writeText("executed")
            kotlin { jvmToolchain(21) }
            sourceSets.main { java.srcDir("src/main/kotlin") }
            """.trimIndent() + "\n",
        )
        baseline = workspaceDigest(root)
    }

    fun createGradleNestedReceiverScript(fileName: String) {
        when (fileName) {
            "settings.gradle.kts" -> createGradleSettingsScript()
            "build.gradle.kts" -> createGradleProjectScript()
            else -> error("Unsupported Gradle Kotlin DSL matrix input: $fileName")
        }
        val root = requireNotNull(workspaceRoot)
        val script = requireNotNull(scriptPath)
        val marker = requireNotNull(executionMarker)
        val quotedMarker = marker.toString().replace("\\", "\\\\").replace("\"", "\\\"")
        val content = when (fileName) {
            "settings.gradle.kts" ->
                """
                pluginManagement {
                    repositories { gradlePluginPortal() }
                    resolutionStrategy { eachPlugin { requested.id.id } }
                }
                dependencyResolutionManagement {
                    repositories { mavenCentral() }
                }
                rootProject.name = "settings-receiver-refusal"
                java.io.File("$quotedMarker").writeText("executed")
                """.trimIndent()
            else ->
                """
                plugins { id("org.jetbrains.kotlin.jvm") version "2.0.21" }
                dependencies { implementation(kotlin("stdlib")) }
                kotlin {
                    sourceSets {
                        val main by getting { kotlin.srcDir("src/main/kotlin") }
                    }
                }
                java.io.File("$quotedMarker").writeText("executed")
                """.trimIndent()
        }
        script.writeText(content + "\n")
        baseline = workspaceDigest(root)
    }

    fun createGradleProjectWithKotlinSettingsContext() {
        createGradleProjectScript()
        val root = requireNotNull(workspaceRoot)
        Files.delete(root.resolve("settings.gradle"))
        root.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement { repositories { gradlePluginPortal() } }
            rootProject.name = "gradle-context-change-refusal"
            """.trimIndent() + "\n",
        )
        baseline = workspaceDigest(root)
    }

    fun changeGradleScriptsAndReproject() {
        val root = requireNotNull(workspaceRoot)
        val buildScript = requireNotNull(scriptPath)
        val settingsScript = root.resolve("settings.gradle.kts")
        val previousSnapshotHash = requireNotNull(snapshot).hash
        val settingsBefore = Files.readString(settingsScript)
        val buildBefore = Files.readString(buildScript)
        val settingsAfter = settingsBefore.replace("gradlePluginPortal()", "mavenCentral()")
        val buildAfter = buildBefore.replace("2.0.21", "2.0.22")
        assertTrue(settingsAfter != settingsBefore, "the settings host evidence must change")
        assertTrue(buildAfter != buildBefore, "the project plugin version evidence must change")
        Files.writeString(settingsScript, settingsAfter)
        Files.writeString(buildScript, buildAfter)
        reprojectWorkspace(root)
        val current = requireNotNull(snapshot)
        assertTrue(current.hash != previousSnapshotHash, "changed Gradle host inputs must change the projected snapshot")
        val hostPaths = setOf(root.relativize(settingsScript), root.relativize(buildScript))
        for (path in hostPaths) {
            assertEquals(1, current.auxiliaryFiles.count { it.path == path }, "$path must remain one captured host input")
            assertFalse(current.files.any { it.path == path }, "$path must not acquire Kotlin/JVM source ownership")
        }
        baseline = workspaceDigest(root)
    }

    fun inspectWorkspace() {
        val root = requireNotNull(workspaceRoot) { "standalone script fixture was not created" }
        reprojectWorkspace(root)
    }

    fun changeStandaloneDependencyAndReproject() {
        val root = requireNotNull(workspaceRoot)
        val input = requireNotNull(scriptPath)
        val previousSnapshotHash = requireNotNull(snapshot).hash
        val before = Files.readString(input)
        val after = before.replace("unresolved-script-host:1.0", "unresolved-script-host:2.0")
        assertTrue(after != before, "the standalone fixture must expose a versioned host dependency")
        Files.writeString(input, after)
        reprojectWorkspace(root)
        assertTrue(requireNotNull(snapshot).hash != previousSnapshotHash, "a changed script input must change the projected snapshot")
        baseline = workspaceDigest(root)
    }

    private fun reprojectWorkspace(root: Path) {
        val toolchain = discoverToolchain(root)
        val scanned = JavaProjectScanner(allowNetworkDependencyResolution = false).scan(root)
        assertTrue(scanned.trackedFiles.any { it.path == root.relativize(requireNotNull(scriptPath)) })
        snapshot = KotlinJvmBuildModelIntegration.attach(scanned, toolchain)
        buildModel = requireNotNull(snapshot).buildModels.single {
            it.providerId == KotlinJvmBuildModelProjector.PROVIDER_ID
        }
        if (expectGradleModel) {
            val gradleModel = requireNotNull(snapshot).buildModels.single { it.providerId == "gradle-declarative-v1" }
            assertEquals("denied", gradleModel.attributes["buildCodeExecution"])
            assertEquals("denied", gradleModel.attributes["networkAccess"])
        }

        val configuredAdapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain))
        adapter = configuredAdapter
        adapterDiagnostics = configuredAdapter.diagnostics(requireNotNull(snapshot))
        diagnostics = configuredAdapter.compilerDiagnostics(requireNotNull(snapshot))
        symbols = configuredAdapter.compilerSymbols(requireNotNull(snapshot))
        plan = KotlinJvmMoveDeclarationPlanner(configuredAdapter).preview(
            requireNotNull(snapshot),
            SymbolId("fixture:setup"),
            "fixture.moved",
            acceptExternalConsumerRisk = true,
        )
    }

    fun requireTypedRefusalBeforeCompiler(expectedCode: String) {
        assertTrue(
            requireNotNull(buildModel).diagnostics.any { it.code == expectedCode },
            "expected the Kotlin/JVM build projection to retain $expectedCode",
        )
        assertTrue(requireNotNull(adapterDiagnostics).any { it.code == expectedCode })
        val diagnosticsResult = assertIs<KotlinCompilerDiagnosticsResult.Refused>(requireNotNull(diagnostics))
        assertEquals(expectedCode, diagnosticsResult.reason.code)
        assertEquals(null, diagnosticsResult.attestation.process)
        val symbolsResult = assertIs<KotlinCompilerSymbolsResult.Refused>(requireNotNull(symbols))
        assertEquals(expectedCode, symbolsResult.reason.code)
        assertEquals(null, symbolsResult.attestation.process)
        assertEquals(expectedCode, requireNotNull(plan).refusalCode)
        assertEquals(PatchStatus.REFUSED, requireNotNull(plan).status)
    }

    fun requireNoSemanticAuthority() {
        val root = requireNotNull(workspaceRoot)
        val sourcePath = requireNotNull(scriptPath)
        val configuredAdapter = requireNotNull(adapter)
        val currentSnapshot = requireNotNull(snapshot)
        val refusedPlan = requireNotNull(plan)
        assertTrue(currentSnapshot.trackedFiles.any { it.path == root.relativize(sourcePath) })
        assertTrue(configuredAdapter.buildSymbols(currentSnapshot).symbols.isEmpty())
        assertTrue(configuredAdapter.findReferences(SymbolId("fixture:setup")).isEmpty())
        assertTrue(refusedPlan.workspaceEdit.edits.isEmpty())
        assertTrue(refusedPlan.affectedFiles.isEmpty())
        assertEquals(null, refusedPlan.authorityLease)
        assertEquals(0.0, refusedPlan.confidence)
        assertFalse(Files.exists(requireNotNull(executionMarker)), "the script body must not execute")
        assertEquals(requireNotNull(baseline), workspaceDigest(root), "inspection must preserve fixture bytes")
    }

    fun requireGradleScriptCapturedAsHostInput(fileName: String) {
        val root = requireNotNull(workspaceRoot)
        val sourcePath = requireNotNull(scriptPath)
        val currentSnapshot = requireNotNull(snapshot)
        val relativePath = root.relativize(sourcePath)
        assertTrue(expectGradleModel, "this host-input assertion applies only to Gradle DSL scripts")
        assertEquals(fileName, sourcePath.fileName.toString())
        val hostInput = assertNotNull(
            currentSnapshot.auxiliaryFiles.singleOrNull { it.path == relativePath },
            "$fileName must be captured as one auxiliary build-host input",
        )
        assertEquals("kotlin", hostInput.languageId)
        assertFalse(
            currentSnapshot.files.any { it.path == relativePath },
            "$fileName must not acquire Kotlin/JVM source ownership",
        )
        val gradleModel = assertNotNull(
            currentSnapshot.buildModels.singleOrNull { it.providerId == "gradle-declarative-v1" },
            "the Gradle host must remain visible as a declarative, non-executing model",
        )
        assertEquals("denied", gradleModel.attributes["buildCodeExecution"])
        assertEquals("denied", gradleModel.attributes["networkAccess"])
    }

    fun close() {
        workspaceRoot?.let { root ->
            if (Files.exists(root)) {
                Files.walk(root).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { path ->
                        require(!Files.isSymbolicLink(path)) { "cleanup refuses symbolic link: $path" }
                        Files.deleteIfExists(path)
                    }
                }
            }
        }
        workspaceRoot = null
    }

    private fun discoverToolchain(workspace: Path): KotlinSemanticToolchain {
        val requiredPrefixes = listOf(
            "kotlin-compiler-embeddable-2.0.21", "kotlin-stdlib-2.0.21",
            "kotlin-script-runtime-2.0.21", "kotlin-reflect-1.6.10",
            "kotlin-daemon-embeddable-2.0.21", "trove4j-1.0.20200330",
            "kotlinx-coroutines-core-jvm-1.6.4", "annotations-13.0",
        )
        val classpath = requireNotNull(System.getProperty("kotlin.compiler.test.classpath"))
            .split(File.pathSeparator).map(Path::of)
            .filter { path -> Files.isRegularFile(path) && requiredPrefixes.any { path.fileName.toString().startsWith(it) } }
        val compiler = classpath.single { it.fileName.toString().startsWith("kotlin-compiler-embeddable-2.0.21") }
        val runtime = classpath.filterNot { it == compiler }.distinctBy { it.fileName.toString() }
        val discovery = KotlinToolchainDiscoverer().discover(KotlinToolchainRequest(
            workspaceRoot = workspace,
            jdkHome = Path.of(System.getProperty("java.home")),
            compilerJar = compiler,
            compilerClasspath = runtime,
        ))
        return assertIs<KotlinToolchainDiscovery.Available>(discovery).toolchain
    }

    private fun workspaceDigest(root: Path): Map<Path, String> = Files.walk(root).use { paths ->
        paths.filter(Files::isRegularFile).toList().associate { path ->
            root.relativize(path) to MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
                .joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}
