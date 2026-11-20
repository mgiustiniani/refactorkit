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

class CManagedHeaderFieldRenameSteps {
    private val fixture = CManagedHeaderFieldRenameFixture()

    @Given("a captured {string} header field rename has {string} consumer authority")
    fun capture(standard: String, condition: String) = fixture.capture(standard, condition)

    @When("the selected header field rename is previewed and managed Apply is requested")
    fun execute() = fixture.execute()

    @Then("the header field managed lifecycle reports {string} with exact namespace images")
    fun outcome(expected: String) = fixture.assertOutcome(expected)

    @Then("the header field inputs and transaction history retain their expected state")
    fun intact() = fixture.assertIntact()

    @After
    fun close() = fixture.close()
}

private class CManagedHeaderFieldRenameFixture : AutoCloseable {
    private var root: Path? = null
    private var manager: ExternalSemanticProcessManager? = null
    private var externalSdk: Path? = null
    private var sdkMode = false
    private var sdkImage: Map<Path, String> = emptyMap()
    private var toolchainIdentity = ""
    private var facade: CRefactoringFacade? = null
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var condition: String
    private lateinit var database: String
    private lateinit var original: Map<Path, String>
    private lateinit var expected: Map<Path, String>
    private lateinit var plan: PatchPlan
    private lateinit var applied: ApplyResult
    private var rolledBack = false

    private fun material(field: String, macro: Boolean = false) = CHeaderFieldRenameSample.material(field, macro)

    fun capture(standard: String, variant: String) {
        require(standard in setOf("c11", "c17"))
        sdkMode = variant.startsWith("SDK ")
        condition = variant.removePrefix("SDK ")
        require(condition in setOf("matching", "incomplete consumer", "database changed", "forged lease", "macro declaration") ||
            sdkMode && condition in setOf("header changes", "header appears", "header removed"))
        val workspace = Files.createTempDirectory("rk-c-header-field-").also { root = it }
        original = material("raw_count", condition == "macro declaration")
        expected = material("scaled_count")
        if (sdkMode) {
            val sdk = Files.createTempDirectory("rk-c-managed-readonly-sdk-"); externalSdk = sdk
            Files.createDirectories(sdk.resolve("usr/include"))
            Files.writeString(sdk.resolve("usr/include/sdk_types.h"),
                "#pragma once\ntypedef int sdk_count;\nstruct sdk_reading { int raw_count; };\n")
            fun withSdk(image: Map<Path, String>, field: String) = image.mapValues { (path, text) ->
                if (path == Path.of("include/api.h")) "#include \"sdk_types.h\"\n" + text.replace("    int $field;", "    sdk_count $field;") else text
            }
            original = withSdk(original, "raw_count"); expected = withSdk(expected, "scaled_count")
            sdkImage = sdkFiles()
        }
        original.forEach { (file, text) ->
            Files.createDirectories(workspace.resolve(file).parent); Files.writeString(workspace.resolve(file), text)
        }
        val sdk = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        val units = if (condition == "incomplete consumer") listOf("src/read_meter.c") else listOf("src/read_meter.c", "src/main.c")
        database = JsonArray(units.map { file -> buildJsonObject {
            put("directory", workspace.toString()); put("file", file)
            put("arguments", JsonArray((listOf(sdk.resolve("usr/bin/clang-22").toString(), "-std=$standard", "-Iinclude") +
                externalSdk?.let { listOf("--sysroot=$it") }.orEmpty() + listOf("-c", file)).map(::JsonPrimitive)))
        } }).toString()
        Files.writeString(workspace.resolve("compile_commands.json"), database)
        snapshot = CProjectScanner().scan(workspace)
        val toolchain = assertIs<ClangToolchainDiscovery.Available>(ClangToolchainDiscoverer().discover(
            ClangToolchainRequest(workspace, sdk.resolve("usr/bin/clang-22"), sdk.resolve("usr/bin/clangd"), sdk.resolve("usr/bin/clang-format"),
                readOnlySdks = externalSdk?.let { listOf(CReadOnlySdkRequest(it, "fixture:managed-field-sdk", "LicenseRef-RefactorKitFixture")) }.orEmpty()),
        )).toolchain
        assertEquals("31a40dc31f3c15a47aa119aa148f339bf363d8f61202ddbdacbd3f24b71ba113",
            toolchain.provenance.evidence.single { it.role == "clang-executable" }.sha256)
        toolchainIdentity = toolchain.provenance.toolchainIdentity()
        if (sdkMode) {
            assertNotNull(toolchain.provenance.readOnlySdkIdentity)
            assertEquals(toolchain.readOnlySdkInputs.identity, toolchain.provenance.readOnlySdkIdentity)
        }
        val processes = ExternalSemanticProcessManager().also { manager = it }
        facade = CRefactoringFacade(toolchain, processes)
    }

    fun execute() {
        val workspace = requireNotNull(root)
        val adapter = requireNotNull(facade)
        plan = adapter.previewForManagedApply(snapshot, "renameSymbol", mapOf(
            "symbol" to "raw_count", "newName" to "scaled_count", "file" to "include/api.h",
            "line" to if (sdkMode) "2" else if (condition == "macro declaration") "2" else "1",
            "character" to if (sdkMode) "14" else if (condition == "macro declaration") "18" else "8",
        ))
        assertSource(original)
        assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        if (condition == "database changed") Files.writeString(workspace.resolve("compile_commands.json"), database + "\n")
        externalSdk?.let { sdk ->
            when (condition) {
                "header changes" -> Files.writeString(sdk.resolve("usr/include/sdk_types.h"), Files.readString(sdk.resolve("usr/include/sdk_types.h")) + "\n/* changed SDK bytes */\n")
                "header appears" -> Files.writeString(sdk.resolve("usr/include/added.h"), "#pragma once\n")
                "header removed" -> Files.delete(sdk.resolve("usr/include/sdk_types.h"))
            }
            sdkImage = sdkFiles()
        }
        val requested = if (condition == "forged lease") {
            val lease = plan.authorityLease
            if (lease == null) plan else plan.copy(authorityLease = OperationAuthorityLease(
                kind = lease.kind, operation = lease.operation, snapshotHash = lease.snapshotHash,
                evidenceHash = lease.evidenceHash, evidenceCompleteness = lease.evidenceCompleteness,
                workspaceEditSha256 = lease.workspaceEditSha256, requiredFileEvidence = lease.requiredFileEvidence,
                requiredClasspathEvidence = lease.requiredClasspathEvidence,
                attributes = lease.attributes + if (sdkMode) mapOf("c.toolchainIdentity" to "0".repeat(64))
                    else mapOf("c.headerField.selectedName" to "calibration"),
            ))
        } else plan
        applied = adapter.apply(snapshot, requested, ApplyAuthorization.explicit("c06-header-field-story"),
            DiagnosticsGate.disabled("caller-cannot-authorize-header-field-rename"))
    }

    fun assertOutcome(outcome: String) {
        val workspace = requireNotNull(root)
        if (condition !in setOf("incomplete consumer", "macro declaration")) {
            assertEquals(PatchStatus.PREVIEW, plan.status, plan.toString())
            assertEquals(RefactoringEvidence.COMPILER_PROVEN, plan.evidence)
            assertTrue(plan.requiresUserApproval)
            val lease = assertNotNull(plan.authorityLease, "Complete header field family authority is absent")
            assertEquals("c.rename-symbol-header-field-v1", lease.kind)
            assertEquals("2", lease.attributes["c.headerField.translationUnitCount"])
            assertEquals("3", lease.attributes["c.headerField.physicalSiteCount"])
            if (sdkMode) {
                assertEquals(toolchainIdentity, lease.attributes["c.toolchainIdentity"])
                assertTrue(plan.affectedFiles.all { !it.isAbsolute && !it.startsWith("..") })
            }
            assertTrue(plan.diagnosticsBefore.none { it.severity == Diagnostic.Severity.ERROR })
            assertTrue(plan.diagnosticsAfterPreview.none { it.severity == Diagnostic.Severity.ERROR })
        }
        if (outcome == "applied and rolled back") {
            assertEquals("matching", condition)
            val transaction = assertIs<ApplyResult.Applied>(applied, applied.toString()).transaction
            assertSource(expected)
            assertEquals(database, Files.readString(workspace.resolve("compile_commands.json")))
            assertIs<ApplyResult.Applied>(PatchEngine(workspace).rollback(transaction))
            rolledBack = true
            assertEquals(JournalState.ROLLED_BACK, assertNotNull(TransactionLog(workspace.resolve(".refactorkit/transactions"))
                .loadRecord(transaction.id)).state)
            assertEquals(snapshot.hash, CProjectScanner().scan(workspace).hash)
        } else {
            assertEquals("refused", outcome)
            assertIs<ApplyResult.Refused>(applied)
            if (condition == "incomplete consumer") assertNull(plan.authorityLease)
            if (condition == "macro declaration") {
                assertEquals(PatchStatus.REFUSED, plan.status, plan.toString())
                assertEquals("c.renameMacroGeneratedSymbolUnsupported", plan.refusalCode)
            }
            assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        }
    }

    fun assertIntact() {
        val workspace = requireNotNull(root)
        assertSource(original)
        assertEquals(database + if (condition == "database changed") "\n" else "", Files.readString(workspace.resolve("compile_commands.json")))
        assertEquals(rolledBack, Files.exists(workspace.resolve(".refactorkit/transactions")))
        if (sdkMode) assertEquals(sdkImage, sdkFiles())
        Files.walk(workspace).use { paths -> assertTrue(paths.noneMatch { it.fileName.toString().endsWith(".o") }) }
        facade?.close()
        assertTrue(requireNotNull(manager).activeProvenance().isEmpty())
    }
    private fun assertSource(image: Map<Path, String>) = image.forEach { (path, text) ->
        assertEquals(text, Files.readString(requireNotNull(root).resolve(path)), path.toString())
    }
    private fun sdkFiles(): Map<Path, String> = externalSdk?.let { sdk -> Files.walk(sdk).use { paths ->
        paths.filter { Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }.toList()
            .associate { sdk.relativize(it) to Files.readString(it) }
    } }.orEmpty()
    override fun close() {
        facade?.close(); manager?.close()
        externalSdk?.let { sdk -> Files.walk(sdk).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
        root?.let { workspace -> Files.walk(workspace).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
}
