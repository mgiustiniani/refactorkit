package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.*
import org.refactorkit.c.CProjectScanner
import org.refactorkit.cli.RefactorKitCli
import org.refactorkit.core.JournalState
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.TransactionId
import org.refactorkit.core.TransactionLog
import org.refactorkit.daemon.DaemonSession
import org.refactorkit.mcp.McpSession
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.*

class CManagedSymbolRenameSurfaceSteps {
    private val fixture = CManagedSymbolRenameSurfaceFixture()

    @Given("the managed C symbol-rename surface {string} captures {string} inputs with {string}")
    fun capture(surface: String, standard: String, condition: String) = fixture.capture(surface, standard, condition)

    @Given("the managed C variable-rename surface {string} captures {string} {string} inputs with {string}")
    fun captureVariable(surface: String, standard: String, binding: String, condition: String) =
        fixture.capture(surface, standard, condition, binding)

    @Given("the managed C header-field surface {string} captures {string} inputs with {string}")
    fun captureHeaderField(surface: String, standard: String, condition: String) = fixture.capture(surface, standard, condition, "header field")

    @When("that surface requests the selected C header field rename lifecycle")
    fun executeHeaderField() = fixture.execute()

    @When("that surface requests the selected C function rename lifecycle")
    fun execute() = fixture.execute()

    @When("that surface requests the selected C variable rename lifecycle")
    fun executeVariable() = fixture.execute()

    @Then("the managed C symbol-rename surface reports {string}")
    fun result(expected: String) = fixture.assertResult(expected)

    @Then("the C symbol-rename surface preserves captured inputs and transaction history")
    fun intact() = fixture.assertIntact()

    @After
    fun close() = fixture.close()
}

private class CManagedSymbolRenameSurfaceFixture : AutoCloseable {
    private var root: Path? = null
    private var daemon: DaemonSession? = null
    private var mcp: McpSession? = null
    private lateinit var sdk: Path
    private lateinit var surface: String
    private lateinit var condition: String
    private var ownedSysroot = false
    private var sdkMode = false
    private var externalSdk: Path? = null
    private val additionalSdks = mutableListOf<Path>()
    private var additionalSdkImages: Map<Path, Map<Path, String>> = emptyMap()
    private var sdkImage: Map<Path, String> = emptyMap()
    private lateinit var database: String
    private lateinit var snapshotHash: String
    private var result = ""
    private var detail = ""
    private var transaction: String? = null
    private var original = mapOf(Path.of("main.c") to "static int old(int value) { return value + 1; }\nint main(void) { return old(2); }\n")
    private var expected = mapOf(Path.of("main.c") to "static int renamed(int value) { return value + 1; }\nint main(void) { return renamed(2); }\n")
    private var selectedFile = "main.c"
    private var collisionTarget = "main"
    private var symbol = "old"
    private var line = 0
    private var character = 11

    fun capture(route: String, standard: String, variant: String, binding: String = "function") {
        require(route in setOf("daemon", "MCP", "CLI") && standard in setOf("c11", "c17"))
        ownedSysroot = variant.startsWith("sysroot ")
        sdkMode = variant.startsWith("SDK ")
        val effectiveCondition = variant.removePrefix("sysroot ").removePrefix("SDK ")
        require(effectiveCondition in setOf("matching", "database changed", "target collision") || sdkMode &&
            effectiveCondition in setOf("changed", "missing license", "invalid array", "two roots", "eight roots", "nine roots", "invalid JSON"))
        require(!sdkMode || binding == "header field" && route in setOf("daemon", "MCP", "CLI"))
        require(!ownedSysroot || binding == "header field")
        require(binding in setOf("function", "local", "parameter", "internal object", "header field"))
        if (binding == "header field") {
            original = CHeaderFieldRenameSample.material("raw_count")
            expected = CHeaderFieldRenameSample.material("renamed")
            symbol = "raw_count"; selectedFile = "include/api.h"; collisionTarget = "calibration"
            line = 1; character = 8
            if (ownedSysroot) {
                fun remap(image: Map<Path, String>) = image.mapKeys { (path, _) ->
                    if (path == Path.of("include/api.h")) Path.of("sdk-root/usr/include/api.h") else path
                }
                original = remap(original); expected = remap(expected)
                selectedFile = "sdk-root/usr/include/api.h"
            }
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
            val sdk = Files.createTempDirectory("rk-c-public-readonly-sdk-"); externalSdk = sdk
            Files.createDirectories(sdk.resolve("usr/include"))
            Files.writeString(sdk.resolve("usr/include/sdk_types.h"),
                "#pragma once\ntypedef int sdk_count;\nstruct sdk_reading { int raw_count; };\n")
            fun withSdk(image: Map<Path, String>, field: String) = image.mapValues { (path, text) ->
                if (path == Path.of("include/api.h")) "#include \"sdk_types.h\"\n" + text.replace("    int $field;", "    sdk_count $field;") else text
            }
            original = withSdk(original, "raw_count"); expected = withSdk(expected, "renamed")
            line = 2; character = 14; sdkImage = sdkFiles()
            val count = when (effectiveCondition) { "two roots" -> 2; "eight roots" -> 8; "nine roots" -> 9; else -> 1 }
            repeat(count - 1) {
                val extra = Files.createTempDirectory("rk-c-additional-readonly-sdk-")
                Files.createDirectories(extra.resolve("usr/include"))
                Files.copy(sdk.resolve("usr/include/sdk_types.h"), extra.resolve("usr/include/sdk_types.h"))
                additionalSdks.add(extra)
            }
            additionalSdkImages = additionalSdks.associateWith(::filesAt)
        }
        surface = route; condition = effectiveCondition
        val workspace = Files.createTempDirectory("rk-c-symbol-surface-").also { root = it }
        sdk = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        original.forEach { (file, content) ->
            Files.createDirectories(workspace.resolve(file).parent); Files.writeString(workspace.resolve(file), content)
        }
        database = JsonArray(original.keys.filter { it.toString().endsWith(".c") }.map { file -> buildJsonObject {
            put("directory", workspace.toString()); put("file", file.toString())
            val arguments = listOf(sdk.resolve("usr/bin/clang-22").toString(), "-std=$standard") +
                (if (ownedSysroot) listOf("--sysroot=sdk-root") else if (binding == "header field") listOf("-Iinclude") else emptyList()) +
                externalSdk?.let { first ->
                    val selected = if (file.toString() == "src/main.c") additionalSdks.lastOrNull() ?: first else first
                    listOf("--sysroot=$selected")
                }.orEmpty() + listOf("-c", file.toString())
            if (ownedSysroot) {
                assertTrue("--sysroot=sdk-root" in arguments)
                assertFalse(arguments.any { it.startsWith("-I") || it.startsWith("-isystem") || it.startsWith("-iquote") })
            }
            put("arguments", JsonArray(arguments.map(::JsonPrimitive)))
        } }).toString()
        Files.writeString(workspace.resolve("compile_commands.json"), database)
        snapshotHash = CProjectScanner().scan(workspace).hash
        when (surface) {
            "daemon" -> daemon = DaemonSession().also { it.dispatch("project.open", buildJsonObject { put("root", workspace.toString()) }) }
            "MCP" -> { mcp = McpSession(); call("project_scan", buildJsonObject { put("root", workspace.toString()) }) }
        }
    }

    fun execute() {
        if (sdkMode) runCatching { executeRequest() }.onFailure { failure ->
            detail = "${failure.javaClass.simpleName}: ${failure.message}"; result = "unexpected SDK failure"
        } else executeRequest()
    }

    private fun executeRequest() {
        if (surface == "CLI") { executeCli(); return }
        val workspace = requireNotNull(root)
        val request = buildJsonObject {
            put("operation", "renameSymbol"); put("languageId", "c")
            put("arguments", buildJsonObject {
                put("symbol", symbol); put("newName", "renamed"); put("file", selectedFile); put("line", line); put("character", character)
            })
            put("clang", sdk.resolve("usr/bin/clang-22").toString())
            put("clangd", sdk.resolve("usr/bin/clangd").toString())
            put("clangFormat", sdk.resolve("usr/bin/clang-format").toString())
            externalSdk?.let { sdk ->
                put("readOnlySdks", if (condition == "invalid array") buildJsonObject { put("root", sdk.toString()) }
                else buildJsonArray { add(buildJsonObject {
                    put("root", sdk.toString()); put("provenance", "fixture:public-managed-sdk")
                    if (condition != "missing license") put("license", "LicenseRef-RefactorKitFixture")
                }) })
            }
        }
        if (sdkMode && condition in setOf("missing license", "invalid array")) {
            if (surface == "daemon") {
                val failure = runCatching { requireNotNull(daemon).dispatch("c.preview", request) }.exceptionOrNull()
                val typed = failure as? JsonRpcException
                detail = failure.toString()
                result = if (typed?.code == JsonRpcErrorCodes.INVALID_PARAMS &&
                    (typed.data as? JsonObject)?.get("refusalCode")?.jsonPrimitive?.content == "c.sdkConfigurationInvalid") "invalid SDK refused" else "invalid SDK not typed"
            } else {
                val response = requireNotNull(mcp).dispatch("tools/call", buildJsonObject { put("name", "preview_refactoring"); put("arguments", request) }).jsonObject
                detail = response.toString()
                result = if (response["isError"]?.jsonPrimitive?.booleanOrNull == true && "c.sdkConfigurationInvalid" in detail) "invalid SDK refused" else "invalid SDK not typed"
            }
            return
        }
        val planId = if (surface == "daemon") {
            val preview = requireNotNull(daemon).dispatch("c.preview", request).jsonObject
            assertFalse(preview.toString().contains("Managed single-symbol Apply unavailable"), preview.toString())
            assertEquals("PREVIEW", preview.getValue("status").jsonPrimitive.content, preview.toString())
            assertEquals("COMPILER_PROVEN", preview.getValue("evidence").jsonPrimitive.content)
            preview.getValue("planId").jsonPrimitive.content
        } else {
            val preview = call("preview_refactoring", request)
            assertFalse(preview.contains("Managed single-symbol Apply unavailable"), preview)
            assertTrue(preview.contains("Status   : PREVIEW") && preview.contains("Operation: renameSymbol"), preview)
            assertTrue(preview.contains("COMPILER_PROVEN"), preview)
            Regex("Plan ID\\s+: (\\S+)").find(preview)?.groupValues?.get(1) ?: error(preview)
        }
        assertSource(original)
        assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        if (condition == "database changed") Files.writeString(workspace.resolve("compile_commands.json"), database + "\n")
        if (sdkMode && condition == "changed") {
            val header = requireNotNull(externalSdk).resolve("usr/include/sdk_types.h")
            Files.writeString(header, Files.readString(header) + "\n/* changed public SDK input */\n")
            sdkImage = sdkFiles()
            assertEquals(snapshotHash, CProjectScanner().scan(workspace).hash, "External SDK mutation must not masquerade as a workspace change")
        }
        try {
            if (surface == "daemon") {
                val applied = requireNotNull(daemon).dispatch("refactor.apply", buildJsonObject { put("planId", planId) }).jsonObject
                detail = applied.toString()
                assertEquals("applied", applied.getValue("status").jsonPrimitive.content, detail)
                transaction = applied.getValue("transactionId").jsonPrimitive.content
            } else {
                val applied = call("apply_refactoring", buildJsonObject { put("planId", planId) }); detail = applied
                if (sdkMode && condition == "changed") {
                    assertTrue(applied.startsWith("Apply refused ["), applied)
                    result = "changed input refused"; return
                }
                if (condition == "database changed") {
                    assertTrue(applied.startsWith("Apply refused [${JsonRpcErrorCodes.SNAPSHOT_CHANGED}]"), applied)
                    result = "changed input refused"; return
                }
                assertTrue(applied.contains("Applied successfully"), applied)
                transaction = Regex("Transaction ID: (\\S+)").find(applied)?.groupValues?.get(1) ?: error(applied)
            }
        } catch (refused: JsonRpcException) {
            detail = refused.message.orEmpty()
            if (sdkMode && condition == "changed") {
                assertTrue(refused.code in setOf(JsonRpcErrorCodes.PLAN_REFUSED, JsonRpcErrorCodes.PLAN_VALIDATION_FAILED, JsonRpcErrorCodes.DIAGNOSTICS_FAILED), detail)
            } else {
                assertEquals("database changed", condition, detail)
                assertEquals(JsonRpcErrorCodes.SNAPSHOT_CHANGED, refused.code, detail)
            }
            result = "changed input refused"; return
        }
        assertEquals("matching", condition)
        assertSource(expected)
        assertEquals(database, Files.readString(workspace.resolve("compile_commands.json")))
        val id = assertNotNull(transaction)
        if (surface == "daemon") {
            val rolled = requireNotNull(daemon).dispatch("patch.rollback", buildJsonObject { put("transactionId", id) }).jsonObject
            assertEquals("rolledBack", rolled.getValue("status").jsonPrimitive.content, rolled.toString())
        } else assertTrue(call("rollback_refactoring", buildJsonObject { put("transactionId", id) }).contains("Rolled back"))
        result = "applied and rolled back"
    }

    private fun executeCli() {
        val workspace = requireNotNull(root)
        val command = listOf("c", "rename", workspace.toString(), "--symbol", symbol, "--to",
            if (condition == "target collision") collisionTarget else "renamed", "--file", selectedFile, "--line", line.toString(), "--character", character.toString(),
            "--clang", sdk.resolve("usr/bin/clang-22").toString(), "--clangd", sdk.resolve("usr/bin/clangd").toString(),
            "--clang-format", sdk.resolve("usr/bin/clang-format").toString()) + if (sdkMode) {
                val roots = listOf(requireNotNull(externalSdk)) + additionalSdks
                val json = if (condition == "invalid JSON") "{" else buildJsonArray {
                    roots.forEach { sdk -> add(buildJsonObject {
                        put("root", sdk.toString()); put("provenance", "fixture:cli-sdk")
                        put("license", "LicenseRef-RefactorKitFixture")
                    }) }
                }.toString()
                assertEquals(when (condition) { "two roots" -> 2; "eight roots" -> 8; "nine roots" -> 9; else -> 1 }, roots.size)
                listOf("--read-only-sdks", json)
            } else emptyList()
        val (code, output) = cli(command + "--preview"); detail = output
        assertSource(original)
        if (sdkMode && condition in setOf("invalid JSON", "nine roots")) {
            assertEquals(1, code, output); assertTrue("c.sdkConfigurationInvalid" in output, output)
            result = "invalid SDK refused"; return
        }
        if (condition == "target collision") {
            assertEquals(1, code, output); assertTrue(output.contains("c.renameTargetIdentifierPresent"), output)
            val (applyCode, refused) = cli(command + "--apply")
            assertEquals(1, applyCode, refused); assertTrue(refused.contains("c.renameTargetIdentifierPresent"), refused)
            result = "collision refused"; return
        }
        assertEquals(0, code, output)
        val preview = Json.parseToJsonElement(output).jsonObject
        assertFalse(output.contains("Managed single-symbol Apply unavailable"), output)
        assertEquals("PREVIEW", preview.getValue("status").jsonPrimitive.content)
        assertEquals("renameSymbol", preview.getValue("operation").jsonPrimitive.content)
        assertEquals("COMPILER_PROVEN", preview.getValue("evidence").jsonPrimitive.content)
        val (appliedCode, appliedOutput) = cli(command + "--apply"); detail = appliedOutput
        assertEquals(0, appliedCode, appliedOutput)
        val applied = Json.parseToJsonElement(appliedOutput).jsonObject
        assertEquals("applied", applied.getValue("status").jsonPrimitive.content)
        transaction = applied.getValue("transactionId").jsonPrimitive.content
        assertSource(expected)
        val (rollbackCode, rollback) = cli(listOf("patch", "rollback", assertNotNull(transaction), "--root", workspace.toString()))
        assertEquals(0, rollbackCode, rollback); assertTrue(rollback.contains("Rolled back transaction"), rollback)
        result = "applied and rolled back"
    }

    fun assertResult(expectedResult: String) = assertEquals(expectedResult, result, detail)

    fun assertIntact() {
        val workspace = requireNotNull(root)
        assertSource(original)
        assertEquals(database + if (condition == "database changed") "\n" else "", Files.readString(workspace.resolve("compile_commands.json")))
        if (ownedSysroot) {
            assertFalse(Files.exists(workspace.resolve("include/api.h")))
            Files.walk(workspace).use { paths -> assertFalse(paths.anyMatch { it.fileName.toString().endsWith(".o") }) }
        }
        if (sdkMode) {
            assertEquals(sdkImage, sdkFiles())
            assertEquals(additionalSdkImages, additionalSdks.associateWith(::filesAt))
            Files.walk(workspace).use { paths -> assertFalse(paths.anyMatch { it.fileName.toString().endsWith(".o") }) }
            if (surface == "MCP") {
                val tools = requireNotNull(mcp).dispatch("tools/list", JsonObject(emptyMap())).jsonObject.getValue("tools").jsonArray
                val preview = tools.map { it.jsonObject }.single { it.getValue("name").jsonPrimitive.content == "preview_refactoring" }
                val config = preview.getValue("inputSchema").jsonObject.getValue("properties").jsonObject.getValue("readOnlySdks").jsonObject
                assertEquals("array", config.getValue("type").jsonPrimitive.content); assertEquals(8, config.getValue("maxItems").jsonPrimitive.int)
                val items = config.getValue("items").jsonObject
                assertEquals(false, items.getValue("additionalProperties").jsonPrimitive.boolean)
                assertEquals(setOf("root", "provenance", "license"), items.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
                assertTrue(items.getValue("properties").jsonObject.values.all { it.jsonObject.getValue("type").jsonPrimitive.content == "string" })
            }
        }
        if (transaction == null) assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        else {
            assertEquals(snapshotHash, CProjectScanner().scan(workspace).hash)
            assertEquals(JournalState.ROLLED_BACK, assertNotNull(TransactionLog(workspace.resolve(".refactorkit/transactions"))
                .loadRecord(TransactionId(assertNotNull(transaction)))).state)
        }
    }

    private fun assertSource(image: Map<Path, String>) = image.forEach { (file, content) ->
        assertEquals(content, Files.readString(requireNotNull(root).resolve(file)), file.toString())
    }

    private fun call(name: String, args: JsonObject): String = requireNotNull(mcp).dispatch("tools/call", buildJsonObject {
        put("name", name); put("arguments", args)
    }).jsonObject.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content

    private fun cli(args: List<String>): Pair<Int, String> = synchronized(RefactorKitCli::class.java) {
        val out = System.out; val err = System.err
        val bytes = ByteArrayOutputStream()
        PrintStream(bytes, true, Charsets.UTF_8).use { stream ->
            try {
                System.setOut(stream); System.setErr(stream)
                RefactorKitCli().run(args) to bytes.toString(Charsets.UTF_8)
            } finally { System.setOut(out); System.setErr(err) }
        }
    }

    private fun sdkFiles(): Map<Path, String> = externalSdk?.let(::filesAt).orEmpty()
    private fun filesAt(sdk: Path): Map<Path, String> = Files.walk(sdk).use { paths ->
        paths.filter { Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }.toList()
            .associate { sdk.relativize(it) to Files.readString(it) }
    }

    override fun close() {
        daemon?.close(); mcp?.close()
        additionalSdks.forEach { sdk -> Files.walk(sdk).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
        externalSdk?.let { sdk -> Files.walk(sdk).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
        root?.let { workspace -> Files.walk(workspace).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
}
