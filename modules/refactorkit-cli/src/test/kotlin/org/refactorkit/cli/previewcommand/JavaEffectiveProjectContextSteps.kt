package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.And
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.TransactionLog
import org.refactorkit.daemon.DaemonSession
import org.refactorkit.java.JavaLanguageAdapter
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.java.JdtAuthoritativeJavaAnalysisResolution
import org.refactorkit.java.JdtJavaSemanticAnalyzer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class JavaEffectiveProjectContextSteps {
    private var container: Path? = null
    private var session: DaemonSession? = null
    private var reactorSession: DaemonSession? = null
    private lateinit var reactorHash: String
    private lateinit var root: Path
    private lateinit var previousHash: String
    private lateinit var baseline: Map<Path, String>
    private lateinit var response: JsonObject
    private lateinit var mavenJava26SdkHome: Path
    private lateinit var mavenJava27SdkHome: Path
    private var java27SemanticOperation: String? = null
    private var java27SemanticQueryResponse: JsonObject? = null
    private var java27SemanticPreviewFailure: JsonRpcException? = null
    private lateinit var sourceSelectionResponse: JsonObject
    private var expectedMavenModuleArtifact: Path? = null
    private lateinit var jpmsMetadataResponse: JsonObject
    private var failure: JsonRpcException? = null
    private var generated = false
    private lateinit var mavenFailsafeOwnership: String
    private lateinit var mavenTestJvmShape: String
    private lateinit var mavenJvmBuildSourceSets: List<org.refactorkit.core.BuildSourceSet>
    private lateinit var mavenFailsafeSelection: String
    private lateinit var mavenFailsafeRequestedKind: String
    private var mavenFailsafeModuleResponses: List<Triple<String, JsonObject, JsonObject>> = emptyList()
    private var checkedInReadOnlyRoot: Path? = null
    private var checkedInSampleRoot: Path? = null
    private var checkedInSampleBaseline: Map<Path, String>? = null
    private var providerFixtureBaseline: Map<Path, String> = emptyMap()
    private var buildHelperSourceSetIds: List<String> = emptyList()
    private var buildHelperV2ModuleIds: Set<String> = emptySet()
    private var buildHelperRootModuleOwners: Set<String> = emptySet()
    private lateinit var artifactWorkspaceRoot: Path
    private lateinit var artifactRepositoryRoot: Path
    private lateinit var relocatedArtifactJar: Path
    private lateinit var artifactInputBaseline: Map<String, String>
    private lateinit var artifactSnapshot: org.refactorkit.core.ProjectSnapshot
    private lateinit var artifactDiagnostics: List<Diagnostic>
    private lateinit var duplicateSymbolDiagnostics: List<Diagnostic>
    private lateinit var customTestSourceSnapshot: org.refactorkit.core.ProjectSnapshot
    private lateinit var customTestSourceDiagnostics: List<Diagnostic>
    private lateinit var customTestSourceBaseline: Map<Path, String>
    private lateinit var sourceOnlyPlatformSnapshot: org.refactorkit.core.ProjectSnapshot
    private var sourceOnlyPlatformResolution: JdtAuthoritativeJavaAnalysisResolution? = null
    private var mavenCoreJar: Path? = null
    private var staleMavenCore = false
    private var expectedLifecycleCompiler: Pair<String, String>? = null
    private lateinit var addModuleSnapshotHash: String
    private lateinit var addModuleOutsidePath: Path
    private var addModuleRefusalCodes: List<String> = emptyList()
    private var addModuleCapability: JsonObject? = null
    private var addModuleStructuralContext: JsonObject? = null
    private var addModuleLifecyclePreview: JsonObject? = null
    private var addModuleLifecycleApplied: JsonObject? = null
    private var addModuleLifecycleRollback: JsonObject? = null
    private var addModuleLifecycleFailure: JsonRpcException? = null
    private var addModuleOfflineBuildExit: Int? = null
    private var addModuleOfflineBuildOutput: String = ""
    @Given("a Maven Add Module fixture with declared parent coordinates and an occupied child")
    fun addModuleRefusalFixture() {
        val owned = Files.createTempDirectory("rk-add-module-refusal-")
        container = owned
        root = owned.resolve("workspace")
        addModuleOutsidePath = owned.resolve("outside")
        addModuleStructuralContext = null
        Files.createDirectories(root.resolve("fixture-repository"))
        write(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture.addmodule</groupId>
              <artifactId>declared-parent-artifact</artifactId><version>2.7.1</version><packaging>pom</packaging>
              <properties><maven.compiler.release>8</maven.compiler.release></properties>
              <build><plugins>
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-resources-plugin</artifactId><version>3.5.0</version></plugin>
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin>
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.6</version></plugin>
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-jar-plugin</artifactId><version>3.5.1</version></plugin>
              </plugins></build>
              <modules><module>occupied</module></modules>
            </project>
        """.trimIndent() + "\n")
        write(root.resolve("occupied/pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion>
              <parent><groupId>fixture.addmodule</groupId><artifactId>declared-parent-artifact</artifactId>
                <version>2.7.1</version><relativePath>../pom.xml</relativePath></parent>
              <artifactId>occupied</artifactId>
            </project>
        """.trimIndent() + "\n")
        write(root.resolve("occupied/src/main/java/fixture/Existing.java"), "package fixture; class Existing {}\n")
        write(root.resolve("settings.gradle"), "rootProject.name='add-module-partial-context'; include 'gradle-observer'\n")
        write(root.resolve("gradle-observer/build.gradle"), "apply plugin: 'java'\n")
        write(root.resolve("gradle-observer/src/main/java/observer/Observer.java"),
            "package observer; public class Observer {}\n")
        baseline = contents(root)
        session = DaemonSession()
        addModuleSnapshotHash = requireNotNull(session).dispatch("project.open", buildJsonObject {
            put("root", root.toString())
            put("mavenRepository", root.resolve("fixture-repository").toString())
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
    }

    @When("daemon Add Module preview is attempted for each unsafe or occupied module name")
    fun attemptUnsafeAddModuleNames() {
        val names = listOf("../escape", "nested/module", addModuleOutsidePath.toString(), "occupied")
        addModuleRefusalCodes = names.map { name ->
            val refusal = assertFailsWith<JsonRpcException>(name) {
                requireNotNull(session).dispatch("refactor.preview", buildJsonObject {
                    put("operation", "createMavenModule")
                    put("languageId", "java")
                    put("expectedSnapshotHash", addModuleSnapshotHash)
                    put("arguments", buildJsonObject {
                        put("moduleName", name)
                        put("parentPom", "pom.xml")
                    })
                })
            }
            refusal.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content ?: "missing-refusal-code"
        }
    }

    @Then("every Add Module request is refused before a plan is retained and all workspace bytes stay unchanged")
    fun addModuleRefusalsPreserveWorkspace() {
        assertEquals(4, addModuleRefusalCodes.size)
        assertEquals("java.createMavenModule.invalidModuleName", addModuleRefusalCodes[0])
        assertEquals("java.createMavenModule.invalidModuleName", addModuleRefusalCodes[1])
        assertEquals("java.createMavenModule.invalidModuleName", addModuleRefusalCodes[2])
        assertEquals("java.createMavenModule.destinationExists", addModuleRefusalCodes[3])
        assertEquals(baseline, contents(root))
        assertFalse(Files.exists(addModuleOutsidePath))
    }

    @When("daemon Add Module preview is applied for module {string}")
    fun applyAddModule(moduleName: String) {
        val active = requireNotNull(session)
        addModuleCapability = active.dispatch("server.capabilities", JsonObject(emptyMap())).jsonObject
            .getValue("methods").jsonArray.map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == "java.createMavenModule" }
        try {
            addModuleLifecyclePreview = active.dispatch("refactor.preview", buildJsonObject {
                put("operation", "createMavenModule")
                put("languageId", "java")
                put("expectedSnapshotHash", addModuleSnapshotHash)
                addModuleStructuralContext?.let { put("javaContext", it) }
                put("arguments", buildJsonObject {
                    put("moduleName", moduleName)
                    put("parentPom", "pom.xml")
                })
            }).jsonObject
            addModuleLifecycleApplied = active.dispatch("refactor.apply", buildJsonObject {
                put("planId", requireNotNull(addModuleLifecyclePreview).getValue("planId").jsonPrimitive.content)
                put("expectedSnapshotHash", addModuleSnapshotHash)
                addModuleStructuralContext?.let { put("javaContext", it) }
            }).jsonObject
            if (addModuleLifecycleApplied?.get("status")?.jsonPrimitive?.content == "applied") {
                try {
                    addModuleOfflineBuildExit = buildAppliedReactorOffline()
                } catch (failure: Exception) {
                    addModuleOfflineBuildExit = -5
                    addModuleOfflineBuildOutput = "Offline Maven verification setup failed (${failure::class.simpleName})"
                }
                addModuleLifecycleRollback = active.dispatch("patch.rollback", buildJsonObject {
                    put("transactionId", requireNotNull(addModuleLifecycleApplied)
                        .getValue("transactionId").jsonPrimitive.content)
                    put("expectedSnapshotHash", requireNotNull(addModuleLifecycleApplied)
                        .getValue("snapshotHash").jsonPrimitive.content)
                    addModuleStructuralContext?.let { put("javaContext", it) }
                }).jsonObject
            }
        } catch (failure: JsonRpcException) {
            addModuleLifecycleFailure = failure
        }
    }

    @When("daemon Add Module preview is applied for module {string} with the partial Java context")
    fun applyAddModuleWithPartialJavaContext(moduleName: String) {
        val active = requireNotNull(session)
        val context = active.dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", addModuleSnapshotHash)
            put("sdkHome", System.getProperty("java.home"))
        }).jsonObject
        assertEquals("partial", context.getValue("status").jsonPrimitive.content, context.toString())
        val gradleProvider = context.getValue("providers").jsonArray.map { it.jsonObject }
            .single { it.getValue("providerId").jsonPrimitive.content == "gradle-declarative-v1" }
        assertEquals("partial", gradleProvider.getValue("status").jsonPrimitive.content)
        assertEquals("denied", gradleProvider.getValue("buildCodeExecution").jsonPrimitive.content)
        assertEquals("denied", gradleProvider.getValue("networkAccess").jsonPrimitive.content)
        assertFalse(Files.exists(root.resolve("build-code-ran")))
        addModuleStructuralContext = buildJsonObject {
            put("sdkHome", System.getProperty("java.home"))
            put("expectedContextHash", context.getValue("contextHash").jsonPrimitive.content)
            put("contextSchemaVersion", context.getValue("schemaVersion").jsonPrimitive.content)
        }
        applyAddModule(moduleName)
    }

    @Then("the Add Module preview uses declared parent coordinates and only contained generated paths")
    fun addModulePreviewUsesDeclaredParentCoordinates() {
        assertEquals(null, addModuleLifecycleFailure?.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content,
            addModuleLifecycleFailure?.message.orEmpty())
        val capability = requireNotNull(addModuleCapability)
        assertEquals("stable", capability.getValue("stability").jsonPrimitive.content)
        val features = capability.getValue("features").jsonObject
        listOf("preview", "renderedDiff", "apply", "rollback").forEach {
            assertEquals("true", features.getValue(it).jsonPrimitive.content)
        }
        val preview = requireNotNull(addModuleLifecyclePreview)
        assertEquals("PREVIEW", preview.getValue("status").jsonPrimitive.content)
        assertEquals("java.createMavenModule", preview.getValue("operation").jsonPrimitive.content)
        val paths = preview.getValue("affectedFiles").jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(setOf("pom.xml", "reports/pom.xml"), paths)
        assertTrue(paths.all { !it.startsWith('/') && !Path.of(it).normalize().startsWith("..") })
        val diff = preview.getValue("renderedDiff").jsonPrimitive.content
        assertTrue(diff.contains("<groupId>fixture.addmodule</groupId>"), diff)
        assertTrue(diff.contains("<artifactId>declared-parent-artifact</artifactId>"), diff)
        assertTrue(diff.contains("<version>2.7.1</version>"), diff)
        assertFalse(diff.contains("<artifactId>workspace</artifactId>"), diff)
        assertEquals("applied", requireNotNull(addModuleLifecycleApplied).getValue("status").jsonPrimitive.content)
        if (addModuleStructuralContext != null) {
            val expectedContext = requireNotNull(addModuleStructuralContext)
            assertEquals(expectedContext.getValue("expectedContextHash"), preview.getValue("contextHash"))
            assertEquals(expectedContext.getValue("contextSchemaVersion"), preview.getValue("contextSchemaVersion"))
            assertTrue(preview.getValue("platformIdentities").jsonArray.isNotEmpty())
            assertEquals(expectedContext.getValue("expectedContextHash"),
                requireNotNull(addModuleLifecycleApplied).getValue("appliedContextHash"))
        }
    }

    @Then("the applied reactor builds offline in a disposable copy and normal rollback restores exact inputs")
    fun offlineBuildAndRollbackRestoreExactInputs() {
        assertEquals(0, addModuleOfflineBuildExit, addModuleOfflineBuildOutput.takeLast(4_000))
        assertEquals("rolledBack", requireNotNull(addModuleLifecycleRollback).getValue("status").jsonPrimitive.content)
        addModuleStructuralContext?.let { expectedContext ->
            assertEquals(expectedContext.getValue("expectedContextHash"),
                requireNotNull(addModuleLifecycleRollback).getValue("rolledBackContextHash"))
        }
        assertFalse(Files.exists(root.resolve("reports")))
        val ignored = setOf(".refactorkit", "target", "build", ".gradle")
        val expected = baseline.filterKeys { path -> path.none { it.toString() in ignored } }
        val actual = contents(root).filterKeys { path -> path.none { it.toString() in ignored } }
        assertEquals(expected, actual)
    }

    private fun buildAppliedReactorOffline(): Int {
        val owned = requireNotNull(container)
        val disposable = owned.resolve("offline-reactor")
        copyWorkspaceForOfflineBuild(root, disposable)
        val settings = owned.resolve("empty-maven-settings.xml")
        write(settings, "<settings/>\n")
        val globalSettings = owned.resolve("empty-maven-global-settings.xml")
        write(globalSettings, "<settings/>\n")
        val repositorySource = Path.of(System.getProperty("user.home"), ".m2", "repository")
        if (!Files.isDirectory(repositorySource, LinkOption.NOFOLLOW_LINKS)) {
            addModuleOfflineBuildOutput = "Offline Maven repository is unavailable"
            return -2
        }
        val repository = owned.resolve("offline-maven-repository")
        copyMavenRepositoryBounded(repositorySource, repository)
        val outputFile = owned.resolve("offline-maven-build.log")
        val maven = System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator)
            .map { Path.of(it).resolve("mvn") }.firstOrNull { Files.isExecutable(it) }
            ?: run { addModuleOfflineBuildOutput = "Maven executable is unavailable"; return -3 }
        val builder = ProcessBuilder(
            maven.toString(), "-o", "-B", "-ntp", "-q",
            "-s", settings.toString(), "-gs", globalSettings.toString(),
            "-Dmaven.repo.local=$repository", "-f", disposable.resolve("pom.xml").toString(), "verify",
        ).directory(disposable.toFile()).redirectErrorStream(true).redirectOutput(outputFile.toFile())
        builder.environment().clear()
        builder.environment()["PATH"] = System.getenv("PATH").orEmpty()
        builder.environment()["JAVA_HOME"] = System.getProperty("java.home")
        builder.environment()["MAVEN_OPTS"] = "-Duser.home=${owned.resolve("empty-maven-home")}"
        val process = builder.start()
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            addModuleOfflineBuildOutput = "Offline Maven verify timed out"
            return -4
        }
        addModuleOfflineBuildOutput = Files.readString(outputFile).takeLast(8_000)
        return process.exitValue()
    }

    private fun copyWorkspaceForOfflineBuild(source: Path, destination: Path) {
        Files.walk(source).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }
                .filter { file -> source.relativize(file).none { it.toString() in setOf(".refactorkit", "target", "build", ".gradle", ".git") } }
                .forEach { file ->
                    val target = destination.resolve(source.relativize(file).toString())
                    Files.createDirectories(target.parent)
                    Files.copy(file, target)
                }
        }
    }

    private fun copyMavenRepositoryBounded(source: Path, destination: Path) {
        var entries = 0
        var bytes = 0L
        Files.walk(source).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }
                .forEach { file ->
                    require(++entries <= 500_000) { "Offline Maven repository exceeds the bounded artifact count" }
                    bytes += Files.size(file)
                    require(bytes <= 8L * 1024 * 1024 * 1024) { "Offline Maven repository exceeds the bounded copy size" }
                    val target = destination.resolve(source.relativize(file).toString())
                    Files.createDirectories(target.parent)
                    if (file.fileName.toString() == "_remote.repositories") {
                        // The disposable build uses empty settings and Maven's default Central. Its copied cache
                        // can contain Resolver's hashed identity for that same default repository, which would
                        // otherwise make a strictly offline lookup miss artifacts already present in the copy.
                        val marker = Files.readString(file)
                        val normalized = Regex(">central-[a-f0-9]{40}=").replace(marker, ">central=")
                        Files.writeString(target, normalized)
                    } else {
                        Files.copy(file, target)
                    }
                }
        }
    }

    @Given("a Maven Java project declares only source level {string} for authoritative analysis")
    fun sourceOnlyPlatformFixture(sourceLevel: String) {
        assertEquals("8", sourceLevel)
        val owned = Files.createTempDirectory("rk-source-only-platform-")
        container = owned
        root = owned.resolve("workspace")
        write(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture.platform</groupId><artifactId>source-only</artifactId>
              <version>1</version><properties><maven.compiler.source>$sourceLevel</maven.compiler.source></properties>
            </project>
        """.trimIndent() + "\n")
        write(root.resolve("src/main/java/example/SourceOnly.java"), "package example; class SourceOnly {}\n")
        baseline = contents(owned)
        sourceOnlyPlatformSnapshot = JavaProjectScanner().scan(root)
        val sourceSet = sourceOnlyPlatformSnapshot.buildModels.single { it.providerId == "maven-effective-v1" }
            .modules.single().sourceSets.single { it.id == "main" }
        assertEquals(sourceLevel, sourceSet.attributes["java.sourceLevel"])
        assertEquals(null, sourceSet.attributes["java.release"])
        assertEquals("explicit-jdk-required", sourceSet.attributes["java.platformSelection"])
    }

    @When("JDT authoritative analysis is requested without a selected platform")
    fun analyzeSourceOnlyWithoutPlatform() {
        sourceOnlyPlatformResolution = JdtJavaSemanticAnalyzer()
            .analyzeAuthoritatively(sourceOnlyPlatformSnapshot, emptyMap())
    }

    @Then("the analysis refuses with {string} before host platform fallback")
    fun sourceOnlyPlatformRefusal(expectedCode: String) {
        val refusal = assertIs<JdtAuthoritativeJavaAnalysisResolution.Refused>(
            requireNotNull(sourceOnlyPlatformResolution),
        )
        assertEquals(expectedCode, refusal.code)
        assertEquals("java.platform.explicitJdkRequired", refusal.code)
        assertTrue(refusal.message.contains("explicit qualified JDK platform"), refusal.message)
    }

    @When("the IDE requests capabilities from a fresh daemon session")
    fun discoverMavenRuntimeSelection() {
        DaemonSession().use { daemon ->
            response = daemon.dispatch("server.capabilities", null).jsonObject
        }
    }

    @Then("project open advertises Maven runtime selection as a read-only capability")
    fun mavenRuntimeSelectionAdvertised() {
        val projectOpen = response.getValue("methods").jsonArray.map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == "project.open" }
        assertEquals(JsonPrimitive("beta-contract"), projectOpen["stability"])
        assertEquals(JsonPrimitive(false), projectOpen["requiresProject"])
        assertEquals(JsonPrimitive(false), projectOpen["writesWorkspace"])
        val features = assertNotNull(projectOpen["features"], "project.open must advertise Maven runtime selection")
        assertEquals(JsonPrimitive(true), features.jsonObject["mavenRuntime"])
    }

    @Given("a Maven source-target 17 project with {string} compiler binding")
    fun mavenCompilerAuthorityFixture(binding: String) {
        val compiler = when (binding) {
            "implicit", "Maven 3.9.11 runtime", "Maven 3.9.16 runtime", "Maven runtime stale" -> ""
            "pinned" -> """
                <build><plugins><plugin>
                  <artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version>
                </plugin></plugins></build>
            """.trimIndent()
            else -> error("Unexpected compiler binding: $binding")
        }
        val owned = Files.createTempDirectory("rk-maven-compiler-authority-")
        container = owned
        root = owned.resolve("workspace")
        val repository = Files.createDirectory(owned.resolve("repository"))
        staleMavenCore = binding == "Maven runtime stale"
        expectedLifecycleCompiler = when (binding) {
            "Maven 3.9.11 runtime", "Maven runtime stale" -> "3.9.11" to "3.13.0"
            "Maven 3.9.16 runtime" -> "3.9.16" to "3.15.0"
            else -> null
        }
        mavenCoreJar = expectedLifecycleCompiler?.let { (maven, plugin) ->
            owned.resolve("maven-core-$maven.jar").also { jar ->
                JarOutputStream(Files.newOutputStream(jar)).use { output ->
                    fun entry(name: String, body: String) {
                        output.putNextEntry(JarEntry(name))
                        output.write(body.toByteArray(Charsets.UTF_8))
                        output.closeEntry()
                    }
                    entry("META-INF/maven/org.apache.maven/maven-core/pom.properties",
                        "groupId=org.apache.maven\nartifactId=maven-core\nversion=$maven\n")
                    entry("META-INF/plexus/default-bindings.xml", """
                        <component-set><components><component>
                          <role>org.apache.maven.lifecycle.mapping.LifecycleMapping</role>
                          <role-hint>jar</role-hint>
                          <implementation>org.apache.maven.lifecycle.mapping.DefaultLifecycleMapping</implementation>
                          <configuration><lifecycles><lifecycle><id>default</id><phases>
                            <compile>org.apache.maven.plugins:maven-compiler-plugin:$plugin:compile</compile>
                            <test-compile>org.apache.maven.plugins:maven-compiler-plugin:$plugin:testCompile</test-compile>
                          </phases></lifecycle></lifecycles></configuration>
                        </component></components></component-set>
                    """.trimIndent())
                }
            }
        }
        write(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture.context</groupId>
              <artifactId>compiler-authority</artifactId><version>1</version>
              <properties><maven.compiler.source>17</maven.compiler.source>
                <maven.compiler.target>17</maven.compiler.target></properties>
              $compiler
            </project>
        """.trimIndent() + "\n")
        write(root.resolve("src/main/java/example/CompilerAuthority.java"),
            "package example; class CompilerAuthority {}\n")
        write(root.resolve("src/test/java/example/CompilerAuthorityTest.java"),
            "package example; class CompilerAuthorityTest { CompilerAuthority owner; }\n")
        session = DaemonSession()
        previousHash = requireNotNull(session).dispatch("project.open", buildJsonObject {
            put("root", root.toString())
            put("mavenRepository", repository.toString())
            mavenCoreJar?.let { jar ->
                put("mavenRuntime", buildJsonObject {
                    put("coreJar", jar.toString())
                    put("sha256", fileSha256(jar))
                })
            }
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        baseline = contents(owned)
    }

    @When("public Maven project context is requested with {string} SDK selection")
    fun requestMavenCompilerAuthority(sdk: String) {
        if (staleMavenCore) {
            Files.writeString(requireNotNull(mavenCoreJar), "changed after project.open")
            baseline = contents(requireNotNull(container))
        }
        response = requireNotNull(session).dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", previousHash)
            when (sdk) {
                "selected" -> put("sdkHome", System.getProperty("java.home"))
                "omitted" -> Unit
                else -> error("Unexpected SDK selection: $sdk")
            }
        }).jsonObject
    }

    @Then("the Maven compiler authority response is {string} with refusal {string}")
    fun mavenCompilerAuthorityResponse(status: String, expectedRefusal: String) {
        assertEquals("java-project-context-v2", response.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals(status, response.getValue("status").jsonPrimitive.content, response.toString())
        if (expectedRefusal == "none") {
            assertEquals(JsonNull, response.getValue("refusal"))
            assertNotNull(response["analysis"] as? JsonObject)
            expectedLifecycleCompiler?.let { (maven, plugin) ->
                val jar = requireNotNull(mavenCoreJar)
                val origin = "maven-core:$maven:jar-default:sha256=${fileSha256(jar)}"
                val sets = response.getValue("modules").jsonArray.single().jsonObject.getValue("sourceSets").jsonArray
                assertEquals(setOf("main", "test"), sets.map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet())
                sets.forEach { value ->
                    val options = value.jsonObject.getValue("compilerOptions").jsonArray.map { it.jsonObject }
                    val version = options.single { it.getValue("name").jsonPrimitive.content == "pluginVersion" }
                    assertEquals(plugin, version.getValue("value").jsonPrimitive.content)
                    assertEquals(origin, version.getValue("origin").jsonPrimitive.content)
                }
                assertTrue(response.getValue("buildEvidence").jsonArray.any {
                    val input = it.jsonObject
                    input.getValue("path").jsonPrimitive.content == jar.toString() &&
                        input.getValue("kind").jsonPrimitive.content == "effective-model-input"
                })
            }
            return
        }
        val refusal = response.getValue("refusal").jsonObject
        assertEquals(expectedRefusal, refusal.getValue("code").jsonPrimitive.content, response.toString())
        if (expectedRefusal == "java.context.compilerOptionsIncomplete") {
            assertTrue(refusal.getValue("message").jsonPrimitive.content.contains("effective compiler options"))
        }
        assertTrue(response.getValue("modules").jsonArray.isEmpty())
    }

    @Given("an explicitly synchronized Maven and Gradle fixture where both providers expose module {string} source set {string} for {string}")
    fun providerQualifiedCollisionFixture(moduleId: String, sourceSetId: String, sourceRelativePath: String) {
        assertEquals(":app", moduleId)
        assertEquals("main", sourceSetId)
        assertEquals("pkg/Runner.java", sourceRelativePath)
        val owned = Files.createTempDirectory("rk-provider-context-"); container = owned
        root = owned.resolve("workspace")
        val mavenModule = root.resolve(":app")
        val gradleModule = root.resolve("gradle-app")
        write(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>root</artifactId>
              <version>1</version><packaging>pom</packaging><modules><module>:app</module></modules>
            </project>
        """.trimIndent() + "\n")
        write(mavenModule.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><parent><groupId>fixture</groupId><artifactId>root</artifactId>
              <version>1</version><relativePath>../pom.xml</relativePath></parent><artifactId>maven-app</artifactId>
              <properties><maven.compiler.release>8</maven.compiler.release></properties>
              <build><sourceDirectory>../maven-src</sourceDirectory><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version><configuration><release>8</release></configuration>
              </plugin></plugins></build>
            </project>
        """.trimIndent() + "\n")
        write(root.resolve("settings.gradle"), "rootProject.name='fixture'; include ':app'; project(':app').projectDir=file('gradle-app')\n")
        write(gradleModule.resolve("build.gradle"), "apply plugin: 'java'\ntasks.withType(JavaCompile).configureEach { options.release = 8 }\n")
        write(root.resolve("maven-src/pkg/Runner.java"), "package pkg; class MavenRunner {}\n")
        write(gradleModule.resolve("src/main/java/pkg/Runner.java"), "package pkg; class GradleRunner {}\n")
        val repository = owned.resolve("empty-maven-repository")
        Files.createDirectories(repository)
        session = DaemonSession()
        previousHash = requireNotNull(session).dispatch("project.open", buildJsonObject {
            put("root", root.toString()); put("mavenRepository", repository.toString())
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        providerFixtureBaseline = projectInputContents()
        val gradle = Path.of("/home/paperboy/.gradle/wrapper/dists/gradle-9.6.1-bin/4ticwg1pgcbps2hj28r8so764/gradle-9.6.1/bin/gradle")
        val synchronized = requireNotNull(session).dispatch("project.sync", buildJsonObject {
            put("buildSystem", "gradle"); put("expectedSnapshotHash", previousHash)
            put("sdkHome", System.getProperty("java.home")); put("gradleExecutable", gradle.toString())
            put("allowBuildExecution", true)
        }).jsonObject
        assertEquals("synchronized", synchronized.getValue("status").jsonPrimitive.content, synchronized.toString())
        previousHash = synchronized.getValue("snapshotHash").jsonPrimitive.content
    }

    @When("project context is requested using {string} provider selection")
    fun acquireProviderQualified(selection: String) {
        assertTrue(selection in setOf("maven", "gradle", "legacy"))
        val request = buildJsonObject {
            put("expectedSnapshotHash", previousHash); put("sdkHome", System.getProperty("java.home"))
            put("moduleId", ":app"); put("sourceSetId", "main"); put("sourceRelativePath", "pkg/Runner.java")
            when (selection) {
                "maven" -> put("providerId", "maven-effective-v1")
                "gradle" -> put("providerId", "gradle-effective-v1")
            }
        }
        response = requireNotNull(session).dispatch("project.context", request).jsonObject
    }

    @Then("schema {string} reports {string} with refusal {string} and selected source provider {string} for module {string}, source set {string} and path {string}")
    fun providerQualifiedOutcome(
        schema: String, outcome: String, refusal: String, provider: String,
        moduleId: String, sourceSetId: String, sourceRelativePath: String,
    ) {
        assertEquals(schema, response.getValue("schemaVersion").jsonPrimitive.content)
        val capability = requireNotNull(session).dispatch("server.capabilities", null).jsonObject
            .getValue("methods").jsonArray.map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == "project.context" }
        val capabilityFeatures = capability.getValue("features").jsonObject
        assertEquals("true", capabilityFeatures.getValue("typedSchemaV3").jsonPrimitive.content)
        assertEquals("true", capabilityFeatures.getValue("providerQualifiedSourceAssociation").jsonPrimitive.content)
        assertEquals(setOf("schemaVersion", "status", "snapshotHash", "contextHash", "sdk", "providers",
            "modules", "buildEvidence", "selectedSource", "analysis", "refusal"), response.keys)
        assertEquals(outcome, response.getValue("status").jsonPrimitive.content, response.toString())
        if (outcome == "refused") {
            assertEquals(refusal, response.getValue("refusal").jsonObject.getValue("code").jsonPrimitive.content)
            assertEquals("null", response.getValue("selectedSource").toString())
            assertEquals("none", provider)
            writeProviderContextResponse("legacy-v2")
            return
        }
        assertEquals("none", refusal)
        val selected = response.getValue("selectedSource").jsonObject
        assertEquals(setOf("path", "symbol", "providerId", "moduleId", "sourceSetId", "sourceRoot",
            "generated", "bindingKey", "evidence"), selected.keys)
        assertEquals(provider, selected.getValue("providerId").jsonPrimitive.content)
        assertEquals(moduleId, selected.getValue("moduleId").jsonPrimitive.content)
        assertEquals(sourceSetId, selected.getValue("sourceSetId").jsonPrimitive.content)
        val associatedPath = Path.of(selected.getValue("path").jsonPrimitive.content).normalize()
        val sourceRoot = Path.of(selected.getValue("sourceRoot").jsonPrimitive.content).normalize()
        assertEquals(associatedPath, sourceRoot.resolve(sourceRelativePath).normalize())
        writeProviderContextResponse(if (provider == "maven-effective-v1") "maven-v3" else "gradle-v3")
    }

    @Then("the query preserves synchronized project inputs without running a Maven lifecycle")
    fun providerQueryIsReadOnly() {
        assertEquals(providerFixtureBaseline, projectInputContents())
        assertFalse(Files.exists(root.resolve(":app/target")), "project.context must not run Maven goals")
    }

    @Given("a Java context fixture with same-path main and test sources and {string}")
    fun fixture(variant: String) {
        val owned = Files.createTempDirectory("rk-java-context-"); container = owned
        root = owned.resolve("app"); Files.createDirectories(root)
        generated = variant == "materialized root"
        write(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>pkg</groupId><artifactId>app</artifactId><version>1</version>
              <properties><maven.compiler.release>8</maven.compiler.release></properties>
              <dependencies>
                <dependency><groupId>fixture</groupId><artifactId>z-first</artifactId><version>1</version></dependency>
                <dependency><groupId>fixture</groupId><artifactId>a-second</artifactId><version>1</version></dependency>
                <dependency><groupId>fixture</groupId><artifactId>runtime</artifactId><version>1</version><scope>runtime</scope></dependency>
                <dependency><groupId>fixture</groupId><artifactId>test</artifactId><version>1</version><scope>test</scope></dependency>
              </dependencies>
            </project>
        """.trimIndent() + "\n")
        write(root.resolve("src/main/java/pkg/Runner.java"),
            "package pkg; public class Runner { java.util.List<String> values; } class Peer {}\n")
        write(root.resolve("src/test/java/pkg/Runner.java"), "package pkg; class Unrelated {}\n")
        if (generated) write(root.resolve("target/generated-sources/rk/pkg/Runner.java"),
            "package pkg; class GeneratedPeer {}\n")
        write(root.resolve("target/classes/.materialized"), "main output\n")
        write(root.resolve("target/test-classes/.materialized"), "test output\n")
        val repository = owned.resolve("repository")
        for (artifact in listOf("z-first", "a-second", "runtime", "test")) {
            if (variant == "missing test artifact" && artifact == "test") continue
            val directory = repository.resolve("fixture/$artifact/1")
            write(directory.resolve("$artifact-1.pom"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId>" +
                    "<artifactId>$artifact</artifactId><version>1</version></project>\n")
            JarOutputStream(Files.newOutputStream(directory.resolve("$artifact-1.jar"))).use { }
        }
        val active = DaemonSession(); session = active
        previousHash = active.dispatch("project.open", buildJsonObject {
            put("root", root.toString()); put("mavenRepository", repository.toString())
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        if (variant == "changed build input") {
            val pom = root.resolve("pom.xml")
            Files.writeString(pom, Files.readString(pom).replace("<maven.compiler.release>8", "<maven.compiler.release>9"))
        }
        reactorHash = createRuntimeReactor(owned.resolve("reference-reactor"))
        baseline = contents(owned)
    }

    @Given("a Maven JPMS project depends on a local named module and uses compiler plugin module-path defaults")
    fun mavenJpmsResolvedDependencyFixture() {
        val owned = Files.createTempDirectory("rk-maven-jpms-dependency-")
        container = owned
        root = owned.resolve("workspace")
        val repository = owned.resolve("repository")
        val artifactDirectory = repository.resolve("fixture/external-library/1")
        val artifact = artifactDirectory.resolve("external-library-1.jar")
        expectedMavenModuleArtifact = artifact.toAbsolutePath().normalize()
        write(artifactDirectory.resolve("external-library-1.pom"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId>
              <artifactId>external-library</artifactId><version>1</version>
            </project>
        """.trimIndent() + "\n")
        createNamedModuleDependencyJar(owned, artifact)
        write(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture.jpms</groupId>
              <artifactId>named-maven-app</artifactId><version>1</version>
              <properties><maven.compiler.release>17</maven.compiler.release></properties>
              <dependencies><dependency><groupId>fixture</groupId><artifactId>external-library</artifactId>
                <version>1</version></dependency></dependencies>
              <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version>
                <configuration><release>17</release><proc>none</proc></configuration>
              </plugin></plugins></build>
            </project>
        """.trimIndent() + "\n")
        write(root.resolve("src/main/java/module-info.java"), "module example.app { requires external.library; }\n")
        write(root.resolve("src/main/java/app/Main.java"), "package app; public final class Main {}\n")
        session = DaemonSession()
        previousHash = requireNotNull(session).dispatch("project.open", buildJsonObject {
            put("root", root.toString())
            put("mavenRepository", repository.toString())
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        baseline = contents(owned)
    }

    private fun createNamedModuleDependencyJar(owned: Path, destination: Path) {
        val sourceRoot = owned.resolve("external-library-source")
        val moduleInfo = sourceRoot.resolve("module-info.java")
        val api = sourceRoot.resolve("external/api/ExternalApi.java")
        write(moduleInfo, "module external.library { exports external.api; }\n")
        write(api, "package external.api; public final class ExternalApi {}\n")
        val classes = owned.resolve("external-library-classes")
        Files.createDirectories(classes)
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) { "The test runtime must include javac" }
        assertEquals(0, compiler.run(
            null, null, null, "--release", "17", "-d", classes.toString(), moduleInfo.toString(), api.toString(),
        ))
        Files.createDirectories(destination.parent)
        JarOutputStream(Files.newOutputStream(destination)).use { jar ->
            Files.walk(classes).use { entries ->
                entries.filter { Files.isRegularFile(it) }.sorted().forEach { classFile ->
                    val entryName = classes.relativize(classFile).toString().replace('\\', '/')
                    jar.putNextEntry(JarEntry(entryName))
                    Files.copy(classFile, jar)
                    jar.closeEntry()
                }
            }
        }
    }

    @When("provider-qualified Maven context v5 requires JPMS facts")
    fun requestMavenResolvedDependencyJpmsContext() {
        val active = requireNotNull(session)
        val metadata = active.dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", previousHash)
            put("sdkHome", System.getProperty("java.home"))
        }).jsonObject
        val module = metadata.getValue("modules").jsonArray.single().jsonObject
        response = active.dispatch("project.context.v5", buildJsonObject {
            put("expectedSnapshotHash", previousHash)
            put("sdkHome", System.getProperty("java.home"))
            put("providerId", "maven-effective-v1")
            put("moduleId", module.getValue("id").jsonPrimitive.content)
            put("sourceSetId", "main")
            put("requiredFacts", buildJsonArray { add("jpms") })
        }).jsonObject
    }

    @Then("the resolved dependency appears on the snapshot-bound module path")
    fun mavenResolvedDependencyAppearsOnModulePath() {
        assertEquals("java-project-context-v5", response.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("available", response.getValue("status").jsonPrimitive.content, response.toString())
        assertEquals(previousHash, response.getValue("snapshotHash").jsonPrimitive.content)
        val module = response.getValue("modules").jsonArray.single().jsonObject
        val sourceSet = module.getValue("sourceSets").jsonArray.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == "main" }
        val compilerOptions = sourceSet.getValue("compilerOptions").jsonArray.map { it.jsonObject }
        val inferredOption = compilerOptions.single { it.getValue("name").jsonPrimitive.content == "useModulePath" }
        assertEquals("true", inferredOption.getValue("value").jsonPrimitive.content)
        assertEquals("maven-compiler-plugin:default", inferredOption.getValue("origin").jsonPrimitive.content)
        val artifactPath = requireNotNull(expectedMavenModuleArtifact).toString()
        val artifact = sourceSet.getValue("resolvedArtifacts").jsonArray.map { it.jsonObject }
            .single { it.getValue("path").jsonPrimitive.content == artifactPath }
        assertEquals("fixture", artifact.getValue("coordinates").jsonObject.getValue("groupId").jsonPrimitive.content)
        assertEquals("external-library", artifact.getValue("coordinates").jsonObject.getValue("artifactId").jsonPrimitive.content)
        assertEquals("1", artifact.getValue("coordinates").jsonObject.getValue("version").jsonPrimitive.content)
        assertTrue(artifact.getValue("fingerprint").jsonPrimitive.content.isNotBlank())
        val jpms = sourceSet.getValue("jpms").jsonObject
        assertEquals("complete", jpms.getValue("availability").jsonPrimitive.content, jpms.toString())
        assertEquals(JsonNull, jpms.getValue("refusalCode"))
        val entry = jpms.getValue("modulePath").jsonArray.map { it.jsonObject }
            .single { it.getValue("path").jsonPrimitive.content == artifactPath }
        assertEquals(listOf("external.library"), entry.getValue("moduleNames").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(artifact.getValue("fingerprint"), entry.getValue("fingerprint"))
        val descriptor = jpms.getValue("descriptors").jsonArray.single().jsonObject
        assertEquals("example.app", descriptor.getValue("moduleName").jsonPrimitive.content)
        assertEquals(listOf("external.library"), descriptor.getValue("requires").jsonArray.map {
            it.jsonObject.getValue("moduleName").jsonPrimitive.content
        })
        val artifactFile = requireNotNull(expectedMavenModuleArtifact)
        val originalArtifact = Files.readAllBytes(artifactFile)
        try {
            Files.write(artifactFile, originalArtifact + byteArrayOf(0x42))
            val stale = requireNotNull(session).dispatch("project.context.v5", buildJsonObject {
                put("expectedSnapshotHash", previousHash)
                put("sdkHome", System.getProperty("java.home"))
                put("providerId", "maven-effective-v1")
                put("moduleId", module.getValue("id").jsonPrimitive.content)
                put("sourceSetId", "main")
                put("requiredFacts", buildJsonArray { add("jpms") })
            }).jsonObject
            assertEquals("refused", stale.getValue("status").jsonPrimitive.content, stale.toString())
            assertEquals("java.context.snapshotStale", stale.getValue("refusal").jsonObject.getValue("code").jsonPrimitive.content)
        } finally {
            Files.write(artifactFile, originalArtifact)
        }
    }

    @Given("a Maven Java project declares a module descriptor with {string}")
    fun jpmsDescriptorFixture(directives: String) {
        assertEquals("opens, uses, and provides", directives)
        createJpmsProjectContextFixture(includeModulePathArtifact = false)
    }

    @Given("a Maven JPMS project declares one module-path artifact")
    fun jpmsModulePathArtifactFixture() {
        createJpmsProjectContextFixture(includeModulePathArtifact = true)
    }

    @Given("a Maven Java project with a complete descriptor and a modular module-path JAR")
    fun completeJpmsModulePathFixture() {
        createJpmsProjectContextFixture(
            includeModulePathArtifact = true,
            completeDescriptor = true,
            compileModulePathArtifact = true,
        )
    }

    @Given("a Maven JPMS project declares opens uses and provides in a complete descriptor")
    fun completeJpmsV5ModulePathFixture() {
        createJpmsProjectContextFixture(
            includeModulePathArtifact = true,
            completeDescriptor = true,
            compileModulePathArtifact = true,
            includeStandardDirectives = true,
        )
    }

    private fun createJpmsProjectContextFixture(
        includeModulePathArtifact: Boolean,
        completeDescriptor: Boolean = false,
        compileModulePathArtifact: Boolean = false,
        includeStandardDirectives: Boolean = false,
    ) {
        val owned = Files.createTempDirectory("rk-jpms-context-")
        container = owned
        root = owned.resolve("workspace")
        val modulePathOptions = if (includeModulePathArtifact) {
            "<compilerArgs><arg>--module-path</arg><arg>target/modules/fixture-module.jar</arg></compilerArgs>"
        } else ""
        write(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture.jpms</groupId>
              <artifactId>jpms-fixture</artifactId><version>1</version>
              <properties><maven.compiler.release>21</maven.compiler.release></properties>
              <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version><configuration><release>21</release><proc>none</proc>$modulePathOptions</configuration>
              </plugin></plugins></build>
            </project>
        """.trimIndent() + "\n")
        val descriptor = if (completeDescriptor) """
            module fixture.jpms {
                requires static transitive fixture.dependency;
                exports fixture.api to fixture.dependency;
                ${if (includeStandardDirectives) """
                opens fixture.internal to fixture.framework, fixture.serializer;
                uses fixture.spi.Engine;
                uses fixture.spi.Renderer;
                provides fixture.spi.Engine with fixture.impl.ZedEngine, fixture.impl.DefaultEngine;
                """.trimIndent() else ""}
            }
        """ else """
            module fixture.jpms {
                requires java.logging;
                exports fixture.api to fixture.client;
                opens fixture.internal to fixture.framework;
                uses fixture.spi.Engine;
                provides fixture.spi.Engine with fixture.impl.DefaultEngine;
            }
        """
        write(root.resolve("src/main/java/module-info.java"), descriptor.trimIndent() + "\n")
        write(root.resolve("src/main/java/fixture/api/Api.java"), "package fixture.api; public final class Api {}\n")
        write(root.resolve("src/main/java/fixture/internal/Internal.java"), "package fixture.internal; public final class Internal {}\n")
        write(root.resolve("src/main/java/fixture/spi/Engine.java"), "package fixture.spi; public interface Engine {}\n")
        write(root.resolve("src/main/java/fixture/spi/Renderer.java"), "package fixture.spi; public interface Renderer {}\n")
        write(root.resolve("src/main/java/fixture/impl/DefaultEngine.java"),
            "package fixture.impl; public final class DefaultEngine implements fixture.spi.Engine {}\n")
        write(root.resolve("src/main/java/fixture/impl/ZedEngine.java"),
            "package fixture.impl; public final class ZedEngine implements fixture.spi.Engine {}\n")
        if (includeModulePathArtifact) {
            val modulePathArtifact = root.resolve("target/modules/fixture-module.jar")
            Files.createDirectories(modulePathArtifact.parent)
            if (compileModulePathArtifact) createModularDependencyJar(owned, modulePathArtifact)
            else JarOutputStream(Files.newOutputStream(modulePathArtifact)).use { }
        }
        val repository = owned.resolve("empty-maven-repository")
        Files.createDirectories(repository)
        session = DaemonSession()
        previousHash = requireNotNull(session).dispatch("project.open", buildJsonObject {
            put("root", root.toString())
            put("mavenRepository", repository.toString())
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        baseline = contents(owned)
    }

    private fun createModularDependencyJar(owned: Path, destination: Path) {
        val sourceRoot = owned.resolve("dependency-module-src")
        val moduleInfo = sourceRoot.resolve("module-info.java")
        val dependencyApi = sourceRoot.resolve("dependency/api/DependencyApi.java")
        val hidden = sourceRoot.resolve("dependency/internal/SecretThing.java")
        write(moduleInfo, "module fixture.dependency { exports dependency.api; }\n")
        write(dependencyApi, "package dependency.api; public final class DependencyApi {}\n")
        write(hidden, "package dependency.internal; public final class SecretThing {}\n")
        val classes = owned.resolve("dependency-module-classes")
        Files.createDirectories(classes)
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) { "The test runtime must include javac" }
        assertEquals(0, compiler.run(
            null, null, null, "--release", "21", "-d", classes.toString(),
            moduleInfo.toString(), dependencyApi.toString(), hidden.toString(),
        ))
        Files.createDirectories(destination.parent)
        JarOutputStream(Files.newOutputStream(destination)).use { jar ->
            Files.walk(classes).use { entries ->
                entries.filter { Files.isRegularFile(it) }.sorted().forEach { classFile ->
                    val entryName = classes.relativize(classFile).toString().replace('\\', '/')
                    jar.putNextEntry(JarEntry(entryName))
                    Files.copy(classFile, jar)
                    jar.closeEntry()
                }
            }
        }
    }

    @Then("public JPMS context reports complete module metadata and exact fingerprints")
    fun jpmsCompleteModuleMetadata() {
        val modulePathArtifact = root.resolve("target/modules/fixture-module.jar").toAbsolutePath().normalize()
        val expectedFingerprint = MessageDigest.getInstance("SHA-256").run {
            update("file\u0000".toByteArray(Charsets.UTF_8))
            update(Files.readAllBytes(modulePathArtifact))
            digest().joinToString("") { byte -> "%02x".format(byte) }
        }
        val descriptorPath = root.resolve("src/main/java/module-info.java").toAbsolutePath().normalize()
        val descriptorBytes = Files.readAllBytes(descriptorPath)
        val expectedDescriptorHash = MessageDigest.getInstance("SHA-256").digest(descriptorBytes)
            .joinToString("") { byte -> "%02x".format(byte) }

        fun mainJpms(value: JsonObject): JsonObject {
            assertEquals("java-project-context-v2", value.getValue("schemaVersion").jsonPrimitive.content)
            assertEquals("available", value.getValue("status").jsonPrimitive.content, value.toString())
            assertEquals(previousHash, value.getValue("snapshotHash").jsonPrimitive.content)
            assertEquals(JsonNull, value.getValue("refusal"))
            assertEquals(JsonNull, value.getValue("selectedSource"))
            assertEquals("eclipse-jdt", value.getValue("analysis").jsonObject.getValue("backend").jsonPrimitive.content)
            val module = value.getValue("modules").jsonArray.single().jsonObject
            val main = module.getValue("sourceSets").jsonArray.map { it.jsonObject }
                .single { it.getValue("id").jsonPrimitive.content == "main" }
            val jpms = main.getValue("jpms").jsonObject
            assertEquals("complete", jpms.getValue("availability").jsonPrimitive.content, jpms.toString())
            assertEquals(JsonNull, jpms.getValue("refusalCode"))
            assertEquals(setOf("availability", "modulePath", "descriptors", "refusalCode"), jpms.keys)

            val entry = jpms.getValue("modulePath").jsonArray.single().jsonObject
            assertEquals(setOf("path", "moduleNames", "fingerprint"), entry.keys)
            assertEquals(modulePathArtifact.toString(), entry.getValue("path").jsonPrimitive.content)
            assertEquals(listOf("fixture.dependency"), entry.getValue("moduleNames").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(expectedFingerprint, entry.getValue("fingerprint").jsonPrimitive.content)

            val descriptor = jpms.getValue("descriptors").jsonArray.single().jsonObject
            assertEquals(setOf("path", "moduleName", "open", "requires", "exports", "contentSha256"), descriptor.keys)
            assertEquals("src/main/java/module-info.java", descriptor.getValue("path").jsonPrimitive.content)
            assertEquals("fixture.jpms", descriptor.getValue("moduleName").jsonPrimitive.content)
            assertEquals("false", descriptor.getValue("open").jsonPrimitive.content)
            val requires = descriptor.getValue("requires").jsonArray.map { it.jsonObject }.single()
            assertEquals("fixture.dependency", requires.getValue("moduleName").jsonPrimitive.content)
            assertEquals(listOf("static", "transitive"), requires.getValue("modifiers").jsonArray.map { it.jsonPrimitive.content })
            val exported = descriptor.getValue("exports").jsonArray.map { it.jsonObject }.single()
            assertEquals("fixture.api", exported.getValue("packageName").jsonPrimitive.content)
            assertEquals(listOf("fixture.dependency"), exported.getValue("targets").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(expectedDescriptorHash, descriptor.getValue("contentSha256").jsonPrimitive.content)
            return jpms
        }

        assertEquals(mainJpms(jpmsMetadataResponse), mainJpms(response))
    }

    @When("provider-qualified Java project context v5 requires JPMS facts")
    fun requestProviderQualifiedJpmsV5() {
        val active = requireNotNull(session)
        val capabilities = active.dispatch("server.capabilities", null).jsonObject
            .getValue("methods").jsonArray.map { it.jsonObject }
        val capability = capabilities.single {
            it.getValue("name").jsonPrimitive.content == "project.context.v5"
        }
        assertEquals("true", capability.getValue("features").jsonObject
            .getValue("typedSchemaV5").jsonPrimitive.content)

        val metadata = active.dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", previousHash)
            put("sdkHome", System.getProperty("java.home"))
        }).jsonObject
        val module = metadata.getValue("modules").jsonArray.single().jsonObject
        response = active.dispatch("project.context.v5", buildJsonObject {
            put("expectedSnapshotHash", previousHash)
            put("sdkHome", System.getProperty("java.home"))
            put("providerId", "maven-effective-v1")
            put("moduleId", module.getValue("id").jsonPrimitive.content)
            put("sourceSetId", "main")
            put("requiredFacts", buildJsonArray { add("jpms") })
        }).jsonObject
    }

    @When("provider-qualified Java project context v5 analyzes symbol {string}")
    fun requestProviderQualifiedJpmsV5ForSymbol(symbol: String) {
        assertEquals("fixture.api.Api", symbol)
        write(root.resolve("src/main/java/fixture/api/Api.java"), """
            package fixture.api;
            public final class Api {
                dependency.api.DependencyApi exported;
                dependency.internal.SecretThing hidden;
            }
        """.trimIndent() + "\n")
        baseline = contents(requireNotNull(container))
        val active = requireNotNull(session)
        previousHash = active.dispatch("project.open", buildJsonObject {
            put("root", root.toString())
            put("mavenRepository", requireNotNull(container).resolve("empty-maven-repository").toString())
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        val metadata = active.dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", previousHash)
            put("sdkHome", System.getProperty("java.home"))
        }).jsonObject
        val moduleId = metadata.getValue("modules").jsonArray.single().jsonObject
            .getValue("id").jsonPrimitive.content
        response = active.dispatch("project.context.v5", buildJsonObject {
            put("expectedSnapshotHash", previousHash)
            put("sdkHome", System.getProperty("java.home"))
            put("providerId", "maven-effective-v1")
            put("moduleId", moduleId)
            put("sourceSetId", "main")
            put("symbol", symbol)
            put("requiredFacts", buildJsonArray { add("jpms") })
        }).jsonObject
    }

    @Then("JPMS context v5 publishes exact opens uses and provides directives")
    fun jpmsV5PublishesStandardDirectives() {
        assertEquals("java-project-context-v5", response.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("available", response.getValue("status").jsonPrimitive.content, response.toString())
        val module = response.getValue("modules").jsonArray.single().jsonObject
        val sourceSet = module.getValue("sourceSets").jsonArray.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == "main" }
        val jpms = sourceSet.getValue("jpms").jsonObject
        assertEquals("complete", jpms.getValue("availability").jsonPrimitive.content, jpms.toString())
        assertEquals(JsonNull, jpms.getValue("refusalCode"))
        val descriptor = jpms.getValue("descriptors").jsonArray.single().jsonObject
        assertEquals(setOf("path", "moduleName", "open", "requires", "exports", "contentSha256", "opens", "uses", "provides"), descriptor.keys)
        val opens = descriptor.getValue("opens").jsonArray.single().jsonObject
        assertEquals("fixture.internal", opens.getValue("packageName").jsonPrimitive.content)
        assertEquals(listOf("fixture.framework", "fixture.serializer"), opens.getValue("targets").jsonArray.map {
            it.jsonPrimitive.content
        })
        assertEquals(listOf("fixture.spi.Engine", "fixture.spi.Renderer"), descriptor.getValue("uses").jsonArray.map {
            it.jsonPrimitive.content
        })
        val provides = descriptor.getValue("provides").jsonArray.single().jsonObject
        assertEquals("fixture.spi.Engine", provides.getValue("serviceName").jsonPrimitive.content)
        assertEquals(listOf("fixture.impl.DefaultEngine", "fixture.impl.ZedEngine"),
            provides.getValue("implementations").jsonArray.map { it.jsonPrimitive.content })
    }

    @Then("JPMS v5 semantic analysis returns a bound source with the expected directive projection")
    fun jpmsV5SemanticAnalysisBindsDirectives() {
        assertEquals("java-project-context-v5", response.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("available", response.getValue("status").jsonPrimitive.content, response.toString())
        val selected = response.getValue("selectedSource").jsonObject
        assertEquals("src/main/java/fixture/api/Api.java", selected.getValue("path").jsonPrimitive.content)
        assertEquals("fixture.api.Api", selected.getValue("symbol").jsonPrimitive.content)
        assertEquals("JDT_BINDING", selected.getValue("evidence").jsonPrimitive.content)
        assertTrue(selected.getValue("bindingKey").jsonPrimitive.content.isNotBlank())
        val analysis = response.getValue("analysis").jsonObject
        assertEquals("eclipse-jdt", analysis.getValue("backend").jsonPrimitive.content)
        assertEquals(1, analysis.getValue("warningCount").jsonPrimitive.content.toInt(),
            "only the intentionally hidden dependency.internal type should remain unresolved")
        val sourceSet = response.getValue("modules").jsonArray.single().jsonObject
            .getValue("sourceSets").jsonArray.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == "main" }
        val descriptor = sourceSet.getValue("jpms").jsonObject
            .getValue("descriptors").jsonArray.single().jsonObject
        assertEquals(listOf("fixture.spi.Engine", "fixture.spi.Renderer"),
            descriptor.getValue("uses").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("fixture.impl.DefaultEngine", "fixture.impl.ZedEngine"),
            descriptor.getValue("provides").jsonArray.single().jsonObject
                .getValue("implementations").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("fixture.framework", "fixture.serializer"),
            descriptor.getValue("opens").jsonArray.single().jsonObject
                .getValue("targets").jsonArray.map { it.jsonPrimitive.content })
    }

    @Then("Maven JDT analysis resolves exported module types and rejects the hidden package")
    fun mavenJpmsSemanticAssociation() {
        assertEquals("available", response.getValue("status").jsonPrimitive.content, response.toString())
        val selected = response.getValue("selectedSource").jsonObject
        assertEquals("src/main/java/fixture/api/Api.java", selected.getValue("path").jsonPrimitive.content)
        assertEquals("fixture.api.Api", selected.getValue("symbol").jsonPrimitive.content)
        assertEquals("JDT_BINDING", selected.getValue("evidence").jsonPrimitive.content)
        assertTrue(selected.getValue("bindingKey").jsonPrimitive.content.isNotBlank())
        assertEquals("eclipse-jdt", response.getValue("analysis").jsonObject.getValue("backend").jsonPrimitive.content)
        assertEquals(1, response.getValue("analysis").jsonObject.getValue("warningCount").jsonPrimitive.content.toInt(),
            "only the non-exported dependency.internal.SecretThing reference should remain unresolved")
    }

    @When("public Java context is requested with and without required JPMS facts")
    fun requestJpmsContextAndRequiredFact() {
        val active = requireNotNull(session)
        fun request(requireJpms: Boolean) = buildJsonObject {
            put("expectedSnapshotHash", previousHash)
            put("sdkHome", System.getProperty("java.home"))
            if (requireJpms) put("requiredFacts", buildJsonArray { add("jpms") })
        }
        jpmsMetadataResponse = active.dispatch("project.context", request(requireJpms = false)).jsonObject
        response = active.dispatch("project.context", request(requireJpms = true)).jsonObject
    }

    @When("public Maven context selects JPMS source {string}")
    fun requestMavenJpmsSemanticContext(symbol: String) {
        assertEquals("fixture.api.Api", symbol)
        write(root.resolve("src/main/java/fixture/api/Api.java"), """
            package fixture.api;
            public final class Api {
                dependency.api.DependencyApi exported;
                dependency.internal.SecretThing hidden;
            }
        """.trimIndent() + "\n")
        baseline = contents(requireNotNull(container))
        val active = requireNotNull(session)
        val repository = requireNotNull(container).resolve("empty-maven-repository")
        previousHash = active.dispatch("project.open", buildJsonObject {
            put("root", root.toString())
            put("mavenRepository", repository.toString())
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        val completeContext = active.dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", previousHash)
            put("sdkHome", System.getProperty("java.home"))
            put("requiredFacts", buildJsonArray { add("jpms") })
        }).jsonObject
        val moduleId = completeContext.getValue("modules").jsonArray.single().jsonObject
            .getValue("id").jsonPrimitive.content
        response = active.dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", previousHash)
            put("sdkHome", System.getProperty("java.home"))
            put("providerId", "maven-effective-v1")
            put("moduleId", moduleId)
            put("sourceSetId", "main")
            put("symbol", symbol)
            put("requiredFacts", buildJsonArray { add("jpms") })
        }).jsonObject
    }

    @When("public JPMS context is requested after its module descriptor changed")
    fun requestJpmsContextAfterDescriptorChange() {
        val descriptor = root.resolve("src/main/java/module-info.java")
        val original = Files.readString(descriptor)
        val changed = original.replace("fixture.framework", "fixture.otherFramework")
        assertTrue(changed != original, "fixture must contain the qualified opens target")
        Files.writeString(descriptor, changed)
        baseline = contents(requireNotNull(container))
        response = requireNotNull(session).dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", previousHash)
            put("sdkHome", System.getProperty("java.home"))
            put("requiredFacts", buildJsonArray { add("jpms") })
        }).jsonObject
    }

    @When("the JPMS module-path artifact changes after project open")
    fun requestJpmsContextAfterModulePathArtifactChange() {
        val modulePathArtifact = root.resolve("target/modules/fixture-module.jar")
        val original = Files.readAllBytes(modulePathArtifact)
        Files.write(modulePathArtifact, original + byteArrayOf(0x42))
        baseline = contents(requireNotNull(container))
        response = requireNotNull(session).dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", previousHash)
            put("sdkHome", System.getProperty("java.home"))
            put("requiredFacts", buildJsonArray { add("jpms") })
        }).jsonObject
    }

    @Then("public JPMS context refuses stale snapshot {string}")
    fun jpmsStaleSnapshotRefuses(refusal: String) {
        assertEquals("java-project-context-v3", response.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("refused", response.getValue("status").jsonPrimitive.content, response.toString())
        assertEquals(refusal, response.getValue("refusal").jsonObject.getValue("code").jsonPrimitive.content)
        val refreshedHash = response.getValue("snapshotHash").jsonPrimitive.content
        assertTrue(refreshedHash.matches(Regex("[a-f0-9]{64}")))
        assertTrue(refreshedHash != previousHash, "changed module-info.java must produce a new snapshot")
        assertTrue(response.getValue("modules").jsonArray.isEmpty(), response.toString())
        assertEquals(JsonNull, response.getValue("selectedSource"))
    }

    @Then("context marks omitted JPMS directives partial and refuses {string}")
    fun jpmsMissingDirectivesRefuse(refusal: String) {
        assertEquals("java-project-context-v2", jpmsMetadataResponse.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("available", jpmsMetadataResponse.getValue("status").jsonPrimitive.content, jpmsMetadataResponse.toString())
        val module = jpmsMetadataResponse.getValue("modules").jsonArray.single().jsonObject
        val main = module.getValue("sourceSets").jsonArray.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == "main" }
        val jpms = main.getValue("jpms").jsonObject
        assertEquals("partial", jpms.getValue("availability").jsonPrimitive.content, jpms.toString())
        assertEquals(refusal, jpms.getValue("refusalCode").jsonPrimitive.content)
        val descriptor = jpms.getValue("descriptors").jsonArray.single().jsonObject
        assertEquals("fixture.jpms", descriptor.getValue("moduleName").jsonPrimitive.content)
        assertEquals(listOf("java.logging"), descriptor.getValue("requires").jsonArray.map {
            it.jsonObject.getValue("moduleName").jsonPrimitive.content
        })
        assertEquals(listOf("fixture.api"), descriptor.getValue("exports").jsonArray.map {
            it.jsonObject.getValue("packageName").jsonPrimitive.content
        })
        assertEquals("false", descriptor.getValue("open").jsonPrimitive.content)
        assertFalse(setOf("opens", "uses", "provides").any(descriptor::containsKey), descriptor.toString())
        val descriptorBytes = Files.readAllBytes(root.resolve("src/main/java/module-info.java"))
        val expectedDescriptorHash = MessageDigest.getInstance("SHA-256").digest(descriptorBytes)
            .joinToString("") { byte -> "%02x".format(byte) }
        assertEquals(expectedDescriptorHash, descriptor.getValue("contentSha256").jsonPrimitive.content)

        assertEquals("java-project-context-v2", response.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("refused", response.getValue("status").jsonPrimitive.content, response.toString())
        assertEquals(refusal, response.getValue("refusal").jsonObject.getValue("code").jsonPrimitive.content)
        assertTrue(response.getValue("modules").jsonArray.isEmpty(), response.toString())
        assertEquals(JsonNull, response.getValue("selectedSource"))
    }

    @When("public Maven project context requires artifact coordinates")
    fun requestMavenArtifactCoordinates() {
        response = requireNotNull(session).dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", previousHash)
            put("sdkHome", System.getProperty("java.home"))
            put("requiredFacts", buildJsonArray { add("artifact-coordinates") })
        }).jsonObject
    }

    @Then("Maven source-set artifact coordinates report {string} with refusal {string}")
    fun mavenArtifactCoordinatesOutcome(outcome: String, refusal: String) {
        assertEquals("java-project-context-v2", response.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals(outcome, response.getValue("status").jsonPrimitive.content, response.toString())
        if (outcome == "refused") {
            assertEquals(refusal, response.getValue("refusal").jsonObject.getValue("code").jsonPrimitive.content)
            assertTrue(response.getValue("modules").jsonArray.isEmpty(), response.toString())
            assertEquals(JsonNull, response.getValue("selectedSource"))
            return
        }
        assertEquals("none", refusal)
        assertEquals(previousHash, response.getValue("snapshotHash").jsonPrimitive.content)
        assertEquals("null", response.getValue("refusal").toString())
        val module = response.getValue("modules").jsonArray.single().jsonObject
        val moduleCoordinates = module.getValue("coordinates").jsonObject
        assertEquals("pkg", moduleCoordinates.getValue("groupId").jsonPrimitive.content)
        assertEquals("app", moduleCoordinates.getValue("artifactId").jsonPrimitive.content)
        assertEquals("1", moduleCoordinates.getValue("version").jsonPrimitive.content)
        val sourceSets = module.getValue("sourceSets").jsonArray.map { it.jsonObject }.associateBy {
            it.getValue("id").jsonPrimitive.content
        }
        val main = requireNotNull(sourceSets["main"])
        val test = requireNotNull(sourceSets["test"])
        val mainArtifacts = assertResolvedArtifacts(
            main, mapOf("z-first" to "compile", "a-second" to "compile", "runtime" to "runtime"),
        )
        val testArtifacts = assertResolvedArtifacts(test, mapOf(
            "z-first" to "compile", "a-second" to "compile", "runtime" to "runtime", "test" to "test",
        ))
        for ((set, resolvedByPath) in listOf(main to mainArtifacts, test to testArtifacts)) {
            val classpathArtifacts = (set.getValue("compileClasspath").jsonArray + set.getValue("runtimeClasspath").jsonArray)
                .map { it.jsonObject }
            assertTrue(classpathArtifacts.isNotEmpty())
            classpathArtifacts.filter { it.getValue("path").jsonPrimitive.content.endsWith(".jar") }.forEach { entry ->
                val artifact = entry.getValue("artifact").jsonObject
                assertEquals(entry.getValue("path").jsonPrimitive.content, artifact.getValue("path").jsonPrimitive.content)
                assertEquals(requireNotNull(resolvedByPath[entry.getValue("path").jsonPrimitive.content]), artifact)
                assertTrue(artifact.getValue("fingerprint").jsonPrimitive.content.matches(Regex("[a-f0-9]{64}")))
            }
        }
    }

    private fun assertResolvedArtifacts(sourceSet: JsonObject, expectedScopes: Map<String, String>): Map<String, JsonObject> {
        assertEquals("complete", sourceSet.getValue("resolvedArtifactAvailability").jsonPrimitive.content)
        assertEquals("null", sourceSet.getValue("resolvedArtifactRefusalCode").toString())
        val artifacts = sourceSet.getValue("resolvedArtifacts").jsonArray.map { it.jsonObject }.associateBy { artifact ->
            artifact.getValue("coordinates").jsonObject.getValue("artifactId").jsonPrimitive.content
        }
        assertEquals(expectedScopes.keys, artifacts.keys)
        expectedScopes.forEach { (artifactId, scope) ->
            val artifact = requireNotNull(artifacts[artifactId])
            val coordinates = artifact.getValue("coordinates").jsonObject
            assertEquals("fixture", coordinates.getValue("groupId").jsonPrimitive.content)
            assertEquals(artifactId, coordinates.getValue("artifactId").jsonPrimitive.content)
            assertEquals("1", coordinates.getValue("version").jsonPrimitive.content)
            assertEquals("jar", artifact.getValue("extension").jsonPrimitive.content)
            assertEquals(JsonNull, artifact.getValue("classifier"))
            assertEquals(scope, artifact.getValue("scope").jsonPrimitive.content)
            assertEquals("false", artifact.getValue("transitive").jsonPrimitive.content)
            assertTrue(artifact.getValue("fingerprint").jsonPrimitive.content.matches(Regex("[a-f0-9]{64}")))
            assertTrue(Path.of(artifact.getValue("path").jsonPrimitive.content).isAbsolute)
        }
        return artifacts.values.associateBy { it.getValue("path").jsonPrimitive.content }
    }

    @When("project context is requested for {string} in {string} with {string} evidence")
    fun acquire(type: String, scope: String, evidence: String) {
        assertTrue(evidence in setOf("current", "previous"))
        val request = buildJsonObject {
            put("expectedSnapshotHash", previousHash)
            put("sdkHome", System.getProperty("java.home"))
            put("sourceRelativePath", "pkg/Runner.java")
            if (scope != "unselected") {
                put("moduleId", "app")
                put("sourceSetId", if (scope == "selected main") "main" else "test")
            }
            if (type != "unspecified") put("symbol", type)
        }
        try {
            response = requireNotNull(session).dispatch("project.context", request).jsonObject
        } catch (problem: JsonRpcException) {
            failure = problem
        }
    }

    @Then("its public provenance reports {string} with {string} owned by {string}")
    fun provenance(outcome: String, source: String, owner: String) {
        assertEquals(null, failure, "project.context must return a structured outcome: ${failure?.message}")
        assertEquals("java-project-context-v2", response.getValue("schemaVersion").jsonPrimitive.content)
        when (outcome) {
            "unique provenance" -> {
                assertEquals("available", response.getValue("status").jsonPrimitive.content, response.toString())
                assertEquals(previousHash, response.getValue("snapshotHash").jsonPrimitive.content)
                assertTrue(response.getValue("contextHash").jsonPrimitive.content.matches(Regex("[a-f0-9]{64}")))
                val selection = response.getValue("selectedSource").jsonObject
                assertEquals(source, selection.getValue("path").jsonPrimitive.content)
                assertEquals(owner, selection.getValue("moduleId").jsonPrimitive.content + ":" +
                    selection.getValue("sourceSetId").jsonPrimitive.content)
                assertEquals(generated.toString(), selection.getValue("generated").jsonPrimitive.content)
                assertEquals("JDT_BINDING", selection.getValue("evidence").jsonPrimitive.content)
                assertTrue(selection.getValue("bindingKey").jsonPrimitive.content.isNotBlank())
                val module = response.getValue("modules").jsonArray.single().jsonObject
                assertEquals("app", module.getValue("id").jsonPrimitive.content)
                val sourceSets = module.getValue("sourceSets").jsonArray.map { it.jsonObject }.associateBy {
                    it.getValue("id").jsonPrimitive.content
                }
                val main = assertNotNull(sourceSets["main"])
                val compile = paths(main, "compileClasspath")
                assertEquals(listOf("z-first-1.jar", "a-second-1.jar"), compile.filter { it.endsWith(".jar") }.map { Path.of(it).fileName.toString() })
                val runtime = paths(main, "runtimeClasspath")
                assertEquals(listOf("z-first-1.jar", "a-second-1.jar", "runtime-1.jar"), runtime.filter { it.endsWith(".jar") }.map { Path.of(it).fileName.toString() })
                assertFalse(compile.any { it.endsWith("runtime-1.jar") || it.endsWith("test-1.jar") })
                assertTrue(paths(assertNotNull(sourceSets["test"]), "compileClasspath").any { it.endsWith("test-1.jar") })
                assertEquals(previousHash, response.getValue("analysis").jsonObject.getValue("snapshotHash").jsonPrimitive.content)
                assertEquals("eclipse-jdt", response.getValue("analysis").jsonObject.getValue("backend").jsonPrimitive.content)
                assertTrue(response.getValue("sdk").jsonArray.isNotEmpty())
                assertTrue(response.getValue("buildEvidence").jsonArray.any {
                    it.jsonObject.getValue("path").jsonPrimitive.content == "pom.xml"
                })
                val capabilities = requireNotNull(session).dispatch("server.capabilities", null).jsonObject
                val capability = capabilities.getValue("methods").jsonArray.map { it.jsonObject }
                    .single { it.getValue("name").jsonPrimitive.content == "project.context" }
                assertEquals("false", capability.getValue("writesWorkspace").jsonPrimitive.content)
                assertEquals("true", capability.getValue("features").jsonObject.getValue("mavenEffectiveContext").jsonPrimitive.content)
                assertEquals("true", capability.getValue("features").jsonObject.getValue("gradleEffectiveContext").jsonPrimitive.content)
                assertEquals("true", capability.getValue("features").jsonObject.getValue("javaQueriesUseContextAuthority").jsonPrimitive.content)
                assertEquals("true", capability.getValue("features").jsonObject.getValue("javaRefactoringsUseContextAuthority").jsonPrimitive.content)
                val unsupported = assertFailsWith<JsonRpcException> {
                    requireNotNull(session).dispatch("project.context", buildJsonObject {
                        put("expectedSnapshotHash", previousHash); put("sdkHome", System.getProperty("java.home"))
                        put("editorOverlay", "unsupported")
                    })
                }
                assertEquals(org.refactorkit.core.JsonRpcErrorCodes.INVALID_PARAMS, unsupported.code)
                val reactor = requireNotNull(reactorSession).dispatch("project.context", buildJsonObject {
                    put("expectedSnapshotHash", reactorHash); put("sdkHome", System.getProperty("java.home"))
                }).jsonObject
                assertEquals("available", reactor.getValue("status").jsonPrimitive.content, reactor.toString())
                val app = reactor.getValue("modules").jsonArray.map { it.jsonObject }
                    .single { it.getValue("id").jsonPrimitive.content == "app" }
                val appMain = app.getValue("sourceSets").jsonArray.map { it.jsonObject }
                    .single { it.getValue("id").jsonPrimitive.content == "main" }
                assertEquals(listOf("app/target/classes", "library/target/classes"), paths(appMain, "compileClasspath"))
                assertEquals(listOf("app/target/classes", "library/target/classes", "runtime/target/classes"),
                    paths(appMain, "runtimeClasspath"))
                assertFalse(appMain.getValue("analysisSourceRoots").jsonArray.any {
                    it.jsonPrimitive.content.startsWith("unrelated/") || it.jsonPrimitive.content.startsWith("runtime/")
                })
            }
            "ambiguous context", "stale context" -> {
                assertEquals("refused", response.getValue("status").jsonPrimitive.content)
                val expected = if (outcome == "ambiguous context") "java.context.sourceAssociationAmbiguous" else "java.context.snapshotStale"
                assertEquals(expected, response.getValue("refusal").jsonObject.getValue("code").jsonPrimitive.content)
                assertEquals("none", source); assertEquals("none", owner)
                assertEquals("null", response.getValue("selectedSource").toString())
                assertTrue(response.getValue("modules").jsonArray.isEmpty())
            }
            else -> error("Unknown outcome: $outcome")
        }
    }

    @Then("context acquisition preserves project bytes and executes no build or debugger")
    fun readOnly() {
        assertEquals(baseline, contents(checkedInReadOnlyRoot ?: requireNotNull(container)))
        checkedInSampleRoot?.let { assertEquals(checkedInSampleBaseline, contents(it)) }
        assertFalse(Files.exists(root.resolve(".gradle")))
        assertFalse(Files.exists(root.resolve(".refactorkit")))
    }

    @Given("a Maven project declares one Build Helper main root and one Build Helper test root")
    fun customTestSourceContextFixture() {
        val owned = Files.createTempDirectory("rk-custom-test-source-context-")
        container = owned
        root = owned.resolve("workspace")
        write(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture.context</groupId><artifactId>custom-roots</artifactId>
              <version>1</version><properties><maven.compiler.release>21</maven.compiler.release></properties>
              <build><plugins><plugin><groupId>org.codehaus.mojo</groupId><artifactId>build-helper-maven-plugin</artifactId>
                <version>3.6.0</version><executions>
                  <execution><id>add-feature-sources</id><goals><goal>add-source</goal></goals>
                    <configuration><sources><source>src/feature/java</source></sources></configuration></execution>
                  <execution><id>add-verification-sources</id><goals><goal>add-test-source</goal></goals>
                    <configuration><sources><source>src/verification/java</source></sources></configuration></execution>
                </executions></plugin></plugins></build>
            </project>
        """.trimIndent() + "\n")
        write(root.resolve("src/feature/java/fixture/Feature.java"),
            "package fixture; public final class Feature {}\n")
        write(root.resolve("src/verification/java/fixture/FeatureTest.java"),
            "package fixture; class FeatureTest { Feature value = new Feature(); }\n")
        Files.createDirectories(owned.resolve("empty-maven-repository"))
        customTestSourceBaseline = contents(owned)
    }

    @When("Java diagnostics are requested for the Maven custom test source")
    fun requestCustomTestSourceDiagnostics() {
        customTestSourceSnapshot = JavaProjectScanner(
            localMavenRepository = requireNotNull(container).resolve("empty-maven-repository"),
        ).scan(root)
        customTestSourceDiagnostics = JavaLanguageAdapter().diagnostics(customTestSourceSnapshot)
    }

    @Then("the custom test source resolves a type from the same-module custom main root")
    fun customTestSourceResolvesCustomMainType() {
        val errors = customTestSourceDiagnostics.filter { it.severity == Diagnostic.Severity.ERROR }
        assertTrue(errors.isEmpty(), errors.toString())
        assertTrue(customTestSourceSnapshot.files.any { it.path == Path.of("src/feature/java/fixture/Feature.java") })
        assertTrue(customTestSourceSnapshot.files.any { it.path == Path.of("src/verification/java/fixture/FeatureTest.java") })
        val customRows = customTestSourceSnapshot.buildModels.single().modules.single().sourceSets
            .filter { it.kind == org.refactorkit.core.SourceSetKind.CUSTOM }
        assertTrue(customRows.any {
            it.attributes["visibility"] == "main" && Path.of("src/feature/java") in it.sourceRoots
        }, customRows.toString())
        assertTrue(customRows.any {
            it.attributes["visibility"] == "test" && Path.of("src/verification/java") in it.sourceRoots
        }, customRows.toString())
    }

    @Then("context evaluation leaves workspace inputs unchanged without running Maven")
    fun customTestSourceContextIsReadOnly() {
        assertEquals(customTestSourceBaseline, contents(requireNotNull(container)))
        assertFalse(Files.exists(root.resolve("target")))
    }

    @Given("a Maven reactor has same Java type declarations in {string}")
    fun duplicateJavaTypesFixture(ownership: String) {
        assertTrue(ownership in setOf("independent modules", "one module across source sets"), ownership)
        val owned = Files.createTempDirectory("rk-maven-duplicate-type-scope-")
        container = owned
        root = owned.resolve("workspace")
        val moduleIds = if (ownership == "independent modules") listOf("left", "right") else listOf("app")
        val moduleEntries = moduleIds.joinToString("") { "<module>$it</module>" }
        write(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture.diagnostics</groupId>
              <artifactId>duplicate-types-reactor</artifactId><version>1</version><packaging>pom</packaging>
              <modules>$moduleEntries</modules>
            </project>
        """.trimIndent() + "\n")
        moduleIds.forEach { moduleId ->
            write(root.resolve("$moduleId/pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>fixture.diagnostics</groupId><artifactId>duplicate-types-reactor</artifactId>
                    <version>1</version><relativePath>../pom.xml</relativePath></parent>
                  <artifactId>$moduleId</artifactId>
                </project>
            """.trimIndent() + "\n")
        }
        if (ownership == "independent modules") {
            moduleIds.forEach { moduleId ->
                write(root.resolve("$moduleId/src/main/java/pkg/Shared.java"),
                    "package pkg;\npublic class Shared {}\n")
            }
        } else {
            write(root.resolve("app/src/main/java/pkg/Shared.java"), "package pkg;\npublic class Shared {}\n")
            write(root.resolve("app/src/test/java/pkg/Shared.java"), "package pkg;\npublic class Shared {}\n")
        }
        Files.createDirectories(owned.resolve("empty-maven-repository"))
        baseline = contents(owned)
    }

    @When("Java project diagnostics are requested for those declarations")
    fun requestDuplicateJavaTypeDiagnostics() {
        val snapshot = JavaProjectScanner(
            localMavenRepository = requireNotNull(container).resolve("empty-maven-repository"),
        ).scan(root)
        duplicateSymbolDiagnostics = JavaLanguageAdapter().diagnostics(snapshot)
    }

    @Then("duplicate Java type diagnostics report {string} for effective module ownership")
    fun verifyDuplicateJavaTypeDiagnostics(outcome: String) {
        val duplicates = duplicateSymbolDiagnostics.filter { it.code == "java.duplicateSymbol" }
        when (outcome) {
            "no duplicate" -> assertTrue(duplicates.isEmpty(), duplicates.toString())
            "duplicate symbol" -> {
                assertEquals(2, duplicates.size, duplicates.toString())
                assertTrue(duplicates.all { it.severity == Diagnostic.Severity.ERROR }, duplicates.toString())
                assertTrue(duplicates.all {
                    it.location?.path in setOf(
                        Path.of("app/src/main/java/pkg/Shared.java"),
                        Path.of("app/src/test/java/pkg/Shared.java"),
                    )
                }, duplicates.toString())
            }
            else -> error("Unknown duplicate diagnostic outcome: $outcome")
        }
    }

    @Then("project diagnostics preserve inputs without running Maven")
    fun duplicateJavaTypeDiagnosticsAreReadOnly() {
        assertEquals(baseline, contents(requireNotNull(container)))
        assertFalse(Files.exists(root.resolve("target")))
    }

    @Given("a Maven Java project declares release {string} and lower source {string}")
    fun mavenProjectDeclaresUnsupportedRelease(release: String, source: String) {
        assertEquals("27", release)
        assertEquals("21", source)
        val owned = Files.createTempDirectory("rk-maven-release-27-context-")
        container = owned
        root = owned.resolve("workspace")
        write(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture.release</groupId><artifactId>unsupported-release</artifactId><version>1</version>
              <properties><maven.compiler.release>$release</maven.compiler.release><maven.compiler.source>$source</maven.compiler.source></properties>
              <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version><configuration><release>$release</release><source>$source</source></configuration>
              </plugin></plugins></build>
            </project>
        """.trimIndent() + "\n")
        write(root.resolve("src/main/java/fixture/App.java"), "package fixture; public class App {}\n")
        val repository = owned.resolve("empty-maven-repository")
        Files.createDirectories(repository)
        session = DaemonSession()
        previousHash = requireNotNull(session).dispatch("project.open", buildJsonObject {
            put("root", root.toString())
            put("mavenRepository", repository.toString())
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        baseline = contents(owned)
    }

    @When("project context is requested using the declared Maven release")
    fun requestContextWithUnsupportedMavenRelease() {
        response = requireNotNull(session).dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", previousHash)
            put("sdkHome", System.getProperty("java.home"))
        }).jsonObject
    }

    @Then("project context refuses with {string} without publishing source level {string}")
    fun mavenReleaseContextRefuses(refusal: String, lowerSource: String) {
        assertEquals("java-project-context-v2", response.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("refused", response.getValue("status").jsonPrimitive.content, response.toString())
        assertEquals(refusal, response.getValue("refusal").jsonObject.getValue("code").jsonPrimitive.content, response.toString())
        assertTrue(response.getValue("modules").jsonArray.isEmpty())
        assertEquals("null", response.getValue("selectedSource").toString())
        assertFalse(response.toString().contains("\"sourceLevel\":\"$lowerSource\""), response.toString())
    }

    @Given("a Maven Java 26 project and the verified JDK 26 platform")
    fun verifiedJava26MavenFixture() {
        val configuredSdk = System.getenv("REFACTORKIT_JDK26_HOME")?.takeIf(String::isNotBlank)
        if (configuredSdk == null) {
            assumeTrue(false, "Set REFACTORKIT_JDK26_HOME to the verified Java 26 JDK to run this acceptance")
            return
        }
        mavenJava26SdkHome = Path.of(configuredSdk).toRealPath()
        assertTrue(Files.isRegularFile(mavenJava26SdkHome.resolve("release")))
        assertTrue(Files.isRegularFile(mavenJava26SdkHome.resolve("lib/ct.sym")))

        val owned = Files.createTempDirectory("rk-maven-java26-context-")
        container = owned
        root = owned.resolve("workspace")
        write(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture.release</groupId>
              <artifactId>java26-context</artifactId><version>1</version>
              <properties><maven.compiler.release>26</maven.compiler.release></properties>
              <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>
                <version>3.16.0</version><configuration><release>26</release></configuration>
              </plugin></plugins></build>
            </project>
        """.trimIndent() + "\n")
        write(root.resolve("src/main/java/fixture/Release26Application.java"), """
            package fixture;
            public record Release26Application(String name, int generation) {
                public String displayName() {
                    return name + ":" + generation;
                }
            }
        """.trimIndent() + "\n")
        val repository = owned.resolve("empty-maven-repository")
        Files.createDirectories(repository)

        val javacOutput = owned.resolve("java26-javac.txt")
        val validationClasses = owned.resolve("java26-validation-classes")
        Files.createDirectories(validationClasses)
        val javac = mavenJava26SdkHome.resolve("bin").resolve(
            if (System.getProperty("os.name").startsWith("Windows")) "javac.exe" else "javac",
        )
        val validation = ProcessBuilder(
            javac.toString(), "--release", "26", "-d", validationClasses.toString(),
            root.resolve("src/main/java/fixture/Release26Application.java").toString(),
        ).redirectErrorStream(true).redirectOutput(javacOutput.toFile()).start()
        assertTrue(validation.waitFor(45, TimeUnit.SECONDS), "Java 26 fixture validation compiler timed out")
        assertEquals(0, validation.exitValue(), Files.readString(javacOutput))

        session = DaemonSession()
        previousHash = requireNotNull(session).dispatch("project.open", buildJsonObject {
            put("root", root.toString())
            put("mavenRepository", repository.toString())
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        baseline = contents(owned)
    }

    @When("public Maven context selects the Java 26 source")
    fun requestMavenJava26Context() {
        val active = requireNotNull(session)
        val overview = active.dispatch("project.summary", buildJsonObject {}).jsonObject
        assertEquals("false", overview.getValue("buildModelsTruncated").jsonPrimitive.content)
        val model = overview.getValue("buildModels").jsonArray.map { it.jsonObject }
            .single { it.getValue("providerId").jsonPrimitive.content == "maven-effective-v1" }
        assertEquals("false", model.getValue("truncated").jsonPrimitive.content)
        val moduleId = model.getValue("modules").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content
        response = active.dispatch("project.context.v7", buildJsonObject {
            put("expectedSnapshotHash", previousHash)
            put("sdkHome", mavenJava26SdkHome.toString())
            put("providerId", "maven-effective-v1")
            put("moduleId", moduleId)
            put("sourceSetId", "main")
            put("sourceRelativePath", "fixture/Release26Application.java")
            put("symbol", "fixture.Release26Application")
        }).jsonObject
    }

    @Then("Java 26 context binds source and attests exact release options and platform identity")
    fun assertMavenJava26Context() {
        assertEquals("java-project-context-v7", response.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("available", response.getValue("status").jsonPrimitive.content, response.toString())
        assertEquals(previousHash, response.getValue("snapshotHash").jsonPrimitive.content)
        val selected = response.getValue("selectedSource").jsonObject
        assertEquals("src/main/java/fixture/Release26Application.java", selected.getValue("path").jsonPrimitive.content)
        assertEquals("fixture.Release26Application", selected.getValue("symbol").jsonPrimitive.content)
        assertEquals("maven-effective-v1", selected.getValue("providerId").jsonPrimitive.content)
        assertEquals("JDT_BINDING", selected.getValue("evidence").jsonPrimitive.content)
        assertTrue(selected.getValue("bindingKey").jsonPrimitive.content.isNotBlank())

        val module = response.getValue("modules").jsonArray.map { it.jsonObject }
            .single { it.getValue("providerId").jsonPrimitive.content == "maven-effective-v1" }
        val main = module.getValue("sourceSets").jsonArray.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == "main" }
        assertEquals(26, main.getValue("sourceLevel").jsonPrimitive.content.toInt())
        assertEquals(26, main.getValue("release").jsonPrimitive.content.toInt())
        assertEquals("complete", main.getValue("compilerOptionsAvailability").jsonPrimitive.content)
        val compilerOptions = main.getValue("compilerOptions").jsonArray.map { it.jsonObject }
        assertTrue(compilerOptions.any {
            it.getValue("name").jsonPrimitive.content == "release" &&
                it.getValue("value").jsonPrimitive.content == "26" &&
                it.getValue("origin").jsonPrimitive.content.startsWith("maven-compiler-plugin")
        }, compilerOptions.toString())
        assertTrue(compilerOptions.any {
            it.getValue("name").jsonPrimitive.content == "release" &&
                it.getValue("value").jsonPrimitive.content == "26"
        }, compilerOptions.toString())

        val sdk = response.getValue("sdk").jsonArray.map { it.jsonObject }
            .single { it.getValue("sourceRelease").jsonPrimitive.content.toInt() == 26 }
        assertEquals(mavenJava26SdkHome, Path.of(sdk.getValue("home").jsonPrimitive.content).toRealPath())
        assertEquals(26, sdk.getValue("platformRelease").jsonPrimitive.content.toInt())
        for (name in listOf("identitySha256", "releaseMetadataSha256", "signatureArchiveSha256")) {
            assertTrue(sdk.getValue(name).jsonPrimitive.content.matches(Regex("[a-f0-9]{64}")))
        }
        fun fileSha256(path: Path): String = MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
        assertEquals(fileSha256(mavenJava26SdkHome.resolve("release")),
            sdk.getValue("releaseMetadataSha256").jsonPrimitive.content)
        assertEquals(fileSha256(mavenJava26SdkHome.resolve("lib/ct.sym")),
            sdk.getValue("signatureArchiveSha256").jsonPrimitive.content)
        assertEquals("ct.sym", Path.of(sdk.getValue("signatureArchive").jsonPrimitive.content).fileName.toString())
        val analysis = response.getValue("analysis").jsonObject
        assertEquals("eclipse-jdt", analysis.getValue("backend").jsonPrimitive.content)
        assertEquals(previousHash, analysis.getValue("snapshotHash").jsonPrimitive.content)
        assertEquals(
            sdk.getValue("identitySha256").jsonPrimitive.content,
            analysis.getValue("platformIdentitiesByRelease").jsonObject.getValue("26").jsonPrimitive.content,
        )
    }

    @Given("a Maven Java 27 project and the verified JDK 27 platform")
    fun verifiedJava27MavenFixture() {
        val configuredSdk = System.getenv("REFACTORKIT_JDK27_HOME")?.takeIf(String::isNotBlank)
        if (configuredSdk == null) {
            assumeTrue(false, "Set REFACTORKIT_JDK27_HOME to the verified Java 27 JDK to run this acceptance")
            return
        }
        mavenJava27SdkHome = Path.of(configuredSdk).toRealPath()
        assertEquals(27, Regex("(?m)^JAVA_VERSION=\\\"?(\\d+)")
            .find(Files.readString(mavenJava27SdkHome.resolve("release")))?.groupValues?.get(1)?.toInt())
        assertEquals("7718b4ea47f370bf1a4183b933a446d3609d3e83c20d2edafbcb2c050c24eef0",
            fileSha256(mavenJava27SdkHome.resolve("release")))
        assertEquals("e0b6302dd1d31b9a9d40bd379ce62c625768609c068cdaa3a988f6edfeddcd2e",
            fileSha256(mavenJava27SdkHome.resolve("lib/ct.sym")))

        val owned = Files.createTempDirectory("rk-maven-java27-context-")
        container = owned
        root = owned.resolve("workspace")
        write(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture.release</groupId>
              <artifactId>java27-context</artifactId><version>1</version>
              <properties><maven.compiler.release>27</maven.compiler.release></properties>
              <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>
                <version>3.16.0</version><configuration><release>27</release></configuration>
              </plugin></plugins></build>
            </project>
        """.trimIndent() + "\n")
        write(root.resolve("src/main/java/fixture/Release27Application.java"), """
            package fixture;
            public record Release27Application(String name, int generation) {
                public String displayName() {
                    return name + ":" + generation;
                }
            }
        """.trimIndent() + "\n")
        val repository = owned.resolve("empty-maven-repository")
        Files.createDirectories(repository)

        val validationClasses = owned.resolve("java27-validation-classes")
        Files.createDirectories(validationClasses)
        val javac = mavenJava27SdkHome.resolve("bin").resolve(
            if (System.getProperty("os.name").startsWith("Windows")) "javac.exe" else "javac",
        )
        val javacOutput = owned.resolve("java27-javac.txt")
        val validation = ProcessBuilder(
            javac.toString(), "--release", "27", "-d", validationClasses.toString(),
            root.resolve("src/main/java/fixture/Release27Application.java").toString(),
        ).redirectErrorStream(true).redirectOutput(javacOutput.toFile()).start()
        assertTrue(validation.waitFor(45, TimeUnit.SECONDS), "Java 27 fixture validation compiler timed out")
        assertEquals(0, validation.exitValue(), Files.readString(javacOutput))

        session = DaemonSession()
        previousHash = requireNotNull(session).dispatch("project.open", buildJsonObject {
            put("root", root.toString())
            put("mavenRepository", repository.toString())
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        baseline = contents(root)
    }

    @When("the provider-qualified Maven v7 metadata context handles a Java {string} request")
    fun requestMavenJava27SemanticOperation(operation: String) {
        assertTrue(operation in setOf("definition", "class rename"))
        java27SemanticOperation = operation
        java27SemanticQueryResponse = null
        java27SemanticPreviewFailure = null

        val active = requireNotNull(session)
        val summary = active.dispatch("project.summary", null).jsonObject
        val maven = summary.getValue("buildModels").jsonArray.map { it.jsonObject }
            .single { it.getValue("providerId").jsonPrimitive.content == "maven-effective-v1" }
        val module = maven.getValue("modules").jsonArray.single().jsonObject
        response = active.dispatch("project.context.v7", buildJsonObject {
            put("expectedSnapshotHash", previousHash)
            put("sdkHome", mavenJava27SdkHome.toString())
            put("providerId", "maven-effective-v1")
            put("moduleId", module.getValue("id").jsonPrimitive.content)
            put("sourceSetId", "main")
        }).jsonObject
        assertEquals("java-project-context-v7", response.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("available", response.getValue("status").jsonPrimitive.content, response.toString())
        assertEquals("unqualified", response.getValue("semanticAnalysis").jsonObject
            .getValue("availability").jsonPrimitive.content)
        assertEquals("java.context.sourceAnalysisUnqualified", response.getValue("semanticAnalysis").jsonObject
            .getValue("refusalCode").jsonPrimitive.content)
        val contextModule = response.getValue("modules").jsonArray.map { it.jsonObject }
            .single { it.getValue("providerId").jsonPrimitive.content == "maven-effective-v1" }
        assertEquals(module.getValue("id"), contextModule.getValue("id"))
        val main = contextModule.getValue("sourceSets").jsonArray.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == "main" }
        assertEquals(27, main.getValue("sourceLevel").jsonPrimitive.content.toInt())
        assertEquals(27, main.getValue("release").jsonPrimitive.content.toInt())

        val javaContext = buildJsonObject {
            put("sdkHome", mavenJava27SdkHome.toString())
            put("expectedContextHash", response.getValue("contextHash").jsonPrimitive.content)
            put("contextSchemaVersion", response.getValue("schemaVersion").jsonPrimitive.content)
        }
        when (operation) {
            "definition" -> {
                java27SemanticQueryResponse = active.dispatch("intelligence.query", buildJsonObject {
                    put("requestId", "maven-java27-definition")
                    put("kind", "definition")
                    put("languageId", "java")
                    put("expectedSnapshotHash", previousHash)
                    put("path", "src/main/java/fixture/Release27Application.java")
                    put("position", buildJsonObject { put("line", 1); put("character", 14) })
                    put("sourceAuthority", buildJsonObject { put("kind", "saved-snapshot") })
                    put("javaContext", javaContext)
                }).jsonObject
            }
            "class rename" -> {
                java27SemanticPreviewFailure = assertFailsWith<JsonRpcException> {
                    active.dispatch("refactor.preview", buildJsonObject {
                        put("operation", "renameClass")
                        put("languageId", "java")
                        put("symbol", "fixture.Release27Application")
                        put("expectedSnapshotHash", previousHash)
                        put("javaContext", javaContext)
                        put("arguments", buildJsonObject { put("newName", "Release27Renamed") })
                    })
                }
            }
        }
    }

    @Then("the Java {string} request is refused with {string}")
    fun assertMavenJava27SemanticRefusal(operation: String, refusalCode: String) {
        assertEquals(operation, java27SemanticOperation)
        assertEquals("java.context.sourceAnalysisUnqualified", refusalCode)
        when (operation) {
            "definition" -> {
                val query = requireNotNull(java27SemanticQueryResponse)
                assertEquals("refused", query.getValue("status").jsonPrimitive.content, query.toString())
                assertEquals("definition", query.getValue("kind").jsonPrimitive.content)
                assertEquals(refusalCode, query.getValue("error").jsonObject
                    .getValue("code").jsonPrimitive.content)
                assertFalse("planId" in query)
            }
            "class rename" -> {
                val previewFailure = requireNotNull(java27SemanticPreviewFailure)
                assertEquals(JsonRpcErrorCodes.PLAN_REFUSED, previewFailure.code)
                assertEquals(refusalCode, previewFailure.data?.jsonObject?.get("refusalCode")
                    ?.jsonPrimitive?.content)
            }
            else -> error("Unexpected Java 27 operation: $operation")
        }
    }

    @And("the Maven Java 27 source and project inputs remain unchanged with no preview plan or managed transaction")
    fun assertMavenJava27SemanticRefusalPreservesInputs() {
        assertEquals(baseline, contents(root), "A refused Java 27 semantic request must preserve project inputs")
        assertFalse(Files.exists(root.resolve("target")), "Metadata and semantic refusal must not execute Maven")
        assertTrue(TransactionLog(root.resolve(".refactorkit/transactions")).listRecords().isEmpty(),
            "Refused semantic requests must not create a managed transaction")
    }

    @Given("a multi-module Maven Java 27 project and the verified JDK 27 platform")
    fun verifiedJava27MavenReactorFixture() {
        val configuredSdk = System.getenv("REFACTORKIT_JDK27_HOME")?.takeIf(String::isNotBlank)
        if (configuredSdk == null) {
            assumeTrue(false, "Set REFACTORKIT_JDK27_HOME to the verified Java 27 JDK to run this acceptance")
            return
        }
        mavenJava27SdkHome = Path.of(configuredSdk).toRealPath()
        assertEquals(27, Regex("(?m)^JAVA_VERSION=\\\"?(\\d+)")
            .find(Files.readString(mavenJava27SdkHome.resolve("release")))?.groupValues?.get(1)?.toInt())
        assertEquals("7718b4ea47f370bf1a4183b933a446d3609d3e83c20d2edafbcb2c050c24eef0",
            fileSha256(mavenJava27SdkHome.resolve("release")))
        assertEquals("e0b6302dd1d31b9a9d40bd379ce62c625768609c068cdaa3a988f6edfeddcd2e",
            fileSha256(mavenJava27SdkHome.resolve("lib/ct.sym")))

        val owned = Files.createTempDirectory("rk-maven-java27-reactor-context-")
        container = owned
        root = owned.resolve("workspace")
        write(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture.release</groupId>
              <artifactId>java27-reactor</artifactId><version>1</version><packaging>pom</packaging>
              <modules><module>library</module><module>app</module></modules>
              <properties><maven.compiler.release>27</maven.compiler.release></properties>
              <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version><configuration><release>27</release></configuration>
              </plugin></plugins></build>
            </project>
        """.trimIndent() + "\n")
        write(root.resolve("library/pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion>
              <parent><groupId>fixture.release</groupId><artifactId>java27-reactor</artifactId><version>1</version><relativePath>../pom.xml</relativePath></parent>
              <artifactId>library</artifactId>
            </project>
        """.trimIndent() + "\n")
        write(root.resolve("app/pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion>
              <parent><groupId>fixture.release</groupId><artifactId>java27-reactor</artifactId><version>1</version><relativePath>../pom.xml</relativePath></parent>
              <artifactId>app</artifactId>
              <dependencies><dependency><groupId>fixture.release</groupId><artifactId>library</artifactId><version>1</version></dependency></dependencies>
            </project>
        """.trimIndent() + "\n")
        write(root.resolve("library/src/main/java/fixture/Release27Base.java"), """
            package fixture;
            public class Release27Base {
                public String message(int generation) { return "generation:" + generation; }
            }
        """.trimIndent() + "\n")
        write(root.resolve("app/src/main/java/fixture/Release27Application.java"), """
            package fixture;
            public final class Release27Application extends Release27Base {
                public String displayName() { return message(27); }
            }
        """.trimIndent() + "\n")
        val repository = owned.resolve("empty-maven-repository")
        Files.createDirectories(repository)
        session = DaemonSession()
        previousHash = requireNotNull(session).dispatch("project.open", buildJsonObject {
            put("root", root.toString())
            put("mavenRepository", repository.toString())
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        baseline = contents(root)
    }

    @When("provider-qualified Java context v6 is requested for Maven metadata")
    fun requestMavenJava27MetadataContext() {
        val active = requireNotNull(session)
        val summary = active.dispatch("project.summary", null).jsonObject
        val maven = summary.getValue("buildModels").jsonArray.map { it.jsonObject }
            .single { it.getValue("providerId").jsonPrimitive.content == "maven-effective-v1" }
        val moduleId = maven.getValue("modules").jsonArray.single().jsonObject
            .getValue("id").jsonPrimitive.content
        response = active.dispatch("project.context.v6", buildJsonObject {
            put("expectedSnapshotHash", previousHash)
            put("sdkHome", mavenJava27SdkHome.toString())
            put("providerId", "maven-effective-v1")
            put("moduleId", moduleId)
            put("sourceSetId", "main")
        }).jsonObject
    }

    @Then("the Java 27 Maven context reports metadata with unqualified source analysis")
    fun assertMavenJava27MetadataContext() {
        assertEquals("java-project-context-v6", response.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("available", response.getValue("status").jsonPrimitive.content, response.toString())
        assertEquals(previousHash, response.getValue("snapshotHash").jsonPrimitive.content)
        assertTrue(response.getValue("contextHash").jsonPrimitive.content.matches(Regex("[a-f0-9]{64}")))
        assertEquals(JsonNull, response.getValue("selectedSource"))
        assertEquals(JsonNull, response.getValue("analysis"))
        assertEquals(JsonNull, response.getValue("refusal"))
        val semanticAnalysis = response.getValue("semanticAnalysis").jsonObject
        assertEquals(setOf("availability", "refusalCode"), semanticAnalysis.keys)
        assertEquals("unqualified", semanticAnalysis.getValue("availability").jsonPrimitive.content)
        assertEquals("java.context.sourceAnalysisUnqualified",
            semanticAnalysis.getValue("refusalCode").jsonPrimitive.content)

        val capability = requireNotNull(session).dispatch("server.capabilities", null).jsonObject
            .getValue("methods").jsonArray.map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == "project.context.v6" }
        val features = capability.getValue("features").jsonObject
        assertEquals("true", features.getValue("typedSchemaV6").jsonPrimitive.content)
        assertEquals("true", features.getValue("providerQualifiedSourceAssociation").jsonPrimitive.content)
        assertEquals("true", features.getValue("metadataOnlyUnqualifiedJavaSourceAnalysis").jsonPrimitive.content)

        val module = response.getValue("modules").jsonArray.map { it.jsonObject }.single {
            it.getValue("providerId").jsonPrimitive.content == "maven-effective-v1"
        }
        val main = module.getValue("sourceSets").jsonArray.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == "main" }
        assertEquals(27, main.getValue("sourceLevel").jsonPrimitive.content.toInt())
        assertEquals(27, main.getValue("release").jsonPrimitive.content.toInt())
        assertEquals("complete", main.getValue("compilerOptionsAvailability").jsonPrimitive.content)
        assertTrue(main.getValue("compilerOptions").jsonArray.map { it.jsonObject }.any {
            it.getValue("name").jsonPrimitive.content == "release" && it.getValue("value").jsonPrimitive.content == "27"
        })
        val jpms = main.getValue("jpms").jsonObject
        assertEquals("unavailable", jpms.getValue("availability").jsonPrimitive.content)
        assertEquals("java.context.sourceAnalysisUnqualified", jpms.getValue("refusalCode").jsonPrimitive.content)

        val sdk = response.getValue("sdk").jsonArray.map { it.jsonObject }
            .single { it.getValue("sourceRelease").jsonPrimitive.content.toInt() == 27 }
        assertEquals(mavenJava27SdkHome, Path.of(sdk.getValue("home").jsonPrimitive.content).toRealPath())
        assertEquals(27, sdk.getValue("platformRelease").jsonPrimitive.content.toInt())
        assertEquals("7718b4ea47f370bf1a4183b933a446d3609d3e83c20d2edafbcb2c050c24eef0",
            sdk.getValue("releaseMetadataSha256").jsonPrimitive.content)
        assertEquals("e0b6302dd1d31b9a9d40bd379ce62c625768609c068cdaa3a988f6edfeddcd2e",
            sdk.getValue("signatureArchiveSha256").jsonPrimitive.content)
    }

    @Then("Java 27 source selection is refused with {string}")
    fun refuseMavenJava27SourceSelection(refusal: String) {
        assertEquals("java.context.sourceAnalysisUnqualified", refusal)
        val active = requireNotNull(session)
        val summary = active.dispatch("project.summary", null).jsonObject
        val maven = summary.getValue("buildModels").jsonArray.map { it.jsonObject }
            .single { it.getValue("providerId").jsonPrimitive.content == "maven-effective-v1" }
        val moduleId = maven.getValue("modules").jsonArray.single().jsonObject
            .getValue("id").jsonPrimitive.content
        sourceSelectionResponse = active.dispatch("project.context.v6", buildJsonObject {
            put("expectedSnapshotHash", previousHash)
            put("sdkHome", mavenJava27SdkHome.toString())
            put("providerId", "maven-effective-v1")
            put("moduleId", moduleId)
            put("sourceSetId", "main")
            put("sourceRelativePath", "fixture/Release27Application.java")
        }).jsonObject
        assertEquals("java-project-context-v6", sourceSelectionResponse.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("refused", sourceSelectionResponse.getValue("status").jsonPrimitive.content)
        assertEquals(refusal, sourceSelectionResponse.getValue("refusal").jsonObject
            .getValue("code").jsonPrimitive.content)
        assertEquals("unqualified", sourceSelectionResponse.getValue("semanticAnalysis").jsonObject
            .getValue("availability").jsonPrimitive.content)
        assertEquals(JsonNull, sourceSelectionResponse.getValue("selectedSource"))
        assertEquals(JsonNull, sourceSelectionResponse.getValue("analysis"))
        assertTrue(sourceSelectionResponse.getValue("modules").jsonArray.isEmpty())
        assertEquals(baseline, contents(root), "Java 27 context acquisition must preserve every project input byte")
        assertFalse(Files.exists(root.resolve("target")), "metadata discovery must not execute Maven")
    }

    @When("provider-qualified Java context v7 is requested for Maven metadata")
    fun requestMavenJava27MetadataContextV7() {
        val active = requireNotNull(session)
        val summary = active.dispatch("project.summary", null).jsonObject
        val maven = summary.getValue("buildModels").jsonArray.map { it.jsonObject }
            .single { it.getValue("providerId").jsonPrimitive.content == "maven-effective-v1" }
        val projectModules = maven.getValue("modules").jsonArray.map { it.jsonObject }
        val selectedModule = projectModules.singleOrNull { it.getValue("id").jsonPrimitive.content == "app" }
            ?: projectModules.single()
        val moduleId = selectedModule
            .getValue("id").jsonPrimitive.content
        response = active.dispatch("project.context.v7", buildJsonObject {
            put("expectedSnapshotHash", previousHash)
            put("sdkHome", mavenJava27SdkHome.toString())
            put("providerId", "maven-effective-v1")
            put("moduleId", moduleId)
            put("sourceSetId", "main")
        }).jsonObject
    }

    @When("provider-qualified Java context v7 selects Maven source owner {string}")
    fun requestMavenJava27SourceOwnerV7(sourceRelativePath: String) {
        assertEquals("fixture/Release27Application.java", sourceRelativePath)
        val active = requireNotNull(session)
        val summary = active.dispatch("project.summary", null).jsonObject
        val maven = summary.getValue("buildModels").jsonArray.map { it.jsonObject }
            .single { it.getValue("providerId").jsonPrimitive.content == "maven-effective-v1" }
        val moduleId = maven.getValue("modules").jsonArray.single().jsonObject
            .getValue("id").jsonPrimitive.content
        val metadataContext = active.dispatch("project.context.v7", buildJsonObject {
            put("expectedSnapshotHash", previousHash)
            put("sdkHome", mavenJava27SdkHome.toString())
            put("providerId", "maven-effective-v1")
            put("moduleId", moduleId)
            put("sourceSetId", "main")
        }).jsonObject
        assertEquals("available", metadataContext.getValue("status").jsonPrimitive.content, metadataContext.toString())
        response = active.dispatch("project.context.v7", buildJsonObject {
            put("expectedSnapshotHash", previousHash)
            put("expectedContextHash", metadataContext.getValue("contextHash").jsonPrimitive.content)
            put("sdkHome", mavenJava27SdkHome.toString())
            put("providerId", "maven-effective-v1")
            put("moduleId", moduleId)
            put("sourceSetId", "main")
            put("sourceRelativePath", sourceRelativePath)
        }).jsonObject
    }

    @When("provider-qualified Java context v7 selects Maven source {string} and symbol {string}")
    fun requestMavenJava27SymbolV7(sourceRelativePath: String, symbol: String) {
        assertEquals("fixture/Release27Application.java", sourceRelativePath)
        assertEquals("fixture.Release27Application", symbol)
        val active = requireNotNull(session)
        val summary = active.dispatch("project.summary", null).jsonObject
        val maven = summary.getValue("buildModels").jsonArray.map { it.jsonObject }
            .single { it.getValue("providerId").jsonPrimitive.content == "maven-effective-v1" }
        val moduleId = maven.getValue("modules").jsonArray.single().jsonObject
            .getValue("id").jsonPrimitive.content
        response = active.dispatch("project.context.v7", buildJsonObject {
            put("expectedSnapshotHash", previousHash)
            put("sdkHome", mavenJava27SdkHome.toString())
            put("providerId", "maven-effective-v1")
            put("moduleId", moduleId)
            put("sourceSetId", "main")
            put("sourceRelativePath", sourceRelativePath)
            put("symbol", symbol)
        }).jsonObject
    }

    @Then("the Java 27 Maven v7 context reports metadata with unqualified source analysis")
    fun assertMavenJava27MetadataContextV7() {
        assertEquals("java-project-context-v7", response.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("available", response.getValue("status").jsonPrimitive.content, response.toString())
        assertEquals(previousHash, response.getValue("snapshotHash").jsonPrimitive.content)
        assertTrue(response.getValue("contextHash").jsonPrimitive.content.matches(Regex("[a-f0-9]{64}")))
        assertEquals(JsonNull, response.getValue("selectedSource"))
        assertEquals(JsonNull, response.getValue("analysis"))
        assertEquals(JsonNull, response.getValue("refusal"))
        val semanticAnalysis = response.getValue("semanticAnalysis").jsonObject
        assertEquals("unqualified", semanticAnalysis.getValue("availability").jsonPrimitive.content)
        assertEquals("java.context.sourceAnalysisUnqualified",
            semanticAnalysis.getValue("refusalCode").jsonPrimitive.content)

        val modules = response.getValue("modules").jsonArray.map { it.jsonObject }
            .filter { it.getValue("providerId").jsonPrimitive.content == "maven-effective-v1" }
        val module = modules.singleOrNull { it.getValue("id").jsonPrimitive.content == "app" }
            ?: modules.single()
        val main = module.getValue("sourceSets").jsonArray.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == "main" }
        assertEquals(27, main.getValue("sourceLevel").jsonPrimitive.content.toInt())
        assertEquals(27, main.getValue("release").jsonPrimitive.content.toInt())
        assertEquals("complete", main.getValue("compilerOptionsAvailability").jsonPrimitive.content)
        assertTrue(main.getValue("compilerOptions").jsonArray.map { it.jsonObject }.any {
            it.getValue("name").jsonPrimitive.content == "release" && it.getValue("value").jsonPrimitive.content == "27"
        })
        assertEquals("not-applicable", main.getValue("mavenTestJvm").jsonObject
            .getValue("availability").jsonPrimitive.content)
        if (modules.size > 1) {
            assertEquals(setOf("app", "library"), modules.map { it.getValue("id").jsonPrimitive.content }.toSet())
            val moduleDependencies = main.getValue("moduleDependencies").jsonArray.map { it.jsonObject }
            assertTrue(moduleDependencies.any {
                it.getValue("moduleId").jsonPrimitive.content == "library" &&
                    it.getValue("scope").jsonPrimitive.content == "compile"
            }, "The Java 27 metadata context lost the app-to-library reactor dependency edge")
            val libraryMain = modules.single { it.getValue("id").jsonPrimitive.content == "library" }
                .getValue("sourceSets").jsonArray.map { it.jsonObject }
                .single { it.getValue("id").jsonPrimitive.content == "main" }
            assertEquals(27, libraryMain.getValue("sourceLevel").jsonPrimitive.content.toInt())
            assertEquals(27, libraryMain.getValue("release").jsonPrimitive.content.toInt())
        }

        val sdk = response.getValue("sdk").jsonArray.map { it.jsonObject }
            .single { it.getValue("sourceRelease").jsonPrimitive.content.toInt() == 27 }
        assertEquals(mavenJava27SdkHome, Path.of(sdk.getValue("home").jsonPrimitive.content).toRealPath())
        assertEquals(27, sdk.getValue("platformRelease").jsonPrimitive.content.toInt())
        assertEquals("7718b4ea47f370bf1a4183b933a446d3609d3e83c20d2edafbcb2c050c24eef0",
            sdk.getValue("releaseMetadataSha256").jsonPrimitive.content)
        assertEquals("e0b6302dd1d31b9a9d40bd379ce62c625768609c068cdaa3a988f6edfeddcd2e",
            sdk.getValue("signatureArchiveSha256").jsonPrimitive.content)
        val capability = requireNotNull(session).dispatch("server.capabilities", null).jsonObject
            .getValue("methods").jsonArray.map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == "project.context.v7" }
        val features = capability.getValue("features").jsonObject
        assertEquals("true", features.getValue("standardJpmsDirectives").jsonPrimitive.content)
        assertEquals("true", features.getValue("metadataOnlyUnqualifiedJavaSourceAnalysis").jsonPrimitive.content)
    }

    @Then("the v7 Java context reports owner {string} without a qualified JDT binding")
    fun assertMavenJava27SourceOwnerV7(sourceRelativePath: String) {
        assertEquals("java-project-context-v7", response.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("available", response.getValue("status").jsonPrimitive.content, response.toString())
        assertEquals(previousHash, response.getValue("snapshotHash").jsonPrimitive.content)
        assertTrue(response.getValue("contextHash").jsonPrimitive.content.matches(Regex("[a-f0-9]{64}")))
        assertEquals(JsonNull, response.getValue("analysis"))
        assertEquals(JsonNull, response.getValue("refusal"))
        val semanticAnalysis = response.getValue("semanticAnalysis").jsonObject
        assertEquals("unqualified", semanticAnalysis.getValue("availability").jsonPrimitive.content)
        assertEquals("java.context.sourceAnalysisUnqualified",
            semanticAnalysis.getValue("refusalCode").jsonPrimitive.content)

        val module = response.getValue("modules").jsonArray.map { it.jsonObject }
            .single { it.getValue("providerId").jsonPrimitive.content == "maven-effective-v1" }
        val main = module.getValue("sourceSets").jsonArray.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == "main" }
        val selected = response.getValue("selectedSource").jsonObject
        assertEquals(setOf("path", "symbol", "providerId", "moduleId", "sourceSetId", "sourceRoot", "generated", "bindingKey", "evidence"),
            selected.keys)
        assertEquals("src/main/java/$sourceRelativePath", selected.getValue("path").jsonPrimitive.content)
        assertEquals(JsonNull, selected.getValue("symbol"))
        assertEquals("maven-effective-v1", selected.getValue("providerId").jsonPrimitive.content)
        assertEquals(module.getValue("id").jsonPrimitive.content, selected.getValue("moduleId").jsonPrimitive.content)
        assertEquals(main.getValue("id").jsonPrimitive.content, selected.getValue("sourceSetId").jsonPrimitive.content)
        assertEquals("src/main/java", selected.getValue("sourceRoot").jsonPrimitive.content)
        assertEquals("false", selected.getValue("generated").jsonPrimitive.content)
        assertEquals(JsonNull, selected.getValue("bindingKey"))
        assertEquals("SOURCE_ROOT_OWNERSHIP", selected.getValue("evidence").jsonPrimitive.content)
        assertEquals(baseline, contents(root), "source owner lookup must preserve every Java 27 project input byte")
        assertFalse(Files.exists(root.resolve("target")), "source owner lookup must not execute Maven")
    }

    @Then("Java 27 Maven v7 source analysis matches the configured JDT qualification and preserves project bytes without running Maven")
    fun assertMavenJava27V7AnalysisQualification() {
        val expected = System.getenv("REFACTORKIT_EXPECT_JAVA27_ANALYSIS")?.lowercase() ?: "auto"
        assertTrue(expected in setOf("auto", "available", "refused"),
            "REFACTORKIT_EXPECT_JAVA27_ANALYSIS must be auto, available, or refused")
        assertEquals("java-project-context-v7", response.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals(previousHash, response.getValue("snapshotHash").jsonPrimitive.content)

        when (response.getValue("status").jsonPrimitive.content) {
            "available" -> {
                assertTrue(expected != "refused", response.toString())
                assertTrue(response.getValue("contextHash").jsonPrimitive.content.matches(Regex("[a-f0-9]{64}")))
                assertEquals(JsonNull, response.getValue("refusal"))
                val semanticAnalysis = response.getValue("semanticAnalysis").jsonObject
                assertEquals("available", semanticAnalysis.getValue("availability").jsonPrimitive.content)
                assertEquals(JsonNull, semanticAnalysis.getValue("refusalCode"))
                val selected = response.getValue("selectedSource").jsonObject
                assertEquals("src/main/java/fixture/Release27Application.java",
                    selected.getValue("path").jsonPrimitive.content)
                assertEquals("fixture.Release27Application", selected.getValue("symbol").jsonPrimitive.content)
                assertEquals("maven-effective-v1", selected.getValue("providerId").jsonPrimitive.content)
                assertEquals("JDT_BINDING", selected.getValue("evidence").jsonPrimitive.content)
                assertTrue(selected.getValue("bindingKey").jsonPrimitive.content.isNotBlank())

                val module = response.getValue("modules").jsonArray.map { it.jsonObject }
                    .single { it.getValue("providerId").jsonPrimitive.content == "maven-effective-v1" }
                val main = module.getValue("sourceSets").jsonArray.map { it.jsonObject }
                    .single { it.getValue("id").jsonPrimitive.content == "main" }
                assertEquals(27, main.getValue("sourceLevel").jsonPrimitive.content.toInt())
                assertEquals(27, main.getValue("release").jsonPrimitive.content.toInt())
                assertEquals("complete", main.getValue("compilerOptionsAvailability").jsonPrimitive.content)
                assertTrue(main.getValue("compilerOptions").jsonArray.map { it.jsonObject }.any {
                    it.getValue("name").jsonPrimitive.content == "release" &&
                        it.getValue("value").jsonPrimitive.content == "27"
                })

                val sdk = response.getValue("sdk").jsonArray.map { it.jsonObject }
                    .single { it.getValue("sourceRelease").jsonPrimitive.content.toInt() == 27 }
                assertEquals(27, sdk.getValue("platformRelease").jsonPrimitive.content.toInt())
                assertEquals("7718b4ea47f370bf1a4183b933a446d3609d3e83c20d2edafbcb2c050c24eef0",
                    sdk.getValue("releaseMetadataSha256").jsonPrimitive.content)
                assertEquals(fileSha256(mavenJava27SdkHome.resolve("lib/ct.sym")),
                    sdk.getValue("signatureArchiveSha256").jsonPrimitive.content)
                val analysis = response.getValue("analysis").jsonObject
                assertEquals("eclipse-jdt", analysis.getValue("backend").jsonPrimitive.content)
                assertEquals(previousHash, analysis.getValue("snapshotHash").jsonPrimitive.content)
                assertEquals(sdk.getValue("identitySha256").jsonPrimitive.content,
                    analysis.getValue("platformIdentitiesByRelease").jsonObject
                        .getValue("27").jsonPrimitive.content)
            }
            "refused" -> {
                assertTrue(expected != "available", response.toString())
                assertEquals(JsonNull, response.getValue("contextHash"))
                assertEquals("java.context.sourceAnalysisUnqualified", response.getValue("refusal")
                    .jsonObject.getValue("code").jsonPrimitive.content)
                assertEquals("unqualified", response.getValue("semanticAnalysis")
                    .jsonObject.getValue("availability").jsonPrimitive.content)
                assertEquals(JsonNull, response.getValue("selectedSource"))
                assertEquals(JsonNull, response.getValue("analysis"))
                assertTrue(response.getValue("modules").jsonArray.isEmpty())
            }
            else -> error("Unexpected Java 27 v7 context status: ${response.getValue("status")}")
        }
        assertFalse(Files.exists(root.resolve("target")), "context analysis must not execute Maven")
    }

    @And("Maven Java 27 v7 source selection is refused with {string}")
    fun refuseMavenJava27SourceSelectionV7(refusal: String) {
        assertEquals("java.context.sourceAnalysisUnqualified", refusal)
        val active = requireNotNull(session)
        val modules = response.getValue("modules").jsonArray.map { it.jsonObject }
            .filter { it.getValue("providerId").jsonPrimitive.content == "maven-effective-v1" }
        val module = modules.singleOrNull { it.getValue("id").jsonPrimitive.content == "app" }
            ?: modules.single()
        val moduleId = module.getValue("id").jsonPrimitive.content
        sourceSelectionResponse = active.dispatch("project.context.v7", buildJsonObject {
            put("expectedSnapshotHash", previousHash)
            put("sdkHome", mavenJava27SdkHome.toString())
            put("providerId", "maven-effective-v1")
            put("moduleId", moduleId)
            put("sourceSetId", "main")
            put("symbol", "fixture.Release27Application")
        }).jsonObject
        assertEquals("java-project-context-v7", sourceSelectionResponse.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("refused", sourceSelectionResponse.getValue("status").jsonPrimitive.content)
        assertEquals(refusal, sourceSelectionResponse.getValue("refusal").jsonObject
            .getValue("code").jsonPrimitive.content)
        assertEquals("unqualified", sourceSelectionResponse.getValue("semanticAnalysis").jsonObject
            .getValue("availability").jsonPrimitive.content)
        assertEquals(JsonNull, sourceSelectionResponse.getValue("selectedSource"))
        assertEquals(JsonNull, sourceSelectionResponse.getValue("analysis"))
        assertTrue(sourceSelectionResponse.getValue("modules").jsonArray.isEmpty())
        assertEquals(baseline, contents(root), "Java 27 v7 context acquisition must preserve every project input byte")
        assertFalse(Files.exists(root.resolve("target")), "metadata discovery must not execute Maven")
    }

    @Given("a Maven module declares Build Helper add-source ownership {string} for a non-generated external root")
    fun mavenBuildHelperRootFixture(ownership: String) {
        assertTrue(ownership in setOf("unique", "duplicate"))
        val owned = Files.createTempDirectory("rk-build-helper-source-root-"); container = owned
        root = owned.resolve("workspace")
        write(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture.roots</groupId><artifactId>root</artifactId>
              <version>1</version><packaging>pom</packaging><modules><module>app</module><module>unrelated</module></modules>
              <properties><maven.compiler.release>8</maven.compiler.release></properties>
            </project>
        """.trimIndent() + "\n")
        write(root.resolve("app/pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion>
              <parent><groupId>fixture.roots</groupId><artifactId>root</artifactId><version>1</version><relativePath>../pom.xml</relativePath></parent>
              <artifactId>app</artifactId>
            </project>
        """.trimIndent() + "\n")
        write(root.resolve("app/src/main/java/fixtures/app/AppType.java"),
            "package fixtures.app; public class AppType {}\n")
        val duplicateExecution = if (ownership == "duplicate") """
            <execution><id>register-extra-again</id><phase>generate-sources</phase><goals><goal>add-source</goal></goals>
              <configuration><sources><source>src/extra/java</source></sources></configuration></execution>
        """.trimIndent() else ""
        write(root.resolve("unrelated/pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion>
              <parent><groupId>fixture.roots</groupId><artifactId>root</artifactId><version>1</version><relativePath>../pom.xml</relativePath></parent>
              <artifactId>unrelated</artifactId>
              <build><plugins>
                <plugin><groupId>org.codehaus.mojo</groupId><artifactId>build-helper-maven-plugin</artifactId><version>3.6.0</version>
                  <executions>
                    <execution><id>register-extra</id><phase>generate-sources</phase><goals><goal>add-source</goal></goals>
                      <configuration><sources><source>src/extra/java</source></sources></configuration>
                    </execution>
                    $duplicateExecution
                  </executions>
                </plugin>
              </plugins></build>
            </project>
        """.trimIndent() + "\n")
        write(root.resolve("unrelated/src/main/java/fixtures/unrelated/MainType.java"),
            "package fixtures.unrelated; class MainType {}\n")
        write(root.resolve("unrelated/src/test/java/fixtures/unrelated/TestType.java"),
            "package fixtures.unrelated; class TestType {}\n")
        if (ownership == "unique") write(root.resolve("unrelated/src/extra/java/fixtures/unrelated/Extra.java"),
            "package fixtures.unrelated; public class Extra {}\n")
        val repository = owned.resolve("empty-maven-repository")
        Files.createDirectories(repository)
        val active = DaemonSession(); session = active
        previousHash = active.dispatch("project.open", buildJsonObject {
            put("root", root.toString()); put("mavenRepository", repository.toString())
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        val discovered = active.dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", previousHash); put("sdkHome", System.getProperty("java.home"))
        }).jsonObject
        val aggregate = active.dispatch("project.context.aggregate", buildJsonObject {
            put("expectedSnapshotHash", previousHash); put("sdkHome", System.getProperty("java.home"))
        }).jsonObject
        assertEquals("java-project-context-v3", aggregate.getValue("schemaVersion").jsonPrimitive.content,
            aggregate.toString())
        val aggregateModule = aggregate.getValue("modules").jsonArray.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == "unrelated" }
        val aggregateRootSets = sourceSets(aggregateModule).values.filter {
            "unrelated/src/extra/java" in strings(it, "sourceRoots")
        }
        assertEquals(if (ownership == "unique") 1 else 2, aggregateRootSets.size,
            "aggregate project context must preserve Build Helper root ownership: ${aggregateRootSets}")
        if (ownership == "unique") writeBuildHelperV2ContextResponse(discovered)
        assertTrue(discovered.getValue("status").jsonPrimitive.content in setOf("available", "partial"), discovered.toString())
        assertEquals("available", discovered.getValue("providers").jsonArray.single().jsonObject
            .getValue("status").jsonPrimitive.content, discovered.toString())
        assertEquals("java-project-context-v2", discovered.getValue("schemaVersion").jsonPrimitive.content)
        val discoveredModules = discovered.getValue("modules").jsonArray.map { it.jsonObject }
        buildHelperV2ModuleIds = discoveredModules.map {
            it.getValue("id").jsonPrimitive.content
        }.toSet()
        buildHelperRootModuleOwners = discoveredModules.filter { module ->
            sourceSets(module).values.any { "unrelated/src/extra/java" in strings(it, "sourceRoots") }
        }.map { it.getValue("id").jsonPrimitive.content }.toSet()
        val module = discoveredModules.single { it.getValue("id").jsonPrimitive.content == "unrelated" }
        val rootSets = sourceSets(module).values.filter { "unrelated/src/extra/java" in strings(it, "sourceRoots") }
        assertEquals(if (ownership == "unique") 1 else 2, rootSets.size, rootSets.toString())
        buildHelperSourceSetIds = rootSets.map { it.getValue("id").jsonPrimitive.content }
        assertTrue(buildHelperSourceSetIds.all { it.startsWith("maven-custom-main-") }, buildHelperSourceSetIds.toString())
        baseline = contents(owned)
    }

    @Then("the public Maven project context advertises sibling modules {string} and {string}")
    fun publicMavenProjectContextRetainsSiblingModules(first: String, second: String) {
        assertEquals(setOf(first, second), buildHelperV2ModuleIds)
    }

    @Then("the Build Helper root is owned only by Maven module {string}")
    fun buildHelperRootHasOneModuleOwner(moduleId: String) {
        assertEquals(setOf(moduleId), buildHelperRootModuleOwners)
    }

    @When("provider-qualified project context is requested with {string} root evidence")
    fun acquireBuildHelperRootContext(snapshot: String) {
        assertTrue(snapshot in setOf("current", "stale"))
        if (snapshot == "stale") {
            val pom = root.resolve("unrelated/pom.xml")
            write(pom, Files.readString(pom).replace("</project>", "<name>changed after open</name></project>"))
            baseline = contents(requireNotNull(container))
        }
        response = requireNotNull(session).dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", previousHash)
            put("sdkHome", System.getProperty("java.home"))
            put("providerId", "maven-effective-v1")
            put("moduleId", "unrelated")
            put("sourceSetId", buildHelperSourceSetIds.first())
            put("sourceRelativePath", "fixtures/unrelated/Extra.java")
            put("symbol", "fixtures.unrelated.Extra")
            put("requiredFacts", buildJsonArray { add("source-root-provenance") })
        }).jsonObject
    }

    @Then("v3 context reports {string} for the Build Helper root {string} with refusal {string}")
    fun buildHelperRootContextOutcome(outcome: String, rootPath: String, refusal: String) {
        writeBuildHelperContextResponse(outcome, refusal)
        assertEquals("java-project-context-v3", response.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals(setOf("schemaVersion", "status", "snapshotHash", "contextHash", "sdk", "providers",
            "modules", "buildEvidence", "selectedSource", "analysis", "refusal"), response.keys)
        assertEquals(outcome, response.getValue("status").jsonPrimitive.content, response.toString())
        if (outcome == "refused") {
            assertEquals(refusal, response.getValue("refusal").jsonObject.getValue("code").jsonPrimitive.content)
            assertEquals("null", response.getValue("selectedSource").toString())
            assertTrue(response.getValue("modules").jsonArray.isEmpty())
            return
        }
        assertEquals("none", refusal)
        assertEquals(previousHash, response.getValue("snapshotHash").jsonPrimitive.content)
        assertTrue(response.getValue("contextHash").jsonPrimitive.content.matches(Regex("[a-f0-9]{64}")))
        assertEquals("null", response.getValue("refusal").toString())
        val module = response.getValue("modules").jsonArray.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == "unrelated" }
        val owner = sourceSets(module).values.single { rootPath in strings(it, "sourceRoots") }
        assertEquals("custom", owner.getValue("kind").jsonPrimitive.content)
        assertFalse(rootPath in strings(owner, "generatedSourceRoots"))
        val evidence = owner.getValue("sourceRootEvidence").jsonArray.map { it.jsonObject }
            .single { it.getValue("path").jsonPrimitive.content == rootPath }
        assertEquals(setOf("path", "origin", "providerId", "descriptorPath", "profileId", "pluginId",
            "executionId", "goal", "phase", "availability"), evidence.keys)
        assertEquals("build-plugin", evidence.getValue("origin").jsonPrimitive.content)
        assertEquals("maven-effective-v1", evidence.getValue("providerId").jsonPrimitive.content)
        assertEquals("unrelated/pom.xml", evidence.getValue("descriptorPath").jsonPrimitive.content)
        assertEquals("null", evidence.getValue("profileId").toString())
        assertEquals("org.codehaus.mojo:build-helper-maven-plugin", evidence.getValue("pluginId").jsonPrimitive.content)
        assertEquals("register-extra", evidence.getValue("executionId").jsonPrimitive.content)
        assertEquals("add-source", evidence.getValue("goal").jsonPrimitive.content)
        assertEquals("generate-sources", evidence.getValue("phase").jsonPrimitive.content)
        assertEquals("complete", evidence.getValue("availability").jsonPrimitive.content)
        val selected = response.getValue("selectedSource").jsonObject
        assertEquals(setOf("path", "symbol", "providerId", "moduleId", "sourceSetId", "sourceRoot",
            "generated", "bindingKey", "evidence"), selected.keys)
        assertEquals("unrelated/src/extra/java/fixtures/unrelated/Extra.java", selected.getValue("path").jsonPrimitive.content)
        assertEquals("fixtures.unrelated.Extra", selected.getValue("symbol").jsonPrimitive.content)
        assertEquals("maven-effective-v1", selected.getValue("providerId").jsonPrimitive.content)
        assertEquals("unrelated", selected.getValue("moduleId").jsonPrimitive.content)
        assertEquals(owner.getValue("id").jsonPrimitive.content, selected.getValue("sourceSetId").jsonPrimitive.content)
        assertEquals(rootPath, selected.getValue("sourceRoot").jsonPrimitive.content)
        assertEquals("false", selected.getValue("generated").jsonPrimitive.content)
        assertTrue(selected.getValue("bindingKey").jsonPrimitive.content.isNotBlank())
        assertEquals("JDT_BINDING", selected.getValue("evidence").jsonPrimitive.content)
    }

    @Given("a Maven project declares one relocated jar and one unsupported zip dependency")
    fun relocatedArtifactFailureFixture() {
        val owned = Files.createTempDirectory("rk-maven-artifact-refusal-")
        container = owned
        artifactWorkspaceRoot = owned.resolve("workspace")
        artifactRepositoryRoot = owned.resolve("repository")
        relocatedArtifactJar = artifactRepositoryRoot.resolve("fixture/legacy/legacy-api/1/legacy-api-1.jar")
        Files.createDirectories(relocatedArtifactJar.parent)
        JarOutputStream(Files.newOutputStream(relocatedArtifactJar)).use { }
        write(artifactRepositoryRoot.resolve("fixture/legacy/legacy-api/1/legacy-api-1.pom"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture.legacy</groupId>
              <artifactId>legacy-api</artifactId><version>1</version>
              <distributionManagement><relocation><groupId>fixture.modern</groupId>
                <artifactId>modern-api</artifactId><version>2</version></relocation></distributionManagement>
            </project>
        """.trimIndent() + "\n")
        write(artifactWorkspaceRoot.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId>
              <artifactId>artifact-refusal</artifactId><version>1</version>
              <properties><maven.compiler.release>21</maven.compiler.release></properties>
              <dependencies>
                <dependency><groupId>fixture.legacy</groupId><artifactId>legacy-api</artifactId><version>1</version></dependency>
                <dependency><groupId>fixture.legacy</groupId><artifactId>binary-distribution</artifactId><version>1</version><type>zip</type></dependency>
              </dependencies>
            </project>
        """.trimIndent() + "\n")
        write(artifactWorkspaceRoot.resolve("src/main/java/fixture/RelocationUse.java"),
            "package fixture; class RelocationUse {}\n")
        artifactInputBaseline = artifactInputHashes()
    }

    @When("the Java project scanner derives its project model and diagnostics")
    fun scanRelocatedUnsupportedArtifactFixture() {
        artifactSnapshot = JavaProjectScanner(localMavenRepository = artifactRepositoryRoot)
            .scan(artifactWorkspaceRoot)
        artifactDiagnostics = JavaLanguageAdapter().authoritativeDiagnostics(
            artifactSnapshot,
            Path.of(System.getProperty("java.home")),
        )
    }

    @Then("scanning returns a typed unavailable classpath diagnostic for both declarations")
    fun verifyRelocatedUnsupportedArtifactRefusal() {
        val unavailable = artifactDiagnostics.singleOrNull { it.code == "classpath.unavailable" }
            ?: error("Expected one classpath.unavailable diagnostic, got $artifactDiagnostics")
        assertEquals(Diagnostic.Severity.ERROR, unavailable.severity)
        assertTrue(unavailable.message.contains("artifact relocation is unsupported"), unavailable.toString())
        assertTrue(unavailable.message.contains("dependency type 'zip' is unsupported"), unavailable.toString())
        assertFalse(artifactDiagnostics.any { it.code == "java.jdt.typeResolution" }, artifactDiagnostics.toString())
    }

    @Then("no relocated artifact is retained in classpath evidence and project inputs stay unchanged")
    fun verifyRelocatedArtifactEvidenceAndReadOnlyScan() {
        val sourceSet = artifactSnapshot.buildModels.single { it.providerId == "maven-effective-v1" }
            .modules.single().sourceSets.single { it.id == "main" }
        assertFalse(relocatedArtifactJar in sourceSet.classpathEntries, sourceSet.classpathEntries.toString())
        assertFalse(relocatedArtifactJar in sourceSet.runtimeClasspathEntries, sourceSet.runtimeClasspathEntries.toString())
        assertFalse(sourceSet.resolvedArtifacts.any { it.path == relocatedArtifactJar }, sourceSet.resolvedArtifacts.toString())
        assertEquals(artifactInputBaseline, artifactInputHashes())
    }

    private fun artifactInputHashes(): Map<String, String> = buildMap {
        listOf(artifactWorkspaceRoot, artifactRepositoryRoot).forEach { base ->
            if (!Files.exists(base)) return@forEach
            Files.walk(base).use { paths ->
                paths.filter(Files::isRegularFile).forEach { file ->
                    val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))
                        .joinToString("") { byte -> "%02x".format(byte) }
                    put("${base.fileName}/${base.relativize(file)}", digest)
                }
            }
        }
    }

    private lateinit var effectiveOutputConfiguration: String
    private lateinit var effectiveOutputScope: String
    private val outputModules=listOf("leaf", "hidden", "runtimeleaf", "library", "provided", "runtime", "testsupport", "app")

    @Given("a Maven reactor with {string} effective output directories")
    fun mavenOutputFixture(configuration:String) {
        effectiveOutputConfiguration=configuration
        val owned=Files.createTempDirectory("rk-maven-outputs-");container=owned;root=owned.resolve("project")
        val inherited=if(configuration=="inherited") "<build><directory>\${project.basedir}/compiled</directory></build>" else ""
        val profileDefaults=if(configuration=="profile") "<main.layout>classes</main.layout><test.layout>test-classes</test.layout>" else ""
        val profileBuild=if(configuration=="profile") "<build><outputDirectory>\${project.build.directory}/\${main.layout}</outputDirectory>"+
            "<testOutputDirectory>\${project.build.directory}/\${test.layout}</testOutputDirectory></build>" else ""
        val profiles=if(configuration=="profile") "<profiles><profile><id>outputs</id><properties><layout>profiled</layout><main.layout>main</main.layout>"+
            "<test.layout>tests</test.layout></properties><build><directory>\${project.basedir}/\${layout}</directory></build></profile></profiles>" else ""
        write(root.resolve("pom.xml"),"<project><modelVersion>4.0.0</modelVersion><groupId>fixture.outputs</groupId><artifactId>root</artifactId>"+
            "<version>1</version><packaging>pom</packaging><properties><maven.compiler.release>17</maven.compiler.release>$profileDefaults</properties>"+
            "<modules>"+outputModules.joinToString("") {"<module>$it</module>"}+"</modules>$inherited$profileBuild$profiles</project>\n")
        fun dependency(module:String,scope:String)="<dependency><groupId>fixture.outputs</groupId><artifactId>$module</artifactId><version>1</version><scope>$scope</scope></dependency>"
        outputModules.forEach {module->
            val dependencies=when(module){"app"->listOf("library" to "compile","provided" to "provided","runtime" to "runtime","testsupport" to "test");
                "library"->listOf("leaf" to "compile","hidden" to "provided","runtimeleaf" to "runtime");else->emptyList()}
            val build=when {
                configuration=="custom"->"<build><outputDirectory>custom-main</outputDirectory><testOutputDirectory>custom-test</testOutputDirectory></build>"
                configuration=="escaping"&&module=="app"->"<build><outputDirectory>\${project.basedir}/../../outside-output</outputDirectory></build>"
                else->""
            }
            write(root.resolve("$module/pom.xml"),"<project><modelVersion>4.0.0</modelVersion><parent><groupId>fixture.outputs</groupId><artifactId>root</artifactId>"+
                "<version>1</version><relativePath>../pom.xml</relativePath></parent><artifactId>$module</artifactId>$build"+
                "<dependencies>"+dependencies.joinToString(""){dependency(it.first,it.second)}+"</dependencies></project>\n")
            val type=module.replaceFirstChar(Char::uppercase)
            write(root.resolve("$module/src/main/java/fixture/$type.java"),"package fixture;\npublic class $type {}\n")
            write(root.resolve("$module/src/test/java/fixture/${type}Test.java"),"package fixture;\nclass ${type}Test { $type value; }\n")
            listOf("target/classes","target/test-classes","build/classes/java/main","build/classes/java/test","build/classes/kotlin/main","build/classes/kotlin/test",outputPath(module,false).substringAfter("$module/"),outputPath(module,true).substringAfter("$module/")).distinct().forEach {directory->
                write(root.resolve("$module/$directory/.materialized"),"$module $directory output\n")
            }
        }
        session=DaemonSession();previousHash=requireNotNull(session).dispatch("project.open",buildJsonObject {
            put("root",root.toString());put("mavenRepository",owned.resolve("empty-repository").toString())
            if(configuration=="profile")put("activeMavenProfiles",buildJsonArray {add("outputs")})
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        baseline=contents(owned)
    }

    @When("its {string} context is acquired without running Maven")
    fun acquireMavenOutputs(scope:String) {
        effectiveOutputScope=scope
        response=requireNotNull(session).dispatch("project.context",buildJsonObject {
            put("expectedSnapshotHash",previousHash);put("sdkHome",System.getProperty("java.home"));put("moduleId","app");put("sourceSetId",scope)
            put("sourceRelativePath",if(scope=="main")"fixture/App.java" else "fixture/AppTest.java")
            put("symbol",if(scope=="main")"fixture.App" else "fixture.AppTest")
        }).jsonObject
    }

    @Then("effective outputs report {string} and preserve reactor compile runtime scope")
    fun mavenOutputs(expected:String) {
        assertEquals(expected,response.getValue("status").jsonPrimitive.content,response.toString())
        if(expected=="refused"){
            assertEquals("java.context.effectiveModelUnavailable",response.getValue("refusal").jsonObject.getValue("code").jsonPrimitive.content)
            assertTrue(response.getValue("modules").jsonArray.isEmpty());return
        }
        val modules=response.getValue("modules").jsonArray.map {it.jsonObject}.associateBy {it.getValue("id").jsonPrimitive.content}
        outputModules.forEach {module->
            val sets=modules.getValue(module).getValue("sourceSets").jsonArray.map {it.jsonObject}.associateBy {it.getValue("id").jsonPrimitive.content}
            assertEquals(listOf(outputPath(module,false)),sets.getValue("main").getValue("outputDirectories").jsonArray.map {it.jsonPrimitive.content})
            assertEquals(listOf(outputPath(module,true)),sets.getValue("test").getValue("outputDirectories").jsonArray.map {it.jsonPrimitive.content})
            assertTrue((paths(sets.getValue("main"),"compileClasspath")+paths(sets.getValue("main"),"runtimeClasspath")+paths(sets.getValue("test"),"compileClasspath")).none {it.contains("build/classes/")})
        }
        val sets=modules.getValue("app").getValue("sourceSets").jsonArray.map {it.jsonObject}.associateBy {it.getValue("id").jsonPrimitive.content}
        val main=sets.getValue("main");val test=sets.getValue("test")
        assertEquals(listOf("app","library","provided","leaf").map {outputPath(it,false)},paths(main,"compileClasspath"))
        assertEquals(listOf("app","library","runtime","leaf","runtimeleaf").map {outputPath(it,false)},paths(main,"runtimeClasspath"))
        val testPaths=listOf(outputPath("app",true))+listOf("app","library","provided","runtime","testsupport","leaf","runtimeleaf").map {outputPath(it,false)}
        assertEquals(testPaths,paths(test,"compileClasspath"));assertEquals(testPaths,paths(test,"runtimeClasspath"))
        assertEquals(effectiveOutputScope,response.getValue("selectedSource").jsonObject.getValue("sourceSetId").jsonPrimitive.content)
        val replay=requireNotNull(session).dispatch("project.context",buildJsonObject {
            put("expectedSnapshotHash",previousHash);put("expectedContextHash",response.getValue("contextHash"));put("sdkHome",System.getProperty("java.home"))
        }).jsonObject
        assertEquals("available",replay.getValue("status").jsonPrimitive.content)
    }

    private fun outputPath(module:String,test:Boolean):String=module+"/"+when(effectiveOutputConfiguration){
        "custom"->if(test)"custom-test" else "custom-main"
        "inherited"->if(test)"compiled/test-classes" else "compiled/classes"
        "profile"->if(test)"profiled/tests" else "profiled/main"
        else->if(test)"target/test-classes" else "target/classes"
    }

    @Given("a multi-module Maven reactor with Failsafe profile {string} and ownership {string}")
    fun mavenFailsafeFixture(profile: String, ownership: String) {
        assertTrue(profile in setOf("active", "inactive"))
        assertTrue(ownership in setOf("unique", "duplicate root", "configured", "duplicate Failsafe", "unresolved"))
        checkedInReadOnlyRoot = null
        mavenFailsafeOwnership = ownership
        val owned = Files.createTempDirectory("rk-maven-failsafe-context-"); container = owned
        root = owned.resolve("reactor")
        val modules = listOf("core", "app")
        write(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture.failsafe</groupId><artifactId>root</artifactId>
              <version>1</version><packaging>pom</packaging><properties><maven.compiler.release>17</maven.compiler.release></properties>
              <modules>${modules.joinToString("") { "<module>$it</module>" }}</modules>
            </project>
        """.trimIndent() + "\n")
        modules.forEach { module ->
            val dependency = if (module == "app") """
                <dependencies><dependency><groupId>fixture.failsafe</groupId><artifactId>core</artifactId>
                  <version>1</version><scope>compile</scope></dependency></dependencies>
            """.trimIndent() else ""
            val sourceRegistrations = buildList {
                add("register-tests")
                if (module == "app" && ownership == "duplicate root") add("register-tests-copy")
            }.joinToString("") { executionId -> """
                <execution><id>$executionId</id><phase>generate-test-sources</phase>
                  <goals><goal>add-test-source</goal></goals>
                  <configuration><sources><source>src/it/java</source></sources></configuration>
                </execution>
            """.trimIndent() }
            val failsafeConfiguration = when (ownership) {
                "configured" -> """
                    <configuration><includes><include>**/*IT.java</include></includes>
                      <reportsDirectory>target/failsafe-reports</reportsDirectory><reportFormat>plain</reportFormat>
                    </configuration>
                """.trimIndent()
                "unresolved" -> """
                    <configuration><includes><include>${'$'}{failsafe.test.selector}</include></includes>
                      <reportsDirectory>target/failsafe-reports</reportsDirectory><reportFormat>plain</reportFormat>
                    </configuration>
                """.trimIndent()
                else -> ""
            }
            val failsafeExecutions = buildString {
                append("""<execution><id>run-tests</id><phase>integration-test</phase>
                  <goals><goal>integration-test</goal><goal>verify</goal></goals>$failsafeConfiguration</execution>""")
                if (ownership == "duplicate Failsafe") append("""<execution><id>run-tests-copy</id><phase>integration-test</phase>
                  <goals><goal>integration-test</goal><goal>verify</goal></goals></execution>""")
            }
            val profileDefinition = """
                <profiles><profile><id>quality</id><build><plugins>
                  <plugin><groupId>org.codehaus.mojo</groupId><artifactId>build-helper-maven-plugin</artifactId><version>3.6.0</version>
                    <executions>$sourceRegistrations</executions>
                  </plugin>
                  <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-failsafe-plugin</artifactId><version>3.5.2</version>
                    <executions>$failsafeExecutions</executions>
                  </plugin>
                </plugins></build></profile></profiles>
            """.trimIndent()
            write(root.resolve("$module/pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>fixture.failsafe</groupId><artifactId>root</artifactId><version>1</version><relativePath>../pom.xml</relativePath></parent>
                  <artifactId>$module</artifactId>$dependency$profileDefinition
                </project>
            """.trimIndent() + "\n")
            val packageName = if (module == "app") "fixture.app" else "fixture.core"
            val mainName = if (module == "app") "App" else "Core"
            val mainBody = if (module == "app") "public class App { fixture.core.Core core; }" else "public class Core {}"
            write(root.resolve("$module/src/main/java/${packageName.replace('.', '/')}/$mainName.java"),
                "package $packageName; $mainBody\n")
            write(root.resolve("$module/src/test/java/${packageName.replace('.', '/')}/${mainName}Test.java"),
                "package $packageName; class ${mainName}Test {}\n")
            val itName = if (module == "app") "AppIT" else "CoreIT"
            write(root.resolve("$module/src/it/java/it/$itName.java"),
                "package it; public class $itName {}\n")
            write(root.resolve("$module/target/classes/.materialized"), "$module main output\n")
            write(root.resolve("$module/target/test-classes/.materialized"), "$module test output\n")
        }
        session = DaemonSession()
        previousHash = requireNotNull(session).dispatch("project.open", buildJsonObject {
            put("root", root.toString())
            put("mavenRepository", owned.resolve("empty-repository").toString())
            if (profile == "active") put("activeMavenProfiles", buildJsonArray { add("quality") })
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        baseline = contents(owned)
    }

    @Given("a Maven reactor declares a local annotation processor module and materialized generated source")
    fun mavenReactorAnnotationProcessorFixture() {
        createMavenReactorAnnotationProcessorFixture(materialized = true)
    }

    @Given("a Maven reactor declares a local annotation processor module without materialized output")
    fun mavenReactorAnnotationProcessorWithoutMaterializedOutput() {
        createMavenReactorAnnotationProcessorFixture(materialized = false)
    }

    private fun createMavenReactorAnnotationProcessorFixture(materialized: Boolean) {
        val owned = Files.createTempDirectory("rk-maven-reactor-annotation-processor-")
        container = owned
        root = owned.resolve("workspace")
        val repository = owned.resolve("empty-maven-repository")
        Files.createDirectories(repository)
        write(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture.processor</groupId>
              <artifactId>processor-reactor</artifactId><version>1</version><packaging>pom</packaging>
              <properties><maven.compiler.release>21</maven.compiler.release></properties>
              <modules><module>processor</module><module>app</module></modules>
            </project>
        """.trimIndent() + "\n")
        write(root.resolve("processor/pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion>
              <parent><groupId>fixture.processor</groupId><artifactId>processor-reactor</artifactId>
                <version>1</version><relativePath>../pom.xml</relativePath></parent>
              <artifactId>greeting-processor</artifactId>
              <build><outputDirectory>target/classes</outputDirectory></build>
            </project>
        """.trimIndent() + "\n")
        write(root.resolve("app/pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion>
              <parent><groupId>fixture.processor</groupId><artifactId>processor-reactor</artifactId>
                <version>1</version><relativePath>../pom.xml</relativePath></parent>
              <artifactId>app</artifactId>
              <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version>
                <configuration><release>21</release>
                  <annotationProcessorPaths><path><groupId>fixture.processor</groupId>
                    <artifactId>greeting-processor</artifactId><version>1</version></path></annotationProcessorPaths>
                  <annotationProcessors><annotationProcessor>fixture.processor.GreetingProcessor</annotationProcessor></annotationProcessors>
                </configuration>
              </plugin></plugins></build>
            </project>
        """.trimIndent() + "\n")
        write(root.resolve("processor/src/main/java/fixture/processor/GreetingProcessor.java"), """
            package fixture.processor;
            public final class GreetingProcessor {}
        """.trimIndent() + "\n")
        write(root.resolve("app/src/main/java/fixture/app/App.java"), """
            package fixture.app;
            public final class App { fixture.generated.GeneratedGreeting greeting; }
        """.trimIndent() + "\n")
        if (materialized) materializeMavenReactorAnnotationProcessorOutputs()
        session = DaemonSession()
        previousHash = requireNotNull(session).dispatch("project.open", buildJsonObject {
            put("root", root.toString())
            put("mavenRepository", repository.toString())
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        baseline = contents(owned)
    }

    private fun materializeMavenReactorAnnotationProcessorOutputs() {
        write(root.resolve("processor/target/classes/fixture/processor/GreetingProcessor.class"),
            "materialized processor output\n")
        write(root.resolve("app/target/generated-sources/annotations/fixture/generated/GeneratedGreeting.java"), """
            package fixture.generated;
            public final class GeneratedGreeting {}
        """.trimIndent() + "\n")
    }

    private val testJvmSelectionShapes = setOf(
        "Surefire explicit JVM", "Surefire JVM wins over toolchain", "Failsafe JVM wins over toolchain",
        "inherited build JVM",
        "test toolchain selection", "unresolved test JVM", "redacted test toolchain",
    )

    private fun testExecutable(): String =
        (System.getenv("REFACTORKIT_JAVA_8_HOME")?.let(Path::of) ?: root.resolve("temurin8")).resolve("bin/java").toString()

    private fun buildTestExecutable(): String = Path.of(System.getProperty("java.home"), "bin", "java").toString()

    private fun testJvmSelectionPlugin(shape: String, repository: Path): String {
        val failsafe = shape == "Failsafe JVM wins over toolchain"
        val toolchain = "<jdkToolchain><version>1.8</version><vendor>temurin</vendor></jdkToolchain>"
        val goal = if (failsafe) "integration-test" else "test"
        val artifact = if (failsafe) "maven-failsafe-plugin" else "maven-surefire-plugin"
        val version = if (failsafe) "3.5.4" else "3.5.3"
        val configuration = when (shape) {
            "Surefire explicit JVM" -> "<jvm>${testExecutable()}</jvm>"
            "Surefire JVM wins over toolchain" -> "<jvm>${buildTestExecutable()}</jvm>$toolchain"
            "Failsafe JVM wins over toolchain" -> "<jvm>/fixture/default/bin/java</jvm>$toolchain"
            "test toolchain selection" -> toolchain
            "unresolved test JVM" -> "<jvm>${'$'}{unresolved.jvm}</jvm>"
            "redacted test toolchain" -> "<jdkToolchain><token>rk-jvm-redaction-probe</token></jdkToolchain>"
            else -> ""
        }
        val executions = if (failsafe) """
            <executions><execution><id>integration-tests</id><phase>integration-test</phase>
              <goals><goal>integration-test</goal><goal>verify</goal></goals>
              <configuration><jvm>${buildTestExecutable()}</jvm></configuration>
            </execution></executions>
        """.trimIndent() else ""
        writeTestJvmPluginDescriptor(repository, artifact, version, goal)
        val unitTests = if (failsafe) {
            writeTestJvmPluginDescriptor(repository, "maven-surefire-plugin", "3.5.3", "test")
            """
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId>
                  <version>3.5.3</version>
                </plugin>
            """.trimIndent()
        } else ""
        return """
            $unitTests
            <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>$artifact</artifactId>
              <version>$version</version><configuration>$configuration</configuration>$executions
            </plugin>
        """.trimIndent()
    }

    @Given("a Maven test-JVM fixture {string}")
    fun mavenTestJvmFixture(shape: String) {
        assertTrue(shape in setOf(
            "direct surefire", "direct failsafe", "unresolved argLine", "duplicate runs", "implicit surefire",
            "failsafe JVM settings absent", "Failsafe declaration without descriptor",
            "inherited default Surefire lifecycle", "parent-managed Failsafe execution",
            "inherited Surefire version unavailable", "JaCoCo prepare-agent contributes argLine",
            "Surefire version property matches parent management",
            "one-level parent Surefire JVM settings", "one-level parent Failsafe JVM settings",
        ) + testJvmSelectionShapes)
        mavenTestJvmShape = shape
        val owned = Files.createTempDirectory("rk-maven-test-jvm-v7-")
        container = owned
        root = owned.resolve("reactor")
        val repository = owned.resolve("empty-maven-repository")
        Files.createDirectories(repository)
        if (shape in setOf(
                "inherited default Surefire lifecycle", "JaCoCo prepare-agent contributes argLine",
                "Surefire version property matches parent management",
            )) {
            writeTestJvmPluginDescriptor(repository, "maven-surefire-plugin", "3.5.4", "test")
        }
        if (shape == "parent-managed Failsafe execution") {
            writeTestJvmPluginDescriptor(repository, "maven-failsafe-plugin", "3.5.4", "integration-test")
        }
        val plugin = if (shape in testJvmSelectionShapes) testJvmSelectionPlugin(shape, repository) else when (shape) {
            "direct surefire" -> """
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.2</version>
                  <executions><execution><id>unit-tests</id><phase>test</phase><goals><goal>test</goal></goals>
                    <configuration><forkCount>1</forkCount><reuseForks>true</reuseForks><argLine>@{argLine} -Xmx1g</argLine></configuration>
                  </execution></executions>
                </plugin>
            """.trimIndent()
            "direct failsafe" -> """
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-failsafe-plugin</artifactId><version>3.5.2</version>
                  <executions><execution><id>integration-tests</id><phase>integration-test</phase>
                    <goals><goal>integration-test</goal><goal>verify</goal></goals>
                    <configuration><forkCount>0</forkCount><reuseForks>false</reuseForks><argLine>@{argLine} -Xmx2g</argLine></configuration>
                  </execution></executions>
                </plugin>
            """.trimIndent()
            "failsafe JVM settings absent", "Failsafe declaration without descriptor" -> """
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-failsafe-plugin</artifactId><version>3.5.2</version>
                  <executions><execution><id>integration-tests</id><phase>integration-test</phase>
                    <goals><goal>integration-test</goal><goal>verify</goal></goals>
                    <configuration><includes><include>**/*IT.java</include></includes>
                      <reportsDirectory>target/failsafe-reports</reportsDirectory><reportFormat>plain</reportFormat></configuration>
                  </execution></executions>
                </plugin>
            """.trimIndent()
            "parent-managed Failsafe execution" -> """
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-failsafe-plugin</artifactId>
                  <executions><execution><id>failsafe-app-tests</id><phase>integration-test</phase>
                    <goals><goal>integration-test</goal><goal>verify</goal></goals>
                    <configuration><includes><include>**/*AppIT.java</include></includes>
                      <reportsDirectory>target/app-failsafe-reports</reportsDirectory><reportFormat>plain</reportFormat></configuration>
                  </execution></executions>
                </plugin>
            """.trimIndent()
            "unresolved argLine" -> """
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.2</version>
                  <executions><execution><id>unit-tests</id><phase>test</phase><goals><goal>test</goal></goals>
                    <configuration><argLine>${'$'}{test.jvm.args}</argLine></configuration>
                  </execution></executions>
                </plugin>
            """.trimIndent()
            "implicit surefire", "inherited default Surefire lifecycle", "inherited Surefire version unavailable",
            "JaCoCo prepare-agent contributes argLine" -> ""
            "Surefire version property matches parent management" -> """
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId>
                  <version>${'$'}{surefire.version}</version>
                </plugin>
            """.trimIndent()
            "one-level parent Surefire JVM settings", "one-level parent Failsafe JVM settings" -> ""
            else -> """
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.2</version>
                  <executions>
                    <execution><id>unit-one</id><phase>test</phase><goals><goal>test</goal></goals><configuration><forkCount>1</forkCount></configuration></execution>
                    <execution><id>unit-two</id><phase>test</phase><goals><goal>test</goal></goals><configuration><forkCount>2</forkCount></configuration></execution>
                  </executions>
                </plugin>
            """.trimIndent()
        }
        val parentPluginManagement = when (shape) {
            "inherited default Surefire lifecycle", "JaCoCo prepare-agent contributes argLine",
            "Surefire version property matches parent management" -> """
                <build><pluginManagement><plugins><plugin>
                  <groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.4</version>
                </plugin></plugins></pluginManagement></build>
            """.trimIndent()
            "parent-managed Failsafe execution" -> """
                <build><pluginManagement><plugins><plugin>
                  <groupId>org.apache.maven.plugins</groupId><artifactId>maven-failsafe-plugin</artifactId><version>3.5.4</version>
                </plugin></plugins></pluginManagement></build>
            """.trimIndent()
            "inherited Surefire version unavailable" -> """
                <build><pluginManagement><plugins><plugin>
                  <groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId>
                </plugin></plugins></pluginManagement></build>
            """.trimIndent()
            else -> ""
        }
        val inheritedParentPlugins = when (shape) {
            "one-level parent Surefire JVM settings" -> """
                <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId>
                  <version>3.5.4</version><configuration><forkCount>2</forkCount><reuseForks>true</reuseForks>
                    <argLine>@{argLine} -Xmx2g</argLine></configuration>
                </plugin></plugins></build>
            """.trimIndent()
            "one-level parent Failsafe JVM settings" -> """
                <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-failsafe-plugin</artifactId>
                  <version>3.5.4</version><configuration><forkCount>1</forkCount><reuseForks>true</reuseForks>
                    <argLine>@{argLine} -Xmx3g</argLine></configuration>
                  <executions><execution><id>failsafe-app-tests</id><phase>integration-test</phase>
                    <goals><goal>integration-test</goal><goal>verify</goal></goals>
                    <configuration><includes><include>**/*AppIT.java</include></includes>
                      <reportsDirectory>target/app-failsafe-reports</reportsDirectory><reportFormat>plain</reportFormat>
                    </configuration>
                  </execution></executions>
                </plugin></plugins></build>
            """.trimIndent()
            else -> ""
        }
        val jacocoPlugin = if (shape == "JaCoCo prepare-agent contributes argLine") """
            <plugin><groupId>org.jacoco</groupId><artifactId>jacoco-maven-plugin</artifactId><version>0.8.13</version>
              <executions><execution><id>prepare-agent</id><goals><goal>prepare-agent</goal></goals></execution></executions>
            </plugin>
        """.trimIndent() else ""
        write(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture.testjvm</groupId><artifactId>root</artifactId><version>1</version><packaging>pom</packaging>
              <modules><module>app</module></modules>$inheritedParentPlugins$parentPluginManagement
            </project>
        """.trimIndent() + "\n")
        val propertyVersion = if (shape == "Surefire version property matches parent management") {
            "<surefire.version>3.5.4</surefire.version>"
        } else ""
        write(root.resolve("app/pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion>
              <parent><groupId>fixture.testjvm</groupId><artifactId>root</artifactId><version>1</version><relativePath>../pom.xml</relativePath></parent>
              <artifactId>app</artifactId><properties><maven.compiler.release>17</maven.compiler.release>$propertyVersion</properties>
              <build><plugins>$plugin$jacocoPlugin
                <plugin><groupId>org.codehaus.mojo</groupId><artifactId>build-helper-maven-plugin</artifactId><version>3.6.0</version>
                  <executions><execution><id>register-integration-source</id><phase>generate-test-sources</phase>
                    <goals><goal>add-test-source</goal></goals>
                    <configuration><sources><source>src/integration-test/java</source></sources></configuration>
                  </execution></executions>
                </plugin>
              </plugins></build>
            </project>
        """.trimIndent() + "\n")
        write(root.resolve("app/src/main/java/fixture/App.java"), "package fixture; public class App {}\n")
        write(root.resolve("app/src/test/java/fixture/AppTest.java"), "package fixture; class AppTest {}\n")
        write(root.resolve("app/src/integration-test/java/fixture/AppIT.java"), "package fixture; class AppIT {}\n")
        session = DaemonSession()
        previousHash = requireNotNull(session).dispatch("project.open", buildJsonObject {
            put("root", root.toString())
            put("mavenRepository", repository.toString())
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        baseline = contents(owned)
    }

    @Given("a Maven Failsafe JVM profile fixture {string}")
    fun mavenFailsafeJvmProfileFixture(shape: String) {
        val owned = Files.createTempDirectory("rk-maven-failsafe-test-jvm-profile-")
        container = owned
        root = owned.resolve("reactor")
        val repository = Files.createDirectory(owned.resolve("empty-maven-repository"))
        writeTestJvmPluginDescriptor(repository, "maven-failsafe-plugin", "3.5.4", "integration-test")
        writeMavenFailsafeJvmProfileFixture(root, shape, buildTestExecutable())
        val active = DaemonSession()
        session = active
        previousHash = active.dispatch("project.open", buildJsonObject {
            put("root", root.toString())
            put("mavenRepository", repository.toString())
            put(if (shape == "inactive profile") "inactiveMavenProfiles" else "activeMavenProfiles",
                buildJsonArray { add("quality") })
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        baseline = contents(owned)
    }

    @Then("Maven profile Failsafe JVM facts report {string}")
    fun verifyMavenFailsafeJvmProfile(outcome: String) {
        fun integrationSet(payload: JsonObject): JsonObject? {
            assertEquals("available", payload.getValue("status").jsonPrimitive.content, payload.toString())
            val app = payload.getValue("modules").jsonArray.map { it.jsonObject }
                .single { it.getValue("id").jsonPrimitive.content == "app" }
            return sourceSets(app).values.singleOrNull { it.getValue("kind").jsonPrimitive.content == "integration-test" }
        }
        val legacySet = integrationSet(response)
        if (outcome == "absent integration set") {
            assertEquals(null, legacySet)
            return
        }
        val legacy = assertNotNull(legacySet).getValue("mavenTestJvm").jsonObject
        val availability = legacy.getValue("availability").jsonPrimitive.content
        if (outcome == "partial configuration") {
            assertEquals("partial", availability)
            assertEquals("java.context.mavenTestJvmConfigurationIncomplete", legacy.getValue("refusalCode").jsonPrimitive.content)
            return
        }
        assertEquals("complete", availability, "An active profile's proven execution requires complete JVM facts")
        val oldExecution = legacy.getValue("executions").jsonArray.single().jsonObject
        assertEquals("profile-integration", oldExecution.getValue("executionId").jsonPrimitive.content)
        assertEquals("3.5.4", oldExecution.getValue("pluginVersion").jsonPrimitive.content)
        assertEquals("forked", oldExecution.getValue("mode").jsonPrimitive.content)
        assertEquals(JsonNull, legacy.getValue("refusalCode"))
        val enrichedResponse = requireNotNull(session).dispatch("project.context.v7.testJvmSelection", buildJsonObject {
            put("expectedSnapshotHash", previousHash)
            put("expectedContextHash", response.getValue("contextHash"))
            put("sdkHome", System.getProperty("java.home"))
            put("providerId", "maven-effective-v1")
            put("moduleId", "app")
            put("sourceSetKind", "integration-test")
        }).jsonObject
        assertEquals(response.getValue("contextHash"), enrichedResponse.getValue("contextHash"))
        val enriched = assertNotNull(integrationSet(enrichedResponse)).getValue("mavenTestJvm").jsonObject
            .getValue("executions").jsonArray.single().jsonObject
        assertEquals(oldExecution, JsonObject(enriched - "jvmSelection"))
        val selection = enriched.getValue("jvmSelection").jsonObject
        assertEquals("complete", selection.getValue("availability").jsonPrimitive.content)
        val origin = selection.getValue("origin").jsonPrimitive.content
        if (outcome == "explicit executable") {
            assertEquals("explicit-executable", selection.getValue("kind").jsonPrimitive.content)
            assertEquals(buildTestExecutable(), selection.getValue("executable").jsonPrimitive.content)
            assertTrue(origin.contains("profiles/quality/") && origin.endsWith("/executions/profile-integration/configuration/jvm"), origin)
            assertEquals("-Dprofile.failsafe=true", enriched.getValue("effectiveArgLineTemplate").jsonPrimitive.content)
            val prerequisite = selection.getValue("toolchainPrerequisite").jsonObject
            assertEquals("java.context.mavenTestJvmToolchainUnresolved", prerequisite.getValue("refusalCode").jsonPrimitive.content)
            assertEquals(mapOf("version" to "1.8", "vendor" to "temurin"),
                prerequisite.getValue("requirements").jsonObject.mapValues { it.value.jsonPrimitive.content })
        } else {
            assertEquals("build JVM default", outcome)
            assertEquals("build-jvm", selection.getValue("kind").jsonPrimitive.content)
            assertTrue(origin.startsWith("plugin-default:module-profile:quality:"), origin)
            assertEquals("", enriched.getValue("effectiveArgLineTemplate").jsonPrimitive.content)
            assertEquals(JsonNull, selection.getValue("toolchainPrerequisite"))
        }
    }

    @Given("a Maven reactor with distinct app and library Failsafe test scopes")
    fun moduleBoundMavenFailsafeFixture() {
        val owned = Files.createTempDirectory("rk-maven-failsafe-app-library-")
        container = owned
        root = owned.resolve("reactor")
        val repository = owned.resolve("empty-maven-repository")
        Files.createDirectories(repository)
        write(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture.modulebound</groupId>
              <artifactId>reactor</artifactId><version>1</version><packaging>pom</packaging>
              <properties><maven.compiler.release>17</maven.compiler.release></properties>
              <modules><module>library</module><module>app</module></modules>
            </project>
        """.trimIndent() + "\n")
        for (module in listOf("library", "app")) {
            val sourceType = if (module == "app") "AppIT" else "LibraryIT"
            val dependency = if (module == "app") """
                <dependencies><dependency><groupId>fixture.modulebound</groupId><artifactId>library</artifactId>
                  <version>1</version><scope>compile</scope></dependency></dependencies>
            """.trimIndent() else ""
            write(root.resolve("$module/pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>fixture.modulebound</groupId><artifactId>reactor</artifactId><version>1</version>
                    <relativePath>../pom.xml</relativePath></parent>
                  <artifactId>$module</artifactId>$dependency
                  <profiles><profile><id>quality</id><build><plugins>
                    <plugin><groupId>org.codehaus.mojo</groupId><artifactId>build-helper-maven-plugin</artifactId><version>3.6.0</version>
                      <executions><execution><id>register-$module-integration-tests</id><phase>generate-test-sources</phase>
                        <goals><goal>add-test-source</goal></goals>
                        <configuration><sources><source>src/integration-test/java</source></sources></configuration>
                      </execution></executions>
                    </plugin>
                    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-failsafe-plugin</artifactId><version>3.5.2</version>
                      <executions><execution><id>failsafe-$module-tests</id><phase>integration-test</phase>
                        <goals><goal>integration-test</goal><goal>verify</goal></goals>
                        <configuration><includes><include>**/*$sourceType.java</include></includes>
                          <reportsDirectory>target/$module-failsafe-reports</reportsDirectory><reportFormat>plain</reportFormat>
                        </configuration>
                      </execution></executions>
                    </plugin>
                  </plugins></build></profile></profiles>
                </project>
            """.trimIndent() + "\n")
            val basePackage = if (module == "app") "it.app" else "it.library"
            write(root.resolve("$module/src/main/java/${basePackage.replace('.', '/')}/Main.java"),
                "package $basePackage; public class Main {}\n")
            write(root.resolve("$module/src/test/java/${basePackage.replace('.', '/')}/UnitTest.java"),
                "package $basePackage; class UnitTest {}\n")
            write(root.resolve("$module/src/integration-test/java/it/$sourceType.java"),
                "package it; public class $sourceType {}\n")
            write(root.resolve("$module/target/classes/.materialized"), "$module main output\n")
            write(root.resolve("$module/target/test-classes/.materialized"), "$module test output\n")
        }
        session = DaemonSession()
        previousHash = requireNotNull(session).dispatch("project.open", buildJsonObject {
            put("root", root.toString())
            put("mavenRepository", repository.toString())
            put("activeMavenProfiles", buildJsonArray { add("quality") })
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        baseline = contents(owned)
        mavenFailsafeModuleResponses = emptyList()
    }

    @When("provider context v3 and v4 are requested for each integration-test module")
    fun requestModuleBoundMavenFailsafeContexts() {
        val active = requireNotNull(session)
        mavenFailsafeModuleResponses = listOf("app" to "AppIT", "library" to "LibraryIT").map { (module, type) ->
            val request = buildJsonObject {
                put("expectedSnapshotHash", previousHash)
                put("sdkHome", System.getProperty("java.home"))
                put("providerId", "maven-effective-v1")
                put("moduleId", module)
                put("sourceSetKind", "integration-test")
                put("sourceRelativePath", "it/$type.java")
                put("symbol", "it.$type")
            }
            Triple(module, active.dispatch("project.context", request).jsonObject,
                active.dispatch("project.context.v4", request).jsonObject)
        }
    }

    @Then("each module retains only its own integration roots, outputs, classpath and Failsafe declarations")
    fun moduleBoundMavenFailsafeContexts() {
        assertEquals(setOf("app", "library"), mavenFailsafeModuleResponses.map { it.first }.toSet())
        for ((module, context, versioned) in mavenFailsafeModuleResponses) {
            val other = if (module == "app") "library" else "app"
            val type = if (module == "app") "AppIT" else "LibraryIT"
            assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
            assertEquals("java-project-context-v3", context.getValue("schemaVersion").jsonPrimitive.content)
            val selected = context.getValue("selectedSource").jsonObject
            assertEquals(module, selected.getValue("moduleId").jsonPrimitive.content)
            assertEquals("maven-effective-v1", selected.getValue("providerId").jsonPrimitive.content)
            assertEquals("$module/src/integration-test/java/it/$type.java", selected.getValue("path").jsonPrimitive.content)
            assertEquals("it.$type", selected.getValue("symbol").jsonPrimitive.content)

            assertEquals("available", versioned.getValue("status").jsonPrimitive.content, versioned.toString())
            assertEquals("java-project-context-v4", versioned.getValue("schemaVersion").jsonPrimitive.content)
            val versionedSelected = versioned.getValue("selectedSource").jsonObject
            assertEquals(module, versionedSelected.getValue("moduleId").jsonPrimitive.content)
            assertEquals(selected.getValue("sourceSetId").jsonPrimitive.content,
                versionedSelected.getValue("sourceSetId").jsonPrimitive.content)
            val moduleRow = versioned.getValue("modules").jsonArray.map { it.jsonObject }
                .single { it.getValue("providerId").jsonPrimitive.content == "maven-effective-v1" &&
                    it.getValue("id").jsonPrimitive.content == module }
            val allModules = versioned.getValue("modules").jsonArray.map { it.jsonObject }
                .associateBy { it.getValue("id").jsonPrimitive.content }
            assertEquals(setOf("app", "library"), allModules.keys)
            for (owner in listOf("app", "library")) {
                val ownedSets = sourceSets(allModules.getValue(owner))
                val ownedIntegration = ownedSets.values.single {
                    it.getValue("kind").jsonPrimitive.content == "integration-test"
                }
                assertEquals(listOf("$owner/src/integration-test/java"), strings(ownedIntegration, "sourceRoots"))
            }
            val sets = sourceSets(moduleRow)
            val integration = sets.values.single { it.getValue("kind").jsonPrimitive.content == "integration-test" }
            assertEquals(selected.getValue("sourceSetId").jsonPrimitive.content,
                integration.getValue("id").jsonPrimitive.content)
            assertEquals(listOf("$module/src/integration-test/java"), strings(integration, "sourceRoots"))
            assertEquals(listOf("$module/target/test-classes"), strings(integration, "outputDirectories"))
            assertFalse(sets.values.any { row -> strings(row, "sourceRoots").any { it.startsWith("$other/") } },
                "$module source-set rows contain roots owned by $other")
            assertFalse(strings(integration, "analysisSourceRoots").any {
                it.startsWith("$other/src/test/") || it.startsWith("$other/src/integration-test/")
            }, "$module analysis roots contain test sources owned by $other")
            val rootEvidence = integration.getValue("sourceRootEvidence").jsonArray.map { it.jsonObject }
                .single { it.getValue("path").jsonPrimitive.content == "$module/src/integration-test/java" }
            assertEquals("complete", rootEvidence.getValue("availability").jsonPrimitive.content)
            assertEquals("build-plugin", rootEvidence.getValue("origin").jsonPrimitive.content)
            assertEquals("maven-effective-v1", rootEvidence.getValue("providerId").jsonPrimitive.content)
            assertEquals("$module/pom.xml", rootEvidence.getValue("descriptorPath").jsonPrimitive.content)
            assertEquals("quality", rootEvidence.getValue("profileId").jsonPrimitive.content)
            assertEquals("org.codehaus.mojo:build-helper-maven-plugin", rootEvidence.getValue("pluginId").jsonPrimitive.content)
            assertEquals("register-$module-integration-tests", rootEvidence.getValue("executionId").jsonPrimitive.content)
            assertEquals("add-test-source", rootEvidence.getValue("goal").jsonPrimitive.content)
            assertEquals("generate-test-sources", rootEvidence.getValue("phase").jsonPrimitive.content)

            val compile = paths(integration, "compileClasspath")
            val runtime = paths(integration, "runtimeClasspath")
            if (module == "app") {
                assertTrue("library/target/classes" in compile, compile.toString())
                assertTrue("library/target/classes" in runtime, runtime.toString())
            } else {
                assertFalse(compile.any { it.startsWith("app/") }, compile.toString())
                assertFalse(runtime.any { it.startsWith("app/") }, runtime.toString())
            }
            assertFalse((compile + runtime).any {
                it == "$other/target/test-classes" || it.startsWith("$other/src/integration-test/")
            }, "$module classpaths contain $other test or integration-test inputs: ${compile + runtime}")

            val failsafe = integration.getValue("mavenFailsafe").jsonObject
            assertEquals("complete", failsafe.getValue("availability").jsonPrimitive.content)
            assertEquals("null", failsafe.getValue("refusalCode").toString())
            val execution = failsafe.getValue("executions").jsonArray.single().jsonObject
            assertEquals("org.apache.maven.plugins:maven-failsafe-plugin", execution.getValue("pluginId").jsonPrimitive.content)
            assertEquals("3.5.2", execution.getValue("pluginVersion").jsonPrimitive.content)
            assertEquals("failsafe-$module-tests", execution.getValue("executionId").jsonPrimitive.content)
            assertEquals(listOf("integration-test", "verify"), execution.getValue("goals").jsonArray.map { it.jsonPrimitive.content })
            assertEquals("integration-test", execution.getValue("phase").jsonPrimitive.content)
            assertEquals("quality", execution.getValue("profileId").jsonPrimitive.content)
            val selector = execution.getValue("selectors").jsonArray.single().jsonObject
            assertEquals("includes", selector.getValue("name").jsonPrimitive.content)
            assertEquals("**/*$type.java", selector.getValue("value").jsonPrimitive.content)
            assertEquals("profile-execution:quality", selector.getValue("origin").jsonPrimitive.content)
            val reports = execution.getValue("reports").jsonArray.map { it.jsonObject }
                .associate { it.getValue("name").jsonPrimitive.content to it.getValue("value").jsonPrimitive.content }
            assertEquals(mapOf("reportsDirectory" to "target/$module-failsafe-reports", "reportFormat" to "plain"), reports)
            writeModuleBoundFailsafeResponse("$module-v3.json", context)
            writeModuleBoundFailsafeResponse("$module-v4.json", versioned)
        }
        assertEquals(baseline, contents(requireNotNull(container)))
    }

    private fun writeModuleBoundFailsafeResponse(name: String, value: JsonObject) {
        val repository = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        val evidence = repository.resolve(
            ".refactorkit/runs/java-failsafe-app-library-context-r001/responses/$name",
        )
        Files.createDirectories(evidence.parent)
        Files.writeString(evidence, value.toString() + "\n")
    }

    @Given("the checked in two module Maven reactor uses Failsafe profile {string}")
    fun checkedInMavenFailsafeFixture(profile: String) {
        assertTrue(profile in setOf("active", "inactive"))
        val owned = Files.createTempDirectory("rk-maven-failsafe-checked-in-"); container = owned
        val repository = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        val checkedIn = repository.resolve("testdata/java-maven-failsafe-context").normalize()
        assertTrue(Files.isRegularFile(checkedIn.resolve("pom.xml")), "checked-in reactor root POM is missing")
        assertTrue(Files.isRegularFile(checkedIn.resolve("core/pom.xml")), "checked-in core POM is missing")
        assertTrue(Files.isRegularFile(checkedIn.resolve("app/pom.xml")), "checked-in app POM is missing")
        assertTrue(Files.isRegularFile(checkedIn.resolve("app/src/it/java/it/AppIT.java")), "checked-in Failsafe source is missing")
        checkedInSampleRoot = checkedIn
        checkedInSampleBaseline = contents(checkedIn)
        root = owned.resolve("reactor")
        copyFixture(checkedIn, root)
        // Model already-materialized module output without running Maven or writing into the checked-in sample.
        write(root.resolve("core/target/classes/.materialized"), "core main output\n")
        checkedInReadOnlyRoot = root
        baseline = contents(root)
        val emptyRepository = owned.resolve("empty-maven-repository")
        Files.createDirectories(emptyRepository)
        session = DaemonSession()
        previousHash = requireNotNull(session).dispatch("project.open", buildJsonObject {
            put("root", root.toString())
            put("mavenRepository", emptyRepository.toString())
            if (profile == "active") put("activeMavenProfiles", buildJsonArray { add("quality") })
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        mavenFailsafeSelection = if (profile == "active") "app symbol" else "project"
    }

    @When("project context is acquired for {string} with {string} evidence")
    fun acquireMavenFailsafe(selection: String, evidence: String) {
        mavenFailsafeSelection = selection
        assertTrue(evidence in setOf("current", "previous"))
        if (evidence == "previous") {
            val pom = root.resolve("app/pom.xml")
            Files.writeString(pom, Files.readString(pom) + "<!-- changed after project.open -->\n")
            baseline = contents(requireNotNull(container))
        }
        fun request(moduleId: String? = null, sourceSetId: String? = null) = buildJsonObject {
            put("expectedSnapshotHash", previousHash)
            put("sdkHome", System.getProperty("java.home"))
            moduleId?.let { put("moduleId", it) }
            sourceSetId?.let { put("sourceSetId", it) }
            if (selection == "ambiguous evidence") {
                put("requiredFacts", buildJsonArray { add("source-root-provenance") })
            }
        }
        val active = requireNotNull(session)
        if (selection == "app symbol") {
            val model = active.dispatch("project.context", request()).jsonObject
            assertEquals("available", model.getValue("status").jsonPrimitive.content, model.toString())
            val app = model.getValue("modules").jsonArray.map { it.jsonObject }
                .single { it.getValue("id").jsonPrimitive.content == "app" }
            val appIntegrationRoot = root.relativize(root.resolve("app/src/it/java")).toString().replace('\\', '/')
            val sourceSet = app.getValue("sourceSets").jsonArray.map { it.jsonObject }.single { row ->
                row.getValue("sourceRoots").jsonArray.any { it.jsonPrimitive.content == appIntegrationRoot }
            }
            response = active.dispatch("project.context", request(
                "app", sourceSet.getValue("id").jsonPrimitive.content,
            ).let { JsonObject(it + mapOf(
                "sourceRelativePath" to kotlinx.serialization.json.JsonPrimitive("it/AppIT.java"),
                "symbol" to kotlinx.serialization.json.JsonPrimitive("it.AppIT"),
            )) }).jsonObject
        } else {
            response = active.dispatch("project.context", request()).jsonObject
        }
    }

    @When("project context is requested by provider-qualified source-set kind {string}")
    fun acquireMavenFailsafeByKind(kind: String) {
        assertEquals("integration-test", kind)
        response = requireNotNull(session).dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", previousHash)
            put("sdkHome", System.getProperty("java.home"))
            put("providerId", "maven-effective-v1")
            put("moduleId", "app")
            put("sourceSetKind", kind)
            put("sourceRelativePath", "it/AppIT.java")
            put("symbol", "it.AppIT")
        }).jsonObject
    }

    @Then("Failsafe kind selection reports {string} with refusal {string} and selected module {string}")
    fun mavenFailsafeKindOutcome(outcome: String, refusal: String, selectedModule: String) {
        val responseName = if (outcome == "available") "unique" else when (refusal) {
            "java.context.sourceSetKindAmbiguous" -> "ambiguous"
            "java.context.sourceSetUnavailable" -> "unavailable"
            else -> "unexpected"
        }
        writeFailsafeKindResponse(responseName)
        val method = requireNotNull(session).dispatch("server.capabilities", null).jsonObject
            .getValue("methods").jsonArray.map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == "project.context" }
        assertEquals("true", method.getValue("features").jsonObject
            .getValue("sourceSetKindSelection").jsonPrimitive.content)
        assertEquals("java-project-context-v3", response.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals(setOf("schemaVersion", "status", "snapshotHash", "contextHash", "sdk", "providers",
            "modules", "buildEvidence", "selectedSource", "analysis", "refusal"), response.keys)
        assertEquals(outcome, response.getValue("status").jsonPrimitive.content, response.toString())
        if (outcome == "refused") {
            assertEquals("none", selectedModule)
            assertEquals(refusal, response.getValue("refusal").jsonObject.getValue("code").jsonPrimitive.content)
            assertEquals("null", response.getValue("selectedSource").toString())
            assertTrue(response.getValue("modules").jsonArray.isEmpty())
            return
        }
        assertEquals("app", selectedModule)
        assertEquals("none", refusal)
        assertEquals("null", response.getValue("refusal").toString())
        val selected = response.getValue("selectedSource").jsonObject
        assertEquals(setOf("path", "symbol", "providerId", "moduleId", "sourceSetId", "sourceRoot",
            "generated", "bindingKey", "evidence"), selected.keys)
        assertEquals("maven-effective-v1", selected.getValue("providerId").jsonPrimitive.content)
        assertEquals("app", selected.getValue("moduleId").jsonPrimitive.content)
        assertEquals("app/src/it/java/it/AppIT.java", selected.getValue("path").jsonPrimitive.content)
        assertEquals("it.AppIT", selected.getValue("symbol").jsonPrimitive.content)
        val app = response.getValue("modules").jsonArray.map { it.jsonObject }
            .single { it.getValue("providerId").jsonPrimitive.content == "maven-effective-v1" &&
                it.getValue("id").jsonPrimitive.content == "app" }
        val sourceSetId = selected.getValue("sourceSetId").jsonPrimitive.content
        val sourceSet = sourceSets(app).getValue(sourceSetId)
        assertEquals("integration-test", sourceSet.getValue("kind").jsonPrimitive.content)
        assertTrue("app/src/it/java" in strings(sourceSet, "sourceRoots"))
    }

    @When("Failsafe metadata is requested for provider {string} module {string} using source-set kind {string}")
    fun requestMavenFailsafeMetadata(providerId: String, moduleId: String, kind: String) {
        assertEquals("maven-effective-v1", providerId)
        assertEquals("app", moduleId)
        mavenFailsafeRequestedKind = kind
        response = requireNotNull(session).dispatch("project.context.v4", buildJsonObject {
            put("expectedSnapshotHash", previousHash)
            put("sdkHome", System.getProperty("java.home"))
            put("providerId", providerId)
            put("moduleId", moduleId)
            put("sourceSetKind", kind)
        }).jsonObject
    }

    @When("provider-qualified Java context v7 is requested for module {string} source-set {string}")
    fun requestMavenTestJvmContext(moduleId: String, kind: String) {
        assertEquals("app", moduleId)
        assertTrue(kind in setOf("main", "test", "integration-test"))
        response = requireNotNull(session).dispatch("project.context.v7", buildJsonObject {
            put("expectedSnapshotHash", previousHash)
            put("sdkHome", System.getProperty("java.home"))
            put("providerId", "maven-effective-v1")
            put("moduleId", moduleId)
            put("sourceSetKind", kind)
        }).jsonObject
    }

    @When("the Maven build model is captured from declared test settings")
    fun captureMavenTestJvmBuildModel() {
        val repository = requireNotNull(container).resolve("empty-maven-repository")
        val snapshot = JavaProjectScanner(localMavenRepository = repository).scan(root)
        val maven = snapshot.buildModels.single { it.providerId == "maven-effective-v1" }
        mavenJvmBuildSourceSets = maven.modules.single { it.id == "app" }.sourceSets
    }

    @Then("Maven build model JVM selection for {string} reports {string} and {string}")
    fun verifyMavenTestJvmBuildModel(sourceSetKind: String, kind: String, availability: String) {
        verifyCapturedMavenJvmSelection(sourceSetKind, kind, availability) { scope ->
            val attributes = mavenJvmBuildSourceSets.single {
                it.kind.name.lowercase().replace('_', '-') == scope
            }.attributes
            fun execution(attribute: String): JsonObject {
                val raw = assertNotNull(attributes[attribute], "Maven Build Model requires the $attribute attribute")
                return kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject
                    .getValue("executions").jsonArray.single().jsonObject
            }
            execution("java.maven.test-jvm.context") to execution("java.maven.test-jvm.context-with-selection")
        }
    }

    @Then("opt-in Maven test JVM selection for {string} reports {string} and {string}")
    fun verifyOptInMavenTestJvmSelection(sourceSetKind: String, kind: String, availability: String) {
        val legacyResponse = response
        val enrichedResponse = requireNotNull(session).dispatch("project.context.v7.testJvmSelection", buildJsonObject {
            put("expectedSnapshotHash", previousHash)
            put("expectedContextHash", legacyResponse.getValue("contextHash"))
            put("sdkHome", System.getProperty("java.home"))
            put("providerId", "maven-effective-v1")
            put("moduleId", "app")
            put("sourceSetKind", sourceSetKind)
        }).jsonObject
        assertEquals(legacyResponse.getValue("contextHash"), enrichedResponse.getValue("contextHash"))
        fun execution(payload: JsonObject, scope: String): JsonObject {
            assertEquals("available", payload.getValue("status").jsonPrimitive.content, payload.toString())
            val module = payload.getValue("modules").jsonArray
                .single { it.jsonObject.getValue("id").jsonPrimitive.content == "app" }.jsonObject
            val sourceSet = sourceSets(module).values.single { it.getValue("kind").jsonPrimitive.content == scope }
            return sourceSet.getValue("mavenTestJvm").jsonObject.getValue("executions").jsonArray.single().jsonObject
        }
        verifyCapturedMavenJvmSelection(sourceSetKind, kind, availability) { scope ->
            execution(legacyResponse, scope) to execution(enrichedResponse, scope)
        }
    }

    private fun verifyCapturedMavenJvmSelection(
        sourceSetKind: String,
        kind: String,
        availability: String,
        executionPair: (String) -> Pair<JsonObject, JsonObject>,
    ) {
        val (legacy, enriched) = executionPair(sourceSetKind)
        assertFalse("jvmSelection" in legacy)
        assertEquals(legacy, JsonObject(enriched - "jvmSelection"))
        val selection = enriched.getValue("jvmSelection").jsonObject
        assertEquals(kind, selection.getValue("kind").jsonPrimitive.content)
        assertEquals(availability, selection.getValue("availability").jsonPrimitive.content)
        val prerequisite = selection.getValue("toolchainPrerequisite")
        if (mavenTestJvmShape.endsWith("wins over toolchain") || mavenTestJvmShape == "test toolchain selection") {
            val toolchain = prerequisite.jsonObject
            assertEquals("unavailable", toolchain.getValue("availability").jsonPrimitive.content)
            assertEquals("java.context.mavenTestJvmToolchainUnresolved", toolchain.getValue("refusalCode").jsonPrimitive.content)
            assertTrue(toolchain.getValue("origin").jsonPrimitive.content.endsWith("/configuration/jdkToolchain"))
            assertEquals(mapOf("version" to "1.8", "vendor" to "temurin"),
                toolchain.getValue("requirements").jsonObject.mapValues { it.value.jsonPrimitive.content })
            assertEquals("false", toolchain.getValue("redacted").jsonPrimitive.content)
        } else if (mavenTestJvmShape != "redacted test toolchain") {
            assertEquals(JsonNull, prerequisite)
        }
        val redacted = mavenTestJvmShape == "redacted test toolchain"
        assertEquals(redacted.toString(), selection.getValue("redacted").jsonPrimitive.content)
        assertFalse(enriched.toString().contains("rk-jvm-redaction-probe"))
        val origin = selection.getValue("origin").jsonPrimitive.content
        when (kind) {
            "explicit-executable" -> {
                val expected = if (mavenTestJvmShape == "Surefire explicit JVM") testExecutable() else buildTestExecutable()
                assertEquals(expected, selection.getValue("executable").jsonPrimitive.content)
                assertTrue(origin.endsWith(if (sourceSetKind == "test") "/configuration/jvm"
                    else "/executions/integration-tests/configuration/jvm"), origin)
                assertEquals(JsonNull, selection.getValue("refusalCode"))
                if (sourceSetKind == "integration-test") {
                    assertEquals("org.apache.maven.plugins:maven-failsafe-plugin", enriched.getValue("pluginId").jsonPrimitive.content)
                    assertEquals("integration-tests", enriched.getValue("executionId").jsonPrimitive.content)
                    val (oldUnit, unitExecution) = executionPair("test")
                    assertEquals(oldUnit, JsonObject(unitExecution - "jvmSelection"))
                    assertEquals("org.apache.maven.plugins:maven-surefire-plugin", unitExecution.getValue("pluginId").jsonPrimitive.content)
                    assertEquals("default-test", unitExecution.getValue("executionId").jsonPrimitive.content)
                    val unitSelection = unitExecution.getValue("jvmSelection").jsonObject
                    assertEquals("build-jvm", unitSelection.getValue("kind").jsonPrimitive.content)
                    assertEquals("complete", unitSelection.getValue("availability").jsonPrimitive.content)
                    assertEquals(JsonNull, unitSelection.getValue("toolchainPrerequisite"))
                }
            }
            "build-jvm" -> {
                assertTrue(origin.startsWith("plugin-default:"), origin)
                assertEquals(JsonNull, selection.getValue("executable"))
            }
            "toolchain" -> {
                assertTrue(origin.endsWith("/configuration/jdkToolchain"))
                assertEquals(mapOf("version" to "1.8", "vendor" to "temurin"),
                    selection.getValue("requirements").jsonObject.mapValues { it.value.jsonPrimitive.content })
                assertEquals("java.context.mavenTestJvmToolchainUnresolved", selection.getValue("refusalCode").jsonPrimitive.content)
                assertEquals(JsonNull, selection.getValue("executable"))
            }
            else -> {
                assertEquals(JsonNull, selection.getValue("executable"))
                assertEquals("java.context.mavenTestJvmSelectionUnavailable", selection.getValue("refusalCode").jsonPrimitive.content)
                assertTrue(selection.getValue("requirements").jsonObject.isEmpty())
            }
        }
    }

    @When("the Maven processor output and generated source are materialized and context is reopened")
    fun materializeMavenReactorProcessorOutputAndReopenContext() {
        materializeMavenReactorAnnotationProcessorOutputs()
        assertTrue(Files.isDirectory(root.resolve("processor/target/classes")))
        assertTrue(Files.isRegularFile(root.resolve(
            "app/target/generated-sources/annotations/fixture/generated/GeneratedGreeting.java")))
        val owned = requireNotNull(container)
        baseline = contents(owned)
        previousHash = requireNotNull(session).dispatch("project.open", buildJsonObject {
            put("root", root.toString())
            put("mavenRepository", owned.resolve("empty-maven-repository").toString())
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
    }

    @Then("Maven v7 context reports the reactor processor output and generated source root")
    fun mavenV7ReactorProcessorAndGeneratedRoot() {
        assertEquals("java-project-context-v7", response.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("available", response.getValue("status").jsonPrimitive.content, response.toString())
        assertEquals(JsonNull, response.getValue("refusal"))
        val app = response.getValue("modules").jsonArray.map { it.jsonObject }
            .single { it.getValue("providerId").jsonPrimitive.content == "maven-effective-v1" &&
                it.getValue("id").jsonPrimitive.content == "app" }
        val main = app.getValue("sourceSets").jsonArray.map { it.jsonObject }
            .single { it.getValue("kind").jsonPrimitive.content == "main" }
        val processorFact = main.getValue("annotationProcessors").jsonObject
        val processorOutput = root.resolve("processor/target/classes").toAbsolutePath().normalize().toString()
        val generatedRoot = "app/target/generated-sources/annotations"
        assertEquals("complete", processorFact.getValue("availability").jsonPrimitive.content, processorFact.toString())
        assertEquals(JsonNull, processorFact.getValue("refusalCode"))
        assertEquals(listOf(processorOutput), processorFact.getValue("processorPath").jsonArray.map { it.jsonPrimitive.content })
        val dependency = processorFact.getValue("dependencies").jsonArray.single().jsonObject
        assertEquals(processorOutput, dependency.getValue("path").jsonPrimitive.content)
        assertEquals("fixture.processor", dependency.getValue("coordinates").jsonObject.getValue("groupId").jsonPrimitive.content)
        assertEquals("greeting-processor", dependency.getValue("coordinates").jsonObject.getValue("artifactId").jsonPrimitive.content)
        assertEquals("classes", dependency.getValue("extension").jsonPrimitive.content)
        assertEquals(listOf("fixture.processor.GreetingProcessor"),
            processorFact.getValue("processorNames").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(generatedRoot in main.getValue("sourceRoots").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf(generatedRoot), main.getValue("generatedSourceRoots").jsonArray.map { it.jsonPrimitive.content })
        val rootEvidence = main.getValue("sourceRootEvidence").jsonArray.map { it.jsonObject }
            .single { it.getValue("path").jsonPrimitive.content == generatedRoot }
        assertEquals("generated", rootEvidence.getValue("origin").jsonPrimitive.content)
        assertEquals("partial", rootEvidence.getValue("availability").jsonPrimitive.content)
        val processorEvidence = response.getValue("buildEvidence").jsonArray.map { it.jsonObject }
            .single { it.getValue("path").jsonPrimitive.content == processorOutput }
        assertEquals("entry", processorEvidence.getValue("kind").jsonPrimitive.content)
        assertTrue(processorEvidence.getValue("fingerprint").jsonPrimitive.content.isNotBlank())
    }

    @Then("Maven v7 context reports the unavailable reactor processor output and no generated source root")
    fun mavenV7UnavailableReactorProcessorOutputAndGeneratedRoot() {
        assertEquals("java-project-context-v7", response.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("available", response.getValue("status").jsonPrimitive.content, response.toString())
        assertEquals(JsonNull, response.getValue("refusal"))
        val app = response.getValue("modules").jsonArray.map { it.jsonObject }
            .single { it.getValue("providerId").jsonPrimitive.content == "maven-effective-v1" &&
                it.getValue("id").jsonPrimitive.content == "app" }
        val main = app.getValue("sourceSets").jsonArray.map { it.jsonObject }
            .single { it.getValue("kind").jsonPrimitive.content == "main" }
        val processorFact = main.getValue("annotationProcessors").jsonObject
        assertEquals("unavailable", processorFact.getValue("availability").jsonPrimitive.content, processorFact.toString())
        assertEquals("java.context.annotationProcessorPathUnavailable",
            processorFact.getValue("refusalCode").jsonPrimitive.content)
        assertTrue(processorFact.getValue("processorPath").jsonArray.isEmpty())
        assertTrue(processorFact.getValue("dependencies").jsonArray.isEmpty())
        assertTrue(main.getValue("generatedSourceRoots").jsonArray.isEmpty())
        assertFalse("app/target/generated-sources/annotations" in
            main.getValue("sourceRoots").jsonArray.map { it.jsonPrimitive.content })
    }

    @Then("test-JVM context for source-set {string} reports {string} mode {string} refusal {string} and argLine {string}")
    fun versionedMavenTestJvmContext(
        requestedSourceSetKind: String,
        availability: String,
        mode: String,
        expectedRefusal: String,
        expectedArgLine: String,
    ) {
        assertEquals("java-project-context-v7", response.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals(setOf("schemaVersion", "status", "snapshotHash", "contextHash", "sdk", "providers",
            "modules", "buildEvidence", "selectedSource", "analysis", "refusal", "semanticAnalysis"), response.keys)
        assertEquals("available", response.getValue("status").jsonPrimitive.content, response.toString())
        assertEquals("null", response.getValue("refusal").toString())
        assertEquals("null", response.getValue("selectedSource").toString())
        val capability = requireNotNull(session).dispatch("server.capabilities", null).jsonObject
            .getValue("methods").jsonArray.map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == "project.context.v7" }
        assertEquals("true", capability.getValue("features").jsonObject
            .getValue("mavenTestJvmSettings").jsonPrimitive.content)
        assertEquals("true", capability.getValue("features").jsonObject
            .getValue("testJvmSettingsAreSourceSetBound").jsonPrimitive.content)
        val app = response.getValue("modules").jsonArray.map { it.jsonObject }
            .single { it.getValue("providerId").jsonPrimitive.content == "maven-effective-v1" &&
                it.getValue("id").jsonPrimitive.content == "app" }
        val allSourceSets = sourceSets(app).values
        val sourceSet = allSourceSets.single {
            it.getValue("kind").jsonPrimitive.content == requestedSourceSetKind
        }
        val fact = sourceSet.getValue("mavenTestJvm").jsonObject
        assertEquals(setOf("availability", "mode", "refusalCode", "executions"), fact.keys)
        assertEquals(availability, fact.getValue("availability").jsonPrimitive.content)
        assertEquals(mode, fact.getValue("mode").jsonPrimitive.content)
        val actualRefusal = fact.getValue("refusalCode")
        assertEquals(if (expectedRefusal == "none") "null" else expectedRefusal,
            if (actualRefusal is JsonNull) "null" else actualRefusal.jsonPrimitive.content)
        val executions = fact.getValue("executions").jsonArray
        val expectedPlugin = if (requestedSourceSetKind == "integration-test") {
            "org.apache.maven.plugins:maven-failsafe-plugin"
        } else "org.apache.maven.plugins:maven-surefire-plugin"
        val associatedKinds = mapOf(
            "test" to "org.apache.maven.plugins:maven-surefire-plugin",
            "integration-test" to "org.apache.maven.plugins:maven-failsafe-plugin",
        )
        for ((kind, pluginId) in associatedKinds) {
            allSourceSets.filter { it.getValue("kind").jsonPrimitive.content == kind }.forEach { row ->
                val rowExecutions = row.getValue("mavenTestJvm").jsonObject.getValue("executions").jsonArray
                assertTrue(rowExecutions.all {
                    it.jsonObject.getValue("pluginId").jsonPrimitive.content == pluginId
                }, "source-set $kind contains an execution owned by another test plugin")
            }
        }
        when (availability) {
            "complete" -> {
                val execution = executions.single().jsonObject
                assertEquals(expectedPlugin, execution.getValue("pluginId").jsonPrimitive.content)
                val expectedVersion = if (mavenTestJvmShape in setOf(
                        "inherited default Surefire lifecycle", "parent-managed Failsafe execution",
                        "one-level parent Surefire JVM settings", "one-level parent Failsafe JVM settings",
                    )) "3.5.4" else "3.5.2"
                assertEquals(expectedVersion, execution.getValue("pluginVersion").jsonPrimitive.content)
                assertEquals(mode, execution.getValue("mode").jsonPrimitive.content)
                val argLine = execution.getValue("argLineLayers").jsonArray.single().jsonObject
                assertEquals("argLine", argLine.getValue("name").jsonPrimitive.content)
                val expectedArgLineValue = if (expectedArgLine == "empty") "" else expectedArgLine
                assertEquals(expectedArgLineValue, argLine.getValue("value").jsonPrimitive.content)
                assertEquals("complete", argLine.getValue("availability").jsonPrimitive.content)
                assertFalse(argLine.getValue("redacted").jsonPrimitive.content.toBoolean())
                val argLineOrigin = argLine.getValue("origin").jsonPrimitive.content
                if (expectedArgLine == "empty") {
                    assertTrue(argLineOrigin.startsWith("plugin-default:parent-pluginManagement:"), argLineOrigin)
                    assertTrue(argLineOrigin.contains(":jar-sha256="), argLineOrigin)
                    val forkSettings = execution.getValue("forkSettings").jsonArray.map { it.jsonObject }
                        .associate { it.getValue("name").jsonPrimitive.content to it.getValue("value").jsonPrimitive.content }
                    assertEquals(mapOf("forkCount" to "1", "reuseForks" to "true"), forkSettings)
                    assertTrue(execution.getValue("forkSettings").jsonArray.all {
                        it.jsonObject.getValue("origin").jsonPrimitive.content == argLineOrigin
                    })
                } else if (mavenTestJvmShape in setOf(
                        "one-level parent Surefire JVM settings", "one-level parent Failsafe JVM settings",
                    )) {
                    assertTrue(argLineOrigin.startsWith("inherited-parent:pom.xml:build/plugins/"), argLineOrigin)
                } else {
                    assertTrue(argLineOrigin.startsWith("pom.xml:build/plugins/"))
                }
                assertEquals(expectedArgLineValue, execution.getValue("effectiveArgLineTemplate").jsonPrimitive.content)
            }
            "partial" -> {
                val execution = executions.single().jsonObject
                assertEquals(mode, execution.getValue("mode").jsonPrimitive.content)
                when (mavenTestJvmShape) {
                    "implicit surefire", "inherited Surefire version unavailable",
                    "Surefire version property matches parent management" -> {
                    assertEquals(expectedPlugin, execution.getValue("pluginId").jsonPrimitive.content)
                    assertEquals(JsonNull, execution.getValue("pluginVersion"))
                    assertEquals("default-test", execution.getValue("executionId").jsonPrimitive.content)
                    assertEquals(listOf("test"), execution.getValue("goals").jsonArray.map { it.jsonPrimitive.content })
                    assertEquals("test", execution.getValue("phase").jsonPrimitive.content)
                    assertTrue(execution.getValue("forkSettings").jsonArray.isEmpty())
                    assertTrue(execution.getValue("argLineLayers").jsonArray.isEmpty())
                    assertEquals("java.context.mavenTestJvmConfigurationIncomplete",
                        execution.getValue("refusalCode").jsonPrimitive.content)
                    }
                    "failsafe JVM settings absent", "Failsafe declaration without descriptor" -> {
                    assertEquals(expectedPlugin, execution.getValue("pluginId").jsonPrimitive.content)
                    assertEquals("3.5.2", execution.getValue("pluginVersion").jsonPrimitive.content)
                    assertEquals("integration-tests", execution.getValue("executionId").jsonPrimitive.content)
                    assertEquals(listOf("integration-test", "verify"),
                        execution.getValue("goals").jsonArray.map { it.jsonPrimitive.content })
                    assertEquals("integration-test", execution.getValue("phase").jsonPrimitive.content)
                    assertTrue(execution.getValue("forkSettings").jsonArray.isEmpty())
                    assertTrue(execution.getValue("argLineLayers").jsonArray.isEmpty())
                    assertEquals("java.context.mavenTestJvmConfigurationIncomplete",
                        execution.getValue("refusalCode").jsonPrimitive.content)
                    }
                    "JaCoCo prepare-agent contributes argLine" -> {
                        assertEquals("3.5.4", execution.getValue("pluginVersion").jsonPrimitive.content)
                        assertEquals("default-test", execution.getValue("executionId").jsonPrimitive.content)
                        assertEquals("forked", execution.getValue("mode").jsonPrimitive.content)
                        val forkSettings = execution.getValue("forkSettings").jsonArray.map { it.jsonObject }
                            .associate { it.getValue("name").jsonPrimitive.content to it.getValue("value").jsonPrimitive.content }
                        assertEquals(mapOf("forkCount" to "1", "reuseForks" to "true"), forkSettings)
                        val argLine = execution.getValue("argLineLayers").jsonArray.single().jsonObject
                        assertEquals(JsonNull, argLine.getValue("value"))
                        assertEquals("unavailable", argLine.getValue("availability").jsonPrimitive.content)
                        assertTrue(argLine.getValue("origin").jsonPrimitive.content.contains(":jar-sha256="))
                    }
                    else -> {
                    val argLine = execution.getValue("argLineLayers").jsonArray.single().jsonObject
                    assertEquals("null", argLine.getValue("value").toString())
                    assertEquals("unavailable", argLine.getValue("availability").jsonPrimitive.content)
                    }
                }
                assertEquals("null", execution.getValue("effectiveArgLineTemplate").toString())
            }
            "ambiguous" -> assertTrue(executions.isEmpty())
        }
        assertEquals("java-project-context-v7", response.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals(baseline, contents(requireNotNull(container)))
        writeTestJvmV7Response(response)
    }

    @And("Maven execution metadata identifies plugin {string} execution {string} goals {string} phase {string} version {string} and Failsafe metadata {string} selector {string} reports {string}")
    fun exactMavenTestJvmExecutionMetadata(
        expectedPlugin: String,
        expectedExecutionId: String,
        expectedGoals: String,
        expectedPhase: String,
        expectedPluginVersion: String,
        expectedFailsafeAvailability: String,
        expectedSelector: String,
        expectedReports: String,
    ) {
        val app = response.getValue("modules").jsonArray.map { it.jsonObject }
            .single { it.getValue("providerId").jsonPrimitive.content == "maven-effective-v1" &&
                it.getValue("id").jsonPrimitive.content == "app" }
        val sourceSetKind = if (mavenTestJvmShape in setOf(
                "direct failsafe", "failsafe JVM settings absent", "Failsafe declaration without descriptor",
                "parent-managed Failsafe execution", "one-level parent Failsafe JVM settings",
            )) {
            "integration-test"
        } else "test"
        val sourceSet = sourceSets(app).values.single { it.getValue("kind").jsonPrimitive.content == sourceSetKind }
        val testJvm = sourceSet.getValue("mavenTestJvm").jsonObject
        val executions = testJvm.getValue("executions").jsonArray
        if (expectedPlugin == "none") {
            assertEquals("none", expectedExecutionId)
            assertTrue(executions.isEmpty(), executions.toString())
        } else {
            val execution = executions.single().jsonObject
            val pluginId = when (expectedPlugin) {
                "surefire" -> "org.apache.maven.plugins:maven-surefire-plugin"
                "failsafe" -> "org.apache.maven.plugins:maven-failsafe-plugin"
                else -> error("Unexpected test JVM plugin $expectedPlugin")
            }
            assertEquals(pluginId, execution.getValue("pluginId").jsonPrimitive.content)
            assertEquals(expectedExecutionId, execution.getValue("executionId").jsonPrimitive.content)
            assertEquals(if (expectedGoals == "none") emptyList() else expectedGoals.split(','),
                execution.getValue("goals").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(if (expectedPhase == "none") "null" else expectedPhase,
                if (execution.getValue("phase") is JsonNull) "null" else execution.getValue("phase").jsonPrimitive.content)
            assertEquals(if (expectedPluginVersion == "none") "null" else expectedPluginVersion,
                if (execution.getValue("pluginVersion") is JsonNull) "null" else execution.getValue("pluginVersion").jsonPrimitive.content)
        }

        val failsafe = sourceSet.getValue("mavenFailsafe").jsonObject
        assertEquals(expectedFailsafeAvailability, failsafe.getValue("availability").jsonPrimitive.content)
        if (expectedFailsafeAvailability == "not-declared") {
            assertTrue(failsafe.getValue("executions").jsonArray.isEmpty(), failsafe.toString())
            assertEquals(JsonNull, failsafe.getValue("refusalCode"))
            assertEquals("none", expectedSelector)
            assertEquals("none", expectedReports)
        } else {
            assertEquals("null", failsafe.getValue("refusalCode").toString())
            val execution = failsafe.getValue("executions").jsonArray.single().jsonObject
            val expectedFailsafeExecutionId = if (mavenTestJvmShape in setOf(
                    "parent-managed Failsafe execution", "one-level parent Failsafe JVM settings",
                )) {
                "failsafe-app-tests"
            } else "integration-tests"
            assertEquals(expectedFailsafeExecutionId, execution.getValue("executionId").jsonPrimitive.content)
            assertEquals(expectedPluginVersion, execution.getValue("pluginVersion").jsonPrimitive.content)
            val selectors = execution.getValue("selectors").jsonArray.map { it.jsonObject }
                .associate { it.getValue("name").jsonPrimitive.content to it.getValue("value").jsonPrimitive.content }
            assertEquals(if (expectedSelector == "none") emptyMap() else mapOf("includes" to expectedSelector), selectors)
            assertTrue(execution.getValue("selectors").jsonArray.all {
                it.jsonObject.getValue("origin").jsonPrimitive.content == "base-execution"
            })
            val reports = execution.getValue("reports").jsonArray.map { it.jsonObject }
                .associate { it.getValue("name").jsonPrimitive.content to it.getValue("value").jsonPrimitive.content }
            val expectedReportValues = if (expectedReports == "none") emptyMap() else expectedReports.split(',')
                .associate { it.substringBefore('=') to it.substringAfter('=') }
            assertEquals(expectedReportValues, reports)
            assertTrue(execution.getValue("reports").jsonArray.all {
                it.jsonObject.getValue("origin").jsonPrimitive.content == "base-execution"
            })
        }
    }

    @Then("versioned project context reports Failsafe metadata {string} with execution {string} and refusal {string}")
    fun versionedMavenFailsafeMetadata(availability: String, expectedExecution: String, expectedRefusal: String) {
        assertEquals("java-project-context-v4", response.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals(setOf("schemaVersion", "status", "snapshotHash", "contextHash", "sdk", "providers",
            "modules", "buildEvidence", "selectedSource", "analysis", "refusal"), response.keys)
        assertEquals("available", response.getValue("status").jsonPrimitive.content, response.toString())
        assertEquals("null", response.getValue("refusal").toString())
        assertEquals("null", response.getValue("selectedSource").toString())
        val capability = requireNotNull(session).dispatch("server.capabilities", null).jsonObject
            .getValue("methods").jsonArray.map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == "project.context.v4" }
        assertEquals("true", capability.getValue("features").jsonObject
            .getValue("mavenFailsafeExecutionMetadata").jsonPrimitive.content)
        val app = response.getValue("modules").jsonArray.map { it.jsonObject }
            .single { it.getValue("providerId").jsonPrimitive.content == "maven-effective-v1" &&
                it.getValue("id").jsonPrimitive.content == "app" }
        val sourceSet = sourceSets(app).values.single {
            it.getValue("kind").jsonPrimitive.content == mavenFailsafeRequestedKind
        }
        val fact = sourceSet.getValue("mavenFailsafe").jsonObject
        assertEquals(setOf("availability", "refusalCode", "executions"), fact.keys)
        assertEquals(availability, fact.getValue("availability").jsonPrimitive.content)
        val refusal = fact.getValue("refusalCode")
        assertEquals(if (expectedRefusal == "none") "null" else expectedRefusal,
            if (refusal is JsonNull) "null" else refusal.jsonPrimitive.content)
        val executions = fact.getValue("executions").jsonArray
        if (expectedExecution == "none") {
            assertTrue(executions.isEmpty())
        } else {
            val execution = executions.single().jsonObject
            assertEquals(expectedExecution, execution.getValue("executionId").jsonPrimitive.content)
            if (availability == "complete") {
                assertEquals("org.apache.maven.plugins:maven-failsafe-plugin", execution.getValue("pluginId").jsonPrimitive.content)
                assertEquals("3.5.2", execution.getValue("pluginVersion").jsonPrimitive.content)
                assertEquals("integration-test", execution.getValue("phase").jsonPrimitive.content)
                assertEquals("quality", execution.getValue("profileId").jsonPrimitive.content)
                assertEquals(listOf("integration-test", "verify"), execution.getValue("goals").jsonArray.map { it.jsonPrimitive.content })
                val selectors = execution.getValue("selectors").jsonArray.map { it.jsonObject }
                assertEquals(1, selectors.size)
                assertEquals("includes", selectors.single().getValue("name").jsonPrimitive.content)
                assertEquals("**/*IT.java", selectors.single().getValue("value").jsonPrimitive.content)
                assertEquals("profile-execution:quality", selectors.single().getValue("origin").jsonPrimitive.content)
                val reports = execution.getValue("reports").jsonArray.associate {
                    val setting = it.jsonObject
                    setting.getValue("name").jsonPrimitive.content to setting.getValue("value").jsonPrimitive.content
                }
                assertEquals(mapOf("reportsDirectory" to "target/failsafe-reports", "reportFormat" to "plain"), reports)
            } else {
                assertEquals("partial", execution.getValue("availability").jsonPrimitive.content)
                assertEquals("java.context.mavenFailsafeConfigurationIncomplete",
                    execution.getValue("refusalCode").jsonPrimitive.content)
                val unresolved = execution.getValue("selectors").jsonArray.single().jsonObject
                assertEquals("null", unresolved.getValue("value").toString())
                assertEquals("unavailable", unresolved.getValue("availability").jsonPrimitive.content)
            }
        }
        writeFailsafeV4Response(response)
    }

    @Then("Failsafe source-set context reports {string} with refusal {string} and module {string}")
    fun mavenFailsafeOutcome(outcome: String, refusal: String, selectedModule: String) {
        assertEquals("java-project-context-v2", response.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals(outcome, response.getValue("status").jsonPrimitive.content, response.toString())
        if (outcome == "refused") {
            assertEquals(refusal, response.getValue("refusal").jsonObject.getValue("code").jsonPrimitive.content)
            assertEquals("null", response.getValue("selectedSource").toString())
            assertTrue(response.getValue("modules").jsonArray.isEmpty())
            return
        }
        assertEquals("null", response.getValue("refusal").toString())
        val modules = response.getValue("modules").jsonArray.map { it.jsonObject }
            .associateBy { it.getValue("id").jsonPrimitive.content }
        assertEquals(setOf("app", "core"), modules.keys)
        if (mavenFailsafeSelection == "app symbol") {
            assertEquals("none", refusal)
            assertEquals("app", selectedModule)
            val selected = response.getValue("selectedSource").jsonObject
            assertEquals("app", selected.getValue("moduleId").jsonPrimitive.content)
            assertEquals("app/src/it/java/it/AppIT.java", selected.getValue("path").jsonPrimitive.content)
            assertEquals("JDT_BINDING", selected.getValue("evidence").jsonPrimitive.content)
            val appSets = sourceSets(modules.getValue("app"))
            val coreSets = sourceSets(modules.getValue("core"))
            val appIntegration = appSets.values.single { it.getValue("kind").jsonPrimitive.content == "integration-test" }
            val coreIntegration = coreSets.values.single { it.getValue("kind").jsonPrimitive.content == "integration-test" }
            assertEquals(selected.getValue("sourceSetId").jsonPrimitive.content,
                appIntegration.getValue("id").jsonPrimitive.content)
            assertEquals(listOf("app/src/it/java"), strings(appIntegration, "sourceRoots"))
            assertEquals(listOf("core/src/it/java"), strings(coreIntegration, "sourceRoots"))
            assertEquals(listOf("app/target/test-classes"), strings(appIntegration, "outputDirectories"))
            assertEquals(listOf("core/target/test-classes"), strings(coreIntegration, "outputDirectories"))
            val evidence = appIntegration.getValue("sourceRootEvidence").jsonArray.map { it.jsonObject }
                .single { it.getValue("path").jsonPrimitive.content == "app/src/it/java" }
            assertEquals("build-plugin", evidence.getValue("origin").jsonPrimitive.content)
            assertEquals("maven-effective-v1", evidence.getValue("providerId").jsonPrimitive.content)
            assertEquals("app/pom.xml", evidence.getValue("descriptorPath").jsonPrimitive.content)
            assertEquals("quality", evidence.getValue("profileId").jsonPrimitive.content)
            assertEquals("org.codehaus.mojo:build-helper-maven-plugin", evidence.getValue("pluginId").jsonPrimitive.content)
            assertEquals("register-tests", evidence.getValue("executionId").jsonPrimitive.content)
            assertEquals("add-test-source", evidence.getValue("goal").jsonPrimitive.content)
            assertEquals("generate-test-sources", evidence.getValue("phase").jsonPrimitive.content)
            assertEquals("complete", evidence.getValue("availability").jsonPrimitive.content)
            val appTest = appSets.values.single { it.getValue("id").jsonPrimitive.content == "test" }
            assertEquals(paths(appTest, "compileClasspath"), paths(appIntegration, "compileClasspath"))
            assertEquals(paths(appTest, "runtimeClasspath"), paths(appIntegration, "runtimeClasspath"))
            val compile = paths(appIntegration, "compileClasspath")
            assertTrue("core/target/classes" in compile, compile.toString())
            assertFalse(compile.any { it == "core/target/test-classes" || it.startsWith("core/src/it/") }, compile.toString())
            assertFalse(strings(appIntegration, "analysisSourceRoots").any { it.startsWith("core/src/it/") })
        } else {
            assertEquals("none", selectedModule)
            assertEquals("none", refusal)
            assertEquals("null", response.getValue("selectedSource").toString())
            assertTrue(modules.values.flatMap { module -> sourceSets(module).values }.none { row ->
                row.getValue("kind").jsonPrimitive.content == "integration-test" ||
                    strings(row, "sourceRoots").any { it.endsWith("/src/it/java") }
            })
        }
    }

    private fun sourceSets(module: JsonObject): Map<String, JsonObject> = module.getValue("sourceSets").jsonArray
        .map { it.jsonObject }.associateBy { it.getValue("id").jsonPrimitive.content }

    private fun strings(sourceSet: JsonObject, key: String): List<String> = sourceSet.getValue(key).jsonArray
        .map { it.jsonPrimitive.content }

    private var sourceProvider = "maven-effective-v1"
    private lateinit var sourceVariant: String
    private lateinit var dependencyBinary: Path
    private lateinit var dependencyAttachment: Path
    private lateinit var dependencyText: String
    private lateinit var sourceQuery: JsonObject

    @Given("a Java binary dependency with {string} source attachment evidence")
    fun dependencySourceFixture(variant: String) {
        sourceVariant = variant
        sourceProvider = "maven-effective-v1"
        val owned = Files.createTempDirectory("rk-dependency-source-"); container = owned
        root = owned.resolve("app")
        val repository = owned.resolve("repository")
        val secondary = variant == "secondary"
        val filename = if (secondary) "Container.java" else "Library.java"
        dependencyText = if (secondary) "package external; public class Container { public static class Library {} }\n"
            else "package external; public class Library { public int answer() { return 42; } }\n"
        val input = owned.resolve("producer/$filename"); write(input, dependencyText)
        val classes = owned.resolve("producer/classes"); Files.createDirectories(classes)
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "--release", "17", "-d", classes.toString(), input.toString()))
        val artifacts = if (variant == "duplicate") listOf("library", "duplicate") else listOf("library")
        artifacts.forEach { artifact ->
            val dir = repository.resolve("external/$artifact/1"); Files.createDirectories(dir)
            write(dir.resolve("$artifact-1.pom"), "<project><modelVersion>4.0.0</modelVersion><groupId>external</groupId>" +
                "<artifactId>$artifact</artifactId><version>1</version></project>\n")
            val binary = dir.resolve("$artifact-1.jar")
            JarOutputStream(Files.newOutputStream(binary)).use { jar ->
                Files.walk(classes).use { paths -> paths.filter(Files::isRegularFile).sorted().forEach { file ->
                    jar.putNextEntry(java.util.jar.JarEntry(classes.relativize(file).toString().replace('\\', '/')))
                    jar.write(Files.readAllBytes(file)); jar.closeEntry()
                } }
            }
            val attachment = dir.resolve("$artifact-1-sources.jar")
            if (variant != "missing") sourceArchive(attachment, filename, dependencyText)
            if (artifact == "library") { dependencyBinary = binary; dependencyAttachment = attachment }
        }
        write(root.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId>" +
            "<artifactId>app</artifactId><version>1</version><properties><maven.compiler.release>17</maven.compiler.release></properties>" +
            "<dependencies>" + artifacts.joinToString("") { "<dependency><groupId>external</groupId><artifactId>$it</artifactId><version>1</version></dependency>" } +
            "</dependencies></project>\n")
        val consumer = if (secondary) "package fixture; class App { external.Container.Library value; }\n"
            else "package fixture; class App { external.Library value; }\n"
        write(root.resolve("src/main/java/fixture/App.java"), consumer)
        val active = DaemonSession(); session = active
        previousHash = active.dispatch("project.open", buildJsonObject {
            put("root", root.toString()); put("mavenRepository", repository.toString())
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        val context = active.dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", previousHash); put("sdkHome", System.getProperty("java.home"))
        }).jsonObject
        assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
        sourceQuery = buildJsonObject {
            put("expectedSnapshotHash", previousHash); put("expectedContextHash", context.getValue("contextHash"))
            put("sdkHome", System.getProperty("java.home")); put("path", "src/main/java/fixture/App.java")
            put("position", buildJsonObject { put("line", 0); put("character", consumer.indexOf("Library") + 1) })
        }
        baseline = contents(owned)
    }

    @When("its bound dependency source is looked up and read with {string} evidence")
    fun dependencySourceRead(change: String) {
        val active = requireNotNull(session)
        val lookup = active.dispatch("java.dependencySource.lookup", sourceQuery).jsonObject
        response = lookup
        if (lookup.getValue("status").jsonPrimitive.content == "available") {
            if (sourceProvider == "gradle-effective-v1") {
                val association = lookup.getValue("association").jsonObject
                dependencyBinary = Path.of(association.getValue("binaryPath").jsonPrimitive.content)
                dependencyAttachment = Path.of(association.getValue("sourceArchivePath").jsonPrimitive.content)
                assertTrue(dependencyBinary.startsWith(requireNotNull(container)))
                assertTrue(dependencyAttachment.startsWith(requireNotNull(container)))
            }
            if (change == "source change") sourceArchive(dependencyAttachment, "Library.java", dependencyText + "// changed attachment\n")
            if (change == "binary change") Files.write(dependencyBinary, Files.readAllBytes(dependencyBinary) + byteArrayOf(0))
            baseline = contents(requireNotNull(container))
            val association = lookup.getValue("association").jsonObject.getValue("associationHash")
            response = active.dispatch("java.dependencySource.read", JsonObject(sourceQuery + ("expectedAssociationHash" to association))).jsonObject
        }
    }

    @Then("dependency source navigation reports {string} with exact classfile provenance")
    fun dependencySourceOutcome(outcome: String) {
        assertEquals("java-dependency-source-v1", response.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals(if (outcome == "stale") "refused" else outcome, response.getValue("status").jsonPrimitive.content, response.toString())
        if (outcome == "available") {
            assertEquals(dependencyText, response.getValue("content").jsonPrimitive.content)
            val attachment = response.getValue("association").jsonObject
            assertEquals("true", attachment.getValue("readOnly").jsonPrimitive.content)
            assertEquals(sourceProvider, attachment.getValue("providerId").jsonPrimitive.content)
            assertEquals("main", attachment.getValue("sourceSetId").jsonPrimitive.content)
            assertEquals("external/" + if (sourceVariant == "secondary") "Container.java" else "Library.java",
                attachment.getValue("sourceEntry").jsonPrimitive.content)
            assertEquals(if (sourceProvider == "maven-effective-v1") "MAVEN_SELECTED_COORDINATE_AND_CLASSFILE_SOURCE_FILE" else "GRADLE_RESOLVED_COMPONENT_SOURCES_VARIANT_AND_CLASSFILE_SOURCE_FILE", attachment.getValue("provenance").jsonObject.getValue("kind").jsonPrimitive.content)
            assertTrue(attachment.getValue("resourceUri").jsonPrimitive.content.startsWith("refactorkit-java-source://"))
            assertTrue(attachment.getValue("associationHash").jsonPrimitive.content.matches(Regex("[a-f0-9]{64}")))
            val caps = requireNotNull(session).dispatch("server.capabilities", null).jsonObject.getValue("methods").jsonArray
            listOf("java.dependencySource.lookup", "java.dependencySource.read").forEach { method ->
                assertEquals("false", caps.map { it.jsonObject }.single { it.getValue("name").jsonPrimitive.content == method }
                    .getValue("writesWorkspace").jsonPrimitive.content)
            }
        } else if (outcome == "stale") assertEquals("java.sources.associationStale", response.getValue("reason").jsonObject.getValue("code").jsonPrimitive.content)
        else if (outcome == "ambiguous") assertEquals(2, response.getValue("candidates").jsonArray.size)
    }

    @Given("a Gradle binary dependency with {string} source variant metadata")
    fun gradleDependencySourceFixture(variant: String) {
        dependencySourceFixture(variant)
        requireNotNull(session).close()
        Files.delete(root.resolve("pom.xml"))
        val owned = requireNotNull(container)
        val artifacts = if (variant == "duplicate") listOf("library", "duplicate") else listOf("library")
        write(root.resolve("settings.gradle"), "rootProject.name='source-navigation'\n")
        write(root.resolve("build.gradle"), "plugins { id 'java' }\nrepositories { maven { url=uri('" + owned.resolve("repository").toUri() + "') } }\n" +
            "dependencies { " + artifacts.joinToString("; ") { "implementation 'external:$it:1'" } + " }\n" +
            "java { toolchain { languageVersion=JavaLanguageVersion.of(21) } }\ntasks.withType(JavaCompile).configureEach { options.release=17 }\n")
        val active = DaemonSession(); session = active
        val opened = active.dispatch("project.open", buildJsonObject { put("root", root.toString()) }).jsonObject
        val sync = active.dispatch("project.sync", buildJsonObject {
            put("buildSystem", "gradle")
            put("expectedSnapshotHash", opened.getValue("snapshotHash")); put("sdkHome", System.getProperty("java.home"))
            put("gradleExecutable", "/home/paperboy/.gradle/wrapper/dists/gradle-9.6.1-bin/4ticwg1pgcbps2hj28r8so764/gradle-9.6.1/bin/gradle")
            put("gradleUserHome", owned.resolve("gradle-home").toString()); put("allowBuildExecution", true)
        }).jsonObject
        assertEquals("synchronized", sync.getValue("status").jsonPrimitive.content, sync.toString())
        previousHash = sync.getValue("snapshotHash").jsonPrimitive.content
        val context = active.dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", previousHash); put("sdkHome", System.getProperty("java.home"))
        }).jsonObject
        assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
        sourceQuery = JsonObject(sourceQuery + mapOf("expectedSnapshotHash" to kotlinx.serialization.json.JsonPrimitive(previousHash),
            "expectedContextHash" to context.getValue("contextHash")))
        sourceProvider = "gradle-effective-v1"
        baseline = contents(owned)
    }

    @Then("dependency source queries preserve synchronized project bytes")
    fun dependencySourceReadOnly() {
        assertEquals(baseline, contents(requireNotNull(container)))
    }

    private lateinit var declarationVariant: String
    private lateinit var declarationSources: Map<String, String>
    private lateinit var declarationEntry: String
    private lateinit var declarationExpectedName: String
    private lateinit var declarationExpectedDescriptor: String
    private lateinit var declarationConsumer: String

    @Given("attested dependency sources with a {string} JVM binding")
    fun dependencyDeclarationFixture(variant: String) {
        dependencySourceFixture("attached")
        requireNotNull(session).close()
        declarationVariant = variant
        val owned = requireNotNull(container)
        val library = """
            package external;
            public class Library<T extends Number> {
              public Library(int value) {}
              public Library(String value) {}
              public int pick(int value) { return value; }
              public String pick(String value) { return value; }
              public T echo(T value) { return value; }
            }
        """.trimIndent() + "\n"
        declarationSources = mapOf("external/Library.java" to library,
            "external/Container.java" to "package external; public class Container {} class Secondary { public static class Nested {} }\n",
            "external/Data.java" to "package external; public record Data(int value) { public Data { if (value < 0) throw new IllegalArgumentException(); } }\n")
        val sourceRoot = owned.resolve("declaration-producer")
        val classes = sourceRoot.resolve("classes"); Files.createDirectories(classes)
        declarationSources.forEach { (path, text) -> write(sourceRoot.resolve(path), text) }
        val arguments = listOf("--release", "17", "-d", classes.toString()) + declarationSources.keys.map { sourceRoot.resolve(it).toString() }
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, *arguments.toTypedArray()))
        JarOutputStream(Files.newOutputStream(dependencyBinary)).use { jar -> Files.walk(classes).use { paths ->
            paths.filter(Files::isRegularFile).sorted().forEach { file ->
                jar.putNextEntry(java.util.jar.JarEntry(classes.relativize(file).toString().replace('\\', '/')))
                jar.write(Files.readAllBytes(file)); jar.closeEntry()
            }
        } }
        if (variant == "mismatch") declarationSources = declarationSources + ("external/Library.java" to library.replace("pick(int value)", "pick(long value)").replace("return value; }\n  public String", "return (int) value; }\n  public String"))
        JarOutputStream(Files.newOutputStream(dependencyAttachment)).use { jar -> declarationSources.forEach { (name, text) ->
            jar.putNextEntry(java.util.jar.JarEntry(name)); jar.write(text.toByteArray(Charsets.UTF_8)); jar.closeEntry()
        } }
        Files.delete(root.resolve("src/main/java/fixture/App.java"))
        declarationConsumer = "package external; class Client { Library<Integer> library = new Library<>(1); int picked = library.pick(7); " +
            "Integer generic = library.echo(8); Secondary.Nested nested; Data data = new Data(9); int implicit() { return data.value(); } }\n"
        write(root.resolve("src/main/java/external/Client.java"), declarationConsumer)
        val target = when (variant) { "generic erasure" -> "echo"; "secondary nested" -> "Nested"; "constructor" -> "Library<>(1)"; "record" -> "Data(9)"; else -> "pick(7)" }
        declarationEntry = when (variant) { "secondary nested" -> "external/Container.java"; "record" -> "external/Data.java"; else -> "external/Library.java" }
        declarationExpectedName = when (variant) { "generic erasure" -> "echo"; "secondary nested" -> "Nested"; "constructor" -> "Library"; "record" -> "Data"; else -> "pick" }
        declarationExpectedDescriptor = when (variant) { "generic erasure" -> "(Ljava/lang/Number;)Ljava/lang/Number;"; "secondary nested" -> "Lexternal/Secondary\$Nested;"; "constructor", "record" -> "(I)V"; else -> "(I)I" }
        val active = DaemonSession(); session = active
        previousHash = active.dispatch("project.open", buildJsonObject { put("root", root.toString()); put("mavenRepository", owned.resolve("repository").toString()) })
            .jsonObject.getValue("snapshotHash").jsonPrimitive.content
        val context = active.dispatch("project.context", buildJsonObject { put("expectedSnapshotHash", previousHash); put("sdkHome", System.getProperty("java.home")) }).jsonObject
        assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
        sourceQuery = buildJsonObject { put("expectedSnapshotHash", previousHash); put("expectedContextHash", context.getValue("contextHash")); put("sdkHome", System.getProperty("java.home"))
            put("path", "src/main/java/external/Client.java"); put("position", buildJsonObject { put("line", 0); put("character", declarationConsumer.indexOf(target) + 1) }) }
        baseline = contents(owned)
    }

    @When("the exact dependency declaration is requested with current source association authority")
    fun dependencyDeclarationRequest() {
        response = requireNotNull(session).dispatch("java.dependencySource.declaration", sourceQuery).jsonObject
    }

    @Then("semantic source navigation reports {string} without guessed declaration positions")
    fun dependencyDeclarationOutcome(outcome: String) {
        assertEquals("java-dependency-source-declaration-v1", response.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals(outcome, response.getValue("status").jsonPrimitive.content, response.toString())
        if (outcome == "available") {
            val association = response.getValue("association").jsonObject
            assertEquals(declarationEntry, association.getValue("sourceEntry").jsonPrimitive.content)
            assertEquals(declarationExpectedDescriptor, association.getValue("jvmIdentity").jsonObject.getValue("descriptor").jsonPrimitive.content)
            val declaration = response.getValue("declaration").jsonObject
            assertEquals("JDT_SOURCE_BINDING", declaration.getValue("evidence").jsonPrimitive.content)
            val text = declarationSources.getValue(declarationEntry)
            fun selected(key: String): String {
                val range = declaration.getValue(key).jsonObject
                fun offset(end: String): Int { val position = range.getValue(end).jsonObject
                    val line = position.getValue("line").jsonPrimitive.content.toInt(); val character = position.getValue("character").jsonPrimitive.content.toInt()
                    return text.split('\n').take(line).sumOf { it.length + 1 } + character }
                return text.substring(offset("start"), offset("end"))
            }
            assertEquals(declarationExpectedName, selected("nameRange"))
            val expectedPrefix = when (declarationVariant) { "overload" -> "public int pick(int value)"; "generic erasure" -> "public T echo(T value)";
                "secondary nested" -> "public static class Nested"; "constructor" -> "public Library(int value)"; "record" -> "public Data {"; else -> error("variant") }
            assertTrue(selected("declarationRange").startsWith(expectedPrefix), selected("declarationRange"))
            val guarded = requireNotNull(session).dispatch("java.dependencySource.declaration", JsonObject(sourceQuery +
                ("expectedAssociationHash" to association.getValue("associationHash")))).jsonObject
            assertEquals(response, guarded)
            if (declarationVariant == "record") {
                val implicit = requireNotNull(session).dispatch("java.dependencySource.declaration", JsonObject(sourceQuery + ("position" to
                    buildJsonObject { put("line", 0); put("character", declarationConsumer.indexOf("value()") + 1) }))).jsonObject
                assertEquals("unavailable", implicit.getValue("status").jsonPrimitive.content, implicit.toString())
            }
        } else assertEquals("java.sources.declarationMismatch", response.getValue("reason").jsonObject.getValue("code").jsonPrimitive.content)
    }

    private lateinit var declarationEvidence: String

    @Given("attested source declaration evidence contains {string}")
    fun dependencyDeclarationEvidence(evidence: String) {
        dependencyDeclarationFixture(if (evidence == "implicit record member") "record" else "overload")
        declarationEvidence = evidence
        val original = declarationSources.getValue("external/Library.java")
        val revised = when (evidence) {
            "duplicate methods" -> original.replace("  public int pick(int value) { return value; }\n",
                "  public int pick(int value) { return value; }\n  public int pick(int value) { return 0; }\n")
            "duplicate fields" -> original.replace("public class Library<T extends Number> {", "public class Library<T extends Number> { public int duplicate; public int duplicate;")
            "missing explicit member" -> original.replace("  public int pick(int value) { return value; }\n", "").replace("  public String pick(String value) { return value; }\n", "")
            "static mismatch" -> original.replace("public int pick(int value)", "public static int pick(int value)")
            else -> original
        }
        declarationSources = declarationSources + ("external/Library.java" to revised)
        JarOutputStream(Files.newOutputStream(dependencyAttachment)).use { jar -> declarationSources.forEach { (name, text) ->
            jar.putNextEntry(java.util.jar.JarEntry(name)); jar.write(text.toByteArray(Charsets.UTF_8)); jar.closeEntry()
        } }
        if (evidence == "implicit record member") sourceQuery = JsonObject(sourceQuery + ("position" to
            buildJsonObject { put("line", 0); put("character", declarationConsumer.indexOf("value()") + 1) }))
        baseline = contents(requireNotNull(container))
    }

    @Then("exact source declaration evidence reports {string} without selecting an ambiguous range")
    fun dependencyDeclarationEvidenceOutcome(outcome: String) {
        assertEquals("java-dependency-source-declaration-v1", response.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals(outcome, response.getValue("status").jsonPrimitive.content, response.toString())
        if (outcome == "available") dependencyDeclarationOutcome(outcome)
        else {
            assertFalse(response.containsKey("declaration"))
            val expected = when (outcome) { "ambiguous" -> "java.sources.declarationAmbiguous";
                "refused" -> "java.sources.declarationMismatch"; else -> "java.sources.declarationUnavailable" }
            assertEquals(expected, response.getValue("reason").jsonObject.getValue("code").jsonPrimitive.content)
            if (outcome == "ambiguous") assertFalse(response.containsKey("declarationCandidates"), "Unresolved duplicates must not advertise bound ranges")
        }
    }

    private lateinit var editorSavedConsumer: String
    private lateinit var editorSavedHelper: String
    private lateinit var editorDirtyConsumer: String
    private lateinit var editorDirtyHelper: String
    private lateinit var editorSourceText: String
    private lateinit var editorSelectedProof: String
    private lateinit var editorNavigation: JsonObject
    private lateinit var editorChange: String
    private val editorHelperPath = "src/main/java/fixture/Selector.java"
    private val editorConsumerPath = "src/main/java/fixture/App.java"

    @Given("Java editor drafts redirect a saved binary call through another document")
    fun editorDependencyFixture() {
        dependencySourceFixture("duplicate")
        requireNotNull(session).close()
        val owned = requireNotNull(container)
        listOf("library" to "FirstApi", "duplicate" to "SecondApi").forEach { (artifact, type) ->
            val source = "package external; public class $type { public int call(int value) { return value; } }\n"
            val input = owned.resolve("editor-producer/$type/$type.java"); write(input, source)
            val classes = owned.resolve("editor-producer/$type/classes"); Files.createDirectories(classes)
            assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "17", "-d", classes.toString(), input.toString()))
            val directory = owned.resolve("repository/external/$artifact/1")
            JarOutputStream(Files.newOutputStream(directory.resolve("$artifact-1.jar"))).use { jar -> Files.walk(classes).use { paths ->
                paths.filter(Files::isRegularFile).forEach { file -> jar.putNextEntry(java.util.jar.JarEntry(classes.relativize(file).toString().replace('\\', '/')))
                    jar.write(Files.readAllBytes(file)); jar.closeEntry() }
            } }
            sourceArchive(directory.resolve("$artifact-1-sources.jar"), "$type.java", source)
            if (type == "SecondApi") { editorSourceText = source; dependencyAttachment = directory.resolve("$artifact-1-sources.jar") }
        }
        editorSavedConsumer = "package fixture; public class App { int value = Selector.selected().call(7); } // saved\n"
        editorSavedHelper = "package fixture; public class Selector { public static external.FirstApi selected() { return new external.FirstApi(); } }\n"
        editorDirtyConsumer = editorSavedConsumer.replace("// saved", "// selected draft")
        editorDirtyHelper = editorSavedHelper.replace("FirstApi", "SecondApi")
        write(root.resolve(editorConsumerPath), editorSavedConsumer); write(root.resolve(editorHelperPath), editorSavedHelper)
        val active = DaemonSession(); session = active
        previousHash = active.dispatch("project.open", buildJsonObject { put("root", root.toString()); put("mavenRepository", owned.resolve("repository").toString()) })
            .jsonObject.getValue("snapshotHash").jsonPrimitive.content
        val context = active.dispatch("project.context", buildJsonObject { put("expectedSnapshotHash", previousHash); put("sdkHome", System.getProperty("java.home")) }).jsonObject
        assertEquals("available", context.getValue("status").jsonPrimitive.content)
        sourceQuery = buildJsonObject { put("expectedSnapshotHash", previousHash); put("expectedContextHash", context.getValue("contextHash")); put("sdkHome", System.getProperty("java.home"))
            put("path", editorConsumerPath); put("position", buildJsonObject { put("line", 0); put("character", editorDirtyConsumer.indexOf("call(7)") + 1) }) }
        val saved = active.dispatch("java.dependencySource.declaration", sourceQuery).jsonObject
        assertEquals("external.FirstApi", saved.getValue("association").jsonObject.getValue("jvmIdentity").jsonObject.getValue("ownerBinaryName").jsonPrimitive.content)
        editorSelectedProof = editorProof(editorDocuments()).getValue("sourceAuthority").jsonObject.getValue("overlayHash").jsonPrimitive.content
        baseline = contents(owned)
    }

    private fun editorDocuments(helperVersion: Long = 44, helper: String = editorDirtyHelper, omit: Boolean = false) = buildJsonObject {
        put("kind", "immutable-editor-overlay"); put("documents", buildJsonArray {
            add(buildJsonObject { put("path", editorConsumerPath); put("version", 44); put("content", editorDirtyConsumer) })
            if (!omit) add(buildJsonObject { put("path", editorHelperPath); put("version", helperVersion); put("content", helper) })
        })
    }

    private fun editorProof(documents: JsonObject) = requireNotNull(session).dispatch("intelligence.query", buildJsonObject {
        put("requestId", "editor-dependency-proof"); put("kind", "definition"); put("languageId", "java")
        put("expectedSnapshotHash", previousHash); put("javaContext", buildJsonObject { put("sdkHome", System.getProperty("java.home")); put("expectedContextHash", sourceQuery.getValue("expectedContextHash")) })
        put("path", editorConsumerPath); put("position", sourceQuery.getValue("position")); put("sourceAuthority", documents)
    }).jsonObject.also { assertEquals("ready", it.getValue("status").jsonPrimitive.content, it.toString()) }

    @When("dependency source navigation consumes the selected overlay with {string} authority")
    fun editorDependencyRead(change: String) {
        editorChange = change
        val active = requireNotNull(session)
        val initial = JsonObject(sourceQuery + mapOf("sourceAuthority" to editorDocuments(), "expectedOverlayHash" to kotlinx.serialization.json.JsonPrimitive(editorSelectedProof)))
        editorNavigation = active.dispatch("java.dependencySource.editor.declaration", initial).jsonObject
        assertEquals("available", editorNavigation.getValue("status").jsonPrimitive.content, editorNavigation.toString())
        val associationHash = editorNavigation.getValue("association").jsonObject.getValue("associationHash")
        var proof = editorSelectedProof
        val documents = when (change) {
            "other document changed" -> editorDocuments(45, editorSavedHelper)
            "version only changed" -> editorDocuments(45).also { proof = editorProof(it).getValue("sourceAuthority").jsonObject.getValue("overlayHash").jsonPrimitive.content }
            "same version changed" -> editorDocuments(44, editorDirtyHelper + "// changed without new version\n")
            "document omitted" -> editorDocuments(omit = true)
            else -> editorDocuments()
        }
        if (change == "archive changed") { sourceArchive(dependencyAttachment, "SecondApi.java", editorSourceText + "// changed source attachment\n"); baseline = contents(requireNotNull(container)) }
        response = active.dispatch("java.dependencySource.editor.read", JsonObject(sourceQuery + mapOf(
            "sourceAuthority" to documents, "expectedOverlayHash" to kotlinx.serialization.json.JsonPrimitive(proof), "expectedAssociationHash" to associationHash))).jsonObject
    }

    @Then("editor dependency navigation reports {string} with the original saved bytes intact")
    fun editorDependencyOutcome(outcome: String) {
        assertEquals("java-editor-dependency-source-v1", response.getValue("schemaVersion").jsonPrimitive.content)
        if (outcome == "second binary") {
            assertEquals("available", response.getValue("status").jsonPrimitive.content, response.toString())
            assertEquals(editorSourceText, response.getValue("content").jsonPrimitive.content)
            assertEquals(previousHash, response.getValue("snapshotHash").jsonPrimitive.content)
            val association = response.getValue("association").jsonObject
            assertEquals("external.SecondApi", association.getValue("jvmIdentity").jsonObject.getValue("ownerBinaryName").jsonPrimitive.content)
            assertEquals("external/SecondApi.java", association.getValue("sourceEntry").jsonPrimitive.content)
            val source = response.getValue("sourceAuthority").jsonObject
            assertEquals(editorSelectedProof, source.getValue("overlayHash").jsonPrimitive.content)
            assertEquals(2, source.getValue("documents").jsonArray.size)
            val declaration = editorNavigation.getValue("declaration").jsonObject.getValue("nameRange").jsonObject
            val start = declaration.getValue("start").jsonObject.getValue("character").jsonPrimitive.content.toInt()
            val end = declaration.getValue("end").jsonObject.getValue("character").jsonPrimitive.content.toInt()
            assertEquals("call", editorSourceText.substring(start, end))
            val wrongMode = JsonObject(editorDocuments() + ("kind" to kotlinx.serialization.json.JsonPrimitive("saved-snapshot")))
            val invalid = assertFailsWith<JsonRpcException> { requireNotNull(session).dispatch("java.dependencySource.editor.lookup", JsonObject(sourceQuery + mapOf(
                "sourceAuthority" to wrongMode, "expectedOverlayHash" to kotlinx.serialization.json.JsonPrimitive(editorSelectedProof)))) }
            assertEquals(org.refactorkit.core.JsonRpcErrorCodes.INVALID_PARAMS, invalid.code)
        } else {
            assertEquals("refused", response.getValue("status").jsonPrimitive.content, response.toString())
            val code = when (outcome) { "overlay stale" -> "java.overlay.hashStale"; "version stale" -> "java.overlay.versionStale"; else -> "java.sources.associationStale" }
            assertEquals(code, response.getValue("reason").jsonObject.getValue("code").jsonPrimitive.content)
        }
        assertEquals(editorSavedConsumer, Files.readString(root.resolve(editorConsumerPath)))
        assertEquals(editorSavedHelper, Files.readString(root.resolve(editorHelperPath)))
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
    }

    private lateinit var platformSelection: String
    private lateinit var platformSdk: Path
    private lateinit var platformQuery: JsonObject

    @Given("a Java platform cursor with {string} source authority")
    fun platformSourceFixture(selection: String) {
        platformSelection = selection
        val owned = Files.createTempDirectory("rk-java-platform-sources-"); container = owned
        root = owned.resolve("project")
        platformSdk = if (selection == "missing SDK sources") {
            val runtimeSdk = Path.of(System.getProperty("java.home")).toRealPath()
            val ownedSdk = Files.createTempDirectory("rk-owned-sdk-without-sources-")
            platformOwnedSdkRoot = ownedSdk
            Files.createDirectories(ownedSdk.resolve("lib"))
            listOf("modules", "ct.sym", "jrt-fs.jar").forEach {
                Files.createSymbolicLink(ownedSdk.resolve("lib/$it"), runtimeSdk.resolve("lib/$it"))
            }
            Files.copy(runtimeSdk.resolve("release"), ownedSdk.resolve("release"))
            assertFalse(Files.exists(ownedSdk.resolve("lib/src.zip")))
            ownedSdk
        }
            else Path.of("/home/paperboy/.local/share/graalvm/graalvm-jdk-25-latest")
        val release = when (selection) { "missing SDK sources" -> 21; "historical release" -> 17; else -> 25 }
        write(root.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>platform</artifactId><version>1</version><properties><maven.compiler.release>$release</maven.compiler.release></properties></project>\n")
        val consumer = "package fixture; class App { String value = new String(\"draft\"); int count = value.charAt(0); int limit = Integer.MAX_VALUE; }\n"
        write(root.resolve("src/main/java/fixture/App.java"), consumer)
        val active = DaemonSession(); session = active
        previousHash = active.dispatch("project.open", buildJsonObject { put("root", root.toString()) }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        val context = active.dispatch("project.context", buildJsonObject { put("expectedSnapshotHash", previousHash); put("sdkHome", platformSdk.toString()) }).jsonObject
        assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
        val token = when (selection) { "current type" -> "String value"; "current constructor" -> "String(\"draft\")"; "current field" -> "MAX_VALUE"; else -> "charAt(0)" }
        platformQuery = buildJsonObject {
            put("expectedSnapshotHash", previousHash); put("expectedContextHash", context.getValue("contextHash")); put("sdkHome", platformSdk.toString())
            put("expectedPlatformIdentitySha256", context.getValue("sdk").jsonArray.single().jsonObject.getValue("identitySha256"))
            put("path", "src/main/java/fixture/App.java"); put("position", buildJsonObject { put("line", 0); put("character", consumer.indexOf(token) + 1) })
            put("sourceAuthority", buildJsonObject { put("kind", "saved-snapshot") })
        }
        baseline = contents(owned)
    }

    @When("the selected SDK platform is queried for a guarded declaration")
    fun platformDeclarationQuery() {
        response = requireNotNull(session).dispatch("java.platformSource.declaration", platformQuery).jsonObject
    }

    @Then("platform source navigation reports {string} without saving project files")
    fun platformDeclarationOutcome(outcome: String) {
        assertEquals("java-platform-source-declaration-v1", response.getValue("schemaVersion").jsonPrimitive.content)
        if (outcome.endsWith("unavailable")) {
            assertEquals("unavailable", response.getValue("status").jsonPrimitive.content, response.toString())
            val code = if (outcome == "sources unavailable") "java.platformSources.sourceArchiveUnavailable" else "java.platformSources.releaseSourceUnqualified"
            assertEquals(code, response.getValue("reason").jsonObject.getValue("code").jsonPrimitive.content)
            assertFalse(response.containsKey("declaration"))
        } else {
            assertEquals("available", response.getValue("status").jsonPrimitive.content, response.toString())
            val association = response.getValue("association").jsonObject
            assertEquals("java.base", association.getValue("platformModule").jsonPrimitive.content)
            assertEquals("CURRENT_JRT_IMAGE", association.getValue("binaryOrigin").jsonObject.getValue("kind").jsonPrimitive.content)
            assertEquals(platformQuery.getValue("expectedPlatformIdentitySha256"), association.getValue("platformIdentitySha256"))
            assertEquals(true, association.getValue("readOnly").jsonPrimitive.content.toBoolean())
            assertTrue(association.getValue("resourceUri").jsonPrimitive.content.startsWith("refactorkit-java-platform-source://"))
            val lookup = requireNotNull(session).dispatch("java.platformSource.lookup", platformQuery).jsonObject
            assertEquals(association, lookup.getValue("association"))
            val read = requireNotNull(session).dispatch("java.platformSource.read", JsonObject(platformQuery +
                ("expectedAssociationHash" to association.getValue("associationHash")))).jsonObject
            assertEquals("available", read.getValue("status").jsonPrimitive.content, read.toString())
            val text = read.getValue("content").jsonPrimitive.content
            val rg = response.getValue("declaration").jsonObject.getValue("nameRange").jsonObject
            fun offset(key: String): Int { val p=rg.getValue(key).jsonObject; return text.split('\n').take(p.getValue("line").jsonPrimitive.content.toInt()).sumOf { it.length + 1 } + p.getValue("character").jsonPrimitive.content.toInt() }
            val expected = when (platformSelection) { "current method" -> "charAt"; "current field" -> "MAX_VALUE"; else -> "String" }
            assertEquals(expected, text.substring(offset("start"), offset("end")))
            if (expected != "MAX_VALUE") assertTrue(text.toByteArray(Charsets.UTF_8).size > 128 * 1024, "String requires the dedicated larger source budget")
            assertEquals(previousHash, read.getValue("snapshotHash").jsonPrimitive.content)
        }
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
    }

    @Given("Java editor drafts redirect the selected SDK call to StringBuffer")
    fun platformEditorFixture() {
        platformSourceFixture("current method")
        requireNotNull(session).close()
        editorSavedConsumer = "package fixture; class App { char value = Selector.selected().charAt(0); } // saved\n"
        editorDirtyConsumer = editorSavedConsumer.replace("// saved", "// selected draft")
        editorSavedHelper = "package fixture; class Selector { static String selected() { return new String(\"saved\"); } }\n"
        editorDirtyHelper = editorSavedHelper.replace("String", "StringBuffer")
        write(root.resolve(editorConsumerPath), editorSavedConsumer); write(root.resolve(editorHelperPath), editorSavedHelper)
        val active = DaemonSession(); session = active
        previousHash = active.dispatch("project.open", buildJsonObject { put("root", root.toString()) }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        val context = active.dispatch("project.context", buildJsonObject { put("expectedSnapshotHash", previousHash); put("sdkHome", platformSdk.toString()) }).jsonObject
        assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
        platformQuery = buildJsonObject {
            put("expectedSnapshotHash", previousHash); put("expectedContextHash", context.getValue("contextHash")); put("sdkHome", platformSdk.toString())
            put("expectedPlatformIdentitySha256", context.getValue("sdk").jsonArray.single().jsonObject.getValue("identitySha256"))
            put("path", editorConsumerPath); put("position", buildJsonObject { put("line", 0); put("character", editorDirtyConsumer.indexOf("charAt(0)") + 1) })
            put("sourceAuthority", buildJsonObject { put("kind", "saved-snapshot") })
        }
        val saved = active.dispatch("java.platformSource.declaration", platformQuery).jsonObject
        assertEquals("java.lang.String", saved.getValue("association").jsonObject.getValue("jvmIdentity").jsonObject.getValue("ownerBinaryName").jsonPrimitive.content)
        editorSelectedProof = platformEditorProof(editorDocuments())
        baseline = contents(requireNotNull(container))
    }

    private fun platformEditorProof(documents: JsonObject): String = requireNotNull(session).dispatch("intelligence.query", buildJsonObject {
        put("requestId", "platform-editor-proof"); put("kind", "definition"); put("languageId", "java")
        put("expectedSnapshotHash", previousHash); put("path", editorConsumerPath); put("position", platformQuery.getValue("position"))
        put("javaContext", buildJsonObject { put("sdkHome", platformSdk.toString()); put("expectedContextHash", platformQuery.getValue("expectedContextHash")) })
        put("sourceAuthority", documents)
    }).jsonObject.also { assertEquals("ready", it.getValue("status").jsonPrimitive.content, it.toString()) }
        .getValue("sourceAuthority").jsonObject.getValue("overlayHash").jsonPrimitive.content

    @When("a platform source read consumes the selected frame with {string} authority")
    fun platformEditorRead(change: String) {
        val active = requireNotNull(session)
        val initial = JsonObject(platformQuery + mapOf("sourceAuthority" to editorDocuments(), "expectedOverlayHash" to kotlinx.serialization.json.JsonPrimitive(editorSelectedProof)))
        editorNavigation = active.dispatch("java.platformSource.declaration", initial).jsonObject
        assertEquals("available", editorNavigation.getValue("status").jsonPrimitive.content, editorNavigation.toString())
        assertEquals("java.lang.StringBuffer", editorNavigation.getValue("association").jsonObject.getValue("jvmIdentity").jsonObject.getValue("ownerBinaryName").jsonPrimitive.content)
        var proof = editorSelectedProof
        val docs = when (change) {
            "other document changed" -> editorDocuments(45, editorSavedHelper)
            "version only changed" -> editorDocuments(45).also { proof = platformEditorProof(it) }
            "same version changed" -> editorDocuments(44, editorDirtyHelper + "// changed\n")
            "document omitted" -> editorDocuments(omit = true)
            else -> editorDocuments()
        }
        val query = if (change == "saved fallback") platformQuery else JsonObject(platformQuery + mapOf(
            "sourceAuthority" to docs, "expectedOverlayHash" to kotlinx.serialization.json.JsonPrimitive(proof)))
        response = active.dispatch("java.platformSource.read", JsonObject(query +
            ("expectedAssociationHash" to editorNavigation.getValue("association").jsonObject.getValue("associationHash")))).jsonObject
    }

    @Then("platform editor navigation reports {string} with both saved sources intact")
    fun platformEditorOutcome(outcome: String) {
        if (outcome == "StringBuffer source") {
            assertEquals("available", response.getValue("status").jsonPrimitive.content, response.toString())
            assertEquals("java.base/java/lang/StringBuffer.java", response.getValue("association").jsonObject.getValue("sourceEntry").jsonPrimitive.content)
            assertEquals(editorSelectedProof, response.getValue("sourceAuthority").jsonObject.getValue("overlayHash").jsonPrimitive.content)
            assertEquals(2, response.getValue("sourceAuthority").jsonObject.getValue("documents").jsonArray.size)
            assertEquals(previousHash, response.getValue("snapshotHash").jsonPrimitive.content)
            assertTrue(response.getValue("content").jsonPrimitive.content.contains("public final class StringBuffer"))
        } else {
            assertEquals("refused", response.getValue("status").jsonPrimitive.content, response.toString())
            val code = when (outcome) { "overlay stale" -> "java.overlay.hashStale"; "version stale" -> "java.overlay.versionStale"; else -> "java.platformSources.associationStale" }
            assertEquals(code, response.getValue("reason").jsonObject.getValue("code").jsonPrimitive.content)
        }
        assertEquals(editorSavedConsumer, Files.readString(root.resolve(editorConsumerPath)))
        assertEquals(editorSavedHelper, Files.readString(root.resolve(editorHelperPath)))
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
    }

    private var platformOwnedSdkRoot: Path? = null
    private lateinit var platformIntegrityCase: String
    private lateinit var platformOriginalArchive: ByteArray
    private lateinit var platformOriginalMetadata: ByteArray
    private lateinit var platformOwnedSource: String
    private lateinit var platformIntegrityAssociation: JsonObject

    @Given("an owned SDK platform source association with {string} evidence")
    fun platformIntegrityFixture(evidence: String) {
        platformSourceFixture("current method"); requireNotNull(session).close()
        val originalSdk = platformSdk
        val ownedSdk = Files.createTempDirectory("rk-owned-sdk-source-"); platformOwnedSdkRoot = ownedSdk; platformSdk = ownedSdk
        Files.createDirectories(ownedSdk.resolve("lib"))
        listOf("modules", "ct.sym", "jrt-fs.jar").forEach { Files.createSymbolicLink(ownedSdk.resolve("lib/$it"), originalSdk.resolve("lib/$it")) }
        Files.copy(originalSdk.resolve("release"), ownedSdk.resolve("release"))
        java.util.zip.ZipFile(originalSdk.resolve("lib/src.zip").toFile()).use { zip ->
            platformOwnedSource = zip.getInputStream(zip.getEntry("java.base/java/lang/String.java")).use { it.readAllBytes().toString(Charsets.UTF_8) }
        }
        writePlatformOwnedArchive(platformOwnedSource)
        platformOriginalArchive = Files.readAllBytes(ownedSdk.resolve("lib/src.zip")); platformOriginalMetadata = Files.readAllBytes(ownedSdk.resolve("release"))
        val active = DaemonSession(); session = active
        previousHash = active.dispatch("project.open", buildJsonObject { put("root", root.toString()) }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        val context = active.dispatch("project.context", buildJsonObject { put("expectedSnapshotHash", previousHash); put("sdkHome", platformSdk.toString()) }).jsonObject
        assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
        platformQuery = JsonObject(platformQuery + mapOf("sdkHome" to kotlinx.serialization.json.JsonPrimitive(platformSdk.toString()),
            "expectedContextHash" to context.getValue("contextHash"), "expectedPlatformIdentitySha256" to context.getValue("sdk").jsonArray.single().jsonObject.getValue("identitySha256")))
        val selected = active.dispatch("java.platformSource.declaration", platformQuery).jsonObject
        assertEquals("available", selected.getValue("status").jsonPrimitive.content, selected.toString())
        platformIntegrityAssociation = selected.getValue("association").jsonObject; platformIntegrityCase = evidence
        baseline = contents(requireNotNull(container))
    }

    private fun writePlatformOwnedArchive(text: String) {
        JarOutputStream(Files.newOutputStream(platformSdk.resolve("lib/src.zip"))).use { zip ->
            zip.putNextEntry(java.util.jar.JarEntry("java.base/java/lang/String.java")); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
        }
    }

    @When("the SDK source bytes or platform metadata change after association")
    fun platformIntegrityRead() {
        val source = platformSdk.resolve("lib/src.zip"); val metadata = platformSdk.resolve("release")
        when (platformIntegrityCase) {
            "changed archive" -> writePlatformOwnedArchive(platformOwnedSource + "\n// changed owned source\n")
            "removed archive" -> Files.delete(source)
            "changed SDK metadata" -> Files.writeString(metadata, platformOriginalMetadata.toString(Charsets.UTF_8) + "\n# changed owned SDK metadata\n")
            "central checksum mismatch", "central size mismatch" -> {
                val bytes = platformOriginalArchive.copyOf()
                val central = (0..bytes.size - 4).first { bytes[it] == 0x50.toByte() && bytes[it + 1] == 0x4b.toByte() && bytes[it + 2] == 1.toByte() && bytes[it + 3] == 2.toByte() }
                val field = central + if (platformIntegrityCase == "central checksum mismatch") 16 else 20
                bytes[field] = (bytes[field].toInt() xor 1).toByte(); Files.write(source, bytes)
            }
        }
        try {
            response = if (platformIntegrityCase.startsWith("central")) requireNotNull(session).dispatch("java.platformSource.declaration", platformQuery).jsonObject
            else requireNotNull(session).dispatch("java.platformSource.read", JsonObject(platformQuery +
                ("expectedAssociationHash" to platformIntegrityAssociation.getValue("associationHash")))).jsonObject
        } finally { Files.write(source, platformOriginalArchive); Files.write(metadata, platformOriginalMetadata) }
    }

    @Then("platform source integrity reports {string} without a guessed declaration")
    fun platformIntegrityOutcome(outcome: String) {
        assertEquals(if (outcome == "available") "available" else "refused", response.getValue("status").jsonPrimitive.content, response.toString())
        if (outcome == "available") assertEquals(platformOwnedSource, response.getValue("content").jsonPrimitive.content)
        else {
            val code = when (outcome) { "association stale" -> "java.platformSources.associationStale"; "context stale" -> "java.context.contextStale"; else -> "java.platformSources.archiveUnsafe" }
            assertEquals(code, response.getValue("reason").jsonObject.getValue("code").jsonPrimitive.content)
            assertFalse(response.containsKey("declaration")); assertFalse(response.containsKey("content"))
        }
        assertTrue(Files.readAllBytes(platformSdk.resolve("lib/src.zip")).contentEquals(platformOriginalArchive))
        assertTrue(Files.readAllBytes(platformSdk.resolve("release")).contentEquals(platformOriginalMetadata))
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
    }

    @Given("SDK source metadata contains {string} owning source evidence")
    fun platformMetadataFixture(source: String) { platformIntegrityFixture("current"); platformIntegrityCase = source }

    @When("fresh platform metadata is requested without an older association guard")
    fun platformMetadataLookup() {
        when (platformIntegrityCase) {
            "wrong package" -> writePlatformOwnedArchive(platformOwnedSource.replaceFirst("package java.lang;", "package copied;"))
            "wrong owner" -> writePlatformOwnedArchive(platformOwnedSource.replaceFirst("public final class String", "public final class Other"))
            "invalid grammar" -> writePlatformOwnedArchive(platformOwnedSource.substringBeforeLast('}'))
            "missing entry" -> JarOutputStream(Files.newOutputStream(platformSdk.resolve("lib/src.zip"))).use { zip ->
                zip.putNextEntry(java.util.jar.JarEntry("java.base/java/lang/Other.java")); zip.write(platformOwnedSource.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            "oversized source" -> writePlatformOwnedArchive(platformOwnedSource + "\n/*" + "x".repeat(512 * 1024) + "*/\n")
        }
        try { response = requireNotNull(session).dispatch("java.platformSource.lookup", platformQuery).jsonObject }
        finally { Files.write(platformSdk.resolve("lib/src.zip"), platformOriginalArchive) }
    }

    @Then("SDK metadata association reports {string} without unrelated source content")
    fun platformMetadataOutcome(outcome: String) {
        val status = when (outcome) { "available" -> "available"; "source budget exceeded" -> "refused"; else -> "unavailable" }
        assertEquals(status, response.getValue("status").jsonPrimitive.content, response.toString())
        if (outcome != "available") {
            val code = if (outcome == "source budget exceeded") "java.platformSources.limitExceeded" else "java.platformSources.sourceIdentityUnavailable"
            assertEquals(code, response.getValue("reason").jsonObject.getValue("code").jsonPrimitive.content)
            assertFalse(response.containsKey("association")); assertFalse(response.containsKey("content"))
        }
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
    }

    private lateinit var platformExpectedTypeName: String
    @Given("a current SDK cursor selects a {string} platform declaration")
    fun platformKindFixture(kind: String) {
        platformSourceFixture("current method"); requireNotNull(session).close()
        val pair = when (kind) {
            "interface" -> "package fixture; class App { java.util.List<String> value; }\n" to "List"
            "nested interface" -> "package fixture; class App { java.util.Map.Entry<?,?> value; }\n" to "Entry"
            "annotation" -> "package fixture; @Deprecated class App {}\n" to "Deprecated"
            "enum" -> "package fixture; class App { java.time.DayOfWeek value; }\n" to "DayOfWeek"
            "class" -> "package fixture; class App { String value; }\n" to "String"
            else -> "package fixture; class App { java.util.List<String> value = java.util.List.of(\"x\"); }\n" to "of(\"x\")"
        }
        platformExpectedTypeName = if (kind == "interface static method") "of" else pair.second
        write(root.resolve("src/main/java/fixture/App.java"), pair.first)
        val active = DaemonSession(); session = active
        previousHash = active.dispatch("project.open", buildJsonObject { put("root", root.toString()) }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        val context = active.dispatch("project.context", buildJsonObject { put("expectedSnapshotHash", previousHash); put("sdkHome", platformSdk.toString()) }).jsonObject
        assertEquals("available", context.getValue("status").jsonPrimitive.content)
        platformQuery = JsonObject(platformQuery + mapOf("expectedSnapshotHash" to kotlinx.serialization.json.JsonPrimitive(previousHash),
            "expectedContextHash" to context.getValue("contextHash"), "expectedPlatformIdentitySha256" to context.getValue("sdk").jsonArray.single().jsonObject.getValue("identitySha256"),
            "position" to buildJsonObject { put("line", 0); put("character", pair.first.indexOf(pair.second) + 1) }))
        baseline = contents(requireNotNull(container))
    }

    @When("exact SDK type kind and classfile identity are required")
    fun platformKindQuery() { response = requireNotNull(session).dispatch("java.platformSource.declaration", platformQuery).jsonObject }

    @Then("SDK declaration kind {string} has an exact guarded source range")
    fun platformKindOutcome(kind: String) {
        assertEquals("available", response.getValue("status").jsonPrimitive.content, "$kind: $response")
        val association = response.getValue("association").jsonObject
        val read = requireNotNull(session).dispatch("java.platformSource.read", JsonObject(platformQuery +
            ("expectedAssociationHash" to association.getValue("associationHash")))).jsonObject
        assertEquals("available", read.getValue("status").jsonPrimitive.content)
        val text = read.getValue("content").jsonPrimitive.content; val range=response.getValue("declaration").jsonObject.getValue("nameRange").jsonObject
        fun offset(key: String): Int { val p=range.getValue(key).jsonObject; return text.split('\n').take(p.getValue("line").jsonPrimitive.content.toInt()).sumOf { it.length + 1 } + p.getValue("character").jsonPrimitive.content.toInt() }
        assertEquals(platformExpectedTypeName, text.substring(offset("start"), offset("end")))
        assertEquals(if (kind == "interface static method") "CALLABLE" else "TYPE", association.getValue("jvmIdentity").jsonObject.getValue("kind").jsonPrimitive.content)
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
    }

    private fun sourceArchive(path: Path, filename: String, source: String) {
        JarOutputStream(Files.newOutputStream(path)).use { jar ->
            jar.putNextEntry(java.util.jar.JarEntry("external/$filename")); jar.write(source.toByteArray(Charsets.UTF_8)); jar.closeEntry()
        }
    }

    private fun writeTestJvmPluginDescriptor(repository: Path, artifactId: String, version: String, goal: String) {
        val jarPath = repository.resolve("org/apache/maven/plugins/$artifactId/$version/$artifactId-$version.jar")
        Files.createDirectories(jarPath.parent)
        val descriptor = """
            <plugin><name>fixture</name><groupId>org.apache.maven.plugins</groupId>
              <artifactId>$artifactId</artifactId><version>$version</version><mojos><mojo><goal>$goal</goal>
                <configuration>
                  <forkCount implementation="java.lang.String" default-value="1">${'$'}{forkCount}</forkCount>
                  <reuseForks implementation="java.lang.Boolean" default-value="true">${'$'}{reuseForks}</reuseForks>
                  <argLine implementation="java.lang.String">${'$'}{argLine}</argLine>
                  <jvm implementation="java.lang.String">${'$'}{jvm}</jvm>
                </configuration>
              </mojo></mojos></plugin>
        """.trimIndent()
        JarOutputStream(Files.newOutputStream(jarPath)).use { jar ->
            jar.putNextEntry(JarEntry("META-INF/maven/plugin.xml"))
            jar.write(descriptor.toByteArray(Charsets.UTF_8))
            jar.closeEntry()
        }
    }

    @After
    fun close() {
        session?.close()
        platformOwnedSdkRoot?.let { owned -> Files.walk(owned).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
        reactorSession?.close()
        container?.let { owned -> Files.walk(owned).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }

    private fun paths(sourceSet: JsonObject, key: String): List<String> = sourceSet.getValue(key).jsonArray.map {
        it.jsonObject.getValue("path").jsonPrimitive.content
    }

    private fun write(path: Path, content: String) {
        Files.createDirectories(path.parent); Files.writeString(path, content)
    }

    private fun copyFixture(source: Path, destination: Path) {
        Files.walk(source).use { paths ->
            paths.filter(Files::isRegularFile).forEach { file ->
                val target = destination.resolve(source.relativize(file).toString())
                Files.createDirectories(target.parent)
                Files.copy(file, target)
            }
        }
    }

    private fun createRuntimeReactor(workspace: Path): String {
        val modules = listOf("library", "runtime", "testsupport", "unrelated", "app")
        write(workspace.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>fixture.reactor</groupId>" +
            "<artifactId>root</artifactId><version>1</version><packaging>pom</packaging>" +
            "<properties><maven.compiler.release>8</maven.compiler.release></properties><modules>" +
            modules.joinToString("") { "<module>$it</module>" } + "</modules></project>\n")
        for (module in modules) {
            val dependencies = if (module == "app") "<dependencies>" +
                listOf("library" to "compile", "runtime" to "runtime", "testsupport" to "test").joinToString("") { (name, scope) ->
                    "<dependency><groupId>fixture.reactor</groupId><artifactId>$name</artifactId><version>1</version><scope>$scope</scope></dependency>"
                } + "</dependencies>" else ""
            write(workspace.resolve("$module/pom.xml"), "<project><modelVersion>4.0.0</modelVersion>" +
                "<parent><groupId>fixture.reactor</groupId><artifactId>root</artifactId><version>1</version>" +
                "<relativePath>../pom.xml</relativePath></parent><artifactId>$module</artifactId>$dependencies</project>\n")
            val source = if (module == "app") "package fixture; public class App { Library value; }\n" else
                "package fixture; public class ${module.replaceFirstChar(Char::uppercase)} {}\n"
            val name = if (module == "app") "App" else module.replaceFirstChar(Char::uppercase)
            write(workspace.resolve("$module/src/main/java/fixture/$name.java"), source)
            write(workspace.resolve("$module/target/classes/.materialized"), "$module output\n")
        }
        val active = DaemonSession(); reactorSession = active
        return active.dispatch("project.open", buildJsonObject { put("root", workspace.toString()) })
            .jsonObject.getValue("snapshotHash").jsonPrimitive.content
    }

    private fun contents(owned: Path): Map<Path, String> = Files.walk(owned).use { paths ->
        paths.filter(Files::isRegularFile).toList().associate { path ->
            owned.relativize(path) to MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
                .joinToString("") { "%02x".format(it) }
        }
    }

    private fun fileSha256(path: Path): String = MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }

    private fun projectInputContents(): Map<Path, String> = Files.walk(root).use { paths ->
        paths.filter(Files::isRegularFile).filter { path ->
            val relative = root.relativize(path)
            relative.none { it.toString() in setOf(".gradle", "build", "target") }
        }.toList().associate { path ->
            root.relativize(path) to MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
                .joinToString("") { "%02x".format(it) }
        }
    }

    private fun writeProviderContextResponse(name: String) {
        val repository = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        val evidence = repository.resolve(".refactorkit/runs/java-provider-qualified-context-r001/responses/$name.json")
        Files.createDirectories(evidence.parent)
        Files.writeString(evidence, response.toString() + "\n")
    }

    private fun writeFailsafeKindResponse(name: String) {
        val repository = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        val evidence = repository.resolve(
            ".refactorkit/runs/java-failsafe-source-set-kind-r002/responses/$name.json",
        )
        Files.createDirectories(evidence.parent)
        Files.writeString(evidence, response.toString() + "\n")
    }

    private fun writeFailsafeV4Response(value: JsonObject) {
        val repository = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        val app = value.getValue("modules").jsonArray.map { it.jsonObject }
            .single { it.getValue("providerId").jsonPrimitive.content == "maven-effective-v1" &&
                it.getValue("id").jsonPrimitive.content == "app" }
        val fact = sourceSets(app).values.single {
            it.getValue("kind").jsonPrimitive.content == mavenFailsafeRequestedKind
        }.getValue("mavenFailsafe").jsonObject
        val name = fact.getValue("availability").jsonPrimitive.content
        val evidence = repository.resolve(".refactorkit/runs/java-maven-failsafe-context-v4-r001/responses/$name.json")
        Files.createDirectories(evidence.parent)
        Files.writeString(evidence, value.toString() + "\n")
    }

    private fun writeTestJvmV7Response(value: JsonObject) {
        val repository = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        val name = mavenTestJvmShape.lowercase().replace(' ', '-')
        val evidence = repository.resolve(".refactorkit/runs/maven-test-jvm-context-v7-r001/responses/$name.json")
        Files.createDirectories(evidence.parent)
        Files.writeString(evidence, value.toString() + "\n")
    }

    private fun writeBuildHelperContextResponse(outcome: String, refusal: String) {
        val name = if (refusal == "none") "unique" else if (refusal.endsWith("snapshotStale")) "stale" else "ambiguous"
        writeBuildHelperResponse(
            ".refactorkit/runs/java-build-helper-module-list-r003/responses/$name.json",
            response,
        )
    }

    private fun writeBuildHelperV2ContextResponse(value: JsonObject) {
        writeBuildHelperResponse(
            ".refactorkit/runs/java-build-helper-module-list-r003/responses/v2-unique.json",
            value,
        )
    }

    private fun writeBuildHelperResponse(relativePath: String, value: JsonObject) {
        val repository = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        val evidence = repository.resolve(relativePath)
        Files.createDirectories(evidence.parent)
        Files.writeString(evidence, value.toString() + "\n")
    }
}
