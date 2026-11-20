package org.refactorkit.lsp.mavenmodulerenamesurface005

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.*
import org.refactorkit.c.CProjectScanner
import org.refactorkit.core.JournalState
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.TransactionId
import org.refactorkit.core.TransactionLog
import org.refactorkit.lsp.LspSession
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.*

class CManagedSymbolRenameLspSteps {
    private val fixture = CManagedSymbolRenameLspFixture()

    @Given("a managed C symbol-rename LSP workspace captures {string} and {string}")
    fun capture(standard: String, condition: String) = fixture.capture(standard, condition)

    @Given("a managed C variable-rename LSP workspace captures {string} {string} and {string}")
    fun captureVariable(standard: String, binding: String, condition: String) = fixture.capture(standard, condition, binding)

    @Given("a managed C header-field LSP workspace captures {string} and {string}")
    fun captureHeaderField(standard: String, condition: String) = fixture.capture(standard, condition, "header field")

    @When("the LSP client executes the selected C symbol-rename commands")
    fun execute() = fixture.execute()

    @Then("the managed C symbol-rename LSP outcome is {string}")
    fun result(expected: String) = fixture.assertResult(expected)

    @Then("the managed C symbol-rename LSP inputs and transaction history are preserved")
    fun intact() = fixture.assertIntact()

    @After
    fun close() = fixture.close()
}

private class CManagedSymbolRenameLspFixture : AutoCloseable {
    private var root: Path? = null
    private var session: LspSession? = null
    private var sdkMode = false
    private var externalSdk: Path? = null
    private var sdkImage: Map<Path, String> = emptyMap()
    private var unexpectedFailure = ""
    private lateinit var sdk: Path
    private lateinit var condition: String
    private lateinit var database: String
    private lateinit var snapshotHash: String
    private var outcome = ""
    private var refusal: JsonRpcException? = null
    private var transaction: String? = null
    private var original = mapOf(Path.of("main.c") to "static int old(int value) { return value + 1; }\nint main(void) { return old(2); }\n")
    private var expected = mapOf(Path.of("main.c") to "static int renamed(int value) { return value + 1; }\nint main(void) { return renamed(2); }\n")
    private var selectedFile = "main.c"
    private var collisionTarget = "main"
    private var refusalPhase = ""
    private var sourceWasChanged = false
    private var symbol = "old"
    private var line = 0
    private var character = 11

    private fun headerMaterial(field: String): Map<Path, String> = linkedMapOf(
        Path.of("include/api.h") to "struct meter_reading {\n    int $field;\n    int calibration;\n};\n" +
            "struct other_reading { int raw_count; };\nstruct meter_reading read_meter(void);\n",
        Path.of("src/read_meter.c") to "#include \"api.h\"\nstruct meter_reading read_meter(void) {\n" +
            "    return (struct meter_reading) { .calibration = 4, .$field = 19 };\n}\n",
        Path.of("src/main.c") to "#include \"api.h\"\nint main(void) {\n    const char *label = \"raw_count\";\n" +
            "    struct meter_reading reading = read_meter();\n    struct other_reading other = { .raw_count = 7 };\n" +
            "    /* raw_count remains a comment. */\n    return reading.$field + other.raw_count + label[0];\n}\n",
    )

    fun capture(standard: String, variant: String, binding: String = "function") {
        sdkMode = variant.startsWith("SDK ")
        condition = variant.removePrefix("SDK ")
        require(standard in setOf("c11", "c17"))
        require(condition in setOf("matching", "target collision", "database changed", "source changed", "incomplete consumer") ||
            sdkMode && condition in setOf("header changes", "header appears", "missing license", "invalid array"))
        require(!sdkMode || binding == "header field")
        require(binding in setOf("function", "local", "parameter", "internal object", "header field"))
        require(variant !in setOf("source changed", "incomplete consumer") || binding == "header field")
        if (binding == "header field") {
            original = headerMaterial("raw_count"); expected = headerMaterial("renamed")
            selectedFile = "include/api.h"; collisionTarget = "calibration"; symbol = "raw_count"
            line = 1; character = 8
        } else if (binding != "function") {
            fun program(global: String, parameter: String, local: String) =
                "static int $global = 4;\nstatic int calculate(int $parameter) {\n" +
                "  int result = $parameter;\n  { int $local = 3; result += $local; }\n" +
                "  return result + $parameter;\n}\nstatic int read(void) { return $global; }\n" +
                "int main(void) { int value = 1; const char *label = \"value\"; " +
                "return calculate(value) + read() + (label[0] == 'v' ? 0 : 1); }\n// value remains a comment\n"
            original = mapOf(Path.of("main.c") to program("value", "value", "value"))
            expected = mapOf(Path.of("main.c") to when (binding) {
                "local" -> program("value", "value", "renamed")
                "parameter" -> program("value", "renamed", "value")
                else -> program("renamed", "value", "value")
            })
            symbol = "value"
            line = when (binding) { "local" -> 3; "parameter" -> 1; else -> 0 }
            character = when (binding) { "local" -> 8; "parameter" -> 25; else -> 11 }
        }
        if (sdkMode) {
            val sdkRoot = Files.createTempDirectory("rk-c-lsp-readonly-sdk-"); externalSdk = sdkRoot
            Files.createDirectories(sdkRoot.resolve("usr/include"))
            Files.writeString(sdkRoot.resolve("usr/include/sdk_types.h"),
                "#pragma once\ntypedef int sdk_count;\nstruct sdk_reading { int raw_count; };\n")
            fun withSdk(image: Map<Path, String>, field: String) = image.mapValues { (path, text) ->
                if (path == Path.of("include/api.h")) "#include \"sdk_types.h\"\n" + text.replace("    int $field;", "    sdk_count $field;") else text
            }
            original = withSdk(original, "raw_count"); expected = withSdk(expected, "renamed")
            line = 2; character = 14; sdkImage = sdkFiles()
        }
        val workspace = Files.createTempDirectory("rk-c-symbol-lsp-").also { root = it }
        sdk = Path.of(assertNotNull(System.getenv("REFACTORKIT_C_QUALIFICATION_TOOLCHAIN")))
        original.forEach { (file, content) ->
            Files.createDirectories(workspace.resolve(file).parent); Files.writeString(workspace.resolve(file), content)
        }
        val units = original.keys.filter { it.toString().endsWith(".c") &&
            (condition != "incomplete consumer" || it != Path.of("src/main.c")) }
        database = JsonArray(units.map { file -> buildJsonObject {
            put("directory", workspace.toString()); put("file", file.toString())
            val arguments = listOf(sdk.resolve("usr/bin/clang-22").toString(), "-std=$standard") +
                (if (binding == "header field") listOf("-Iinclude") else emptyList()) +
                externalSdk?.let { listOf("--sysroot=$it") }.orEmpty() + listOf("-c", file.toString())
            put("arguments", JsonArray(arguments.map(::JsonPrimitive)))
        } }).toString()
        Files.writeString(workspace.resolve("compile_commands.json"), database)
        snapshotHash = CProjectScanner().scan(workspace).hash
        session = LspSession().also { it.dispatch("initialize", buildJsonObject {
            put("rootUri", workspace.toUri().toString())
            put("capabilities", buildJsonObject { put("workspace", buildJsonObject {
                put("workspaceEdit", buildJsonObject { put("documentChanges", true) })
            }) })
        }) }
    }

    fun execute() {
        if (sdkMode) runCatching { executeCommands() }.onFailure { failure ->
            unexpectedFailure = "${failure.javaClass.simpleName}: ${failure.message}"; outcome = "unexpected SDK failure"
        } else executeCommands()
    }

    private fun executeCommands() {
        val workspace = requireNotNull(root)
        val preview: JsonObject
        try {
            preview = command("refactorkit.c.preview", buildJsonObject {
                put("operation", "renameSymbol")
                put("arguments", buildJsonObject {
                    put("symbol", symbol); put("newName", if (condition == "target collision") collisionTarget else "renamed")
                    put("file", selectedFile); put("line", line); put("character", character)
                })
                put("clang", sdk.resolve("usr/bin/clang-22").toString())
                put("clangd", sdk.resolve("usr/bin/clangd").toString())
                put("clangFormat", sdk.resolve("usr/bin/clang-format").toString())
                externalSdk?.let { root ->
                    put("readOnlySdks", if (condition == "invalid array") buildJsonObject { put("root", root.toString()) }
                    else buildJsonArray { add(buildJsonObject {
                        put("root", root.toString()); put("provenance", "fixture:lsp-sdk")
                        if (condition != "missing license") put("license", "LicenseRef-RefactorKitFixture")
                    }) })
                }
            })
        } catch (rejected: JsonRpcException) {
            refusal = rejected; refusalPhase = "preview"
            outcome = if (sdkMode && condition in setOf("missing license", "invalid array")) "invalid SDK refused"
                else if (condition == "incomplete consumer") "incomplete consumer refused" else "collision refused"
            return
        }
        assertEquals("PREVIEW", preview.getValue("status").jsonPrimitive.content)
        assertEquals("renameSymbol", preview.getValue("operation").jsonPrimitive.content)
        assertEquals("COMPILER_PROVEN", preview.getValue("evidence").jsonPrimitive.content)
        assertFalse("changes" in preview || "documentChanges" in preview)
        assertSource(original)
        assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        if (condition == "database changed") Files.writeString(workspace.resolve("compile_commands.json"), database + "\n")
        if (condition == "source changed") {
            val file = Path.of("src/main.c")
            Files.writeString(workspace.resolve(file), original.getValue(file) + "// caller edit\n")
            sourceWasChanged = true
        }
        externalSdk?.let { sdkRoot ->
            when (condition) {
                "header changes" -> {
                    val file = sdkRoot.resolve("usr/include/sdk_types.h")
                    Files.writeString(file, Files.readString(file) + "\n/* changed LSP SDK input */\n")
                }
                "header appears" -> Files.writeString(sdkRoot.resolve("usr/include/added.h"), "#pragma once\n")
            }
            sdkImage = sdkFiles()
            assertEquals(snapshotHash, CProjectScanner().scan(workspace).hash, "SDK changes must not change the workspace snapshot")
        }
        val applied: JsonObject
        try {
            applied = command("refactorkit.applyPlan", buildJsonObject { put("planId", preview.getValue("planId")) })
        } catch (rejected: JsonRpcException) {
            refusal = rejected; refusalPhase = "apply"
            outcome = if (condition == "incomplete consumer") "incomplete consumer refused" else "changed input refused"
            return
        }
        assertEquals("matching", condition)
        transaction = applied.getValue("transactionId").jsonPrimitive.content
        assertSource(expected)
        assertEquals(database, Files.readString(workspace.resolve("compile_commands.json")))
        val rolled = command("refactorkit.rollback", buildJsonObject { put("transactionId", assertNotNull(transaction)) })
        assertEquals("rolledBack", rolled.getValue("status").jsonPrimitive.content)
        assertEquals(transaction, rolled.getValue("transactionId").jsonPrimitive.content)
        outcome = "applied and rolled back"
    }

    fun assertResult(expectedOutcome: String) {
        assertEquals(expectedOutcome, outcome, unexpectedFailure.ifEmpty { refusal?.message.orEmpty() })
        if (expectedOutcome == "applied and rolled back") assertNull(refusal)
        else {
            val rejected = assertNotNull(refusal)
            if (expectedOutcome == "invalid SDK refused") {
                assertTrue(sdkMode && condition in setOf("missing license", "invalid array"))
                assertEquals("preview", refusalPhase)
                assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, rejected.code)
                assertEquals("c.sdkConfigurationInvalid", (rejected.data as? JsonObject)?.get("refusalCode")?.jsonPrimitive?.content)
            } else if (sdkMode && expectedOutcome == "changed input refused") {
                assertTrue(condition in setOf("header changes", "header appears"))
                assertEquals("apply", refusalPhase)
                assertTrue(rejected.code in setOf(JsonRpcErrorCodes.PLAN_REFUSED, JsonRpcErrorCodes.PLAN_VALIDATION_FAILED, JsonRpcErrorCodes.DIAGNOSTICS_FAILED), rejected.toString())
            } else if (expectedOutcome == "collision refused") {
                assertEquals("target collision", condition)
                assertEquals(JsonRpcErrorCodes.PLAN_REFUSED, rejected.code)
                assertTrue(rejected.data.toString().contains("c.renameTargetIdentifierPresent"), rejected.toString())
            } else if (expectedOutcome == "incomplete consumer refused") {
                assertEquals("incomplete consumer", condition)
                assertEquals(if (refusalPhase == "preview") JsonRpcErrorCodes.PLAN_REFUSED else JsonRpcErrorCodes.DIAGNOSTICS_FAILED, rejected.code)
                if (refusalPhase == "apply") assertTrue(rejected.message.orEmpty().contains("lease"), rejected.message)
            } else {
                assertTrue(condition in setOf("database changed", "source changed"))
                assertEquals(JsonRpcErrorCodes.SNAPSHOT_CHANGED, rejected.code)
                assertTrue(rejected.message.orEmpty().contains("changed since preview"), rejected.message)
            }
        }
    }

    fun assertIntact() {
        val workspace = requireNotNull(root)
        assertSource(if (sourceWasChanged) original.mapValues { (file, text) ->
            text + if (file == Path.of("src/main.c")) "// caller edit\n" else ""
        } else original)
        assertEquals(database + if (condition == "database changed") "\n" else "", Files.readString(workspace.resolve("compile_commands.json")))
        if (sdkMode) {
            assertEquals(sdkImage, sdkFiles())
            Files.walk(workspace).use { paths -> assertFalse(paths.anyMatch { it.fileName.toString().endsWith(".o") }) }
        }
        if (transaction == null) assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        else assertEquals(JournalState.ROLLED_BACK, assertNotNull(TransactionLog(workspace.resolve(".refactorkit/transactions"))
            .loadRecord(TransactionId(assertNotNull(transaction)))).state)
        if (condition == "database changed" || sourceWasChanged) assertNotEquals(snapshotHash, CProjectScanner().scan(workspace).hash)
        else assertEquals(snapshotHash, CProjectScanner().scan(workspace).hash)
    }

    private fun assertSource(image: Map<Path, String>) = image.forEach { (file, content) ->
        assertEquals(content, Files.readString(requireNotNull(root).resolve(file)), file.toString())
    }

    private fun command(name: String, args: JsonObject): JsonObject = requireNotNull(session)
        .dispatch("workspace/executeCommand", buildJsonObject { put("command", name); put("arguments", JsonArray(listOf(args))) }).jsonObject

    private fun sdkFiles(): Map<Path, String> = externalSdk?.let { sdkRoot -> Files.walk(sdkRoot).use { paths ->
        paths.filter { Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }.toList()
            .associate { sdkRoot.relativize(it) to Files.readString(it) }
    } }.orEmpty()

    override fun close() {
        session?.dispatch("shutdown", null); session?.dispatch("exit", null)
        externalSdk?.let { sdkRoot -> Files.walk(sdkRoot).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
        root?.let { workspace -> Files.walk(workspace).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
}
