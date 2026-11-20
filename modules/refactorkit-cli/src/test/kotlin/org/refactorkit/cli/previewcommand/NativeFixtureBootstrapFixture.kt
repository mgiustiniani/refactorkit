package org.refactorkit.cli.previewcommand

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Executes the actual bootstrap with native, explicitly configured inputs in disposable scratch. */
internal class NativeFixtureBootstrapFixture private constructor(
    private val repository: Path,
    private val directory: Path,
    private val inputs: LinkedHashMap<String, Path>,
    private val profile: String,
    private val wrongSdk: Boolean,
) : AutoCloseable {
    private val helper = repository.resolve("scripts/prepare-native-fixture-profile.py")
    private val environmentFile = directory.resolve("github-env")
    private val evidenceFile = directory.resolve("evidence.json")
    private val stdout = directory.resolve("stdout")
    private val stderr = directory.resolve("stderr")
    private val lts = profile == "lts"
    private val sdkVersions = if (lts) listOf(8, 11, 17, 21, 25) else listOf(21, 25, 26, 27)
    private val selected = inputs.toMutableMap().apply {
        if (wrongSdk) {
            if (lts) put("jdk11", getValue("jdk8")) else put("jdk26", getValue("jdk25"))
        }
    }

    private fun sdkInputs(version: Int): List<String> = listOf("release", "bin/java", "bin/javac", "lib/ct.sym") +
        if (version == 8) listOf("jre/lib/jce.jar", "jre/lib/jsse.jar") else emptyList()
    private val before: Map<Path, String>
    private var exitCode: Int? = null

    init {
        Files.writeString(environmentFile, SENTINEL)
        val artifacts = mutableListOf(helper)
        sdkVersions.forEach { version ->
            sdkInputs(version).forEach { relative -> artifacts.add(inputs.getValue("jdk$version").resolve(relative)) }
        }
        artifacts.add(inputs.getValue("gradle98"))
        artifacts.add(inputs.getValue("gradle98").parent.parent.resolve("lib/gradle-launcher-9.8.1.jar"))
        artifacts.add(inputs.getValue("node"))
        artifacts.add(inputs.getValue("ajv-module").resolve("package.json"))
        listOf(".github/workflows/ci.yml", ".github/workflows/release.yml",
            "qualification/protocol-schema-toolchain/package.json",
            "qualification/protocol-schema-toolchain/package-lock.json").forEach { artifacts.add(repository.resolve(it)) }
        before = artifacts.associateWith(::sha)
    }

    fun execute() {
        val command = mutableListOf("python3", helper.toString(), "--java-scope", profile)
        selected.forEach { (name, path) -> command += listOf("--$name", path.toString()) }
        command += listOf("--candidate-root", candidate.toString(), "--github-env", environmentFile.toString(),
            "--evidence", evidenceFile.toString())
        val process = ProcessBuilder(command).directory(directory.toFile()).apply {
            redirectOutput(stdout.toFile()); redirectError(stderr.toFile())
            environment().remove("PYTHONPATH")
            environment().remove("PYTHONHOME")
        }.start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.toHandle().descendants().use { children -> children.forEach { it.destroyForcibly() } }
            process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS)
            error("Native fixture bootstrap timed out")
        }
        assertTrue(Files.size(stdout) < 65536 && Files.size(stderr) < 65536, "Unexpected bootstrap output")
        exitCode = process.exitValue()
    }

    fun verifyOutcome(expected: String) {
        if (wrongSdk) {
            assertEquals("profile refused", expected)
            assertEquals(1, exitCode, Files.readString(stderr))
            val refusal = Json.parseToJsonElement(Files.readString(stderr)).jsonObject
            assertEquals("REFUSED", refusal.text("status"))
            assertEquals(if (lts) "SDK11_VERSION_MISMATCH" else "SDK26_VERSION_MISMATCH", refusal.text("code"))
            assertEquals("", Files.readString(stdout))
            assertFalse(Files.exists(evidenceFile), "Refused input must not publish qualification evidence")
            assertEquals(SENTINEL, Files.readString(environmentFile))
        } else {
            assertEquals("complete profile emitted", expected)
            assertEquals(0, exitCode, Files.readString(stderr))
            assertEquals("", Files.readString(stderr))
            val record = Json.parseToJsonElement(Files.readString(evidenceFile)).jsonObject
            assertEquals("VERIFIED_FIXTURE_INPUTS", record.text("status"))
            assertEquals(profile, record.text("javaScope"))
            assertEquals("false", record.text("sourceGrammarQualified"))
            assertEquals("false", record.text("newToolsInstalled"))
            val selectors = record.getValue("selectors").jsonObject.mapValues { it.value.jsonPrimitive.content }
            assertEquals(expectedSelectors, selectors, "Selectors have independently expected owners")
            assertEquals(SENTINEL + expectedSelectors.entries.joinToString("") { "${it.key}=${it.value}\n" },
                Files.readString(environmentFile))
            val facts = record.getValue("artifacts").jsonObject
            assertEquals(sdkVersions.map { "sdk$it" }.toSet() + setOf("gradle98", "node", "ajv"), facts.keys)
            for (major in sdkVersions) {
                val files = facts.getValue("sdk$major").jsonObject.getValue("files").jsonObject
                assertEquals(sdkInputs(major).toSet(), files.keys)
                files.forEach { (relative, hash) ->
                    assertEquals(before.getValue(inputs.getValue("jdk$major").resolve(relative)), hash.jsonPrimitive.content)
                }
            }
            assertEquals("9.8.1", facts.getValue("gradle98").jsonObject.text("version"))
            assertEquals("v22.18.0", facts.getValue("node").jsonObject.text("version"))
            assertEquals("6.15.0", facts.getValue("ajv").jsonObject.text("version"))
            assertEquals("false", facts.getValue("ajv").jsonObject.text("fullDraft2020Qualification"))
        }
    }

    fun verifyInvariants() {
        before.forEach { (path, hash) -> assertEquals(hash, sha(path), "Selected artifact changed: $path") }
        val expected = mutableSetOf("github-env", "stdout", "stderr")
        if (!wrongSdk) expected += "evidence.json"
        Files.list(directory).use { files -> assertEquals(expected, files.map { it.fileName.toString() }.toList().toSet()) }
        assertFalse(Files.exists(candidate), "Bootstrap must not create or execute a runtime candidate")
        verifyWorkflowConfiguration()
    }

    private fun verifyWorkflowConfiguration() {
        for ((file, jobs) in listOf("ci.yml" to listOf("build", "cross-platform-runtime"),
            "release.yml" to listOf("build-runtime"))) {
            val document = yaml(Files.readString(repository.resolve(".github/workflows/$file")))
            for (job in jobs) {
                val steps = (mapping(mapping(document["jobs"])[job])["steps"] as List<*>).map(::mapping)
                fun named(name: String): Map<*, *> = steps.single { it["name"] == name }
                val release = file == "release.yml"
                val versions = listOf(8, 11, 17)
                val sdks = versions.map { major ->
                    val setup = named("Set up JDK $major LTS qualification input")
                    assertEquals(major.toString(), mapping(setup["with"])["java-version"])
                    assertEquals("actions/setup-java@d1374f8ac74474200d16ef8ecee22c7ae7a51880", setup["uses"])
                    setup
                }
                val records = versions.map { named("Record JDK $it LTS qualification home") }
                val restore = named(if (release) "Set up JDK 21 build runtime" else "Set up exact JDK 21 build runtime")
                val gradle811 = named("Prepare the pinned Gradle 8.11.1 fixture input")
                assertEquals("bash scripts/prepare-gradle-8-11-1-fixture.sh", gradle811["run"])
                assertTrue(steps.indexOf(gradle811) < steps.indexOf(named("Verify and export native fixture profile")))
                if (release) assertEquals("startsWith(github.ref_name, 'v0.8.')", gradle811["if"])
                val gradle = named("Set up exact Gradle 9.8.1 fixture input")
                assertEquals("gradle/actions/setup-gradle@ed408507eac070d1f99cc633dbcf757c94c7933a", gradle["uses"])
                assertEquals("9.8.1", mapping(gradle["with"])["gradle-version"])
                val install = named("Install pinned protocol schema qualification packages without scripts")
                assertEquals("qualification/protocol-schema-toolchain", install["working-directory"])
                assertEquals("npm ci --ignore-scripts --no-audit --no-fund", install["run"])
                val bootstrap = named("Verify and export native fixture profile")
                val build = named(if (release) "Clean v0.8 build and complete standard regression" else "Build and test")
                assertTrue((sdks + records).all { steps.indexOf(it) < steps.indexOf(restore) })
                assertTrue(steps.indexOf(restore) < steps.indexOf(bootstrap))
                assertTrue(steps.indexOf(gradle) < steps.indexOf(bootstrap))
                assertTrue(steps.indexOf(install) < steps.indexOf(bootstrap))
                assertTrue(steps.indexOf(bootstrap) < steps.indexOf(build), "Fixture validation must precede qualification")
                assertEquals("bash", bootstrap["shell"])
                val command = bootstrap["run"].toString()
                val args = versions.map { "jdk$it" } + listOf("jdk21", "jdk25", "gradle98",
                    "node", "ajv-module", "candidate-root", "github-env", "evidence")
                args.forEach { assertTrue(command.contains("--$it "), "Missing selected input: $it") }
                assertTrue(command.contains("--java-scope lts"))
                assertFalse(command.contains("--jdk26 ") || command.contains("--jdk27 "))
                versions.forEach { major ->
                    assertTrue(command.contains("\"\$REFACTORKIT_CI_JDK${major}_HOME\""))
                }
                assertTrue(command.contains("\"\$GITHUB_WORKSPACE/qualification/protocol-schema-toolchain/node_modules/ajv\""))
                val javaSmoke = named(if (job == "build") "Run packaged authoritative Java diagnostics qualification"
                    else "Native packaged authoritative Java diagnostics qualification")
                assertTrue(steps.indexOf(bootstrap) < steps.indexOf(javaSmoke))
                assertTrue(javaSmoke["run"].toString().contains("--java-scope"), "$file/$job must select a Java smoke scope")
                val smokeScope = if (release) "\${{ startsWith(github.ref_name, 'v0.8.') && 'lts' || 'full' }}" else "lts"
                assertEquals(smokeScope, mapping(javaSmoke["env"])["REFACTORKIT_JAVA_SMOKE_SCOPE"])
                val clang = named("Prepare the pinned Clang 22.1.8 C qualification toolchain")
                assertEquals("bash scripts/prepare-clang-qualification-toolchain.sh", clang["run"])
                assertTrue(steps.indexOf(clang) < steps.indexOf(build))
                val node = named("Set up pinned TypeScript qualification runtime")
                assertEquals("22.18.0", mapping(node["with"])["node-version"])
                assertTrue(steps.indexOf(node) < steps.indexOf(bootstrap))
                val upload = named("Upload verified native fixture profile")
                assertEquals("actions/upload-artifact@v4", upload["uses"])
                assertEquals(".refactorkit/runs/ci-native-fixture-profile/profile.json", mapping(upload["with"])["path"])
                if (release) {
                    (sdks + records + listOf(gradle, install, bootstrap)).forEach {
                        assertEquals("startsWith(github.ref_name, 'v0.8.')", it["if"], "Historical release gates must remain unaffected")
                    }
                    assertEquals("\${{ always() && startsWith(github.ref_name, 'v0.8.') }}", upload["if"])
                    listOf("Verify 0.7.0 source version before building", "Requalify 0.7.0 through existing source and packaged gates").forEach {
                        assertEquals("github.ref_name == 'v0.7.0'", named(it)["if"])
                    }
                } else assertEquals("always()", upload["if"])
            }
        }
        val packageRoot = repository.resolve("qualification/protocol-schema-toolchain")
        val manifest = Json.parseToJsonElement(Files.readString(packageRoot.resolve("package.json"))).jsonObject
        assertEquals("true", manifest.text("private"))
        assertEquals(mapOf("ajv" to "6.15.0", "ajv2020" to "npm:ajv@8.20.0"),
            manifest.getValue("dependencies").jsonObject.mapValues { it.value.jsonPrimitive.content })
        assertFalse("scripts" in manifest)
        val lock = Json.parseToJsonElement(Files.readString(packageRoot.resolve("package-lock.json"))).jsonObject
        val ajv = lock.getValue("packages").jsonObject.getValue("node_modules/ajv").jsonObject
        assertEquals("6.15.0", ajv.text("version"))
        assertEquals("https://registry.npmjs.org/ajv/-/ajv-6.15.0.tgz", ajv.text("resolved"))
        assertEquals("sha512-fgFx7Hfoq60ytK2c7DhnF8jIvzYgOMxfugjLOSMHjLIPgenqa7S7oaagATUq99mV6IYvN2tRmC0wnTYX6iPbMw==", ajv.text("integrity"))
    }

    private val candidate: Path get() = directory.resolve("not-yet-built-runtime")
    private val expectedSelectors: Map<String, String> get() = linkedMapOf(
        "REFACTORKIT_JAVA_25_HOME" to inputs.getValue("jdk25").toString(),
        "REFACTORKIT_ENTRYPOINT_SDK25_HOME" to inputs.getValue("jdk25").toString(),
        "REFACTORKIT_GRADLE_9_8_1_EXECUTABLE" to inputs.getValue("gradle98").toString(),
        "REFACTORKIT_SCHEMA_NODE" to inputs.getValue("node").toString(),
        "REFACTORKIT_SCHEMA_AJV_MODULE" to inputs.getValue("ajv-module").toString(),
        "REFACTORKIT_ENTRYPOINT_CANDIDATE_ROOT" to candidate.toString(),
    ).apply {
        if (lts) {
            listOf(8, 11, 17).forEach { major ->
                put("REFACTORKIT_JAVA_${major}_HOME", inputs.getValue("jdk$major").toString())
            }
        } else {
            put("REFACTORKIT_JDK26_HOME", inputs.getValue("jdk26").toString())
            put("REFACTORKIT_JDK27_HOME", inputs.getValue("jdk27").toString())
            put("REFACTORKIT_SDK27_HOME", inputs.getValue("jdk27").toString())
        }
    }

    override fun close() {
        Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    companion object {
        private const val SENTINEL = "EXISTING_FIXTURE_SENTINEL=preserve\n"
        private fun sha(path: Path): String = Files.newInputStream(path).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content
        private fun mapping(value: Any?): Map<*, *> = value as Map<*, *>
        private fun yaml(text: String): Map<*, *> {
            val optionsClass = Class.forName("org.yaml.snakeyaml.LoaderOptions")
            val options = optionsClass.getConstructor().newInstance()
            optionsClass.getMethod("setAllowDuplicateKeys", Boolean::class.javaPrimitiveType).invoke(options, false)
            val safe = Class.forName("org.yaml.snakeyaml.constructor.SafeConstructor")
                .getConstructor(optionsClass).newInstance(options)
            val yamlClass = Class.forName("org.yaml.snakeyaml.Yaml")
            val parser = yamlClass.getConstructor(Class.forName("org.yaml.snakeyaml.constructor.BaseConstructor")).newInstance(safe)
            return mapping(yamlClass.getMethod("load", String::class.java).invoke(parser, text))
        }
        fun capture(case: String): NativeFixtureBootstrapFixture {
            require(case in setOf("complete verified inputs", "SDK25 supplied as SDK26",
                "complete verified LTS inputs", "SDK8 supplied as SDK11"))
            val lts = case in setOf("complete verified LTS inputs", "SDK8 supplied as SDK11")
            val selectedSdkVariables = if (lts) mapOf(8 to listOf("REFACTORKIT_JAVA_8_HOME"),
                11 to listOf("REFACTORKIT_JAVA_11_HOME"), 17 to listOf("REFACTORKIT_JAVA_17_HOME"))
            else mapOf(26 to listOf("REFACTORKIT_JDK26_HOME"),
                27 to listOf("REFACTORKIT_JDK27_HOME", "REFACTORKIT_SDK27_HOME"))
            assumeTrue(selectedSdkVariables.values.all { names -> names.any { !System.getenv(it).isNullOrBlank() } },
                "Select all inputs for the requested native SDK qualification profile")
            val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
            fun explicit(vararg names: String): Path = Path.of(requireNotNull(names.firstNotNullOfOrNull(System::getenv)) {
                "Missing explicitly selected native fixture input: ${names.joinToString()}"
            }).toRealPath()
            val inputs = linkedMapOf(
                "jdk21" to Path.of(System.getProperty("java.home")).toRealPath(),
                "jdk25" to explicit("REFACTORKIT_ENTRYPOINT_SDK25_HOME", "REFACTORKIT_JAVA_25_HOME"),
                "gradle98" to explicit("REFACTORKIT_GRADLE_9_8_1_EXECUTABLE"),
                "node" to explicit("REFACTORKIT_SCHEMA_NODE"),
                "ajv-module" to explicit("REFACTORKIT_SCHEMA_AJV_MODULE"),
            )
            selectedSdkVariables.forEach { (major, names) -> inputs["jdk$major"] = explicit(*names.toTypedArray()) }
            return NativeFixtureBootstrapFixture(root, Files.createTempDirectory("refactorkit-native-profile-"),
                inputs, if (lts) "lts" else "extended", case.contains(" supplied as "))
        }
    }
}
