package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.*
import kotlinx.serialization.json.*
import org.refactorkit.core.JsonRpcException
import org.refactorkit.daemon.DaemonSession
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class JavaCursorMutationSelectionSteps {
    private lateinit var root: Path
    private var session: DaemonSession? = null
    private lateinit var hash: String
    private lateinit var authority: JsonObject
    private lateinit var before: Map<String, String>
    private lateinit var query: JsonObject
    private var preview: JsonObject? = null
    private var applied: JsonObject? = null
    private var code: String? = null
    private lateinit var target: String
    private val sdkHome: String get() = System.getenv("REFACTORKIT_JAVA_QUALIFICATION_SDK") ?: System.getProperty("java.home")
    private var constructorEvent = ""
    private val runner = "src/main/java/pkg/Runner.java"
    private val caller = "src/main/java/pkg/Consumer.java"
    @Given("saved Java cursor fixtures with an explicit SDK and overloaded method calls")
    fun fixture() {
        root = Files.createTempDirectory("rk-java-cursor-")
        write("pom.xml", "<project><modelVersion>4.0.0</modelVersion><groupId>pkg</groupId><artifactId>app</artifactId><version>1</version><properties><maven.compiler.release>8</maven.compiler.release></properties></project>")
        write("src/main/java/pkg/Runner.java", "package pkg;\npublic class Runner {\n public int count;\n public String call(String value) { return value; }\n public int call(int value) { return value; }\n}\n")
        write(caller, "package pkg;\npublic class Consumer {\n Runner runner = new Runner();\n int amount = runner.count;\n String text = runner.call(\"value\");\n int number = runner.call(2);\n}\n")
        before = contents();session = DaemonSession();hash = session!!.dispatch("project.open", buildJsonObject { put("root", root.toString()) }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        val c = session!!.dispatch("project.context", buildJsonObject { put("expectedSnapshotHash", hash);put("sdkHome", sdkHome) }).jsonObject
        assertEquals("available", c.getValue("status").jsonPrimitive.content);authority = buildJsonObject { put("sdkHome", sdkHome);put("expectedContextHash", c.getValue("contextHash")) }
    }
    @Given("saved Java constructor cursor fixtures with an explicit SDK and overloaded calls")
    fun constructorFixture() {
        root = Files.createTempDirectory("rk-java-constructor-cursor-")
        write("pom.xml", "<project><modelVersion>4.0.0</modelVersion><groupId>pkg</groupId><artifactId>app</artifactId><version>1</version><properties><maven.compiler.release>8</maven.compiler.release></properties></project>")
        write(runner, "package pkg;\npublic class Runner {\n public int count;\n public Runner(String text, int amount) { count = amount; }\n public Runner(boolean flag) { count = flag ? 1 : 0; }\n}\n")
        write(caller, "package pkg;\npublic class Consumer {\n Runner created = new Runner(\"value\", 2);\n Runner unrelated = new Runner(false);\n}\n")
        before = contents(); session = DaemonSession()
        hash = session!!.dispatch("project.open", buildJsonObject { put("root", root.toString()) }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        val base = session!!.dispatch("project.context", buildJsonObject { put("expectedSnapshotHash", hash); put("sdkHome", sdkHome) }).jsonObject
        assertEquals("available", base.getValue("status").jsonPrimitive.content, base.toString())
        val module = base.getValue("modules").jsonArray.map { it.jsonObject }
            .single { it.getValue("providerId").jsonPrimitive.content == "maven-effective-v1" }.getValue("id")
        val context = session!!.dispatch("project.context.v7", buildJsonObject {
            put("expectedSnapshotHash", hash); put("sdkHome", sdkHome); put("providerId", "maven-effective-v1")
            put("moduleId", module); put("sourceSetId", "main")
        }).jsonObject
        assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
        assertEquals("java-project-context-v7", context.getValue("schemaVersion").jsonPrimitive.content)
        context.getValue("sdk").jsonArray.forEach { platform ->
            assertEquals(Path.of(sdkHome).toRealPath(), Path.of(platform.jsonObject.getValue("home").jsonPrimitive.content).toRealPath())
            assertEquals(8, platform.jsonObject.getValue("sourceRelease").jsonPrimitive.int)
        }
        authority = buildJsonObject { put("sdkHome", sdkHome); put("expectedContextHash", context.getValue("contextHash")); put("contextSchemaVersion", "java-project-context-v7") }
    }

    private fun selectConstructor(event: String) {
        constructorEvent = event
        val line = before.getValue(runner).lines().indexOfFirst { it.contains("Runner(String text") }
        val column = before.getValue(runner).lines()[line].indexOf("Runner") + 1
        query = session!!.dispatch("intelligence.query", buildJsonObject {
            put("requestId", "constructor-cursor"); put("kind", "mutationSelection"); put("languageId", "java"); put("path", runner)
            put("position", buildJsonObject { put("line", line); put("character", column) }); put("expectedSnapshotHash", hash)
            put("javaContext", authority); put("sourceAuthority", buildJsonObject { put("kind", "saved-snapshot") })
        }).jsonObject
        if (query.getValue("status").jsonPrimitive.content != "ready") { code = query.getValue("error").jsonObject.getValue("code").jsonPrimitive.content; return }
        val operation = when (event) {
            "rename parameter" -> "changeSignature.renameParameter"; "add parameter" -> "changeSignature.addParameter"
            "reorder parameters" -> "changeSignature.reorderParameters"; "remove parameter" -> "changeSignature.removeParameter"
            "change type" -> "changeSignature.changeParameterType"; "incompatible rename" -> "renameMember"
            else -> error("Unexpected constructor event: $event")
        }
        val args = buildJsonObject {
            put("acceptExternalConsumerRisk", true)
            when (event) {
                "rename parameter" -> { put("oldName", "amount"); put("newName", "total") }
                "add parameter" -> { put("type", "boolean"); put("name", "enabled"); put("default", "true") }
                "reorder parameters" -> put("order", "amount,text")
                "remove parameter" -> put("name", "text")
                "change type" -> { put("name", "text"); put("type", "Object") }
                "incompatible rename" -> put("newName", "Changed")
            }
        }
        try {
            preview = session!!.dispatch("refactor.preview", buildJsonObject {
                put("operation", operation); put("languageId", "java"); put("expectedSnapshotHash", hash)
                put("javaContext", authority); put("javaSelection", query.getValue("selection")); put("arguments", args)
            }).jsonObject
            assertEquals("PREVIEW", preview!!.getValue("status").jsonPrimitive.content, preview.toString())
            assertTrue(preview!!.getValue("renderedDiff").jsonPrimitive.content.contains("Runner"))
            assertEquals(before, contents()); assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
            applied = session!!.dispatch("refactor.apply", buildJsonObject {
                put("planId", preview!!.getValue("planId")); put("javaContext", authority); put("expectedSnapshotHash", hash)
            }).jsonObject
        } catch (failure: JsonRpcException) {
            code = failure.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content ?: "untyped-refusal"
        }
    }

    @When("the {string} cursor requests a mutation selector before {string}")
    fun select(selected: String, event: String) {
        target = selected
        if (selected == "constructor") { selectConstructor(event); return }
        val line = when (selected) { "class" -> 2; "method" -> 4; "field" -> 3; else -> 1 }
        val column = when (selected) { "class" -> 2; "method" -> before.getValue(caller).lines()[line].indexOf("call") + 1; "field" -> before.getValue(caller).lines()[line].indexOf("count") + 1; else -> 0 }
        query = session!!.dispatch("intelligence.query", buildJsonObject { put("requestId", "cursor");put("kind", "mutationSelection");put("languageId", "java");put("path", caller);put("position", buildJsonObject { put("line", line);put("character", column) });put("expectedSnapshotHash", hash);put("javaContext", authority);put("sourceAuthority", buildJsonObject { put("kind", "saved-snapshot") }) }).jsonObject
        if (query.getValue("status").jsonPrimitive.content != "ready") { code = query.getValue("error").jsonObject.getValue("code").jsonPrimitive.content;return }
        var selection = query.getValue("selection").jsonObject
        if (event == "tampered selector") selection = JsonObject(selection + ("selectorHash" to JsonPrimitive("0".repeat(64))))
        if (event == "source changed") { write(caller, before.getValue(caller) + "// later source version\n");before = contents() }
        try {
            preview = session!!.dispatch("refactor.preview", buildJsonObject { put("operation", if (selected == "class") "renameClass" else "renameMember");put("languageId", "java");put("expectedSnapshotHash", hash);put("javaContext", authority);put("javaSelection", selection);put("arguments", buildJsonObject { put("newName", when (selected) { "class" -> "Worker"; "method" -> "invoke"; else -> "total" }) }) }).jsonObject
            applied = session!!.dispatch("refactor.apply", buildJsonObject { put("planId", preview!!.getValue("planId"));put("javaContext", authority);put("expectedSnapshotHash", hash) }).jsonObject
        } catch (failure: JsonRpcException) { code = failure.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content ?: "untyped-refusal" }
    }
    @Then("the cursor mutation reports {string} using exact SDK binding identity")
    fun outcome(expected: String) {
        if (expected != "applied") { assertEquals(expected, code);assertNull(applied);return }
        assertNull(code);assertEquals("ready", query.getValue("status").jsonPrimitive.content);assertEquals(expected, applied?.getValue("status")?.jsonPrimitive?.content)
        val selected = query.getValue("selection").jsonObject
        assertEquals("java-mutation-selection-v1", selected.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals(hash, selected.getValue("snapshotHash").jsonPrimitive.content);assertEquals(authority.getValue("expectedContextHash"), selected.getValue("contextHash"))
        assertEquals(when (target) { "constructor" -> "pkg.Runner#<init>(java.lang.String,int)"; "class" -> "pkg.Runner"; "method" -> "pkg.Runner#call(java.lang.String)"; else -> "pkg.Runner#count" }, selected.getValue("symbolId").jsonPrimitive.content)
        if (target == "constructor") {
            assertEquals("constructor", selected.getValue("symbolKind").jsonPrimitive.content)
            assertEquals(runner, selected.getValue("declaration").jsonObject.getValue("path").jsonPrimitive.content)
            assertTrue(selected.getValue("bindingKey").jsonPrimitive.content.isNotBlank())
        }
        assertEquals("compiler", query.getValue("evidence").jsonPrimitive.content)
        assertEquals(query.getValue("contextHash"), preview?.get("contextHash"))
        assertEquals(selected.getValue("selectorHash"), preview?.get("selectionHash"))
        assertEquals(selected.getValue("selectorHash"), applied?.get("appliedSelectionHash"))
    }
    @Then("selector refusal or retained apply preserves the expected source authority")
    fun bytes() {
        if (code != null) { assertEquals(before, contents());assertFalse(Files.exists(root.resolve(".refactorkit/transactions")));return }
        val after = contents()
        if (target == "constructor") { constructorBytes(after); return }
        when (target) {
            "class" -> { assertFalse(after.containsKey("src/main/java/pkg/Runner.java"));assertTrue(after.getValue(caller).contains("new Worker()")) }
            "method" -> { assertTrue(after.getValue(caller).contains("runner.invoke(\"value\")"));assertTrue(after.getValue(caller).contains("runner.call(2)"));assertTrue(after.getValue("src/main/java/pkg/Runner.java").contains("int call(int")) }
            "field" -> { assertTrue(after.getValue(caller).contains("runner.total"));assertFalse(after.getValue(caller).contains("runner.count")) }
        }
    }
    private fun constructorBytes(after: Map<String, String>) {
        val source = before.getValue(runner); val use = before.getValue(caller)
        val expected = before.toMutableMap()
        expected[runner] = when (constructorEvent) {
            "rename parameter" -> source.replace("int amount) { count = amount;", "int total) { count = total;")
            "add parameter" -> source.replace("String text, int amount)", "String text, int amount, boolean enabled)")
            "reorder parameters" -> source.replace("String text, int amount)", "int amount, String text)")
            "remove parameter" -> source.replace("String text, int amount)", "int amount)")
            "change type" -> source.replace("String text, int amount)", "Object text, int amount)")
            else -> error("Unexpected applied constructor event")
        }
        expected[caller] = when (constructorEvent) {
            "add parameter" -> use.replace("new Runner(\"value\", 2)", "new Runner(\"value\", 2, true)")
            "reorder parameters" -> use.replace("new Runner(\"value\", 2)", "new Runner(2, \"value\")")
            "remove parameter" -> use.replace("new Runner(\"value\", 2)", "new Runner(2)")
            else -> use
        }
        assertEquals(expected, after)
        val result = assertNotNull(applied)
        val rolled = session!!.dispatch("patch.rollback", buildJsonObject {
            put("transactionId", result.getValue("transactionId")); put("expectedSnapshotHash", result.getValue("snapshotHash")); put("javaContext", authority)
        }).jsonObject
        assertEquals("rolledBack", rolled.getValue("status").jsonPrimitive.content)
        assertEquals(hash, rolled.getValue("snapshotHash").jsonPrimitive.content)
        assertEquals(before, contents())
    }
    private fun write(path: String, text: String) { val file = root.resolve(path);Files.createDirectories(file.parent);Files.writeString(file, text) }
    private fun contents() = Files.walk(root).use { p -> p.filter { Files.isRegularFile(it) && (it.toString().endsWith(".java") || it.fileName.toString() == "pom.xml") }.toList().associate { root.relativize(it).toString() to Files.readString(it) } }
    @After fun close() { session?.close();if (::root.isInitialized) Files.walk(root).use { p -> p.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
}
