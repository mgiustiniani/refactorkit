package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.test.*

class JavaNativeDiagnosticOwnershipSteps {
    private val fixture = JavaNativeDiagnosticOwnershipFixture()
    @Given("native Java diagnostic ownership uses SDK {int} with {string}")
    fun capture(sdk: Int, profile: String) = fixture.capture(sdk, profile)
    @When("main and test compiler groups report against the same immutable editor sources")
    fun query() = fixture.query()
    @Then("only explicitly owned compiler errors are published with their original ranges")
    fun verify() = fixture.verify()
    @Then("source ownership qualification keeps independent errors and preserves captured inputs")
    fun intact() = fixture.verifyInputs()
    @After fun close() = fixture.close()
}

/** Scenario-owned compiler inputs; all expected spans are authored source tokens. */
private class JavaNativeDiagnosticOwnershipFixture : AutoCloseable {
    private lateinit var owned: Path
    private lateinit var workspace: Path
    private lateinit var sdk: Path
    private lateinit var baseline: Map<String, String>
    private lateinit var sdkBaseline: Map<String, String>
    private lateinit var context: JsonObject
    private lateinit var params: JsonObject
    private var native: NativeJavaDaemonRpcSession? = null
    private var level = 0
    private var profile = ""
    private val stages = mutableListOf<Triple<Map<String, String>, JsonObject, Int>>()
    private val mainPath = "src/main/java/Main.java"
    private val helperPath = "src/main/java/Helper.java"
    private val closedPath = "src/test/java/Closed.java"
    private val testOnlyPath = "src/test/java/TestOnly.java"
    private val main = "class Main { int use = Helper.value(); }\n"
    private val closed = "class Closed { int use = Helper.value(); TestOnly scoped; }\n"
    private val closedBad = "class Closed { Unknown missing; int use = Helper.value(); TestOnly scoped; }\n"

    private fun helper(result: String): String = "/* 💡 owned */ class Helper { static int value() { return $result; }" +
        (if (profile == "repeated errors") " static int other() { return $result; }" else "") + " }\n"

    fun capture(value: Int, mode: String) {
        require(value in setOf(25, 26) && mode in setOf("release", "source-target", "repeated errors"))
        level = value; profile = mode
        sdk = if (level == 26) optionalNativeJavaSdkHome("REFACTORKIT_JDK26_HOME")
            else Path.of(requireNotNull(System.getenv("REFACTORKIT_ENTRYPOINT_SDK25_HOME"))).toRealPath()
        owned = Files.createTempDirectory("rk-native-diagnostic-owner-")
        workspace = Files.createDirectory(owned.resolve("workspace"))
        val properties = if (profile == "source-target") "<maven.compiler.source>$level</maven.compiler.source><maven.compiler.target>$level</maven.compiler.target>"
            else "<maven.compiler.release>$level</maven.compiler.release>"
        write(workspace, "pom.xml", "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>app</artifactId><version>1</version><properties>$properties</properties><build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin></plugins></build></project>\n")
        for ((path, text) in mapOf(mainPath to main, helperPath to helper("0"), closedPath to closed, testOnlyPath to "class TestOnly {}\n")) write(workspace, path, text)
        native = NativeJavaDaemonRpcSession(workspace)
        val process = requireNotNull(native)
        val snapshot = process.dispatch("project.open", buildJsonObject { put("root", workspace.toString()) }).getValue("snapshotHash")
        val summary = process.dispatch("project.summary", buildJsonObject {})
        val model = summary.getValue("buildModels").jsonArray.map { it.jsonObject }.single { it.getValue("providerId").jsonPrimitive.content == "maven-effective-v1" }
        val module = model.getValue("modules").jsonArray.single().jsonObject.getValue("id")
        context = process.dispatch("project.context.v7", buildJsonObject {
            put("sdkHome", sdk.toString()); put("expectedSnapshotHash", snapshot)
            put("providerId", "maven-effective-v1"); put("moduleId", module); put("sourceSetId", "main")
        })
        assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
        assertEquals("java-project-context-v7", context.getValue("schemaVersion").jsonPrimitive.content)
        val generation = process.dispatch("index.status", buildJsonObject {}).getValue("generation")
        params = buildJsonObject {
            put("requestId", "native-diagnostic-owner"); put("kind", "diagnostics"); put("languageId", "java"); put("priority", "interactive")
            put("path", mainPath); put("limit", 500); put("expectedSnapshotHash", snapshot); put("expectedIndexGeneration", generation)
            put("javaContext", buildJsonObject {
                put("sdkHome", sdk.toString()); put("expectedContextHash", context.getValue("contextHash")); put("contextSchemaVersion", context.getValue("schemaVersion"))
            })
        }
        baseline = inventory(workspace); sdkBaseline = sdkImages()
    }

    fun query() {
        val values = listOf(
            mapOf(helperPath to helper("\"bad-value\""), closedPath to closedBad),
            mapOf(helperPath to helper("0"), closedPath to closed),
            mapOf(helperPath to "class Helper { TestOnly forbidden; static int value() { return 0; } }\n", closedPath to closed),
            mapOf(helperPath to helper("0"), closedPath to closed),
        )
        for ((index, documents) in values.withIndex()) {
            val expected = when (index) { 0 -> if (profile == "repeated errors") 3 else 2; 2 -> 1; else -> 0 }
            oracle(index, documents, expected)
            val frame = buildJsonObject {
                put("kind", "immutable-editor-overlay")
                put("documents", buildJsonArray { for ((path, text) in (mapOf(mainPath to main) + documents)) add(buildJsonObject {
                    put("path", path); put("content", text); put("version", 7 + index)
                }) })
            }
            val request = JsonObject(params + ("sourceAuthority" to frame))
            val result = requireNotNull(native).dispatch("intelligence.query", request)
            assertEquals("ready", result.getValue("status").jsonPrimitive.content, "Fixture query must be valid before behavior checks: $result")
            stages += Triple(documents, result, expected)
        }
    }

    private fun oracle(index: Int, documents: Map<String, String>, expected: Int) {
        val root = Files.createDirectory(owned.resolve("oracle-$index"))
        val files = mapOf(mainPath to main, helperPath to requireNotNull(documents[helperPath]), closedPath to requireNotNull(documents[closedPath]), testOnlyPath to "class TestOnly {}\n")
        for ((path, text) in files) write(root, path, text)
        val classes = Files.createDirectory(root.resolve("classes"))
        val paths = files.keys.filter { index != 2 || it.startsWith("src/main/") }
        val flags = if (profile == "source-target") listOf("-source", "$level", "-target", "$level") else listOf("--release", "$level")
        val args = listOf(sdk.resolve("bin/javac").toString(), "-proc:none", "-implicit:none", "-XDrawDiagnostics", "-encoding", "UTF-8") + flags +
            listOf("-sourcepath", root.resolve("src/main/java").toString(), "-d", classes.toString()) + paths.map { root.resolve(it).toString() }
        val log = root.resolve("javac.log")
        val compiler = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        if (!compiler.waitFor(30, TimeUnit.SECONDS)) { compiler.destroyForcibly(); compiler.waitFor(); error("Independent javac timed out") }
        val text = Files.readString(log)
        assertEquals(if (expected == 0) 0 else 1, compiler.exitValue(), text)
        val errors = text.lines().filter { it.contains(": compiler.err.") }
        assertEquals(expected, errors.size, text)
        if (index == 0) {
            assertEquals(expected - 1, errors.count { it.startsWith("Helper.java:") && it.contains("compiler.err.prob.found.req") }, text)
            assertEquals(1, errors.count { it.startsWith("Closed.java:") && it.contains("compiler.err.cant.resolve.location") }, text)
        }
        if (index == 2) assertTrue(text.contains("TestOnly"), text)
    }

    fun verify() {
        assertEquals(4, stages.size)
        for ((index, stage) in stages.withIndex()) {
            val (documents, response, expected) = stage
            assertEquals("ready", response.getValue("status").jsonPrimitive.content, response.toString())
            assertTrue(response.getValue("complete").jsonPrimitive.boolean)
            assertEquals("compiler-errors", response.getValue("coverage").jsonPrimitive.content)
            assertEquals("java-sdk-compiler-diagnostics-v1", response.getValue("providerId").jsonPrimitive.content)
            assertEquals("jdk$level-javac-compiler-errors-v1", response.getValue("backend").jsonPrimitive.content)
            assertEquals(context.getValue("contextHash"), response.getValue("contextHash"))
            assertEquals(context.getValue("schemaVersion"), response.getValue("contextSchemaVersion"))
            assertEquals(4, response.getValue("analyzedSourceCount").jsonPrimitive.int)
            val items = response.getValue("items").jsonArray.map { it.jsonObject }
            assertEquals(expected, response.getValue("total").jsonPrimitive.int, response.toString())
            assertEquals(expected, items.size, response.toString())
            for (item in items) assertEquals("error", item.getValue("severity").jsonPrimitive.content)
            if (index == 0) {
                val helperErrors = items.filter { it.getValue("location").jsonObject.getValue("path").jsonPrimitive.content == helperPath }
                assertEquals(expected - 1, helperErrors.size)
                val text = documents.getValue(helperPath)
                val starts = Regex(Regex.escape("\"bad-value\"")).findAll(text).map { it.range.first }.toSet()
                val actual = helperErrors.map { item ->
                    assertEquals("java.javac.compiler.err.prob.found.req", item.getValue("code").jsonPrimitive.content)
                    val range = item.getValue("location").jsonObject.getValue("range").jsonObject
                    val start = range.getValue("start").jsonObject; val end = range.getValue("end").jsonObject
                    assertEquals(0, start.getValue("line").jsonPrimitive.int); assertEquals(0, end.getValue("line").jsonPrimitive.int)
                    assertEquals(11, end.getValue("character").jsonPrimitive.int - start.getValue("character").jsonPrimitive.int)
                    start.getValue("character").jsonPrimitive.int
                }.toSet()
                assertEquals(starts, actual, "Independent equal-message errors retain each explicit UTF16 range")
                val closedError = items.single { it.getValue("location").jsonObject.getValue("path").jsonPrimitive.content == closedPath }
                assertEquals("java.javac.compiler.err.cant.resolve.location", closedError.getValue("code").jsonPrimitive.content)
            }
            if (index == 2) {
                assertEquals(helperPath, items.single().getValue("location").jsonObject.getValue("path").jsonPrimitive.content)
                assertTrue(items.single().getValue("message").jsonPrimitive.content.contains("TestOnly"))
            }
        }
        println("NATIVE_DIAGNOSTIC_SOURCE_OWNERSHIP SDK=$level profile=$profile stages=4")
    }

    fun verifyInputs() {
        assertEquals(baseline, inventory(workspace)); assertEquals(sdkBaseline, sdkImages())
        assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        closeNative()
    }
    private fun closeNative() { val active = native; native = null; active?.close() }
    private fun write(root: Path, relative: String, text: String) { val p = root.resolve(relative); Files.createDirectories(p.parent); Files.writeString(p, text) }
    private fun hash(path: Path) = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
    private fun inventory(root: Path): Map<String, String> = Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).toList().associate { root.relativize(it).toString() to hash(it) } }
    private fun sdkImages() = listOf("release", "bin/java", "bin/javac", "lib/ct.sym").associateWith { hash(sdk.resolve(it)) }
    override fun close() { closeNative(); if (::owned.isInitialized) owned.toFile().deleteRecursively() }
}
