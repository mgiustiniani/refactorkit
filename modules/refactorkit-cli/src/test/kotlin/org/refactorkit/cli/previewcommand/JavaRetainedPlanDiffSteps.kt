package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.When
import io.cucumber.java.en.Then
import kotlinx.serialization.json.*
import org.refactorkit.daemon.DaemonSession
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class JavaRetainedPlanDiffSteps {
    private lateinit var root: Path
    private var session: DaemonSession? = null
    private lateinit var hash: String
    private lateinit var authority: JsonObject
    private lateinit var response: JsonObject
    private lateinit var before: Map<String, String>
    private lateinit var operation: String
    private var large = false
    @Given("a saved Java diff fixture with {string} changes and explicit SDK authority")
    fun fixture(extent: String) {
        root = Files.createTempDirectory("rk-retained-diff-"); large = extent == "large"
        write("pom.xml", "<project><modelVersion>4.0.0</modelVersion><groupId>pkg</groupId><artifactId>app</artifactId><version>1</version><properties><maven.compiler.release>8</maven.compiler.release></properties></project>")
        write("src/main/java/pkg/Runner.java", "package pkg;\nimport java.util.Set;\nimport java.util.List;\npublic class Runner { List<String> values;public Runner(){} }\n")
        write("src/main/java/pkg/Consumer.java", "package pkg; public class Consumer {\n" + if (large) (0..1_200).joinToString("\n", postfix="\n}\n") { "Runner value$it = new Runner();" } else "Runner value = new Runner();\n}\n")
        before = contents(); session = DaemonSession()
        hash = session!!.dispatch("project.open", buildJsonObject { put("root", root.toString()) }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        val context = session!!.dispatch("project.context", buildJsonObject { put("expectedSnapshotHash", hash); put("sdkHome", "/usr/lib/jvm/java-21-openjdk") }).jsonObject
        assertEquals("available", context.getValue("status").jsonPrimitive.content)
        authority = buildJsonObject { put("sdkHome", "/usr/lib/jvm/java-21-openjdk"); put("expectedContextHash", context.getValue("contextHash")) }
    }
    @When("an SDK-bound {string} preview renders its retained plan")
    fun preview(selected: String) {
        operation = selected
        response = session!!.dispatch("refactor.preview", buildJsonObject {
            put("operation", selected); put("languageId", "java"); put("symbol", "pkg.Runner"); put("expectedSnapshotHash", hash); put("javaContext", authority)
            put("arguments", buildJsonObject { if (selected == "renameClass") put("newName", "Worker") else put("file", "src/main/java/pkg/Runner.java") })
        }).jsonObject
    }
    @Then("the textual diff is {string} and represents that plan's edits")
    fun diff(completeness: String) {
        val text = assertNotNull(response["renderedDiff"], "Every retained preview must carry its rendered plan diff").jsonPrimitive.content
        assertTrue(text.contains("--- a/src/main/java/pkg/Runner.java"), text)
        assertEquals(completeness == "truncated", response.getValue("diffTruncated").jsonPrimitive.boolean)
        val reasons = response.getValue("diffTruncationReasons").jsonArray
        assertEquals(completeness == "complete", reasons.isEmpty())
        when (operation) {
            "organizeImports" -> assertTrue(text.contains("-import java.util.Set;"), text)
            "renameClass" -> { assertTrue(text.contains("class Worker"), text); assertTrue(text.contains("+++ b/src/main/java/pkg/Worker.java"), text); assertTrue(text.contains("Consumer.java"), text) }
            "formatFile" -> assertTrue(text.contains("+public class Runner {"), text)
        }
        assertTrue(text.toByteArray().size <= org.refactorkit.core.ProtocolLimits.MAX_PREVIEW_DIFF_BYTES)
    }
    @Then("preview preserves source bytes and retained apply uses the same edits")
    fun apply() {
        assertEquals(before, contents())
        if (large) { session!!.dispatch("refactor.discard", buildJsonObject { put("planId", response.getValue("planId")) }); return }
        val applied = session!!.dispatch("refactor.apply", buildJsonObject { put("planId", response.getValue("planId")); put("javaContext", authority); put("expectedSnapshotHash", hash) }).jsonObject
        assertEquals("applied", applied.getValue("status").jsonPrimitive.content)
        val after = contents(); assertNotEquals(before, after)
        if (operation == "renameClass") { assertFalse(after.containsKey("src/main/java/pkg/Runner.java")); assertTrue(after.getValue("src/main/java/pkg/Worker.java").contains("class Worker")); assertTrue(after.getValue("src/main/java/pkg/Consumer.java").contains("new Worker()")) }
        else if (operation == "organizeImports") assertFalse(after.getValue("src/main/java/pkg/Runner.java").contains("import java.util.Set"))
        else assertTrue(after.getValue("src/main/java/pkg/Runner.java").contains("public Runner() {"))
    }
    private fun write(path: String, text: String) { val file = root.resolve(path); Files.createDirectories(file.parent); Files.writeString(file, text) }
    private fun contents() = Files.walk(root).use { p -> p.filter { Files.isRegularFile(it) && (it.toString().endsWith(".java") || it.fileName.toString() == "pom.xml") }.toList().associate { root.relativize(it).toString() to Files.readString(it) } }
    @After fun close() { session?.close(); if (::root.isInitialized) Files.walk(root).use { p -> p.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
}
