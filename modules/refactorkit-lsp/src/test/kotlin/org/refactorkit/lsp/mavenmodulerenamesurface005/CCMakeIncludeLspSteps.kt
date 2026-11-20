package org.refactorkit.lsp.mavenmodulerenamesurface005

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.*
import org.refactorkit.c.CCMakeIncludeMoveSample
import org.refactorkit.c.CProjectScanner
import org.refactorkit.core.*
import org.refactorkit.lsp.LspSession
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.*

class CCMakeIncludeLspSteps {
    private val fixture = CCMakeIncludeLspFixture()
    @Given("a managed include-aware CMake LSP workspace captures {string} {string} and {string}")
    fun capture(standard: String, operation: String, condition: String) = fixture.capture(standard, operation, condition)
    @When("the client executes the managed include-aware CMake LSP lifecycle")
    fun execute() = fixture.execute()
    @Then("the managed include-aware CMake LSP outcome is {string}")
    fun outcome(expected: String) = fixture.assertOutcome(expected)
    @Then("the managed include-aware CMake LSP project and journal retain their expected images")
    fun intact() = fixture.assertIntact()
    @After
    fun close() = fixture.close()
}

private class CCMakeIncludeLspFixture : AutoCloseable {
    private var root: Path? = null
    private var session: LspSession? = null
    private var packaged: PackagedCQueryLspClient? = null
    private lateinit var sdk: Path
    private lateinit var mode: String
    private lateinit var condition: String
    private lateinit var original: Map<Path, String>
    private lateinit var expected: Map<Path, String>
    private lateinit var database: String
    private lateinit var expectedDatabase: String
    private lateinit var snapshotHash: String
    private var refusal: JsonRpcException? = null
    private var refusalPhase = ""
    private var transaction: String? = null
    private var outcome = ""
    private val forwardedHeader = Path.of("component/include/api.h")
    private val forwarding get() = condition.startsWith("forwarding ")
    private val databaseChanged get() = condition in setOf("database changed", "forwarding database changed")
    private val headerChanged get() = condition == "forwarding header changed"
    private val missingForwardingHeader get() = condition == "forwarding missing header"

    fun capture(standard: String, operation: String, variant: String) {
        require(standard in setOf("c11", "c17") && operation in setOf("component", "source", "header"))
        require(variant in setOf(
            "matching", "database changed", "missing flag", "forwarding and mapping",
            "forwarding database changed", "forwarding header changed", "forwarding missing header",
        ))
        mode = operation; condition = variant
        val workspace = Files.createTempDirectory("rk-cmake-include-lsp-").also { root = it }
        sdk = Path.of(assertNotNull(System.getenv("REFACTORKIT_C_QUALIFICATION_TOOLCHAIN")))
        original = CCMakeIncludeMoveSample.material(mode, "PUBLIC", false)
        expected = CCMakeIncludeMoveSample.material(mode, "PUBLIC", true,
            valueName = if (forwarding) "library_value" else "api_value",
        )
        if (forwarding) {
            expected = expected + (forwardedHeader to "#include \"../../library/include/api.h\"\n")
        }
        original.forEach { (file, text) -> Files.createDirectories(workspace.resolve(file).parent); Files.writeString(workspace.resolve(file), text) }
        fun commands(moved: Boolean): String {
            val directory = if (moved && mode == "component") "library" else "component"
            val first = if (moved && mode == "source") "$directory/src/deep/api.c" else "$directory/src/api.c"
            return JsonArray(listOf(first, "$directory/src/use.c").map { file -> buildJsonObject {
                put("directory", workspace.toString()); put("file", file)
                put("arguments", JsonArray((listOf(sdk.resolve("usr/bin/clang-22").toString(), "-std=$standard") +
                    (if (condition == "missing flag") emptyList() else listOf("-I", "$directory/include")) +
                    listOf("-c", file)).map(::JsonPrimitive)))
            } }).toString()
        }
        database = commands(false); expectedDatabase = commands(true)
        Files.writeString(workspace.resolve("compile_commands.json"), database)
        snapshotHash = CProjectScanner().scan(workspace).hash
        val runtime = System.getProperty("refactorkit.c.lsp.component.package.root")?.let(Path::of)
        if (runtime == null) session = LspSession()
        else packaged = PackagedCQueryLspClient(runtime, workspace)
        val initialized = dispatch("initialize", buildJsonObject {
            put("rootUri", workspace.toUri().toString())
            put("capabilities", buildJsonObject { put("workspace", buildJsonObject {
                put("workspaceEdit", buildJsonObject { put("documentChanges", true) })
            }) })
        }).jsonObject
        val commands = initialized.getValue("capabilities").jsonObject.getValue("executeCommandProvider")
            .jsonObject.getValue("commands").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(commands.containsAll(listOf("refactorkit.c.preview", "refactorkit.applyPlan", "refactorkit.rollback")))
    }

    fun execute() {
        val workspace = requireNotNull(root)
        val operation = if (mode == "component") "relocateComponent" else "moveSource"
        val preview = try {
            command("refactorkit.c.preview", buildJsonObject {
                put("operation", operation)
                put("arguments", buildJsonObject {
                    when (mode) {
                        "component" -> {
                            put("componentDir", "component")
                            put("newDir", "library")
                            if (forwarding) {
                                put("rename.api_value", "library_value")
                                put("forwardHeaders", if (missingForwardingHeader) "component/include/missing.h"
                                    else forwardedHeader.toString())
                            }
                        }
                        "source" -> { put("file", "component/src/api.c"); put("target", "component/src/deep/api.c") }
                        else -> { put("file", "component/include/api.h"); put("target", "component/include/public/api.h") }
                    }
                })
                put("clang", sdk.resolve("usr/bin/clang-22").toString())
                put("clangd", sdk.resolve("usr/bin/clangd").toString())
                put("clangFormat", sdk.resolve("usr/bin/clang-format").toString())
            })
        } catch (failure: JsonRpcException) {
            refusal = failure
            refusalPhase = "preview"
            outcome = if (missingForwardingHeader) "forwarding refused" else "unavailable refused"
            return
        }
        assertEquals("PREVIEW", preview.getValue("status").jsonPrimitive.content)
        assertEquals(operation, preview.getValue("operation").jsonPrimitive.content)
        assertFalse("changes" in preview || "documentChanges" in preview)
        if (condition != "missing flag") {
            assertEquals("COMPILER_PROVEN", preview.getValue("evidence").jsonPrimitive.content)
            assertEquals("HIGH", preview.getValue("riskLevel").jsonPrimitive.content)
            val affected = when (mode) {
                "component" -> setOf("component/src/api.c", "component/src/use.c", "component/include/api.h",
                    "library/src/api.c", "library/src/use.c", "library/include/api.h", "CMakeLists.txt", "compile_commands.json")
                "source" -> setOf("component/src/api.c", "component/src/deep/api.c", "CMakeLists.txt", "compile_commands.json")
                else -> setOf("component/include/api.h", "component/include/public/api.h", "component/src/api.c",
                    "component/src/use.c", "CMakeLists.txt")
            }
            assertEquals(affected, preview.getValue("affectedFiles").jsonArray.map { it.jsonPrimitive.content }.toSet())
            assertFalse(preview.getValue("diffTruncated").jsonPrimitive.content.toBoolean())
            if (forwarding) {
                val diff = preview.getValue("renderedDiff").jsonPrimitive.content
                assertTrue("#include \"../../library/include/api.h\"" in diff && "library_value" in diff, diff)
            }
        }
        assertImage(original)
        assertEquals(database, Files.readString(workspace.resolve("compile_commands.json")))
        assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        if (databaseChanged) Files.writeString(workspace.resolve("compile_commands.json"), database + "\n")
        if (headerChanged) Files.writeString(workspace.resolve(forwardedHeader), changedHeaderText())
        val applied = try {
            command("refactorkit.applyPlan", buildJsonObject { put("planId", preview.getValue("planId")) })
        } catch (failure: JsonRpcException) {
            refusal = failure; refusalPhase = "apply"
            outcome = if (databaseChanged || headerChanged) "changed input refused" else "unavailable refused"
            return
        }
        assertTrue(condition in setOf("matching", "forwarding and mapping"))
        transaction = applied.getValue("transactionId").jsonPrimitive.content
        assertImage(expected)
        assertEquals(expectedDatabase, Files.readString(workspace.resolve("compile_commands.json")))
        val rollback = command("refactorkit.rollback", buildJsonObject { put("transactionId", assertNotNull(transaction)) })
        assertEquals("rolledBack", rollback.getValue("status").jsonPrimitive.content)
        assertEquals(transaction, rollback.getValue("transactionId").jsonPrimitive.content)
        outcome = "applied and rolled back"
    }

    fun assertOutcome(expectedOutcome: String) {
        assertEquals(expectedOutcome, outcome, refusal?.toString())
        when (expectedOutcome) {
            "applied and rolled back" -> assertNull(refusal)
            "changed input refused" -> {
                assertEquals("apply", refusalPhase)
                assertEquals(JsonRpcErrorCodes.SNAPSHOT_CHANGED, assertNotNull(refusal).code)
            }
            "forwarding refused" -> {
                assertEquals("preview", refusalPhase)
                val failure = assertNotNull(refusal)
                assertEquals(JsonRpcErrorCodes.PLAN_REFUSED, failure.code)
                assertEquals("c.cmakeInclude.forwardingAuthorityUnavailable",
                    failure.data?.jsonObject?.getValue("refusalCode")?.jsonPrimitive?.content)
            }
            "unavailable refused" -> {
                assertEquals("missing flag", condition)
                assertEquals(if (refusalPhase == "preview") JsonRpcErrorCodes.PLAN_REFUSED else JsonRpcErrorCodes.DIAGNOSTICS_FAILED,
                    assertNotNull(refusal).code)
            }
            else -> error("Unknown outcome: $expectedOutcome")
        }
    }

    fun assertIntact() {
        val workspace = requireNotNull(root)
        assertImage(if (headerChanged) original + (forwardedHeader to changedHeaderText()) else original)
        assertEquals(database + if (databaseChanged) "\n" else "", Files.readString(workspace.resolve("compile_commands.json")))
        if (transaction == null) assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        else assertEquals(JournalState.ROLLED_BACK, assertNotNull(TransactionLog(workspace.resolve(".refactorkit/transactions"))
            .loadRecord(TransactionId(assertNotNull(transaction)))).state)
        if (databaseChanged || headerChanged) assertNotEquals(snapshotHash, CProjectScanner().scan(workspace).hash)
        else assertEquals(snapshotHash, CProjectScanner().scan(workspace).hash)
        Files.walk(workspace).use { stream -> assertFalse(stream.anyMatch { it.fileName.toString().endsWith(".o") || it.fileName.toString().endsWith(".a") }) }
    }

    private fun assertImage(image: Map<Path, String>) {
        val workspace = requireNotNull(root)
        image.forEach { (file, text) -> assertEquals(text, Files.readString(workspace.resolve(file)), file.toString()) }
        val other = (original.keys + expected.keys) - image.keys
        other.forEach { assertFalse(Files.exists(workspace.resolve(it)), "Unexpected path after lifecycle: $it") }
    }

    private fun changedHeaderText() = original.getValue(forwardedHeader) + "// caller changed\n"

    private fun dispatch(method: String, params: JsonObject): JsonElement =
        packaged?.dispatch(method, params) ?: requireNotNull(session).dispatch(method, params)

    private fun command(name: String, args: JsonObject): JsonObject = dispatch("workspace/executeCommand",
        buildJsonObject { put("command", name); put("arguments", JsonArray(listOf(args))) }).jsonObject

    override fun close() {
        try {
            packaged?.close()
            session?.dispatch("shutdown", null)
            session?.dispatch("exit", null)
        } finally {
            root?.let { workspace -> Files.walk(workspace).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            } }
        }
    }
}
