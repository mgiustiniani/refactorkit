package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.*
import org.refactorkit.cli.RefactorKitCli
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.zip.GZIPInputStream
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.daemon.DaemonSession
import org.refactorkit.mcp.McpSession
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.*

class CCompilerSearchSteps {
    private val fixture = CCompilerSearchFixture()
    @Given("the {string} C search fixture captures {string} and {string}")
    fun capture(surface: String, standard: String, condition: String) = fixture.capture(surface, standard, condition)
    @When("that surface searches the captured C declarations")
    fun search() = fixture.search()
    @Then("the C symbol search response is {string} with its declared evidence")
    fun result(expected: String) = fixture.assertResult(expected)
    @Then("the C symbol search preserves all project inputs and has no transaction")
    fun intact() = fixture.assertIntact()
    @When("that surface navigates the captured C {string} binding")
    fun navigate(binding: String) = fixture.navigate(binding)
    @Then("the C navigation response matches {string} with native source evidence")
    fun navigationResult(expected: String) = fixture.assertNavigation(expected)
    @After
    fun close() = fixture.close()
}

private class CCompilerSearchFixture : AutoCloseable {
    private var root: Path? = null
    private var cli = false
    private var sdkMode = false
    private var externalSdk: Path? = null
    private var sdkImages: Map<Path, String> = emptyMap()
    private var daemon: DaemonSession? = null
    private var mcp: McpSession? = null
    private var packaged: TypeScriptPackagedSurface? = null
    private var packagedRoute = ""
    private lateinit var condition: String
    private lateinit var sdk: Path
    private lateinit var snapshotHash: String
    private lateinit var images: Map<Path, String>
    private var all: JsonArray? = null
    private var values: JsonArray? = null
    private var error: String? = null
    private var code: Int? = null
    private var recordRead: JsonObject? = null
    private var scopedRead: JsonObject? = null
    private var prototypeRead: JsonObject? = null
    private var prototypeDeclarations: List<JsonObject> = emptyList()
    private var nativeChildrenBefore: Set<Long> = emptySet()

    fun capture(requestedSurface: String, standard: String, variant: String) {
        val sourceMcp = requestedSurface == "source MCP"
        require(!requestedSurface.startsWith("source ") || sourceMcp)
        val surface = requestedSurface.removePrefix("packaged ").removePrefix("source ")
        require(surface in setOf("daemon", "MCP", "CLI") && standard in setOf("c11", "c17"))
        sdkMode = variant.startsWith("SDK ")
        val forwardRecord = variant in setOf("source-forward-struct", "source-forward-union", "header-forward-struct", "header-forward-union",
            "source-incomplete-struct", "source-incomplete-union", "header-incomplete-struct", "header-incomplete-union")
        val scopedEnum = variant in setOf("scope-enum-block", "scope-enum-shadow", "scope-enum-nested")
        val scopedTypedef = variant in setOf("scope-typedef-block", "scope-typedef-shadow", "scope-typedef-nested",
            "scope-typedef-pointer-shadow", "scope-typedef-chain-shadow", "scope-typedef-repeated-alias", "scope-typedef-ordinary-shadow")
        val scoped = scopedTypedef || scopedEnum || variant in setOf("scope-block", "scope-shadow", "scope-forward-block")
        require(sdkMode || scoped || forwardRecord || variant in setOf("captured", "database changed", "missing TU", "macro declaration", "wrong hash", "navigation drift", "navigation drift after refresh", "unknown symbol", "source type family", "source member family", "prototype tags", "header prototype tags", "function parameter tags", "ordinary values", "prototype composed at-cap", "prototype composed over-cap"))
        cli = surface == "CLI"
        condition = variant
        val workspace = Files.createTempDirectory("rk-c-compiler-search-").also { root = it }
        sdk = Path.of(assertNotNull(System.getenv("REFACTORKIT_C_QUALIFICATION_TOOLCHAIN")))
        if (sourceMcp) {
            packagedRoute = surface
            packaged = TypeScriptPackagedSurface(Path.of(System.getProperty("java.home")), workspace,
                assertNotNull(System.getProperty("refactorkit.mcp.test.classpath")), sourceMcp = true)
        } else if (requestedSurface.startsWith("packaged ")) {
            packagedRoute = surface
            val candidate = Path.of(assertNotNull(System.getenv("REFACTORKIT_C_QUERY_CANDIDATE_ROOT"), "Packaged C queries require an explicit candidate"))
            packaged = TypeScriptPackagedSurface(candidate, workspace)
        }
        if (forwardRecord) recordRead = requireNotNull(CCompilerSearchSteps::class.java
            .getResourceAsStream("/record-navigation/native-fixture.json.gz")).use { stream ->
            GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject }
        }.getValue("modes").jsonObject.getValue(standard).jsonObject.getValue(variant).jsonObject
        if (scoped) scopedRead = requireNotNull(CCompilerSearchSteps::class.java
            .getResourceAsStream(when { scopedTypedef -> "/scoped-typedef-navigation/native-fixture.json.gz"; scopedEnum -> "/scoped-type-navigation/native-fixture.json.gz"; else -> "/scoped-record-navigation/native-fixture.json.gz" })).use { stream ->
            GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject }
        }.getValue("modes").jsonObject.getValue(standard).jsonObject.getValue(variant.removePrefix(when { scopedTypedef -> "scope-typedef-"; scopedEnum -> "scope-enum-"; else -> "scope-" })).jsonObject
        if (variant in setOf("prototype tags", "header prototype tags", "function parameter tags")) prototypeRead = requireNotNull(CCompilerSearchSteps::class.java
            .getResourceAsStream(when (variant) {
                "header prototype tags" -> "/header-prototype-navigation/native-fixture.json.gz"
                "function parameter tags" -> "/function-parameter-tag-navigation/type-uses.json.gz"
                else -> "/prototype-tag-navigation/native-fixture.json.gz"
            })).use { stream ->
            GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject }
        }.getValue("observationBytes").jsonPrimitive.content.let { raw ->
            Json.parseToJsonElement(raw).jsonObject.getValue("results").jsonArray.map { it.jsonObject }
                .single { it.getValue("standard").jsonPrimitive.content == standard }
        }
        if (variant.startsWith("prototype composed ")) prototypeRead = requireNotNull(CCompilerSearchSteps::class.java
            .getResourceAsStream("/prototype-composed-limit/$standard-${variant.removePrefix("prototype composed ")}.json.gz")).use { stream ->
            GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject }
        }
        nativeChildrenBefore = nativeChildren()
        val capturedRecord = recordRead ?: scopedRead ?: prototypeRead
        images = if (capturedRecord != null) capturedRecord.getValue("sources").jsonObject
            .map { (file, text) -> Path.of(file) to text.jsonPrimitive.content }.toMap()
        else if (variant == "ordinary values") Json.parseToJsonElement(requireNotNull(CCompilerSearchSteps::class.java
            .getResource("/ordinary-value-navigation/fixture-inputs.json")).readText()).jsonObject.getValue("sources").jsonObject
            .map { (file, text) -> Path.of(file) to text.jsonPrimitive.content }.toMap()
        else if (variant in setOf("source type family", "source member family")) {
            val material = Json.parseToJsonElement(requireNotNull(CCompilerSearchSteps::class.java
                .getResource("/source-type-navigation/native-fixture.json")).readText()).jsonObject
            material.getValue("modes").jsonObject.getValue(standard).jsonObject.getValue("sources").jsonObject
                .map { (file, text) -> Path.of(file) to text.jsonPrimitive.content }.toMap()
        } else linkedMapOf(
            Path.of("include/api.h") to "#pragma once\ntypedef int value_t;\nstruct value { int member; };\nunion payload { int amount; };\nenum stage { ORDER_PENDING = 1 };\nextern int value;\nvalue_t calculate(value_t input);\n",
            Path.of("src/api.c") to "#include \"api.h\"\nint value = 7;\nvalue_t calculate(value_t input) { int local = input; return local + value; }\n",
            Path.of("src/main.c") to "#include \"api.h\"\nint main(void) { struct value item = { .member = 1 }; union payload holder = { .amount = 2 }; if (holder.amount == 0) goto done; done: ; return calculate(item.member + holder.amount) + ORDER_PENDING; }\n",
        ).let { material -> if (variant == "macro declaration") material + (Path.of("src/api.c") to
            "#include \"api.h\"\n#define DECLARE(name) int name = 7;\nDECLARE(value)\nvalue_t calculate(value_t input) { return input + value; }\n") else material }
        if (sdkMode) {
            require(variant in setOf("SDK captured", "SDK stale id", "SDK missing input"))
            val sdkRoot = Files.createTempDirectory("rk-c-readonly-query-sdk-"); externalSdk = sdkRoot
            Files.createDirectories(sdkRoot.resolve("usr/include"))
            Files.writeString(sdkRoot.resolve("usr/include/api.h"), images.getValue(Path.of("include/api.h")))
            images = images.filterKeys { it != Path.of("include/api.h") }
            Files.createDirectories(workspace.resolve("include")); sdkImages = sdkFiles()
        }
        images.forEach { (file, text) -> Files.createDirectories(workspace.resolve(file).parent); Files.writeString(workspace.resolve(file), text) }
        val units = listOf("src/api.c", "src/main.c").let { if (variant == "missing TU") it.take(1) else it }
        val database = JsonArray(units.map { file -> buildJsonObject {
            put("directory", workspace.toString()); put("file", file)
            put("arguments", JsonArray((listOf(sdk.resolve("usr/bin/clang-22").toString(), "-std=$standard", "-Iinclude") +
                externalSdk?.let { listOf("--sysroot=$it") }.orEmpty() + listOf("-c", file)).map(::JsonPrimitive)))
        } }).toString()
        images = images + (Path.of("compile_commands.json") to database)
        Files.writeString(workspace.resolve("compile_commands.json"), database)
        if (packaged != null) {
            val wire = assertNotNull(packaged)
            if (surface == "MCP") {
                wire.dispatch("MCP", "initialize", buildJsonObject {
                    put("protocolVersion", "2024-11-05"); put("capabilities", buildJsonObject {})
                    put("clientInfo", buildJsonObject { put("name", "c-runtime-query-story"); put("version", "1") })
                })
                wire.notifyInitialized()
                val opened = wire.dispatch("MCP", "tools/call", buildJsonObject {
                    put("name", "project_scan"); put("arguments", buildJsonObject { put("root", workspace.toString()) })
                }).jsonObject
                val text = opened.getValue("content").jsonArray.joinToString("\n") { it.jsonObject.getValue("text").jsonPrimitive.content }
                snapshotHash = assertNotNull(Regex("Snapshot: ([0-9a-f]{64})").find(text)).groupValues[1]
            } else snapshotHash = wire.dispatch("daemon", "project.open", buildJsonObject { put("root", workspace.toString()) })
                .jsonObject.getValue("snapshotHash").jsonPrimitive.content
        } else if (cli) {
            DaemonSession().use { session ->
                val opened = session.dispatch("project.open", buildJsonObject { put("root", workspace.toString()) }).jsonObject
                snapshotHash = opened.getValue("snapshotHash").jsonPrimitive.content
            }
        } else if (surface == "daemon") {
            val opened = DaemonSession().also { daemon = it }.dispatch("project.open", buildJsonObject { put("root", workspace.toString()) }).jsonObject
            snapshotHash = opened.getValue("snapshotHash").jsonPrimitive.content
        } else {
            val opened = McpSession().also { mcp = it }.dispatch("tools/call", buildJsonObject {
                put("name", "project_scan"); put("arguments", buildJsonObject { put("root", workspace.toString()) })
            }).jsonObject
            val text = opened.getValue("content").jsonArray.joinToString("\n") { it.jsonObject.getValue("text").jsonPrimitive.content }
            snapshotHash = assertNotNull(Regex("Snapshot: ([0-9a-f]{64})").find(text)).groupValues[1]
        }
        if (variant == "database changed") {
            val text = database + "\n"; Files.writeString(workspace.resolve("compile_commands.json"), text)
            images = images + (Path.of("compile_commands.json") to text)
        }
    }

    fun search() {
        fun request(query: String): JsonArray? {
            val args = buildJsonObject {
                put("languageId", "c"); put("query", query)
                put("expectedSnapshotHash", if (condition == "wrong hash") "0".repeat(64) else snapshotHash)
                put("clang", sdk.resolve("usr/bin/clang-22").toString()); put("clangd", sdk.resolve("usr/bin/clangd").toString())
                put("clangFormat", sdk.resolve("usr/bin/clang-format").toString())
            }
            return call("search", args)?.jsonArray
        }
        all = request("")
        if (all != null && condition == "captured") values = request("value")
        if (all != null && condition == "prototype composed at-cap") values = request("opaque")
    }

    private var navigationBinding = ""
    private var selectedDeclaration: JsonObject? = null
    private var definition: JsonObject? = null
    private var references: JsonArray? = null
    private var referencesWithDeclarations: JsonArray? = null
    private var declarationsError: String? = null
    private var definitionError: String? = null
    private var referenceError: String? = null
    private var definitionCode: Int? = null
    private var referenceCode: Int? = null

    private fun call(operation: String, args: JsonObject): JsonElement? {
        error = null; code = null
        return try {
            if (cli) return cliCall(operation, args)
            if (packagedRoute == "daemon") return assertNotNull(packaged).dispatch("daemon", "symbol.$operation", args)
            daemon?.let { return it.dispatch("symbol.$operation", args) }
            val request = buildJsonObject { put("name", "symbol_$operation"); put("arguments", args) }
            val result = (packaged?.dispatch("MCP", "tools/call", request)
                ?: assertNotNull(mcp).dispatch("tools/call", request)).jsonObject
            val text = result.getValue("content").jsonArray.joinToString("\n") { it.jsonObject.getValue("text").jsonPrimitive.content }
            if (result["isError"]?.jsonPrimitive?.content == "true") { error = text; null } else Json.parseToJsonElement(text)
        } catch (failure: JsonRpcException) { error = failure.message; code = failure.code; null }
    }

    private fun cliCall(operation: String, args: JsonObject): JsonElement? {
        val keys = mapOf("expectedSnapshotHash" to "expected-snapshot-hash", "clangFormat" to "clang-format", "readOnlySdks" to "read-only-sdks")
        val command = listOf("c", if (operation == "search") "symbols" else operation, requireNotNull(root).toString()) +
            args.entries.filter { it.key != "languageId" }.flatMap { (key, value) ->
                if (key == "includeDeclaration") {
                    if (value.jsonPrimitive.boolean) listOf("--include-declaration") else emptyList()
                } else listOf("--" + (keys[key] ?: key), if (value is JsonPrimitive) value.content else value.toString())
            }
        packaged?.let { wire -> return Json.parseToJsonElement(wire.cli(command, 0)) }
        val previousOut = System.out; val previousErr = System.err
        val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
        val exit = try {
            PrintStream(out, true, Charsets.UTF_8).use { stdout ->
                PrintStream(err, true, Charsets.UTF_8).use { stderr ->
                    System.setOut(stdout); System.setErr(stderr)
                    RefactorKitCli().run(command)
                }
            }
        } finally { System.setOut(previousOut); System.setErr(previousErr) }
        if (exit != 0) { error = err.toString(Charsets.UTF_8); code = exit; return null }
        assertEquals("", err.toString(Charsets.UTF_8))
        return Json.parseToJsonElement(out.toString(Charsets.UTF_8))
    }

    private fun navigationArguments() = buildJsonObject {
        put("languageId", "c"); put("expectedSnapshotHash", snapshotHash)
        put("clang", sdk.resolve("usr/bin/clang-22").toString())
        put("clangd", sdk.resolve("usr/bin/clangd").toString())
        put("clangFormat", sdk.resolve("usr/bin/clang-format").toString())
        externalSdk?.let { root -> put("readOnlySdks", buildJsonArray { add(buildJsonObject {
            put("root", root.toString()); put("provenance", "fixture:readonly-query-sdk"); put("license", "LicenseRef-RefactorKitFixture")
        }) }) }
    }

    fun navigate(binding: String) {
        if (sdkMode) { navigationBinding = binding; navigateSdk(binding); return }
        require(binding in setOf("function", "typedef", "field", "parameter", "struct", "union", "enum", "local value", "global value", "static value", "enum value", "parameter value", "shadow alias", "shadow value", "shadow peer alias", "source struct field", "source union field", "peer struct field", "peer union field", "scoped struct", "scoped union", "scoped enum", "scoped typedef", "prototype first tag", "prototype other tag", "prototype global tag")); navigationBinding = binding
        val declarations = call("search", JsonObject(navigationArguments() + ("query" to JsonPrimitive(""))))?.jsonArray
        if (declarations == null) {
            definitionError = error; referenceError = error
            definitionCode = code; referenceCode = code
            return
        }
        if (condition in setOf("prototype tags", "header prototype tags", "function parameter tags")) { navigatePrototype(declarations, binding); return }
        if (condition == "ordinary values") { navigateOrdinaryValues(declarations, binding); return }
        if (condition == "scope-typedef-ordinary-shadow") { navigateOrdinaryShadow(declarations, binding); return }
        val sourceMember = condition == "source member family"
        val sourceFamily = condition == "source type family"
        val peer = binding.startsWith("peer ")
        val record = recordRead
        val scoped = scopedRead != null
        if (scoped) require(binding in setOf("scoped struct", "scoped union", "scoped enum", "scoped typedef"))
        val keyword = when (binding) { "scoped struct" -> "struct"; "scoped enum" -> "enum"; "scoped typedef" -> "typedef"; else -> "union" }
        val name = if (scoped) { when (keyword) { "struct" -> "meter"; "enum" -> "stage"; "typedef" -> "count_t"; else -> "payload" } } else if (record != null) record.getValue("name").jsonPrimitive.content else if (sourceMember) {
            when (binding) { "source struct field" -> "member"; "source union field" -> "amount";
                "peer struct field", "peer union field" -> "other"; else -> error("Unknown source member: $binding") }
        } else if (sourceFamily) when (binding) {
            "struct" -> "meter"; "union" -> "payload"; "enum" -> "phase"; "typedef" -> "scalar_t"
            else -> error("Unknown source type binding: $binding")
        } else when (binding) { "function" -> "calculate"; "typedef" -> "value_t"; "field" -> "member"; else -> "input" }
        val kind = if (scoped) keyword.uppercase() else if (sourceMember) "FIELD" else when (binding) { "function" -> "FUNCTION"; "typedef" -> "TYPEDEF"; "field" -> "FIELD";
            "struct" -> "STRUCT"; "union" -> "UNION"; "enum" -> "ENUM"; else -> "PARAMETER" }
        val file = if (scoped) "src/api.c" else if (record != null) { if (record.getValue("profile").jsonPrimitive.content == "header") "include/api.h" else "src/api.c" }
            else if (peer) "src/main.c" else if (sourceMember || sourceFamily || binding == "parameter") "src/api.c" else "include/api.h"
        val tu = if (scoped) "src/api.c" else if (record != null) "src/api.c" else if (peer) "src/main.c" else if (sourceMember || sourceFamily || binding == "parameter") "src/api.c" else "src/main.c"
        val scopedPoint = if (scoped) {
            val indent = if (condition in setOf("scope-forward-block", "scope-enum-nested", "scope-typedef-nested")) "        " else "    "
            val marker = if (keyword == "typedef") scopedTypedefDeclarationMarker()
                else "$indent$keyword $name" + if (condition == "scope-forward-block") ";" else " {"
            val lines = images.getValue(Path.of(file)).lines()
            val line = lines.indexOfFirst { it.contains(marker) }.also { assertTrue(it >= 0) }
            line + 1 to if (keyword == "typedef") lines[line].indexOf(name)
                else (lines[line].indexOf("$keyword $name") + keyword.length + 1)
        } else null
        val selected = declarations.map { it.jsonObject }.single { it.getValue("name").jsonPrimitive.content == name &&
            it.getValue("cKind").jsonPrimitive.content == kind && it.getValue("file").jsonPrimitive.content == file &&
            it.getValue("translationUnit").jsonPrimitive.content == tu &&
            (record == null || it.getValue("line").jsonPrimitive.int == 2) &&
            (scopedPoint == null || (it.getValue("line").jsonPrimitive.int == scopedPoint.first && it.getValue("character").jsonPrimitive.int == scopedPoint.second)) &&
            (!sourceMember || it.getValue("line").jsonPrimitive.int ==
                if (binding.endsWith("struct field")) 3 else 4) }
        selectedDeclaration = selected
        if (scoped) {
            val peerDeclaration = declarations.map { it.jsonObject }.single { it.getValue("name").jsonPrimitive.content == name &&
                it.getValue("cKind").jsonPrimitive.content == kind && it.getValue("file").jsonPrimitive.content == "src/main.c" &&
                it.getValue("translationUnit").jsonPrimitive.content == "src/main.c" }
            assertNotEquals(selected.getValue("id"), peerDeclaration.getValue("id"))
            if (condition !in setOf("scope-block", "scope-enum-block", "scope-typedef-block")) {
                val outerLine = if (keyword == "typedef") images.getValue(Path.of(file)).lines()
                    .indexOfFirst { it.startsWith("typedef ") && it.endsWith(" $name;") }.also { assertTrue(it >= 0) } + 1
                    else if (keyword in setOf("struct", "enum")) 2 else 3
                val outer = declarations.map { it.jsonObject }.single { it.getValue("name").jsonPrimitive.content == name &&
                    it.getValue("cKind").jsonPrimitive.content == kind && it.getValue("file").jsonPrimitive.content == file &&
                    it.getValue("translationUnit").jsonPrimitive.content == tu && it.getValue("line").jsonPrimitive.int == outerLine }
                assertNotEquals(selected.getValue("id"), outer.getValue("id"))
            }
        }
        if (sourceFamily) {
            val unrelated = declarations.map { it.jsonObject }.single {
                it.getValue("name").jsonPrimitive.content == name &&
                    it.getValue("cKind").jsonPrimitive.content == kind &&
                    it.getValue("file").jsonPrimitive.content == "src/main.c" &&
                    it.getValue("translationUnit").jsonPrimitive.content == "src/main.c"
            }
            assertNotEquals(selected.getValue("id"), unrelated.getValue("id"))
        }
        if (peer) {
            val sibling = declarations.map { it.jsonObject }.single {
                it.getValue("name").jsonPrimitive.content == name && it.getValue("file").jsonPrimitive.content == file &&
                    it.getValue("cKind").jsonPrimitive.content == "FIELD" &&
                    it.getValue("line").jsonPrimitive.int == if (binding.endsWith("struct field")) 4 else 3
            }
            assertNotEquals(selected.getValue("id"), sibling.getValue("id"))
        }
        val id = if (condition == "unknown symbol") "c-decl:" + "0".repeat(64) else selected.getValue("id").jsonPrimitive.content
        if (condition in setOf("navigation drift", "navigation drift after refresh")) {
            val db = Path.of("compile_commands.json")
            val text = images.getValue(db) + "\n"
            Files.writeString(requireNotNull(root).resolve(db), text)
            images = images + (db to text)
            if (condition == "navigation drift after refresh") {
                val refreshed = assertNotNull(packaged).dispatch("daemon", "workspace.refresh", buildJsonObject {}).jsonObject
                assertEquals("ready", refreshed.getValue("status").jsonPrimitive.content)
                assertNotEquals(snapshotHash, refreshed.getValue("snapshotHash").jsonPrimitive.content)
            }
        }
        val args = JsonObject(navigationArguments() + ("symbol" to JsonPrimitive(id)))
        definition = call("definition", args)?.jsonObject; definitionError = error; definitionCode = code
        references = call("references", args)?.jsonArray; referenceError = error; referenceCode = code
        if (scoped || record?.getValue("hasCompleteDefinition")?.jsonPrimitive?.boolean == false) {
            referencesWithDeclarations = call("references", JsonObject(args + ("includeDeclaration" to JsonPrimitive(true))))?.jsonArray
            declarationsError = error
        }
    }

    fun assertNavigation(expected: String) {
        if (sdkMode) { assertSdkNavigation(expected); return }
        if (expected == "ordinary value family") { assertOrdinaryValueNavigation(); return }
        if (expected == "ordinary shadow family") { assertOrdinaryShadowNavigation(); return }
        if (expected == "prototype tag family") { assertPrototypeNavigation(); return }
        if (expected in setOf("scoped record definition and references", "scoped enum definition and references", "scoped typedef definition and references")) { assertScopedRecordNavigation(); return }
        if (expected == "incomplete record references without definition") {
            assertIncompleteRecordNavigation()
            return
        }
        if (expected == "forward record definition and references") {
            assertForwardRecordNavigation()
            return
        }
        if (expected == "native definition and references") {
            assertNull(definitionError); assertNull(referenceError)
            val found = assertNotNull(definition)
            assertEquals(if (condition == "source member family" && navigationBinding.startsWith("peer ")) "src/main.c"
                else if (condition in setOf("source type family", "source member family") || navigationBinding in setOf("function", "parameter")) "src/api.c" else "include/api.h", found.getValue("file").jsonPrimitive.content)
            val uses = assertNotNull(references).map { it.jsonObject }
            assertEquals(if (condition == "source type family") 1 else when (navigationBinding) { "function", "parameter" -> 1; "typedef" -> 4; else -> 2 }, uses.size)
            if (condition == "source type family") {
                val selected = assertNotNull(selectedDeclaration)
                for (key in listOf("id", "file", "line", "character", "endLine", "endCharacter"))
                    assertEquals(selected.getValue(key), found.getValue(key), key)
                val name = selected.getValue("name").jsonPrimitive.content
                val line = when (navigationBinding) { "struct" -> 7; "union" -> 8; "enum" -> 9; "typedef" -> 6
                    else -> error("Unknown source type binding: $navigationBinding") }
                val start = images.getValue(Path.of("src/api.c")).lines()[line].indexOf(name).also { assertTrue(it >= 0) }
                assertEquals(setOf(Triple(line + 1, start, start + name.length)), uses.map {
                    Triple(it.getValue("line").jsonPrimitive.int, it.getValue("character").jsonPrimitive.int,
                        it.getValue("endCharacter").jsonPrimitive.int)
                }.toSet())
                (listOf(found) + uses).forEach { item ->
                    assertEquals("src/api.c", item.getValue("file").jsonPrimitive.content)
                    assertEquals(listOf("src/api.c"), item.getValue("translationUnits").jsonArray.map { it.jsonPrimitive.content })
                }
            }
            if (condition == "source member family") {
                val selected = assertNotNull(selectedDeclaration)
                for (key in listOf("id", "file", "line", "character", "endLine", "endCharacter"))
                    assertEquals(selected.getValue(key), found.getValue(key), key)
                val peer = navigationBinding.startsWith("peer ")
                val struct = navigationBinding.endsWith("struct field")
                val name = if (peer) "other" else if (struct) "member" else "amount"
                val file = if (peer) "src/main.c" else "src/api.c"
                val markers = if (struct) listOf(7 to ".$name", 8 to "item.$name") else listOf(8 to ".$name", 10 to "box.$name")
                val expectedSites = markers.map { (line, marker) ->
                    val start = images.getValue(Path.of(file)).lines()[line].indexOf(marker).also { assertTrue(it >= 0) } + marker.length - name.length
                    Triple(line + 1, start, start + name.length)
                }.toSet()
                assertEquals(expectedSites, uses.map { Triple(it.getValue("line").jsonPrimitive.int,
                    it.getValue("character").jsonPrimitive.int, it.getValue("endCharacter").jsonPrimitive.int) }.toSet())
                (listOf(found) + uses).forEach { item ->
                    assertEquals(file, item.getValue("file").jsonPrimitive.content)
                    assertEquals(listOf(file), item.getValue("translationUnits").jsonArray.map { it.jsonPrimitive.content })
                }
            }
            (listOf(found) + uses).forEach { item ->
                assertEquals("compiler", item.getValue("evidence").jsonPrimitive.content)
                assertEquals("captured-family", item.getValue("completeness").jsonPrimitive.content)
                assertEquals(snapshotHash, item.getValue("snapshotHash").jsonPrimitive.content)
                assertEquals("31a40dc31f3c15a47aa119aa148f339bf363d8f61202ddbdacbd3f24b71ba113", item.getValue("compilerSha256").jsonPrimitive.content)
                val file = Path.of(item.getValue("file").jsonPrimitive.content)
                val line = item.getValue("line").jsonPrimitive.int - 1
                val start = item.getValue("character").jsonPrimitive.int
                val end = item.getValue("endCharacter").jsonPrimitive.int
                assertEquals(item.getValue("name").jsonPrimitive.content, images.getValue(file).lines()[line].substring(start, end))
            }
        } else {
            assertNull(definition); assertNull(references)
            assertTrue(assertNotNull(definitionError).contains(expected), definitionError)
            assertTrue(assertNotNull(referenceError).contains(expected), referenceError)
            if (daemon != null || packagedRoute == "daemon") {
                val expectedCode = when {
                    expected.endsWith(".symbolNotFound") -> JsonRpcErrorCodes.SYMBOL_NOT_FOUND
                    expected.endsWith(".snapshotChanged") || expected.endsWith(".snapshotStale") -> JsonRpcErrorCodes.SNAPSHOT_CHANGED
                    else -> JsonRpcErrorCodes.PLAN_REFUSED
                }
                assertEquals(expectedCode, definitionCode)
                assertEquals(expectedCode, referenceCode)
            }
        }
    }

    private fun navigateOrdinaryShadow(declarations: JsonArray, binding: String) {
        require(binding in setOf("shadow alias", "shadow value", "shadow peer alias"))
        all = declarations
        val file = if (binding == "shadow peer alias") "src/main.c" else "src/api.c"
        val kind = if (binding == "shadow value") "VARIABLE" else "TYPEDEF"
        val selected = declarations.map { it.jsonObject }.singleOrNull {
            it.getValue("name").jsonPrimitive.content == "count_t" && it.getValue("cKind").jsonPrimitive.content == kind &&
                it.getValue("file").jsonPrimitive.content == file && it.getValue("translationUnit").jsonPrimitive.content == file
        }
        selectedDeclaration = selected
        if (selected == null) return
        val args = JsonObject(navigationArguments() + ("symbol" to selected.getValue("id")))
        definition = call("definition", args)?.jsonObject; definitionError = error; definitionCode = code
        references = call("references", args)?.jsonArray; referenceError = error; referenceCode = code
        referencesWithDeclarations = call("references", JsonObject(args + ("includeDeclaration" to JsonPrimitive(true))))?.jsonArray
        declarationsError = error
    }

    private fun assertOrdinaryShadowNavigation() {
        assertNull(definitionError); assertNull(referenceError); assertNull(declarationsError)
        val selected = assertNotNull(selectedDeclaration)
        val file = if (navigationBinding == "shadow peer alias") "src/main.c" else "src/api.c"
        val kind = if (navigationBinding == "shadow value") "VARIABLE" else "TYPEDEF"
        fun point(marker: String): List<Any> {
            val text = images.getValue(Path.of(file))
            val start = text.indexOf(marker).also { assertTrue(it >= 0, marker) } + marker.indexOf("count_t").also { assertTrue(it >= 0) }
            val prefix = text.substring(0, start)
            val line = prefix.count { it == '\n' } + 1; val column = start - (prefix.lastIndexOf('\n') + 1)
            return listOf(file, line, column, column + "count_t".length)
        }
        val expectedDefinition = point(when (navigationBinding) {
            "shadow alias" -> "typedef int count_t;"; "shadow value" -> "int count_t = 3"; else -> "typedef long count_t;"
        })
        val expectedUses = when (navigationBinding) {
            "shadow alias" -> setOf(point("count_t value = 2"), point("count_t value = 4"))
            "shadow value" -> setOf(point("return count_t"))
            else -> setOf(point("count_t value = 7"))
        }
        val found = assertNotNull(definition)
        val uses = assertNotNull(references).map { it.jsonObject }
        val declared = assertNotNull(referencesWithDeclarations).map { it.jsonObject }
        assertEquals(expectedDefinition, prototypePoint(found))
        assertEquals(selected.getValue("id"), found.getValue("id"))
        assertEquals(selected.getValue("id"), found.getValue("requestedSymbolId"))
        assertEquals(expectedUses.size, uses.size); assertEquals(expectedUses, uses.map(::prototypePoint).toSet())
        assertEquals(expectedUses.size + 1, declared.size)
        assertEquals(expectedUses + setOf(expectedDefinition), declared.map(::prototypePoint).toSet())
        val identities = assertNotNull(all).map { it.jsonObject }.filter { it.getValue("name").jsonPrimitive.content == "count_t" }
        assertEquals(3, identities.size); assertEquals(3, identities.map { it.getValue("id") }.toSet().size)
        assertEquals(2, identities.count { it.getValue("cKind").jsonPrimitive.content == "TYPEDEF" })
        assertEquals(1, identities.count { it.getValue("cKind").jsonPrimitive.content == "VARIABLE" })
        assertEquals(setOf("src/api.c", "src/main.c"), identities.map { it.getValue("file").jsonPrimitive.content }.toSet())
        listOf(uses, declared).forEach { rows -> rows.forEach { row ->
            assertEquals(selected.getValue("id"), row.getValue("symbol"))
            assertEquals(rows.size, row.getValue("queryTotal").jsonPrimitive.int)
            assertFalse(row.getValue("queryTruncated").jsonPrimitive.boolean)
        } }
        (listOf(found) + uses + declared).forEach { row ->
            assertEquals(listOf(file), row.getValue("translationUnits").jsonArray.map { it.jsonPrimitive.content })
        }
        identities.forEach { row ->
            assertEquals("ORDINARY", row.getValue("lookupSpace").jsonPrimitive.content)
            assertEquals("NONE", row.getValue("linkage").jsonPrimitive.content)
        }
        (listOf(selected, found) + uses + declared).forEach { row ->
            assertEquals(snapshotHash, row.getValue("snapshotHash").jsonPrimitive.content)
            assertEquals("compiler", row.getValue("evidence").jsonPrimitive.content)
            assertEquals("ORDINARY", row.getValue("lookupSpace").jsonPrimitive.content)
            assertEquals("count_t", row.getValue("name").jsonPrimitive.content)
            assertEquals(kind, row.getValue("cKind").jsonPrimitive.content)
            assertEquals(file, row.getValue("file").jsonPrimitive.content)
            assertEquals("31a40dc31f3c15a47aa119aa148f339bf363d8f61202ddbdacbd3f24b71ba113", row.getValue("compilerSha256").jsonPrimitive.content)
            val line = row.getValue("line").jsonPrimitive.int - 1
            assertEquals(line + 1, row.getValue("endLine").jsonPrimitive.int)
            assertEquals("count_t", images.getValue(Path.of(file)).lines()[line].substring(
                row.getValue("character").jsonPrimitive.int, row.getValue("endCharacter").jsonPrimitive.int))
        }
    }

    private fun navigateOrdinaryValues(declarations: JsonArray, binding: String) {
        require(binding in setOf("local value", "global value", "static value", "enum value", "parameter value"))
        all = declarations
        val name = when (binding) { "local value" -> "local"; "global value", "parameter value" -> "value"; "enum value" -> "READY"; else -> "hidden" }
        val file = if (binding in setOf("global value", "enum value")) "include/api.h" else "src/api.c"
        val kind = when (binding) { "enum value" -> "ENUMERATOR"; "parameter value" -> "PARAMETER"; else -> "VARIABLE" }
        val selected = declarations.map { it.jsonObject }.singleOrNull {
            it.getValue("name").jsonPrimitive.content == name && it.getValue("cKind").jsonPrimitive.content == kind &&
                it.getValue("file").jsonPrimitive.content == file && it.getValue("translationUnit").jsonPrimitive.content == "src/api.c"
        }
        selectedDeclaration = selected
        if (selected == null) return
        val args = JsonObject(navigationArguments() + ("symbol" to selected.getValue("id")))
        definition = call("definition", args)?.jsonObject; definitionError = error; definitionCode = code
        references = call("references", args)?.jsonArray; referenceError = error; referenceCode = code
        referencesWithDeclarations = call("references", JsonObject(args + ("includeDeclaration" to JsonPrimitive(true))))?.jsonArray
        declarationsError = error
    }

    private fun assertOrdinaryValueNavigation() {
        assertNull(definitionError); assertNull(referenceError); assertNull(declarationsError)
        val selected = assertNotNull(selectedDeclaration)
        val name = selected.getValue("name").jsonPrimitive.content
        val found = assertNotNull(definition)
        val uses = assertNotNull(references).map { it.jsonObject }
        val declared = assertNotNull(referencesWithDeclarations).map { it.jsonObject }
        fun point(file: String, marker: String): List<Any> {
            val text = images.getValue(Path.of(file))
            val start = text.indexOf(marker).also { assertTrue(it >= 0, marker) } + marker.indexOf(name).also { assertTrue(it >= 0) }
            val prefix = text.substring(0, start)
            val line = prefix.count { it == '\n' } + 1; val column = start - (prefix.lastIndexOf('\n') + 1)
            return listOf(file, line, column, column + name.length)
        }
        val expectedDefinition = point(if (navigationBinding == "enum value") "include/api.h" else "src/api.c", when (navigationBinding) {
            "local value" -> "int local = value"; "global value" -> "int value = 7"
            "enum value" -> "enum state { READY"; "parameter value" -> "int compute(int value)"
            else -> "static int hidden = 3"
        })
        val expectedUses = when (navigationBinding) {
            "local value" -> setOf(point("src/api.c", "local += value"), point("src/api.c", "return local"))
            "global value" -> setOf(point("src/main.c", "+ value + READY"))
            "enum value" -> setOf(point("src/api.c", "+ READY"), point("src/main.c", "+ READY"))
            "parameter value" -> setOf(point("src/api.c", "int local = value"))
            else -> setOf(point("src/api.c", "+ hidden + READY"))
        }
        val expectedDeclarations = if (navigationBinding == "global value")
            setOf(point("include/api.h", "extern int value"), expectedDefinition) else setOf(expectedDefinition)
        assertEquals(expectedDefinition, prototypePoint(found))
        assertEquals(selected.getValue("id"), found.getValue("requestedSymbolId"))
        if (navigationBinding == "global value") assertNotEquals(selected.getValue("id"), found.getValue("id"))
        else assertEquals(selected.getValue("id"), found.getValue("id"))
        assertEquals(expectedUses.size, uses.size); assertEquals(expectedUses, uses.map(::prototypePoint).toSet())
        assertEquals(expectedUses + expectedDeclarations, declared.map(::prototypePoint).toSet())
        assertEquals(expectedUses.size + expectedDeclarations.size, declared.size)
        assertEquals(when (navigationBinding) { "local value", "enum value", "parameter value" -> "NONE"; "global value" -> "EXTERNAL"; else -> "INTERNAL" }, selected.getValue("linkage").jsonPrimitive.content)
        val variables = assertNotNull(all).map { it.jsonObject }.filter { it.getValue("cKind").jsonPrimitive.content == "VARIABLE" }
        for (privateName in listOf("hidden", "local")) {
            val separate = variables.filter { it.getValue("name").jsonPrimitive.content == privateName }
            assertEquals(2, separate.size); assertEquals(2, separate.map { it.getValue("id") }.toSet().size)
            assertEquals(setOf("src/api.c", "src/main.c"), separate.map { it.getValue("file").jsonPrimitive.content }.toSet())
        }
        listOf(uses, declared).forEach { rows -> rows.forEach { row ->
            assertEquals(selected.getValue("id"), row.getValue("symbol"))
            assertEquals(rows.size, row.getValue("queryTotal").jsonPrimitive.int)
            assertFalse(row.getValue("queryTruncated").jsonPrimitive.boolean)
        } }
        (listOf(found) + uses + declared).forEach { row ->
            val file = row.getValue("file").jsonPrimitive.content
            val units = if (file == "include/api.h") listOf("src/api.c", "src/main.c") else listOf(file)
            assertEquals(units, row.getValue("translationUnits").jsonArray.map { it.jsonPrimitive.content })
        }
        (listOf(selected, found) + uses + declared).forEach { row ->
            assertEquals(snapshotHash, row.getValue("snapshotHash").jsonPrimitive.content)
            assertEquals("compiler", row.getValue("evidence").jsonPrimitive.content)
            assertEquals("ORDINARY", row.getValue("lookupSpace").jsonPrimitive.content)
            assertEquals("31a40dc31f3c15a47aa119aa148f339bf363d8f61202ddbdacbd3f24b71ba113", row.getValue("compilerSha256").jsonPrimitive.content)
            val file = row.getValue("file").jsonPrimitive.content
            val line = row.getValue("line").jsonPrimitive.int - 1
            assertEquals(line + 1, row.getValue("endLine").jsonPrimitive.int)
            assertEquals(name, images.getValue(Path.of(file)).lines()[line].substring(
                row.getValue("character").jsonPrimitive.int, row.getValue("endCharacter").jsonPrimitive.int))
        }
    }

    private fun prototypePoint(file: String, marker: String): List<Any> {
        val source = images.getValue(Path.of(file))
        val start = source.indexOf(marker).also { assertTrue(it >= 0, marker) } + marker.indexOf("opaque")
        val prefix = source.substring(0, start)
        val line = prefix.count { it == '\n' } + 1
        val column = start - (prefix.lastIndexOf('\n') + 1)
        return listOf(file, line, column, column + "opaque".length)
    }

    private fun prototypePoint(value: JsonObject): List<Any> = listOf(value.getValue("file").jsonPrimitive.content,
        value.getValue("line").jsonPrimitive.int, value.getValue("character").jsonPrimitive.int,
        value.getValue("endCharacter").jsonPrimitive.int)

    private fun prototypeMarker() = when (navigationBinding) {
        "prototype first tag" -> "int inspect(struct opaque *first"
        "prototype other tag" -> "int inspect_other(struct opaque *third)"
        "prototype global tag" -> "struct opaque { int later; }"
        else -> error("Unknown prototype binding: $navigationBinding")
    }

    private fun navigatePrototype(declarations: JsonArray, binding: String) {
        require(binding in setOf("prototype first tag", "prototype other tag", "prototype global tag"))
        prototypeDeclarations = declarations.map { it.jsonObject }.filter {
            it.getValue("name").jsonPrimitive.content == "opaque" && it.getValue("cKind").jsonPrimitive.content == "STRUCT"
        }
        val point = prototypePoint(if (condition == "header prototype tags") "include/api.h" else "src/api.c", prototypeMarker())
        // Missing native declarations are product observations asserted by Then, not fixture exceptions.
        val selected = prototypeDeclarations.singleOrNull { prototypePoint(it) == point &&
            it.getValue("translationUnit").jsonPrimitive.content == "src/api.c" }
        selectedDeclaration = selected
        if (selected == null) return
        val args = JsonObject(navigationArguments() + ("symbol" to selected.getValue("id")))
        definition = call("definition", args)?.jsonObject; definitionError = error; definitionCode = code
        references = call("references", args)?.jsonArray; referenceError = error; referenceCode = code
        referencesWithDeclarations = call("references", JsonObject(args + ("includeDeclaration" to JsonPrimitive(true))))?.jsonArray
        declarationsError = error
    }

    private fun assertPrototypeNavigation() {
        assertNotNull(prototypeRead)
        val header = condition == "header prototype tags"
        val prototypeFile = if (header) "include/api.h" else "src/api.c"
        assertEquals(if (header) 6 else 4, prototypeDeclarations.size, "Native index retains prototype identities per original TU")
        assertEquals(prototypeDeclarations.size, prototypeDeclarations.map { it.getValue("id") }.toSet().size)
        if (header) {
            val owned = prototypeDeclarations.filter { it.getValue("file").jsonPrimitive.content == prototypeFile }
            assertEquals(mapOf("src/api.c" to 2, "src/main.c" to 2), owned.groupingBy { it.getValue("translationUnit").jsonPrimitive.content }.eachCount())
        }
        val expectedDeclarations = setOf(prototypePoint(prototypeFile, "int inspect(struct opaque *first"),
            prototypePoint(prototypeFile, "int inspect_other(struct opaque *third)"),
            prototypePoint("src/api.c", "struct opaque { int later; }"),
            prototypePoint("src/main.c", "struct opaque { double unrelated; }"))
        assertEquals(expectedDeclarations, prototypeDeclarations.map(::prototypePoint).toSet())
        val selected = assertNotNull(selectedDeclaration)
        val declarationPoint = prototypePoint(prototypeFile, prototypeMarker())
        assertEquals(declarationPoint, prototypePoint(selected))
        val global = navigationBinding == "prototype global tag"
        if (global) {
            assertNull(definitionError)
            val found = assertNotNull(definition)
            assertEquals(declarationPoint, prototypePoint(found))
            assertEquals(selected.getValue("id"), found.getValue("requestedSymbolId"))
            assertEquals(selected.getValue("id"), found.getValue("id"))
        } else {
            assertNull(definition)
            assertTrue(assertNotNull(definitionError).contains("c.navigation.definitionUnavailable"), definitionError)
            if (daemon != null) assertEquals(JsonRpcErrorCodes.PLAN_REFUSED, definitionCode)
        }
        assertNull(referenceError); assertNull(declarationsError)
        val expectedUses = when (navigationBinding) {
            "prototype first tag" -> if (condition == "function parameter tags") setOf(
                prototypePoint(prototypeFile, "struct opaque *second"),
                prototypePoint(prototypeFile, "struct opaque *local"),
                prototypePoint(prototypeFile, "(struct opaque *)local"),
                prototypePoint(prototypeFile, "sizeof(struct opaque *)"))
                else setOf(prototypePoint(prototypeFile, "struct opaque *second"))
            "prototype other tag" -> emptySet()
            else -> setOf(prototypePoint("src/api.c", "struct opaque item"))
        }
        val uses = assertNotNull(references).map { it.jsonObject }
        val declared = assertNotNull(referencesWithDeclarations).map { it.jsonObject }
        assertEquals(expectedUses.size, uses.size)
        assertEquals(expectedUses, uses.map(::prototypePoint).toSet())
        assertEquals(expectedUses + setOf(declarationPoint), declared.map(::prototypePoint).toSet())
        assertEquals(expectedUses.size + 1, declared.size)
        listOf(uses, declared).forEach { rows -> rows.forEach { row ->
            assertEquals(selected.getValue("id"), row.getValue("symbol"))
            assertEquals(rows.size, row.getValue("queryTotal").jsonPrimitive.int)
            assertFalse(row.getValue("queryTruncated").jsonPrimitive.boolean)
            assertEquals(listOf("src/api.c"), row.getValue("translationUnits").jsonArray.map { it.jsonPrimitive.content })
        } }
        (prototypeDeclarations + uses + declared + listOfNotNull(definition)).forEach { row ->
            assertEquals(snapshotHash, row.getValue("snapshotHash").jsonPrimitive.content)
            assertEquals("compiler", row.getValue("evidence").jsonPrimitive.content)
            assertEquals("TAG", row.getValue("lookupSpace").jsonPrimitive.content)
            if (row.getValue("file").jsonPrimitive.content == "include/api.h")
                assertTrue(row.getValue("translationUnit").jsonPrimitive.content in setOf("src/api.c", "src/main.c"))
            else assertEquals(row.getValue("file"), row.getValue("translationUnit"))
            assertEquals("clangd.symbolInfo.v22", row.getValue("supplementalBindingProviderId").jsonPrimitive.content)
            assertEquals("22.1.8", row.getValue("clangdVersion").jsonPrimitive.content)
            assertEquals("aacb4651e1886b98c5413369fc1789f4ca2d4fa5b94fdf0de4f48c98a52d4324", row.getValue("clangdSha256").jsonPrimitive.content)
            assertEquals("31a40dc31f3c15a47aa119aa148f339bf363d8f61202ddbdacbd3f24b71ba113", row.getValue("compilerSha256").jsonPrimitive.content)
            val point = prototypePoint(row)
            val file = Path.of(point[0] as String); val line = point[1] as Int; val column = point[2] as Int
            assertEquals("opaque", images.getValue(file).lines()[line - 1].substring(column, column + 6))
            assertEquals(row.getValue("line"), row.getValue("endLine"))
        }
    }

    private fun nativeChildren(): Set<Long> = ProcessHandle.current().descendants().use { children ->
        children.filter { it.info().command().orElse("").startsWith(sdk.toString()) }.map { it.pid() }.toList().toSet()
    }

    private fun scopedTypedefDeclarationMarker(): String = when (condition) {
        "scope-typedef-pointer-shadow" -> "    typedef int *count_t;"
        "scope-typedef-chain-shadow" -> "    typedef base_t count_t;"
        "scope-typedef-nested" -> "        typedef int count_t;"
        else -> "    typedef int count_t;"
    }

    private fun assertScopedRecordNavigation() {
        assertNotNull(scopedRead)
        assertNull(definitionError); assertNull(referenceError); assertNull(declarationsError)
        val selected = assertNotNull(selectedDeclaration)
        val name = selected.getValue("name").jsonPrimitive.content
        val keyword = when (navigationBinding) { "scoped struct" -> "struct"; "scoped enum" -> "enum"; "scoped typedef" -> "typedef"; else -> "union" }
        val forward = condition == "scope-forward-block"
        val indent = if (forward || condition in setOf("scope-enum-nested", "scope-typedef-nested")) "        " else "    "
        val source = images.getValue(Path.of("src/api.c"))
        fun expected(marker: String, occurrence: Int = 0): List<Any> {
            var at = source.indexOf(marker).also { assertTrue(it >= 0, marker) }
            repeat(occurrence) { at = source.indexOf(marker, at + marker.length).also { assertTrue(it >= 0, marker) } }
            val offset = at + marker.indexOf(name)
            val prefix = source.substring(0, offset)
            val line = prefix.count { it == '\n' } + 1
            val start = offset - (prefix.lastIndexOf('\n') + 1)
            return listOf("src/api.c", line, start, start + name.length)
        }
        fun point(item: JsonObject): List<Any> = listOf(item.getValue("file").jsonPrimitive.content,
            item.getValue("line").jsonPrimitive.int, item.getValue("character").jsonPrimitive.int,
            item.getValue("endCharacter").jsonPrimitive.int)
        val definitionPoint = if (keyword == "typedef") expected(scopedTypedefDeclarationMarker()) else expected("$indent$keyword $name {")
        val declarationPoint = if (keyword == "typedef") definitionPoint else expected("$indent$keyword $name" + if (forward) ";" else " {")
        val found = assertNotNull(definition)
        assertEquals(definitionPoint, point(found))
        assertEquals(selected.getValue("id"), found.getValue("requestedSymbolId"))
        if (forward) assertNotEquals(selected.getValue("id"), found.getValue("id")) else assertEquals(selected.getValue("id"), found.getValue("id"))
        val useMarkers = if (keyword == "typedef") listOf("$indent$name value = " + when (condition) {
            "scope-typedef-pointer-shadow" -> "&number"; "scope-typedef-nested" -> "4"; else -> "2"
        })
            else if (keyword == "enum") listOf("${indent}enum $name state = READY")
            else if (forward) listOf("$indent$keyword $name *before", "$indent$keyword $name " + if (keyword == "struct") "item" else "box")
            else listOf("$indent$keyword $name " + if (keyword == "struct") "item" else "box")
        val expectedUses = useMarkers.map { expected(it) }.toSet()
        val uses = assertNotNull(references).map { it.jsonObject }
        val declared = assertNotNull(referencesWithDeclarations).map { it.jsonObject }
        assertEquals(if (forward) 2 else 1, uses.size)
        assertEquals(expectedUses, uses.map(::point).toSet())
        val familyDeclarations = if (condition == "scope-typedef-repeated-alias")
            setOf(definitionPoint, expected(scopedTypedefDeclarationMarker(), 1)) else setOf(declarationPoint, definitionPoint)
        assertEquals(expectedUses + familyDeclarations, declared.map(::point).toSet())
        assertEquals(expectedUses.size + familyDeclarations.size, declared.size)
        (listOf(found) + uses + declared).forEach { item ->
            assertEquals("src/api.c", item.getValue("file").jsonPrimitive.content)
            assertEquals(listOf("src/api.c"), item.getValue("translationUnits").jsonArray.map { it.jsonPrimitive.content })
            assertEquals("compiler", item.getValue("evidence").jsonPrimitive.content)
            assertEquals("captured-family", item.getValue("completeness").jsonPrimitive.content)
            assertEquals(snapshotHash, item.getValue("snapshotHash").jsonPrimitive.content)
            assertEquals("31a40dc31f3c15a47aa119aa148f339bf363d8f61202ddbdacbd3f24b71ba113", item.getValue("compilerSha256").jsonPrimitive.content)
            assertEquals(if (keyword == "typedef") "ORDINARY" else "TAG", item.getValue("lookupSpace").jsonPrimitive.content)
            val line = item.getValue("line").jsonPrimitive.int - 1
            val start = item.getValue("character").jsonPrimitive.int
            val end = item.getValue("endCharacter").jsonPrimitive.int
            assertEquals(line + 1, item.getValue("endLine").jsonPrimitive.int)
            assertEquals(name, source.lines()[line].substring(start, end))
        }
        listOf(uses, declared).forEach { items -> items.forEach { item ->
            assertEquals(selected.getValue("id"), item.getValue("symbol"))
            assertEquals(items.size, item.getValue("queryTotal").jsonPrimitive.int)
            assertFalse(item.getValue("queryTruncated").jsonPrimitive.boolean)
        } }
    }

    private fun assertIncompleteRecordNavigation() {
        val record = assertNotNull(recordRead)
        assertFalse(record.getValue("hasCompleteDefinition").jsonPrimitive.boolean)
        val name = record.getValue("name").jsonPrimitive.content
        val keyword = record.getValue("keyword").jsonPrimitive.content
        val shared = record.getValue("profile").jsonPrimitive.content == "header"
        assertNull(definition)
        assertTrue(assertNotNull(definitionError).contains("c.navigation.definitionUnavailable"), definitionError)
        if (daemon != null) assertEquals(JsonRpcErrorCodes.PLAN_REFUSED, definitionCode)
        assertNull(referenceError); assertNull(declarationsError)
        val uses = assertNotNull(references).map { it.jsonObject }
        val withDeclarations = assertNotNull(referencesWithDeclarations).map { it.jsonObject }
        fun point(item: JsonObject) = listOf(item.getValue("file").jsonPrimitive.content,
            item.getValue("line").jsonPrimitive.int, item.getValue("character").jsonPrimitive.int,
            item.getValue("endCharacter").jsonPrimitive.int)
        val expected = images.entries.filter { (file, _) -> file.toString().endsWith(".c") && (shared || file.toString() == "src/api.c") }
            .flatMap { (file, source) -> source.lines().mapIndexedNotNull { line, text ->
                val at = text.indexOf("$keyword $name")
                if (at < 0 || text.contains("$keyword $name {") || text.trim() == "$keyword $name;") null
                else listOf(file.toString(), line + 1, at + keyword.length + 1, at + keyword.length + 1 + name.length)
            } }.toSet()
        assertEquals(if (shared) 3 else 2, expected.size)
        assertEquals(expected, uses.map(::point).toSet())
        val declaration = point(assertNotNull(selectedDeclaration))
        assertEquals(expected + setOf(declaration), withDeclarations.map(::point).toSet())
        assertEquals(uses.size + 1, withDeclarations.size)
        listOf(uses, withDeclarations).forEach { results -> results.forEach { item ->
            assertEquals("compiler", item.getValue("evidence").jsonPrimitive.content)
            assertEquals("captured-family", item.getValue("completeness").jsonPrimitive.content)
            assertEquals(snapshotHash, item.getValue("snapshotHash").jsonPrimitive.content)
            assertEquals("31a40dc31f3c15a47aa119aa148f339bf363d8f61202ddbdacbd3f24b71ba113", item.getValue("compilerSha256").jsonPrimitive.content)
            assertEquals(assertNotNull(selectedDeclaration).getValue("id"), item.getValue("symbol"))
            assertEquals(results.size, item.getValue("queryTotal").jsonPrimitive.int)
            assertFalse(item.getValue("queryTruncated").jsonPrimitive.boolean)
            val file = item.getValue("file").jsonPrimitive.content
            val units = if (shared && point(item) == declaration) listOf("src/api.c", "src/main.c") else listOf(file)
            assertEquals(units, item.getValue("translationUnits").jsonArray.map { it.jsonPrimitive.content })
            val line = item.getValue("line").jsonPrimitive.int - 1
            assertEquals(line + 1, item.getValue("endLine").jsonPrimitive.int)
            assertEquals(name, images.getValue(Path.of(file)).lines()[line].substring(
                item.getValue("character").jsonPrimitive.int, item.getValue("endCharacter").jsonPrimitive.int))
        } }
    }

    private fun assertForwardRecordNavigation() {
        val record = assertNotNull(recordRead)
        val name = record.getValue("name").jsonPrimitive.content
        val keyword = record.getValue("keyword").jsonPrimitive.content
        val shared = record.getValue("profile").jsonPrimitive.content == "header"
        assertNull(definitionError); assertNull(referenceError)
        val found = assertNotNull(definition)
        val uses = assertNotNull(references).map { it.jsonObject }
        val apiText = images.getValue(Path.of("src/api.c")).lines()
        val definitionLine = apiText.indexOfFirst { it.contains("$keyword $name {") }.also { assertTrue(it >= 0) }
        val column = apiText[definitionLine].indexOf("$keyword $name") + keyword.length + 1
        fun point(item: JsonObject) = listOf(item.getValue("file").jsonPrimitive.content,
            item.getValue("line").jsonPrimitive.int, item.getValue("character").jsonPrimitive.int,
            item.getValue("endCharacter").jsonPrimitive.int)
        assertEquals(listOf("src/api.c", definitionLine + 1, column, column + name.length), point(found))
        assertEquals(assertNotNull(selectedDeclaration).getValue("id"), found.getValue("requestedSymbolId"))
        assertNotEquals(assertNotNull(selectedDeclaration).getValue("id"), found.getValue("id"))
        val expectedUses = images.entries.filter { (file, _) -> file.toString().endsWith(".c") && (shared || file.toString() == "src/api.c") }
            .flatMap { (file, source) -> source.lines().mapIndexedNotNull { line, text ->
                val at = text.indexOf("$keyword $name")
                if (at < 0 || text.contains("$keyword $name {") || text.trim() == "$keyword $name;") null
                else listOf(file.toString(), line + 1, at + keyword.length + 1, at + keyword.length + 1 + name.length)
            } }.toSet()
        assertEquals(if (shared) 3 else 2, expectedUses.size)
        assertEquals(expectedUses, uses.map(::point).toSet())
        (listOf(found) + uses).forEach { item ->
            assertEquals("compiler", item.getValue("evidence").jsonPrimitive.content)
            assertEquals("captured-family", item.getValue("completeness").jsonPrimitive.content)
            assertEquals(snapshotHash, item.getValue("snapshotHash").jsonPrimitive.content)
            assertEquals("31a40dc31f3c15a47aa119aa148f339bf363d8f61202ddbdacbd3f24b71ba113", item.getValue("compilerSha256").jsonPrimitive.content)
            val file = item.getValue("file").jsonPrimitive.content
            assertEquals(listOf(file), item.getValue("translationUnits").jsonArray.map { it.jsonPrimitive.content })
            val line = item.getValue("line").jsonPrimitive.int - 1
            assertEquals(name, images.getValue(Path.of(file)).lines()[line].substring(
                item.getValue("character").jsonPrimitive.int, item.getValue("endCharacter").jsonPrimitive.int))
        }
    }

    fun assertResult(expected: String) {
        if (expected == "compiler composed limit declarations") {
            assertNull(error)
            val rows = assertNotNull(all).map { it.jsonObject }
            assertEquals(200, rows.size)
            rows.forEach { row ->
                assertEquals(16384, row.getValue("queryTotal").jsonPrimitive.int)
                assertTrue(row.getValue("queryTruncated").jsonPrimitive.boolean)
                assertEquals(snapshotHash, row.getValue("snapshotHash").jsonPrimitive.content)
                assertEquals("clangd.symbolInfo.v22", row.getValue("supplementalBindingProviderId").jsonPrimitive.content)
            }
            val selected = assertNotNull(values).map { it.jsonObject }
            assertEquals(4, selected.size); assertEquals(4, selected.map { it.getValue("id") }.toSet().size)
            val expectedPoints = setOf(prototypePoint("src/api.c", "int inspect(struct opaque *first"),
                prototypePoint("src/api.c", "int inspect_other(struct opaque *third)"),
                prototypePoint("src/api.c", "struct opaque { int later; }"),
                prototypePoint("src/main.c", "struct opaque { double unrelated; }"))
            assertEquals(expectedPoints, selected.map(::prototypePoint).toSet())
            selected.forEach { row ->
                assertEquals(4, row.getValue("queryTotal").jsonPrimitive.int)
                assertFalse(row.getValue("queryTruncated").jsonPrimitive.boolean)
                assertEquals("STRUCT", row.getValue("cKind").jsonPrimitive.content)
                assertEquals("TAG", row.getValue("lookupSpace").jsonPrimitive.content)
                assertEquals(snapshotHash, row.getValue("snapshotHash").jsonPrimitive.content)
            }
            return
        }
        if (expected == "compiler declarations") {
            assertNull(error)
            val declarations = assertNotNull(all).map { it.jsonObject }
            assertEquals(28, declarations.size)
            assertEquals(28, declarations.map { it.getValue("id").jsonPrimitive.content }.toSet().size)
            assertEquals(setOf("FUNCTION", "PARAMETER", "VARIABLE", "STRUCT", "UNION", "ENUM", "TYPEDEF", "ENUMERATOR", "FIELD", "LABEL"),
                declarations.map { it.getValue("cKind").jsonPrimitive.content }.toSet())
            declarations.forEach { item ->
                assertEquals("compiler", item.getValue("evidence").jsonPrimitive.content)
                assertEquals("declarations", item.getValue("completeness").jsonPrimitive.content)
                assertEquals(snapshotHash, item.getValue("snapshotHash").jsonPrimitive.content)
                assertTrue(item.getValue("compilationContextHash").jsonPrimitive.content.matches(Regex("[0-9a-f]{64}")))
                assertEquals(28, item.getValue("queryTotal").jsonPrimitive.int)
                assertFalse(item.getValue("queryTruncated").jsonPrimitive.boolean)
                val file = Path.of(item.getValue("file").jsonPrimitive.content)
                val line = item.getValue("line").jsonPrimitive.int - 1
                val column = item.getValue("character").jsonPrimitive.int
                val name = item.getValue("name").jsonPrimitive.content
                assertEquals(name, images.getValue(file).lines()[line].substring(column, column + name.length))
            }
            assertEquals(setOf("src/api.c", "src/main.c"), declarations.map { it.getValue("translationUnit").jsonPrimitive.content }.toSet())
            val matches = assertNotNull(values).map { it.jsonObject }
            assertEquals(7, matches.size)
            assertEquals(setOf("ORDINARY", "TAG"), matches.map { it.getValue("lookupSpace").jsonPrimitive.content }.toSet())
        } else {
            assertNull(all, "Unavailable C analysis must not become an empty successful search")
            assertTrue(assertNotNull(error).contains(expected), error)
            if (condition == "prototype composed over-cap") assertTrue(assertNotNull(error).contains("INPUT_LIMIT"), error)
            if (daemon != null) assertEquals(if (condition == "wrong hash" || condition == "database changed") JsonRpcErrorCodes.SNAPSHOT_CHANGED else JsonRpcErrorCodes.PLAN_REFUSED, code)
        }
    }

    fun assertIntact() {
        val workspace = requireNotNull(root)
        if (sdkMode) {
            assertEquals(sdkImages, sdkFiles())
            assertTrue((nativeChildren() - nativeChildrenBefore).isEmpty(), "SDK query processes must terminate")
            if (mcp != null || packagedRoute == "MCP") {
                val tools = (packaged?.dispatch("MCP", "tools/list", JsonObject(emptyMap()))
                    ?: requireNotNull(mcp).dispatch("tools/list", JsonObject(emptyMap()))).jsonObject.getValue("tools").jsonArray.map { it.jsonObject }
                for (name in listOf("symbol_search", "symbol_definition", "symbol_references")) {
                    val schema = tools.single { it.getValue("name").jsonPrimitive.content == name }.getValue("inputSchema").jsonObject
                        .getValue("properties").jsonObject.getValue("readOnlySdks").jsonObject
                    assertEquals("array", schema.getValue("type").jsonPrimitive.content); assertEquals(8, schema.getValue("maxItems").jsonPrimitive.int)
                }
            }
        }
        images.forEach { (file, content) -> assertEquals(content, Files.readString(workspace.resolve(file))) }
        assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        if (prototypeRead != null || scopedRead != null || packaged != null) assertTrue((nativeChildren() - nativeChildrenBefore).isEmpty(), "Owned native children must terminate")
        Files.walk(workspace).use { stream -> assertFalse(stream.anyMatch { it.fileName.toString().endsWith(".o") || it.fileName.toString().endsWith(".a") }) }
    }

    private fun navigateSdk(binding: String) {
        require(binding in setOf("typedef", "field"))
        val declarations = call("search", JsonObject(navigationArguments() + ("query" to JsonPrimitive(""))))?.jsonArray
        all = declarations
        if (declarations == null) {
            definitionError = error; referenceError = error; definitionCode = code; referenceCode = code; return
        }
        val file = requireNotNull(externalSdk).resolve("usr/include/api.h").toString()
        selectedDeclaration = declarations.map { it.jsonObject }.single {
            it.getValue("name").jsonPrimitive.content == (if (binding == "typedef") "value_t" else "member") &&
                it.getValue("cKind").jsonPrimitive.content == (if (binding == "typedef") "TYPEDEF" else "FIELD") &&
                it.getValue("file").jsonPrimitive.content == file && it.getValue("translationUnit").jsonPrimitive.content == "src/main.c"
        }
        val selected = requireNotNull(selectedDeclaration)
        if (condition == "SDK stale id") {
            val header = requireNotNull(externalSdk).resolve("usr/include/api.h")
            Files.writeString(header, Files.readString(header) + "\n/* SDK input identity changes */\n")
        } else if (condition == "SDK missing input") Files.delete(requireNotNull(externalSdk).resolve("usr/include/api.h"))
        sdkImages = sdkFiles()
        val request = JsonObject(navigationArguments() + ("symbol" to selected.getValue("id")))
        definition = call("definition", request)?.jsonObject; definitionError = error; definitionCode = code
        references = call("references", request)?.jsonArray; referenceError = error; referenceCode = code
        if (condition == "SDK captured") {
            referencesWithDeclarations = call("references", JsonObject(request + ("includeDeclaration" to JsonPrimitive(true))))?.jsonArray
            declarationsError = error
        }
    }

    private fun assertSdkNavigation(expected: String) {
        if (expected != "native SDK definition and references") {
            assertNull(definition); assertNull(references)
            assertTrue(assertNotNull(definitionError).contains(expected), definitionError)
            assertTrue(assertNotNull(referenceError).contains(expected), referenceError)
            return
        }
        assertNull(definitionError); assertNull(referenceError); assertNull(declarationsError)
        val selected = assertNotNull(selectedDeclaration)
        val found = assertNotNull(definition)
        val uses = assertNotNull(references).map { it.jsonObject }
        assertTrue(uses.isNotEmpty())
        val sdkRoot = requireNotNull(externalSdk)
        assertEquals(sdkRoot.resolve("usr/include/api.h").toString(), found.getValue("file").jsonPrimitive.content)
        assertEquals(true, found.getValue("readOnly").jsonPrimitive.boolean)
        assertEquals("SDK", found.getValue("sourceOwnership").jsonPrimitive.content)
        assertEquals(true, selected.getValue("readOnly").jsonPrimitive.boolean)
        assertEquals("fixture:readonly-query-sdk", found.getValue("sdk").jsonObject.getValue("provenance").jsonPrimitive.content)
        assertEquals("LicenseRef-RefactorKitFixture", found.getValue("sdk").jsonObject.getValue("license").jsonPrimitive.content)
        assertEquals(selected.getValue("sdkInputsIdentity"), found.getValue("sdkInputsIdentity"))
        assertTrue(uses.any { !it.getValue("readOnly").jsonPrimitive.boolean })
        for (row in listOf(found) + uses + assertNotNull(referencesWithDeclarations).map { it.jsonObject }) {
            val file = Path.of(row.getValue("file").jsonPrimitive.content)
            val sdkFile = file.isAbsolute
            val text = if (sdkFile) Files.readString(file) else images.getValue(file)
            val line = row.getValue("line").jsonPrimitive.int - 1
            val start = row.getValue("character").jsonPrimitive.int; val end = row.getValue("endCharacter").jsonPrimitive.int
            assertEquals(row.getValue("name").jsonPrimitive.content, text.lines()[line].substring(start, end))
            assertEquals(sdkFile, row.getValue("readOnly").jsonPrimitive.boolean)
            assertEquals((if (sdkFile) file else requireNotNull(root).resolve(file)).toUri().toString(), row.getValue("uri").jsonPrimitive.content)
            assertEquals("compiler", row.getValue("evidence").jsonPrimitive.content)
            assertEquals("31a40dc31f3c15a47aa119aa148f339bf363d8f61202ddbdacbd3f24b71ba113", row.getValue("compilerSha256").jsonPrimitive.content)
            assertFalse(".refactorkit/readonly-sdk" in row.toString())
        }
        assertTrue(assertNotNull(referencesWithDeclarations).size > uses.size)
    }

    private fun sdkFiles(): Map<Path, String> = externalSdk?.let { sdkRoot -> Files.walk(sdkRoot).use { paths ->
        paths.filter { Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }.toList()
            .associate { sdkRoot.relativize(it) to Files.readString(it) }
    } }.orEmpty()

    override fun close() {
        packaged?.close(); daemon?.close(); mcp?.close()
        externalSdk?.let { sdkRoot -> Files.walk(sdkRoot).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
        root?.let { workspace -> Files.walk(workspace).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
}
