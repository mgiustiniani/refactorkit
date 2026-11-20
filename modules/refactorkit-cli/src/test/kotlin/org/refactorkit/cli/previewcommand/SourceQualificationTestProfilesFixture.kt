package org.refactorkit.cli.previewcommand

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Reads actual configured tasks in an isolated Gradle project cache; never executes them. */
internal class SourceQualificationTestProfilesFixture private constructor(
    private val repository: Path,
    private val directory: Path,
    private val executable: Path,
    private val callerOverride: Boolean,
) : AutoCloseable {
    private val report = directory.resolve("models.json")
    private val init = directory.resolve("inspect.init.gradle")
    private val callerRuntime = directory.resolve("caller-runtime")
    private val callerGradle = directory.resolve("caller-gradle")
    private val runtimeTask = ":modules:refactorkit-cli:refactorkitRuntimeDist"
    private var rows: List<JsonObject> = emptyList()

    fun inspect() {
        Files.createDirectory(callerRuntime)
        Files.writeString(callerGradle, "caller-owned executable selector; never executed\n")
        init.toFile().writeText(INSPECTOR)
        execute("model", listOf("inspectRefactorKitSourceTestModels"))
        println("Source test task models: " + Files.readString(report))
        rows = Json.parseToJsonElement(Files.readString(report)).jsonArray.map { it.jsonObject }
        assertEquals(setOf(":modules:refactorkit-cli:test", ":modules:refactorkit-lsp:test"),
            rows.map { it.getValue("taskPath").jsonPrimitive.content }.toSet())
    }

    fun verifyOwnership() {
        rows.forEach { row ->
            val cli = row.getValue("taskPath").jsonPrimitive.content.endsWith("cli:test")
            val owner = repository.resolve("modules/" + if (cli) "refactorkit-cli" else "refactorkit-lsp")
            val expectedRuntime = if (callerOverride) callerRuntime else
                repository.resolve("modules/refactorkit-cli/build/package/refactorkit")
            val expectedEvidence = if (callerOverride) directory.resolve(if (cli) "caller-cli-evidence" else "caller-lsp-evidence")
                else owner.resolve("build/reports/runtime-wire/test")
            assertEquals(expectedRuntime.toString(), row.getValue("candidate").jsonPrimitive.content,
                "Source test must own a runtime candidate or retain the explicit caller candidate")
            assertEquals(expectedEvidence.toString(), row.getValue("evidence").jsonPrimitive.content,
                "Source test must retain its own wire evidence or the explicit caller destination")
            val dependencies = row.getValue("directDependencies").jsonArray.map { it.jsonPrimitive.content }
            if (callerOverride) assertFalse(runtimeTask in dependencies, "An external candidate must not trigger an unrelated package rebuild")
            else assertTrue(runtimeTask in dependencies, "Standalone source tests must schedule their owned runtime")
        }
    }

    fun verifyScheduling() {
        val cli = rows.single { it.getValue("taskPath").jsonPrimitive.content.endsWith("cli:test") }
        assertEquals((if (callerOverride) callerGradle else executable).toString(),
            cli.getValue("gradleExecutable").jsonPrimitive.content,
            "Gradle fixture executable must default to the running distribution or retain caller override")
        val graph = execute("graph", listOf(":modules:refactorkit-cli:test", ":modules:refactorkit-lsp:test", "--dry-run"))
        println("Source test scheduling graph: " + graph)
        val cliTask = graph.indexOf(":modules:refactorkit-cli:test SKIPPED")
        val lspTask = graph.indexOf(":modules:refactorkit-lsp:test SKIPPED")
        assertTrue(cliTask >= 0 && lspTask >= 0, graph)
        if (!callerOverride) {
            val runtime = graph.indexOf("$runtimeTask SKIPPED")
            assertTrue(runtime >= 0 && runtime < cliTask && runtime < lspTask, graph)
        }
        assertEquals("caller-owned executable selector; never executed\n", Files.readString(callerGradle))
    }

    private fun execute(label: String, tasks: List<String>): String {
        val log = directory.resolve("$label.log")
        val args = mutableListOf(executable.toString(), "--offline", "--no-daemon", "--no-watch-fs",
            "--no-configuration-cache", "--max-workers=1", "--console=plain", "--project-cache-dir",
            directory.resolve("project-cache").toString(), "-I", init.toString())
        if (callerOverride) {
            args += "-Drefactorkit.ts.packaged.evidence=" + directory.resolve("caller-cli-evidence")
            args += "-Drefactorkit.c.lsp.packaged.evidence=" + directory.resolve("caller-lsp-evidence")
        }
        args += tasks
        val process = ProcessBuilder(args).directory(repository.toFile()).apply {
            redirectErrorStream(true); redirectOutput(log.toFile())
            environment().apply {
                remove("REFACTORKIT_C_QUERY_CANDIDATE_ROOT"); remove("REFACTORKIT_GRADLE_EXECUTABLE")
                remove("JAVA_TOOL_OPTIONS"); remove("_JAVA_OPTIONS"); remove("GRADLE_OPTS")
                put("JAVA_HOME", System.getProperty("java.home"))
                put("REFACTORKIT_TEST_PROFILE_OUTPUT", directory.toString())
                if (callerOverride) {
                    put("REFACTORKIT_C_QUERY_CANDIDATE_ROOT", callerRuntime.toString())
                    put("REFACTORKIT_GRADLE_EXECUTABLE", callerGradle.toString())
                }
            }
        }.start()
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.toHandle().descendants().use { children -> children.forEach { it.destroyForcibly() } }
            process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS)
            error("Gradle task model inspection timed out: " + Files.readString(log))
        }
        val text = Files.readString(log)
        assertTrue(Files.size(log) < 2 * 1024 * 1024, "Unexpectedly large model inspection output")
        assertEquals(0, process.exitValue(), text)
        return text
    }

    override fun close() {
        Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    companion object {
        fun capture(profile: String): SourceQualificationTestProfilesFixture {
            require(profile == "owned defaults" || profile == "caller override")
            val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
            val gradle = Path.of(requireNotNull(System.getenv("REFACTORKIT_GRADLE_EXECUTABLE")) {
                "The source Test task must provision the executing Gradle distribution" }).toRealPath()
            require(Files.isExecutable(gradle))
            return SourceQualificationTestProfilesFixture(root,
                Files.createTempDirectory("refactorkit-source-test-profiles-"), gradle, profile == "caller override")
        }

        private val INSPECTOR = """
            gradle.beforeProject { p ->
                if (p.path == ':') p.layout.buildDirectory.set(new File(System.getenv('REFACTORKIT_TEST_PROFILE_OUTPUT'), 'private-root-build'))
            }
            gradle.projectsEvaluated {
                gradle.rootProject.tasks.register('inspectRefactorKitSourceTestModels') {
                    doLast {
                        def rows = [':modules:refactorkit-cli', ':modules:refactorkit-lsp'].collect { path ->
                            def t = gradle.rootProject.project(path).tasks.named('test').get()
                            def key = path.endsWith('cli') ? 'refactorkit.ts.packaged.evidence' : 'refactorkit.c.lsp.packaged.evidence'
                            [taskPath:t.path, candidate:t.environment['REFACTORKIT_C_QUERY_CANDIDATE_ROOT'],
                             evidence:t.systemProperties[key], gradleExecutable:t.environment['REFACTORKIT_GRADLE_EXECUTABLE'],
                             directDependencies:t.dependsOn.findAll { it instanceof CharSequence }.collect { it.toString() }.sort()]
                        }
                        new File(System.getenv('REFACTORKIT_TEST_PROFILE_OUTPUT'), 'models.json').text = groovy.json.JsonOutput.toJson(rows)
                    }
                }
            }
        """.trimIndent()
    }
}
