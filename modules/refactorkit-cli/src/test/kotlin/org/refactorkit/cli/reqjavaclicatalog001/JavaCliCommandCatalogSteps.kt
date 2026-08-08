package org.refactorkit.cli.reqjavaclicatalog001

import io.cucumber.datatable.DataTable
import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.refactorkit.cli.RefactorKitCli
import org.refactorkit.core.RefactorKitVersion
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URI
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitResult.CONTINUE
import java.nio.file.FileVisitResult.SKIP_SUBTREE
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.security.Permission
import java.util.UUID
import kotlin.io.path.exists
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JavaCliCommandCatalogSteps {
    private data class Invocation(
        val arguments: List<String>,
        val exitCode: Int,
        val stdout: ByteArray,
        val stderr: ByteArray,
    )

    private data class ContractField(
        val scope: String,
        val position: Int,
        val name: String,
        val jsonType: String,
    )

    private data class PublicRoute(
        val name: String,
        val operation: String,
    )

    private lateinit var repositoryRoot: Path
    private lateinit var installedRoot: Path
    private lateinit var installedExecutable: Path
    private lateinit var sourceBuiltCodeLocation: Path
    private lateinit var repositoryManifestBefore: Map<String, String>
    private lateinit var installationManifestBefore: Map<String, String>
    private lateinit var pinnedCapabilities: ByteArray
    private lateinit var pinnedHelp: ByteArray
    private lateinit var contractFields: List<ContractField>
    private lateinit var parserMethod: java.lang.reflect.Method
    private lateinit var missingParserProbeRoot: Path

    private val catalogInvocations = mutableListOf<Invocation>()
    private val allInvocations = mutableListOf<Invocation>()
    private val routeProbes = linkedMapOf<String, Invocation>()
    private val observedSecurityManagers = mutableListOf<BoundarySecurityManager>()
    private var semanticSessionAttempts = 0
    private var failClosedCliCount = 0
    private var parserOptionGrammarVerified = false
    private var routeContractVerified = false
    private var boundaryManifestsVerified = false

    private lateinit var helpInvocation: Invocation
    private lateinit var capabilitiesInvocation: Invocation
    private lateinit var expectedCatalogueBytes: ByteArray

    @After
    @Suppress("DEPRECATION")
    fun restoreSecurityManagerIfNeeded() {
        if (System.getSecurityManager() != null) {
            System.setSecurityManager(null)
        }
    }

    @Given("the source-built RefactorKit 0.7.0-SNAPSHOT CLI entrypoint runs locally on Linux with JDK 21 and the current CPU architecture")
    fun establishSourceBuiltRuntime() {
        repositoryRoot = locateRepositoryRoot()
        installedRoot = Path.of(System.getProperty("user.home"), ".local", "share", "refactorkit")
            .toAbsolutePath().normalize()
        installedExecutable = installedRoot.resolve("bin/refactorkit")
        sourceBuiltCodeLocation = codeLocation(RefactorKitCli::class.java)

        assertEquals("0.7.0-SNAPSHOT", RefactorKitVersion.VERSION)
        assertTrue(System.getProperty("os.name").contains("Linux", ignoreCase = true))
        assertEquals(21, Runtime.version().feature())
        assertTrue(System.getProperty("os.arch").isNotBlank())
        assertTrue(sourceBuiltCodeLocation.startsWith(repositoryRoot), sourceBuiltCodeLocation.toString())
        assertTrue(
            sourceBuiltCodeLocation.toString().replace('\\', '/').contains("/modules/refactorkit-cli/build/"),
            sourceBuiltCodeLocation.toString(),
        )
        assertFalse(sourceBuiltCodeLocation.startsWith(installedRoot), sourceBuiltCodeLocation.toString())

        pinnedCapabilities = resourceBytes(CAPABILITIES_FIXTURE)
        pinnedHelp = resourceBytes(HELP_FIXTURE)
        assertEquals(PINNED_CAPABILITIES_SHA256, sha256(pinnedCapabilities))
        assertEquals(PINNED_HELP_SHA256, sha256(pinnedHelp))

        repositoryManifestBefore = captureManifest(repositoryRoot, excludeRepositoryBuildState = true)
        installationManifestBefore = captureManifest(installedRoot, excludeRepositoryBuildState = false)
        assertFalse(repositoryRoot.resolve(".refactorkit").exists(), "test repository already contains .refactorkit")
    }

    @Given("the installed RefactorKit executable is guarded by an invocation tripwire and is neither selected nor invoked")
    @Suppress("DEPRECATION")
    fun guardInstalledExecutable() {
        assertTrue(installedExecutable.exists(), "installed executable required for the non-selection tripwire")
        assertFalse(sourceBuiltCodeLocation.startsWith(installedRoot))

        val selfTest = BoundarySecurityManager(repositoryRoot, installedRoot)
        val refusal = assertFailsWith<SecurityException> {
            selfTest.checkExec(installedExecutable.toString())
        }
        assertTrue(refusal.message.orEmpty().contains("process execution"))
        assertEquals(listOf("exec:${installedExecutable.toAbsolutePath().normalize()}"), selfTest.violations)

        val original = System.getSecurityManager()
        val installProbe = BoundarySecurityManager(repositoryRoot, installedRoot)
        System.setSecurityManager(installProbe)
        try {
            assertEquals(installProbe, System.getSecurityManager())
        } finally {
            System.setSecurityManager(original)
        }
        assertTrue(installProbe.violations.isEmpty())
    }

    @Given("the v1 command-catalogue contract admits exactly these fields in this order:")
    fun fixClosedContract(table: DataTable) {
        contractFields = table.asMaps().map { row ->
            ContractField(
                scope = row.getValue("scope"),
                position = row.getValue("position").toInt(),
                name = row.getValue("field"),
                jsonType = row.getValue("JSON type"),
            )
        }
        assertEquals(EXPECTED_CONTRACT_FIELDS, contractFields)
    }

    @Given("workspace opening, scanning, planning, locking, WAL, transaction, editing, and installation mutation boundaries are instrumented fail-closed")
    fun armFailClosedBoundaries() {
        assertTrue(::repositoryManifestBefore.isInitialized)
        assertTrue(::installationManifestBefore.isInitialized)

        val cli = newFailClosedCli()
        val scannerField = RefactorKitCli::class.java.getDeclaredField("scanner")
        assertTrue(scannerField.trySetAccessible())
        assertNull(scannerField.get(cli), "scanner reflection tripwire was not armed")

        val selfTest = BoundarySecurityManager(repositoryRoot, installedRoot)
        val sourcePath = repositoryRoot.resolve("modules/refactorkit-cli/src/main/kotlin")
        assertFailsWith<SecurityException> { selfTest.checkRead(sourcePath.toString()) }
        assertFailsWith<SecurityException> { selfTest.checkWrite(repositoryRoot.resolve("tripwire-write").toString()) }
        assertFailsWith<SecurityException> { selfTest.checkDelete(repositoryRoot.resolve("tripwire-delete").toString()) }
        assertFailsWith<SecurityException> { selfTest.checkExec("refactorkit") }
        assertEquals(4, selfTest.violations.size)
    }

    @Given("a public-parser reachability probe resolves route and option grammar through the production parser but stops before command execution")
    fun establishParserReachabilityProbe() {
        parserMethod = RefactorKitCli::class.java.getDeclaredMethod("parseOptions", List::class.java)
        assertTrue(parserMethod.trySetAccessible())
        val cli = newFailClosedCli()

        assertParsed(
            cli,
            listOf("--json"),
            expectedOptions = emptyMap(),
            expectedFlags = setOf("json"),
            expectedPositionals = emptyList(),
        )
        assertParsed(
            cli,
            listOf("--module-name", "probe", "--parent-pom", "probe/pom.xml", "--apply"),
            expectedOptions = mapOf("module-name" to "probe", "parent-pom" to "probe/pom.xml"),
            expectedFlags = setOf("apply"),
            expectedPositionals = emptyList(),
        )
        assertParsed(
            cli,
            listOf("--from", "source", "--to", "destination", "--root", "probe-root", "--apply"),
            expectedOptions = mapOf("from" to "source", "to" to "destination", "root" to "probe-root"),
            expectedFlags = setOf("apply"),
            expectedPositionals = emptyList(),
        )
        assertParsed(
            cli,
            listOf("--old-module-dir", "old", "--new-module-dir", "new", "--new-artifact-id", "new-id"),
            expectedOptions = mapOf(
                "old-module-dir" to "old",
                "new-module-dir" to "new",
                "new-artifact-id" to "new-id",
            ),
            expectedFlags = emptySet(),
            expectedPositionals = emptyList(),
        )

        missingParserProbeRoot = Path.of(
            System.getProperty("java.io.tmpdir"),
            "refactorkit-command-catalog-parser-probe-${UUID.randomUUID()}",
        ).toAbsolutePath().normalize()
        assertFalse(missingParserProbeRoot.exists())

        routeProbes["java create-module"] = guardedInvoke(listOf("java", "create-module"))
        routeProbes["java move-across-maven-modules"] = guardedInvoke(
            listOf("java", "move-across-maven-modules", "--root", missingParserProbeRoot.toString()),
        )
        routeProbes["java rename-module"] = guardedInvoke(listOf("java", "rename-module"))

        assertEquals(2, routeProbes.getValue("java create-module").exitCode)
        assertContentEquals(
            "create-module requires --module-name\n".toByteArray(Charsets.UTF_8),
            routeProbes.getValue("java create-module").stderr,
        )
        assertEquals(1, routeProbes.getValue("java move-across-maven-modules").exitCode)
        assertContentEquals(
            "Path does not exist: $missingParserProbeRoot\n".toByteArray(Charsets.UTF_8),
            routeProbes.getValue("java move-across-maven-modules").stderr,
        )
        assertEquals(2, routeProbes.getValue("java rename-module").exitCode)
        assertContentEquals(
            "rename-module requires --old-module-dir\n".toByteArray(Charsets.UTF_8),
            routeProbes.getValue("java rename-module").stderr,
        )
        routeProbes.values.forEach { probe ->
            assertTrue(probe.stdout.isEmpty(), probe.stdoutText())
            assertFalse(probe.stderrText().contains("Unknown java subcommand"), probe.stderrText())
        }
        parserOptionGrammarVerified = true
    }

    @When("the source-built public parser invokes {string} twice with separately captured stdout and stderr bytes")
    fun invokeCommandCatalogueTwice(commandLine: String) {
        assertEquals("refactorkit commands --json", commandLine)
        val arguments = commandLine.split(' ').drop(1)
        catalogInvocations += guardedInvoke(arguments)
        catalogInvocations += guardedInvoke(arguments)
        assertEquals(2, catalogInvocations.size)
        assertFalse(catalogInvocations[0].stdout === catalogInvocations[1].stdout)
        assertFalse(catalogInvocations[0].stderr === catalogInvocations[1].stderr)
    }

    @When("the same source-built entrypoint invokes top-level help and {string}")
    fun invokeHelpAndCapabilities(commandLine: String) {
        assertEquals("refactorkit capabilities", commandLine)
        helpInvocation = guardedInvoke(listOf("--help"))
        capabilitiesInvocation = guardedInvoke(listOf("capabilities"))
        assertEquals(0, helpInvocation.exitCode)
        assertEquals(0, capabilitiesInvocation.exitCode)
        assertTrue(helpInvocation.stderr.isEmpty(), helpInvocation.stderrText())
        assertTrue(capabilitiesInvocation.stderr.isEmpty(), capabilitiesInvocation.stderrText())
    }

    @When("the reachability probe resolves these exact public token routes:")
    fun bindReachableRoutesToRequirementOperations(table: DataTable) {
        val routes = table.asMaps().map { row ->
            PublicRoute(row.getValue("route"), row.getValue("semantic operation identity"))
        }
        assertEquals(EXPECTED_ROUTES, routes)
        assertEquals(EXPECTED_ROUTES.map(PublicRoute::name), routeProbes.keys.toList())
        assertTrue(parserOptionGrammarVerified)
        assertEquals(0, semanticSessionAttempts)
        assertTrue(observedSecurityManagers.all { it.violations.isEmpty() })
        assertTrue(allInvocations.none { it.arguments.any { argument -> argument == "--apply" } })

        assertEquals(repositoryManifestBefore, captureManifest(repositoryRoot, excludeRepositoryBuildState = true))
        assertEquals(installationManifestBefore, captureManifest(installedRoot, excludeRepositoryBuildState = false))
        assertFalse(repositoryRoot.resolve(".refactorkit").exists())
        boundaryManifestsVerified = true
        routeContractVerified = true
    }

    @Then("both command-catalogue invocations exit successfully")
    fun bothCommandCatalogueInvocationsExitSuccessfully() {
        assertEquals(
            0,
            catalogInvocations[0].exitCode,
            "first source-built 'commands --json' invocation must be accepted; stderr=${catalogInvocations[0].stderrText()}",
        )
        assertEquals(
            0,
            catalogInvocations[1].exitCode,
            "second source-built 'commands --json' invocation must be accepted; stderr=${catalogInvocations[1].stderrText()}",
        )
    }

    @Then("each stdout is exactly the UTF-8 encoding of this single JSON object followed by one LF byte:")
    fun stdoutIsExactCatalogueBytes(expectedJson: String) {
        assertFalse(expectedJson.contains('\r'))
        expectedCatalogueBytes = expectedJson.removeSuffix("\n").toByteArray(Charsets.UTF_8) + byteArrayOf('\n'.code.toByte())
        catalogInvocations.forEach { invocation ->
            assertContentEquals(expectedCatalogueBytes, invocation.stdout)
            assertEquals('\n'.code.toByte(), invocation.stdout.last())
            assertEquals(1, invocation.stdout.count { it == '\n'.code.toByte() })
        }
    }

    @Then("the two complete stdout byte sequences are identical")
    fun catalogueBytesAreDeterministic() {
        assertContentEquals(catalogInvocations[0].stdout, catalogInvocations[1].stdout)
    }

    @Then("each stderr is empty, with no ANSI sequence, human prose, log, progress text, or stack trace in either stream")
    fun catalogueStreamsContainOnlyContractBytes() {
        catalogInvocations.forEach { invocation ->
            assertTrue(invocation.stderr.isEmpty(), invocation.stderrText())
            assertContentEquals(expectedCatalogueBytes, invocation.stdout)
            assertFalse(invocation.stdout.contains(0x1b.toByte()))
            assertFalse(invocation.stderr.contains(0x1b.toByte()))
        }
    }

    @Then("the closed v1 contract validator accepts that exact object and rejects each object formed by adding the listed unknown field without ignoring or normalizing it:")
    fun closedValidatorRejectsUnknownFields(table: DataTable) {
        val exact = Json.parseToJsonElement(expectedCatalogueBytes.toString(Charsets.UTF_8))
        validateClosedCatalogue(exact)

        table.asMaps().forEach { row ->
            assertEquals("unexpected", row.getValue("unknown field"))
            assertEquals(true, Json.parseToJsonElement(row.getValue("JSON value")).jsonPrimitive.booleanOrNull)
            val mutated = when (row.getValue("insertion point")) {
                "top level" -> JsonObject(exact.jsonObject + ("unexpected" to JsonPrimitive(true)))
                "first command entry" -> {
                    val top = exact.jsonObject
                    val commands = top.getValue("commands").jsonArray.toMutableList()
                    commands[0] = JsonObject(commands[0].jsonObject + ("unexpected" to JsonPrimitive(true)))
                    JsonObject(top + ("commands" to JsonArray(commands)))
                }
                else -> error("unexpected insertion point: ${row.getValue("insertion point")}")
            }
            assertFailsWith<IllegalArgumentException> { validateClosedCatalogue(mutated) }
        }
    }

    @Then("the commands are in exact lexicographic name order, every alias array is empty, and no route or semantic operation identity is inferred from source-name occurrences")
    fun catalogueOrderingAndOriginAreExact() {
        val commands = parseActualCatalogue().getValue("commands").jsonArray.map(JsonElement::jsonObject)
        val names = commands.map { it.getValue("name").jsonPrimitive.content }
        assertEquals(names.sorted(), names)
        assertEquals(EXPECTED_ROUTES.map(PublicRoute::name), names)
        assertTrue(commands.all { it.getValue("aliases").jsonArray.isEmpty() })
        assertTrue(observedSecurityManagers.all { it.violations.isEmpty() }, "catalogue must not inspect repository sources")
        assertTrue(boundaryManifestsVerified)
    }

    @Then("top-level help exposes {string} and exactly these truthful requirement-owned Java usage lines:")
    fun helpExposesExactRequirementOwnedLines(commandLine: String, table: DataTable) {
        assertEquals("refactorkit commands --json", commandLine)
        val helpLines = helpInvocation.stdoutText().lineSequence().map(String::trim).toList()
        assertEquals(1, helpLines.count { it == commandLine })
        table.asMaps().forEach { row ->
            assertTrue(EXPECTED_ROUTES.any { it.name == row.getValue("route") })
            assertEquals(1, helpLines.count { it == row.getValue("exact usage line") })
        }
    }

    @Then("help, exact catalogue membership, and actual public-parser reachability agree for those three routes")
    fun helpCatalogueAndParserAgree() {
        val catalogue = parseActualCatalogue().getValue("commands").jsonArray.map { command ->
            val entry = command.jsonObject
            PublicRoute(entry.getValue("name").jsonPrimitive.content, entry.getValue("operation").jsonPrimitive.content)
        }
        assertEquals(EXPECTED_ROUTES, catalogue)
        assertEquals(EXPECTED_ROUTES.map(PublicRoute::name), routeProbes.keys.toList())
        val help = helpInvocation.stdoutText()
        EXPECTED_ROUTES.forEach { route -> assertTrue(help.contains("refactorkit ${route.name}")) }
        assertTrue(routeContractVerified)
    }

    @Then("help does not claim dependency rewrite coordinates are unconditionally required for {string}")
    fun moveHelpKeepsDependencyCoordinatesOptional(route: String) {
        assertEquals("java move-across-maven-modules", route)
        val usage = helpInvocation.stdoutText().lineSequence().map(String::trim)
            .single { it.startsWith("refactorkit $route ") }
        assertEquals(EXPECTED_MOVE_USAGE, usage)
        assertTrue(usage.contains("[--dependency-pom <pom>"))
        assertFalse(usage.contains("--to <root> --dependency-pom"))
    }

    @Then("every existing human-oriented parser and command behavior remains compatible, with no requirement-owned change outside the additive truthful top-level help lines")
    fun humanHelpAndParserBehaviorRemainCompatible() {
        val baselineLines = pinnedHelp.toString(Charsets.UTF_8).split('\n')
        val actualLines = helpInvocation.stdoutText().split('\n')
        val baselineOutsideRequirement = baselineLines.filterNot { it.trim() == LEGACY_MOVE_USAGE }
        val actualOutsideRequirement = actualLines.filterNot { it.trim() in REQUIREMENT_OWNED_HELP_LINES }
        assertEquals(baselineOutsideRequirement, actualOutsideRequirement)

        assertEquals("create-module requires --module-name\n", routeProbes.getValue("java create-module").stderrText())
        assertEquals("rename-module requires --old-module-dir\n", routeProbes.getValue("java rename-module").stderrText())
        assertEquals(0, semanticSessionAttempts)
    }

    @Then("{string} remains byte-for-byte compatible with its pinned pre-slice output and valid only as the existing language-capability schema")
    fun capabilitiesRemainPinned(commandLine: String) {
        assertEquals("refactorkit capabilities", commandLine)
        assertEquals(PINNED_CAPABILITIES_SHA256, sha256(pinnedCapabilities))
        assertContentEquals(pinnedCapabilities, capabilitiesInvocation.stdout)
        assertTrue(capabilitiesInvocation.stderr.isEmpty())
        val capabilities = Json.parseToJsonElement(capabilitiesInvocation.stdoutText()).jsonObject
        assertEquals(listOf("schemaVersion", "adapters", "vocabulary"), capabilities.keys.toList())
        assertEquals(1, capabilities.getValue("schemaVersion").jsonPrimitive.intOrNull)
        assertEquals(listOf("java", "javascript", "kotlin", "typescript"), capabilities.getValue("adapters").jsonArray.map {
            it.jsonObject.getValue("languageId").jsonPrimitive.content
        })
    }

    @Then("the capabilities output contains none of {string}, {string}, {string}, {string}, {string}, {string}, {string}, or {string} as command-catalogue fields")
    fun capabilitiesDoNotBecomeCommandCatalogue(
        commands: String,
        operation: String,
        aliases: String,
        modes: String,
        mutationAuthority: String,
        jsonSupport: String,
        stability: String,
        requiredArguments: String,
    ) {
        assertEquals(
            listOf("commands", "operation", "aliases", "modes", "mutationAuthority", "jsonSupport", "stability", "requiredArguments"),
            listOf(commands, operation, aliases, modes, mutationAuthority, jsonSupport, stability, requiredArguments),
        )
        val objects = mutableListOf<JsonObject>()
        collectObjects(Json.parseToJsonElement(capabilitiesInvocation.stdoutText()), objects)
        assertTrue(objects.none { "commands" in it })
        assertTrue(objects.none { it.keys.containsAll(ENTRY_FIELD_NAMES) })
        assertTrue(objects.none { it["schema"]?.jsonPrimitive?.content == CATALOGUE_SCHEMA })
    }

    @Then("no invocation opens or scans a workspace, creates a preview or plan, acquires a workspace lock, creates a WAL or transaction, edits a file, mutates an installation, or invokes the installed executable")
    fun noInvocationCrossesMutationBoundaries() {
        assertTrue(boundaryManifestsVerified)
        assertEquals(0, semanticSessionAttempts)
        assertTrue(failClosedCliCount >= allInvocations.size)
        assertTrue(observedSecurityManagers.all { it.violations.isEmpty() })
        assertEquals(repositoryManifestBefore, captureManifest(repositoryRoot, excludeRepositoryBuildState = true))
        assertEquals(installationManifestBefore, captureManifest(installedRoot, excludeRepositoryBuildState = false))
        assertFalse(repositoryRoot.resolve(".refactorkit").exists())
        assertTrue(allInvocations.none { invocation -> invocation.arguments.any { it == "--apply" } })
    }

    @Then("this scenario does not qualify any of these explicitly excluded future or external surfaces:")
    fun exclusionsRemainUnqualified(table: DataTable) {
        assertEquals(EXPECTED_EXCLUSIONS, table.asMaps().map { it.getValue("excluded surface") })
        assertTrue(allInvocations.none { invocation ->
            invocation.arguments.any { token -> token in setOf("installDist", "apply", "rollback", "typescript", "kotlin") }
        })
        assertTrue(sourceBuiltCodeLocation.startsWith(repositoryRoot))
        assertFalse(sourceBuiltCodeLocation.startsWith(installedRoot))
    }

    private fun newFailClosedCli(): RefactorKitCli {
        val cli = RefactorKitCli(
            semanticSessionFactory = {
                semanticSessionAttempts++
                error("workspace/session tripwire: catalogue discovery must not create a semantic session")
            },
        )
        val scannerField = RefactorKitCli::class.java.getDeclaredField("scanner")
        check(scannerField.trySetAccessible())
        scannerField.set(cli, null)
        check(scannerField.get(cli) == null) { "scanner reflection tripwire was not armed" }
        failClosedCliCount++
        return cli
    }

    @Suppress("DEPRECATION")
    private fun guardedInvoke(arguments: List<String>): Invocation {
        val cli = newFailClosedCli()
        val manager = BoundarySecurityManager(repositoryRoot, installedRoot)
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        val originalSecurityManager = System.getSecurityManager()

        System.setSecurityManager(manager)
        val exitCode = try {
            System.setOut(PrintStream(stdout, true, Charsets.UTF_8.name()))
            System.setErr(PrintStream(stderr, true, Charsets.UTF_8.name()))
            cli.run(arguments)
        } finally {
            System.out.flush()
            System.err.flush()
            System.setOut(originalOut)
            System.setErr(originalErr)
            System.setSecurityManager(originalSecurityManager)
        }

        assertTrue(manager.violations.isEmpty(), manager.violations.joinToString())
        val invocation = Invocation(
            arguments = arguments.toList(),
            exitCode = exitCode,
            stdout = stdout.toByteArray().copyOf(),
            stderr = stderr.toByteArray().copyOf(),
        )
        observedSecurityManagers += manager
        allInvocations += invocation
        return invocation
    }

    private fun assertParsed(
        cli: RefactorKitCli,
        arguments: List<String>,
        expectedOptions: Map<String, String>,
        expectedFlags: Set<String>,
        expectedPositionals: List<String>,
    ) {
        val parsed = parserMethod.invoke(cli, arguments)
        assertEquals(expectedOptions, reflectedField(parsed, "options"))
        assertEquals(expectedFlags, reflectedField(parsed, "flags"))
        assertEquals(expectedPositionals, reflectedField(parsed, "positionals"))
    }

    private fun reflectedField(instance: Any, name: String): Any? {
        val field = instance.javaClass.getDeclaredField(name)
        assertTrue(field.trySetAccessible())
        return field.get(instance)
    }

    private fun validateClosedCatalogue(element: JsonElement) {
        val top = element as? JsonObject ?: throw IllegalArgumentException("catalogue must be an object")
        require(top.keys.toList() == TOP_LEVEL_FIELD_NAMES) { "unexpected top-level fields or order: ${top.keys}" }
        requireString(top.getValue("schema"), "schema")
        require(top.getValue("schema").jsonPrimitive.content == CATALOGUE_SCHEMA)
        val schemaVersion = top.getValue("schemaVersion") as? JsonPrimitive
            ?: throw IllegalArgumentException("schemaVersion must be an integer")
        require(!schemaVersion.isString && schemaVersion.intOrNull == 1) { "schemaVersion must be integer 1" }
        val commands = top.getValue("commands") as? JsonArray
            ?: throw IllegalArgumentException("commands must be an array")
        require(commands.size == EXPECTED_ROUTES.size) { "unexpected command count" }

        commands.forEach { commandElement ->
            val command = commandElement as? JsonObject ?: throw IllegalArgumentException("entry must be an object")
            require(command.keys.toList() == ENTRY_FIELD_NAMES) { "unexpected entry fields or order: ${command.keys}" }
            listOf("name", "operation", "mutationAuthority", "jsonSupport", "stability").forEach { field ->
                requireString(command.getValue(field), field)
            }
            listOf("aliases", "modes", "requiredArguments").forEach { field ->
                val values = command.getValue(field) as? JsonArray
                    ?: throw IllegalArgumentException("$field must be an array")
                values.forEach { requireString(it, "$field element") }
            }
            require(command.getValue("modes").jsonArray.map { it.jsonPrimitive.content }.all { it in setOf("preview", "apply") })
            require(command.getValue("mutationAuthority").jsonPrimitive.content == "refactorkit-managed")
            require(command.getValue("jsonSupport").jsonPrimitive.content == "catalog-only")
            require(command.getValue("stability").jsonPrimitive.content == "experimental")
            require(command.getValue("requiredArguments").jsonArray.all { it.jsonPrimitive.content.startsWith("--") })
        }
    }

    private fun requireString(element: JsonElement, field: String) {
        require(element is JsonPrimitive && element.isString) { "$field must be a string" }
    }

    private fun parseActualCatalogue(): JsonObject {
        assertContentEquals(expectedCatalogueBytes, catalogInvocations[0].stdout)
        return Json.parseToJsonElement(catalogInvocations[0].stdoutText()).jsonObject
    }

    private fun collectObjects(element: JsonElement, destination: MutableList<JsonObject>) {
        when (element) {
            is JsonObject -> {
                destination += element
                element.values.forEach { collectObjects(it, destination) }
            }
            is JsonArray -> element.forEach { collectObjects(it, destination) }
            else -> Unit
        }
    }

    private fun resourceBytes(path: String): ByteArray {
        val stream = assertNotNull(javaClass.classLoader.getResourceAsStream(path), "missing test fixture $path")
        return stream.use { it.readBytes() }
    }

    private fun captureManifest(root: Path, excludeRepositoryBuildState: Boolean): Map<String, String> {
        require(Files.isDirectory(root)) { "manifest root does not exist: $root" }
        val entries = mutableMapOf<String, String>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val relative = root.relativize(dir)
                if (excludeRepositoryBuildState && relative.nameCount > 0 &&
                    relative.fileName.toString() in REPOSITORY_MANIFEST_EXCLUDED_DIRECTORIES
                ) {
                    return SKIP_SUBTREE
                }
                entries[manifestPath(relative)] = "directory:${permissions(dir)}"
                return CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val relative = root.relativize(file)
                entries[manifestPath(relative)] = when {
                    Files.isSymbolicLink(file) -> "symlink:${Files.readSymbolicLink(file)}"
                    attrs.isRegularFile -> {
                        val bytes = Files.readAllBytes(file)
                        "file:${bytes.size}:${permissions(file)}:${sha256(bytes)}"
                    }
                    else -> "other:${permissions(file)}"
                }
                return CONTINUE
            }
        })
        return entries.toSortedMap()
    }

    private fun permissions(path: Path): String = runCatching {
        Files.getPosixFilePermissions(path).sortedBy(PosixFilePermission::name).joinToString(",") { it.name }
    }.getOrElse { "unavailable" }

    private fun manifestPath(relative: Path): String =
        relative.toString().replace('\\', '/').ifBlank { "." }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { byte -> "%02x".format(byte) }

    private fun codeLocation(type: Class<*>): Path {
        val uri: URI = assertNotNull(type.protectionDomain.codeSource?.location).toURI()
        return Path.of(uri).toAbsolutePath().normalize()
    }

    private fun locateRepositoryRoot(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath().normalize()
        while (candidate != null) {
            if (candidate.resolve("settings.gradle.kts").exists() && candidate.resolve("modules/refactorkit-cli").exists()) {
                return candidate
            }
            candidate = candidate.parent
        }
        error("repository root not found")
    }

    private fun Invocation.stdoutText(): String = stdout.toString(Charsets.UTF_8)
    private fun Invocation.stderrText(): String = stderr.toString(Charsets.UTF_8)

    @Suppress("DEPRECATION")
    private class BoundarySecurityManager(
        private val repositoryRoot: Path,
        private val installedRoot: Path,
    ) : SecurityManager() {
        val violations = mutableListOf<String>()

        override fun checkPermission(permission: Permission?) = Unit
        override fun checkPermission(permission: Permission?, context: Any?) = Unit

        override fun checkRead(file: String?) {
            val path = normalized(file) ?: return
            if (path.startsWith(installedRoot) ||
                (path.startsWith(repositoryRoot) && !isBuildInfrastructure(path))
            ) {
                block("read", path, "workspace or installed-runtime read")
            }
        }

        override fun checkWrite(file: String?) {
            block("write", normalized(file), "filesystem write")
        }

        override fun checkDelete(file: String?) {
            block("delete", normalized(file), "filesystem delete")
        }

        override fun checkExec(command: String?) {
            val normalized = normalized(command)
            val display = normalized?.toString() ?: command.orEmpty()
            violations += "exec:$display"
            throw SecurityException("process execution is forbidden during source-built catalogue discovery: $display")
        }

        private fun isBuildInfrastructure(path: Path): Boolean {
            val relative = repositoryRoot.relativize(path)
            return relative.any { segment -> segment.toString() in setOf("build", ".gradle") }
        }

        private fun normalized(raw: String?): Path? = raw?.let { value ->
            runCatching { Path.of(value).toAbsolutePath().normalize() }.getOrNull()
        }

        private fun block(kind: String, path: Path?, description: String): Nothing {
            val display = path?.toString().orEmpty()
            violations += "$kind:$display"
            throw SecurityException("$description is forbidden during source-built catalogue discovery: $display")
        }
    }

    private companion object {
        const val CATALOGUE_SCHEMA = "refactorkit.cli-command-catalog/v1"
        const val CAPABILITIES_FIXTURE =
            "org/refactorkit/cli/reqjavaclicatalog001/capabilities-pre-slice-dec4087fa8b7.json"
        const val HELP_FIXTURE =
            "org/refactorkit/cli/reqjavaclicatalog001/help-pre-slice-06fd4878c8fd.txt"
        const val PINNED_CAPABILITIES_SHA256 = "dec4087fa8b7ba038a5b9a82728ec724054ee6fd28456eb9ec637fd7d0d0d1c3"
        const val PINNED_HELP_SHA256 = "06fd4878c8fd717a9515d6dd8572f182cff2067239c3bcc870fa3e184aafee61"

        val TOP_LEVEL_FIELD_NAMES = listOf("schema", "schemaVersion", "commands")
        val ENTRY_FIELD_NAMES = listOf(
            "name",
            "operation",
            "aliases",
            "modes",
            "mutationAuthority",
            "jsonSupport",
            "stability",
            "requiredArguments",
        )
        val EXPECTED_CONTRACT_FIELDS = listOf(
            ContractField("top level", 1, "schema", "string"),
            ContractField("top level", 2, "schemaVersion", "integer"),
            ContractField("top level", 3, "commands", "array"),
            ContractField("entry", 1, "name", "string"),
            ContractField("entry", 2, "operation", "string"),
            ContractField("entry", 3, "aliases", "array<string>"),
            ContractField("entry", 4, "modes", "array<string>"),
            ContractField("entry", 5, "mutationAuthority", "string"),
            ContractField("entry", 6, "jsonSupport", "string"),
            ContractField("entry", 7, "stability", "string"),
            ContractField("entry", 8, "requiredArguments", "array<string>"),
        )
        val EXPECTED_ROUTES = listOf(
            PublicRoute("java create-module", "java.createMavenModule"),
            PublicRoute("java move-across-maven-modules", "java.moveAcrossMavenModules"),
            PublicRoute("java rename-module", "java.renameMavenModule"),
        )
        const val EXPECTED_MOVE_USAGE =
            "refactorkit java move-across-maven-modules --from <root> --to <root> [--dependency-pom <pom> --source-group-id <id> --source-artifact-id <id> --source-version <v> --destination-group-id <id> --destination-artifact-id <id> --destination-version <v>] [--root <path>] [--apply]"
        const val LEGACY_MOVE_USAGE =
            "refactorkit java move-across-maven-modules --from <root> --to <root> --dependency-pom <pom> --source-group-id <id> --source-artifact-id <id> --source-version <v> --destination-group-id <id> --destination-artifact-id <id> --destination-version <v> [--root <path>] [--apply]"
        val REQUIREMENT_OWNED_HELP_LINES = setOf(
            "refactorkit commands --json",
            "refactorkit java create-module --module-name <name> --parent-pom <pom> [--root <path>] [--apply]",
            EXPECTED_MOVE_USAGE,
            "refactorkit java rename-module --old-module-dir <dir> --new-module-dir <dir> [--new-artifact-id <id>] [--root <path>] [--apply]",
        )
        val REPOSITORY_MANIFEST_EXCLUDED_DIRECTORIES = setOf(".git", ".gradle", "build")
        val EXPECTED_EXCLUSIONS = listOf(
            "refactorkit.cli-result/v1 operation-result envelopes",
            "installDist or source-built-to-installed executable parity",
            "installed-runtime promotion or overwrite",
            "a fresh SURFACE-002 apply, refusal, or rollback rerun",
            "non-Java command inventory or other language capability work",
            "Windows, macOS, another CPU architecture, another JDK, or release parity",
        )
    }
}
