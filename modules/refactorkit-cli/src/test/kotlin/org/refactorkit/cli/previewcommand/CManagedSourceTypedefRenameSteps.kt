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
import java.util.zip.GZIPInputStream
import kotlin.test.*

class CManagedSourceTypedefRenameSteps {
    private val fixture = ManagedSourceTypedefRenameFixture()
    @Given("a captured {string} source typedef rename has {string} shape and {string} authority")
    fun capture(standard: String, shape: String, condition: String) = fixture.capture(standard, shape, condition)
    @When("the source typedef rename is previewed and managed Apply is requested")
    fun execute() = fixture.execute()
    @Then("the source typedef lifecycle reports {string} with exact type and preserved namespace images")
    fun outcome(expected: String) = fixture.assertOutcome(expected)
    @Then("the source typedef inputs and transaction history retain their expected state")
    fun intact() = fixture.assertIntact()
    @After
    fun close() = fixture.close()
}

private class ManagedSourceTypedefRenameFixture : AutoCloseable {
    private var root: Path? = null
    private var processes: ExternalSemanticProcessManager? = null
    private var facade: CRefactoringFacade? = null
    private lateinit var original: Map<Path, String>
    private lateinit var expected: Map<Path, String>
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var database: String
    private lateinit var condition: String
    private lateinit var plan: PatchPlan
    private lateinit var applied: ApplyResult
    private var offset = -1
    private var rolledBack = false
    private val selectedFile = Path.of("src/api.c")

    fun capture(standard: String, shape: String, condition: String) {
        require(standard in setOf("c11", "c17") && shape in setOf("scalar", "pointer", "chain"))
        require(condition in setOf("matching", "ordinary collision", "missing TU", "shadow alias", "macro declaration", "database changed", "forged lease", "source changed"))
        this.condition = condition
        val material = requireNotNull(CManagedSourceTypedefRenameSteps::class.java
            .getResourceAsStream("/source-typedef-rename/native-fixture.json.gz")).use { stream ->
            GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject }
        }.getValue("modes").jsonObject.getValue(standard).jsonObject.getValue(shape).jsonObject
        fun image(phase: String) = material.getValue(phase).jsonObject.getValue("sources").jsonObject
            .map { (path, text) -> Path.of(path) to text.jsonPrimitive.content }.toMap()
        original = image("before"); expected = image("after")
        fun aliases(node: JsonObject): List<JsonObject> = listOfNotNull(node.takeIf {
            it["kind"]?.jsonPrimitive?.content == "TypedefDecl" && it["name"]?.jsonPrimitive?.content == "scalar_t"
        }) + node["inner"]?.jsonArray.orEmpty().flatMap { aliases(it.jsonObject) }
        val ast = material.getValue("before").jsonObject.getValue("asts").jsonObject.getValue("src/api.c").jsonObject
        offset = aliases(ast).single().getValue("loc").jsonObject.getValue("offset").jsonPrimitive.int
        if (condition == "shadow alias") original = original + (selectedFile to
            (original.getValue(selectedFile) + "static int hidden(void) { typedef long scalar_t; scalar_t local = 1; return (int)local; }\n"))
        if (condition == "macro declaration") {
            val source = original.getValue(selectedFile).replaceFirst("typedef int scalar_t;",
                "#define DECLARE_ALIAS(name) typedef int name\nDECLARE_ALIAS(scalar_t);")
            offset = source.indexOf("DECLARE_ALIAS(scalar_t)").also { require(it >= 0) } + "DECLARE_ALIAS(".length
            original = original + (selectedFile to source)
        }
        assertEquals("scalar_t", original.getValue(selectedFile).substring(offset, offset + "scalar_t".length))
        val workspace = Files.createTempDirectory("rk-c-source-typedef-").also { root = it }
        original.forEach { (file, text) -> Files.createDirectories(workspace.resolve(file).parent); Files.writeString(workspace.resolve(file), text) }
        val sdk = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        database = JsonArray(listOf("src/api.c", "src/main.c").map { file -> buildJsonObject {
            put("directory", workspace.toString()); put("file", file)
            put("arguments", JsonArray(listOf(sdk.resolve("usr/bin/clang-22").toString(), "-std=$standard", "-Iinclude", "-c", file).map(::JsonPrimitive)))
        } }.let { rows -> if (condition == "missing TU") rows.take(1) else rows }).toString()
        Files.writeString(workspace.resolve("compile_commands.json"), database)
        snapshot = CProjectScanner().scan(workspace)
        val toolchain = assertIs<ClangToolchainDiscovery.Available>(ClangToolchainDiscoverer().discover(ClangToolchainRequest(
            workspace, sdk.resolve("usr/bin/clang-22"), sdk.resolve("usr/bin/clangd"), sdk.resolve("usr/bin/clang-format")))).toolchain
        assertEquals("31a40dc31f3c15a47aa119aa148f339bf363d8f61202ddbdacbd3f24b71ba113",
            toolchain.provenance.evidence.single { it.role == "clang-executable" }.sha256)
        facade = CRefactoringFacade(toolchain, ExternalSemanticProcessManager().also { processes = it })
    }

    fun execute() {
        val position = TextEdits.positionForOffset(original.getValue(selectedFile), offset)
        val adapter = requireNotNull(facade)
        plan = adapter.previewForManagedApply(snapshot, "renameSymbol", mapOf("symbol" to "scalar_t",
            "newName" to if (condition == "ordinary collision") "read_value" else "quantity_t", "file" to selectedFile.toString(),
            "line" to position.line.toString(), "character" to position.character.toString()))
        assertSource(original)
        assertFalse(Files.exists(requireNotNull(root).resolve(".refactorkit/transactions")))
        if (plan.status == PatchStatus.PREVIEW && condition in setOf("matching", "database changed", "forged lease", "source changed")) {
            assertEquals(expected, WorkspaceEditSimulator.apply(snapshot, plan.workspaceEdit).files.associate { it.path to it.content },
                "Preview must retain the native unrelated TU and same-spelled member/designator images")
        }
        if (condition == "database changed") Files.writeString(requireNotNull(root).resolve("compile_commands.json"), database + "\n")
        if (condition == "source changed") Files.writeString(requireNotNull(root).resolve("src/main.c"),
            original.getValue(Path.of("src/main.c")) + "// caller edit\n")
        val requested = if (condition == "forged lease") {
            val lease = assertNotNull(plan.authorityLease, "A clean source typedef lease is required before forgery")
            plan.copy(authorityLease = OperationAuthorityLease(kind = lease.kind, operation = lease.operation,
                snapshotHash = lease.snapshotHash, evidenceHash = lease.evidenceHash, evidenceCompleteness = lease.evidenceCompleteness,
                workspaceEditSha256 = lease.workspaceEditSha256, requiredFileEvidence = lease.requiredFileEvidence,
                requiredClasspathEvidence = lease.requiredClasspathEvidence,
                attributes = lease.attributes + ("c.typedef.underlyingCanonical" to "double")))
        } else plan
        applied = adapter.apply(snapshot, requested, ApplyAuthorization.explicit("c06-source-typedef-story"),
            DiagnosticsGate.disabled("caller-cannot-authorize-source-typedef-rename"))
    }

    fun assertOutcome(outcome: String) {
        if (condition != "matching") {
            if (condition in setOf("database changed", "forged lease", "source changed")) {
                assertEquals(when (condition) { "database changed" -> "changed input refused"
                    "forged lease" -> "forged lease refused"; "source changed" -> "changed source refused"
                    else -> error("Unknown changed source condition: $condition") }, outcome)
                assertEquals(PatchStatus.PREVIEW, plan.status, plan.toString())
                assertEquals(RefactoringEvidence.COMPILER_PROVEN, plan.evidence)
                assertEquals("c.rename-symbol-typedef-v1", assertNotNull(plan.authorityLease).kind)
            } else {
                assertEquals("refused", outcome)
                assertEquals(PatchStatus.REFUSED, plan.status, plan.toString())
                assertFalse(plan.requiresUserApproval)
                assertTrue(plan.affectedFiles.isEmpty())
                assertTrue(plan.workspaceEdit.edits.isEmpty())
                assertNull(plan.authorityLease)
                val code = assertNotNull(plan.refusalCode)
                if (condition == "macro declaration") {
                    if (code == "c.typedefFacts.sourceUnsupported") assertRefusalDiagnostic(code)
                    else {
                        assertTrue(code in setOf("c.renameMacroGeneratedSymbolUnsupported", "clangd.refused"), code)
                        assertEquals(RefactoringEvidence.STRUCTURAL, plan.evidence)
                    }
                } else {
                    assertEquals(when (condition) { "ordinary collision" -> "c.renameTypedefTargetPresent"
                        "missing TU" -> "c.typedefFacts.incompleteTUs"
                        "shadow alias" -> "c.typedefFacts.ambiguous_type_alias"
                        else -> error("Unknown source refusal: $condition") }, code)
                    assertRefusalDiagnostic(code)
                }
            }
            assertIs<ApplyResult.Refused>(applied)
            assertSource(currentInputImage())
            assertFalse(Files.exists(requireNotNull(root).resolve(".refactorkit/transactions")))
            return
        }
        assertEquals("applied and rolled back", outcome)
        assertEquals(PatchStatus.PREVIEW, plan.status, plan.toString())
        assertEquals(RefactoringEvidence.COMPILER_PROVEN, plan.evidence)
        assertEquals(setOf(selectedFile), plan.affectedFiles)
        val lease = assertNotNull(plan.authorityLease, "Native source typedef mutation authority is absent")
        assertEquals("c.rename-symbol-typedef-v1", lease.kind)
        assertEquals("2", lease.attributes["c.typedef.translationUnitCount"])
        assertEquals("2", lease.attributes["c.typedef.physicalSiteCount"])
        assertEquals("iso-pedantic-errors-v1", lease.attributes["c.typedef.validationPolicy"])
        assertTrue(lease.requiredFileEvidence.isNotEmpty())
        assertTrue(plan.diagnosticsBefore.none { it.severity == Diagnostic.Severity.ERROR })
        assertTrue(plan.diagnosticsAfterPreview.none { it.severity == Diagnostic.Severity.ERROR })
        val workspace = requireNotNull(root)
        val transaction = assertIs<ApplyResult.Applied>(applied, applied.toString()).transaction
        assertSource(expected)
        assertEquals(database, Files.readString(workspace.resolve("compile_commands.json")))
        assertIs<ApplyResult.Applied>(PatchEngine(workspace).rollback(transaction)); rolledBack = true
        assertEquals(JournalState.ROLLED_BACK, assertNotNull(TransactionLog(workspace.resolve(".refactorkit/transactions"))
            .loadRecord(transaction.id)).state)
        assertEquals(snapshot.hash, CProjectScanner().scan(workspace).hash)
    }

    fun assertIntact() {
        assertEquals(condition == "matching", rolledBack)
        assertSource(currentInputImage())
        assertEquals(database + if (condition == "database changed") "\n" else "", Files.readString(requireNotNull(root).resolve("compile_commands.json")))
        assertEquals(rolledBack, Files.exists(requireNotNull(root).resolve(".refactorkit/transactions")))
    }
    private fun currentInputImage(): Map<Path, String> = if (condition == "source changed")
        original + (Path.of("src/main.c") to (original.getValue(Path.of("src/main.c")) + "// caller edit\n"))
        else original
    private fun assertRefusalDiagnostic(code: String) {
        val diagnostic = plan.diagnosticsAfterPreview.single { it.code == code }
        assertEquals(Diagnostic.Severity.ERROR, diagnostic.severity)
        assertEquals(DiagnosticCategory.SAFETY, diagnostic.category)
        assertEquals(if (diagnostic.evidence == DiagnosticEvidence.COMPILER) RefactoringEvidence.COMPILER_PROVEN
            else RefactoringEvidence.STRUCTURAL, plan.evidence)
    }
    private fun assertSource(image: Map<Path, String>) = image.forEach { (file, text) ->
        assertEquals(text, Files.readString(requireNotNull(root).resolve(file)), file.toString())
    }
    override fun close() {
        facade?.close(); processes?.close()
        root?.let { directory -> Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
}
