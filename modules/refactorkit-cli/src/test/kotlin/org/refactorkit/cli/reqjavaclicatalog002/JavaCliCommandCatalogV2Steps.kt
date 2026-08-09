package org.refactorkit.cli.reqjavaclicatalog002

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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.refactorkit.cli.RefactorKitCli
import org.refactorkit.cli.testharness.GuardedChildJvm
import org.refactorkit.core.RefactorKitVersion
import java.net.URI
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitResult.CONTINUE
import java.nio.file.FileVisitResult.SKIP_SUBTREE
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.Base64
import kotlin.io.path.exists
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JavaCliCommandCatalogV2Steps {
    private data class Invocation(
        val arguments: List<String>,
        val exitCode: Int,
        val stdout: ByteArray,
        val stderr: ByteArray,
    )

    private data class RouteOperation(
        val route: String,
        val operation: String,
    )

    private enum class CatalogueVersion(
        val schema: String,
        val number: Int,
    ) {
        V1("refactorkit.cli-command-catalog/v1", 1),
        V2("refactorkit.cli-command-catalog/v2", 2),
    }

    private lateinit var repositoryRoot: Path
    private lateinit var installedRoot: Path
    private lateinit var installedExecutable: Path
    private lateinit var sourceBuiltCodeLocation: Path
    private lateinit var repositoryManifestBefore: Map<String, String>
    private lateinit var installationManifestBefore: Map<String, String>
    private lateinit var v1Oracle: ByteArray
    private lateinit var v2Oracle: ByteArray
    private lateinit var pinnedCapabilities: ByteArray
    private val unversionedInvocations = mutableListOf<Invocation>()
    private val explicitV1Invocations = mutableListOf<Invocation>()
    private val selectorRejections = linkedMapOf<String, Invocation>()
    private val allInvocations = mutableListOf<Invocation>()
    private var guardedProcessInvocations = 0
    private var semanticSessionAttempts = 0
    private var failClosedCliCount = 0
    private var selectorRejectionsChecked = false
    private var boundaryTripwiresArmed = false
    private lateinit var helpInvocation: Invocation
    private lateinit var capabilitiesInvocation: Invocation

    @After
    fun verifyBoundaries() {
        if (::repositoryManifestBefore.isInitialized) {
            assertEquals(repositoryManifestBefore, captureManifest(repositoryRoot, excludeRepositoryBuildState = true))
            assertEquals(installationManifestBefore, captureManifest(installedRoot, excludeRepositoryBuildState = false))
            assertFalse(repositoryRoot.resolve(".refactorkit").exists())
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

        v1Oracle = resourceBytes(V1_ORACLE_FIXTURE)
        v2Oracle = resourceBytes(V2_ORACLE_FIXTURE)
        pinnedCapabilities = resourceBytes(CAPABILITIES_FIXTURE)
        assertEquals(V1_ORACLE_SHA256, sha256(v1Oracle))
        assertEquals(V2_ORACLE_SHA256, sha256(v2Oracle))
        assertEquals(PINNED_CAPABILITIES_SHA256, sha256(pinnedCapabilities))
        assertEquals('\n'.code.toByte(), v1Oracle.last())
        assertEquals('\n'.code.toByte(), v2Oracle.last())

        repositoryManifestBefore = captureManifest(repositoryRoot, excludeRepositoryBuildState = true)
        installationManifestBefore = captureManifest(installedRoot, excludeRepositoryBuildState = false)
        assertFalse(repositoryRoot.resolve(".refactorkit").exists(), "test repository already contains .refactorkit")
    }

    @Given("the installed RefactorKit executable is guarded by invocation, lookup, and no-follow mutation tripwires and is neither selected, invoked, nor modified")
    fun guardInstalledExecutable() {
        assertTrue(installedExecutable.exists(), "installed executable required for boundary tripwires")
        assertFalse(sourceBuiltCodeLocation.startsWith(installedRoot))
        assertTrue(Files.readAttributes(installedExecutable, BasicFileAttributes::class.java, NOFOLLOW_LINKS).isRegularFile)

        val selfTest = CatalogBoundarySecurityManager(repositoryRoot, installedRoot)
        assertFailsWith<SecurityException> { selfTest.checkExec(installedExecutable.toString()) }
        assertFailsWith<SecurityException> { selfTest.checkRead(installedExecutable.toString()) }
        assertFailsWith<SecurityException> { selfTest.checkWrite(installedExecutable.toString()) }
        assertFailsWith<SecurityException> { selfTest.checkDelete(installedExecutable.toString()) }
        assertEquals(
            listOf(
                "exec:${installedExecutable.toAbsolutePath().normalize()}",
                "read:${installedExecutable.toAbsolutePath().normalize()}",
                "write:${installedExecutable.toAbsolutePath().normalize()}",
                "delete:${installedExecutable.toAbsolutePath().normalize()}",
            ),
            selfTest.violations,
        )

        val processProbe = launchGuardedHarness(PROBE_MODE, emptyList())
        assertEquals(true, processProbe.getValue("securityManagerInstalled").jsonPrimitive.boolean)
        assertEquals(
            selfTest.violations,
            processProbe.getValue("violations").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Given("workspace opening, scanning, planning, locking, WAL, transaction, editing, and installation mutation boundaries are instrumented fail-closed")
    fun armFailClosedBoundaries() {
        assertTrue(::repositoryManifestBefore.isInitialized)
        assertTrue(::installationManifestBefore.isInitialized)

        val cli = newFailClosedCli()
        val scannerField = RefactorKitCli::class.java.getDeclaredField("scanner")
        assertTrue(scannerField.trySetAccessible())
        assertNull(scannerField.get(cli), "scanner reflection tripwire was not armed")

        val selfTest = CatalogBoundarySecurityManager(repositoryRoot, installedRoot)
        val sourcePath = repositoryRoot.resolve("modules/refactorkit-cli/src/main/kotlin")
        assertFailsWith<SecurityException> { selfTest.checkRead(sourcePath.toString()) }
        assertFailsWith<SecurityException> { selfTest.checkWrite(repositoryRoot.resolve("tripwire-write").toString()) }
        assertFailsWith<SecurityException> { selfTest.checkDelete(repositoryRoot.resolve("tripwire-delete").toString()) }
        assertFailsWith<SecurityException> { selfTest.checkExec("refactorkit") }
        assertEquals(4, selfTest.violations.size)
        boundaryTripwiresArmed = true
    }

    @Given("separate closed v1 and v2 validators are available without treating either version as the other")
    fun establishSeparateClosedValidators() {
        val v1 = parse(v1Oracle)
        val v2 = parse(v2Oracle)
        validateClosedCatalogue(v1, CatalogueVersion.V1)
        validateClosedCatalogue(v2, CatalogueVersion.V2)
        assertFailsWith<IllegalArgumentException> { validateClosedCatalogue(v1, CatalogueVersion.V2) }
        assertFailsWith<IllegalArgumentException> { validateClosedCatalogue(v2, CatalogueVersion.V1) }
    }

    @Given("the exact v1 bytes, closed field and type contract, producer order, command inventory, and semantics are owned unchanged by {string}")
    fun pinV1CompatibilityOwner(requirementId: String) {
        assertEquals("REQ-JAVA-CLI-CATALOG-001", requirementId)
        assertEquals(V1_ORACLE_SHA256, sha256(v1Oracle))
        validateClosedCatalogue(parse(v1Oracle), CatalogueVersion.V1)
    }

    @Given("the exact successful preview result is owned only by {string} and is not copied into this catalogue scenario")
    fun keepPreviewResultOutOfCatalogue(requirementId: String) {
        assertEquals("REQ-JAVA-CLI-RESULT-001", requirementId)
        listOf(v1Oracle, v2Oracle).forEach { bytes ->
            val document = parse(bytes)
            assertFalse(document.keys.any { it in RESULT_ENVELOPE_FIELDS })
            assertFalse(bytes.toString(Charsets.UTF_8).contains("refactorkit.cli-result/"))
        }
    }

    @When("the source-built public parser invokes {string} twice and {string} twice with each stdout and stderr captured separately")
    fun invokeDefaultAndExplicitV1Twice(unversioned: String, explicitV1: String) {
        assertEquals("refactorkit commands --json", unversioned)
        assertEquals("refactorkit commands --json --schema-version 1", explicitV1)
        repeat(2) { unversionedInvocations += guardedInvoke(argumentsOf(unversioned)) }
        repeat(2) { explicitV1Invocations += guardedInvoke(argumentsOf(explicitV1)) }
        assertEquals(2, unversionedInvocations.size)
        assertEquals(2, explicitV1Invocations.size)
        assertFalse(unversionedInvocations[0].stdout === unversionedInvocations[1].stdout)
        assertFalse(explicitV1Invocations[0].stdout === explicitV1Invocations[1].stdout)
    }

    @When("the same source-built public parser invokes each invalid selector request:")
    fun invokeInvalidSelectors(table: DataTable) {
        val rows = table.asMaps()
        assertEquals(EXPECTED_INVALID_SELECTORS, rows.map { it.getValue("selector condition") to it.getValue("exact request") })
        rows.forEach { row ->
            val condition = row.getValue("selector condition")
            selectorRejections[condition] = guardedInvoke(argumentsOf(row.getValue("exact request")))
        }
        assertSelectorRejectionsDoNotFallback()
        selectorRejectionsChecked = true
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

    @Then("both unversioned invocations exit successfully")
    fun unversionedInvocationsExitSuccessfully() {
        unversionedInvocations.forEachIndexed { index, invocation ->
            assertEquals(
                0,
                invocation.exitCode,
                "unversioned catalogue invocation ${index + 1} must be accepted; stderr=${invocation.stderrText()}",
            )
        }
    }

    @Then("each unversioned stdout is exactly the UTF-8 encoding of this single compact JSON object followed by one LF byte:")
    fun unversionedStdoutIsExactV2(expectedJson: String) {
        val featureOracle = expectedJson.removeSuffix("\n").toByteArray(Charsets.UTF_8) + byteArrayOf('\n'.code.toByte())
        assertContentEquals(v2Oracle, featureOracle, "feature v2 oracle must match the independent test resource")
        unversionedInvocations.forEachIndexed { index, invocation ->
            assertContentEquals(
                v2Oracle,
                invocation.stdout,
                "REQ-JAVA-CLI-CATALOG-002 default invocation ${index + 1} must emit exact v2 bytes; " +
                    "expectedSha=$V2_ORACLE_SHA256 actualSha=${sha256(invocation.stdout)}",
            )
        }
    }

    @Then("the two complete unversioned stdout byte sequences are identical")
    fun unversionedCatalogueIsDeterministic() {
        assertContentEquals(unversionedInvocations[0].stdout, unversionedInvocations[1].stdout)
    }

    @Then("each unversioned stderr is empty, with no ANSI sequence, human prose, log, progress text, or stack trace in either stream")
    fun unversionedStreamsContainOnlyContractBytes() {
        unversionedInvocations.forEach { invocation ->
            assertTrue(invocation.stderr.isEmpty(), invocation.stderrText())
            assertContentEquals(v2Oracle, invocation.stdout)
            assertFalse(invocation.stdout.contains(0x1b.toByte()))
            assertFalse(invocation.stderr.contains(0x1b.toByte()))
        }
    }

    @Then("both explicit-v1 invocations exit successfully and each stdout is exactly the v1 UTF-8 byte oracle owned by {string}, including its one trailing LF byte")
    fun explicitV1IsCompatible(requirementId: String) {
        assertEquals("REQ-JAVA-CLI-CATALOG-001", requirementId)
        explicitV1Invocations.forEachIndexed { index, invocation ->
            assertEquals(0, invocation.exitCode, "explicit-v1 invocation ${index + 1} was rejected")
            assertContentEquals(v1Oracle, invocation.stdout)
        }
    }

    @Then("the two complete explicit-v1 stdout byte sequences are identical and each stderr is empty")
    fun explicitV1IsDeterministicAndClean() {
        assertContentEquals(explicitV1Invocations[0].stdout, explicitV1Invocations[1].stdout)
        explicitV1Invocations.forEach { assertTrue(it.stderr.isEmpty(), it.stderrText()) }
    }

    @Then("absence of {string} selects v2 while one exact {string} selects v1")
    fun selectorSemanticsAreUnambiguous(selector: String, exactSelector: String) {
        assertEquals("--schema-version", selector)
        assertEquals("--schema-version 1", exactSelector)
        unversionedInvocations.forEach { assertContentEquals(v2Oracle, it.stdout) }
        explicitV1Invocations.forEach { assertContentEquals(v1Oracle, it.stdout) }
    }

    @Then("every malformed, duplicate, missing-value, or unsupported selector request is rejected with no successful catalogue bytes and without falling back to v1 or v2")
    fun invalidSelectorsDoNotProduceSuccessfulCatalogueBytes() {
        assertSelectorRejectionsDoNotFallback()
        selectorRejectionsChecked = true
    }

    @Then("this selector-rejection requirement specifies no operation-result envelope, exact error prose, output stream, or exit code")
    fun selectorRejectionsRemainOutputAgnostic() {
        assertTrue(selectorRejectionsChecked)
        selectorRejections.values.forEach { invocation ->
            assertFalse(invocation.stdout.contentEquals(v1Oracle))
            assertFalse(invocation.stdout.contentEquals(v2Oracle))
        }
    }

    @Then("the v2 top-level and entry field sets, JSON types, and producer order are exactly the v1 field, type, and order contract owned by {string}, with no added, omitted, or reordered field")
    fun v2RetainsV1Shape(requirementId: String) {
        assertEquals("REQ-JAVA-CLI-CATALOG-001", requirementId)
        val v1 = parse(v1Oracle)
        val v2 = parse(v2Oracle)
        assertEquals(v1.keys.toList(), v2.keys.toList())
        val v1Entries = v1.getValue("commands").jsonArray.map { it.jsonObject }
        val v2Entries = v2.getValue("commands").jsonArray.map { it.jsonObject }
        assertEquals(v1Entries.map { it.keys.toList() }, v2Entries.map { it.keys.toList() })
        assertEquals(v1Entries.map(::jsonTypeSignature), v2Entries.map(::jsonTypeSignature))
    }

    @Then("the commands remain in exact lexicographic name order with the same route-operation pairs, empty aliases, modes, mutation authority, stability, and required arguments as v1")
    fun v2ChangesOnlyQualifiedJsonSupport() {
        val v1Entries = entries(parse(v1Oracle))
        val v2Entries = entries(parse(v2Oracle))
        val names = v2Entries.map { it.getValue("name").jsonPrimitive.content }
        assertEquals(names.sorted(), names)
        v1Entries.zip(v2Entries).forEach { (v1, v2) ->
            (ENTRY_FIELD_NAMES - "jsonSupport").forEach { field -> assertEquals(v1.getValue(field), v2.getValue(field)) }
            assertTrue(v2.getValue("aliases").jsonArray.isEmpty())
        }
    }

    @Then("only {string} has {string} equal to {string}, while {string} and {string} remain {string}")
    fun onlyRenameHasPreviewJsonSupport(
        renameRoute: String,
        jsonSupport: String,
        previewOnly: String,
        createRoute: String,
        moveRoute: String,
        catalogOnly: String,
    ) {
        assertEquals("jsonSupport", jsonSupport)
        val supportByRoute = entries(parse(v2Oracle)).associate { entry ->
            entry.getValue("name").jsonPrimitive.content to entry.getValue(jsonSupport).jsonPrimitive.content
        }
        assertEquals(previewOnly, supportByRoute.getValue(renameRoute))
        assertEquals(catalogOnly, supportByRoute.getValue(createRoute))
        assertEquals(catalogOnly, supportByRoute.getValue(moveRoute))
        assertEquals("preview-only", previewOnly)
        assertEquals("catalog-only", catalogOnly)
    }

    @Then("{string} means only the successful source-built preview qualified by {string} and grants no apply, refusal, error, transaction, rollback, approval, or write authority")
    fun previewOnlyDoesNotGrantMutationAuthority(jsonSupport: String, requirementId: String) {
        assertEquals("preview-only", jsonSupport)
        assertEquals("REQ-JAVA-CLI-RESULT-001", requirementId)
        val rename = entries(parse(v2Oracle)).single { it.getValue("name").jsonPrimitive.content == "java rename-module" }
        assertEquals(jsonSupport, rename.getValue("jsonSupport").jsonPrimitive.content)
        assertEquals("refactorkit-managed", rename.getValue("mutationAuthority").jsonPrimitive.content)
        assertTrue(allInvocations.none { invocation -> invocation.arguments.any { it == "--apply" } })
        assertEquals(0, semanticSessionAttempts)
    }

    @Then("the v2 inventory agrees with the production-parser reachability oracle owned by {string}, without inferring a route or operation from source-name occurrences")
    fun v2MatchesOwnedReachabilityOracle(requirementId: String) {
        assertEquals("REQ-JAVA-CLI-CATALOG-001", requirementId)
        val actual = entries(parse(v2Oracle)).map {
            RouteOperation(it.getValue("name").jsonPrimitive.content, it.getValue("operation").jsonPrimitive.content)
        }
        assertEquals(EXPECTED_ROUTES, actual)
        assertEquals(allInvocations.size, guardedProcessInvocations, "every catalogue invocation must use the guarded child")
    }

    @Then("the separate closed validators accept their exact versioned documents and reject each listed mutation without ignoring or normalizing it:")
    fun validatorsRejectListedMutations(table: DataTable) {
        validateClosedCatalogue(parse(v1Oracle), CatalogueVersion.V1)
        validateClosedCatalogue(parse(v2Oracle), CatalogueVersion.V2)
        val expectedRows = EXPECTED_VALIDATOR_MUTATIONS.map { (validator, mutation) ->
            mapOf("validator" to validator, "mutation" to mutation)
        }
        assertEquals(expectedRows, table.asMaps())

        table.asMaps().forEach { row ->
            val version = when (row.getValue("validator")) {
                "v1" -> CatalogueVersion.V1
                "v2" -> CatalogueVersion.V2
                else -> error("unexpected validator")
            }
            val source = if (version == CatalogueVersion.V1) parse(v1Oracle) else parse(v2Oracle)
            val mutated = mutate(source, row.getValue("mutation"))
            assertFailsWith<IllegalArgumentException> { validateClosedCatalogue(mutated, version) }
        }
    }

    @Then("neither exact document is accepted by the other version's validator or under the other schema identity")
    fun versionsRemainClosedAndDistinct() {
        assertFailsWith<IllegalArgumentException> { validateClosedCatalogue(parse(v1Oracle), CatalogueVersion.V2) }
        assertFailsWith<IllegalArgumentException> { validateClosedCatalogue(parse(v2Oracle), CatalogueVersion.V1) }
        assertFalse(v1Oracle.contentEquals(v2Oracle))
    }

    @Then("top-level help exposes exactly {string} as the machine-readable catalogue discovery route and agrees with the public parser")
    fun helpExposesVersionedDiscovery(commandLine: String) {
        assertEquals("refactorkit commands --json [--schema-version 1]", commandLine)
        val helpLines = helpInvocation.stdoutText().lineSequence().map(String::trim).toList()
        assertEquals(1, helpLines.count { it == commandLine })
        assertTrue(allInvocations.any { it.arguments == listOf("commands", "--json") })
        assertTrue(allInvocations.any { it.arguments == listOf("commands", "--json", "--schema-version", "1") })
    }

    @Then("{string} remains byte-for-byte compatible with its pinned pre-evolution output and valid only as the existing language-capability schema")
    fun capabilitiesRemainPinned(commandLine: String) {
        assertEquals("refactorkit capabilities", commandLine)
        assertEquals(PINNED_CAPABILITIES_SHA256, sha256(pinnedCapabilities))
        assertContentEquals(pinnedCapabilities, capabilitiesInvocation.stdout)
        assertTrue(capabilitiesInvocation.stderr.isEmpty())
        val capabilities = parse(capabilitiesInvocation.stdout)
        assertEquals(listOf("schemaVersion", "adapters", "vocabulary"), capabilities.keys.toList())
        assertEquals(1, capabilities.getValue("schemaVersion").jsonPrimitive.intOrNull)
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
        val prohibited = listOf(commands, operation, aliases, modes, mutationAuthority, jsonSupport, stability, requiredArguments)
        assertEquals(
            listOf("commands", "operation", "aliases", "modes", "mutationAuthority", "jsonSupport", "stability", "requiredArguments"),
            prohibited,
        )
        val objects = mutableListOf<JsonObject>()
        collectObjects(Json.parseToJsonElement(capabilitiesInvocation.stdoutText()), objects)
        assertTrue(objects.none { "commands" in it })
        assertTrue(objects.none { it.keys.containsAll(ENTRY_FIELD_NAMES) })
        assertTrue(objects.none { it["schema"]?.jsonPrimitive?.content?.startsWith("refactorkit.cli-command-catalog/") == true })
    }

    @Then("the explicit-v1 compatibility, default-v2 catalogue, and separately owned successful preview are qualified from the same source-built revision before any one is promoted, and none is promoted alone")
    fun qualificationUsesOneSourceBuiltRevision() {
        assertTrue(sourceBuiltCodeLocation.startsWith(repositoryRoot))
        assertFalse(sourceBuiltCodeLocation.startsWith(installedRoot))
        assertEquals(4, unversionedInvocations.size + explicitV1Invocations.size)
        assertTrue(allInvocations.none { it.arguments.any { argument -> argument == "installDist" } })
        assertEquals(repositoryManifestBefore, captureManifest(repositoryRoot, excludeRepositoryBuildState = true))
    }

    @Then("no catalogue, selector, help, or capabilities invocation opens or scans a workspace, creates a preview or plan, acquires a workspace lock, creates a WAL or transaction, edits a file, mutates an installation, or invokes the installed executable")
    fun invocationsRemainNonMutating() {
        assertTrue(boundaryTripwiresArmed)
        assertEquals(0, semanticSessionAttempts)
        assertTrue(failClosedCliCount >= allInvocations.size)
        assertEquals(allInvocations.size, guardedProcessInvocations)
        assertEquals(repositoryManifestBefore, captureManifest(repositoryRoot, excludeRepositoryBuildState = true))
        assertEquals(installationManifestBefore, captureManifest(installedRoot, excludeRepositoryBuildState = false))
        assertFalse(repositoryRoot.resolve(".refactorkit").exists())
        assertTrue(allInvocations.none { invocation -> invocation.arguments.any { it == "--apply" } })
    }

    @Then("this scenario makes no implementation, validation, promotion, or support claim for:")
    fun exclusionsRemainUnqualified(table: DataTable) {
        assertEquals(EXPECTED_EXCLUSIONS, table.asMaps().map { it.getValue("explicitly excluded scope") })
        assertTrue(allInvocations.none { invocation ->
            invocation.arguments.any { token -> token in setOf("apply", "rollback", "typescript", "kotlin", "installDist") }
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

    private fun guardedInvoke(arguments: List<String>): Invocation {
        val response = launchGuardedHarness(INVOKE_MODE, arguments)
        assertEquals(0, response.getValue("semanticSessionAttempts").jsonPrimitive.int)
        assertEquals(true, response.getValue("scannerTripwireArmed").jsonPrimitive.boolean)
        assertTrue(response.getValue("violations").jsonArray.isEmpty(), response.toString())

        val invocation = Invocation(
            arguments = arguments.toList(),
            exitCode = response.getValue("cliExitCode").jsonPrimitive.int,
            stdout = Base64.getDecoder().decode(response.getValue("stdoutBase64").jsonPrimitive.content),
            stderr = Base64.getDecoder().decode(response.getValue("stderrBase64").jsonPrimitive.content),
        )
        guardedProcessInvocations++
        failClosedCliCount++
        allInvocations += invocation
        return invocation
    }

    private fun launchGuardedHarness(mode: String, arguments: List<String>): JsonObject {
        val javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toAbsolutePath().normalize()
        assertTrue(Files.isExecutable(javaExecutable), "active JDK java executable is unavailable: $javaExecutable")
        assertFalse(javaExecutable.startsWith(installedRoot), "guard harness must not select the installed runtime")

        val child = GuardedChildJvm.launch(
            workingDirectory = repositoryRoot,
            mainClass = JavaCliCommandCatalogV2ProcessHarness::class.java,
            arguments = listOf(mode, repositoryRoot.toString(), installedRoot.toString()) + arguments,
            requiredClasses = listOf(RefactorKitCli::class.java),
            timeoutSeconds = PROCESS_TIMEOUT_SECONDS,
        )
        assertEquals(0, child.exitCode, "guard harness failed: ${child.stderr.toString(Charsets.UTF_8)}")
        GuardedChildJvm.assertOnlySecurityManagerDeprecationNotice(
            child.stderr,
            JavaCliCommandCatalogV2ProcessHarness::class.java,
        )

        val response = parse(child.stdout)
        assertEquals(HARNESS_PROTOCOL, response.getValue("protocol").jsonPrimitive.content)
        assertEquals(mode, response.getValue("mode").jsonPrimitive.content)
        assertEquals(21, response.getValue("runtimeFeature").jsonPrimitive.int)
        assertEquals(true, response.getValue("securityManagerInstalled").jsonPrimitive.boolean)
        assertTrue(
            Base64.getDecoder().decode(response.getValue("managerDiagnosticsBase64").jsonPrimitive.content).isEmpty(),
            "guard manager emitted unexpected in-process diagnostics",
        )
        return response
    }

    private fun argumentsOf(commandLine: String): List<String> {
        require(commandLine.startsWith("refactorkit "))
        return commandLine.split(' ').drop(1)
    }

    private fun assertSelectorRejectionsDoNotFallback() {
        assertEquals(EXPECTED_INVALID_SELECTORS.map(Pair<String, String>::first), selectorRejections.keys.toList())
        selectorRejections.values.forEach { invocation ->
            assertFalse(
                invocation.exitCode == 0 &&
                    (invocation.stdout.contentEquals(v1Oracle) || invocation.stdout.contentEquals(v2Oracle)),
                "invalid selector produced a successful versioned catalogue",
            )
            assertFalse(invocation.stdout.contentEquals(v1Oracle), "invalid selector fell back to v1")
            assertFalse(invocation.stdout.contentEquals(v2Oracle), "invalid selector fell back to v2")
        }
    }

    private fun validateClosedCatalogue(element: JsonElement, version: CatalogueVersion) {
        val top = element as? JsonObject ?: throw IllegalArgumentException("catalogue must be an object")
        require(top.keys.toList() == TOP_LEVEL_FIELD_NAMES) { "unexpected top-level fields or order: ${top.keys}" }
        requireString(top.getValue("schema"), "schema")
        require(top.getValue("schema").jsonPrimitive.content == version.schema) { "unexpected schema identity" }
        val schemaVersion = top.getValue("schemaVersion") as? JsonPrimitive
            ?: throw IllegalArgumentException("schemaVersion must be an integer")
        require(!schemaVersion.isString && schemaVersion.intOrNull == version.number) { "unexpected schema version" }
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
            val route = command.getValue("name").jsonPrimitive.content
            require(command.getValue("modes").jsonArray.map { it.jsonPrimitive.content } == listOf("preview", "apply"))
            require(command.getValue("aliases").jsonArray.isEmpty())
            require(command.getValue("mutationAuthority").jsonPrimitive.content == "refactorkit-managed")
            val allowedSupport = if (version == CatalogueVersion.V1 || route != "java rename-module") "catalog-only" else "preview-only"
            require(command.getValue("jsonSupport").jsonPrimitive.content == allowedSupport) { "unexpected jsonSupport" }
            require(command.getValue("stability").jsonPrimitive.content == "experimental")
            require(command.getValue("requiredArguments").jsonArray.all { it.jsonPrimitive.content.startsWith("--") })
        }

        val observedRoutes = commands.map { entry ->
            val command = entry.jsonObject
            RouteOperation(command.getValue("name").jsonPrimitive.content, command.getValue("operation").jsonPrimitive.content)
        }
        require(observedRoutes == EXPECTED_ROUTES) { "unexpected route-operation inventory" }
    }

    private fun mutate(source: JsonObject, mutation: String): JsonObject = when (mutation) {
        "add unknown top-level field \"unexpected\" with value true" ->
            JsonObject(source + ("unexpected" to JsonPrimitive(true)))
        "add unknown field \"unexpected\" with value true to the first entry" -> {
            val commands = source.getValue("commands").jsonArray.toMutableList()
            commands[0] = JsonObject(commands[0].jsonObject + ("unexpected" to JsonPrimitive(true)))
            JsonObject(source + ("commands" to JsonArray(commands)))
        }
        "replace the first entry's \"jsonSupport\" with unknown value \"unknown\"" -> {
            val commands = source.getValue("commands").jsonArray.toMutableList()
            commands[0] = JsonObject(commands[0].jsonObject + ("jsonSupport" to JsonPrimitive("unknown")))
            JsonObject(source + ("commands" to JsonArray(commands)))
        }
        "replace the rename entry's \"jsonSupport\" with unknown value \"unknown\"" -> {
            val commands = source.getValue("commands").jsonArray.map { entry ->
                if (entry.jsonObject.getValue("name").jsonPrimitive.content == "java rename-module") {
                    JsonObject(entry.jsonObject + ("jsonSupport" to JsonPrimitive("unknown")))
                } else {
                    entry
                }
            }
            JsonObject(source + ("commands" to JsonArray(commands)))
        }
        else -> error("unexpected mutation: $mutation")
    }

    private fun requireString(element: JsonElement, field: String) {
        require(element is JsonPrimitive && element.isString) { "$field must be a string" }
    }

    private fun parse(bytes: ByteArray): JsonObject = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject

    private fun entries(document: JsonObject): List<JsonObject> =
        document.getValue("commands").jsonArray.map { it.jsonObject }

    private fun jsonTypeSignature(entry: JsonObject): List<String> = entry.values.map { value ->
        when (value) {
            is JsonArray -> "array"
            is JsonObject -> "object"
            is JsonPrimitive -> when {
                value.isString -> "string"
                value.intOrNull != null -> "integer"
                else -> "primitive"
            }
        }
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

    private fun manifestPath(relative: Path): String = relative.toString().replace('\\', '/').ifBlank { "." }

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

    private companion object {
        const val V1_ORACLE_FIXTURE =
            "org/refactorkit/cli/reqjavaclicatalog002/catalog-v1-oracle-4790e142d491.json"
        const val V2_ORACLE_FIXTURE =
            "org/refactorkit/cli/reqjavaclicatalog002/catalog-v2-oracle-6aaffe8c0c71.json"
        const val CAPABILITIES_FIXTURE =
            "org/refactorkit/cli/reqjavaclicatalog001/capabilities-pre-slice-dec4087fa8b7.json"
        const val V1_ORACLE_SHA256 = "4790e142d491d0fc3fd44dfa94c65c67997bc3ffaf0133691c0afebd9ee683cb"
        const val V2_ORACLE_SHA256 = "6aaffe8c0c718685510e745abc740c1896b02050a3eff7c19146f61c7abe109d"
        const val PINNED_CAPABILITIES_SHA256 = "dec4087fa8b7ba038a5b9a82728ec724054ee6fd28456eb9ec637fd7d0d0d1c3"
        const val HARNESS_PROTOCOL = "refactorkit.test.catalog-v2-guard/v1"
        const val PROBE_MODE = "probe"
        const val INVOKE_MODE = "invoke"
        const val PROCESS_TIMEOUT_SECONDS = 30L

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
        val RESULT_ENVELOPE_FIELDS = setOf("requestId", "status", "operation", "result", "diagnostics")
        val EXPECTED_ROUTES = listOf(
            RouteOperation("java create-module", "java.createMavenModule"),
            RouteOperation("java move-across-maven-modules", "java.moveAcrossMavenModules"),
            RouteOperation("java rename-module", "java.renameMavenModule"),
        )
        val EXPECTED_INVALID_SELECTORS = listOf(
            "malformed" to "refactorkit commands --json --schema-version one",
            "duplicate" to "refactorkit commands --json --schema-version 1 --schema-version 1",
            "missing value" to "refactorkit commands --json --schema-version",
            "unsupported" to "refactorkit commands --json --schema-version 2",
        )
        val EXPECTED_VALIDATOR_MUTATIONS = listOf(
            "v1" to "add unknown top-level field \"unexpected\" with value true",
            "v1" to "add unknown field \"unexpected\" with value true to the first entry",
            "v1" to "replace the first entry's \"jsonSupport\" with unknown value \"unknown\"",
            "v2" to "add unknown top-level field \"unexpected\" with value true",
            "v2" to "add unknown field \"unexpected\" with value true to the first entry",
            "v2" to "replace the rename entry's \"jsonSupport\" with unknown value \"unknown\"",
        )
        val EXPECTED_EXCLUSIONS = listOf(
            "REQ-JAVA-CLI-RESULT-002 through REQ-JAVA-CLI-RESULT-006",
            "JSON apply, refusal, usage, operational, internal-error, closure, redaction, or truncation result behavior",
            "JSON results for create-module, move-across-maven-modules, another Java command, or another language",
            "installDist or source-built-to-installed parity, installed execution, overwrite, or promotion",
            "a fresh SURFACE-002 apply, refusal, rollback, crash, recovery, or authority qualification",
            "non-Java command inventory or other language capability behavior",
            "Windows, macOS, another CPU architecture, another JDK, or another host",
            "package, installer, signed, native, broad release, or release-promotion behavior",
        )
        val REPOSITORY_MANIFEST_EXCLUDED_DIRECTORIES = setOf(".git", ".gradle", "build")
    }
}
