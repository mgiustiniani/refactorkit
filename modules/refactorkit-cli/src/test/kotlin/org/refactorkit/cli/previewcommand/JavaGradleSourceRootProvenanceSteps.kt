package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.When
import io.cucumber.java.en.Then
import kotlinx.serialization.json.*
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.TextEdits
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import kotlin.test.*

class JavaGradleSourceRootProvenanceSteps {
    private val fixture = JavaGradleSourceRootProvenanceFixture()
    @Given("an evaluated Gradle Java editor fixture selects {string} with {string} source roots")
    fun capture(sdk: String, profile: String) = fixture.capture(sdk, profile)
    @When("compiler diagnostics are requested for its exact Gradle editor frame")
    fun query() = fixture.query()
    @Then("evaluated Gradle roots and diagnostics report {string} with native owner provenance")
    fun outcome(expected: String) = fixture.verify(expected)
    @Then("the Gradle editor query preserves captured inputs without compiling or evaluating the build again")
    fun intact() = fixture.verifyInputs()
    @After
    fun close() = fixture.close()
}

private class JavaGradleSourceRootProvenanceFixture : AutoCloseable {
    private lateinit var root: Path
    private lateinit var native: NativeJavaDaemonRpcSession
    private lateinit var sdk: Path
    private lateinit var gradle: Path
    private lateinit var profile: String
    private lateinit var context: JsonObject
    private lateinit var params: JsonObject
    private lateinit var response: JsonObject
    private lateinit var inputs: Map<String, String>
    private lateinit var sdkInputs: Map<Path, String>
    private lateinit var gradleSha: String
    private val mainPath = "src/main/java/demo/Runner.java"
    private val helperPath = "src/main/java/demo/Helper.java"
    private val mainDraft = "package demo; /* 💡 */ class Runner { String value = java.util.List.of(Helper.newMessage()).get(0); }\n"
    private val helperDraft = "package demo; class Helper { int broken = \"wrong\"; static String newMessage() { return \"draft\"; } }\n"

    fun capture(sdkName: String, profile: String) {
        require(sdkName in setOf("SDK21", "SDK25"))
        require(profile in setOf("source8", "release8", "custom and generated", "changed build receipt"))
        this.profile = profile
        sdk = Path.of(if (sdkName == "SDK25") requireNotNull(System.getenv("REFACTORKIT_ENTRYPOINT_SDK25_HOME"))
            else System.getProperty("java.home")).toRealPath()
        gradle = Path.of(System.getenv("REFACTORKIT_GRADLE_9_8_1_EXECUTABLE")
            ?: "/tmp/ide-gradle-9.8.1/gradle-9.8.1/bin/gradle").toRealPath()
        assertTrue(Files.isExecutable(gradle), "The exact configured Gradle9.8.1 is required")
        root = Files.createTempDirectory("rk-gradle-editor-roots-")
        write("settings.gradle", "rootProject.name='editor-roots'\n")
        write("gradle.properties", "org.gradle.jvmargs=-Xmx256m\n")
        val release = if (profile == "release8") "options.release = 8" else ""
        val extra = if (profile == "custom and generated") "sourceSets.main.java.srcDir('extra/java')" else ""
        write("build.gradle", """plugins { id 'java' }
java { toolchain { languageVersion = JavaLanguageVersion.of(${if (sdkName == "SDK25") 25 else 21}) } }
tasks.withType(JavaCompile).configureEach { sourceCompatibility = '1.8'; targetCompatibility = '1.8'; $release }
$extra
def receipt = file('.refactorkit/probe/metadata-evaluations.txt')
receipt.parentFile.mkdirs()
receipt.text = ((receipt.exists() ? receipt.text.trim().toInteger() : 0) + 1).toString()
gradle.taskGraph.whenReady { graph ->
    if (graph.allTasks.any { it instanceof JavaCompile }) throw new GradleException('Compilation is forbidden in this metadata fixture')
    file('.refactorkit/probe/task-paths.txt').text = graph.allTasks.collect { it.path }.join('\n')
}
""")
        write(mainPath, "package demo; class Runner { String value = Helper.oldMessage(); }\n")
        write(helperPath, "package demo; class Helper { static String oldMessage() { return \"saved\"; } }\n")
        if (profile == "custom and generated") {
            write("extra/java/demo/Extra.java", "package demo; class Extra {}\n")
            write("build/generated/sources/annotationProcessor/java/main/demo/Generated.java", "package demo; class Generated {}\n")
        }
        inputs = sourceInputs()
        sdkInputs = listOf("release", "lib/ct.sym", "lib/modules", "lib/jrt-fs.jar", "lib/src.zip")
            .associate { sdk.resolve(it) to sha(sdk.resolve(it)) }
        gradleSha = sha(gradle)
        native = NativeJavaDaemonRpcSession(root)
        val opened = native.dispatch("project.open", buildJsonObject { put("root", root.toString()) })
        val sync = native.dispatch("project.sync", buildJsonObject {
            put("buildSystem", "gradle"); put("expectedSnapshotHash", opened.getValue("snapshotHash"))
            put("sdkHome", sdk.toString()); put("gradleRuntimeHome", System.getProperty("java.home"))
            put("gradleExecutable", gradle.toString()); put("gradleUserHome", root.resolve(".refactorkit/gradle-home").toString())
            put("allowBuildExecution", true)
        })
        assertEquals("synchronized", sync.getValue("status").jsonPrimitive.content, sync.toString())
        assertEquals("9.8.1", sync.getValue("gradleVersion").jsonPrimitive.content)
        val snapshot = sync.getValue("snapshotHash")
        context = native.dispatch("project.context", buildJsonObject { put("expectedSnapshotHash", snapshot); put("sdkHome", sdk.toString()) })
        assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
        assertEquals("java-project-context-v2", context.getValue("schemaVersion").jsonPrimitive.content)
        val generation = native.dispatch("index.status", buildJsonObject {}).getValue("generation")
        params = buildJsonObject {
            put("requestId", "gradle-editor-roots"); put("kind", "diagnostics"); put("languageId", "java")
            put("path", mainPath); put("limit", 500); put("expectedSnapshotHash", snapshot); put("expectedIndexGeneration", generation)
            put("javaContext", buildJsonObject { put("sdkHome", sdk.toString()); put("expectedContextHash", context.getValue("contextHash")); put("contextSchemaVersion", context.getValue("schemaVersion")) })
            put("sourceAuthority", buildJsonObject {
                put("kind", "immutable-editor-overlay")
                put("documents", buildJsonArray { for ((path, text) in listOf(mainPath to mainDraft, helperPath to helperDraft)) add(buildJsonObject {
                    put("path", path); put("version", 7); put("content", text)
                }) })
            })
        }
        assertEquals(inputs, sourceInputs())
    }

    fun query() {
        if (profile == "changed build receipt") {
            write("build.gradle", inputs.getValue("build.gradle") + "\n// caller build edit\n")
            inputs = sourceInputs()
        }
        response = native.dispatch("intelligence.query", params)
    }

    fun verify(expected: String) {
        val sets = context.getValue("modules").jsonArray.single().jsonObject.getValue("sourceSets").jsonArray.map { it.jsonObject }
        assertEquals(setOf("main", "test"), sets.map { it.getValue("id").jsonPrimitive.content }.toSet())
        for (set in sets) {
            assertEquals("complete", set.getValue("compilerOptionsAvailability").jsonPrimitive.content)
            val roots = set.getValue("sourceRoots").jsonArray.map { it.jsonPrimitive.content }.toSet()
            val generated = set.getValue("generatedSourceRoots").jsonArray.map { it.jsonPrimitive.content }.toSet()
            val evidence = set.getValue("sourceRootEvidence").jsonArray.map { it.jsonObject }
            assertEquals(roots, evidence.map { it.getValue("path").jsonPrimitive.content }.toSet(), "Evaluated roots require exact captured owner evidence")
            assertEquals(roots.size, evidence.size)
            assertTrue(generated.isNotEmpty())
            val id = set.getValue("id").jsonPrimitive.content
            for (row in evidence) {
                assertEquals("complete", row.getValue("availability").jsonPrimitive.content)
                assertEquals("gradle-effective-v1", row.getValue("providerId").jsonPrimitive.content)
                assertEquals("java", row.getValue("pluginId").jsonPrimitive.content)
                assertEquals(if (id == "main") ":compileJava" else ":compileTestJava", row.getValue("executionId").jsonPrimitive.content)
                assertEquals(if (row.getValue("path").jsonPrimitive.content in generated) "generated" else "build-plugin", row.getValue("origin").jsonPrimitive.content)
            }
        }
        if (profile == "custom and generated") assertTrue(sets.single { it.getValue("id").jsonPrimitive.content == "main" }
            .getValue("sourceRoots").jsonArray.any { it.jsonPrimitive.content == "extra/java" })
        if (expected.startsWith("java.") || expected.startsWith("intelligence.")) {
            assertEquals("refused", response.getValue("status").jsonPrimitive.content, response.toString())
            assertEquals(expected, response.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            assertFalse("items" in response); assertFalse("complete" in response)
            return
        }
        assertEquals("ready", response.getValue("status").jsonPrimitive.content, response.toString())
        assertEquals(true, response.getValue("complete").jsonPrimitive.boolean)
        assertEquals("compiler-errors", response.getValue("coverage").jsonPrimitive.content)
        assertEquals(if (profile == "custom and generated") 4 else 2, response.getValue("analyzedSourceCount").jsonPrimitive.int)
        val items = response.getValue("items").jsonArray.map { it.jsonObject }
        assertEquals(if (profile == "release8") 2 else 1, items.size)
        assertEquals(items.size, response.getValue("total").jsonPrimitive.int)
        val drafts = mapOf(mainPath to mainDraft, helperPath to helperDraft)
        for (item in items) {
            assertEquals("error", item.getValue("severity").jsonPrimitive.content)
            val location = item.getValue("location").jsonObject
            val path = location.getValue("path").jsonPrimitive.content
            val range = location.getValue("range").jsonObject
            fun offset(point: JsonObject) = TextEdits.offsetOf(drafts.getValue(path), SourcePosition(point.getValue("line").jsonPrimitive.int, point.getValue("character").jsonPrimitive.int))
            val token = drafts.getValue(path).substring(offset(range.getValue("start").jsonObject), offset(range.getValue("end").jsonObject))
            assertTrue(if (path == helperPath) token.contains("wrong") else path == mainPath && token.contains("of"), token)
        }
        assertTrue(items.any { it.getValue("location").jsonObject.getValue("path").jsonPrimitive.content == helperPath })
        assertEquals(params.getValue("expectedSnapshotHash"), response.getValue("snapshotHash"))
        assertEquals(context.getValue("contextHash"), response.getValue("contextHash"))
        assertEquals(context.getValue("schemaVersion"), response.getValue("contextSchemaVersion"))
        val documents = response.getValue("sourceAuthority").jsonObject.getValue("documents").jsonArray.map { it.jsonObject }
        assertEquals(drafts.keys, documents.map { it.getValue("path").jsonPrimitive.content }.toSet())
        for (document in documents) {
            assertEquals(7, document.getValue("version").jsonPrimitive.int)
            assertEquals(digest(drafts.getValue(document.getValue("path").jsonPrimitive.content).toByteArray()), document.getValue("contentSha256").jsonPrimitive.content)
        }
    }

    fun verifyInputs() {
        assertEquals(inputs, sourceInputs())
        assertEquals("1", Files.readString(root.resolve(".refactorkit/probe/metadata-evaluations.txt")))
        assertEquals(":refactorkitEffectiveContext_v1", Files.readString(root.resolve(".refactorkit/probe/task-paths.txt")))
        assertEquals(sdkInputs, sdkInputs.keys.associateWith(::sha))
        assertEquals(gradleSha, sha(gradle))
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
        assertFalse(Files.exists(root.resolve("build/classes")))
    }
    private fun sourceInputs() = Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) &&
        (it.toString().endsWith(".java") || it.fileName.toString() in setOf("build.gradle", "settings.gradle", "gradle.properties"))
    }.toList().associate { root.relativize(it).toString() to Files.readString(it) } }
    private fun write(path: String, text: String) { val file = root.resolve(path); Files.createDirectories(file.parent); Files.writeString(file, text) }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun sha(path: Path): String {
        val hash = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input -> val buffer = ByteArray(65536); while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) } }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    override fun close() {
        if (::native.isInitialized) native.close()
        if (::root.isInitialized) Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}
