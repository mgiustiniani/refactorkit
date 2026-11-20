package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.*
import org.refactorkit.c.*
import org.refactorkit.core.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.*

class CCMakeIncludeManagedSteps {
    private val fixture = CCMakeIncludeManagedFixture()
    @Given("a captured {string} CMake {string} move has {string} include roots and {string} inputs")
    fun capture(standard: String, operation: String, visibility: String, condition: String) = fixture.capture(standard, operation, visibility, condition)
    @When("the include-aware CMake lifecycle requests preview and approved managed Apply")
    fun execute() = fixture.execute()
    @Then("the include-aware CMake lifecycle reports {string} with exact source header and target images")
    fun outcome(expected: String) = fixture.assertOutcome(expected)
    @Then("the include-aware CMake inputs and transaction history retain their expected state")
    fun intact() = fixture.assertIntact()
    @After
    fun close() = fixture.close()
}

private class CCMakeIncludeManagedFixture : AutoCloseable {
    private var root: Path? = null
    private var manager: ExternalSemanticProcessManager? = null
    private var facade: CRefactoringFacade? = null
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var mode: String
    private lateinit var visibility: String
    private lateinit var condition: String
    private lateinit var original: Map<Path, String>
    private lateinit var expected: Map<Path, String>
    private lateinit var database: String
    private lateinit var expectedDatabase: String
    private lateinit var plan: PatchPlan
    private lateinit var applied: ApplyResult
    private var rolledBack = false

    private val validPrefixVariants = setOf("explicit API mapping", "API database changed", "forwarding header with mapping")
    private val invalidPrefixVariants = setOf("missing API declaration", "cyclic API mapping")
    private val validForwardingVariants = setOf("requested forwarding header", "forwarding header with mapping", "forwarding database changed")
    private val invalidForwardingVariants = setOf("unknown forwarding header", "source as forwarding header")
    private val databaseDrift get() = condition in setOf("database changed", "API database changed", "forwarding database changed")

    private fun material(renamed: Boolean) = CCMakeIncludeMoveSample.material(
        mode, visibility, renamed,
        valueName = if (renamed && condition in validPrefixVariants) "library_value" else "api_value",
    )

    fun capture(standard: String, operation: String, access: String, variant: String) {
        require(standard in setOf("c11", "c17") && operation in setOf("component", "source", "header"))
        require(access in setOf("PRIVATE", "PUBLIC") && variant in setOf("matching", "missing flag", "database changed", "source changed", "forged lease", "shadow header", "missing TU", "unsafe flag", "pretty database") + validPrefixVariants + invalidPrefixVariants + validForwardingVariants + invalidForwardingVariants)
        mode = operation; visibility = access; condition = variant
        val workspace = Files.createTempDirectory("rk-cmake-include-managed-").also { root = it }
        original = material(false); expected = material(true)
        if (variant in validForwardingVariants) {
            expected = expected + (Path.of("component/include/api.h") to "#include \"../../library/include/api.h\"\n")
        }
        original.forEach { (path, text) -> Files.createDirectories(workspace.resolve(path).parent); Files.writeString(workspace.resolve(path), text) }
        val sdk = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        fun compilationDatabase(renamed: Boolean): String {
            val directory = if (renamed && mode == "component") "library" else "component"
            val first = if (renamed && mode == "source") "$directory/src/deep/api.c" else "$directory/src/api.c"
            return JsonArray(listOf(first, "$directory/src/use.c").map { source -> buildJsonObject {
                val sourceText = if (variant == "pretty database") "./$source" else source
                val rootText = if (variant == "pretty database") "./$directory/include" else "$directory/include"
                put("directory", workspace.toString()); put("file", sourceText)
                put("arguments", JsonArray((listOf(sdk.resolve("usr/bin/clang-22").toString(), "-std=$standard") +
                    (if (variant == "missing flag") emptyList() else listOf("-I", rootText)) +
                    (if (variant == "unsafe flag") listOf("-fplugin=${workspace.resolve("must-not-load.so")}") else emptyList()) +
                    listOf("-c", sourceText)).map(::JsonPrimitive)))
            } }.let { if (variant == "missing TU") it.take(1) else it }).let {
                if (variant == "pretty database") Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), it) else it.toString()
            }
        }
        database = compilationDatabase(false); expectedDatabase = compilationDatabase(true)
        Files.writeString(workspace.resolve("compile_commands.json"), database)
        snapshot = CProjectScanner().scan(workspace)
        val toolchain = assertIs<ClangToolchainDiscovery.Available>(ClangToolchainDiscoverer().discover(ClangToolchainRequest(
            workspace, sdk.resolve("usr/bin/clang-22"), sdk.resolve("usr/bin/clangd"), sdk.resolve("usr/bin/clang-format")))).toolchain
        assertEquals("31a40dc31f3c15a47aa119aa148f339bf363d8f61202ddbdacbd3f24b71ba113",
            toolchain.provenance.evidence.single { it.role == "clang-executable" }.sha256)
        facade = CRefactoringFacade(toolchain, ExternalSemanticProcessManager().also { manager = it })
    }

    fun execute() {
        val operation = if (mode == "component") "relocateComponent" else "moveSource"
        val args = when (mode) {
            "component" -> mapOf("componentDir" to "component", "newDir" to "library")
            "source" -> mapOf("file" to "component/src/api.c", "target" to "component/src/deep/api.c")
            else -> mapOf("file" to "component/include/api.h", "target" to "component/include/public/api.h")
        }
        val mapping = when (condition) {
            in validPrefixVariants -> mapOf("rename.api_value" to "library_value")
            "missing API declaration" -> mapOf("rename.api_missing" to "library_missing")
            "cyclic API mapping" -> mapOf("rename.api_value" to "api_use", "rename.api_use" to "api_value")
            else -> emptyMap()
        }
        val forwarding = when (condition) {
            in validForwardingVariants -> mapOf("forwardHeaders" to "component/include/api.h")
            "unknown forwarding header" -> mapOf("forwardHeaders" to "component/include/unknown.h")
            "source as forwarding header" -> mapOf("forwardHeaders" to "component/src/api.c")
            else -> emptyMap()
        }
        val adapter = requireNotNull(facade)
        plan = adapter.previewForManagedApply(snapshot, operation, args + mapping + forwarding)
        assertImage(original)
        assertFalse(Files.exists(requireNotNull(root).resolve(".refactorkit/transactions")))
        if (databaseDrift || condition in setOf("source changed", "shadow header", "forged lease")) {
            assertNotNull(plan.authorityLease, "A genuine clean preview lease is required before input drift or forgery")
        }
        if (databaseDrift) Files.writeString(requireNotNull(root).resolve("compile_commands.json"), database + "\n")
        if (condition == "source changed") Files.writeString(requireNotNull(root).resolve("component/src/use.c"),
            original.getValue(Path.of("component/src/use.c")) + "// caller edit\n")
        if (condition == "shadow header") Files.writeString(requireNotNull(root).resolve("component/src/api.h"), "int api_value(void);\n")
        if (condition !in (setOf("missing flag", "missing TU", "unsafe flag") + invalidPrefixVariants + invalidForwardingVariants) && plan.authorityLease != null) {
            val staged = WorkspaceEditSimulator.apply(snapshot, plan.workspaceEdit)
            assertEquals(expected, staged.trackedFiles.filter { it.languageId in setOf("c", "cmake") }.associate { it.path to it.content })
            assertEquals(expectedDatabase, staged.auxiliaryFiles.single { it.path == Path.of("compile_commands.json") }.content)
        }
        val requested = if (condition == "forged lease") {
            val lease = assertNotNull(plan.authorityLease)
            plan.copy(authorityLease = OperationAuthorityLease(kind = lease.kind, operation = lease.operation,
                snapshotHash = lease.snapshotHash, evidenceHash = lease.evidenceHash, evidenceCompleteness = lease.evidenceCompleteness,
                workspaceEditSha256 = lease.workspaceEditSha256, requiredFileEvidence = lease.requiredFileEvidence,
                requiredClasspathEvidence = lease.requiredClasspathEvidence,
                attributes = lease.attributes + ("c.cmakeInclude.bindingSha256" to "0".repeat(64))))
        } else plan
        applied = adapter.apply(snapshot, requested, ApplyAuthorization.explicit("c08-c15-owned-include-story"),
            DiagnosticsGate.disabled("caller-cannot-authorize-include-relocation"))
    }

    fun assertOutcome(outcome: String) {
        val workspace = requireNotNull(root)
        if (condition in invalidPrefixVariants || condition in invalidForwardingVariants) {
            assertEquals("refused", outcome)
            assertEquals(PatchStatus.REFUSED, plan.status, plan.toString())
            assertEquals(if (condition in invalidForwardingVariants) "c.cmakeInclude.forwardingAuthorityUnavailable"
                else "c.cmakeInclude.prefixAuthorityUnavailable", plan.refusalCode)
            assertTrue(plan.workspaceEdit.edits.isEmpty())
            assertNull(plan.authorityLease)
            assertIs<ApplyResult.Refused>(applied)
            assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
            return
        }
        if (condition in setOf("missing flag", "missing TU", "unsafe flag")) {
            assertEquals("refused", outcome); assertNull(plan.authorityLease); assertIs<ApplyResult.Refused>(applied)
            assertTrue(plan.status in setOf(PatchStatus.PREVIEW, PatchStatus.REFUSED))
            assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
            return
        }
        assertEquals(PatchStatus.PREVIEW, plan.status, plan.toString())
        assertEquals(RefactoringEvidence.COMPILER_PROVEN, plan.evidence)
        val lease = assertNotNull(plan.authorityLease, "No independently owned include relocation authority")
        assertEquals("c.cmake-include-relocation-v1", lease.kind)
        assertEquals("2", lease.attributes["c.cmakeInclude.translationUnitCount"])
        assertEquals(if (mode == "header") "2" else "0", lease.attributes["c.cmakeInclude.includeEditCount"])
        assertEquals("quoted-iso-v1", lease.attributes["c.cmakeInclude.sourcePolicy"])
        if (condition in validPrefixVariants) {
            assertEquals("api_value=library_value", lease.attributes["c.cmakeInclude.prefixMapping"])
            assertTrue(lease.attributes.getValue("c.cmakeInclude.prefixEditSha256").matches(Regex("[a-f0-9]{64}")))
        }
        assertTrue(plan.diagnosticsBefore.none { it.severity == Diagnostic.Severity.ERROR })
        assertTrue(plan.diagnosticsAfterPreview.none { it.severity == Diagnostic.Severity.ERROR })
        if (databaseDrift || condition in setOf("source changed", "shadow header", "forged lease")) {
            assertEquals("refused", outcome); assertIs<ApplyResult.Refused>(applied)
            assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        } else {
            assertEquals("applied and rolled back", outcome)
            val transaction = assertIs<ApplyResult.Applied>(applied, applied.toString()).transaction
            assertImage(expected)
            assertEquals(expectedDatabase, Files.readString(workspace.resolve("compile_commands.json")))
            assertIs<ApplyResult.Applied>(PatchEngine(workspace).rollback(transaction)); rolledBack = true
            assertEquals(JournalState.ROLLED_BACK, assertNotNull(TransactionLog(workspace.resolve(".refactorkit/transactions")).loadRecord(transaction.id)).state)
            assertEquals(snapshot.hash, CProjectScanner().scan(workspace).hash)
        }
    }

    fun assertIntact() {
        val callerImage = when (condition) {
            "source changed" -> original + (Path.of("component/src/use.c") to (original.getValue(Path.of("component/src/use.c")) + "// caller edit\n"))
            "shadow header" -> original + (Path.of("component/src/api.h") to "int api_value(void);\n")
            else -> original
        }
        assertImage(callerImage)
        assertFalse(Files.exists(requireNotNull(root).resolve("must-not-load.so")))
        assertEquals(database + if (databaseDrift) "\n" else "", Files.readString(requireNotNull(root).resolve("compile_commands.json")))
        assertEquals(rolledBack, Files.exists(requireNotNull(root).resolve(".refactorkit/transactions")))
    }
    private fun assertImage(image: Map<Path, String>) {
        val workspace = requireNotNull(root)
        val actual = CProjectScanner().scan(workspace)
        assertEquals(image, actual.trackedFiles.filter { it.languageId in setOf("c", "cmake") }.associate { it.path to it.content })
    }
    override fun close() {
        facade?.close(); manager?.close()
        root?.let { path -> Files.walk(path).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
}
