package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.And
import io.cucumber.java.en.Given
import io.cucumber.java.en.When
import io.cucumber.java.en.Then
import kotlinx.serialization.json.*
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.TransactionLog
import org.junit.jupiter.api.Assumptions
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.*

class JavaGradleEffectiveContextSteps {
    private lateinit var root: Path
    private var session: GradleContextRpcSession? = null
    private lateinit var baseline: Map<String, String>
    private lateinit var result: JsonObject
    private lateinit var sdk: String
    private var gradleJava27ContextVersion = 6
    private var failure: JsonRpcException? = null
    private var mode = "main"
    private var compositeOutside: Path? = null
    private var compositeGradleHome: Path? = null
    private var compositeLayout: String? = null
    private var compositeResponse: JsonObject? = null
    private var compositeExpectedSnapshotHash: String? = null
    private var compositeBaseline: Map<String, String> = emptyMap()
    private var compositeDirectModuleId: String? = null
    private var compositeSynchronizedSnapshotHash: String? = null
    private var compositeSummary: JsonObject? = null
    private var compositeServerVersion: JsonObject? = null
    private var compositeSyncSucceeded = false
    private var kotlinDslExpectedSnapshotHash: String? = null
    private var kotlinDslSyncResponse: JsonObject? = null
    private var kotlinDslMainContext: JsonObject? = null
    private var kotlinDslTestContext: JsonObject? = null
    private var kotlinDslIntegrationContext: JsonObject? = null
    private var kotlinDslInputBaseline: Map<String, String> = emptyMap()
    private var gradleCompilerOptionsConfiguration: String? = null
    private var gradleCompilerOptionsContext: JsonObject? = null
    private var gradleCompilerOptionsRequiredContext: JsonObject? = null
    private lateinit var gradleJava26SnapshotHash: String
    private lateinit var gradleJava27SnapshotHash: String
    private var gradleJava27DefinitionQueryResponse: JsonObject? = null
    private var gradleJava27SemanticOperation: String? = null
    private var gradleJava27SemanticQueryResponse: JsonObject? = null
    private var gradleJava27SemanticPreviewFailure: JsonRpcException? = null
    private lateinit var gradleJpmsModulePathArtifact: Path
    private lateinit var gradleJpmsModulePathArtifactBytes: ByteArray
    private lateinit var gradleJpmsSnapshotHash: String
    private lateinit var gradleJpmsSynchronizedHash: String
    private var gradleExecutableOverride: Path? = null
    private lateinit var gradleAnnotationProcessorJar: Path
    private lateinit var gradleAnnotationProcessorJarBytes: ByteArray
    private val repeatSyncEvidenceDirectory = if (System.getProperty("refactorkit.gradle.composite.package.root") == null) {
        ".refactorkit/runs/gradle-composite-repeat-sync-r001"
    } else {
        ".refactorkit/runs/gradle-composite-repeat-sync-r002"
    }
    private var compositeAggregateEvidencePath = ".refactorkit/runs/ide-gradle-aggregate-context-r001/aggregate-context-response.json"

    @Given("a Gradle Java workspace with six modules and distinct dependency scopes")
    fun fixture() {
        root = Files.createTempDirectory("rk-gradle-effective-")
        write("settings.gradle", "rootProject.name='fixture'; include 'library','processor','runtime','testsupport','unrelated','app'\n")
        write("gradle.properties", "org.gradle.jvmargs=-Xmx256m\n")
        write("build.gradle", "subprojects { apply plugin: 'java-library'; tasks.withType(JavaCompile).configureEach { options.release=8 } }\n")
        write("app/build.gradle", "dependencies { implementation project(':library'); compileOnly project(':processor'); annotationProcessor project(':processor'); runtimeOnly project(':runtime'); testImplementation project(':testsupport') }\n")
        for (name in listOf("library", "processor", "runtime", "testsupport", "unrelated")) {
            write("$name/build.gradle", "// $name\n")
            write("$name/src/main/java/fixtures/$name/Value.java", "package fixtures.$name; public class Value {}\n")
        }
        write("app/src/main/java/pkg/Runner.java", "package pkg; public class Runner { fixtures.library.Value value; } class Peer {}\n")
        write("app/src/test/java/pkg/Runner.java", "package pkg; class Unrelated { fixtures.testsupport.Value value; }\n")
        write("app/build/generated/sources/annotationProcessor/java/main/generated/GeneratedValue.java", "package generated; public class GeneratedValue {}\n")
        session = newGradleContextRpcSession(); session!!.dispatch("project.open", buildJsonObject { put("root", root.toString()) })
        baseline = contents()
    }

    @When("Gradle is synchronized with {string} and Java {string} context is requested")
    fun synchronize(policy: String, selection: String) {
        val selectedSdk25 by lazy {
            requireNotNull(System.getenv("REFACTORKIT_ENTRYPOINT_SDK25_HOME")) {
                "REFACTORKIT_ENTRYPOINT_SDK25_HOME is required for Gradle SDK 25 qualification"
            }
        }
        val gradle = requireNotNull(System.getenv("REFACTORKIT_GRADLE_9_8_1_EXECUTABLE")) {
            "REFACTORKIT_GRADLE_9_8_1_EXECUTABLE is required for Gradle context qualification"
        }
        mode = selection
        sdk = if (policy == "explicit SDK25") selectedSdk25
            else Path.of(System.getProperty("java.home")).toRealPath().toString()
        val initial = session!!.dispatch("project.summary", null).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        try {
            val sync = session!!.dispatch("project.sync", buildJsonObject {
                put("buildSystem", "gradle"); put("expectedSnapshotHash", initial); put("sdkHome", sdk)
                put("gradleExecutable", gradle)
                put("allowBuildExecution", policy != "denied")
            }).jsonObject
            if (sync.getValue("status").jsonPrimitive.content == "refused") { result = sync; return }
            val hash = sync.getValue("snapshotHash").jsonPrimitive.content
            if (selection == "stale build") { write("build.gradle", Files.readString(root.resolve("build.gradle")) + "\n// build context changed\n"); baseline = contents() }
            result = session!!.dispatch("project.context", buildJsonObject {
                put("expectedSnapshotHash", hash); put("sdkHome", if (selection == "other SDK") selectedSdk25 else sdk)
                if (selection != "stale build" && selection != "other SDK") {
                    put("moduleId", ":app"); put("sourceSetId", if (selection == "test") "test" else "main")
                    put("sourceRelativePath", "pkg/Runner.java"); put("symbol", if (selection == "test") "pkg.Unrelated" else "pkg.Peer")
                }
            }).jsonObject
        } catch (refused: JsonRpcException) { failure = refused }
    }

    @Then("effective Gradle context reports {string} with exact compile and runtime visibility")
    fun outcome(expected: String) {
        assertNull(failure, failure?.message)
        if (expected != "available") {
            assertEquals("refused", result.getValue("status").jsonPrimitive.content)
            assertEquals(expected, (result["refusal"] ?: result.getValue("error")).jsonObject.getValue("code").jsonPrimitive.content)
            return
        }
        assertEquals(expected, result.getValue("status").jsonPrimitive.content, result.toString())
        assertEquals(6, result.getValue("modules").jsonArray.size)
        val provider = result.getValue("providers").jsonArray.single().jsonObject
        assertEquals("gradle-effective-v1", provider.getValue("providerId").jsonPrimitive.content)
        assertEquals("available", provider.getValue("status").jsonPrimitive.content)
        val app = result.getValue("modules").jsonArray.single { it.jsonObject.getValue("id").jsonPrimitive.content == ":app" }.jsonObject
        val main = app.getValue("sourceSets").jsonArray.single { it.jsonObject.getValue("id").jsonPrimitive.content == "main" }.jsonObject
        fun paths(set: JsonObject, key: String) = set.getValue(key).jsonArray.map { it.jsonObject.getValue("path").jsonPrimitive.content }
        assertTrue(paths(main, "compileClasspath").any { it.startsWith("library/") })
        assertTrue(paths(main, "compileClasspath").any { it.startsWith("processor/") })
        assertFalse(paths(main, "compileClasspath").any { it.startsWith("runtime/") || it.startsWith("testsupport/") || it.startsWith("unrelated/") })
        assertTrue(paths(main, "runtimeClasspath").any { it.startsWith("runtime/") })
        assertFalse(paths(main, "runtimeClasspath").any { it.startsWith("processor/") || it.startsWith("testsupport/") })
        assertTrue(main.getValue("generatedSourceRoots").jsonArray.any { it.jsonPrimitive.content.contains("annotationProcessor") })
        val selected = result.getValue("selectedSource").jsonObject
        assertEquals("app/src/${if (mode == "test") "test" else "main"}/java/pkg/Runner.java", selected.getValue("path").jsonPrimitive.content)
        assertEquals("JDT_BINDING", selected.getValue("evidence").jsonPrimitive.content)
        assertTrue(result.getValue("sdk").jsonArray.all { it.jsonObject.getValue("home").jsonPrimitive.content == Path.of(sdk).toRealPath().toString() })
    }

    @Then("the Gradle context preserves original Java and build input bytes")
    fun readOnly() { assertEquals(baseline, contents()) }

    @Given("a Gradle 8.11.1 Java project with a complete JPMS descriptor and a real module-path JAR")
    fun gradle8111JpmsModulePathFixture() {
        val configured = System.getenv("REFACTORKIT_GRADLE_8_11_1_EXECUTABLE")?.let(Path::of)
        val start = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val repositoryRoot = generateSequence(start) { it.parent }
            .firstOrNull { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        val executable = configured ?: repositoryRoot?.resolve(".tools/gradle-8.11.1/bin/gradle")
        Assumptions.assumeTrue(executable != null && Files.isExecutable(executable),
            "Set REFACTORKIT_GRADLE_8_11_1_EXECUTABLE or provide .tools/gradle-8.11.1/bin/gradle")
        val selected = requireNotNull(executable).toAbsolutePath().normalize()
        gradleJpmsModulePathFixture(release = 17)
        gradleExecutableOverride = selected
        val versionProcess = ProcessBuilder(selected.toString(), "--version").redirectErrorStream(true).apply {
            environment()["JAVA_HOME"] = sdk
            environment()["GRADLE_USER_HOME"] = requireNotNull(compositeGradleHome).toString()
        }.start()
        val versionOutput = versionProcess.inputStream.bufferedReader().use { it.readText() }
        assertTrue(versionProcess.waitFor(30, TimeUnit.SECONDS), "Gradle 8.11.1 version check must finish")
        assertEquals(0, versionProcess.exitValue(), versionOutput)
        assertTrue(Regex("(?m)^Gradle 8\\.11\\.1$").containsMatchIn(versionOutput), versionOutput)
    }

    @Given("a Gradle 8.11.1 Java project with inferred module path and a declared modular dependency")
    fun gradle8111JpmsInferredModulePathFixture() {
        gradle8111JpmsMavenDependencyFixture(disableInference = false)
    }

    @Given("a Gradle 8.11.1 Java project with module path inference disabled and a declared modular dependency")
    fun gradle8111JpmsInferenceDisabledFixture() {
        gradle8111JpmsMavenDependencyFixture(disableInference = true)
    }

    private fun gradle8111JpmsMavenDependencyFixture(disableInference: Boolean) {
        val configured = System.getenv("REFACTORKIT_GRADLE_8_11_1_EXECUTABLE")?.let(Path::of)
        val start = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val repositoryRoot = generateSequence(start) { it.parent }
            .firstOrNull { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        val executable = configured ?: repositoryRoot?.resolve(".tools/gradle-8.11.1/bin/gradle")
        Assumptions.assumeTrue(executable != null && Files.isExecutable(executable),
            "Set REFACTORKIT_GRADLE_8_11_1_EXECUTABLE or provide .tools/gradle-8.11.1/bin/gradle")
        gradleExecutableOverride = requireNotNull(executable).toAbsolutePath().normalize()
        root = Files.createTempDirectory("rk-gradle-8111-jpms-inferred-")
        sdk = Path.of(System.getProperty("java.home")).toRealPath().toString()
        compositeGradleHome = root.resolveSibling("${root.fileName}-gradle-home")
        write("settings.gradle", "rootProject.name='gradle-jpms-inferred'\n")
        write("build.gradle", """
            apply plugin: 'java'
            repositories { maven { url uri('repository') } }
            dependencies { implementation 'fixture:dependency:1' }
            tasks.withType(org.gradle.api.tasks.compile.JavaCompile).configureEach {
              options.release = 17
              ${if (disableInference) "modularity.inferModulePath.set(false)" else ""}
            }
        """.trimIndent() + "\n")
        write("src/main/java/module-info.java", "module fixture.gradle { requires fixture.dependency; }\n")
        write("src/main/java/fixture/app/UsesDependency.java", """
            package fixture.app;
            public final class UsesDependency { fixture.api.DependencyApi value; }
        """.trimIndent() + "\n")
        gradleJpmsModulePathArtifact = root.resolve("repository/fixture/dependency/1/dependency-1.jar")
        createModularDependencyJar(requireNotNull(compositeGradleHome).resolve("module-source"), gradleJpmsModulePathArtifact, 17)
        write("repository/fixture/dependency/1/dependency-1.pom", """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>fixture</groupId><artifactId>dependency</artifactId><version>1</version>
              <packaging>jar</packaging>
            </project>
        """.trimIndent() + "\n")
        gradleJpmsModulePathArtifactBytes = Files.readAllBytes(gradleJpmsModulePathArtifact)
        session = newGradleContextRpcSession()
        gradleJpmsSnapshotHash = session!!.dispatch("project.open", buildJsonObject {
            put("root", root.toString())
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        baseline = contents()
        val versionProcess = ProcessBuilder(requireNotNull(gradleExecutableOverride).toString(), "--version")
            .redirectErrorStream(true).apply {
                environment()["JAVA_HOME"] = sdk
                environment()["GRADLE_USER_HOME"] = requireNotNull(compositeGradleHome).toString()
            }.start()
        val versionOutput = versionProcess.inputStream.bufferedReader().use { it.readText() }
        assertTrue(versionProcess.waitFor(30, TimeUnit.SECONDS), "Gradle 8.11.1 version check must finish")
        assertEquals(0, versionProcess.exitValue(), versionOutput)
        assertTrue(Regex("(?m)^Gradle 8\\.11\\.1$").containsMatchIn(versionOutput), versionOutput)
    }

    @Given("a Gradle Java project with a complete JPMS descriptor and a real module-path JAR")
    fun gradleJpmsModulePathFixture() = gradleJpmsModulePathFixture(release = 21)

    private fun gradleJpmsModulePathFixture(release: Int) {
        root = Files.createTempDirectory("rk-gradle-jpms-")
        sdk = Path.of(System.getProperty("java.home")).toRealPath().toString()
        compositeGradleHome = root.resolveSibling("${root.fileName}-gradle-home")
        write("settings.gradle", "rootProject.name='gradle-jpms'\n")
        write("build.gradle", """
            apply plugin: 'java'
            tasks.withType(org.gradle.api.tasks.compile.JavaCompile).configureEach {
              options.release = $release
              options.compilerArgs.addAll(['--module-path', 'test-modules/fixture-dependency.jar'])
            }
        """.trimIndent() + "\n")
        write("src/main/java/module-info.java", """
            module fixture.gradle {
                requires static transitive fixture.dependency;
                exports fixture.api to fixture.dependency;
            }
        """.trimIndent() + "\n")
        write("src/main/java/fixture/api/Api.java", "package fixture.api; public final class Api {}\n")
        gradleJpmsModulePathArtifact = root.resolve("test-modules/fixture-dependency.jar")
        createModularDependencyJar(requireNotNull(compositeGradleHome).resolve("module-source"), gradleJpmsModulePathArtifact, release)
        gradleJpmsModulePathArtifactBytes = Files.readAllBytes(gradleJpmsModulePathArtifact)
        session = newGradleContextRpcSession()
        gradleJpmsSnapshotHash = session!!.dispatch("project.open", buildJsonObject {
            put("root", root.toString())
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        baseline = contents()
    }

    @When("Gradle is explicitly synchronized and public context requires JPMS")
    fun synchronizeGradleJpmsContext() {
        gradleJpmsSynchronizedHash = synchronizeGradleJpmsFixture()
        result = session!!.dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", gradleJpmsSynchronizedHash)
            put("sdkHome", sdk)
            put("requiredFacts", buildJsonArray { add(JsonPrimitive("jpms")) })
        }).jsonObject
    }

    @When("Gradle is explicitly synchronized and public context observes JPMS inference")
    fun synchronizeGradleJpmsContextWithoutRequiredFact() {
        gradleJpmsSynchronizedHash = synchronizeGradleJpmsFixture()
        result = session!!.dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", gradleJpmsSynchronizedHash)
            put("sdkHome", sdk)
        }).jsonObject
    }

    @When("Gradle is explicitly synchronized and public aggregate context observes JPMS inference")
    fun synchronizeGradleJpmsAggregateContext() {
        gradleJpmsSynchronizedHash = synchronizeGradleJpmsFixture()
        result = session!!.dispatch("project.context.aggregate", buildJsonObject {
            put("expectedSnapshotHash", gradleJpmsSynchronizedHash)
            put("sdkHome", sdk)
        }).jsonObject
    }

    @When("Gradle is explicitly synchronized and public aggregate context requires JPMS")
    fun synchronizeGradleJpmsAggregateContextRequiringFact() {
        gradleJpmsSynchronizedHash = synchronizeGradleJpmsFixture()
        result = session!!.dispatch("project.context.aggregate", buildJsonObject {
            put("expectedSnapshotHash", gradleJpmsSynchronizedHash)
            put("sdkHome", sdk)
            put("requiredFacts", buildJsonArray { add(JsonPrimitive("jpms")) })
        }).jsonObject
    }

    @When("Gradle is explicitly synchronized and public context selects JPMS source {string}")
    fun synchronizeAndSelectGradleJpmsSource(symbol: String) {
        require(symbol == "fixture.api.Api")
        write("src/main/java/fixture/api/Api.java", """
            package fixture.api;
            public final class Api {
                dependency.api.DependencyApi exported;
                dependency.internal.SecretThing hidden;
            }
        """.trimIndent() + "\n")
        gradleJpmsSnapshotHash = session!!.dispatch("project.open", buildJsonObject {
            put("root", root.toString())
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        baseline = contents()
        gradleJpmsSynchronizedHash = synchronizeGradleJpmsFixture()
        result = session!!.dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", gradleJpmsSynchronizedHash)
            put("sdkHome", sdk)
            put("providerId", "gradle-effective-v1")
            put("moduleId", ":")
            put("sourceSetId", "main")
            put("sourceRelativePath", "fixture/api/Api.java")
            put("symbol", symbol)
            put("requiredFacts", buildJsonArray { add(JsonPrimitive("jpms")) })
        }).jsonObject
    }

    @When("Gradle is explicitly synchronized before the JPMS module-path artifact changes")
    fun synchronizeGradleJpmsThenChangeArtifact() {
        gradleJpmsSynchronizedHash = synchronizeGradleJpmsFixture()
        Files.write(gradleJpmsModulePathArtifact, gradleJpmsModulePathArtifactBytes + byteArrayOf(0x42))
        baseline = contents()
        result = session!!.dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", gradleJpmsSynchronizedHash)
            put("sdkHome", sdk)
            put("requiredFacts", buildJsonArray { add(JsonPrimitive("jpms")) })
        }).jsonObject
    }

    @Then("public Gradle JPMS context refuses stale snapshot {string}")
    fun staleGradleJpmsSnapshotRefuses(refusal: String) {
        assertEquals("java-project-context-v2", result.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("refused", result.getValue("status").jsonPrimitive.content, result.toString())
        assertEquals(refusal, result.getValue("refusal").jsonObject.getValue("code").jsonPrimitive.content)
        val refreshedHash = result.getValue("snapshotHash").jsonPrimitive.content
        assertTrue(refreshedHash.matches(Regex("[a-f0-9]{64}")))
        assertTrue(refreshedHash != gradleJpmsSynchronizedHash, "changed Gradle module-path artifact must produce a new snapshot")
        assertTrue(result.getValue("modules").jsonArray.isEmpty(), result.toString())
        assertEquals(JsonNull, result.getValue("selectedSource"))
    }

    @Then("JDT JPMS analysis resolves exported module types and rejects the hidden package")
    fun jdtJpmsSemanticAssociation() {
        assertEquals("available", result.getValue("status").jsonPrimitive.content, result.toString())
        val selected = result.getValue("selectedSource").jsonObject
        assertEquals("src/main/java/fixture/api/Api.java", selected.getValue("path").jsonPrimitive.content)
        assertEquals("fixture.api.Api", selected.getValue("symbol").jsonPrimitive.content)
        assertEquals("JDT_BINDING", selected.getValue("evidence").jsonPrimitive.content)
        assertTrue(selected.getValue("bindingKey").jsonPrimitive.content.isNotBlank())
        assertEquals("eclipse-jdt", result.getValue("analysis").jsonObject.getValue("backend").jsonPrimitive.content)
        assertEquals(1, result.getValue("analysis").jsonObject.getValue("warningCount").jsonPrimitive.int,
            "only the non-exported dependency.internal.SecretThing reference should remain unresolved")
    }

    private fun synchronizeGradleJpmsFixture(): String {
        val executable = gradleExecutableOverride ?: Path.of(System.getProperty("user.home"), ".gradle", "wrapper", "dists",
            "gradle-9.6.1-bin", "4ticwg1pgcbps2hj28r8so764", "gradle-9.6.1", "bin", "gradle")
        assertTrue(Files.isExecutable(executable), "Configured Gradle executable must be installed at $executable")
        val synchronized = session!!.dispatch("project.sync", buildJsonObject {
            put("buildSystem", "gradle")
            put("expectedSnapshotHash", gradleJpmsSnapshotHash)
            put("sdkHome", sdk)
            put("gradleExecutable", executable.toString())
            put("gradleUserHome", requireNotNull(compositeGradleHome).toString())
            put("allowBuildExecution", true)
        }).jsonObject
        assertEquals("synchronized", synchronized.getValue("status").jsonPrimitive.content, synchronized.toString())
        if (gradleExecutableOverride != null) {
            assertEquals("8.11.1", synchronized.getValue("gradleVersion").jsonPrimitive.content, synchronized.toString())
        }
        return synchronized.getValue("snapshotHash").jsonPrimitive.content
    }

    @Given("a Gradle Java project with a real declared annotation processor JAR")
    fun gradleAnnotationProcessorFixture() {
        root = Files.createTempDirectory("rk-gradle-annotation-processor-")
        sdk = Path.of(System.getProperty("java.home")).toRealPath().toString()
        compositeGradleHome = root.resolveSibling("${root.fileName}-gradle-home")
        write("settings.gradle", "rootProject.name='gradle-annotation-processor'\n")
        write("build.gradle", """
            apply plugin: 'java'
            dependencies { annotationProcessor files('processor-path/fixture-processor.jar') }
            tasks.withType(org.gradle.api.tasks.compile.JavaCompile).configureEach { options.release = 21 }
        """.trimIndent() + "\n")
        write("src/main/java/fixture/app/Trigger.java", "package fixture.app; @Deprecated public final class Trigger {}\n")
        gradleAnnotationProcessorJar = root.resolve("processor-path/fixture-processor.jar")
        createAnnotationProcessorJar(requireNotNull(compositeGradleHome).resolve("processor-source"), gradleAnnotationProcessorJar)
        gradleAnnotationProcessorJarBytes = Files.readAllBytes(gradleAnnotationProcessorJar)
        session = newGradleContextRpcSession()
        session!!.dispatch("project.open", buildJsonObject { put("root", root.toString()) })
        baseline = contents()
    }

    @When("Gradle is explicitly synchronized and main annotation processor context is required")
    fun synchronizeGradleAnnotationProcessorContext() {
        val executable = Path.of(System.getProperty("user.home"), ".gradle", "wrapper", "dists",
            "gradle-9.6.1-bin", "4ticwg1pgcbps2hj28r8so764", "gradle-9.6.1", "bin", "gradle")
        assertTrue(Files.isExecutable(executable), "Gradle 9.6.1 must be installed at $executable")
        val initialHash = session!!.dispatch("project.summary", null).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        val synchronized = session!!.dispatch("project.sync", buildJsonObject {
            put("buildSystem", "gradle")
            put("expectedSnapshotHash", initialHash)
            put("sdkHome", sdk)
            put("gradleExecutable", executable.toString())
            put("gradleUserHome", requireNotNull(compositeGradleHome).toString())
            put("allowBuildExecution", true)
        }).jsonObject
        assertEquals("synchronized", synchronized.getValue("status").jsonPrimitive.content, synchronized.toString())
        val synchronizedHash = synchronized.getValue("snapshotHash").jsonPrimitive.content
        result = session!!.dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", synchronizedHash)
            put("sdkHome", sdk)
            put("providerId", "gradle-effective-v1")
            put("moduleId", ":")
            put("sourceSetId", "main")
            put("requiredFacts", buildJsonArray { add(JsonPrimitive("annotation-processors")) })
        }).jsonObject
    }

    @Then("public Gradle annotation processor metadata reports the configured path and provider")
    fun publicGradleAnnotationProcessorMetadata() {
        assertEquals(gradleAnnotationProcessorJarBytes.toList(), Files.readAllBytes(gradleAnnotationProcessorJar).toList())
        assertEquals("java-project-context-v3", result.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("available", result.getValue("status").jsonPrimitive.content, result.toString())
        assertEquals(JsonNull, result.getValue("refusal"))
        val module = result.getValue("modules").jsonArray.single().jsonObject
        val main = module.getValue("sourceSets").jsonArray.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == "main" }
        val processors = main.getValue("annotationProcessors").jsonObject
        assertEquals("complete", processors.getValue("availability").jsonPrimitive.content, processors.toString())
        assertEquals(listOf("processor-path/fixture-processor.jar"),
            processors.getValue("processorPath").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(processors.getValue("dependencies").jsonArray.isEmpty())
        assertEquals(listOf("fixture.processor.NoopProcessor"),
            processors.getValue("processorNames").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(processors.getValue("options").jsonObject.isEmpty())
        assertEquals(JsonNull, processors.getValue("mode"))
        val evidence = result.getValue("buildEvidence").jsonArray.map { it.jsonObject }
            .single { it.getValue("path").jsonPrimitive.content == "processor-path/fixture-processor.jar" }
        assertEquals("entry", evidence.getValue("kind").jsonPrimitive.content)
        assertEquals(fileEntryFingerprint(gradleAnnotationProcessorJar), evidence.getValue("fingerprint").jsonPrimitive.content)
        assertFalse(Files.exists(root.resolve("build/generated/sources/annotationProcessor/java/main/fixture/processor/GeneratedByProcessor.java")))
    }

    private fun createAnnotationProcessorJar(sourceRoot: Path, destination: Path) {
        val source = sourceRoot.resolve("fixture/processor/NoopProcessor.java")
        Files.createDirectories(source.parent)
        Files.writeString(source, """
            package fixture.processor;
            import java.io.IOException;
            import java.io.Writer;
            import java.util.Set;
            import javax.annotation.processing.AbstractProcessor;
            import javax.annotation.processing.RoundEnvironment;
            import javax.annotation.processing.SupportedAnnotationTypes;
            import javax.annotation.processing.SupportedSourceVersion;
            import javax.lang.model.SourceVersion;
            import javax.lang.model.element.TypeElement;
            @SupportedAnnotationTypes("*")
            @SupportedSourceVersion(SourceVersion.RELEASE_21)
            public final class NoopProcessor extends AbstractProcessor {
              private boolean generated;
              @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                if (!round.processingOver() && !generated) {
                  generated = true;
                  try (Writer out = processingEnv.getFiler().createSourceFile("fixture.processor.GeneratedByProcessor").openWriter()) {
                    out.write("package fixture.processor; public final class GeneratedByProcessor {}\n");
                  } catch (IOException failure) { throw new IllegalStateException(failure); }
                }
                return false;
              }
            }
        """.trimIndent() + "\n")
        val classes = sourceRoot.resolve("classes")
        Files.createDirectories(classes)
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) { "The test runtime must include javac" }
        assertEquals(0, compiler.run(null, null, null, "--release", "21", "-proc:none", "-d", classes.toString(), source.toString()))
        Files.createDirectories(destination.parent)
        JarOutputStream(Files.newOutputStream(destination)).use { jar ->
            Files.walk(classes).use { entries ->
                entries.filter { Files.isRegularFile(it) }.sorted().forEach { classFile ->
                    jar.putNextEntry(JarEntry(classes.relativize(classFile).toString().replace('\\', '/')))
                    Files.copy(classFile, jar)
                    jar.closeEntry()
                }
            }
            jar.putNextEntry(JarEntry("META-INF/services/javax.annotation.processing.Processor"))
            jar.write("fixture.processor.NoopProcessor\n".toByteArray(Charsets.UTF_8))
            jar.closeEntry()
        }
    }

    @Then("public Gradle JPMS metadata is complete with exact module identity, fingerprints, and unchanged inputs")
    fun publicGradleJpmsMetadata() {
        assertEquals(gradleJpmsModulePathArtifactBytes.toList(), Files.readAllBytes(gradleJpmsModulePathArtifact).toList())
        assertEquals("java-project-context-v2", result.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("available", result.getValue("status").jsonPrimitive.content, result.toString())
        assertEquals(JsonNull, result.getValue("refusal"))
        assertEquals(JsonNull, result.getValue("selectedSource"))
        assertEquals("eclipse-jdt", result.getValue("analysis").jsonObject.getValue("backend").jsonPrimitive.content)
        val module = result.getValue("modules").jsonArray.single().jsonObject
        val main = module.getValue("sourceSets").jsonArray.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == "main" }
        val jpms = main.getValue("jpms").jsonObject
        assertEquals("complete", jpms.getValue("availability").jsonPrimitive.content,
            "${jpms}; sourceSet=${main}")
        assertEquals(JsonNull, jpms.getValue("refusalCode"))
        val modulePath = jpms.getValue("modulePath").jsonArray.single().jsonObject
        assertEquals(gradleJpmsModulePathArtifact.toAbsolutePath().normalize().toString(),
            modulePath.getValue("path").jsonPrimitive.content)
        assertEquals(listOf("fixture.dependency"), modulePath.getValue("moduleNames").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(fileEntryFingerprint(gradleJpmsModulePathArtifact), modulePath.getValue("fingerprint").jsonPrimitive.content)
        val descriptor = jpms.getValue("descriptors").jsonArray.single().jsonObject
        assertEquals("src/main/java/module-info.java", descriptor.getValue("path").jsonPrimitive.content)
        assertEquals("fixture.gradle", descriptor.getValue("moduleName").jsonPrimitive.content)
        assertEquals("false", descriptor.getValue("open").jsonPrimitive.content)
        val requires = descriptor.getValue("requires").jsonArray.map { it.jsonObject }.single()
        assertEquals("fixture.dependency", requires.getValue("moduleName").jsonPrimitive.content)
        assertEquals(listOf("static", "transitive"), requires.getValue("modifiers").jsonArray.map { it.jsonPrimitive.content })
        val exports = descriptor.getValue("exports").jsonArray.map { it.jsonObject }.single()
        assertEquals("fixture.api", exports.getValue("packageName").jsonPrimitive.content)
        assertEquals(listOf("fixture.dependency"), exports.getValue("targets").jsonArray.map { it.jsonPrimitive.content })
        val descriptorBytes = Files.readAllBytes(root.resolve("src/main/java/module-info.java"))
        assertEquals(sha256Bytes(descriptorBytes), descriptor.getValue("contentSha256").jsonPrimitive.content)
    }

    @Then("Gradle JPMS inference publishes the declared modular dependency on the module path")
    fun publicGradleJpmsInferenceMetadata() {
        assertEquals("available", result.getValue("status").jsonPrimitive.content, result.toString())
        assertEquals("java-project-context-v2", result.getValue("schemaVersion").jsonPrimitive.content)
        val module = result.getValue("modules").jsonArray.single().jsonObject
        val main = module.getValue("sourceSets").jsonArray.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == "main" }
        val jpms = main.getValue("jpms").jsonObject
        assertEquals("complete", jpms.getValue("availability").jsonPrimitive.content,
            "${jpms}; sourceSet=${main}")
        assertEquals(JsonNull, jpms.getValue("refusalCode"))
        val modulePath = jpms.getValue("modulePath").jsonArray.single().jsonObject
        assertEquals(gradleJpmsModulePathArtifact.toAbsolutePath().normalize().toString(),
            modulePath.getValue("path").jsonPrimitive.content)
        assertEquals(listOf("fixture.dependency"), modulePath.getValue("moduleNames").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(fileEntryFingerprint(gradleJpmsModulePathArtifact), modulePath.getValue("fingerprint").jsonPrimitive.content)
        val descriptor = jpms.getValue("descriptors").jsonArray.single().jsonObject
        assertEquals("src/main/java/module-info.java", descriptor.getValue("path").jsonPrimitive.content)
        assertEquals("fixture.gradle", descriptor.getValue("moduleName").jsonPrimitive.content)
        assertEquals("fixture.dependency", descriptor.getValue("requires").jsonArray.single().jsonObject
            .getValue("moduleName").jsonPrimitive.content)
        assertEquals(gradleJpmsModulePathArtifactBytes.toList(), Files.readAllBytes(gradleJpmsModulePathArtifact).toList())
    }

    @Then("Gradle JPMS inference disabled refuses the missing required module as {string}")
    fun gradleJpmsInferenceDisabledRefuses(refusal: String) {
        assertEquals("refused", result.getValue("status").jsonPrimitive.content, result.toString())
        assertEquals(refusal, result.getValue("refusal").jsonObject.getValue("code").jsonPrimitive.content)
        assertTrue(result.getValue("modules").jsonArray.isEmpty(), result.toString())
        assertEquals(JsonNull, result.getValue("selectedSource"))
    }

    @Then("public aggregate Gradle context analyzes the inferred JPMS main source set without test-set leakage")
    fun publicAggregateGradleJpmsInference() {
        assertEquals("available", result.getValue("status").jsonPrimitive.content, result.toString())
        assertEquals("java-project-context-v3", result.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("eclipse-jdt", result.getValue("analysis").jsonObject.getValue("backend").jsonPrimitive.content)
        val module = result.getValue("modules").jsonArray.single().jsonObject
        val sourceSets = module.getValue("sourceSets").jsonArray.map { it.jsonObject }.associateBy {
            it.getValue("id").jsonPrimitive.content
        }
        val mainJpms = sourceSets.getValue("main").getValue("jpms").jsonObject
        assertEquals("complete", mainJpms.getValue("availability").jsonPrimitive.content, mainJpms.toString())
        assertEquals(listOf(gradleJpmsModulePathArtifact.toAbsolutePath().normalize().toString()),
            mainJpms.getValue("modulePath").jsonArray.map { it.jsonObject.getValue("path").jsonPrimitive.content })
        val testJpms = sourceSets.getValue("test").getValue("jpms").jsonObject
        assertEquals("not-declared", testJpms.getValue("availability").jsonPrimitive.content, testJpms.toString())
        assertTrue(testJpms.getValue("modulePath").jsonArray.isEmpty(), testJpms.toString())
        assertTrue(testJpms.getValue("descriptors").jsonArray.isEmpty(), testJpms.toString())
    }

    private fun createModularDependencyJar(sourceRoot: Path, destination: Path, release: Int) {
        val moduleInfo = sourceRoot.resolve("module-info.java")
        val api = sourceRoot.resolve("dependency/api/DependencyApi.java")
        val hidden = sourceRoot.resolve("dependency/internal/SecretThing.java")
        Files.createDirectories(moduleInfo.parent)
        Files.createDirectories(api.parent)
        Files.createDirectories(hidden.parent)
        Files.writeString(moduleInfo, "module fixture.dependency { exports dependency.api; }\n")
        Files.writeString(api, "package dependency.api; public final class DependencyApi {}\n")
        Files.writeString(hidden, "package dependency.internal; public final class SecretThing {}\n")
        val classes = sourceRoot.resolve("classes")
        Files.createDirectories(classes)
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) { "The test runtime must include javac" }
        assertEquals(0, compiler.run(null, null, null, "--release", release.toString(), "-d", classes.toString(),
            moduleInfo.toString(), api.toString(), hidden.toString()))
        Files.createDirectories(destination.parent)
        JarOutputStream(Files.newOutputStream(destination)).use { jar ->
            Files.walk(classes).use { entries ->
                entries.filter { Files.isRegularFile(it) }.sorted().forEach { classFile ->
                    jar.putNextEntry(JarEntry(classes.relativize(classFile).toString().replace('\\', '/')))
                    Files.copy(classFile, jar)
                    jar.closeEntry()
                }
            }
        }
    }

    private fun fileEntryFingerprint(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("file\u0000".toByteArray(Charsets.UTF_8))
        digest.update(Files.readAllBytes(path))
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun sha256Bytes(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    @Given("a Gradle Java project with compiler arguments {string}")
    fun compilerOptionsFixture(configuration: String) {
        assertTrue(configuration in setOf("preview-enabled", "preview-enabled-java21", "preview-absent", "argument-provider", "custom-task-action", "secret-argument"))
        gradleCompilerOptionsConfiguration = configuration
        root = Files.createTempDirectory("rk-gradle-compiler-options-")
        val java21Preview = configuration == "preview-enabled-java21"
        sdk = (if (java21Preview) {
            System.getenv("REFACTORKIT_JAVA_21_HOME")?.let(Path::of)
                ?: Path.of("/usr/lib/jvm/java-21-openjdk")
        } else {
            System.getenv("REFACTORKIT_JAVA_25_HOME")?.let(Path::of)
                ?: Path.of("/home/paperboy/.local/share/graalvm/graalvm-jdk-25-latest")
        }).toRealPath().toString()
        write("settings.gradle", "rootProject.name='compiler-options'\n")
        val compilerArgsConfiguration = when (configuration) {
            "preview-enabled", "preview-enabled-java21" -> "tasks.named('compileJava') { options.compilerArgs.addAll(['-Xlint:deprecation', '--enable-preview']) }"
            "preview-absent" -> ""
            "argument-provider" -> """
                tasks.named('compileJava') {
                  options.compilerArgs.add('-Xlint:deprecation')
                  options.compilerArgumentProviders.add({ ['--enable-preview'] } as org.gradle.process.CommandLineArgumentProvider)
                }
            """.trimIndent()
            "custom-task-action" -> "tasks.named('compileJava') { doFirst { new File(project.projectDir, 'custom-action-ran').text = 'ran'; options.compilerArgs.add('--enable-preview') } }"
            "secret-argument" -> "tasks.named('compileJava') { options.compilerArgs.add('-Aservice.token=compiler-secret-sentinel') }"
            else -> error("unsupported compiler argument fixture")
        }
        val release = if (java21Preview) 21 else 25
        write("build.gradle", """
            apply plugin: 'java'
            tasks.withType(org.gradle.api.tasks.compile.JavaCompile).configureEach { options.release = $release }
            $compilerArgsConfiguration
        """.trimIndent() + "\n")
        val source = if (java21Preview) {
            "package pkg; public class Preview { public static boolean test(Object value) { return switch (value) { case String _ -> true; default -> false; }; } }\n"
        } else {
            "package pkg; public class Preview {}\n"
        }
        write("src/main/java/pkg/Preview.java", source)
        session = newGradleContextRpcSession()
        session!!.dispatch("project.open", buildJsonObject { put("root", root.toString()) })
        baseline = contents()
        gradleCompilerOptionsContext = null
        gradleCompilerOptionsRequiredContext = null
    }

    @And("the Gradle classes task succeeds with the Java 21 preview fixture")
    fun gradleClassesTaskSucceedsForJava21Preview() {
        assertEquals("preview-enabled-java21", gradleCompilerOptionsConfiguration)
        runGradleTask("classes", "build/classes/java/main/pkg/Preview.class")
    }

    @When("Gradle is synchronized and public Java context requires compiler options")
    fun synchronizeAndRequireCompilerOptions() {
        val initial = session!!.dispatch("project.summary", null).jsonObject
            .getValue("snapshotHash").jsonPrimitive.content
        val executable = Path.of(System.getProperty("user.home"), ".gradle", "wrapper", "dists",
            "gradle-9.6.1-bin", "4ticwg1pgcbps2hj28r8so764", "gradle-9.6.1", "bin", "gradle")
        assertTrue(Files.isExecutable(executable), "Gradle 9.6.1 test distribution must be available at $executable")
        val synchronized = session!!.dispatch("project.sync", buildJsonObject {
            put("buildSystem", "gradle")
            put("expectedSnapshotHash", initial)
            put("sdkHome", sdk)
            put("gradleExecutable", executable.toString())
            put("allowBuildExecution", true)
        }).jsonObject
        assertEquals("synchronized", synchronized.getValue("status").jsonPrimitive.content, synchronized.toString())
        val snapshotHash = synchronized.getValue("snapshotHash").jsonPrimitive.content
        fun context(required: Boolean) = session!!.dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", snapshotHash)
            put("sdkHome", sdk)
            put("providerId", "gradle-effective-v1")
            put("moduleId", ":")
            put("sourceSetId", "main")
            put("sourceRelativePath", "pkg/Preview.java")
            put("symbol", "pkg.Preview")
            if (required) put("requiredFacts", buildJsonArray { add(JsonPrimitive("compiler-options")) })
        }).jsonObject
        gradleCompilerOptionsContext = context(required = false)
        gradleCompilerOptionsRequiredContext = context(required = true)
    }

    @When("Gradle is synchronized without a request user home and public Java context requires compiler options")
    fun synchronizeAndRequireCompilerOptionsWithoutUserHome() = synchronizeAndRequireCompilerOptions()

    @Given("a Gradle Java 26 project and the verified JDK 26 platform")
    fun verifiedJava26GradleFixture() {
        val configuredSdk = System.getenv("REFACTORKIT_JDK26_HOME")?.takeIf(String::isNotBlank)
        Assumptions.assumeTrue(configuredSdk != null,
            "Set REFACTORKIT_JDK26_HOME to the verified Java 26 JDK to run this acceptance")
        val selectedSdk = Path.of(requireNotNull(configuredSdk)).toRealPath()
        assertTrue(Files.isRegularFile(selectedSdk.resolve("release")))
        assertTrue(Files.isRegularFile(selectedSdk.resolve("lib/ct.sym")))

        val owned = Files.createTempDirectory("rk-gradle-java26-context-")
        root = owned.resolve("workspace")
        Files.createDirectories(root)
        sdk = selectedSdk.toString()
        write("settings.gradle", "rootProject.name='java26-context'\n")
        write("build.gradle", """
            apply plugin: 'java'
            tasks.withType(org.gradle.api.tasks.compile.JavaCompile).configureEach {
              options.release = 26
              options.compilerArgs.addAll(['-Xlint:deprecation', '-g'])
            }
        """.trimIndent() + "\n")
        write("src/main/java/fixture/Release26Application.java", """
            package fixture;
            public record Release26Application(String name, int generation) {
              public String displayName() { return name + ":" + generation; }
            }
        """.trimIndent() + "\n")

        val output = owned.resolve("java26-javac.txt")
        val classes = owned.resolve("java26-validation-classes")
        Files.createDirectories(classes)
        val javac = selectedSdk.resolve("bin").resolve(
            if (System.getProperty("os.name").startsWith("Windows")) "javac.exe" else "javac",
        )
        val validation = ProcessBuilder(
            javac.toString(), "--release", "26", "-d", classes.toString(),
            root.resolve("src/main/java/fixture/Release26Application.java").toString(),
        ).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        assertTrue(validation.waitFor(45, TimeUnit.SECONDS), "Java 26 fixture validation compiler timed out")
        assertEquals(0, validation.exitValue(), Files.readString(output))

        session = newGradleContextRpcSession()
        val opened = session!!.dispatch("project.open", buildJsonObject { put("root", root.toString()) }).jsonObject
        gradleJava26SnapshotHash = opened.getValue("snapshotHash").jsonPrimitive.content
        baseline = contents()
    }

    @When("an explicit offline Gradle sync selects the Java 26 source context")
    fun synchronizeAndSelectJava26GradleContext() {
        val executable = Path.of(assertNotNull(System.getenv("REFACTORKIT_GRADLE_EXECUTABLE"),
            "Supply the verified cached Gradle distribution explicitly for this native gate")).toRealPath()
        assertTrue(Files.isExecutable(executable), "Configured Gradle test distribution must be executable at $executable")
        val synchronized = session!!.dispatch("project.sync", buildJsonObject {
            put("buildSystem", "gradle")
            put("expectedSnapshotHash", gradleJava26SnapshotHash)
            put("sdkHome", sdk)
            put("gradleExecutable", executable.toString())
            put("allowBuildExecution", true)
        }).jsonObject
        assertEquals("synchronized", synchronized.getValue("status").jsonPrimitive.content, synchronized.toString())
        val synchronizedHash = synchronized.getValue("snapshotHash").jsonPrimitive.content
        result = session!!.dispatch("project.context.v7", buildJsonObject {
            put("expectedSnapshotHash", synchronizedHash)
            put("sdkHome", sdk)
            put("providerId", "gradle-effective-v1")
            put("moduleId", ":")
            put("sourceSetId", "main")
            put("sourceRelativePath", "fixture/Release26Application.java")
            put("symbol", "fixture.Release26Application")
            put("requiredFacts", buildJsonArray { add(JsonPrimitive("compiler-options")) })
        }).jsonObject
    }

    @Then("Gradle Java 26 context binds source and attests exact release options and platform identity")
    fun assertGradleJava26Context() {
        assertEquals("java-project-context-v7", result.getValue("schemaVersion").jsonPrimitive.content, result.toString())
        assertEquals("available", result.getValue("status").jsonPrimitive.content, result.toString())
        val selected = result.getValue("selectedSource").jsonObject
        assertTrue(selected.getValue("path").jsonPrimitive.content.endsWith("src/main/java/fixture/Release26Application.java"), selected.toString())
        assertEquals("fixture.Release26Application", selected.getValue("symbol").jsonPrimitive.content)
        assertEquals("gradle-effective-v1", selected.getValue("providerId").jsonPrimitive.content)
        assertEquals(":", selected.getValue("moduleId").jsonPrimitive.content)
        assertEquals("main", selected.getValue("sourceSetId").jsonPrimitive.content)
        assertEquals("JDT_BINDING", selected.getValue("evidence").jsonPrimitive.content)
        assertTrue(selected.getValue("bindingKey").jsonPrimitive.content.isNotBlank())

        val module = result.getValue("modules").jsonArray.map { it.jsonObject }
            .single { it.getValue("providerId").jsonPrimitive.content == "gradle-effective-v1" }
        val main = module.getValue("sourceSets").jsonArray.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == "main" }
        assertEquals(26, main.getValue("sourceLevel").jsonPrimitive.content.toInt())
        assertEquals(26, main.getValue("release").jsonPrimitive.content.toInt())
        assertEquals("complete", main.getValue("compilerOptionsAvailability").jsonPrimitive.content)
        val compilerOptions = main.getValue("compilerOptions").jsonArray.map { it.jsonObject }
        assertEquals(listOf("-Xlint:deprecation", "-g"), compilerOptions.map {
            it.getValue("value").jsonPrimitive.content
        }, compilerOptions.toString())
        compilerOptions.forEachIndexed { index, option ->
            assertEquals("compilerArg", option.getValue("name").jsonPrimitive.content)
            assertEquals("gradle-java-compile::compileJava:sourceSet=main:compilerArgs[$index]",
                option.getValue("origin").jsonPrimitive.content)
        }

        val sdkRecord = result.getValue("sdk").jsonArray.map { it.jsonObject }
            .single { it.getValue("sourceRelease").jsonPrimitive.content.toInt() == 26 }
        assertEquals(Path.of(sdk).toRealPath(), Path.of(sdkRecord.getValue("home").jsonPrimitive.content).toRealPath())
        assertEquals(26, sdkRecord.getValue("platformRelease").jsonPrimitive.content.toInt())
        for (name in listOf("identitySha256", "releaseMetadataSha256", "signatureArchiveSha256")) {
            assertTrue(sdkRecord.getValue(name).jsonPrimitive.content.matches(Regex("[a-f0-9]{64}")))
        }
        fun fileSha256(path: Path): String = MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
        assertEquals(fileSha256(Path.of(sdk).resolve("release")),
            sdkRecord.getValue("releaseMetadataSha256").jsonPrimitive.content)
        assertEquals(fileSha256(Path.of(sdk).resolve("lib/ct.sym")),
            sdkRecord.getValue("signatureArchiveSha256").jsonPrimitive.content)
        assertEquals("ct.sym", Path.of(sdkRecord.getValue("signatureArchive").jsonPrimitive.content).fileName.toString())
        val analysis = result.getValue("analysis").jsonObject
        assertEquals("eclipse-jdt", analysis.getValue("backend").jsonPrimitive.content)
        assertEquals(result.getValue("snapshotHash").jsonPrimitive.content,
            analysis.getValue("snapshotHash").jsonPrimitive.content)
        assertEquals(sdkRecord.getValue("identitySha256").jsonPrimitive.content,
            analysis.getValue("platformIdentitiesByRelease").jsonObject.getValue("26").jsonPrimitive.content)
    }

    @Given("a Gradle Java 27 project and the verified JDK 27 platform")
    fun verifiedJava27GradleMetadataFixture() {
        val configuredSdk = System.getenv("REFACTORKIT_JDK27_HOME")?.takeIf(String::isNotBlank)
        Assumptions.assumeTrue(configuredSdk != null,
            "Set REFACTORKIT_JDK27_HOME to the verified Java 27 JDK to run this acceptance")
        val selectedSdk = Path.of(requireNotNull(configuredSdk)).toRealPath()
        assertEquals(27, Regex("(?m)^JAVA_VERSION=\\\"?(\\d+)")
            .find(Files.readString(selectedSdk.resolve("release")))?.groupValues?.get(1)?.toInt())
        assertEquals("7718b4ea47f370bf1a4183b933a446d3609d3e83c20d2edafbcb2c050c24eef0",
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(selectedSdk.resolve("release")))
                .joinToString("") { "%02x".format(it) })
        assertEquals("e0b6302dd1d31b9a9d40bd379ce62c625768609c068cdaa3a988f6edfeddcd2e",
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(selectedSdk.resolve("lib/ct.sym")))
                .joinToString("") { "%02x".format(it) })

        root = Files.createTempDirectory("rk-gradle-java27-context-")
        sdk = selectedSdk.toString()
        write("settings.gradle", "rootProject.name='java27-context'\n")
        write("build.gradle", """
            apply plugin: 'java'
            java {
              toolchain { languageVersion = JavaLanguageVersion.of(27) }
            }
            tasks.withType(org.gradle.api.tasks.compile.JavaCompile).configureEach {
              options.release = 27
              options.compilerArgs.addAll(['-Xlint:deprecation', '-g'])
            }
        """.trimIndent() + "\n")
        write("src/main/java/fixture/Release27Application.java", """
            package fixture;
            public record Release27Application(String name, int generation) {
              public String displayName() { return name + ":" + generation; }
            }
        """.trimIndent() + "\n")
        session = newGradleContextRpcSession()
        gradleJava27SnapshotHash = session!!.dispatch("project.open", buildJsonObject {
            put("root", root.toString())
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        baseline = contents()
    }

    @When("provider-qualified Java context v6 is requested for Gradle metadata")
    fun requestGradleJava27MetadataContextV6() = requestGradleJava27MetadataContext(6)

    @When("provider-qualified Java context v7 is requested for Gradle metadata")
    fun requestGradleJava27MetadataContextV7() = requestGradleJava27MetadataContext(7)

    @When("provider-qualified Java context v7 selects Gradle source owner {string}")
    fun requestGradleJava27SourceOwnerV7(sourceRelativePath: String) {
        assertEquals("fixture/Release27Application.java", sourceRelativePath)
        requestGradleJava27MetadataContext(7)
        val current = requireNotNull(session)
        result = current.dispatch("project.context.v7", buildJsonObject {
            put("expectedSnapshotHash", result.getValue("snapshotHash").jsonPrimitive.content)
            put("expectedContextHash", result.getValue("contextHash").jsonPrimitive.content)
            put("sdkHome", sdk)
            put("providerId", "gradle-effective-v1")
            put("moduleId", ":")
            put("sourceSetId", "main")
            put("sourceRelativePath", sourceRelativePath)
        }).jsonObject
    }

    @When("a saved-snapshot Java definition query runs with the selected Gradle Java 27 context")
    fun queryGradleJava27DefinitionWithSelectedContext() {
        assertEquals("available", result.getValue("status").jsonPrimitive.content, result.toString())
        val selected = result.getValue("selectedSource").jsonObject
        assertEquals("src/main/java/fixture/Release27Application.java",
            selected.getValue("path").jsonPrimitive.content)
        gradleJava27DefinitionQueryResponse = requireNotNull(session).dispatch("intelligence.query", buildJsonObject {
            put("requestId", "gradle-java27-definition")
            put("kind", "definition")
            put("languageId", "java")
            put("expectedSnapshotHash", result.getValue("snapshotHash").jsonPrimitive.content)
            put("path", selected.getValue("path").jsonPrimitive.content)
            put("position", buildJsonObject { put("line", 1); put("character", 14) })
            put("sourceAuthority", buildJsonObject { put("kind", "saved-snapshot") })
            put("javaContext", buildJsonObject {
                put("sdkHome", sdk)
                put("expectedContextHash", result.getValue("contextHash").jsonPrimitive.content)
                put("contextSchemaVersion", result.getValue("schemaVersion").jsonPrimitive.content)
            })
        }).jsonObject
        saveGradleJava27DefinitionQueryEvidence(requireNotNull(gradleJava27DefinitionQueryResponse))
    }

    @When("the provider-qualified Gradle v7 metadata context handles a Java {string} request")
    fun requestGradleJava27SemanticOperation(operation: String) {
        assertTrue(operation in setOf("definition", "class rename"))
        gradleJava27SemanticOperation = operation
        gradleJava27SemanticQueryResponse = null
        gradleJava27SemanticPreviewFailure = null
        requestGradleJava27MetadataContext(7)

        assertEquals("java-project-context-v7", result.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("available", result.getValue("status").jsonPrimitive.content, result.toString())
        assertEquals("unqualified", result.getValue("semanticAnalysis").jsonObject
            .getValue("availability").jsonPrimitive.content)
        assertEquals("java.context.sourceAnalysisUnqualified", result.getValue("semanticAnalysis").jsonObject
            .getValue("refusalCode").jsonPrimitive.content)
        val contextModule = result.getValue("modules").jsonArray.map { it.jsonObject }
            .single { it.getValue("providerId").jsonPrimitive.content == "gradle-effective-v1" }
        assertEquals(":", contextModule.getValue("id").jsonPrimitive.content)
        val main = contextModule.getValue("sourceSets").jsonArray.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == "main" }
        assertEquals(27, main.getValue("sourceLevel").jsonPrimitive.content.toInt())
        assertEquals(27, main.getValue("release").jsonPrimitive.content.toInt())

        val javaContext = buildJsonObject {
            put("sdkHome", sdk)
            put("expectedContextHash", result.getValue("contextHash").jsonPrimitive.content)
            put("contextSchemaVersion", result.getValue("schemaVersion").jsonPrimitive.content)
        }
        val current = requireNotNull(session)
        when (operation) {
            "definition" -> {
                gradleJava27SemanticQueryResponse = current.dispatch("intelligence.query", buildJsonObject {
                    put("requestId", "gradle-java27-definition-refusal")
                    put("kind", "definition")
                    put("languageId", "java")
                    put("expectedSnapshotHash", result.getValue("snapshotHash").jsonPrimitive.content)
                    put("path", "src/main/java/fixture/Release27Application.java")
                    put("position", buildJsonObject { put("line", 1); put("character", 14) })
                    put("sourceAuthority", buildJsonObject { put("kind", "saved-snapshot") })
                    put("javaContext", javaContext)
                }).jsonObject
            }
            "class rename" -> {
                gradleJava27SemanticPreviewFailure = assertFailsWith<JsonRpcException> {
                    current.dispatch("refactor.preview", buildJsonObject {
                        put("operation", "renameClass")
                        put("languageId", "java")
                        put("symbol", "fixture.Release27Application")
                        put("expectedSnapshotHash", result.getValue("snapshotHash").jsonPrimitive.content)
                        put("javaContext", javaContext)
                        put("arguments", buildJsonObject { put("newName", "Release27Renamed") })
                    })
                }
            }
        }
    }

    @Then("the Gradle Java {string} request is refused with {string}")
    fun assertGradleJava27SemanticRefusal(operation: String, refusalCode: String) {
        assertEquals(operation, gradleJava27SemanticOperation)
        assertEquals("java.context.sourceAnalysisUnqualified", refusalCode)
        when (operation) {
            "definition" -> {
                val query = requireNotNull(gradleJava27SemanticQueryResponse)
                assertEquals("refused", query.getValue("status").jsonPrimitive.content, query.toString())
                assertEquals("definition", query.getValue("kind").jsonPrimitive.content)
                assertEquals(refusalCode, query.getValue("error").jsonObject
                    .getValue("code").jsonPrimitive.content)
                assertFalse("planId" in query)
            }
            "class rename" -> {
                val previewFailure = requireNotNull(gradleJava27SemanticPreviewFailure)
                assertEquals(JsonRpcErrorCodes.PLAN_REFUSED, previewFailure.code)
                assertEquals(refusalCode, previewFailure.data?.jsonObject?.get("refusalCode")
                    ?.jsonPrimitive?.content)
            }
            else -> error("Unexpected Gradle Java 27 operation: $operation")
        }
    }

    @And("the Gradle Java 27 source and project inputs remain unchanged with no preview plan or managed transaction")
    fun assertGradleJava27SemanticRefusalPreservesInputs() {
        assertEquals(baseline, contents(), "A refused Java 27 semantic request must preserve source and build inputs")
        assertTrue(TransactionLog(root.resolve(".refactorkit/transactions")).listRecords().isEmpty(),
            "Refused semantic requests must not create a managed transaction")
    }

    @And("the Java definition query refuses with {string}")
    fun assertGradleJava27DefinitionQueryRefusal(refusal: String) {
        val response = requireNotNull(gradleJava27DefinitionQueryResponse)
        assertEquals("java.context.sourceAnalysisUnqualified", refusal)
        assertEquals("refused", response.getValue("status").jsonPrimitive.content, response.toString())
        assertEquals("definition", response.getValue("kind").jsonPrimitive.content, response.toString())
        assertEquals("gradle-java27-definition", response.getValue("requestId").jsonPrimitive.content)
        assertEquals(refusal, response.getValue("error").jsonObject.getValue("code").jsonPrimitive.content,
            response.toString())
        assertTrue(response.getValue("error").jsonObject.getValue("message").jsonPrimitive.content.isNotBlank(),
            response.toString())
        assertEquals(result.getValue("snapshotHash"), response.getValue("snapshotHash"))
        saveGradleJava27DefinitionQueryEvidence(response)
    }

    private fun saveGradleJava27DefinitionQueryEvidence(response: JsonObject) {
        val repository = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        val evidence = repository.resolve(".refactorkit/runs/ide-java27-definition-refusal-r001/definition-query-response.json")
        Files.createDirectories(evidence.parent)
        Files.writeString(evidence, response.toString() + "\n")
    }

    private fun requestGradleJava27MetadataContext(version: Int) {
        require(version == 6 || version == 7)
        gradleJava27ContextVersion = version
        val gradleRuntimeHome = Path.of(System.getProperty("java.home")).toRealPath()
        val executable = Path.of(System.getProperty("user.home"), ".gradle", "wrapper", "dists",
            "gradle-9.6.1-bin", "4ticwg1pgcbps2hj28r8so764", "gradle-9.6.1", "bin", "gradle")
        assertTrue(Files.isExecutable(executable), "Gradle 9.6.1 test distribution must be available at $executable")
        val synchronized = session!!.dispatch("project.sync", buildJsonObject {
            put("buildSystem", "gradle")
            put("expectedSnapshotHash", gradleJava27SnapshotHash)
            put("sdkHome", sdk)
            put("gradleRuntimeHome", gradleRuntimeHome.toString())
            put("gradleExecutable", executable.toString())
            put("allowBuildExecution", true)
        }).jsonObject
        assertEquals("synchronized", synchronized.getValue("status").jsonPrimitive.content, synchronized.toString())
        assertEquals(Path.of(sdk).toRealPath(), Path.of(synchronized.getValue("sdkHome").jsonPrimitive.content).toRealPath())
        assertEquals(gradleRuntimeHome,
            Path.of(synchronized.getValue("gradleRuntimeHome").jsonPrimitive.content).toRealPath())
        val synchronizedHash = synchronized.getValue("snapshotHash").jsonPrimitive.content
        result = session!!.dispatch("project.context.v$version", buildJsonObject {
            put("expectedSnapshotHash", synchronizedHash)
            put("sdkHome", sdk)
            put("providerId", "gradle-effective-v1")
            put("moduleId", ":")
            put("sourceSetId", "main")
        }).jsonObject
    }

    @Then("the Java 27 Gradle context reports metadata with unqualified source analysis")
    fun assertGradleJava27MetadataContext() {
        assertEquals("java-project-context-v$gradleJava27ContextVersion", result.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("available", result.getValue("status").jsonPrimitive.content, result.toString())
        assertTrue(result.getValue("snapshotHash").jsonPrimitive.content.matches(Regex("[a-f0-9]{64}")))
        assertTrue(result.getValue("contextHash").jsonPrimitive.content.matches(Regex("[a-f0-9]{64}")))
        assertEquals(JsonNull, result.getValue("selectedSource"))
        assertEquals(JsonNull, result.getValue("analysis"))
        assertEquals(JsonNull, result.getValue("refusal"))
        val semanticAnalysis = result.getValue("semanticAnalysis").jsonObject
        assertEquals("unqualified", semanticAnalysis.getValue("availability").jsonPrimitive.content)
        assertEquals("java.context.sourceAnalysisUnqualified",
            semanticAnalysis.getValue("refusalCode").jsonPrimitive.content)

        val module = result.getValue("modules").jsonArray.map { it.jsonObject }
            .single { it.getValue("providerId").jsonPrimitive.content == "gradle-effective-v1" }
        val main = module.getValue("sourceSets").jsonArray.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == "main" }
        assertEquals(27, main.getValue("sourceLevel").jsonPrimitive.content.toInt())
        assertEquals(27, main.getValue("release").jsonPrimitive.content.toInt())
        assertEquals("complete", main.getValue("compilerOptionsAvailability").jsonPrimitive.content)
        val options = main.getValue("compilerOptions").jsonArray.map { it.jsonObject }
        assertEquals(listOf("-Xlint:deprecation", "-g"), options.map { it.getValue("value").jsonPrimitive.content })
        val jpms = main.getValue("jpms").jsonObject
        assertEquals("unavailable", jpms.getValue("availability").jsonPrimitive.content)
        assertEquals("java.context.sourceAnalysisUnqualified", jpms.getValue("refusalCode").jsonPrimitive.content)

        val sdkRecord = result.getValue("sdk").jsonArray.map { it.jsonObject }
            .single { it.getValue("sourceRelease").jsonPrimitive.content.toInt() == 27 }
        assertEquals(Path.of(sdk).toRealPath(), Path.of(sdkRecord.getValue("home").jsonPrimitive.content).toRealPath())
        assertEquals(27, sdkRecord.getValue("platformRelease").jsonPrimitive.content.toInt())
        assertTrue(sdkRecord.getValue("identitySha256").jsonPrimitive.content.matches(Regex("[a-f0-9]{64}")))
        assertEquals("7718b4ea47f370bf1a4183b933a446d3609d3e83c20d2edafbcb2c050c24eef0",
            sdkRecord.getValue("releaseMetadataSha256").jsonPrimitive.content)
        assertEquals("e0b6302dd1d31b9a9d40bd379ce62c625768609c068cdaa3a988f6edfeddcd2e",
            sdkRecord.getValue("signatureArchiveSha256").jsonPrimitive.content)

        val methods = requireNotNull(session).dispatch("server.capabilities", null).jsonObject
            .getValue("methods").jsonArray.map { it.jsonObject }
        val contextFeatures = methods.single {
            it.getValue("name").jsonPrimitive.content == "project.context.v$gradleJava27ContextVersion"
        }.getValue("features").jsonObject
        if (gradleJava27ContextVersion == 6) {
            assertEquals("true", contextFeatures.getValue("typedSchemaV6").jsonPrimitive.content)
        } else {
            assertEquals("true", contextFeatures.getValue("standardJpmsDirectives").jsonPrimitive.content)
        }
        assertEquals("true", contextFeatures.getValue("metadataOnlyUnqualifiedJavaSourceAnalysis").jsonPrimitive.content)
        val sync = methods.single { it.getValue("name").jsonPrimitive.content == "project.sync" }
            .getValue("features").jsonObject
        assertEquals("true", sync.getValue("separateGradleRuntimeAndCompilerSdk").jsonPrimitive.content)
    }

    @Then("the v7 Gradle Java context reports owner {string} without a qualified JDT binding")
    fun assertGradleJava27SourceOwnerV7(sourceRelativePath: String) {
        assertEquals("java-project-context-v7", result.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("available", result.getValue("status").jsonPrimitive.content, result.toString())
        assertTrue(result.getValue("snapshotHash").jsonPrimitive.content.matches(Regex("[a-f0-9]{64}")))
        assertTrue(result.getValue("contextHash").jsonPrimitive.content.matches(Regex("[a-f0-9]{64}")))
        assertEquals(JsonNull, result.getValue("analysis"))
        assertEquals(JsonNull, result.getValue("refusal"))
        val semanticAnalysis = result.getValue("semanticAnalysis").jsonObject
        assertEquals("unqualified", semanticAnalysis.getValue("availability").jsonPrimitive.content)
        assertEquals("java.context.sourceAnalysisUnqualified",
            semanticAnalysis.getValue("refusalCode").jsonPrimitive.content)

        val module = result.getValue("modules").jsonArray.map { it.jsonObject }
            .single { it.getValue("providerId").jsonPrimitive.content == "gradle-effective-v1" }
        val main = module.getValue("sourceSets").jsonArray.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == "main" }
        val selected = result.getValue("selectedSource").jsonObject
        assertEquals(setOf("path", "symbol", "providerId", "moduleId", "sourceSetId", "sourceRoot", "generated", "bindingKey", "evidence"),
            selected.keys)
        assertEquals("src/main/java/$sourceRelativePath", selected.getValue("path").jsonPrimitive.content)
        assertEquals(JsonNull, selected.getValue("symbol"))
        assertEquals("gradle-effective-v1", selected.getValue("providerId").jsonPrimitive.content)
        assertEquals(module.getValue("id").jsonPrimitive.content, selected.getValue("moduleId").jsonPrimitive.content)
        assertEquals(main.getValue("id").jsonPrimitive.content, selected.getValue("sourceSetId").jsonPrimitive.content)
        assertEquals("src/main/java", selected.getValue("sourceRoot").jsonPrimitive.content)
        assertEquals("false", selected.getValue("generated").jsonPrimitive.content)
        assertEquals(JsonNull, selected.getValue("bindingKey"))
        assertEquals("SOURCE_ROOT_OWNERSHIP", selected.getValue("evidence").jsonPrimitive.content)
        assertEquals(baseline, contents(), "Gradle source owner lookup must preserve Java and build input bytes")
    }

    @And("Gradle Java 27 source selection is refused with {string}")
    fun refuseGradleJava27SourceSelection(refusal: String) {
        assertEquals("java.context.sourceAnalysisUnqualified", refusal)
        val sourceSelectionResponse = requireNotNull(session).dispatch("project.context.v$gradleJava27ContextVersion", buildJsonObject {
            put("expectedSnapshotHash", result.getValue("snapshotHash").jsonPrimitive.content)
            put("sdkHome", sdk)
            put("providerId", "gradle-effective-v1")
            put("moduleId", ":")
            put("sourceSetId", "main")
            if (gradleJava27ContextVersion == 7) {
                put("symbol", "fixture.Release27Application")
            } else {
                put("sourceRelativePath", "fixture/Release27Application.java")
            }
        }).jsonObject
        assertEquals("refused", sourceSelectionResponse.getValue("status").jsonPrimitive.content)
        assertEquals("java-project-context-v$gradleJava27ContextVersion",
            sourceSelectionResponse.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals(refusal, sourceSelectionResponse.getValue("refusal").jsonObject
            .getValue("code").jsonPrimitive.content)
        assertEquals("unqualified", sourceSelectionResponse.getValue("semanticAnalysis").jsonObject
            .getValue("availability").jsonPrimitive.content)
        assertEquals(JsonNull, sourceSelectionResponse.getValue("selectedSource"))
        assertEquals(JsonNull, sourceSelectionResponse.getValue("analysis"))
        assertEquals(baseline, contents(), "Gradle sync and context acquisition must preserve Java and build inputs")
    }

    @Given("a Gradle Java project declares release {string} and lower source {string}")
    fun gradleProjectDeclaresUnsupportedRelease(release: String, lowerSource: String) {
        assertEquals("27", release)
        assertEquals("21", lowerSource)
        val configuredSdk = System.getenv("REFACTORKIT_JDK26_HOME")?.takeIf(String::isNotBlank)
        Assumptions.assumeTrue(configuredSdk != null,
            "Set REFACTORKIT_JDK26_HOME to the verified Java 26 JDK to run this acceptance")
        sdk = Path.of(requireNotNull(configuredSdk)).toRealPath().toString()
        root = Files.createTempDirectory("rk-gradle-release-27-context-")
        write("settings.gradle", "rootProject.name='unsupported-release-context'\n")
        write("build.gradle", """
            apply plugin: 'java'
            tasks.withType(org.gradle.api.tasks.compile.JavaCompile).configureEach {
              sourceCompatibility = '21'
              targetCompatibility = '21'
              options.release = 27
            }
        """.trimIndent() + "\n")
        write("src/main/java/fixture/App.java", "package fixture; public class App {}\n")
        session = newGradleContextRpcSession()
        gradleJava27SnapshotHash = session!!.dispatch("project.open", buildJsonObject {
            put("root", root.toString())
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        baseline = contents()
    }

    @When("explicit Gradle sync requests the declared Java release")
    fun synchronizeDeclaredGradleRelease() {
        val executable = Path.of(System.getProperty("user.home"), ".gradle", "wrapper", "dists",
            "gradle-9.6.1-bin", "4ticwg1pgcbps2hj28r8so764", "gradle-9.6.1", "bin", "gradle")
        assertTrue(Files.isExecutable(executable), "Gradle 9.6.1 test distribution must be available at $executable")
        result = session!!.dispatch("project.sync", buildJsonObject {
            put("buildSystem", "gradle")
            put("expectedSnapshotHash", gradleJava27SnapshotHash)
            put("sdkHome", sdk)
            put("gradleExecutable", executable.toString())
            put("allowBuildExecution", true)
        }).jsonObject
    }

    @Then("Gradle sync refuses with {string} without publishing source level {string}")
    fun gradleReleaseRefusesWithoutSourceFallback(refusal: String, lowerSource: String) {
        assertEquals("java-gradle-sync-v1", result.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("refused", result.getValue("status").jsonPrimitive.content, result.toString())
        assertEquals(refusal, result.getValue("error").jsonObject.getValue("code").jsonPrimitive.content,
            result.toString())
        assertFalse(result.toString().contains("\"sourceLevel\":\"$lowerSource\""), result.toString())
        assertEquals(null, result["snapshotHash"])
        assertEquals(null, result["providerId"])
    }

    @Then("refused Gradle sync preserves original Java and build input bytes")
    fun refusedGradleSyncPreservesInputs() {
        assertEquals(baseline, contents())
    }

    @Then("the public Gradle context reports compiler arguments {string} with availability {string} and outcome {string}")
    fun publicCompilerOptions(expectedArguments: String, availability: String, outcome: String) {
        val context = requireNotNull(gradleCompilerOptionsContext)
        val aggregateStatus = context.getValue("status").jsonPrimitive.content
        assertTrue(aggregateStatus in setOf("available", "partial"), context.toString())
        val modules = context.getValue("modules").jsonArray
        assertEquals(1, modules.size, context.toString())
        val module = modules.single().jsonObject
        val sourceSet = module.getValue("sourceSets").jsonArray.single {
            it.jsonObject.getValue("id").jsonPrimitive.content == "main"
        }.jsonObject
        if (gradleCompilerOptionsConfiguration == "preview-enabled-java21") {
            assertEquals(21, sourceSet.getValue("sourceLevel").jsonPrimitive.int, context.toString())
        }
        assertEquals(availability, sourceSet.getValue("compilerOptionsAvailability").jsonPrimitive.content, context.toString())
        val options = sourceSet.getValue("compilerOptions").jsonArray.map { it.jsonObject }
        val expected = when (expectedArguments) {
            "none" -> emptyList()
            "redacted" -> listOf("<redacted>")
            else -> expectedArguments.split(',')
        }
        assertEquals(expected, options.map { it.getValue("value").jsonPrimitive.content }, context.toString())
        options.forEachIndexed { index, option ->
            assertEquals("compilerArg", option.getValue("name").jsonPrimitive.content)
            assertEquals(
                "gradle-java-compile::compileJava:sourceSet=main:compilerArgs[$index]",
                option.getValue("origin").jsonPrimitive.content,
            )
            assertEquals(expectedArguments == "redacted", option.getValue("redacted").jsonPrimitive.content.toBoolean())
        }
        if (expectedArguments == "redacted") assertFalse(context.toString().contains("compiler-secret-sentinel"))
        if (gradleCompilerOptionsConfiguration == "custom-task-action") {
            assertFalse(Files.exists(root.resolve("custom-action-ran")), "Gradle metadata export must not execute custom JavaCompile actions")
        }

        val required = requireNotNull(gradleCompilerOptionsRequiredContext)
        if (outcome == "available") {
            assertEquals("available", required.getValue("status").jsonPrimitive.content, required.toString())
        } else {
            assertEquals("refused", required.getValue("status").jsonPrimitive.content, required.toString())
            assertEquals(outcome, required.getValue("refusal").jsonObject.getValue("code").jsonPrimitive.content, required.toString())
        }
    }

    @Given("a Gradle Java multi-project where the root configures preview compiler arguments for subprojects")
    fun rootConfiguresSubprojectPreviewCompilerArguments() {
        root = Files.createTempDirectory("rk-gradle-subproject-preview-")
        sdk = (System.getenv("REFACTORKIT_JAVA_21_HOME")?.let(Path::of)
            ?: Path.of("/usr/lib/jvm/java-21-openjdk")).toRealPath().toString()
        write("settings.gradle", "rootProject.name='subproject-preview'; include ':app'\n")
        write("build.gradle", """
            subprojects {
              apply plugin: 'java-library'
              tasks.withType(org.gradle.api.tasks.compile.JavaCompile).configureEach {
                options.release = 21
                options.compilerArgs.add('--enable-preview')
              }
            }
        """.trimIndent() + "\n")
        write("app/build.gradle", "// Java compiler options are inherited from the root build.\n")
        write("app/src/main/java/pkg/Preview.java", """
            package pkg;
            public final class Preview {
              static int classify(Object value) {
                return switch (value) {
                  case String _ -> 1;
                  default -> 0;
                };
              }
            }
        """.trimIndent() + "\n")
        baseline = contents()
        session = newGradleContextRpcSession()
        session!!.dispatch("project.open", buildJsonObject { put("root", root.toString()) })
    }

    @When("the app Java 21 classes task is built and its Gradle source context is requested")
    fun buildAndRequestSelectedAppContext() {
        runGradleTask(":app:classes", "app/build/classes/java/main/pkg/Preview.class")
        val initial = session!!.dispatch("project.summary", null).jsonObject
            .getValue("snapshotHash").jsonPrimitive.content
        val executable = Path.of(System.getProperty("user.home"), ".gradle", "wrapper", "dists",
            "gradle-9.6.1-bin", "4ticwg1pgcbps2hj28r8so764", "gradle-9.6.1", "bin", "gradle")
        assertTrue(Files.isExecutable(executable), "Gradle 9.6.1 test distribution must be available at $executable")
        val synchronized = session!!.dispatch("project.sync", buildJsonObject {
            put("buildSystem", "gradle")
            put("expectedSnapshotHash", initial)
            put("sdkHome", sdk)
            put("gradleExecutable", executable.toString())
            put("allowBuildExecution", true)
        }).jsonObject
        assertEquals("synchronized", synchronized.getValue("status").jsonPrimitive.content, synchronized.toString())
        val snapshotHash = synchronized.getValue("snapshotHash").jsonPrimitive.content
        result = session!!.dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", snapshotHash)
            put("sdkHome", sdk)
            put("providerId", "gradle-effective-v1")
            put("moduleId", ":app")
            put("sourceSetId", "main")
            put("sourceRelativePath", "pkg/Preview.java")
            put("symbol", "pkg.Preview")
            put("requiredFacts", buildJsonArray { add(JsonPrimitive("compiler-options")) })
        }).jsonObject
    }

    @Then("the selected app main context reports the ordered preview compiler arguments from its compileJava task")
    fun selectedAppContextRetainsRootConfiguredPreviewArguments() {
        assertEquals("available", result.getValue("status").jsonPrimitive.content, result.toString())
        val selected = result.getValue("selectedSource").jsonObject
        assertEquals("gradle-effective-v1", selected.getValue("providerId").jsonPrimitive.content)
        assertEquals(":app", selected.getValue("moduleId").jsonPrimitive.content)
        assertEquals("main", selected.getValue("sourceSetId").jsonPrimitive.content)
        val module = result.getValue("modules").jsonArray.single().jsonObject
        assertEquals(":app", module.getValue("id").jsonPrimitive.content)
        val main = module.getValue("sourceSets").jsonArray.single {
            it.jsonObject.getValue("id").jsonPrimitive.content == "main"
        }.jsonObject
        assertEquals("complete", main.getValue("compilerOptionsAvailability").jsonPrimitive.content)
        val options = main.getValue("compilerOptions").jsonArray.map { it.jsonObject }
        assertEquals(listOf("--enable-preview"), options.map { it.getValue("value").jsonPrimitive.content })
        assertEquals(
            listOf("gradle-java-compile::app:compileJava:sourceSet=main:compilerArgs[0]"),
            options.map { it.getValue("origin").jsonPrimitive.content },
        )
    }

    @Given("a Gradle composite Java workspace with {string} included builds")
    fun compositeFixture(layout: String) {
        assertTrue(layout in setOf("nested", "outside", "custom-project-dir", "included-app"))
        compositeLayout = layout
        root = Files.createTempDirectory("rk-gradle-composite-")
        sdk = Path.of(System.getProperty("java.home")).toRealPath().toString()
        compositeGradleHome = root.resolveSibling("${root.fileName}-gradle-home")
        if (layout == "outside") compositeOutside = root.resolveSibling("${root.fileName}-outside")
        write("settings.gradle", when (layout) {
            "outside" -> "rootProject.name='composite'; includeBuild '../${compositeOutside!!.fileName}'\n"
            "included-app" -> "rootProject.name='composite'; include ':app', ':lib'; includeBuild 'gradle/included-build'\n"
            else -> "rootProject.name='composite'; include ':app', ':lib'; includeBuild 'direct'; includeBuild 'unreferenced'\n"
        })
        write("gradle.properties", "org.gradle.jvmargs=-Xmx256m\n")
        write("build.gradle", "subprojects { apply plugin: 'java-library'; group='fixture.root'; version='1.0'; tasks.withType(JavaCompile).configureEach { options.release=8 } }\n")
        write("app/build.gradle", if (layout == "included-app") {
            "dependencies { implementation project(':lib') }\n"
        } else {
            "dependencies { implementation project(':lib'); implementation 'fixture.direct:lib:1.0' }\n"
        })
        write("lib/build.gradle", "// root build :lib\n")
        write("app/src/main/java/pkg/Runner.java", "package pkg; public class Runner {}\n")
        write("lib/src/main/java/pkg/Runner.java", "package pkg; public class Runner {}\n")
        if (layout == "nested" || layout == "custom-project-dir") {
            val directSettings = if (layout == "custom-project-dir") {
                "rootProject.name='direct'; include ':lib'; project(':lib').projectDir=file('../detached/direct-lib'); includeBuild '../nested'\n"
            } else {
                "rootProject.name='direct'; include ':lib'; includeBuild '../nested'\n"
            }
            write("direct/settings.gradle", directSettings)
            write("direct/build.gradle", "subprojects { apply plugin: 'java-library'; group='fixture.direct'; version='1.0'; tasks.withType(JavaCompile).configureEach { options.release=8 } }\n")
            if (layout != "custom-project-dir") {
                write("direct/lib/build.gradle", "dependencies { implementation 'fixture.nested:lib:1.0' }\n")
            }
            val directProjectRoot = if (layout == "custom-project-dir") "detached/direct-lib" else "direct/lib"
            write("$directProjectRoot/build.gradle", "dependencies { implementation 'fixture.nested:lib:1.0' }\n")
            write("$directProjectRoot/src/main/java/pkg/Runner.java", "package pkg; public class Runner {}\n")
            write("nested/settings.gradle", "rootProject.name='nested'; include ':lib'\n")
            write("nested/build.gradle", "subprojects { apply plugin: 'java-library'; group='fixture.nested'; version='1.0'; tasks.withType(JavaCompile).configureEach { options.release=8 } }\n")
            write("nested/lib/build.gradle", "// nested included build :lib\n")
            write("nested/lib/src/main/java/pkg/Runner.java", "package pkg; public class Runner {}\n")
            write("unreferenced/settings.gradle", "rootProject.name='unreferenced'; include ':lib'\n")
            write("unreferenced/build.gradle", "subprojects { apply plugin: 'java-library'; group='fixture.unreferenced'; version='1.0'; tasks.withType(JavaCompile).configureEach { options.release=8 } }\n")
            write("unreferenced/lib/build.gradle", "// unreferenced included build :lib\n")
            write("unreferenced/lib/src/main/java/pkg/Runner.java", "package pkg; public class Runner {}\n")
        } else if (layout == "included-app") {
            write("gradle/included-build/settings.gradle", "rootProject.name='included-app-build'; include ':app'; project(':app').projectDir=file('../app')\n")
            write("gradle/included-build/build.gradle", "subprojects { apply plugin: 'java-library'; group='fixture.included'; version='1.0'; tasks.withType(JavaCompile).configureEach { options.release=8 } }\n")
            write("gradle/app/build.gradle", "// included build :app mapped outside its build root\n")
            write("gradle/app/src/main/java/pkg/IncludedRunner.java", "package pkg; public class IncludedRunner {}\n")
        } else {
            val outside = requireNotNull(compositeOutside)
            Files.createDirectories(outside)
            Files.writeString(outside.resolve("settings.gradle"), "rootProject.name='outside'; include ':lib'\n")
            Files.writeString(outside.resolve("build.gradle"), "subprojects { apply plugin: 'java-library'; group='fixture.outside'; version='1.0'; tasks.withType(JavaCompile).configureEach { options.release=8 } }\n")
            Files.createDirectories(outside.resolve("lib/src/main/java/pkg"))
            Files.writeString(outside.resolve("lib/build.gradle"), "// outside included build\n")
            Files.writeString(outside.resolve("lib/src/main/java/pkg/Runner.java"), "package pkg; public class Runner {}\n")
        }
        session = newGradleContextRpcSession()
        compositeExpectedSnapshotHash = session!!.dispatch("project.open", buildJsonObject { put("root", root.toString()) })
            .jsonObject.getValue("snapshotHash").jsonPrimitive.content
        compositeBaseline = compositeInputContents()
    }

    @When("project context is requested with {string} identity and {string} evidence")
    fun requestCompositeContext(selection: String, freshness: String) {
        assertTrue(selection in setOf("provider-qualified", "legacy duplicate project path", "aggregate"))
        assertTrue(freshness in setOf("current", "stale"))
        if (selection == "aggregate") assertEquals("current", freshness)
        val initial = requireNotNull(compositeExpectedSnapshotHash)
        val sync = session!!.dispatch("project.sync", buildJsonObject {
            put("buildSystem", "gradle")
            put("expectedSnapshotHash", initial)
            put("sdkHome", sdk)
            put("gradleExecutable", "/home/paperboy/.gradle/wrapper/dists/gradle-9.6.1-bin/4ticwg1pgcbps2hj28r8so764/gradle-9.6.1/bin/gradle")
            put("gradleUserHome", requireNotNull(compositeGradleHome).toString())
            put("allowBuildExecution", true)
        }).jsonObject
        compositeSyncSucceeded = sync.getValue("status").jsonPrimitive.content != "refused"
        compositeServerVersion = session!!.dispatch("server.version", null).jsonObject
        compositeSummary = session!!.dispatch("project.summary", null).jsonObject
        if (sync.getValue("status").jsonPrimitive.content == "refused") {
            compositeResponse = sync
            return
        }
        val synchronizedHash = sync.getValue("snapshotHash").jsonPrimitive.content
        compositeSynchronizedSnapshotHash = synchronizedHash
        val overview = session!!.dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", synchronizedHash)
            put("sdkHome", sdk)
        }).jsonObject
        assertEquals("available", overview.getValue("status").jsonPrimitive.content, overview.toString())
        val expectedDirectProjectRoot = when (compositeLayout) {
            "custom-project-dir" -> "detached/direct-lib"
            "included-app" -> "gradle/app"
            else -> "direct/lib"
        }
        val selectedModule = overview.getValue("modules").jsonArray.single { element ->
            element.jsonObject.getValue("root").jsonPrimitive.content == expectedDirectProjectRoot
        }.jsonObject
        compositeDirectModuleId = selectedModule.getValue("id").jsonPrimitive.content
        if (selection == "aggregate") {
            compositeBaseline = compositeInputContents()
            compositeResponse = session!!.dispatch("project.context.aggregate", buildJsonObject {
                put("expectedSnapshotHash", synchronizedHash)
                put("sdkHome", sdk)
            }).jsonObject
            return
        }
        if (freshness == "stale") {
            val descriptor = root.resolve("direct/build.gradle")
            Files.writeString(descriptor, Files.readString(descriptor) + "\n// included build input changed after synchronization\n")
        }
        compositeBaseline = compositeInputContents()
        compositeResponse = session!!.dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", synchronizedHash)
            put("sdkHome", sdk)
            put("moduleId", if (selection == "legacy duplicate project path") ":lib" else requireNotNull(compositeDirectModuleId))
            put("sourceSetId", "main")
            if (selection == "provider-qualified") put("providerId", "gradle-effective-v1")
            if (freshness != "stale" && selection == "provider-qualified") {
                put("sourceRelativePath", if (compositeLayout == "included-app") "pkg/IncludedRunner.java" else "pkg/Runner.java")
                put("symbol", if (compositeLayout == "included-app") "pkg.IncludedRunner" else "pkg.Runner")
            }
        }).jsonObject
    }

    @Then("included Gradle app context preserves its exact build identity and mapped directory")
    fun includedGradleAppContextIdentity() {
        val response = requireNotNull(compositeResponse)
        assertEquals("available", response.getValue("status").jsonPrimitive.content, response.toString())
        assertEquals("java-project-context-v3", response.getValue("schemaVersion").jsonPrimitive.content)
        val modules = response.getValue("modules").jsonArray.map { it.jsonObject }
        val includedApp = modules.single {
            it.getValue("buildRoot").jsonPrimitive.content == "gradle/included-build" &&
                it.getValue("projectPath").jsonPrimitive.content == ":app"
        }
        assertEquals("gradle/app", includedApp.getValue("root").jsonPrimitive.content)
        assertEquals("gradle-${sha256("gradle/included-build")}::app", includedApp.getValue("id").jsonPrimitive.content)
        assertEquals(3, modules.size)
        assertEquals(modules.size, modules.map {
            it.getValue("buildRoot").jsonPrimitive.content to it.getValue("projectPath").jsonPrimitive.content
        }.distinct().size)
        val selected = response.getValue("selectedSource").jsonObject
        assertEquals("gradle-effective-v1", selected.getValue("providerId").jsonPrimitive.content)
        assertEquals(includedApp.getValue("id").jsonPrimitive.content, selected.getValue("moduleId").jsonPrimitive.content)
        assertEquals("gradle/app/src/main/java/pkg/IncludedRunner.java", selected.getValue("path").jsonPrimitive.content)
        assertEquals(compositeBaseline, compositeInputContents(), "qualified context selection must not modify build or source inputs")
        saveCompositeEvidence("included-app-selected", response)
    }

    @Then("aggregate Gradle context exposes qualified workspace identity for every module")
    fun aggregateContextIdentity() {
        val response = requireNotNull(compositeResponse)
        assertEquals("available", response.getValue("status").jsonPrimitive.content, response.toString())
        assertEquals("java-project-context-v3", response.getValue("schemaVersion").jsonPrimitive.content)
        val modules = response.getValue("modules").jsonArray.map { it.jsonObject }
        assertEquals(5, modules.size)
        val identities = modules.map { module ->
            module.getValue("buildRoot").jsonPrimitive.content to module.getValue("projectPath").jsonPrimitive.content
        }
        assertEquals(modules.size, identities.distinct().size)
        assertEquals(setOf(".", "direct", "nested", "unreferenced"), identities.map { it.first }.toSet())
        assertTrue(identities.contains("." to ":app"))
        assertTrue(identities.contains("direct" to ":lib"))
        val capabilities = session!!.dispatch("server.capabilities", null).jsonObject
            .getValue("methods").jsonArray.single {
                it.jsonObject.getValue("name").jsonPrimitive.content == "project.context.aggregate"
            }.jsonObject.getValue("features").jsonObject
        assertEquals(true, capabilities.getValue("aggregateGradleCompositeBuildIdentity").jsonPrimitive.boolean)
        assertEquals(compositeBaseline, compositeInputContents(), "aggregate context acquisition must not modify project inputs")
        val repository = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        val evidence = repository.resolve(compositeAggregateEvidencePath)
        Files.createDirectories(evidence.parent)
        Files.writeString(evidence, response.toString() + "\n")
    }

    @And("the selected Gradle module context is refreshed after another explicit sync")
    fun refreshSelectedGradleModuleAfterAnotherSync() {
        val firstAggregate = requireNotNull(compositeResponse)
        assertEquals("available", firstAggregate.getValue("status").jsonPrimitive.content, firstAggregate.toString())
        assertEquals("java-project-context-v3", firstAggregate.getValue("schemaVersion").jsonPrimitive.content)
        val firstModules = firstAggregate.getValue("modules").jsonArray.map { it.jsonObject }
        val selected = firstModules.single {
            it.getValue("buildRoot").jsonPrimitive.content == "direct" &&
                it.getValue("projectPath").jsonPrimitive.content == ":lib"
        }
        val evidenceDirectory = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
            .resolve(repeatSyncEvidenceDirectory)
        Files.createDirectories(evidenceDirectory)
        Files.writeString(evidenceDirectory.resolve("first-aggregate-context-response.json"), firstAggregate.toString() + "\n")
        val firstSnapshotHash = requireNotNull(compositeSynchronizedSnapshotHash)
        val selectedContext = session!!.dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", firstSnapshotHash)
            put("sdkHome", sdk)
            put("providerId", "gradle-effective-v1")
            put("moduleId", selected.getValue("id").jsonPrimitive.content)
            put("sourceSetId", "main")
            put("sourceRelativePath", "pkg/Runner.java")
            put("symbol", "pkg.Runner")
        }).jsonObject
        assertEquals("available", selectedContext.getValue("status").jsonPrimitive.content, selectedContext.toString())
        assertEquals("java-project-context-v3", selectedContext.getValue("schemaVersion").jsonPrimitive.content)
        val selectedRow = selectedContext.getValue("modules").jsonArray.map { it.jsonObject }.single {
            it.getValue("id").jsonPrimitive.content == selected.getValue("id").jsonPrimitive.content
        }
        assertEquals("direct", selectedRow.getValue("buildRoot").jsonPrimitive.content)
        assertEquals(":lib", selectedRow.getValue("projectPath").jsonPrimitive.content)
        assertEquals(selected.getValue("id").jsonPrimitive.content, selectedRow.getValue("id").jsonPrimitive.content)
        Files.writeString(evidenceDirectory.resolve("selected-module-context-response.json"), selectedContext.toString() + "\n")

        val sync = session!!.dispatch("project.sync", buildJsonObject {
            put("buildSystem", "gradle")
            put("expectedSnapshotHash", firstSnapshotHash)
            put("sdkHome", sdk)
            put("gradleExecutable", "/home/paperboy/.gradle/wrapper/dists/gradle-9.6.1-bin/4ticwg1pgcbps2hj28r8so764/gradle-9.6.1/bin/gradle")
            put("gradleUserHome", requireNotNull(compositeGradleHome).toString())
            put("allowBuildExecution", true)
        }).jsonObject
        assertEquals("synchronized", sync.getValue("status").jsonPrimitive.content, sync.toString())
        Files.writeString(evidenceDirectory.resolve("second-sync-response.json"), sync.toString() + "\n")
        val refreshedSnapshotHash = sync.getValue("snapshotHash").jsonPrimitive.content
        assertEquals(firstSnapshotHash, refreshedSnapshotHash, "unchanged project inputs must retain the synchronized snapshot hash")
        compositeSynchronizedSnapshotHash = refreshedSnapshotHash
        compositeServerVersion = session!!.dispatch("server.version", null).jsonObject
        compositeSummary = session!!.dispatch("project.summary", null).jsonObject
        compositeAggregateEvidencePath = "$repeatSyncEvidenceDirectory/aggregate-context-response.json"
        compositeBaseline = compositeInputContents()
        compositeResponse = session!!.dispatch("project.context.aggregate", buildJsonObject {
            put("expectedSnapshotHash", refreshedSnapshotHash)
            put("sdkHome", sdk)
        }).jsonObject
    }

    @Then("the composite context reports {string} with qualified workspace ownership")
    fun compositeContextOutcome(expected: String) {
        val response = requireNotNull(compositeResponse)
        if (expected != "available") {
            assertEquals("refused", response.getValue("status").jsonPrimitive.content, response.toString())
            val refusal = response["refusal"]?.jsonObject ?: response["error"]?.jsonObject
                ?: fail("Refused composite context has no typed error: $response")
            assertEquals(expected, refusal.getValue("code").jsonPrimitive.content)
            saveCompositeEvidence(expected, response)
            return
        }
        assertEquals("available", response.getValue("status").jsonPrimitive.content, response.toString())
        assertEquals("java-project-context-v3", response.getValue("schemaVersion").jsonPrimitive.content)
        val projectContextCapability = session!!.dispatch("server.capabilities", null).jsonObject
            .getValue("methods").jsonArray.single { it.jsonObject.getValue("name").jsonPrimitive.content == "project.context" }
            .jsonObject.getValue("features").jsonObject
        assertEquals(true, projectContextCapability.getValue("gradleCompositeBuildIdentity").jsonPrimitive.boolean)
        val modules = response.getValue("modules").jsonArray.map { it.jsonObject }
        assertEquals(5, modules.size, "root, direct, nested and unreferenced Java projects must be present exactly once")
        assertEquals(modules.size, modules.map { it.getValue("id").jsonPrimitive.content }.distinct().size)
        val expectedBuildRoots = setOf(".", "direct", "nested", "unreferenced")
        assertEquals(expectedBuildRoots, modules.map { it.getValue("buildRoot").jsonPrimitive.content }.toSet())
        assertEquals(modules.size, modules.map {
            it.getValue("buildRoot").jsonPrimitive.content to it.getValue("projectPath").jsonPrimitive.content
        }.distinct().size)
        modules.forEach { module ->
            val path = Path.of(module.getValue("buildRoot").jsonPrimitive.content)
            assertFalse(path.isAbsolute || path.normalize().startsWith(".."), module.toString())
            assertEquals("gradle-effective-v1", module.getValue("providerId").jsonPrimitive.content)
        }
        val rootLib = modules.single { it.getValue("buildRoot").jsonPrimitive.content == "." && it.getValue("projectPath").jsonPrimitive.content == ":lib" }
        val app = modules.single { it.getValue("buildRoot").jsonPrimitive.content == "." && it.getValue("projectPath").jsonPrimitive.content == ":app" }
        val directLib = modules.single { it.getValue("buildRoot").jsonPrimitive.content == "direct" && it.getValue("projectPath").jsonPrimitive.content == ":lib" }
        val nestedLib = modules.single { it.getValue("buildRoot").jsonPrimitive.content == "nested" && it.getValue("projectPath").jsonPrimitive.content == ":lib" }
        val unreferencedLib = modules.single { it.getValue("buildRoot").jsonPrimitive.content == "unreferenced" && it.getValue("projectPath").jsonPrimitive.content == ":lib" }
        assertEquals("gradle-${sha256("direct")}::lib", directLib.getValue("id").jsonPrimitive.content)
        fun dependencies(module: JsonObject) = module.getValue("sourceSets").jsonArray
            .single { it.jsonObject.getValue("id").jsonPrimitive.content == "main" }.jsonObject
            .getValue("moduleDependencies").jsonArray.map { it.jsonObject.getValue("moduleId").jsonPrimitive.content }.toSet()
        assertTrue(rootLib.getValue("id").jsonPrimitive.content in dependencies(app), "root app dependency must target the root :lib")
        assertTrue(directLib.getValue("id").jsonPrimitive.content in dependencies(app), "root app dependency must retain the direct included-build owner")
        assertTrue(nestedLib.getValue("id").jsonPrimitive.content in dependencies(directLib), "direct library dependency must target nested :lib")
        assertFalse(modules.any { unreferencedLib.getValue("id").jsonPrimitive.content in dependencies(it) }, "unreferenced included build must not acquire a synthetic dependency edge")
        val selected = response.getValue("selectedSource").jsonObject
        assertEquals("gradle-effective-v1", selected.getValue("providerId").jsonPrimitive.content)
        assertEquals(requireNotNull(compositeDirectModuleId), selected.getValue("moduleId").jsonPrimitive.content)
        val expectedSelectedPath = if (compositeLayout == "custom-project-dir") {
            "detached/direct-lib/src/main/java/pkg/Runner.java"
        } else {
            "direct/lib/src/main/java/pkg/Runner.java"
        }
        assertEquals(expectedSelectedPath, selected.getValue("path").jsonPrimitive.content)
        if (compositeLayout == "custom-project-dir") {
            assertEquals("detached/direct-lib", directLib.getValue("root").jsonPrimitive.content)
            assertFalse(directLib.getValue("root").jsonPrimitive.content.startsWith("direct/"))
            val main = directLib.getValue("sourceSets").jsonArray.single {
                it.jsonObject.getValue("id").jsonPrimitive.content == "main"
            }.jsonObject
            assertTrue(main.getValue("sourceRoots").jsonArray.any {
                it.jsonPrimitive.content == "detached/direct-lib/src/main/java"
            })
            assertTrue(main.getValue("outputDirectories").jsonArray.any {
                it.jsonPrimitive.content == "detached/direct-lib/build/classes/java/main"
            })
            val test = directLib.getValue("sourceSets").jsonArray.single {
                it.jsonObject.getValue("id").jsonPrimitive.content == "test"
            }.jsonObject
            val directModuleId = directLib.getValue("id").jsonPrimitive.content
            assertTrue(test.getValue("compileClasspath").jsonArray.any {
                it.jsonObject.getValue("path").jsonPrimitive.content == "detached/direct-lib/build/classes/java/main" &&
                    it.jsonObject.getValue("moduleId").jsonPrimitive.content == directModuleId &&
                    it.jsonObject.getValue("sourceSetId").jsonPrimitive.content == "main"
            }, test.getValue("compileClasspath").toString())
        }
        assertEquals(compositeBaseline, compositeInputContents(), "Gradle synchronization and context acquisition must not modify project inputs")
        saveCompositeEvidence(expected, response)
    }

    @Then("refused composite context contains no partial model")
    fun refusedCompositeContainsNoModel() {
        val response = requireNotNull(compositeResponse)
        if (response.getValue("status").jsonPrimitive.content == "refused") {
            assertTrue(response["modules"]?.jsonArray?.isEmpty() != false, response.toString())
            assertTrue(response["providers"]?.jsonArray?.isEmpty() != false, response.toString())
            assertTrue(response["selectedSource"] == null || response["selectedSource"] is JsonNull, response.toString())
        }
        assertEquals(compositeBaseline, compositeInputContents())
    }

    @And("project summary preserves all published Gradle composite identities")
    fun projectSummaryCompositeIdentities() {
        val version = requireNotNull(compositeServerVersion)
        assertEquals("0.2", version.getValue("apiVersion").jsonPrimitive.content, version.toString())
        assertTrue(version.getValue("version").jsonPrimitive.content.isNotBlank(), version.toString())
        val summaryCapability = session!!.dispatch("server.capabilities", null).jsonObject
            .getValue("methods").jsonArray.single { it.jsonObject.getValue("name").jsonPrimitive.content == "project.summary" }
            .jsonObject.getValue("features").jsonObject
        assertEquals(true, summaryCapability.getValue("gradleCompositeBuildIdentity").jsonPrimitive.boolean)

        val summary = requireNotNull(compositeSummary)
        val effectiveGradleModels = summary.getValue("buildModels").jsonArray.map { it.jsonObject }
            .filter { it.getValue("providerId").jsonPrimitive.content == "gradle-effective-v1" }
        if (compositeSyncSucceeded) {
            val modules = effectiveGradleModels.single().getValue("modules").jsonArray.map { it.jsonObject }
            assertCompositeSummaryIdentity(modules)
            val publishedByIdentity = modules.associateBy { it.getValue("id").jsonPrimitive.content }
            val enumeratedModules = summary.getValue("modules").jsonArray.map { it.jsonObject }
            assertEquals(publishedByIdentity.keys, enumeratedModules.map { it.getValue("id").jsonPrimitive.content }.toSet(),
                "top-level project.summary module rows must be directly selectable by their provider-qualified IDs")
            enumeratedModules.forEach { enumerated ->
                val identity = enumerated.getValue("id").jsonPrimitive.content
                val published = requireNotNull(publishedByIdentity[identity])
                assertEquals("gradle-effective-v1", enumerated.getValue("providerId").jsonPrimitive.content)
                assertEquals(published.getValue("root").jsonPrimitive.content, enumerated.getValue("root").jsonPrimitive.content)
                assertEquals(published.getValue("buildRoot").jsonPrimitive.content, enumerated.getValue("buildRoot").jsonPrimitive.content)
                assertEquals(published.getValue("projectPath").jsonPrimitive.content, enumerated.getValue("projectPath").jsonPrimitive.content)
            }
        } else {
            assertFalse(effectiveGradleModels.any { it.getValue("status").jsonPrimitive.content == "available" }, summary.toString())
        }
        if (compositeLayout == "nested" && compositeAggregateEvidencePath.contains("gradle-composite-repeat-sync")) {
            val repository = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
            val evidenceDirectory = repository.resolve(repeatSyncEvidenceDirectory)
            Files.writeString(evidenceDirectory.resolve("server-version.json"), version.toString() + "\n")
            Files.writeString(evidenceDirectory.resolve("project-summary.json"), summary.toString() + "\n")
        }
    }

    @Given("a Gradle Kotlin DSL Java workspace with an app depending on a library")
    fun kotlinDslMultiModuleWorkspace() {
        root = Files.createTempDirectory("rk-gradle-kotlin-dsl-context-")
        compositeGradleHome = root.resolveSibling("${root.fileName}-gradle-home")
        sdk = Path.of("/usr/lib/jvm/java-21-openjdk").toRealPath().toString()
        write("settings.gradle.kts", """
            rootProject.name = "ide-kotlin-dsl-context"
            include(":app", ":library")
        """.trimIndent() + "\n")
        write("app/build.gradle.kts", """
            plugins { application }
            dependencies { implementation(project(":library")) }
            application { mainClass.set("sample.app.App") }
        """.trimIndent() + "\n")
        write("library/build.gradle.kts", """
            plugins { `java-library` }
        """.trimIndent() + "\n")
        write("library/src/main/java/sample/library/Library.java", """
            package sample.library;
            public final class Library { public String message() { return "ready"; } }
        """.trimIndent() + "\n")
        write("app/src/main/java/sample/app/App.java", """
            package sample.app;
            import sample.library.Library;
            public final class App { public String message() { return new Library().message(); } }
        """.trimIndent() + "\n")
        write("app/src/test/java/sample/app/AppTest.java", """
            package sample.app;
            import sample.library.Library;
            final class AppTest { Library library = new Library(); }
        """.trimIndent() + "\n")
        session = newGradleContextRpcSession()
        kotlinDslExpectedSnapshotHash = session!!.dispatch("project.open", buildJsonObject {
            put("root", root.toString())
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        kotlinDslInputBaseline = kotlinDslInputContents()
    }

    @Given("a Gradle Kotlin DSL Java workspace maps app and library to custom project directories")
    fun remappedKotlinDslMultiModuleWorkspace() {
        root = Files.createTempDirectory("rk-gradle-kotlin-dsl-remapped-context-")
        compositeGradleHome = root.resolveSibling("${root.fileName}-gradle-home")
        sdk = Path.of("/usr/lib/jvm/java-21-openjdk").toRealPath().toString()
        write("settings.gradle.kts", """
            rootProject.name = "ide-kotlin-dsl-remapped-context"
            include(":app", ":library")
            project(":app").projectDir = file("modules/client")
            project(":library").projectDir = file("modules/api")
        """.trimIndent() + "\n")
        write("modules/client/build.gradle.kts", """
            plugins { application }
            dependencies { implementation(project(":library")) }
            application { mainClass.set("sample.app.App") }
        """.trimIndent() + "\n")
        write("modules/api/build.gradle.kts", """
            plugins { `java-library` }
        """.trimIndent() + "\n")
        write("modules/api/src/main/java/sample/library/Library.java", """
            package sample.library;
            public final class Library { public String message() { return "ready"; } }
        """.trimIndent() + "\n")
        write("modules/client/src/main/java/sample/app/App.java", """
            package sample.app;
            import sample.library.Library;
            public final class App { public String message() { return new Library().message(); } }
        """.trimIndent() + "\n")
        write("modules/client/src/test/java/sample/app/AppTest.java", """
            package sample.app;
            import sample.library.Library;
            final class AppTest { Library library = new Library(); }
        """.trimIndent() + "\n")
        session = newGradleContextRpcSession()
        kotlinDslExpectedSnapshotHash = session!!.dispatch("project.open", buildJsonObject {
            put("root", root.toString())
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        kotlinDslInputBaseline = kotlinDslInputContents()
    }

    @Given("a Gradle Kotlin DSL Java workspace declares an integrationTest source set")
    fun kotlinDslIntegrationSourceSetWorkspace() {
        root = Files.createTempDirectory("rk-gradle-kotlin-dsl-integration-source-set-")
        compositeGradleHome = root.resolveSibling("${root.fileName}-gradle-home")
        sdk = Path.of("/usr/lib/jvm/java-21-openjdk").toRealPath().toString()
        write("settings.gradle.kts", """
            rootProject.name = "ide-kotlin-dsl-integration-source-set"
            include(":app", ":library")
        """.trimIndent() + "\n")
        write("app/build.gradle.kts", """
            plugins { application }
            val integrationTest by sourceSets.creating {
                java.srcDir("src/integrationTest/java")
                compileClasspath += sourceSets.main.get().output
                runtimeClasspath += sourceSets.main.get().output
            }
            configurations[integrationTest.implementationConfigurationName]
                .extendsFrom(configurations.testImplementation.get())
            dependencies {
                implementation(project(":library"))
                add(integrationTest.implementationConfigurationName, project(":library"))
            }
            application { mainClass.set("sample.app.App") }
        """.trimIndent() + "\n")
        write("library/build.gradle.kts", """
            plugins { `java-library` }
        """.trimIndent() + "\n")
        write("library/src/main/java/sample/library/Library.java", """
            package sample.library;
            public final class Library { public String message() { return "ready"; } }
        """.trimIndent() + "\n")
        write("app/src/main/java/sample/app/App.java", """
            package sample.app;
            import sample.library.Library;
            public final class App { public String message() { return new Library().message(); } }
        """.trimIndent() + "\n")
        write("app/src/integrationTest/java/sample/app/AppIntegrationTest.java", """
            package sample.app;
            import sample.library.Library;
            final class AppIntegrationTest { Library library = new Library(); }
        """.trimIndent() + "\n")
        session = newGradleContextRpcSession()
        kotlinDslExpectedSnapshotHash = session!!.dispatch("project.open", buildJsonObject {
            put("root", root.toString())
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        kotlinDslInputBaseline = kotlinDslInputContents()
    }

    @When("the Kotlin DSL workspace is explicitly synchronized offline and app context is requested")
    fun synchronizeKotlinDslWorkspaceAndRequestAppContext() {
        val sync = session!!.dispatch("project.sync", buildJsonObject {
            put("buildSystem", "gradle")
            put("expectedSnapshotHash", requireNotNull(kotlinDslExpectedSnapshotHash))
            put("sdkHome", sdk)
            put("gradleExecutable", "/home/paperboy/.gradle/wrapper/dists/gradle-9.6.1-bin/4ticwg1pgcbps2hj28r8so764/gradle-9.6.1/bin/gradle")
            put("gradleUserHome", requireNotNull(compositeGradleHome).toString())
            put("allowBuildExecution", true)
        }).jsonObject
        kotlinDslSyncResponse = sync
        if (sync.getValue("status").jsonPrimitive.content != "synchronized") return
        val synchronizedHash = sync.getValue("snapshotHash").jsonPrimitive.content
        fun appContext(sourceSetId: String, path: String, symbol: String) = session!!.dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", synchronizedHash)
            put("sdkHome", sdk)
            put("providerId", "gradle-effective-v1")
            put("moduleId", ":app")
            put("sourceSetId", sourceSetId)
            put("sourceRelativePath", path)
            put("symbol", symbol)
        }).jsonObject
        kotlinDslMainContext = appContext("main", "sample/app/App.java", "sample.app.App")
        kotlinDslTestContext = appContext("test", "sample/app/AppTest.java", "sample.app.AppTest")
    }

    @When("the remapped Kotlin DSL workspace is synchronized offline and app main and test contexts are requested")
    fun synchronizeRemappedKotlinDslWorkspaceAndRequestAppContexts() {
        val sync = session!!.dispatch("project.sync", buildJsonObject {
            put("buildSystem", "gradle")
            put("expectedSnapshotHash", requireNotNull(kotlinDslExpectedSnapshotHash))
            put("sdkHome", sdk)
            put("gradleExecutable", "/home/paperboy/.gradle/wrapper/dists/gradle-9.6.1-bin/4ticwg1pgcbps2hj28r8so764/gradle-9.6.1/bin/gradle")
            put("gradleUserHome", requireNotNull(compositeGradleHome).toString())
            put("allowBuildExecution", true)
        }).jsonObject
        kotlinDslSyncResponse = sync
        if (sync.getValue("status").jsonPrimitive.content != "synchronized") return
        val synchronizedHash = sync.getValue("snapshotHash").jsonPrimitive.content
        fun appContext(sourceSetId: String, path: String, symbol: String) = session!!.dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", synchronizedHash)
            put("sdkHome", sdk)
            put("providerId", "gradle-effective-v1")
            put("moduleId", ":app")
            put("sourceSetId", sourceSetId)
            put("sourceRelativePath", path)
            put("symbol", symbol)
        }).jsonObject
        kotlinDslMainContext = appContext("main", "sample/app/App.java", "sample.app.App")
        kotlinDslTestContext = appContext("test", "sample/app/AppTest.java", "sample.app.AppTest")
    }

    @When("the Kotlin DSL workspace is synchronized offline and integrationTest context is requested")
    fun synchronizeKotlinDslWorkspaceAndRequestIntegrationTestContext() {
        val sync = session!!.dispatch("project.sync", buildJsonObject {
            put("buildSystem", "gradle")
            put("expectedSnapshotHash", requireNotNull(kotlinDslExpectedSnapshotHash))
            put("sdkHome", sdk)
            put("gradleExecutable", "/home/paperboy/.gradle/wrapper/dists/gradle-9.6.1-bin/4ticwg1pgcbps2hj28r8so764/gradle-9.6.1/bin/gradle")
            put("gradleUserHome", requireNotNull(compositeGradleHome).toString())
            put("allowBuildExecution", true)
        }).jsonObject
        kotlinDslSyncResponse = sync
        if (sync.getValue("status").jsonPrimitive.content != "synchronized") return
        val synchronizedHash = sync.getValue("snapshotHash").jsonPrimitive.content
        kotlinDslIntegrationContext = session!!.dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", synchronizedHash)
            put("sdkHome", sdk)
            put("providerId", "gradle-effective-v1")
            put("moduleId", ":app")
            put("sourceSetId", "integrationTest")
            put("sourceRelativePath", "sample/app/AppIntegrationTest.java")
            put("symbol", "sample.app.AppIntegrationTest")
        }).jsonObject
    }

    @Then("project context retains the library main dependency in app compile and runtime context")
    fun kotlinDslAppContextRetainsLibraryMainDependency() {
        val sync = requireNotNull(kotlinDslSyncResponse)
        assertEquals("synchronized", sync.getValue("status").jsonPrimitive.content, sync.toString())
        val selectedContexts = listOf(
            Triple(requireNotNull(kotlinDslMainContext), "main", "app/src/main/java/sample/app/App.java"),
            Triple(requireNotNull(kotlinDslTestContext), "test", "app/src/test/java/sample/app/AppTest.java"),
        )
        for ((context, appSourceSetId, selectedPath) in selectedContexts) {
            assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
            assertEquals("java-project-context-v3", context.getValue("schemaVersion").jsonPrimitive.content)
            val modules = context.getValue("modules").jsonArray.map { it.jsonObject }
            val app = modules.single { it.getValue("id").jsonPrimitive.content == ":app" }
            val library = modules.single { it.getValue("id").jsonPrimitive.content == ":library" }
            val appSourceSet = app.getValue("sourceSets").jsonArray.map { it.jsonObject }
                .single { it.getValue("id").jsonPrimitive.content == appSourceSetId }
            val libraryMain = library.getValue("sourceSets").jsonArray.map { it.jsonObject }
                .single { it.getValue("id").jsonPrimitive.content == "main" }
            val dependencyIds = appSourceSet.getValue("moduleDependencies").jsonArray.map { dependency ->
                dependency.jsonObject.getValue("moduleId").jsonPrimitive.content
            }.toSet()
            assertTrue(library.getValue("id").jsonPrimitive.content in dependencyIds,
                "Gradle Kotlin DSL must retain the :app $appSourceSetId -> :library project dependency")
            for (classpathName in listOf("compileClasspath", "runtimeClasspath")) {
                val libraryEntry = appSourceSet.getValue(classpathName).jsonArray.map { it.jsonObject }
                    .singleOrNull {
                        it.getValue("moduleId").jsonPrimitive.content == library.getValue("id").jsonPrimitive.content &&
                            it.getValue("sourceSetId").jsonPrimitive.content == "main"
                    }
                assertNotNull(
                    libraryEntry,
                    "$appSourceSetId $classpathName must expose the :library/main context; " +
                        "entries=${appSourceSet.getValue(classpathName)}; dependencies=$dependencyIds",
                )
                val libraryPath = libraryEntry.getValue("path").jsonPrimitive.content
                val expectedOutput = if (classpathName == "compileClasspath") {
                    "library/build/classes/java/main"
                } else {
                    "library/build/libs/library.jar"
                }
                assertTrue(libraryPath.endsWith(expectedOutput),
                    "$classpathName must retain the exact :library/main output identity: $libraryEntry")
            }
            assertTrue(libraryMain.getValue("sourceRoots").jsonArray.any {
                it.jsonPrimitive.content.endsWith("library/src/main/java")
            })
            val selectedSource = context.getValue("selectedSource").jsonObject
            assertEquals(selectedPath, selectedSource.getValue("path").jsonPrimitive.content)
            assertEquals(":app", selectedSource.getValue("moduleId").jsonPrimitive.content)
            assertEquals("gradle-effective-v1", selectedSource.getValue("providerId").jsonPrimitive.content)
        }
    }

    @Then("remapped Kotlin DSL contexts preserve project identity source ownership and library classpaths")
    fun remappedKotlinDslContextsPreserveProjectIdentityAndClasspaths() {
        val sync = requireNotNull(kotlinDslSyncResponse)
        assertEquals("synchronized", sync.getValue("status").jsonPrimitive.content, sync.toString())
        val selectedContexts = listOf(
            Triple(requireNotNull(kotlinDslMainContext), "main", "modules/client/src/main/java/sample/app/App.java"),
            Triple(requireNotNull(kotlinDslTestContext), "test", "modules/client/src/test/java/sample/app/AppTest.java"),
        )
        for ((context, sourceSetId, selectedPath) in selectedContexts) {
            assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
            assertEquals("java-project-context-v3", context.getValue("schemaVersion").jsonPrimitive.content)
            val modules = context.getValue("modules").jsonArray.map { it.jsonObject }
            val app = modules.single { it.getValue("id").jsonPrimitive.content == ":app" }
            val library = modules.single { it.getValue("id").jsonPrimitive.content == ":library" }
            assertEquals(".", app.getValue("buildRoot").jsonPrimitive.content)
            assertEquals(":app", app.getValue("projectPath").jsonPrimitive.content)
            assertEquals(".", library.getValue("buildRoot").jsonPrimitive.content)
            assertEquals(":library", library.getValue("projectPath").jsonPrimitive.content)
            val appSourceSet = app.getValue("sourceSets").jsonArray.map { it.jsonObject }
                .single { it.getValue("id").jsonPrimitive.content == sourceSetId }
            val libraryMain = library.getValue("sourceSets").jsonArray.map { it.jsonObject }
                .single { it.getValue("id").jsonPrimitive.content == "main" }
            val expectedAppRoot = if (sourceSetId == "main") {
                "modules/client/src/main/java"
            } else {
                "modules/client/src/test/java"
            }
            assertTrue(appSourceSet.getValue("sourceRoots").jsonArray.any {
                it.jsonPrimitive.content.endsWith(expectedAppRoot)
            }, "settings.gradle.kts projectDir must determine :app $sourceSetId source ownership")
            assertTrue(libraryMain.getValue("sourceRoots").jsonArray.any {
                it.jsonPrimitive.content.endsWith("modules/api/src/main/java")
            }, "settings.gradle.kts projectDir must determine :library source ownership")
            for (classpathName in listOf("compileClasspath", "runtimeClasspath")) {
                val libraryEntry = appSourceSet.getValue(classpathName).jsonArray.map { it.jsonObject }
                    .singleOrNull {
                        it.getValue("moduleId").jsonPrimitive.content == library.getValue("id").jsonPrimitive.content &&
                            it.getValue("sourceSetId").jsonPrimitive.content == "main"
                    }
                assertNotNull(libraryEntry, "$sourceSetId $classpathName must select :library/main: ${appSourceSet.getValue(classpathName)}")
                val expectedSuffix = if (classpathName == "compileClasspath") {
                    "modules/api/build/classes/java/main"
                } else {
                    "modules/api/build/libs/library.jar"
                }
                assertTrue(libraryEntry.getValue("path").jsonPrimitive.content.endsWith(expectedSuffix),
                    "$classpathName must resolve through the mapped library project directory: $libraryEntry")
            }
            val selectedSource = context.getValue("selectedSource").jsonObject
            assertEquals(selectedPath, selectedSource.getValue("path").jsonPrimitive.content)
            assertEquals(":app", selectedSource.getValue("moduleId").jsonPrimitive.content)
            assertEquals("gradle-effective-v1", selectedSource.getValue("providerId").jsonPrimitive.content)
        }
    }

    @Then("project context selects the integrationTest source set with exact library main visibility")
    fun kotlinDslIntegrationContextSelectsExactSourceSetAndLibrary() {
        val sync = requireNotNull(kotlinDslSyncResponse)
        assertEquals("synchronized", sync.getValue("status").jsonPrimitive.content, sync.toString())
        val context = requireNotNull(kotlinDslIntegrationContext)
        assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
        assertEquals("java-project-context-v3", context.getValue("schemaVersion").jsonPrimitive.content)
        val modules = context.getValue("modules").jsonArray.map { it.jsonObject }
        val app = modules.single { it.getValue("id").jsonPrimitive.content == ":app" }
        val library = modules.single { it.getValue("id").jsonPrimitive.content == ":library" }
        val integrationTest = app.getValue("sourceSets").jsonArray.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == "integrationTest" }
        assertEquals("custom", integrationTest.getValue("kind").jsonPrimitive.content,
            "the provider must not infer source-set semantics from its name alone")
        assertTrue(integrationTest.getValue("sourceRoots").jsonArray.any {
            it.jsonPrimitive.content.endsWith("app/src/integrationTest/java")
        })
        val selectedSource = context.getValue("selectedSource").jsonObject
        assertEquals("app/src/integrationTest/java/sample/app/AppIntegrationTest.java", selectedSource.getValue("path").jsonPrimitive.content)
        assertEquals(":app", selectedSource.getValue("moduleId").jsonPrimitive.content)
        assertEquals("gradle-effective-v1", selectedSource.getValue("providerId").jsonPrimitive.content)
        val dependencyIds = integrationTest.getValue("moduleDependencies").jsonArray.map {
            it.jsonObject.getValue("moduleId").jsonPrimitive.content
        }.toSet()
        assertTrue(library.getValue("id").jsonPrimitive.content in dependencyIds, dependencyIds.toString())
        for ((classpathName, suffix) in listOf(
            "compileClasspath" to "library/build/classes/java/main",
            "runtimeClasspath" to "library/build/libs/library.jar",
        )) {
            val libraryMain = integrationTest.getValue(classpathName).jsonArray.map { it.jsonObject }
                .singleOrNull {
                    it.getValue("moduleId").jsonPrimitive.content == library.getValue("id").jsonPrimitive.content &&
                        it.getValue("sourceSetId").jsonPrimitive.content == "main"
                }
            assertNotNull(libraryMain, "$classpathName must retain exact :library/main visibility: ${integrationTest.getValue(classpathName)}")
            assertTrue(libraryMain.getValue("path").jsonPrimitive.content.endsWith(suffix), "$libraryMain")
        }
    }

    @And("Kotlin DSL and Java project inputs remain byte-identical after sync")
    fun kotlinDslProjectInputsRemainByteIdentical() {
        assertEquals(kotlinDslInputBaseline, kotlinDslInputContents(),
            "explicit Gradle sync and project context acquisition must not edit Kotlin DSL or Java project inputs")
    }

    private fun runGradleTask(task: String, expectedOutput: String) {
        val executable = Path.of(System.getProperty("user.home"), ".gradle", "wrapper", "dists",
            "gradle-9.6.1-bin", "4ticwg1pgcbps2hj28r8so764", "gradle-9.6.1", "bin", "gradle")
        assertTrue(Files.isExecutable(executable), "Gradle 9.6.1 test distribution must be available at $executable")
        val output = Files.createTempFile("rk-gradle-java-classes-", ".log")
        val process = ProcessBuilder(
            executable.toString(), "--offline", "--no-daemon", "--no-configuration-cache",
            "--project-dir", root.toString(), task,
        ).redirectErrorStream(true).redirectOutput(output.toFile()).also { builder ->
            val environment = builder.environment()
            environment["JAVA_HOME"] = sdk
            environment["PATH"] = "${Path.of(sdk).resolve("bin")}${File.pathSeparator}${environment["PATH"].orEmpty()}"
        }.start()
        try {
            val finished = process.waitFor(120, TimeUnit.SECONDS)
            if (!finished) {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
                process.waitFor(10, TimeUnit.SECONDS)
            }
            val log = Files.readString(output).takeLast(16_384)
            assertTrue(finished, "Gradle $task timed out; output tail:\n$log")
            assertEquals(0, process.exitValue(), "Gradle $task failed; output tail:\n$log")
            assertTrue(Files.isRegularFile(root.resolve(expectedOutput)), log)
        } finally {
            if (process.isAlive) {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
                process.waitFor(10, TimeUnit.SECONDS)
            }
            Files.deleteIfExists(output)
        }
    }

    private fun write(relative: String, content: String) { val file = root.resolve(relative); Files.createDirectories(file.parent); Files.writeString(file, content) }
    private fun contents(): Map<String, String> = Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) && (it.toString().endsWith(".java") || it.toString().endsWith(".gradle") || it.fileName.toString() == "gradle.properties") }.toList().associate { root.relativize(it).toString() to Files.readString(it) } }
    private fun compositeInputContents(): Map<String, String> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) && (it.toString().endsWith(".java") || it.toString().endsWith(".gradle") || it.fileName.toString() in setOf("settings.gradle", "gradle.properties")) }
            .filter { root.relativize(it).none { part -> part.toString() == "build" || part.toString() == ".gradle" } }
            .toList().associate { root.relativize(it).toString() to Files.readString(it) }
    }
    private fun kotlinDslInputContents(): Map<String, String> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) && (it.toString().endsWith(".java") || it.toString().endsWith(".gradle") || it.toString().endsWith(".gradle.kts") || it.fileName.toString() == "gradle.properties") }
            .filter { root.relativize(it).none { part -> part.toString() == "build" || part.toString() == ".gradle" } }
            .toList().associate { root.relativize(it).toString() to Files.readString(it) }
    }
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private fun assertCompositeSummaryIdentity(modules: List<JsonObject>) {
        if (compositeLayout == "included-app") {
            assertEquals(3, modules.size)
            val identities = modules.map {
                it.getValue("buildRoot").jsonPrimitive.content to it.getValue("projectPath").jsonPrimitive.content
            }.toSet()
            assertEquals(setOf("." to ":app", "." to ":lib", "gradle/included-build" to ":app"), identities)
            modules.forEach { module ->
                val buildRoot = module.getValue("buildRoot").jsonPrimitive.content
                val projectPath = module.getValue("projectPath").jsonPrimitive.content
                assertEquals("gradle-${sha256(buildRoot)}:$projectPath", module.getValue("id").jsonPrimitive.content)
            }
            return
        }
        assertEquals(5, modules.size)
        assertEquals(setOf(".", "direct", "nested", "unreferenced"), modules.map {
            it.getValue("buildRoot").jsonPrimitive.content
        }.toSet())
        assertEquals(modules.size, modules.map {
            it.getValue("buildRoot").jsonPrimitive.content to it.getValue("projectPath").jsonPrimitive.content
        }.distinct().size)
        modules.forEach { module ->
            val buildRoot = module.getValue("buildRoot").jsonPrimitive.content
            val projectPath = module.getValue("projectPath").jsonPrimitive.content
            assertEquals("gradle-${sha256(buildRoot)}:$projectPath", module.getValue("id").jsonPrimitive.content)
        }
        assertEquals("gradle-${sha256("direct")}::lib", modules.single {
            it.getValue("buildRoot").jsonPrimitive.content == "direct" &&
                it.getValue("projectPath").jsonPrimitive.content == ":lib"
        }.getValue("id").jsonPrimitive.content)
    }
    private fun saveCompositeEvidence(outcome: String, value: JsonObject) {
        val repository = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        val responseDirectory = if (System.getProperty("refactorkit.gradle.composite.package.root") == null) {
            ".refactorkit/runs/gradle-composite-summary-identities-r001/responses"
        } else {
            "$repeatSyncEvidenceDirectory/responses"
        }
        val directory = repository.resolve(responseDirectory)
        Files.createDirectories(directory)
        val prefix = when (compositeLayout) {
            "custom-project-dir" -> "custom-project-dir-"
            "included-app" -> "included-app-"
            else -> ""
        }
        Files.writeString(directory.resolve("$prefix${outcome.replace('.', '-')}.json"), value.toString() + "\n")
        compositeSummary?.let { Files.writeString(directory.resolve("${prefix}project-summary.json"), it.toString() + "\n") }
        compositeServerVersion?.let { Files.writeString(directory.resolve("server-version.json"), it.toString() + "\n") }
    }
    @After
    fun close() {
        session?.close()
        if (::root.isInitialized) Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        compositeOutside?.let { outside -> if (Files.exists(outside)) Files.walk(outside).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
        compositeGradleHome?.let { home -> if (Files.exists(home)) Files.walk(home).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
}
