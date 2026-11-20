package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.When
import io.cucumber.java.en.Then
import kotlinx.serialization.json.*
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchFaultInjector
import org.refactorkit.core.PatchFaultPoint
import org.refactorkit.daemon.DaemonSession
import org.refactorkit.java.JavaBoundContext
import org.refactorkit.java.JavaBoundContextResolution
import org.refactorkit.java.JavaProjectContextRequest
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.java.JavaOrganizeImportsPlanner
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class JavaMutationContextAuthoritySteps {
    private lateinit var owned: Path
    private lateinit var root: Path
    private lateinit var sdk: Path
    private var session: DaemonSession? = null
    private lateinit var hash: String
    private lateinit var context: JsonObject
    private lateinit var preview: JsonObject
    private var result: JsonObject? = null
    private var failure: JsonRpcException? = null
    private lateinit var baseline: Map<String, String>
    private lateinit var operation: String
    private lateinit var gradleSnapshotHash: String
    private lateinit var gradleMoveContext: JsonObject
    private lateinit var gradleMoveV3Context: JsonObject
    private var gradleMovePreview: JsonObject? = null
    private var gradleMoveFailure: JsonRpcException? = null
    private var gradleMoveApplySchemaRefusalCode: String? = null
    private lateinit var gradleMoveBaseline: Map<String, String>

    @Given("a Java mutation fixture with an acquired explicit SDK context")
    fun fixture() {
        owned = Files.createTempDirectory("rk-java-mutation-context-"); root = owned.resolve("app"); sdk = owned.resolve("sdk")
        Files.createDirectories(root); Files.createDirectories(sdk.resolve("lib"))
        Files.copy(Path.of("/usr/lib/jvm/java-21-openjdk/release"), sdk.resolve("release"))
        Files.createSymbolicLink(sdk.resolve("lib/ct.sym"), Path.of("/usr/lib/jvm/java-21-openjdk/lib/ct.sym"))
        Files.createSymbolicLink(sdk.resolve("lib/jrt-fs.jar"), Path.of("/usr/lib/jvm/java-21-openjdk/lib/jrt-fs.jar"))
        Files.createSymbolicLink(sdk.resolve("lib/modules"), Path.of("/usr/lib/jvm/java-21-openjdk/lib/modules"))
        write("pom.xml", "<project><modelVersion>4.0.0</modelVersion><groupId>pkg</groupId><artifactId>app</artifactId><version>1</version><properties><maven.compiler.release>8</maven.compiler.release></properties></project>\n")
        write("src/main/java/pkg/Runner.java", "package pkg;\nimport java.util.Set;\nimport java.util.List;\npublic class Runner { List<String> values; public Runner() {} }\n")
        write("src/main/java/pkg/Consumer.java", "package pkg; public class Consumer { Runner value = new Runner(); }\n")
        session = DaemonSession(); hash = session!!.dispatch("project.open", buildJsonObject { put("root", root.toString()) }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        context = session!!.dispatch("project.context", buildJsonObject { put("expectedSnapshotHash", hash); put("sdkHome", sdk.toString()) }).jsonObject
        assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
        baseline = contents()
    }

    @When("a Java {string} preview is followed by {string}")
    fun execute(selectedOperation: String, action: String) {
        operation = selectedOperation
        preview = session!!.dispatch("refactor.preview", buildJsonObject {
            put("operation", operation); put("symbol", "pkg.Runner"); put("languageId", "java"); put("expectedSnapshotHash", hash)
            put("arguments", buildJsonObject { if (operation == "renameClass") put("newName", "Worker") else put("file", "src/main/java/pkg/Runner.java") })
            put("javaContext", authority())
        }).jsonObject
        if (action == "SDK drift apply") Files.writeString(sdk.resolve("release"), Files.readString(sdk.resolve("release")) + "\nSDK_INPUT_CHANGED=true\n")
        if (action == "build drift apply") { write("pom.xml", Files.readString(root.resolve("pom.xml")).replace("<version>1</version>", "<version>2</version>")); baseline = contents() }
        try {
            result = session!!.dispatch("refactor.apply", buildJsonObject {
                put("planId", preview.getValue("planId")); put("expectedSnapshotHash", hash)
                if (action != "missing context apply") put("javaContext", authority(action == "other context apply"))
            }).jsonObject
        } catch (refused: JsonRpcException) { failure = refused }
    }

    @Then("the SDK-bound mutation reports {string}")
    fun outcome(expected: String) {
        if (expected == "applied") {
            assertNull(failure); assertEquals(expected, result?.getValue("status")?.jsonPrimitive?.content)
            assertEquals(context.getValue("contextHash"), preview["contextHash"])
            assertEquals(context.getValue("contextHash"), result?.get("appliedContextHash"))
            if (operation == "renameClass") {
                assertFalse(Files.exists(root.resolve("src/main/java/pkg/Runner.java")))
                assertTrue(Files.readString(root.resolve("src/main/java/pkg/Worker.java")).contains("class Worker"))
                assertTrue(Files.readString(root.resolve("src/main/java/pkg/Consumer.java")).contains("new Worker()"))
            } else {
                assertFalse(Files.readString(root.resolve("src/main/java/pkg/Runner.java")).contains("import java.util.Set"))
                proveSdkCurrencyUnderLock()
            }
        } else {
            assertNotNull(failure)
            assertEquals(expected, failure!!.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content)
            assertNull(result)
        }
    }

    @Then("context refusal leaves no source modification or transaction intent")
    fun noWrites() {
        if (failure != null) { assertEquals(baseline, contents()); assertFalse(Files.exists(root.resolve(".refactorkit/transactions"))) }
    }

    @Given("a synchronized Gradle Java project with a single class move target")
    fun synchronizedGradleMoveClassFixture() {
        owned = Files.createTempDirectory("rk-gradle-moveclass-context-")
        root = owned.resolve("workspace")
        sdk = Path.of(System.getProperty("java.home")).toRealPath()
        write("settings.gradle", "rootProject.name='move-context'; include 'app'\n")
        write("gradle.properties", "org.gradle.jvmargs=-Xmx256m\n")
        write("build.gradle", "subprojects { apply plugin: 'java'; tasks.withType(JavaCompile).configureEach { options.release=8 } }\n")
        write("app/build.gradle", "// app source-set context\n")
        write("app/src/main/java/pkg/Runner.java", "package pkg;\npublic class Runner {}\n")
        write("app/src/main/java/consumer/Consumer.java", "package consumer;\nimport pkg.Runner;\npublic class Consumer { Runner value = new Runner(); }\n")
        session = DaemonSession()
        val opened = session!!.dispatch("project.open", buildJsonObject { put("root", root.toString()) }).jsonObject
        val initial = opened.getValue("snapshotHash").jsonPrimitive.content
        val sync = session!!.dispatch("project.sync", buildJsonObject {
            put("buildSystem", "gradle")
            put("expectedSnapshotHash", initial)
            put("sdkHome", sdk.toString())
            put("gradleExecutable", "/home/paperboy/.gradle/wrapper/dists/gradle-9.6.1-bin/4ticwg1pgcbps2hj28r8so764/gradle-9.6.1/bin/gradle")
            put("gradleUserHome", owned.resolve("gradle-home").toString())
            put("allowBuildExecution", true)
        }).jsonObject
        assertEquals("synchronized", sync.getValue("status").jsonPrimitive.content, sync.toString())
        gradleSnapshotHash = sync.getValue("snapshotHash").jsonPrimitive.content
        gradleMoveContext = session!!.dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", gradleSnapshotHash)
            put("sdkHome", sdk.toString())
        }).jsonObject
        assertEquals("available", gradleMoveContext.getValue("status").jsonPrimitive.content, gradleMoveContext.toString())
        assertEquals("java-project-context-v2", gradleMoveContext.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals(gradleSnapshotHash, gradleMoveContext.getValue("snapshotHash").jsonPrimitive.content)
        gradleMoveV3Context = session!!.dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", gradleSnapshotHash)
            put("sdkHome", sdk.toString())
            put("providerId", "gradle-effective-v1")
            put("moduleId", ":app")
            put("sourceSetId", "main")
            put("sourceRelativePath", "pkg/Runner.java")
            put("symbol", "pkg.Runner")
        }).jsonObject
        assertEquals("available", gradleMoveV3Context.getValue("status").jsonPrimitive.content, gradleMoveV3Context.toString())
        assertEquals("java-project-context-v3", gradleMoveV3Context.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals(gradleSnapshotHash, gradleMoveV3Context.getValue("snapshotHash").jsonPrimitive.content)
        gradleMoveBaseline = gradleMoveInputs()
    }

    @When("a Gradle moveClass preview is requested with {string} context authority")
    fun requestGradleMoveClassPreview(authority: String) {
        assertTrue(authority in setOf("current", "current-v3", "stale"))
        if (authority == "current-v3") gradleMoveContext = gradleMoveV3Context
        else gradleMoveContext = session!!.dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", gradleSnapshotHash)
            put("sdkHome", sdk.toString())
        }).jsonObject
        val expectedContextHash = if (authority in setOf("current", "current-v3")) {
            gradleMoveContext.getValue("contextHash").jsonPrimitive.content
        } else "0".repeat(64)
        try {
            gradleMovePreview = session!!.dispatch("refactor.preview", buildJsonObject {
                put("operation", "moveClass")
                put("languageId", "java")
                put("symbol", "pkg.Runner")
                put("expectedSnapshotHash", gradleSnapshotHash)
                put("javaContext", buildJsonObject {
                    put("sdkHome", sdk.toString())
                    put("expectedContextHash", expectedContextHash)
                    put("contextSchemaVersion", gradleMoveContext.getValue("schemaVersion").jsonPrimitive.content)
                })
                put("arguments", buildJsonObject { put("targetPackage", "moved.pkg") })
            }).jsonObject
        } catch (refused: JsonRpcException) {
            gradleMoveFailure = refused
        }
    }

    @Then("the moveClass preview reports {string} and {string} synchronized authority")
    fun gradleMoveClassPreviewAuthority(outcome: String, attestation: String) {
        if (outcome == "preview") {
            assertNull(gradleMoveFailure, gradleMoveFailure?.message)
            val result = assertNotNull(gradleMovePreview)
            assertEquals("PREVIEW", result.getValue("status").jsonPrimitive.content, result.toString())
            assertEquals("moveClass", result.getValue("operation").jsonPrimitive.content)
            assertEquals(gradleMoveContext.getValue("snapshotHash"), result.getValue("snapshot").jsonObject.getValue("hash"))
            assertTrue(result.getValue("snapshot").jsonObject.getValue("validatedOnApply").jsonPrimitive.boolean)
            assertEquals(gradleMoveContext.getValue("contextHash"), result.getValue("contextHash"))
            assertEquals(gradleMoveContext.getValue("schemaVersion").jsonPrimitive.content,
                result.getValue("contextSchemaVersion").jsonPrimitive.content)
            val platformIdentities = gradleMoveContext.getValue("sdk").jsonArray.map {
                it.jsonObject.getValue("identitySha256").jsonPrimitive.content
            }
            assertEquals(platformIdentities, result.getValue("platformIdentities").jsonArray.map { it.jsonPrimitive.content })
            val edits = result.getValue("structuredDiff").jsonArray.map { it.jsonObject }
            assertTrue(edits.any {
                it.getValue("type").jsonPrimitive.content == "renameFile" &&
                    it.getValue("path").jsonPrimitive.content == "app/src/main/java/pkg/Runner.java" &&
                    it.getValue("newPath").jsonPrimitive.content == "app/src/main/java/moved/pkg/Runner.java"
            }, edits.toString())
            assertEquals(
                if (attestation == "exact") gradleMoveContext.getValue("schemaVersion").jsonPrimitive.content else attestation,
                result.getValue("contextSchemaVersion").jsonPrimitive.content,
            )
            if (gradleMoveContext.getValue("schemaVersion").jsonPrimitive.content == "java-project-context-v3") {
                var schemaMismatch: JsonRpcException? = null
                try {
                    session!!.dispatch("refactor.apply", buildJsonObject {
                        put("planId", result.getValue("planId"))
                        put("expectedSnapshotHash", gradleSnapshotHash)
                        put("javaContext", buildJsonObject {
                            put("sdkHome", sdk.toString())
                            put("expectedContextHash", gradleMoveContext.getValue("contextHash").jsonPrimitive.content)
                            put("contextSchemaVersion", "java-project-context-v2")
                        })
                    })
                } catch (refused: JsonRpcException) { schemaMismatch = refused }
                gradleMoveApplySchemaRefusalCode = schemaMismatch?.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content
                assertEquals("java.context.contextStale", gradleMoveApplySchemaRefusalCode)
                assertEquals(gradleMoveBaseline, gradleMoveInputs())
                assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
            }
        } else {
            assertEquals("refused", outcome)
            assertNull(gradleMovePreview)
            assertNotNull(gradleMoveFailure)
            assertEquals(attestation, gradleMoveFailure!!.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content)
        }
        assertEquals(gradleMoveBaseline, gradleMoveInputs())
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
        writeGradleMoveClassEvidence()
    }

    private fun authority(other: Boolean = false) = buildJsonObject {
        put("sdkHome", sdk.toString()); put("expectedContextHash", if (other) "0".repeat(64) else context.getValue("contextHash").jsonPrimitive.content)
    }
    private fun proveSdkCurrencyUnderLock() {
        val checkRoot = owned.resolve("under-lock"); Files.createDirectories(checkRoot)
        baseline.forEach { (relative, content) -> val file = checkRoot.resolve(relative); Files.createDirectories(file.parent); Files.writeString(file, content) }
        val scanner = JavaProjectScanner(); val saved = scanner.scan(checkRoot)
        val admitted = JavaBoundContext.acquire(JavaProjectContextRequest(saved.hash, sdk), { scanner.scan(checkRoot) })
        val context = (admitted as JavaBoundContextResolution.Available).context
        val rawPlan = JavaOrganizeImportsPlanner().previewSingleFile(context.snapshot, Path.of("src/main/java/pkg/Runner.java"))
        val preview = context.attestPreview(rawPlan) { scanner.scan(checkRoot) }
        val release = Files.readString(sdk.resolve("release"))
        try {
            val engine = PatchEngine(checkRoot, faultInjector = PatchFaultInjector { point, _, _ ->
                if (point == PatchFaultPoint.BEFORE_AUTHORITY_LEASE_VALIDATION) Files.writeString(sdk.resolve("release"), release + "\nUNDER_LOCK_SDK_DRIFT=true\n")
            })
            val refused = engine.apply(preview.plan, saved, ApplyAuthorization.explicit("context-story"), preview.diagnosticsGate)
            assertIs<ApplyResult.Refused>(refused)
            assertTrue(refused.diagnostics.any { it.code == "diagnostics.unavailable" && it.details.fields["phase"] == "baseline" }, refused.toString())
            assertFalse(Files.exists(checkRoot.resolve(".refactorkit/transactions")))
            baseline.forEach { (relative, content) -> assertEquals(content, Files.readString(checkRoot.resolve(relative))) }
        } finally { Files.writeString(sdk.resolve("release"), release) }
        var commits = 0
        try {
            val engine = PatchEngine(checkRoot, faultInjector = PatchFaultInjector { point, _, _ ->
                if (point == PatchFaultPoint.AFTER_COMMITTED_IMAGE) {
                    commits++
                    Files.writeString(sdk.resolve("release"), if (commits == 1) release + "\nPOST_COMMIT_SDK_DRIFT=true\n" else release)
                }
            })
            val refused = engine.apply(preview.plan, scanner.scan(checkRoot), ApplyAuthorization.explicit("context-story"), preview.diagnosticsGate)
            assertIs<ApplyResult.Refused>(refused)
            assertTrue(refused.diagnostics.any { it.code == "authoritative.postApplyUnavailable" }, refused.toString())
            assertEquals(2, commits, "The committed modification must be compensated after SDK drift")
            baseline.forEach { (relative, content) -> assertEquals(content, Files.readString(checkRoot.resolve(relative))) }
        } finally { Files.writeString(sdk.resolve("release"), release) }
    }
    private fun write(relative: String, content: String) { val file = root.resolve(relative); Files.createDirectories(file.parent); Files.writeString(file, content) }
    private fun contents(): Map<String, String> = Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) && (it.toString().endsWith(".java") || it.fileName.toString() == "pom.xml") }.toList().associate { root.relativize(it).toString() to Files.readString(it) } }
    private fun gradleMoveInputs(): Map<String, String> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) && root.relativize(it).none { segment -> segment.toString() in setOf(".gradle", "build", "target") } }
            .filter { it.toString().endsWith(".java") || it.toString().endsWith(".gradle") || it.fileName.toString() == "gradle.properties" }
            .toList().associate { root.relativize(it).toString() to Files.readString(it) }
    }
    private fun writeGradleMoveClassEvidence() {
        val repository = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        val evidence = repository.resolve(
            ".refactorkit/runs/gradle-moveclass-context-preview-r001/responses/${if (gradleMoveFailure == null) "preview" else "stale"}.json",
        )
        Files.createDirectories(evidence.parent)
        val contextSchema = gradleMoveContext.getValue("schemaVersion").jsonPrimitive.content
        val responseName = when {
            gradleMoveFailure != null -> "stale"
            contextSchema == "java-project-context-v3" -> "preview-v3"
            else -> "preview"
        }
        Files.writeString(evidence.resolveSibling("$responseName.json"), buildJsonObject {
            put("projectContext", gradleMoveContext)
            put("preview", gradleMovePreview ?: JsonNull)
            put("refusalCode", gradleMoveFailure?.data?.jsonObject?.get("refusalCode") ?: JsonNull)
            put("applySchemaRefusalCode", gradleMoveApplySchemaRefusalCode?.let(::JsonPrimitive) ?: JsonNull)
        }.toString() + "\n")
    }
    @After
    fun close() { session?.close(); if (::owned.isInitialized) Files.walk(owned).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
}
