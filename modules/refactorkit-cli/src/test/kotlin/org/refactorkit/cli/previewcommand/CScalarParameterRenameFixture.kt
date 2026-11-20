package org.refactorkit.cli.previewcommand

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.c.CProjectScanner
import org.refactorkit.c.CRefactoringFacade
import org.refactorkit.c.CSignaturePlanner
import org.refactorkit.c.ClangToolchainDiscoverer
import org.refactorkit.c.ClangToolchainDiscovery
import org.refactorkit.c.ClangToolchainRequest
import org.refactorkit.cli.RefactorKitCli
import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.ApprovalKind
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.JournalState
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.TransactionLog
import org.refactorkit.core.WorkspaceEditSimulator
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Authored scalar fixtures, real CLI preview, compiler diagnostics and managed exact rollback. */
internal class CScalarParameterRenameFixture private constructor(
    private val owned: Path,
    private val type: String,
    private val dialect: String,
) : AutoCloseable {
    private val workspace = Files.createDirectory(owned.resolve("workspace"))
    private val reference = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
    private val compiler = reference.resolve("usr/bin/clang-22")
    private val clangd = reference.resolve("usr/bin/clangd")
    private val formatter = reference.resolve("usr/bin/clang-format")
    private val rightType = if (type == "long") "int" else "long"
    private val call = if (type == "long") "1L, 2" else "1.5, 2L"
    private val result = if (type == "long") "12L" else "17.0"
    private fun image(name: String) = "int required[REQUIRED - 16];\n" +
        "static $type weighted($type $name, $rightType right);\n" +
        "static $type weighted($type $name, $rightType right) { return $name * 10 + right; }\n" +
        "static int unrelated(int amount) { return amount; }\n" +
        "int main(void) { return weighted($call) == $result ? 0 : 1; }\n"
    private val original = image("amount")
    private val expected = image("total")
    private val source = workspace.resolve("main.c")
    private val database = workspace.resolve("compile_commands.json")
    private val toolHashes = listOf(compiler, clangd, formatter).associateWith(::sha)
    private val manager = ExternalSemanticProcessManager()
    private lateinit var databaseBytes: String
    private var beforeHash = ""
    private var previewSource = ""
    private var appliedSource = ""
    private val oracleResults = mutableListOf<Int>()
    private var cliResponse = ""
    private var cliExit = -1

    init {
        Files.writeString(source, original)
        databaseBytes = JsonArray(listOf(buildJsonObject {
            put("directory", workspace.toString()); put("file", "main.c")
            put("arguments", JsonArray(listOf(compiler.toString(), "-std=$dialect", "-DREQUIRED=17", "-c", "main.c").map(::JsonPrimitive)))
        })).toString()
        Files.writeString(database, databaseBytes)
    }

    fun execute() {
        val before = CProjectScanner().scan(workspace)
        beforeHash = before.hash
        oracleResults += oracle("before")
        val stdout = System.out; val stderr = System.err
        val bytes = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(bytes, true, Charsets.UTF_8)); System.setErr(PrintStream(bytes, true, Charsets.UTF_8))
            cliExit = RefactorKitCli().run(listOf("c", "change-signature", workspace.toString(),
                "--operation", "rename-parameter", "--file", "main.c", "--symbol", "weighted",
                "--old-param", "amount", "--new-param", "total", "--preview",
                "--clang", compiler.toString(), "--clangd", clangd.toString(), "--clang-format", formatter.toString()))
        } finally { System.setOut(stdout); System.setErr(stderr) }
        cliResponse = bytes.toString(Charsets.UTF_8)
        assertEquals(0, cliExit, cliResponse)
        assertEquals(original, Files.readString(source))
        assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")), "CLI preview must not start a transaction")
        val toolchain = assertIs<ClangToolchainDiscovery.Available>(ClangToolchainDiscoverer().discover(
            ClangToolchainRequest(workspace, compiler, clangd, formatter))).toolchain
        assertEquals("22.1.8", toolchain.provenance.clangVersion)
        val plan = CSignaturePlanner(toolchain, manager).use { planner ->
            planner.start(before)
            planner.renameParameter(before, Path.of("main.c"), "weighted", "amount", "total")
        }
        assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
        assertEquals(RefactoringEvidence.COMPILER_PROVEN, plan.evidence)
        assertTrue(plan.requiresUserApproval)
        val staged = WorkspaceEditSimulator.apply(before, plan.workspaceEdit)
        previewSource = staged.files.single().content
        assertEquals(expected, previewSource)
        CRefactoringFacade(toolchain, manager).use { facade ->
            val gate = facade.diagnosticsGate(before, plan)
            assertEquals(emptyList(), assertNotNull(gate.provider).invoke(before))
            assertEquals(emptyList(), assertNotNull(gate.provider).invoke(staged))
            val applied = assertIs<ApplyResult.Applied>(facade.apply(before, plan,
                ApplyAuthorization.explicit("c-scalar-rename-story", "bdd-caller"), DiagnosticsGate.disabled("caller-disabled")))
            assertEquals(ApprovalKind.EXPLICIT_APPLY, applied.transaction.approval.kind)
            assertEquals("c-scalar-rename-story", applied.transaction.approval.surface)
            appliedSource = Files.readString(source)
            assertEquals(expected, appliedSource)
            oracleResults += oracle("applied")
            assertIs<ApplyResult.Applied>(PatchEngine(workspace).rollback(applied.transaction))
            assertEquals(original, Files.readString(source))
            assertEquals(emptyList(), assertNotNull(gate.provider).invoke(CProjectScanner().scan(workspace)))
            oracleResults += oracle("rolled-back")
        }
    }

    fun verifyBehavior() {
        assertEquals(expected, previewSource); assertEquals(expected, appliedSource)
        assertEquals(listOf(0, 0, 0), oracleResults, "Native before/apply/rollback programs must retain the same result")
        val response = Json.parseToJsonElement(cliResponse).jsonObject
        assertEquals("PREVIEW", response.getValue("status").jsonPrimitive.content)
        assertEquals("COMPILER_PROVEN", response.getValue("evidence").jsonPrimitive.content)
        assertEquals("changeSignature", response.getValue("operation").jsonPrimitive.content)
        assertEquals(1, response.getValue("affectedFiles").jsonArray.size)
        val diff = response.getValue("renderedDiff").jsonPrimitive.content
        assertTrue(diff.contains("+static $type weighted($type total, $rightType right);"), diff)
        assertTrue(diff.contains("return total * 10 + right;"), diff)
        assertFalse(diff.contains("+static int unrelated(int total)"), diff)
    }

    fun verifyInputs() {
        assertEquals(original, Files.readString(source)); assertEquals(databaseBytes, Files.readString(database))
        assertEquals(beforeHash, CProjectScanner().scan(workspace).hash)
        assertEquals(toolHashes, toolHashes.keys.associateWith(::sha))
        assertTrue(manager.activeProvenance().isEmpty(), "Every native semantic process must be closed")
        val records = TransactionLog(workspace.resolve(".refactorkit/transactions")).listRecordsReadOnly()
        assertEquals(1, records.size)
        assertEquals(JournalState.ROLLED_BACK, records.single().state)
        assertEquals("changeSignature", records.single().operation)
        assertFalse(Files.exists(workspace.resolve("main.o")))
    }

    private fun oracle(label: String): Int {
        val executable = owned.resolve("$label-program")
        val log = owned.resolve("$label.log")
        val compile = ProcessBuilder(compiler.toString(), "--no-default-config", "-std=$dialect", "-DREQUIRED=17",
            source.toString(), "-o", executable.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        if (!compile.waitFor(15, TimeUnit.SECONDS)) { compile.destroyForcibly(); compile.waitFor(); error("Native compile oracle timed out") }
        assertEquals(0, compile.exitValue(), Files.readString(log))
        val process = ProcessBuilder(executable.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(); error("Authored native oracle timed out") }
        return process.exitValue()
    }

    override fun close() {
        manager.close()
        Files.walk(owned).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    companion object {
        private fun sha(path: Path) = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it) }
        fun capture(type: String, dialect: String): CScalarParameterRenameFixture {
            require(type in setOf("long", "double")); require(dialect in setOf("c11", "c17"))
            return CScalarParameterRenameFixture(Files.createTempDirectory("rk-c-scalar-rename-"), type, dialect)
        }
    }
}
