package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.When
import io.cucumber.java.en.Then
import kotlinx.serialization.json.*
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.JsonRpcErrorCodes
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class GradleLiteralModuleRenameSteps {
    private val fixture = NativeGradleLiteralRenameFixture()
    @Given("a synchronized {string} Gradle multiproject rename fixture uses {string} includes")
    fun capture(dsl: String, form: String) = fixture.capture(dsl, form)
    @When("the daemon previews a literal Gradle app project rename")
    fun preview() = fixture.preview()
    @Then("the multiproject Gradle rename is {string} with {string}")
    fun disposition(status: String, refusal: String) = fixture.disposition(status, refusal)
    @Then("the Gradle rename preserves peer project identity and supports exact rollback")
    fun lifecycle() = fixture.lifecycle()
    @After fun close() = fixture.close()
}

private class NativeGradleLiteralRenameFixture : AutoCloseable {
    private var root: Path? = null
    private var home: Path? = null
    private var copy: Path? = null
    private var session: GradleContextRpcSession? = null
    private lateinit var sdk: Path
    private lateinit var gradle: Path
    private lateinit var settings: String
    private lateinit var originals: Map<String, String>
    private lateinit var snapshot: String
    private lateinit var context: JsonObject
    private lateinit var authority: JsonObject
    private var kotlinDsl = false
    private var plan: JsonObject? = null
    private var failure: JsonRpcException? = null

    fun capture(dsl: String, form: String) {
        require(dsl in setOf("Kotlin", "Groovy") && form in setOf("literal list", "conditional list", "inbound dependency"))
        kotlinDsl = dsl == "Kotlin"
        sdk = Path.of(System.getenv("REFACTORKIT_JAVA_QUALIFICATION_SDK") ?: System.getProperty("java.home")).toRealPath()
        gradle = Path.of(requireNotNull(System.getenv("REFACTORKIT_GRADLE_EXECUTABLE"))).toRealPath()
        val workspace = Files.createTempDirectory("rk-gradle-literal-rename-"); root = workspace
        home = Files.createTempDirectory("rk-gradle-literal-home-")
        settings = if (kotlinDsl) "settings.gradle.kts" else "settings.gradle"
        val includes = if (kotlinDsl) "include(\":library\", \":app\")" else "include ':library', ':app'"
        val included = if (form == "conditional list") "if (true) $includes" else includes
        val inputs = linkedMapOf(
            settings to "rootProject.name = \"literal-rename\"\n$included\n",
            "gradle.properties" to "org.gradle.jvmargs=-Xmx384m\norg.gradle.daemon=false\norg.gradle.java.installations.auto-detect=false\norg.gradle.java.installations.auto-download=false\norg.gradle.java.installations.paths=$sdk\n",
            "app/src/main/java/fixture/App.java" to "package fixture; public final class App { public int value() { return 1; } }\n",
            "library/src/main/java/fixture/Library.java" to "package fixture; public final class Library {}\n",
        )
        for (module in listOf("app", "library")) {
            val script = if (kotlinDsl) "plugins { java }\njava { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }\n"
                else "plugins { id 'java' }\njava { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }\n"
            inputs["$module/" + if (kotlinDsl) "build.gradle.kts" else "build.gradle"] = script +
                if (module == "library" && form == "inbound dependency") "dependencies { implementation project(':app') }\n" else ""
        }
        inputs.forEach { (path, text) -> write(workspace, path, text) }; originals = inputs
        session = newGradleContextRpcSession()
        val opened = rpc("project.open", buildJsonObject { put("root", workspace.toString()) })
        val synced = rpc("project.sync", buildJsonObject {
            put("buildSystem", "gradle"); put("expectedSnapshotHash", opened.getValue("snapshotHash"))
            put("sdkHome", sdk.toString()); put("gradleExecutable", gradle.toString())
            put("gradleUserHome", requireNotNull(home).toString()); put("allowBuildExecution", true)
        })
        assertEquals("synchronized", synced.getValue("status").jsonPrimitive.content, synced.toString())
        snapshot = synced.getValue("snapshotHash").jsonPrimitive.content
        val aggregate = rpc("project.context", buildJsonObject { put("sdkHome", sdk.toString()); put("expectedSnapshotHash", snapshot) })
        assertEquals("available", aggregate.getValue("status").jsonPrimitive.content, aggregate.toString())
        assertTrue(aggregate.getValue("modules").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }.containsAll(listOf(":app", ":library")))
        context = rpc("project.context.v7", selectedContextRequest())
        assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
        authority = buildJsonObject {
            put("sdkHome", sdk.toString()); put("expectedContextHash", context.getValue("contextHash"))
            put("contextSchemaVersion", "java-project-context-v7")
        }
        assertEquals(originals, contents())
    }

    fun preview() {
        try {
            plan = rpc("refactor.preview", buildJsonObject {
                put("operation", "renameGradleModule"); put("languageId", "java"); put("expectedSnapshotHash", snapshot)
                put("javaContext", authority)
                put("arguments", buildJsonObject { put("buildRoot", "."); put("oldProjectPath", ":app"); put("newProjectPath", ":renamed") })
            })
        } catch (error: JsonRpcException) { failure = error }
    }

    fun disposition(status: String, refusal: String) {
        if (failure != null) {
            assertEquals("REFUSED", status); assertEquals(JsonRpcErrorCodes.PLAN_REFUSED, failure!!.code)
            assertEquals(refusal, failure!!.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content)
        } else {
            val result = assertNotNull(plan)
            assertEquals(status, result.getValue("status").jsonPrimitive.content, result.toString())
            if (status == "REFUSED") assertEquals(refusal, result.getValue("refusalCode").jsonPrimitive.content)
            else {
                assertEquals("none", refusal)
                assertTrue(result.getValue("renderedDiff").jsonPrimitive.content.contains("projectDir"))
            }
        }
        assertEquals(originals, contents())
        assertFalse(Files.exists(requireNotNull(root).resolve(".refactorkit/transactions")))
    }

    fun lifecycle() {
        val result = plan
        if (failure != null || result?.getValue("status")?.jsonPrimitive?.content == "REFUSED") {
            assertEquals(originals, contents()); return
        }
        val retained = assertNotNull(result)
        val applied = rpc("refactor.apply", buildJsonObject {
            put("planId", retained.getValue("planId")); put("expectedSnapshotHash", snapshot); put("javaContext", authority)
        })
        assertEquals("applied", applied.getValue("status").jsonPrimitive.content, applied.toString())
        val mapping = if (kotlinDsl) "project(\":renamed\").projectDir = file(\"app\")\n"
            else "project(':renamed').projectDir = file('app')\n"
        val expected = originals + (settings to (originals.getValue(settings).replace(":app", ":renamed") + mapping))
        assertEquals(expected, contents())
        assertTrue(Files.isDirectory(requireNotNull(root).resolve("app")))
        assertFalse(Files.exists(requireNotNull(root).resolve("renamed")))
        compileIndependentCopy(expected)
        val rolled = rpc("patch.rollback", buildJsonObject {
            put("transactionId", applied.getValue("transactionId")); put("expectedSnapshotHash", applied.getValue("snapshotHash")); put("javaContext", authority)
        })
        assertEquals("rolledBack", rolled.getValue("status").jsonPrimitive.content, rolled.toString())
        assertEquals(snapshot, rolled.getValue("snapshotHash").jsonPrimitive.content)
        assertEquals(originals, contents())
        val restored = rpc("project.context.v7", selectedContextRequest())
        assertEquals("available", restored.getValue("status").jsonPrimitive.content, restored.toString())
        assertEquals(context.getValue("contextHash"), restored.getValue("contextHash"))
    }

    private fun compileIndependentCopy(inputs: Map<String, String>) {
        val workspace = Files.createTempDirectory("rk-gradle-literal-post-"); copy = workspace
        inputs.forEach { (path, text) -> write(workspace, path, text) }
        val log = workspace.resolve("compile.log")
        val command = listOf(gradle.toString(), "--offline", "--no-daemon", "--no-watch-fs", "--console=plain", "--max-workers=1",
            "--gradle-user-home", requireNotNull(home).toString(), "-Dorg.gradle.java.home=$sdk", ":renamed:compileJava", ":library:compileJava")
        val process = ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true).redirectOutput(log.toFile())
            .apply { environment()["JAVA_HOME"] = sdk.toString() }.start()
        try {
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "Independent post-apply compile timed out")
            assertEquals(0, process.exitValue(), Files.readString(log).takeLast(4096))
        } finally {
            if (process.isAlive) { process.descendants().forEach { it.destroyForcibly() }; process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS) }
        }
        assertTrue(Files.isRegularFile(workspace.resolve("app/build/classes/java/main/fixture/App.class")))
        assertTrue(Files.isRegularFile(workspace.resolve("library/build/classes/java/main/fixture/Library.class")))
        assertFalse(Files.exists(workspace.resolve("renamed")))
        assertEquals(inputs, contents(workspace))
    }
    private fun selectedContextRequest() = buildJsonObject {
        put("sdkHome", sdk.toString()); put("expectedSnapshotHash", snapshot)
        put("providerId", "gradle-effective-v1"); put("moduleId", ":app"); put("sourceSetId", "main")
    }
    private fun rpc(method: String, params: JsonObject): JsonObject = requireNotNull(session).dispatch(method, params).jsonObject
    private fun write(workspace: Path, path: String, text: String) { val file = workspace.resolve(path); Files.createDirectories(file.parent); Files.writeString(file, text) }
    private fun contents(workspace: Path = requireNotNull(root)): Map<String, String> = Files.walk(workspace).use { paths ->
        paths.filter { Files.isRegularFile(it) && workspace.relativize(it).none { part -> part.toString() in setOf(".gradle", ".refactorkit", "build") } && it.fileName.toString() != "compile.log" }
            .toList().associate { workspace.relativize(it).toString().replace('\\', '/') to Files.readString(it) }
    }
    override fun close() {
        session?.close()
        for (directory in listOfNotNull(copy, root, home)) Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}
