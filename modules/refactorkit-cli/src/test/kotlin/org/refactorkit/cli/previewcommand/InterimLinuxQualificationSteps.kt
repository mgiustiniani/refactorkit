package org.refactorkit.cli.previewcommand

import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import io.cucumber.gherkin.GherkinParser
import io.cucumber.tagexpressions.TagExpressionParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Operational workflow fixture: parses actual YAML and executes only its scope preflight. */
class InterimLinuxQualificationSteps {
    private lateinit var event: String
    private lateinit var target: String
    private lateinit var repository: Path
    private lateinit var ci: Map<*, *>
    private lateinit var release: Map<*, *>
    private var admitted = true
    private var selected: List<String> = emptyList()
    private lateinit var sourceInventory: CStorySourceGateInventory
    private var publicationDraft: Boolean? = null
    private var publicationLatest: String? = null

    @When("the repository workflow evaluates release publication visibility")
    fun publicationVisibility() {
        val publication = steps(mapping(mapping(release["jobs"])["publish"]))
            .single { it["uses"] == "softprops/action-gh-release@v2" }
        val value = mapping(publication["with"])["draft"]
        publicationDraft = if (value is Boolean) value else Expression(requireNotNull(value).toString()).evaluate() as Boolean
        publicationLatest = Expression(requireNotNull(mapping(publication["with"])["make_latest"]).toString()).evaluate().toString()
    }

    @Then("the release publication visibility is {string}")
    fun publicationDisposition(expected: String) {
        require(expected in setOf("draft", "existing public policy"))
        assertEquals(expected == "draft", publicationDraft,
            "Publication visibility for $target; binding does not grant independent download/review approval")
        assertEquals(if (expected == "draft") "false" else "true", publicationLatest,
            "An unreviewed draft must not be requested as the latest public release")
    }

    @When("source regression inventories the admitted C Stories")
    fun inventorySourceStories() {
        sourceInventory = CStorySourceGateInventory.capture(repository)
    }

    @Then("each admitted C feature has exactly one source runner")
    fun sourceRunnersCoverCStories() {
        assertTrue(sourceInventory.featureCount > 0)
        assertTrue(sourceInventory.runnerGaps.isEmpty(), sourceInventory.runnerGaps.joinToString("\n"))
    }

    @Then("the default source filter selects every admitted C scenario")
    fun defaultFilterAdmitsCStories() {
        assertTrue(sourceInventory.caseCount > 0)
        assertTrue(sourceInventory.filterGaps.isEmpty(), sourceInventory.filterGaps.joinToString("\n"))
        println("C source regression inventory: ${sourceInventory.featureCount} features, ${sourceInventory.caseCount} cases; complete runner and default-filter coverage")
    }

    @Given("an interim qualification request uses {string} and {string}")
    fun request(event: String, target: String) {
        this.event = event; this.target = target
        repository = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve(".github/workflows/ci.yml")) }
        ci = yaml(Files.readString(repository.resolve(".github/workflows/ci.yml")))
        release = yaml(Files.readString(repository.resolve(".github/workflows/release.yml")))
    }

    @When("the repository workflow selects its qualification jobs")
    fun select() {
        val jobs = mapping(if (event == "release") release["jobs"] else ci["jobs"])
        if (event != "release") {
            val gate = jobs["qualification-scope"] as? Map<*, *>
            if (gate != null) {
                val step = steps(gate).single()
                assertEquals("python", step["shell"])
                val process = ProcessBuilder("/usr/bin/python3", "-c", step["run"].toString()).apply {
                    environment()["QUALIFICATION_EVENT"] = event
                    environment()["QUALIFICATION_PLATFORM"] = if (event == "workflow_dispatch") target else ""
                    redirectErrorStream(true)
                }.start()
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly(); process.waitFor(); error("Scope preflight did not terminate")
                }
                admitted = process.exitValue() == 0
            }
        }
        selected = if (!admitted) emptyList() else jobs.filterKeys { it != "qualification-scope" }.values.flatMap { value ->
            val job = mapping(value)
            val matrix = (job["strategy"] as? Map<*, *>)?.get("matrix") as? Map<*, *>
            if (matrix == null) listOf(job["runs-on"].toString()) else {
                val include = matrix["include"]
                val rows = if (include is String) Expression(include).evaluate() else include
                when (rows) {
                    is JsonArray -> rows.map { (it as JsonObject).getValue("os").let { os -> (os as JsonPrimitive).content } }
                    is List<*> -> rows.map { mapping(it)["os"].toString() }
                    else -> error("Unsupported workflow matrix: $rows")
                }
            }
        }
    }

    @Then("the workflow host disposition is {string}")
    fun disposition(expected: String) {
        when (expected) {
            "linux only" -> {
                assertTrue(admitted)
                assertTrue(selected.isNotEmpty())
                assertTrue(selected.all { it in setOf("ubuntu-latest", "ubuntu-24.04") }, "Non-Linux runners selected: $selected")
            }
            "refused" -> { assertFalse(admitted); assertTrue(selected.isEmpty()) }
            "stable matrix" -> assertEquals(setOf("ubuntu-latest", "windows-2022", "macos-15-intel", "macos-15"), selected.toSet())
            else -> error("Unexpected disposition: $expected")
        }
        val jobs = mapping(ci["jobs"])
        assertNotNull(jobs["qualification-scope"], "Unsupported dispatch needs a failing prerequisite")
        jobs.filterKeys { it != "qualification-scope" }.forEach { (name, value) ->
            val job = mapping(value)
            val needs = job["needs"].let { if (it is List<*>) it else listOf(it) }
            assertTrue("qualification-scope" in needs, "$name bypasses the scope prerequisite")
            assertFalse(job["if"].toString().contains("always()"), "$name bypasses a failed prerequisite")
            assertFalse(job["continue-on-error"] == true, "$name masks qualification failure")
        }
    }

    @Then("interim selection preserves native verification and historical release guards")
    fun qualificationGuards() {
        val jobs = mapping(ci["jobs"])
        val nativeFinalizers = mapOf(
            "maven-move-class-native" to "finalize-native-maven-move-class-authority.py",
            "k1-k2-shared-foundations-native" to "finalize-native-k1-k2-shared-foundations.py",
            "k5-move-next-native" to "finalize-native-k5-move-next.py",
            "k5-bounded-completion-native" to "finalize-native-k5-completion.py",
        )
        nativeFinalizers.forEach { (name, script) ->
            val job = mapping(jobs[name])
            val finalizer = steps(job).single { it["run"].toString().contains("\"scripts/$script\"") }
            assertFalse(finalizer["if"] == false)
            assertTrue(finalizer["run"].toString().contains("check=True"))
            assertTrue(steps(job).any { it["uses"] == "actions/upload-artifact@v4" && it["if"] == "always()" })
        }
        assertTrue(steps(mapping(jobs["cross-platform-runtime"])).any { it["name"] == "Verify native runtime archive" })
        val runtime = mapping(mapping(release["jobs"])["build-runtime"])
        val releaseSteps = steps(runtime)
        listOf("Verify 0.7.0 source version before building", "Requalify 0.7.0 through existing source and packaged gates").forEach { name ->
            assertEquals("github.ref_name == 'v0.7.0'", releaseSteps.single { it["name"] == name }["if"])
        }
        assertTrue(releaseSteps.any { it["uses"] == "actions/attest-build-provenance@v2" })
        assertTrue(releaseSteps.any { it["uses"] == "anchore/sbom-action@v0" })
        val publication = steps(mapping(mapping(release["jobs"])["publish"]))
            .single { it["uses"] == "softprops/action-gh-release@v2" }
        assertEquals("\${{ github.ref_name == 'v0.7.0' || startsWith(github.ref_name, 'v0.8.') }}", mapping(publication["with"])["draft"])
    }

    @Then("the {string} evidence binds the checked out package version")
    fun nativeKotlinPackageVersion(job: String) {
        val step = steps(mapping(mapping(ci["jobs"])[job])).single {
            it["name"] == "Finalize fail-closed native receipt"
        }
        val finalizer = requireNotNull(step["run"]).toString()
        val flag = "--package-version-from-source"
        val selected = if (finalizer.contains("\"$flag\"")) flag else "legacy"
        val log = Files.createTempFile("rk-native-kotlin-version-", ".log")
        try {
            val process = ProcessBuilder(
                "python3", "-B", "scripts/native-kotlin-version-story-fixture.py", job, selected,
            ).directory(repository.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("Native Kotlin receipt fixture timed out for $job")
            }
            assertEquals(0, process.exitValue(), Files.readString(log))
        } finally {
            Files.deleteIfExists(log)
        }
    }

    @When("the C qualification toolchain is prepared for workflow test tasks")
    fun prepareCQualificationToolchain() {
        val ciSteps = steps(mapping(mapping(ci["jobs"])["build"]))
        val ciSetup = ciSteps.single { it["name"] == "Prepare the pinned Clang 22.1.8 C qualification toolchain" }
        assertEquals("bash scripts/prepare-clang-qualification-toolchain.sh", ciSetup["run"])
        assertEquals("bash", ciSetup["shell"])
        assertEquals(null, ciSetup["if"])
        assertTrue(ciSteps.indexOf(ciSetup) < ciSteps.indexOf(ciSteps.single { it["name"] == "Build and test" }))

        val releaseSteps = steps(mapping(mapping(release["jobs"])["build-runtime"]))
        val releaseSetup = releaseSteps.single { it["name"] == "Prepare the pinned Clang 22.1.8 C qualification toolchain" }
        assertEquals("bash scripts/prepare-clang-qualification-toolchain.sh", releaseSetup["run"])
        assertEquals("startsWith(github.ref_name, 'v0.8.')", releaseSetup["if"])
        assertTrue(releaseSteps.indexOf(releaseSetup) < releaseSteps.indexOf(releaseSteps.single { it["name"] == "Clean build and test" }))
    }

    @Then("CI verifies the official LLVM 22.1.8 Linux archive and its tool identities")
    fun verifyPinnedToolchain() {
        val script = Files.readString(repository.resolve("scripts/prepare-clang-qualification-toolchain.sh"))
        listOf(
            "https://github.com/llvm/llvm-project/releases/download/llvmorg-22.1.8/LLVM-22.1.8-Linux-X64.tar.xz",
            "df0e1ecf16caf3489a272a5eea4eec9b0d82878f6477fa309504f918a0006384",
            "31a40dc31f3c15a47aa119aa148f339bf363d8f61202ddbdacbd3f24b71ba113",
            "aacb4651e1886b98c5413369fc1789f4ca2d4fa5b94fdf0de4f48c98a52d4324",
            "b60fe916a9d98bfd0f317a210f6a06dfde1217ebb0b605da697ef211208d3bcf",
            "a99d309f3cadea7fd7203ebec873bc165fc0c9ee0694ba4015c9902a5debf3a3",
            "sha256sum --check --strict",
            "-print-resource-dir",
            "\"\$clang\" --version --no-default-config",
            "\"\$clang\" -dumpmachine --no-default-config",
            "\"\$clang\" -print-resource-dir --no-default-config",
            "include/stddef.h",
            "LLVM-22.1.8-Linux-X64",
            "\${archive_root}/bin/clang-22",
            "\${archive_root}/bin/clangd",
            "\${archive_root}/bin/clang-format",
            "\${archive_root}/lib/libclang-cpp.so.22.1",
            "\${archive_root}/lib/clang/22",
            "include/llvm/Support/LICENSE.TXT",
            "rm -f \"\$archive\"",
            "GITHUB_ENV",
        ).forEach { assertTrue(script.contains(it), "Pinned toolchain setup is missing: $it") }
    }

    @Then("every C unit and CLI test task receives that toolchain explicitly")
    fun forwardToolchainToTests() {
        val property = "refactorkit.c.qualificationToolchain"
        val environment = "REFACTORKIT_C_QUALIFICATION_TOOLCHAIN"
        val gradle = Files.readString(repository.resolve("build.gradle.kts"))
        assertTrue(gradle.contains("tasks.withType<Test>().configureEach"), "Shared Gradle test configuration is missing")
        assertTrue(gradle.contains("project.path in setOf(\":modules:refactorkit-c\", \":modules:refactorkit-cli\")"))
        assertTrue(gradle.contains("project.providers.environmentVariable(\"$environment\")"), "The shared build does not read the CI qualification path")
        assertTrue(gradle.contains("systemProperty(\"$property\", it)"), "The shared build does not pass the qualification path to Test JVMs")
    }

    @Then("default C qualification retains native evidence in distinct task report directories")
    fun retainNativeEvidenceByDefault() {
        val selectors = mapOf(
            "compilerRuntimeEvidence" to "REFACTORKIT_C_COMPILER_RUNTIME_EVIDENCE",
            "formatterRuntimeEvidence" to "REFACTORKIT_C_FORMATTER_RUNTIME_EVIDENCE",
            "runtimeDiscoveryEvidence" to "REFACTORKIT_C_RUNTIME_DISCOVERY_EVIDENCE",
            "runtimeManifestEvidence" to "REFACTORKIT_C_RUNTIME_MANIFEST_EVIDENCE",
        )
        val taskReports = repository.resolve("modules/refactorkit-cli/build/reports/c-qualification/test")
        val destinations = selectors.map { (key, environmentKey) ->
            val actual = Path.of(assertNotNull(System.getProperty("refactorkit.c.$key"),
                "Default Gradle source tests did not provision $key")).toAbsolutePath().normalize()
            val configuredDestination = System.getenv(environmentKey)?.let(Path::of)?.toAbsolutePath()?.normalize()
            if (configuredDestination == null) assertEquals(taskReports, actual.parent, "Default native reports must belong to this task/module")
            else assertEquals(configuredDestination, actual, "An explicit native report selector must be retained")
            assertTrue(Files.isDirectory(actual), "Gradle did not create $actual before running the Story")
            actual
        }
        assertEquals(selectors.size, destinations.toSet().size, "Independent native consumers must not share their reports")
    }

    @Then("v0.8 release tests use the same qualified toolchain without changing v0.7 gates")
    fun preserveReleaseBoundary() {
        val releaseSteps = steps(mapping(mapping(release["jobs"])["build-runtime"]))
        val setup = releaseSteps.single { it["name"] == "Prepare the pinned Clang 22.1.8 C qualification toolchain" }
        assertEquals("startsWith(github.ref_name, 'v0.8.')", setup["if"])
        val genericBuild = releaseSteps.single { it["name"] == "Clean build and test" }
        assertEquals("\${{ github.ref_name != 'v0.7.0' && !startsWith(github.ref_name, 'v0.8.') }}", genericBuild["if"])
        assertEquals("\${{ matrix.gradle }} clean build --no-daemon", genericBuild["run"])
        val capture = releaseSteps.single { it["name"] == "Capture exact v0.8 source and native qualification inputs" }
        val currentBuild = releaseSteps.single { it["name"] == "Clean v0.8 build and complete standard regression" }
        assertEquals("startsWith(github.ref_name, 'v0.8.')", capture["if"])
        assertEquals("startsWith(github.ref_name, 'v0.8.')", currentBuild["if"])
        assertTrue(currentBuild["run"].toString().contains("clean build goldenTest --no-daemon --no-build-cache"))
        assertTrue(releaseSteps.indexOf(setup) < releaseSteps.indexOf(capture))
        assertTrue(releaseSteps.indexOf(capture) < releaseSteps.indexOf(currentBuild))
        val historicalBuild = releaseSteps.single { it["name"] == "Clean 0.7.0 build and complete standard regression" }
        assertEquals("github.ref_name == 'v0.7.0'", historicalBuild["if"])
        assertTrue(releaseSteps.indexOf(setup) < releaseSteps.indexOf(currentBuild))
    }

    // SnakeYAML is already on the runtime through the Java recipe module. No dependency is added.
    private fun yaml(text: String): Map<*, *> {
        val optionsClass = Class.forName("org.yaml.snakeyaml.LoaderOptions")
        val options = optionsClass.getConstructor().newInstance()
        val safe = Class.forName("org.yaml.snakeyaml.constructor.SafeConstructor")
            .getConstructor(optionsClass).newInstance(options)
        val yamlClass = Class.forName("org.yaml.snakeyaml.Yaml")
        val parser = yamlClass.getConstructor(Class.forName("org.yaml.snakeyaml.constructor.BaseConstructor")).newInstance(safe)
        return mapping(yamlClass.getMethod("load", String::class.java).invoke(parser, text))
    }

    private fun mapping(value: Any?): Map<*, *> = value as? Map<*, *> ?: error("Expected YAML object: $value")
    private fun steps(job: Map<*, *>): List<Map<*, *>> = (job["steps"] as List<*>).map(::mapping)

    /** Small independent evaluator for the actual matrix's documented expression subset. */
    private inner class Expression(raw: String) {
        private val body = raw.trim().removePrefix("\${{").removeSuffix("}}").trim()
        private val lexer = Regex("'[^']*'|\\|\\||&&|==|[(),]|[A-Za-z_][A-Za-z0-9_.]*")
        private val tokens = lexer.findAll(body).map { it.value }.toList()
        private var index = 0
        fun evaluate(): Any? {
            assertEquals(body.filterNot(Char::isWhitespace), tokens.joinToString("").filterNot(Char::isWhitespace))
            return disjunction().also { assertEquals(tokens.size, index, "Unconsumed workflow expression") }
        }
        private fun disjunction(): Any? {
            var left = conjunction()
            while (take("||")) { val right = conjunction(); left = if (truth(left)) left else right }
            return left
        }
        private fun conjunction(): Any? {
            var left = equality()
            while (take("&&")) { val right = equality(); left = if (truth(left)) right else left }
            return left
        }
        private fun equality(): Any? {
            val left = atom()
            return if (take("==")) left == atom() else left
        }
        private fun atom(): Any? {
            if (take("(")) return disjunction().also { expect(")") }
            val token = tokens.getOrNull(index++) ?: error("Incomplete workflow expression")
            if (token.startsWith("'")) return token.removeSurrounding("'")
            return when (token) {
                "github.event_name" -> event
                "github.ref_name" -> target
                "inputs.qualification_platform" -> if (event == "workflow_dispatch") target else ""
                "fromJSON" -> { expect("("); val json = disjunction(); expect(")"); Json.parseToJsonElement(json.toString()) }
                "startsWith" -> {
                    expect("("); val value = disjunction(); expect(","); val prefix = disjunction(); expect(")")
                    value.toString().startsWith(prefix.toString(), ignoreCase = true)
                }
                "contains" -> {
                    expect("("); val value = disjunction(); expect(","); val item = disjunction(); expect(")")
                    value.toString().contains(item.toString(), ignoreCase = true)
                }
                else -> error("Unsupported workflow expression token: $token")
            }
        }
        private fun truth(value: Any?): Boolean = value != null && value != false && value != ""
        private fun take(token: String): Boolean = if (tokens.getOrNull(index) == token) { index++; true } else false
        private fun expect(token: String) = assertTrue(take(token), "Expected '$token' in workflow expression")
    }
}

/** Read-only scheduling evidence from actual source suites and Cucumber's Gherkin/tag parsers. */
private data class CStorySourceGateInventory(
    val featureCount: Int,
    val caseCount: Int,
    val runnerGaps: List<String>,
    val filterGaps: List<String>,
) {
    companion object {
        fun capture(repository: Path): CStorySourceGateInventory {
            val features = Files.list(repository.resolve("features")).use { paths ->
                paths.filter { path ->
                    val name = path.fileName.toString()
                    name.endsWith(".feature") && (name.startsWith("c-") || name.startsWith("interim-"))
                }.sorted().toList()
            }
            val runnerResources = mutableMapOf<String, MutableList<Path>>()
            val resource = Regex("@SelectClasspathResource\\(\"([^\"]+)\"\\)")
            Files.list(repository.resolve("modules")).use { modules ->
                modules.forEach { module ->
                    val testSources = module.resolve("src/test")
                    if (Files.isDirectory(testSources)) Files.walk(testSources).use { paths ->
                        paths.filter { Files.isRegularFile(it) && it.fileName.toString().let { name ->
                            name.endsWith(".kt") || name.endsWith(".java")
                        } }.forEach { source ->
                            val text = Files.readString(source)
                            if ("@IncludeEngines(\"cucumber\")" in text) resource.findAll(text).forEach { match ->
                                runnerResources.getOrPut(match.groupValues[1]) { mutableListOf() }.add(repository.relativize(source))
                            }
                        }
                    }
                }
            }
            val runnerGaps = features.mapNotNull { feature ->
                val owners = runnerResources[feature.fileName.toString()].orEmpty()
                if (owners.size == 1) null else "${feature.fileName}: expected one source runner, found ${owners.size}: $owners"
            }
            // A module's default belongs to its own Test task. The LSP module
            // has no default tag restriction; CLI's allowlist must not govern it.
            val defaultFilters = runnerResources.values.flatten().map { it.subpath(0, 2) }.distinct().associateWith { module ->
                val file = repository.resolve(module).resolve("src/test/resources/cucumber-filter-tags.txt")
                TagExpressionParser.parse(if (Files.isRegularFile(file)) Files.readString(file).trim() else "")
            }
            val parser = GherkinParser.builder().includeSource(false).includeGherkinDocument(false).includePickles(true).build()
            val filterGaps = mutableListOf<String>()
            var caseCount = 0
            features.forEach { feature ->
                val owner = runnerResources[feature.fileName.toString()]?.singleOrNull()
                val expression = owner?.let { defaultFilters.getValue(it.subpath(0, 2)) }
                parser.parse(feature).use { envelopes -> envelopes.forEach { envelope ->
                    require(envelope.parseError.isEmpty) { "Invalid admitted feature $feature: ${envelope.parseError}" }
                    envelope.pickle.ifPresent { pickle ->
                        caseCount++
                        if (expression?.evaluate(pickle.tags.map { it.name }) == false) {
                            filterGaps += "${feature.fileName}:${pickle.name}: excluded by the default source filter"
                        }
                    }
                } }
            }
            return CStorySourceGateInventory(features.size, caseCount, runnerGaps, filterGaps)
        }
    }
}
