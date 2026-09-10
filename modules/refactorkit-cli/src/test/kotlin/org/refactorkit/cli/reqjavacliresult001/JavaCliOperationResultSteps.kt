package org.refactorkit.cli.reqjavacliresult001

import io.cucumber.datatable.DataTable
import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.Scenario
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.refactorkit.cli.RefactorKitCli
import org.refactorkit.cli.testharness.GuardedChildJvm
import org.refactorkit.core.RefactorKitVersion
import org.refactorkit.java.JavaProjectScanner
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.Base64
import java.util.EnumSet
import java.util.concurrent.locks.ReentrantLock
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RED-only Story BDD glue for REQ-JAVA-CLI-RESULT-001. */
class JavaCliOperationResultSteps {
    private data class ExpectedEdit(
        val order: Int,
        val kind: String,
        val path: String,
        val destination: String?,
        val textEditCount: Int,
        val startLine: Int?,
        val startCharacter: Int?,
        val endLine: Int?,
        val endCharacter: Int?,
        val replacement: ByteArray?,
    )

    private data class Invocation(
        val label: String,
        val arguments: List<String>,
        val root: Path,
        val exitCode: Int,
        val stdout: ByteArray,
        val stderr: ByteArray,
    )

    private data class ManifestEntry(
        val kind: String,
        val size: Long?,
        val sha256: String?,
        val linkTarget: String?,
        val permissions: String,
    )

    private data class ExactManifest(val entries: Map<String, ManifestEntry>)
    private data class DigestEvidence(val bytes: ByteArray, val sha256: String)
    private data class TopField(val position: Int, val name: String, val type: String, val value: String)
    private data class PlanField(val position: Int, val name: String, val type: String, val value: String)
    private data class VisibleChange(val order: Int, val kind: String, val path: String, val previousPath: String?)

    private lateinit var scenario: Scenario
    private lateinit var repositoryRoot: Path
    private lateinit var fixtureRoot: Path
    private lateinit var installedRoot: Path
    private lateinit var installedExecutable: Path
    private lateinit var sourceBuiltCodeLocation: Path
    private lateinit var temporaryRoot: Path
    private lateinit var machineRoot: Path
    private lateinit var humanRoot: Path
    private lateinit var permanentManifest: ExactManifest
    private lateinit var machineS0Manifest: ExactManifest
    private lateinit var humanS0Manifest: ExactManifest
    private lateinit var repositoryManifestBefore: ExactManifest
    private lateinit var installationManifestBefore: ExactManifest
    private lateinit var s0SnapshotSha256: String
    private lateinit var expectedEdits: List<ExpectedEdit>
    private lateinit var expectedDigest: DigestEvidence
    private lateinit var pinnedHumanStdout: ByteArray
    private lateinit var pinnedHumanStderr: ByteArray
    private var pinnedHumanExitCode: Int = Int.MIN_VALUE
    private lateinit var expectedJsonTemplate: String
    private lateinit var expectedJsonBytes: List<ByteArray>

    private val invocations = mutableListOf<Invocation>()
    private val boundaryViolationReports = mutableListOf<List<String>>()
    private var lockHeld = false
    private var publicScannerRuns = 0
    private var installedTripwireSelfTested = false
    private var mutationTripwireSelfTested = false
    private var exactArgvBound = false
    private var previewOnlyContractBound = false

    @Before("@REQ-JAVA-CLI-RESULT-001")
    fun prepareScenario(scenario: Scenario) {
        GLOBAL_STREAM_LOCK.lock()
        lockHeld = true
        try {
            this.scenario = scenario
            repositoryRoot = locateRepositoryRoot()
            fixtureRoot = repositoryRoot.resolve(FIXTURE_PATH).toAbsolutePath().normalize()
            installedRoot = Path.of(System.getProperty("user.home"), ".local", "share", "refactorkit")
                .toAbsolutePath().normalize()
            installedExecutable = installedRoot.resolve("bin/refactorkit").normalize()
            sourceBuiltCodeLocation = codeLocation(RefactorKitCli::class.java)
            temporaryRoot = Files.createTempDirectory("req-java-cli-result-001-").toAbsolutePath().normalize()
            machineRoot = temporaryRoot.resolve("machine-result").normalize()
            humanRoot = temporaryRoot.resolve("human-compatibility").normalize()
        } catch (failure: Throwable) {
            restoreAndUnlock()
            throw failure
        }
    }

    @After("@REQ-JAVA-CLI-RESULT-001")
    fun verifyBoundariesAndClean() {
        try {
            if (this::fixtureRoot.isInitialized && this::permanentManifest.isInitialized) {
                assertEquals(permanentManifest, captureManifest(fixtureRoot), "Permanent fixture changed")
            }
            if (this::machineS0Manifest.isInitialized) {
                assertEquals(machineS0Manifest, captureManifest(machineRoot, excludeEngine = true), "Machine workspace changed")
                assertFalse(Files.exists(machineRoot.resolve(ENGINE_DIRECTORY), LinkOption.NOFOLLOW_LINKS))
            }
            if (this::humanS0Manifest.isInitialized) {
                assertEquals(humanS0Manifest, captureManifest(humanRoot, excludeEngine = true), "Human workspace changed")
                assertFalse(Files.exists(humanRoot.resolve(ENGINE_DIRECTORY), LinkOption.NOFOLLOW_LINKS))
            }
            if (this::installationManifestBefore.isInitialized) {
                assertEquals(installationManifestBefore, captureManifest(installedRoot), "Installed runtime changed")
            }
            if (this::repositoryManifestBefore.isInitialized) {
                assertEquals(
                    repositoryManifestBefore,
                    captureManifest(repositoryRoot, repositoryProtected = true),
                    "Repository source or documentation changed during the scenario",
                )
            }
            assertTrue(boundaryViolationReports.all { it.isEmpty() }, boundaryViolationReports.flatten().joinToString())
        } finally {
            restoreAndUnlock()
        }
    }

    @Given("the source-built RefactorKit 0.7.0 CLI entrypoint runs locally on Linux with JDK 21 and the current CPU architecture")
    fun sourceBuiltRuntimeIsQualified() {
        assertEquals("0.7.0", RefactorKitVersion.VERSION)
        assertEquals(21, Runtime.version().feature())
        assertTrue(System.getProperty("os.name").contains("Linux", ignoreCase = true))
        assertTrue(System.getProperty("os.arch").isNotBlank())
        assertTrue(sourceBuiltCodeLocation.startsWith(repositoryRoot), sourceBuiltCodeLocation.toString())
        assertTrue(
            sourceBuiltCodeLocation.invariantSeparatorsPathString.contains("/modules/refactorkit-cli/build/"),
            sourceBuiltCodeLocation.toString(),
        )
        assertFalse(sourceBuiltCodeLocation.startsWith(installedRoot))
        assertEquals(BASELINE_SHA256, sha256(Files.readAllBytes(repositoryRoot.resolve(BASELINE_PATH))))
        assertEquals(APPROVED_CHANGE_SHA256, sha256(Files.readAllBytes(repositoryRoot.resolve(APPROVED_CHANGE_PATH))))
        assertStatusNeutralFeatureIntegrity(repositoryRoot.resolve(FEATURE_PATH))
        repositoryManifestBefore = captureManifest(repositoryRoot, repositoryProtected = true)
    }

    @Given("the permanent fixture {string} is an immutable offline reactor with one root aggregator and exactly 20 direct non-aggregator JAR children")
    fun permanentFixtureIsQualified(path: String) {
        assertEquals(FIXTURE_PATH, path)
        assertTrue(Files.isDirectory(fixtureRoot, LinkOption.NOFOLLOW_LINKS))
        permanentManifest = captureManifest(fixtureRoot)
        assertTrue(permanentManifest.entries.values.none { it.kind == "symbolic-link" })
        val rootPom = parsePom(fixtureRoot.resolve("pom.xml"))
        assertEquals("pom", directChildText(rootPom, "packaging"))
        val modules = directChildren(directChildren(rootPom, "modules").single(), "module")
            .map { it.textContent.trim() }
        assertEquals(20, modules.size)
        assertEquals(20, modules.toSet().size)
        modules.forEach { module ->
            val child = parsePom(fixtureRoot.resolve(module).resolve("pom.xml"))
            assertEquals("jar", directChildText(child, "packaging") ?: "jar", module)
            assertTrue(directChildren(child, "modules").isEmpty(), "$module must not aggregate modules")
        }
    }

    @Given("the harness creates pairwise-distinct machine-result and human-compatibility workspaces as fresh no-follow disposable byte copies of that permanent fixture, refusing every symbolic link and preserving every relative regular-file byte and path kind")
    fun createNoFollowCopies() {
        copyNoFollow(fixtureRoot, machineRoot)
        copyNoFollow(fixtureRoot, humanRoot)
        machineS0Manifest = captureManifest(machineRoot)
        humanS0Manifest = captureManifest(humanRoot)
        assertEquals(permanentManifest, machineS0Manifest)
        assertEquals(permanentManifest, humanS0Manifest)
        assertNotEquals(machineRoot, humanRoot)
        assertFalse(machineRoot.startsWith(humanRoot))
        assertFalse(humanRoot.startsWith(machineRoot))
    }

    @Given("each copy has {string} as one exact direct child, has no {string} path, and contains the qualified source at {string}")
    fun copiesHaveExactQualifiedShape(oldModule: String, newModule: String, source: String) {
        assertEquals(OLD_MODULE, oldModule)
        assertEquals(NEW_MODULE, newModule)
        assertEquals(PRODUCT_SOURCE, source)
        listOf(machineRoot, humanRoot).forEach { root ->
            val modules = directChildren(directChildren(parsePom(root.resolve("pom.xml")), "modules").single(), "module")
                .map { it.textContent.trim() }
            assertEquals(1, modules.count { it == oldModule })
            assertFalse(Files.exists(root.resolve(newModule), LinkOption.NOFOLLOW_LINKS))
            assertTrue(Files.isRegularFile(root.resolve(source), LinkOption.NOFOLLOW_LINKS))
        }
    }

    @Given("every scan, plan, invocation, and observation is confined to its declared disposable root, while the permanent fixture remains read-only")
    fun confineInteractions() {
        assertTrue(machineRoot.startsWith(temporaryRoot))
        assertTrue(humanRoot.startsWith(temporaryRoot))
        assertFalse(fixtureRoot.startsWith(temporaryRoot))
        assertEquals(permanentManifest, captureManifest(fixtureRoot))
    }

    @Given("the existing installation under {string} is guarded by fail-closed invocation, lookup, and no-follow mutation tripwires and is neither selected nor used as acceptance evidence")
    fun guardInstalledRuntime(path: String) {
        assertEquals("~/.local/share/refactorkit", path)
        assertTrue(Files.isDirectory(installedRoot, LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.isRegularFile(installedExecutable, LinkOption.NOFOLLOW_LINKS))
        installationManifestBefore = captureManifest(installedRoot)
        assertFalse(sourceBuiltCodeLocation.startsWith(installedRoot))

        val probe = ResultPreviewBoundarySecurityManager(machineRoot, fixtureRoot, installedRoot, repositoryRoot)
        assertFailsWith<SecurityException> { probe.checkExec(installedExecutable.toString()) }
        assertFailsWith<SecurityException> { probe.checkRead(installedExecutable.toString()) }
        assertFailsWith<SecurityException> { probe.checkWrite(installedExecutable.toString()) }
        assertEquals(listOf("exec", "installed-read", "protected-write"), probe.violations.map { it.substringBefore(':') })

        val childProbe = launchResultHarness(PROBE_MODE, machineRoot, emptyList())
        assertEquals(
            listOf(
                "exec",
                "installed-read",
                "fixture-read",
                "repository-read",
                "engine-read",
                "protected-write",
                "protected-delete",
                "network",
            ),
            childProbe.getValue("violations").jsonArray.map { it.jsonPrimitive.content.substringBefore(':') },
        )
        installedTripwireSelfTested = true
    }

    @Given("before any CLI invocation the harness independently retains the machine-result workspace's exact no-follow non-engine image and its public-scanner snapshot SHA-256 as {string}, without reading a CLI result, projected plan, or internal PlanId")
    fun retainIndependentS0(name: String) {
        assertEquals("S0", name)
        assertTrue(invocations.isEmpty())
        machineS0Manifest = captureManifest(machineRoot, excludeEngine = true)
        val response = launchResultHarness(SCAN_MODE, machineRoot, emptyList())
        val violations = response.getValue("violations").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(violations.isEmpty(), response.toString())
        boundaryViolationReports += violations
        publicScannerRuns += 1
        s0SnapshotSha256 = response.getValue("snapshotSha256").jsonPrimitive.content
        assertTrue(SHA256.matches(s0SnapshotSha256))
        assertEquals(20, response.getValue("moduleCount").jsonPrimitive.intOrNull)
        assertEquals(machineS0Manifest, captureManifest(machineRoot, excludeEngine = true))
        assertFalse(Files.exists(machineRoot.resolve(ENGINE_DIRECTORY), LinkOption.NOFOLLOW_LINKS))
    }

    @Given("the complete normalized WorkspaceEdit oracle is declared independently from immutable fixture bytes with exactly these entries in application order:")
    fun bindCompleteEditOracle(table: DataTable) {
        expectedEdits = table.asMaps().map { row ->
            ExpectedEdit(
                order = row.getValue("order").toInt(),
                kind = row.getValue("kind"),
                path = row.getValue("canonical path or source path"),
                destination = row.getValue("destination path").nullValue(),
                textEditCount = row.getValue("text-edit count").toInt(),
                startLine = row.getValue("start line").nullableInt(),
                startCharacter = row.getValue("start character").nullableInt(),
                endLine = row.getValue("end line").nullableInt(),
                endCharacter = row.getValue("end character").nullableInt(),
                replacement = parseReplacement(row.getValue("exact replacement UTF-8 bytes")),
            )
        }
        assertEquals((1..5).toList(), expectedEdits.map { it.order })
        assertEquals(listOf("modify", "modify", "modify", "move", "move"), expectedEdits.map { it.kind })
        assertEquals(EXPECTED_EDIT_PATHS, expectedEdits.map { it.path })
        assertEquals(EXPECTED_EDIT_DESTINATIONS, expectedEdits.map { it.destination })
        expectedEdits.filter { it.kind == "modify" }.forEach { edit ->
            assertEquals(1, edit.textEditCount)
            assertContentEquals(REPLACEMENT_BYTES, assertNotNull(edit.replacement))
            val bytes = readAllBytesNoFollow(machineRoot.resolve(edit.path))
            assertEquals(sha256(readAllBytesNoFollow(fixtureRoot.resolve(edit.path))), sha256(bytes))
            val lines = bytes.toString(StandardCharsets.UTF_8).split('\n')
            val selected = lines[assertNotNull(edit.startLine)].substring(
                assertNotNull(edit.startCharacter),
                assertNotNull(edit.endCharacter),
            )
            assertEquals(edit.startLine, edit.endLine)
            assertEquals(OLD_MODULE, selected, "Literal range drifted for ${edit.path}")
        }
        expectedEdits.filter { it.kind == "move" }.forEach { edit ->
            assertEquals(0, edit.textEditCount)
            assertNull(edit.replacement)
            assertNotNull(edit.destination)
            assertTrue(Files.isRegularFile(machineRoot.resolve(edit.path), LinkOption.NOFOLLOW_LINKS))
            assertFalse(Files.exists(machineRoot.resolve(edit.destination), LinkOption.NOFOLLOW_LINKS))
        }
    }

    @Given("before interaction the harness independently computes {string} from {string} and that complete literal edit, without calling result-projection or plan-digest production code, by framing every string as its unsigned 32-bit big-endian UTF-8 byte length followed by its exact UTF-8 bytes")
    fun computeIndependentDigest(digestName: String, snapshotName: String) {
        assertEquals("EXPECTED_PLAN_SHA256", digestName)
        assertEquals("S0", snapshotName)
        assertEquals(0, publicScannerRuns - 1)
        expectedDigest = computeCompleteEditDigest(s0SnapshotSha256, expectedEdits)
        assertTrue(SHA256.matches(expectedDigest.sha256))
    }

    @Given("that digest input frames, in order, {string}, {string}, and the independently retained {string} snapshot SHA-256, then appends byte 0x01 and unsigned 32-bit big-endian edit count 5")
    fun digestPrefixIsExact(schema: String, command: String, snapshotName: String) {
        assertEquals(PLAN_DIGEST_SCHEMA, schema)
        assertEquals(COMMAND_IDENTITY, command)
        assertEquals("S0", snapshotName)
        val expectedPrefix = framed(schema) + framed(command) + framed(s0SnapshotSha256) + byteArrayOf(0x01) + int32(5)
        assertContentEquals(expectedPrefix, expectedDigest.bytes.copyOfRange(0, expectedPrefix.size))
    }

    @Given("for each modify in the declared order the digest input appends byte 0x01, its framed canonical path, unsigned 32-bit big-endian text-edit count 1, its four declared zero-based coordinates as unsigned 32-bit big-endian values, and its framed exact 14 replacement bytes, with text edits ordered by start line, start character, end line, and end character")
    fun digestModifyFramingIsExact() {
        val modifies = expectedEdits.filter { it.kind == "modify" }
        assertEquals(listOf(1, 2, 3), modifies.map { it.order })
        modifies.forEach { edit ->
            assertContentEquals(REPLACEMENT_BYTES, assertNotNull(edit.replacement))
            assertEquals(14, edit.replacement.size)
            assertTrue(listOf(edit.startLine, edit.startCharacter, edit.endLine, edit.endCharacter).all { it != null })
        }
        assertEquals(expectedDigest.sha256, computeCompleteEditDigest(s0SnapshotSha256, expectedEdits).sha256)
    }

    @Given("for each move in the declared order the digest input appends byte 0x02, its framed canonical source path, and its framed canonical destination path")
    fun digestMoveFramingIsExact() {
        val moves = expectedEdits.filter { it.kind == "move" }
        assertEquals(listOf(4, 5), moves.map { it.order })
        moves.forEach { edit ->
            assertNotNull(edit.destination)
            assertTrue(isCanonicalRelative(edit.path))
            assertTrue(isCanonicalRelative(edit.destination))
        }
        assertEquals(expectedDigest.sha256, sha256(expectedDigest.bytes))
    }

    @Given("no request ID, internal PlanId, summary, warning, confidence, risk, diagnostic, authority object, timestamp, projection limit, or truncation state contributes to {string}")
    fun digestExcludesNonEditMetadata(name: String) {
        assertEquals("EXPECTED_PLAN_SHA256", name)
        val framedInput = expectedDigest.bytes.toString(StandardCharsets.ISO_8859_1)
        NON_DIGEST_SENTINELS.forEach { sentinel -> assertFalse(framedInput.contains(sentinel), sentinel) }
        assertEquals(expectedDigest.sha256, computeCompleteEditDigest(s0SnapshotSha256, expectedEdits).sha256)
    }

    @Given("fail-closed observations cover workspace-lock acquisition, {string} creation, WAL access, transaction creation, managed file edits, rollback execution or claims, installed-runtime invocation or lookup, and installation mutation")
    fun armPreviewMutationTripwires(engineDirectory: String) {
        assertEquals(ENGINE_DIRECTORY, engineDirectory)
        val probe = ResultPreviewBoundarySecurityManager(machineRoot, fixtureRoot, installedRoot, repositoryRoot)
        assertFailsWith<SecurityException> { probe.checkWrite(machineRoot.resolve(WORKSPACE_LOCK).toString()) }
        assertFailsWith<SecurityException> { probe.checkRead(machineRoot.resolve(TRANSACTION_DIRECTORY).toString()) }
        assertFailsWith<SecurityException> { probe.checkWrite(machineRoot.resolve(OLD_MODULE).resolve("pom.xml").toString()) }
        assertFailsWith<SecurityException> { probe.checkDelete(machineRoot.resolve(PRODUCT_SOURCE).toString()) }
        assertFailsWith<SecurityException> { probe.checkExec("refactorkit patch rollback probe") }
        assertFailsWith<SecurityException> { probe.checkConnect("example.invalid", 443) }
        assertEquals(
            listOf("protected-write", "engine-read", "protected-write", "protected-delete", "exec", "network"),
            probe.violations.map { it.substringBefore(':') },
        )
        mutationTripwireSelfTested = true
    }

    @Given("the human-mode stdout bytes, stderr bytes, and exit status for the exact qualified request are pinned from source-built base commit {string} before result-protocol production changes and without invoking the installed runtime")
    fun loadPinnedHumanBaseline(baseCommit: String) {
        assertEquals(BASE_COMMIT, baseCommit)
        pinnedHumanStdout = resourceBytes(HUMAN_STDOUT_RESOURCE)
        pinnedHumanStderr = resourceBytes(HUMAN_STDERR_RESOURCE)
        pinnedHumanExitCode = resourceBytes(HUMAN_EXIT_RESOURCE).toString(StandardCharsets.US_ASCII).trim().toInt()
        assertEquals(HUMAN_STDOUT_SHA256, sha256(pinnedHumanStdout))
        assertEquals(HUMAN_STDERR_SHA256, sha256(pinnedHumanStderr))
        assertEquals(0, pinnedHumanExitCode)
        assertEquals(HUMAN_BUNDLE_SHA256, humanBundleSha256(pinnedHumanExitCode, pinnedHumanStdout, pinnedHumanStderr))
        scenario.attach(
            "s0SnapshotSha256=$s0SnapshotSha256\n" +
                "expectedPlanSha256=${expectedDigest.sha256}\n" +
                "humanBaselineBundleSha256=$HUMAN_BUNDLE_SHA256\n",
            "text/plain",
            "independent-oracle-identities",
        )
        assertTrue(installedTripwireSelfTested)
        assertTrue(mutationTripwireSelfTested)
        assertTrue(invocations.isEmpty())
    }

    @When("the public source-built parser invokes these exact requests, substituting only each normalized disposable root for its named placeholder and never adding {string}:")
    fun invokeExactRequests(forbiddenFlag: String, table: DataTable) {
        assertEquals("--apply", forbiddenFlag)
        val rows = table.asMaps()
        assertEquals(listOf("1", "2", "3", "4"), rows.map { it.getValue("order") })
        assertEquals(EXPECTED_TABLE_INVOCATION_LABELS, rows.map { it.getValue("invocation") })
        rows.forEachIndexed { index, row ->
            val template = row.getValue("exact argv")
            val expectedTemplate = when (index) {
                0, 1 -> JSON_ARGV_TEMPLATE
                2 -> GENERATED_JSON_ARGV_TEMPLATE
                else -> HUMAN_ARGV_TEMPLATE
            }
            assertEquals(expectedTemplate, template)
            assertFalse(template.split(' ').contains(forbiddenFlag))
            val root = if (index < 3) machineRoot else humanRoot
            val placeholder = if (index < 3) "<machine-result-root>" else "<human-compatibility-root>"
            assertEquals(1, template.split(' ').count { it == placeholder })
            val arguments = template.split(' ').drop(1).map { token -> if (token == placeholder) root.toString() else token }
            assertEquals(0, arguments.count { it == placeholder })
            val result = guardedInvoke(EXPECTED_INVOCATION_LABELS[index], root, arguments)
            invocations += result
            scenario.attach(result.stdout, "application/octet-stream", "${result.label}-stdout")
            scenario.attach(result.stderr, "application/octet-stream", "${result.label}-stderr")
        }
        assertEquals(4, invocations.size)
        exactArgvBound = true
        previewOnlyContractBound = invocations.all { "--apply" !in it.arguments }
    }

    @Then("each of the three JSON invocations exits 0 and has empty stderr")
    fun jsonInvocationsExitZeroWithEmptyStderr() {
        assertEquals(3, jsonInvocations().size)
        jsonInvocations().forEach { invocation ->
            assertEquals(0, invocation.exitCode, "${invocation.label} exit; stderr=${invocation.stderr.toString(StandardCharsets.UTF_8)}")
            assertTrue(invocation.stderr.isEmpty(), "${invocation.label} stderr=${invocation.stderr.toString(StandardCharsets.UTF_8)}")
        }
    }

    @Then("each JSON stdout is exactly the compact UTF-8 encoding of this single object after substituting its independently expected request ID, {string}, and retained {string} snapshot SHA-256, followed by one LF byte and no other byte:")
    fun jsonStdoutIsExactEnvelope(digestName: String, snapshotName: String, expectedJson: String) {
        assertEquals("EXPECTED_PLAN_SHA256", digestName)
        assertEquals("S0", snapshotName)
        expectedJsonTemplate = expectedJson.trim()
        assertEquals(1, expectedJsonTemplate.lineSequence().count())
        val generatedRequestId = generatedRequestIdFrom(jsonInvocations()[2])
        val requestIds = listOf(FIXED_REQUEST_ID, FIXED_REQUEST_ID, generatedRequestId)
        expectedJsonBytes = requestIds.map { requestId -> expectedEnvelopeBytes(requestId) }
        jsonInvocations().zip(expectedJsonBytes).forEach { (invocation, expected) ->
            assertContentEquals(
                expected,
                invocation.stdout,
                "REQ-JAVA-CLI-RESULT-001 expected exactly one refactorkit.cli-result/v1 object plus LF from " +
                    "public RefactorKitCli.run for ${invocation.label}; ${protocolMismatch(invocation.stdout)}",
            )
        }
    }

    @Then("the top-level object has exactly these fields in producer order, with no {string}, {string}, duplicate, or additional field:")
    fun topLevelObjectIsClosed(schemaField: String, errorField: String, table: DataTable) {
        assertEquals("schema", schemaField)
        assertEquals("error", errorField)
        val expected = table.asMaps().map { row ->
            TopField(row.getValue("position").toInt(), row.getValue("field"), row.getValue("exact JSON type"), row.getValue("exact value for this qualified preview"))
        }
        assertEquals(EXPECTED_TOP_FIELDS, expected)
        resultObjects().forEachIndexed { index, result ->
            assertEquals(EXPECTED_TOP_FIELDS.map { it.name }, result.keys.toList())
            assertFalse(schemaField in result)
            assertFalse(errorField in result)
            assertEquals(1, result.getValue("schemaVersion").jsonPrimitive.intOrNull)
            assertEquals(COMMAND_IDENTITY, result.getValue("command").jsonPrimitive.content)
            assertEquals(if (index < 2) FIXED_REQUEST_ID else generatedRequestIdFrom(jsonInvocations()[2]), result.getValue("requestId").jsonPrimitive.content)
            assertEquals("preview", result.getValue("outcome").jsonPrimitive.content)
            assertTrue(result.getValue("transaction") is JsonNull)
            assertTrue(result.getValue("diagnostics") is JsonArray && result.getValue("diagnostics").jsonArray.isEmpty())
            assertEquals(false, result.getValue("truncated").jsonPrimitive.booleanOrNull)
        }
    }

    @Then("the plan object has exactly these fields in producer order and exact values derived from the independent oracles rather than from an output or internal PlanId:")
    fun planObjectIsExact(table: DataTable) {
        val expected = table.asMaps().map { row ->
            PlanField(row.getValue("position").toInt(), row.getValue("field"), row.getValue("exact JSON type"), row.getValue("exact value"))
        }
        assertEquals(EXPECTED_PLAN_FIELDS, expected)
        resultObjects().forEach { result ->
            val plan = result.getValue("plan").jsonObject
            assertEquals(EXPECTED_PLAN_FIELDS.map { it.name }, plan.keys.toList())
            assertEquals(expectedDigest.sha256, plan.getValue("sha256").jsonPrimitive.content)
            assertEquals(s0SnapshotSha256, plan.getValue("snapshotSha256").jsonPrimitive.content)
            assertEquals(true, plan.getValue("requiresApproval").jsonPrimitive.booleanOrNull)
            assertEquals(5, plan.getValue("changeCount").jsonPrimitive.intOrNull)
            assertEquals(5, plan.getValue("changes").jsonArray.size)
        }
    }

    @Then("the changes array preserves the complete normalized WorkspaceEdit application order and contains exactly these objects, each with fields {string}, {string}, and {string} in that order:")
    fun changesAreExact(kindField: String, pathField: String, previousPathField: String, table: DataTable) {
        assertEquals(listOf("kind", "path", "previousPath"), listOf(kindField, pathField, previousPathField))
        val expected = table.asMaps().map { row ->
            VisibleChange(row.getValue("order").toInt(), row.getValue("kind"), row.getValue("path"), row.getValue("previousPath").nullValue())
        }
        assertEquals(EXPECTED_VISIBLE_CHANGES, expected)
        resultObjects().forEach { result ->
            val changes = result.getValue("plan").jsonObject.getValue("changes").jsonArray.map { it.jsonObject }
            assertEquals(expected.size, changes.size)
            expected.zip(changes).forEach { (oracle, actual) ->
                assertEquals(listOf(kindField, pathField, previousPathField), actual.keys.toList())
                assertEquals(oracle.kind, actual.getValue(kindField).jsonPrimitive.content)
                assertEquals(oracle.path, actual.getValue(pathField).jsonPrimitive.content)
                if (oracle.previousPath == null) assertTrue(actual.getValue(previousPathField) is JsonNull)
                else assertEquals(oracle.previousPath, actual.getValue(previousPathField).jsonPrimitive.content)
            }
        }
    }

    @Then("every modify uses its canonical modified path with null {string}, while every move uses its destination as {string} and its source as {string}")
    fun visiblePathSemanticsAreExact(previousPath: String, path: String, repeatedPreviousPath: String) {
        assertEquals("previousPath", previousPath)
        assertEquals("path", path)
        assertEquals(previousPath, repeatedPreviousPath)
        EXPECTED_VISIBLE_CHANGES.forEach { change ->
            assertTrue(isCanonicalRelative(change.path))
            if (change.kind == "modify") assertNull(change.previousPath)
            else assertTrue(isCanonicalRelative(assertNotNull(change.previousPath)))
        }
    }

    @Then("^both fixed-correlation results preserve \"([^\"]*)\" byte-for-byte, that value matches `\\Q[A-Za-z0-9][A-Za-z0-9._:-]{0,127}\\E`, and their complete stdout byte sequences are identical$")
    fun fixedCorrelationIsOpaqueAndDeterministic(requestId: String) {
        assertEquals(FIXED_REQUEST_ID, requestId)
        assertTrue(REQUEST_ID.matches(requestId))
        assertEquals(requestId, resultObjects()[0].getValue("requestId").jsonPrimitive.content)
        assertEquals(requestId, resultObjects()[1].getValue("requestId").jsonPrimitive.content)
        assertContentEquals(invocations[0].stdout, invocations[1].stdout)
    }

    @Then("^the generated result has one newly generated request ID matching `\\Qrequest-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\E` while every other expected result value remains derived from the same \"([^\"]*)\" and complete edit oracles$")
    fun generatedCorrelationIsBounded(snapshotName: String) {
        assertEquals("S0", snapshotName)
        val generated = generatedRequestIdFrom(jsonInvocations()[2])
        assertTrue(GENERATED_REQUEST_ID.matches(generated), generated)
        assertNotEquals(FIXED_REQUEST_ID, generated)
        assertContentEquals(expectedEnvelopeBytes(generated), jsonInvocations()[2].stdout)
    }

    @Then("no caller-supplied or generated request ID is accepted as snapshot authority, plan identity, approval, apply authority, transaction identity, idempotency, authentication, or semantic evidence")
    fun requestIdsRemainCorrelationOnly() {
        assertFalse(expectedDigest.bytes.toString(StandardCharsets.ISO_8859_1).contains(FIXED_REQUEST_ID))
        assertTrue(invocations.all { "--apply" !in it.arguments })
        assertTrue(resultObjects().all { it.getValue("transaction") is JsonNull })
        assertTrue(resultObjects().all { result ->
            result.getValue("plan").jsonObject.getValue("snapshotSha256").jsonPrimitive.content == s0SnapshotSha256
        })
    }

    @Then("every JSON stream contains no ANSI sequence, logger output, progress text, human prose, or stack trace")
    fun jsonStreamsContainOnlyProtocolBytes() {
        jsonInvocations().forEachIndexed { index, invocation ->
            assertContentEquals(expectedJsonBytes[index], invocation.stdout)
            assertTrue(invocation.stderr.isEmpty())
            assertFalse(invocation.stdout.contains(0x1b.toByte()))
            assertFalse(invocation.stdout.toString(StandardCharsets.UTF_8).contains("Operation:"))
            assertFalse(invocation.stdout.toString(StandardCharsets.UTF_8).contains("Exception"))
        }
    }

    @Then("the human-compatibility invocation has stdout, stderr, and exit status byte-for-byte equal to its pinned pre-slice source-built baseline")
    fun humanModeRemainsPinned() {
        val human = invocations.single { it.label == "human-compatibility" }
        assertEquals(pinnedHumanExitCode, human.exitCode)
        assertContentEquals(pinnedHumanStdout, human.stdout)
        assertContentEquals(pinnedHumanStderr, human.stderr)
        assertEquals(HUMAN_BUNDLE_SHA256, humanBundleSha256(human.exitCode, human.stdout, human.stderr))
    }

    @Then("all permitted preview activity is read-only scanning and planning of the disposable workspace, so this scenario does not claim that no workspace scan occurs")
    fun previewAllowsReadOnlyScanning() {
        assertTrue(publicScannerRuns >= 1)
        assertTrue(exactArgvBound)
        assertTrue(previewOnlyContractBound)
        assertTrue(invocations.all { "--apply" !in it.arguments })
    }

    @Then("both disposable workspaces retain their exact pre-invocation non-engine bytes and path kinds, {string} remains absent, every fail-closed mutation tripwire remains untriggered, no lock, WAL, transaction, managed edit, or rollback operation or claim exists, and the installed runtime was neither invoked nor mutated")
    fun previewsLeaveNoMutation(engineDirectory: String) {
        assertEquals(ENGINE_DIRECTORY, engineDirectory)
        assertEquals(machineS0Manifest, captureManifest(machineRoot, excludeEngine = true))
        assertEquals(humanS0Manifest, captureManifest(humanRoot, excludeEngine = true))
        assertFalse(Files.exists(machineRoot.resolve(engineDirectory), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(humanRoot.resolve(engineDirectory), LinkOption.NOFOLLOW_LINKS))
        assertTrue(boundaryViolationReports.all { it.isEmpty() })
        assertEquals(installationManifestBefore, captureManifest(installedRoot))
        assertTrue(invocations.none { it.arguments.any { token -> token in setOf("--apply", "rollback") } })
        assertTrue(invocations.none { invocation ->
            val output = invocation.stdout.toString(StandardCharsets.UTF_8) + invocation.stderr.toString(StandardCharsets.UTF_8)
            output.contains("Transaction:") || output.contains("Rolled back transaction")
        })
    }

    @Then("this scenario makes no implementation, validation, promotion, or support claim for:")
    fun excludedScopeRemainsDeferred(table: DataTable) {
        assertEquals(EXPECTED_EXCLUSIONS, table.asMaps().map { it.getValue("explicitly excluded scope") })
        assertTrue(invocations.all { "--apply" !in it.arguments })
        assertTrue(sourceBuiltCodeLocation.startsWith(repositoryRoot))
        assertFalse(sourceBuiltCodeLocation.startsWith(installedRoot))
    }

    private fun guardedInvoke(label: String, root: Path, arguments: List<String>): Invocation {
        require(root == machineRoot || root == humanRoot)
        require(arguments.firstOrNull() == "java")
        require("--apply" !in arguments)
        val response = launchResultHarness(INVOKE_MODE, root, arguments)
        val violations = response.getValue("violations").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(violations.isEmpty(), response.toString())
        boundaryViolationReports += violations
        assertFalse(Files.exists(root.resolve(ENGINE_DIRECTORY), LinkOption.NOFOLLOW_LINKS))
        return Invocation(
            label = label,
            arguments = arguments.toList(),
            root = root,
            exitCode = assertNotNull(response.getValue("cliExitCode").jsonPrimitive.intOrNull),
            stdout = Base64.getDecoder().decode(response.getValue("stdoutBase64").jsonPrimitive.content),
            stderr = Base64.getDecoder().decode(response.getValue("stderrBase64").jsonPrimitive.content),
        )
    }

    private fun launchResultHarness(mode: String, root: Path, arguments: List<String>): JsonObject {
        val javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toAbsolutePath().normalize()
        assertFalse(javaExecutable.startsWith(installedRoot), "guard harness must not select the installed runtime")
        val child = GuardedChildJvm.launch(
            workingDirectory = repositoryRoot,
            mainClass = JavaCliOperationResultProcessHarness::class.java,
            arguments = listOf(
                mode,
                repositoryRoot.toString(),
                fixtureRoot.toString(),
                installedRoot.toString(),
                root.toString(),
            ) + arguments,
            requiredClasses = listOf(RefactorKitCli::class.java, JavaProjectScanner::class.java),
            timeoutSeconds = PROCESS_TIMEOUT_SECONDS,
        )
        assertEquals(0, child.exitCode, "result guard harness failed: ${child.stderr.toString(StandardCharsets.UTF_8)}")
        GuardedChildJvm.assertOnlySecurityManagerDeprecationNotice(
            child.stderr,
            JavaCliOperationResultProcessHarness::class.java,
        )
        val response = Json.parseToJsonElement(child.stdout.toString(StandardCharsets.UTF_8)).jsonObject
        assertEquals(HARNESS_PROTOCOL, response.getValue("protocol").jsonPrimitive.content)
        assertEquals(mode, response.getValue("mode").jsonPrimitive.content)
        assertEquals(21, response.getValue("runtimeFeature").jsonPrimitive.intOrNull)
        assertEquals(true, response.getValue("securityManagerInstalled").jsonPrimitive.booleanOrNull)
        assertTrue(
            Base64.getDecoder().decode(response.getValue("managerDiagnosticsBase64").jsonPrimitive.content).isEmpty(),
            "result guard manager emitted unexpected in-process diagnostics",
        )
        return response
    }

    private fun jsonInvocations(): List<Invocation> = invocations.take(3)

    private fun resultObjects(): List<JsonObject> = jsonInvocations().map { invocation ->
        val text = invocation.stdout.toString(StandardCharsets.UTF_8)
        assertTrue(text.endsWith("\n") && !text.endsWith("\n\n"), "${invocation.label} LF framing")
        Json.parseToJsonElement(text.dropLast(1)).jsonObject
    }

    private fun generatedRequestIdFrom(invocation: Invocation): String {
        val text = invocation.stdout.toString(StandardCharsets.UTF_8)
        return runCatching {
            Json.parseToJsonElement(text.removeSuffix("\n")).jsonObject.getValue("requestId").jsonPrimitive.content
        }.getOrElse {
            "<GENERATED_REQUEST_ID_UNAVAILABLE>"
        }
    }

    private fun expectedEnvelopeBytes(requestId: String): ByteArray = expectedJsonTemplate
        .replace("<REQUEST_ID>", requestId)
        .replace("<EXPECTED_PLAN_SHA256>", expectedDigest.sha256)
        .replace("<S0_SNAPSHOT_SHA256>", s0SnapshotSha256)
        .toByteArray(StandardCharsets.UTF_8) + byteArrayOf('\n'.code.toByte())

    private fun protocolMismatch(actual: ByteArray): String {
        val text = actual.toString(StandardCharsets.UTF_8)
        return if (text.startsWith("Operation: java.renameMavenModule\nStatus: PREVIEW\n")) {
            "observed legacy human PatchPreviewRenderer text beginning 'Operation: java.renameMavenModule' instead of JSON"
        } else {
            "observed ${actual.size} non-contract stdout bytes beginning ${text.take(80).replace("\n", "\\n")}"
        }
    }

    private fun computeCompleteEditDigest(snapshotSha256: String, edits: List<ExpectedEdit>): DigestEvidence {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeFramed(PLAN_DIGEST_SCHEMA.toByteArray(StandardCharsets.UTF_8))
            output.writeFramed(COMMAND_IDENTITY.toByteArray(StandardCharsets.UTF_8))
            output.writeFramed(snapshotSha256.toByteArray(StandardCharsets.UTF_8))
            output.writeByte(0x01)
            output.writeInt(edits.size)
            edits.sortedBy { it.order }.forEach { edit ->
                when (edit.kind) {
                    "modify" -> {
                        output.writeByte(0x01)
                        output.writeFramed(edit.path.toByteArray(StandardCharsets.UTF_8))
                        output.writeInt(edit.textEditCount)
                        output.writeInt(assertNotNull(edit.startLine))
                        output.writeInt(assertNotNull(edit.startCharacter))
                        output.writeInt(assertNotNull(edit.endLine))
                        output.writeInt(assertNotNull(edit.endCharacter))
                        output.writeFramed(assertNotNull(edit.replacement))
                    }
                    "move" -> {
                        output.writeByte(0x02)
                        output.writeFramed(edit.path.toByteArray(StandardCharsets.UTF_8))
                        output.writeFramed(assertNotNull(edit.destination).toByteArray(StandardCharsets.UTF_8))
                    }
                    else -> error("Unsupported literal edit kind ${edit.kind}")
                }
            }
        }
        val preimage = bytes.toByteArray()
        return DigestEvidence(preimage, sha256(preimage))
    }

    private fun DataOutputStream.writeFramed(value: ByteArray) {
        require(value.size >= 0)
        writeInt(value.size)
        write(value)
    }

    private fun framed(value: String): ByteArray {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        return int32(bytes.size) + bytes
    }

    private fun int32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun humanBundleSha256(exitCode: Int, stdout: ByteArray, stderr: ByteArray): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeFramed(HUMAN_PIN_SCHEMA.toByteArray(StandardCharsets.UTF_8))
            output.writeInt(exitCode)
            output.writeFramed(stdout)
            output.writeFramed(stderr)
        }
        return sha256(bytes.toByteArray())
    }

    private fun parseReplacement(value: String): ByteArray? {
        if (value == "null") return null
        return value.substringBefore(" (").trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun String.nullValue(): String? = takeUnless { it == "null" }
    private fun String.nullableInt(): Int? = takeUnless { it == "null" }?.toInt()

    private fun isCanonicalRelative(path: String): Boolean {
        val parsed = Path.of(path)
        return !parsed.isAbsolute &&
            parsed.normalize().invariantSeparatorsPathString == path &&
            parsed.iterator().asSequence().none { component -> component.toString() == ".." }
    }

    private fun resourceBytes(path: String): ByteArray {
        val stream = assertNotNull(javaClass.classLoader.getResourceAsStream(path), "Missing resource $path")
        return stream.use { it.readBytes() }
    }

    private fun parsePom(path: Path): Element {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            return factory.newDocumentBuilder().parse(input).documentElement
        }
    }

    private fun directChildren(parent: Element, localName: String): List<Element> = buildList {
        val children = parent.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child is Element && (child.localName ?: child.nodeName.substringAfter(':')) == localName) add(child)
        }
    }

    private fun directChildText(parent: Element, localName: String): String? =
        directChildren(parent, localName).singleOrNull()?.textContent?.trim()

    private fun copyNoFollow(source: Path, target: Path) {
        require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS))
        Files.walkFileTree(source, EnumSet.noneOf(FileVisitOption::class.java), Int.MAX_VALUE, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                require(!attrs.isSymbolicLink && !Files.isSymbolicLink(dir)) { "Symbolic-link directory refused: ${source.relativize(dir)}" }
                val destination = target.resolve(source.relativize(dir).toString()).normalize()
                require(destination.startsWith(target))
                Files.createDirectories(destination)
                copyPermissions(dir, destination)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                require(!attrs.isSymbolicLink && !Files.isSymbolicLink(file)) { "Symbolic link refused: ${source.relativize(file)}" }
                require(attrs.isRegularFile) { "Non-regular fixture entry refused: ${source.relativize(file)}" }
                val destination = target.resolve(source.relativize(file).toString()).normalize()
                require(destination.startsWith(target))
                Files.createDirectories(assertNotNull(destination.parent))
                Files.copy(file, destination, StandardCopyOption.COPY_ATTRIBUTES)
                return FileVisitResult.CONTINUE
            }
        })
        assertEquals(captureManifest(source), captureManifest(target))
    }

    private fun copyPermissions(source: Path, target: Path) {
        runCatching { Files.setPosixFilePermissions(target, Files.getPosixFilePermissions(source, LinkOption.NOFOLLOW_LINKS)) }
    }

    private fun captureManifest(
        root: Path,
        excludeEngine: Boolean = false,
        repositoryProtected: Boolean = false,
    ): ExactManifest {
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) { "Manifest root is unavailable: $root" }
        val entries = sortedMapOf<String, ManifestEntry>()
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption::class.java), Int.MAX_VALUE, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val relative = manifestPath(root, dir)
                if (shouldExcludeManifestDirectory(relative, excludeEngine, repositoryProtected)) return FileVisitResult.SKIP_SUBTREE
                require(!attrs.isSymbolicLink && !Files.isSymbolicLink(dir)) { "Manifest refuses symbolic-link directory: $relative" }
                entries[relative] = ManifestEntry("directory", null, null, null, permissions(dir))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val relative = manifestPath(root, file)
                if (repositoryProtected && relative.startsWith("docs/requirements/evidence/")) return FileVisitResult.CONTINUE
                entries[relative] = when {
                    attrs.isSymbolicLink || Files.isSymbolicLink(file) -> ManifestEntry(
                        "symbolic-link", null, null, Files.readSymbolicLink(file).toString(), permissions(file),
                    )
                    attrs.isRegularFile -> {
                        val bytes = readAllBytesNoFollow(file)
                        ManifestEntry("regular-file", bytes.size.toLong(), sha256(bytes), null, permissions(file))
                    }
                    else -> ManifestEntry("other", null, null, null, permissions(file))
                }
                return FileVisitResult.CONTINUE
            }
        })
        return ExactManifest(entries)
    }

    private fun shouldExcludeManifestDirectory(relative: String, excludeEngine: Boolean, repositoryProtected: Boolean): Boolean {
        if (excludeEngine && (relative == ENGINE_DIRECTORY || relative.startsWith("$ENGINE_DIRECTORY/"))) return true
        if (!repositoryProtected || relative == ".") return false
        val parts = relative.split('/')
        if (parts.any { it in setOf(".git", ".gradle", "build") }) return true
        return relative == "docs/requirements/evidence" || relative.startsWith("docs/requirements/evidence/")
    }

    private fun manifestPath(root: Path, path: Path): String =
        root.relativize(path).normalize().invariantSeparatorsPathString.ifBlank { "." }

    private fun permissions(path: Path): String = runCatching {
        Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
            .sortedBy(PosixFilePermission::name).joinToString(",") { it.name }
    }.getOrElse { "unavailable" }

    private fun readAllBytesNoFollow(path: Path): ByteArray =
        Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.readBytes() }

    private fun deleteNoFollow(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption::class.java), Int.MAX_VALUE, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                runCatching {
                    val permissions = Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS).toMutableSet()
                    permissions += PosixFilePermission.OWNER_WRITE
                    Files.setPosixFilePermissions(file, permissions)
                }
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun restoreAndUnlock() {
        try {
            if (this::temporaryRoot.isInitialized) deleteNoFollow(temporaryRoot)
        } finally {
            if (lockHeld) {
                lockHeld = false
                GLOBAL_STREAM_LOCK.unlock()
            }
        }
    }

    private fun locateRepositoryRoot(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath().normalize()
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts")) &&
                Files.isDirectory(candidate.resolve("modules/refactorkit-cli"))
            ) return candidate
            candidate = candidate.parent
        }
        error("Repository root not found")
    }

    private fun codeLocation(type: Class<*>): Path {
        val uri: URI = assertNotNull(type.protectionDomain.codeSource?.location).toURI()
        return Path.of(uri).toAbsolutePath().normalize()
    }

    private fun assertStatusNeutralFeatureIntegrity(path: Path) {
        val featureBytes = readAllBytesNoFollow(path)
        val featureText = featureBytes.toString(StandardCharsets.UTF_8)
        assertContentEquals(
            featureBytes,
            featureText.toByteArray(StandardCharsets.UTF_8),
            "RESULT001 feature must be valid UTF-8 without byte replacement",
        )

        val targetLines = FEATURE_LINE.findAll(featureText).filter { line ->
            FEATURE_TAG.findAll(line.value).count { it.value == RESULT_REQUIREMENT_TAG } > 0
        }.toList()
        assertEquals(1, targetLines.size, "RESULT001 feature must contain exactly one target requirement tag line")
        val targetLine = targetLines.single()
        val tags = FEATURE_TAG.findAll(targetLine.value).toList()
        assertEquals(
            1,
            tags.count { it.value == RESULT_REQUIREMENT_TAG },
            "RESULT001 target line must contain its requirement tag exactly once",
        )

        val statusCandidates = tags.filter { EFFECTIVE_STATUS_CANDIDATE.matches(it.value) }
        val unknownStatuses = statusCandidates.filterNot { it.value in EFFECTIVE_STATUS_TAGS }
        assertTrue(unknownStatuses.isEmpty(), "Unknown RESULT001 effective status tokens: ${unknownStatuses.map { it.value }}")
        val allowedStatuses = tags.filter { it.value in EFFECTIVE_STATUS_TAGS }
        assertEquals(1, allowedStatuses.size, "RESULT001 target line must contain exactly one allowed effective status token")

        val status = allowedStatuses.single()
        val statusStart = targetLine.range.first + status.range.first
        var canonicalText = featureText.replaceRange(
            statusStart,
            statusStart + status.value.length,
            CANONICAL_FEATURE_STATUS,
        )
        // Preserve the original feature digest: only these exact, single metadata spans may evolve.
        val releaseMetadata = mapOf(
            "  This qualification is limited to source-built RefactorKit 0.7.0 on local Linux, JDK 21, and the current CPU architecture.\n" +
                "  V070-RELEASE-VERSION-TRANSITION-001 changes only version metadata, not the preview oracle or release-parity exclusions.\n" to
                "  This qualification is limited to source-built RefactorKit 0.7.0-SNAPSHOT on local Linux, JDK 21, and the current CPU architecture.\n",
            "    Given the source-built RefactorKit 0.7.0 CLI entrypoint runs locally on Linux with JDK 21 and the current CPU architecture\n" to
                "    Given the source-built RefactorKit 0.7.0-SNAPSHOT CLI entrypoint runs locally on Linux with JDK 21 and the current CPU architecture\n",
        )
        releaseMetadata.forEach { (current, historical) ->
            val start = canonicalText.indexOf(current)
            assertTrue(start >= 0 && start == canonicalText.lastIndexOf(current), "Exact release metadata must occur once")
            canonicalText = canonicalText.replaceRange(start, start + current.length, historical)
        }
        assertEquals(
            STATUS_NEUTRAL_FEATURE_SHA256,
            sha256(canonicalText.toByteArray(StandardCharsets.UTF_8)),
            "RESULT001 feature drifted outside its status token and exact approved release-version metadata",
        )
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        val GLOBAL_STREAM_LOCK = ReentrantLock(true)
        val SHA256 = Regex("[0-9a-f]{64}")
        val REQUEST_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        val GENERATED_REQUEST_ID = Regex("request-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        val FEATURE_LINE = Regex("(?m)^.*$")
        val FEATURE_TAG = Regex("(?<!\\S)@[A-Za-z0-9][A-Za-z0-9-]*(?!\\S)")
        val EFFECTIVE_STATUS_CANDIDATE = Regex(
            "@(?:implemented(?:-[A-Za-z0-9]+)*|partial(?:-[A-Za-z0-9]+)*|config(?:-[A-Za-z0-9]+)*|" +
                "placeholder(?:-[A-Za-z0-9]+)*|absent(?:-[A-Za-z0-9]+)*|not(?:-[A-Za-z0-9]+)*)",
        )
        val EFFECTIVE_STATUS_TAGS = setOf(
            "@implemented-and-validated",
            "@implemented-not-e2e-validated",
            "@partial",
            "@config-only",
            "@placeholder",
            "@absent",
            "@not-implemented",
        )

        const val BASE_COMMIT = "8b361e3a4ac9d83ef2b9b4e797a7a4ea569d42dc"
        const val BASELINE_PATH = "docs/requirements/req-java-cli-result-001-baseline.md"
        const val APPROVED_CHANGE_PATH = "docs/requirements/req-java-cli-result-001-approved-change-001.md"
        const val FEATURE_PATH = "features/java-cli-operation-result.feature"
        const val BASELINE_SHA256 = "8f29946c5dcb0128075764fbcd2756156d61edfad4d0fffe2f49afa526c68957"
        const val APPROVED_CHANGE_SHA256 = "7c8555ba5c3f393f1809136a38e86da9419234962864513bd4515001557c250c"
        const val RESULT_REQUIREMENT_TAG = "@REQ-JAVA-CLI-RESULT-001"
        const val CANONICAL_FEATURE_STATUS = "@absent"
        const val STATUS_NEUTRAL_FEATURE_SHA256 = "b6f62831bf38c2fdaadbd02abc024abd16c8818a812678c76a1eb146642e4ed9"
        const val FIXTURE_PATH = "testdata/acceptance/java-maven-move-class-authority-20-modules"
        const val OLD_MODULE = "catalog-model"
        const val NEW_MODULE = "catalog-domain"
        const val PRODUCT_SOURCE = "catalog-model/src/main/java/com/acme/catalog/legacy/Product.java"
        const val ENGINE_DIRECTORY = ".refactorkit"
        const val WORKSPACE_LOCK = ".refactorkit/workspace.lock"
        const val TRANSACTION_DIRECTORY = ".refactorkit/transactions"
        const val PLAN_DIGEST_SCHEMA = "refactorkit.cli-result/plan-sha256/v1"
        const val COMMAND_IDENTITY = "java.renameMavenModule"
        const val FIXED_REQUEST_ID = "result-preview-001"
        const val HUMAN_PIN_SCHEMA = "refactorkit.cli-result/human-pin/v1"
        const val HUMAN_STDOUT_RESOURCE =
            "org/refactorkit/cli/reqjavacliresult001/human-pre-slice-b353a04f5159.stdout"
        const val HUMAN_STDERR_RESOURCE =
            "org/refactorkit/cli/reqjavacliresult001/human-pre-slice-e3b0c44298fc.stderr"
        const val HUMAN_EXIT_RESOURCE =
            "org/refactorkit/cli/reqjavacliresult001/human-pre-slice-exit-9a271f2a916b.txt"
        const val HUMAN_STDOUT_SHA256 = "b353a04f51595e749cbb7a0c7a4e5a63c355bd8182f555e6c01acdb8971a1c4c"
        const val HUMAN_STDERR_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        const val HUMAN_BUNDLE_SHA256 = "2c38357f125f5d20b1ea9b840d2a8eff12e64337a5fe8655afa10cb5777fb31b"
        const val HARNESS_PROTOCOL = "refactorkit.test.cli-result-preview-guard/v1"
        const val PROBE_MODE = "probe"
        const val SCAN_MODE = "scan"
        const val INVOKE_MODE = "invoke"
        const val PROCESS_TIMEOUT_SECONDS = 60L

        val REPLACEMENT_BYTES = "catalog-domain".toByteArray(StandardCharsets.UTF_8)
        val EXPECTED_EDIT_PATHS = listOf(
            "catalog-model/pom.xml",
            "pom.xml",
            "catalog-pricing/pom.xml",
            "catalog-model/pom.xml",
            "catalog-model/src/main/java/com/acme/catalog/legacy/Product.java",
        )
        val EXPECTED_EDIT_DESTINATIONS = listOf(
            null,
            null,
            null,
            "catalog-domain/pom.xml",
            "catalog-domain/src/main/java/com/acme/catalog/legacy/Product.java",
        )
        val NON_DIGEST_SENTINELS = listOf(
            FIXED_REQUEST_ID,
            "PlanId",
            "summary",
            "warning",
            "confidence",
            "risk",
            "diagnostic",
            "authority",
            "timestamp",
            "truncated",
        )
        val EXPECTED_TABLE_INVOCATION_LABELS = listOf(
            "fixed correlation A",
            "fixed correlation B",
            "generated correlation",
            "human compatibility",
        )
        val EXPECTED_INVOCATION_LABELS = listOf(
            "fixed-correlation-a",
            "fixed-correlation-b",
            "generated-correlation",
            "human-compatibility",
        )
        const val JSON_ARGV_TEMPLATE =
            "refactorkit java rename-module --old-module-dir catalog-model --new-module-dir catalog-domain " +
                "--new-artifact-id catalog-domain --root <machine-result-root> --json --request-id result-preview-001"
        const val GENERATED_JSON_ARGV_TEMPLATE =
            "refactorkit java rename-module --old-module-dir catalog-model --new-module-dir catalog-domain " +
                "--new-artifact-id catalog-domain --root <machine-result-root> --json"
        const val HUMAN_ARGV_TEMPLATE =
            "refactorkit java rename-module --old-module-dir catalog-model --new-module-dir catalog-domain " +
                "--new-artifact-id catalog-domain --root <human-compatibility-root>"

        val EXPECTED_TOP_FIELDS = listOf(
            TopField(1, "schemaVersion", "integer", "1"),
            TopField(2, "command", "string", COMMAND_IDENTITY),
            TopField(3, "requestId", "string", "the independently expected invocation correlation value"),
            TopField(4, "outcome", "string", "preview"),
            TopField(5, "plan", "object", "the exact canonical plan projection"),
            TopField(6, "transaction", "null", "null"),
            TopField(7, "diagnostics", "array", "empty"),
            TopField(8, "truncated", "boolean", "false"),
        )
        val EXPECTED_PLAN_FIELDS = listOf(
            PlanField(1, "sha256", "string", "EXPECTED_PLAN_SHA256 as 64 lowercase hexadecimal characters"),
            PlanField(2, "snapshotSha256", "string", "the independently retained S0 snapshot SHA-256"),
            PlanField(3, "requiresApproval", "boolean", "true"),
            PlanField(4, "changeCount", "integer", "5"),
            PlanField(5, "changes", "array", "the complete five-change projection below"),
        )
        val EXPECTED_VISIBLE_CHANGES = listOf(
            VisibleChange(1, "modify", "catalog-model/pom.xml", null),
            VisibleChange(2, "modify", "pom.xml", null),
            VisibleChange(3, "modify", "catalog-pricing/pom.xml", null),
            VisibleChange(4, "move", "catalog-domain/pom.xml", "catalog-model/pom.xml"),
            VisibleChange(
                5,
                "move",
                "catalog-domain/src/main/java/com/acme/catalog/legacy/Product.java",
                "catalog-model/src/main/java/com/acme/catalog/legacy/Product.java",
            ),
        )
        val EXPECTED_EXCLUSIONS = listOf(
            "REQ-JAVA-CLI-RESULT-002 applied transaction correlation",
            "REQ-JAVA-CLI-RESULT-003 application refusal projection",
            "REQ-JAVA-CLI-RESULT-004 usage or expected operational error projection",
            "REQ-JAVA-CLI-RESULT-005 unexpected internal error projection",
            "REQ-JAVA-CLI-RESULT-006 schema closure, limits, redaction, or truncation behavior",
            "invalid request IDs, duplicate arguments, missing values, apply, refusal, or any error outcome",
            "java create-module, java move-across-maven-modules, other Java commands, or any other language",
            "installDist parity, installed execution or promotion, package, installer, signed, or native behavior",
            "Windows, macOS, another CPU architecture, another JDK, or another host",
            "a fresh SURFACE-002 authority, apply, refusal, rollback, crash, recovery, or transaction qualification",
            "release-grade portability, release promotion, broad support-matrix status, or changes to prior promoted rows",
        )
    }
}
