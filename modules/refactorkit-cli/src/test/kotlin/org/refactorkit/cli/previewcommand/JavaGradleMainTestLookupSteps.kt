package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.*
import kotlinx.serialization.json.*
import org.refactorkit.core.*
import org.refactorkit.daemon.DaemonSession
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class JavaGradleMainTestLookupSteps {
    private lateinit var root: Path
    private var session: DaemonSession? = null
    private lateinit var sdk: String
    private lateinit var baseline: Map<String, String>
    private lateinit var snapshotHash: String
    private lateinit var authority: JsonObject
    private lateinit var context: JsonObject
    private lateinit var selector: JsonObject
    private lateinit var preview: JsonObject
    private lateinit var applied: JsonObject
    private lateinit var restored: JsonObject
    private val selectedPath = "app/src/test/java/pkg/Runner.java"

    @Given("an effective Gradle SDK {string} workspace with a test filename matching a main class")
    fun fixture(version: String) {
        root = Files.createTempDirectory("rk-gradle-source-lookup-")
        sdk = if (version == "25") "/home/paperboy/.local/share/graalvm/graalvm-jdk-25-latest" else "/usr/lib/jvm/java-21-openjdk"
        write("settings.gradle", "rootProject.name='lookup'; include 'app','library','unrelated'\n")
        write("gradle.properties", "org.gradle.jvmargs=-Xmx256m\n")
        write("build.gradle", "subprojects { apply plugin: 'java-library'; tasks.withType(JavaCompile).configureEach { options.release=8 } }\n")
        write("app/build.gradle", "dependencies { implementation project(':library') }\n")
        write("library/build.gradle", "// library\n");write("unrelated/build.gradle", "// unrelated\n")
        write("library/src/main/java/fixtures/Helper.java", "package fixtures;\npublic class Helper { public static String value() { return \"main\"; } }\n")
        write("unrelated/src/main/java/hidden/Helper.java", "package hidden;\npublic class Helper {}\n")
        write("app/src/main/java/pkg/Runner.java", "package pkg;\npublic class Runner { public static String run() { return fixtures.Helper.value(); } }\nclass Peer {}\n")
        write(selectedPath, "package pkg;\nclass Other { String value = Runner.run(); }\n")
        baseline=sources();session=DaemonSession();snapshotHash=rpc("project.open", buildJsonObject { put("root", root.toString()) }).getValue("snapshotHash").jsonPrimitive.content
        val sync=rpc("project.sync", buildJsonObject { put("buildSystem", "gradle");put("expectedSnapshotHash", snapshotHash);put("sdkHome", sdk);put("gradleExecutable", "/home/paperboy/.gradle/wrapper/dists/gradle-9.6.1-bin/4ticwg1pgcbps2hj28r8so764/gradle-9.6.1/bin/gradle");put("allowBuildExecution", true) })
        assertEquals("synchronized", sync.getValue("status").jsonPrimitive.content, sync.toString());snapshotHash=sync.getValue("snapshotHash").jsonPrimitive.content
        context=rpc("project.context", buildJsonObject { put("sdkHome", sdk);put("expectedSnapshotHash", snapshotHash) });assertEquals("available", context.getValue("status").jsonPrimitive.content)
        authority=buildJsonObject { put("sdkHome", sdk);put("expectedContextHash", context.getValue("contextHash")) }
    }
    @When("its test cursor selects the main class for a retained rename")
    fun select() {
        val line=baseline.getValue(selectedPath).lines()[1]
        selector=rpc("intelligence.query", buildJsonObject { put("requestId", "gradle-source-lookup");put("kind", "mutationSelection");put("languageId", "java");put("path", selectedPath);put("position", buildJsonObject { put("line", 1);put("character", line.indexOf("Runner")+1) });put("javaContext", authority);put("expectedSnapshotHash", snapshotHash);put("sourceAuthority", buildJsonObject { put("kind", "saved-snapshot") }) })
        assertEquals("ready", selector.getValue("status").jsonPrimitive.content, selector.toString())
        preview=rpc("refactor.preview", buildJsonObject { put("operation", "renameClass");put("languageId", "java");put("javaContext", authority);put("javaSelection", selector.getValue("selection"));put("expectedSnapshotHash", snapshotHash);put("arguments", buildJsonObject { put("newName", "Worker") }) })
        applied=rpc("refactor.apply", buildJsonObject { put("planId", preview.getValue("planId"));put("javaContext", authority);put("expectedSnapshotHash", snapshotHash) })
        assertTrue(Files.readString(root.resolve(selectedPath)).contains("Worker.run()"))
        restored=rpc("patch.rollback", buildJsonObject { put("transactionId", applied.getValue("transactionId"));put("javaContext", authority);put("expectedSnapshotHash", applied.getValue("snapshotHash")) })
    }
    @Then("exact Gradle source lookup permits semantic preview apply and protected restoration")
    fun outcomes() {
        assertEquals("pkg.Runner", selector.getValue("selection").jsonObject.getValue("symbolId").jsonPrimitive.content)
        assertEquals("app/src/main/java/pkg/Runner.java", selector.getValue("selection").jsonObject.getValue("declaration").jsonObject.getValue("path").jsonPrimitive.content)
        assertEquals("JDT_BINDING", preview.getValue("evidence").jsonPrimitive.content)
        assertEquals("PREVIEW", preview.getValue("status").jsonPrimitive.content);assertEquals("applied", applied.getValue("status").jsonPrimitive.content)
        assertEquals("rolledBack", restored.getValue("status").jsonPrimitive.content);assertEquals(snapshotHash, restored.getValue("snapshotHash").jsonPrimitive.content)
        assertEquals(authority.getValue("expectedContextHash"), restored.getValue("rolledBackContextHash"))
        assertTrue(restored.getValue("diagnostics").jsonArray.none { it.jsonObject["severity"]?.jsonPrimitive?.content == "error" })
    }
    @Then("main and test bytes return to their original versions without changing visibility")
    fun images() {
        assertEquals(baseline, sources())
        val current=rpc("project.context", buildJsonObject { put("sdkHome", sdk);put("expectedSnapshotHash", snapshotHash);put("expectedContextHash", authority.getValue("expectedContextHash")) })
        assertEquals(context.getValue("contextHash"), current.getValue("contextHash"));assertEquals(context.getValue("modules"), current.getValue("modules"))
        val main=current.getValue("modules").jsonArray.single { it.jsonObject.getValue("id").jsonPrimitive.content == ":app" }.jsonObject.getValue("sourceSets").jsonArray.single { it.jsonObject.getValue("id").jsonPrimitive.content == "main" }.jsonObject
        val compile=main.getValue("compileClasspath").jsonArray.map { it.jsonObject.getValue("path").jsonPrimitive.content }
        assertTrue(compile.any { it.startsWith("library/") });assertFalse(compile.any { it.startsWith("unrelated/") })
    }
    private fun rpc(method: String, params: JsonObject)=session!!.dispatch(method, params).jsonObject
    private fun write(relative: String, content: String) { val p=root.resolve(relative);Files.createDirectories(p.parent);Files.writeString(p, content) }
    private fun sources()=Files.walk(root).use { p -> p.filter { Files.isRegularFile(it) && (it.toString().endsWith(".java") || it.toString().endsWith(".gradle") || it.fileName.toString() == "gradle.properties") && ".gradle" !in root.relativize(it).map(Path::toString) }.toList().associate { root.relativize(it).toString() to Files.readString(it) } }
    @After fun close() { session?.close();if (::root.isInitialized) Files.walk(root).use { p -> p.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
}
