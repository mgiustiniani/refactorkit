package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.*
import kotlinx.serialization.json.*
import org.refactorkit.daemon.DaemonSession
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class JavaImmutableEditorOverlaySteps {
    private lateinit var root: Path
    private var session: DaemonSession? = null
    private lateinit var hash: String
    private lateinit var authority: JsonObject
    private lateinit var baseline: Map<String, String>
    private lateinit var response: JsonObject
    private lateinit var queryKind: String
    private val runnerPath = "src/main/java/pkg/Runner.java"
    private val consumerPath = "src/main/java/pkg/Consumer.java"
    private val childPath = "src/main/java/pkg/Child.java"
    private val dirtyRunner = "package pkg;\npublic class Runner { public static String pending() { return \"dirty\"; } }\n"
    private val dirtyConsumer = "package pkg;\npublic class Consumer { String result = Runner.pending(); }\n"
    private val navigationRunner = """
        package pkg;
        public class Runner {
            public static String pending() { return "dirty"; }
        }
    """.trimIndent() + "\n"
    private val navigationConsumer = """
        package pkg;
        public class Consumer {
            public String use() { return Runner.pending(); }
        }
    """.trimIndent() + "\n"
    private lateinit var navigationFixtures: Map<String, Pair<JsonObject, JsonObject>>
    @Given("saved Java sources and an explicit SDK context with two unsaved editor documents")
    fun fixture() {
        root = Files.createTempDirectory("rk-java-editor-overlay-")
        write("pom.xml", "<project><modelVersion>4.0.0</modelVersion><groupId>pkg</groupId><artifactId>app</artifactId><version>1</version><properties><maven.compiler.release>8</maven.compiler.release></properties></project>")
        write(runnerPath, "package pkg;\npublic class Runner { public static int existing() { return 1; } }\n")
        write(consumerPath, "package pkg;\npublic class Consumer { int result = Runner.existing(); }\n")
        write(childPath, "package pkg;\npublic class Child extends Runner {}\n")
        baseline = contents();session = DaemonSession();hash = session!!.dispatch("project.open", buildJsonObject { put("root", root.toString()) }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        val sdkHome = System.getProperty("java.home")
        val c = session!!.dispatch("project.context", buildJsonObject { put("expectedSnapshotHash", hash);put("sdkHome", sdkHome) }).jsonObject
        assertEquals("available", c.getValue("status").jsonPrimitive.content);authority = buildJsonObject { put("sdkHome", sdkHome);put("expectedContextHash", c.getValue("contextHash")) }
    }
    @When("Java {string} reads the immutable overlay after {string}")
    fun read(kind: String, event: String) {
        queryKind = kind
        if (event != "current") query("hover", 30, dirtyRunner)
        response = query(kind, if (event == "older version") 29 else 30, dirtyRunner + if (event == "same version changed") "// changed without new version\n" else "")
    }
    @Then("the Java overlay query reports {string} with exact document authority")
    fun outcome(expected: String) {
        if (expected != "ready") { assertEquals("refused", response.getValue("status").jsonPrimitive.content);assertEquals(expected, response.getValue("error").jsonObject.getValue("code").jsonPrimitive.content);return }
        assertEquals(expected, response.getValue("status").jsonPrimitive.content, response.toString())
        assertEquals(hash, response.getValue("snapshotHash").jsonPrimitive.content);assertEquals(authority.getValue("expectedContextHash"), response.getValue("contextHash"));assertNotEquals(hash, response.getValue("providerSnapshotHash").jsonPrimitive.content)
        val source = response.getValue("sourceAuthority").jsonObject;assertEquals("immutable-editor-overlay", source.getValue("kind").jsonPrimitive.content);assertEquals(hash, source.getValue("baseSnapshotHash").jsonPrimitive.content)
        assertEquals(2, source.getValue("documents").jsonArray.size);source.getValue("documents").jsonArray.forEach { assertEquals(30, it.jsonObject.getValue("version").jsonPrimitive.int) }
        when (queryKind) {
            "hover" -> assertTrue(response.getValue("contents").jsonArray.any { it.jsonObject.getValue("value").jsonPrimitive.content.contains("pending") }, response.toString())
            "definition" -> assertEquals(runnerPath, response.getValue("locations").jsonArray.single().jsonObject.getValue("path").jsonPrimitive.content)
            "references" -> assertTrue(response.getValue("references").jsonArray.any { it.jsonObject.getValue("path").jsonPrimitive.content == consumerPath })
            "documentSymbols" -> {
                assertTrue(response.getValue("items").jsonArray.any { it.toString().contains("pending") }, response.toString())
                val workspace = query("workspaceSymbols", 30, dirtyRunner);assertEquals("ready", workspace.getValue("status").jsonPrimitive.content);assertTrue(workspace.getValue("items").jsonArray.any { it.toString().contains("pending") })
            }
        }
        val replay = query(queryKind, 30, dirtyRunner);assertEquals(response.getValue("sourceAuthority"), replay.getValue("sourceAuthority"));assertEquals(response["provenanceHash"], replay["provenanceHash"])
    }
    @When("I query Java types and calls through the immutable editor overlay")
    fun queryNavigationThroughOverlay() {
        navigationFixtures = linkedMapOf(
            "typeDefinition" to navigationQuery(
                "typeDefinition", consumerPath, 2, navigationConsumer.lines()[2].indexOf("Runner") + 1,
            ),
            "typeHierarchy" to navigationQuery(
                "typeHierarchy", runnerPath, 1, navigationRunner.lines()[1].indexOf("Runner") + 1, "both",
            ),
            "callHierarchyIncoming" to navigationQuery(
                "callHierarchy", runnerPath, 2, navigationRunner.lines()[2].indexOf("pending") + 1, "incoming",
            ),
            "callHierarchyOutgoing" to navigationQuery(
                "callHierarchy", consumerPath, 2, navigationConsumer.lines()[2].indexOf("use") + 1, "outgoing",
            ),
        )
    }
    @Then("all three navigation queries resolve dirty source relationships with overlay provenance")
    fun verifyNavigationOverlay() {
        val responses = navigationFixtures.mapValues { it.value.second }
        responses.values.forEach { value ->
            assertEquals("ready", value.getValue("status").jsonPrimitive.content, value.toString())
            assertEquals(hash, value.getValue("snapshotHash").jsonPrimitive.content)
            assertEquals(authority.getValue("expectedContextHash"), value.getValue("contextHash"))
            assertNotEquals(hash, value.getValue("providerSnapshotHash").jsonPrimitive.content)
            assertEquals("java-jdt-bindings-v1", value.getValue("providerId").jsonPrimitive.content)
            assertEquals("compiler", value.getValue("evidence").jsonPrimitive.content)
            assertTrue(value.getValue("provenanceHash").jsonPrimitive.content.matches(Regex("[0-9a-f]{64}")))
            val source = value.getValue("sourceAuthority").jsonObject
            assertEquals("immutable-editor-overlay", source.getValue("kind").jsonPrimitive.content)
            assertEquals(hash, source.getValue("baseSnapshotHash").jsonPrimitive.content)
            assertEquals(2, source.getValue("documents").jsonArray.size)
            source.getValue("documents").jsonArray.forEach { assertEquals(41, it.jsonObject.getValue("version").jsonPrimitive.int) }
        }
        val definition = responses.getValue("typeDefinition")
        assertEquals("pkg.Runner", definition.getValue("target").jsonObject.getValue("id").jsonPrimitive.content)
        val hierarchy = responses.getValue("typeHierarchy")
        assertEquals("workspace-source", hierarchy.getValue("scope").jsonPrimitive.content)
        assertTrue(hierarchy.getValue("items").jsonArray.any {
            it.jsonObject.getValue("relation").jsonPrimitive.content == "subtype" &&
                it.jsonObject.getValue("symbol").jsonObject.getValue("id").jsonPrimitive.content == "pkg.Child"
        })
        val incoming = responses.getValue("callHierarchyIncoming")
        assertEquals("workspace-direct-invocations", incoming.getValue("scope").jsonPrimitive.content)
        assertEquals("partial", incoming.getValue("completeness").jsonPrimitive.content)
        assertTrue(incoming.getValue("items").jsonArray.any {
            it.jsonObject.getValue("method").jsonObject.getValue("id").jsonPrimitive.content == "pkg.Consumer#use()" &&
                it.jsonObject.getValue("callSites").jsonArray.isNotEmpty()
        })
        val outgoing = responses.getValue("callHierarchyOutgoing")
        assertTrue(outgoing.getValue("items").jsonArray.any {
            it.jsonObject.getValue("method").jsonObject.getValue("id").jsonPrimitive.content == "pkg.Runner#pending()" &&
                it.jsonObject.getValue("callSites").jsonArray.isNotEmpty()
        })
        val capabilities = session!!.dispatch("server.capabilities", buildJsonObject { }).jsonObject
        val queryMethod = capabilities.getValue("methods").jsonArray.map { it.jsonObject }.single {
            it.getValue("name").jsonPrimitive.content == "intelligence.query"
        }
        val featureKeys = queryMethod.getValue("features").jsonObject
        setOf(
            "typeDefinitionAtPosition", "javaSavedSnapshotTypeDefinition", "javaImmutableEditorOverlayTypeDefinition",
            "typeHierarchyAtPosition", "javaSavedSnapshotTypeHierarchy", "javaImmutableEditorOverlayTypeHierarchy",
            "callHierarchyAtPosition", "javaSavedSnapshotCallHierarchy", "javaImmutableEditorOverlayCallHierarchy",
        ).forEach { assertEquals(true, featureKeys.getValue(it).jsonPrimitive.boolean, it) }
        val javaAdapter = capabilities.getValue("languageKernel").jsonObject.getValue("adapters").jsonArray
            .map { it.jsonObject }.single { it.getValue("languageId").jsonPrimitive.content == "java" }
        val operations = javaAdapter.getValue("capabilities").jsonArray.map {
            it.jsonObject.getValue("operation").jsonPrimitive.content
        }.toSet()
        assertTrue(operations.containsAll(setOf("typeDefinition", "typeHierarchy", "callHierarchy")))
        val fixturePath = Path.of(System.getProperty("user.dir")).resolve("build/reports/cucumber/java-semantic-navigation-overlay-fixtures.json")
        Files.createDirectories(fixturePath.parent)
        Files.writeString(fixturePath, buildJsonObject {
            navigationFixtures.forEach { (name, fixture) -> put(name, buildJsonObject {
                put("request", fixture.first); put("response", fixture.second)
            }) }
        }.toString())
    }
    @Then("Java overlay analysis leaves saved sources and transaction state unchanged") fun unchanged() {
        val refused = assertFailsWith<org.refactorkit.core.JsonRpcException> {
            session!!.dispatch("refactor.preview", buildJsonObject {
                put("operation", "renameClass");put("languageId", "java");put("symbol", "pkg.Runner");put("javaContext", authority);put("expectedSnapshotHash", hash)
                put("sourceAuthority", buildJsonObject { put("kind", "immutable-editor-overlay");put("documents", buildJsonArray { }) })
                put("arguments", buildJsonObject { put("newName", "Worker") })
            })
        }
        assertEquals("java.context.dirtyMutationUnsupported", refused.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content)
        assertEquals(baseline, contents());assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
    }
    private fun query(kind: String, version: Long, runner: String): JsonObject = session!!.dispatch("intelligence.query", buildJsonObject {
        put("requestId", "overlay-$kind-$version");put("kind", kind);put("languageId", "java");put("expectedSnapshotHash", hash);put("javaContext", authority)
        put("path", if (kind == "documentSymbols" || kind == "workspaceSymbols") runnerPath else consumerPath)
        put("position", buildJsonObject { put("line", 1);put("character", dirtyConsumer.lines()[1].indexOf("pending") + 1) })
        put("sourceAuthority", buildJsonObject { put("kind", "immutable-editor-overlay");put("documents", buildJsonArray {
            add(buildJsonObject { put("path", runnerPath);put("version", version);put("content", runner) })
            add(buildJsonObject { put("path", consumerPath);put("version", version);put("content", dirtyConsumer) })
        }) })
    }).jsonObject
    private fun navigationQuery(
        kind: String,
        path: String,
        line: Int,
        character: Int,
        direction: String? = null,
    ): Pair<JsonObject, JsonObject> {
        val request = buildJsonObject {
            put("requestId", "overlay-navigation-$kind-$direction-$line")
            put("kind", kind); put("languageId", "java"); put("expectedSnapshotHash", hash)
            put("javaContext", authority); put("path", path)
            put("position", buildJsonObject { put("line", line); put("character", character) })
            direction?.let { put("direction", it) }
            put("sourceAuthority", buildJsonObject { put("kind", "immutable-editor-overlay"); put("documents", buildJsonArray {
                add(buildJsonObject { put("path", runnerPath); put("version", 41); put("content", navigationRunner) })
                add(buildJsonObject { put("path", consumerPath); put("version", 41); put("content", navigationConsumer) })
            }) })
        }
        return request to session!!.dispatch("intelligence.query", request).jsonObject
    }
    private fun write(path: String, text: String) { val file = root.resolve(path);Files.createDirectories(file.parent);Files.writeString(file, text) }
    private fun contents() = Files.walk(root).use { p -> p.filter { Files.isRegularFile(it) && (it.toString().endsWith(".java") || it.fileName.toString() == "pom.xml") }.toList().associate { root.relativize(it).toString() to Files.readString(it) } }
    @After fun close() { session?.close();if (::root.isInitialized) Files.walk(root).use { p -> p.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
}
