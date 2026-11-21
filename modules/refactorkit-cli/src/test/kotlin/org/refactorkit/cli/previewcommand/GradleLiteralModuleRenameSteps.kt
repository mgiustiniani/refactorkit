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
    private var literalConsumerBuild: String? = null
    private var kotlinDsl = false
    private var application = false
    private var plan: JsonObject? = null
    private var failure: JsonRpcException? = null

    fun capture(dsl: String, form: String) {
        require(dsl in setOf("Kotlin", "Groovy") && form in setOf(
            "literal list", "conditional list", "literal dependency", "dynamic dependency",
            "application dependency", "application dynamic dependency", "application duplicate origin",
            "application dependency hook",
        ))
        kotlinDsl = dsl == "Kotlin"
        application = form.startsWith("application ")
        val buildName = if (kotlinDsl) "build.gradle.kts" else "build.gradle"
        if (form in setOf("literal dependency", "application dependency")) literalConsumerBuild = "library/$buildName"
        val dependency = when (form) {
            "literal dependency" -> if (kotlinDsl) """dependencies { implementation(project(":app")) }"""
                else "dependencies { implementation project(':app') }"
            "dynamic dependency" -> "def targetPath = ':app'\ndependencies { implementation project(targetPath) }"
            else -> ""
        }
        sdk = Path.of(System.getenv("REFACTORKIT_JAVA_QUALIFICATION_SDK") ?: System.getProperty("java.home")).toRealPath()
        gradle = Path.of(requireNotNull(System.getenv("REFACTORKIT_GRADLE_EXECUTABLE"))).toRealPath()
        val workspace = Files.createTempDirectory("rk-gradle-literal-rename-"); root = workspace
        home = Files.createTempDirectory("rk-gradle-literal-home-")
        settings = if (kotlinDsl) "settings.gradle.kts" else "settings.gradle"
        val modules = listOf("library", "app") + if (application) listOf("peer") else emptyList()
        val includes = if (kotlinDsl) modules.joinToString(", ", "include(", ")") { "\":$it\"" }
            else modules.joinToString(", ", "include ") { if (application) "'$it'" else "':$it'" }
        val included = if (form == "conditional list") "if (true) $includes" else includes
        val inputs = linkedMapOf(
            settings to "rootProject.name = \"literal-rename\"\n$included\n",
            "gradle.properties" to "org.gradle.jvmargs=-Xmx384m\norg.gradle.daemon=false\norg.gradle.java.installations.auto-detect=false\norg.gradle.java.installations.auto-download=false\norg.gradle.java.installations.paths=$sdk\n",
            "app/src/main/java/fixture/App.java" to "package fixture; public final class App { public int value() { return 1; } }\n",
            "library/src/main/java/fixture/Library.java" to if (dependency.isNotEmpty())
                "package fixture; public final class Library { public int read() { return new App().value(); } }\n"
                else "package fixture; public final class Library {}\n",
        )
        for (module in listOf("app", "library")) {
            val script = if (kotlinDsl) "plugins { java }\njava { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }\n"
                else "plugins { id 'java' }\njava { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }\n"
            inputs["$module/$buildName"] = script +
                if (module == "library" && dependency.isNotEmpty()) "$dependency\n" else ""
        }
        if (application) addApplicationInputs(inputs, buildName, form)
        inputs.forEach { (path, text) -> write(workspace, path, text) }; originals = inputs
        session = newGradleContextRpcSession()
        val capability = rpc("server.capabilities", buildJsonObject {}).getValue("methods").jsonArray
            .map(JsonElement::jsonObject)
            .single { it.getValue("name").jsonPrimitive.content == "java.renameGradleModule" }
            .getValue("features").jsonObject
        listOf(
            "literalSettingsOnly", "inboundProjectDependenciesRefused",
            "literalInboundProjectDependencies", "unqualifiedInboundProjectDependenciesRefused",
        ).forEach { flag -> assertEquals(true, capability.getValue(flag).jsonPrimitive.boolean, flag) }
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
                val diff = result.getValue("renderedDiff").jsonPrimitive.content
                assertTrue(diff.contains("projectDir"))
                literalConsumerBuild?.let { path ->
                    assertTrue(diff.contains(path), diff)
                    val expected = if (kotlinDsl) """implementation(project(":renamed"))""" else "implementation project(':renamed')"
                    assertTrue(diff.contains(expected), diff)
                }
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
        val expected = originals.toMutableMap()
        val settingsBefore = originals.getValue(settings)
        val includeEdit = if (application && !kotlinDsl) settingsBefore.replace("'app'", "':renamed'")
            else settingsBefore.replace(":app", ":renamed")
        expected[settings] = includeEdit + mapping
        literalConsumerBuild?.let { path ->
            val before = if (kotlinDsl) """implementation(project(":app"))""" else "implementation project(':app')"
            expected[path] = originals.getValue(path).replace(before, before.replace(":app", ":renamed"))
        }
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
            "--gradle-user-home", requireNotNull(home).toString(), "-Dorg.gradle.java.home=$sdk", ":renamed:compileJava", ":library:compileJava") +
            if (application) listOf(":library:run") else emptyList()
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
        if (application) {
            assertTrue(Files.isRegularFile(workspace.resolve("peer/build/classes/java/main/fixture/Peer.class")))
            assertTrue(Files.readString(log).contains("renamed library and peer = 3"))
        }
        assertFalse(Files.exists(workspace.resolve("renamed")))
        assertEquals(inputs, contents(workspace))
    }

    private fun addApplicationInputs(inputs: MutableMap<String, String>, buildName: String, form: String) {
        inputs["peer/src/main/java/fixture/Peer.java"] =
            "package fixture; public final class Peer { public int value() { return 2; } }\n"
        inputs["library/src/main/java/fixture/Library.java"] = """
            package fixture;
            public final class Library {
                public static void main(String[] args) {
                    System.out.println("renamed library and peer = " + (new App().value() + new Peer().value()));
                }
            }
        """.trimIndent() + "\n"
        if (kotlinDsl) {
            inputs[buildName] = "plugins { base }\n"
            inputs["peer/$buildName"] = "plugins { id(\"java-library\") }\n"
            val target = if (form == "application dynamic dependency") "targetPath" else "\":app\""
            val variable = if (form == "application dynamic dependency") "val targetPath = \":app\"\n" else ""
            val hook = if (form == "application dependency hook") "dependencies.add(\"implementation\", project(\":app\"))\n" else ""
            inputs["library/$buildName"] = """
                import org.gradle.api.tasks.compile.JavaCompile
                import org.gradle.api.tasks.testing.Test
                plugins { application }
                group = "fixture"
                version = "1.0"
                application { mainClass.set("fixture.Library") }
                repositories { mavenCentral() }
                ${variable}dependencies {
                    implementation(project($target))
                    implementation(project(":peer"))
                }
                ${hook}tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
                tasks.withType<Test>().configureEach { useJUnit(); systemProperty("fixture.mode", "base") }
            """.trimIndent() + "\n"
        } else {
            inputs[buildName] = """
                subprojects {
                    apply plugin: 'java-library'
                    group = 'fixture'
                    version = '1.0'
                    repositories { mavenCentral() }
                    java { withSourcesJar() }
                    tasks.withType(JavaCompile).configureEach { options.release = 17 }
                    tasks.withType(Test).configureEach { useJUnit() }
                }
            """.trimIndent() + "\n"
            inputs["app/$buildName"] = ""
            inputs["peer/$buildName"] = "tasks.withType(JavaCompile).configureEach { options.compilerArgs.add('-proc:none') }\n"
            val target = if (form == "application dynamic dependency") "targetPath" else "':app'"
            val variable = if (form == "application dynamic dependency") "def targetPath = ':app'\n" else ""
            val duplicate = if (form == "application duplicate origin") "implementation project(':app')" else ""
            inputs["library/$buildName"] = """
                apply plugin: 'application'
                application { mainClass = 'fixture.Library' }
                ${variable}dependencies {
                    implementation project($target)
                    runtimeOnly project(':peer')
                    compileOnly project(':peer')
                    $duplicate
                }
                // The project path in this comment is not a declaration: :app
                def fixtureMode = providers.gradleProperty('fixtureMode').getOrElse('base')
                tasks.named('compileJava') { options.compilerArgs.add('-Agreeting.mode=' + fixtureMode) }
                tasks.named('test') { systemProperty 'fixture.expectedMode', fixtureMode }
            """.trimIndent() + "\n"
        }
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
