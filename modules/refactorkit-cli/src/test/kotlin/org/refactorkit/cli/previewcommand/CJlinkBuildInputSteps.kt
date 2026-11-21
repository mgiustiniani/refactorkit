package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CJlinkBuildInputSteps {
    private val scratch = Files.createTempDirectory("rk-jlink-build-inputs-")
    private lateinit var buildJdk: Path
    private lateinit var declaredInputs: Set<Path>
    private val windows = System.getProperty("os.name").startsWith("Windows")

    @Given("the packaging build runs on an installed JDK 21")
    fun selectJdk() {
        assertEquals(21, Runtime.version().feature())
        buildJdk = Path.of(System.getProperty("java.home")).toRealPath()
        assertTrue(Files.isRegularFile(buildJdk.resolve("jmods/java.base.jmod")))
    }

    @When("the actual Gradle runtime image task is configured")
    fun inspectTask() {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        val init = scratch.resolve("runtime-inputs.gradle")
        Files.writeString(init, """
            import groovy.json.JsonOutput

            gradle.projectsEvaluated {
                def task = gradle.rootProject.project(':modules:refactorkit-cli').tasks.named('jlinkRuntime').get()
                def inputFacts = [home: System.getProperty('java.home'),
                                  files: task.inputs.files.files.collect { it.canonicalPath }.sort()]
                println('JLINK_BUILD_INPUT_FACTS=' + JsonOutput.toJson(inputFacts))
            }
        """.trimIndent())
        val wrapper = if (windows) listOf("cmd", "/c", root.resolve("gradlew.bat").toString())
        else listOf(root.resolve("gradlew").toString())
        val command = wrapper + listOf(
            "--offline", "--no-daemon", "--no-watch-fs", "--max-workers=2", "--console=plain",
            "--project-cache-dir", scratch.resolve("project-cache").toString(),
            "-Dorg.gradle.java.home=$buildJdk", "--init-script", init.toString(),
            ":modules:refactorkit-cli:jlinkRuntime", "--dry-run",
        )
        val log = scratch.resolve("gradle.log")
        val process = ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true)
            .redirectOutput(log.toFile()).apply {
                environment()["JAVA_HOME"] = buildJdk.toString()
            }.start()
        if (!process.waitFor(150, TimeUnit.SECONDS)) {
            process.descendants().use { descendants -> descendants.forEach { it.destroyForcibly() } }
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
            error("Gradle did not report the runtime image inputs")
        }
        val output = Files.readString(log)
        assertEquals(0, process.exitValue(), output)
        val prefix = "JLINK_BUILD_INPUT_FACTS="
        val facts = Json.parseToJsonElement(output.lineSequence().single { it.startsWith(prefix) }.removePrefix(prefix))
            .jsonObject
        assertEquals(buildJdk, Path.of(facts.getValue("home").jsonPrimitive.content).toRealPath())
        declaredInputs = facts.getValue("files").jsonArray.map { Path.of(it.jsonPrimitive.content) }.toSet()
    }

    @Then("its file inputs include the selected JDK release metadata, linker, implementation and JMODs")
    fun checkInputs() {
        val executable = if (windows) "bin/jlink.exe" else "bin/jlink"
        val required = listOf("release", executable, "lib/modules").map { buildJdk.resolve(it).toRealPath() }.toSet()
        val missing = required - declaredInputs
        assertTrue(missing.isEmpty(), "Missing packaging JDK inputs: $missing")
        val jmods = buildJdk.resolve("jmods").toRealPath()
        val modules = Files.list(jmods).use { paths -> paths.filter { it.toString().endsWith(".jmod") }.toList() }
        assertTrue(modules.isNotEmpty(), "The build JDK has no JMODs")
        assertTrue(jmods in declaredInputs || declaredInputs.containsAll(modules), "Missing packaging JMOD inputs")
    }

    @After
    fun close() {
        Files.walk(scratch).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}
