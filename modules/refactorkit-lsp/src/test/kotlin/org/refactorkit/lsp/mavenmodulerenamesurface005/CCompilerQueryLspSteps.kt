package org.refactorkit.lsp.mavenmodulerenamesurface005

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.When
import io.cucumber.java.en.Then
import kotlinx.serialization.json.*
import org.refactorkit.core.JsonRpcException
import org.refactorkit.lsp.LspSession
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlin.test.*

class CCompilerQueryLspSteps {
    private val fixture = NativeCQueryLspFixture()
    @Given("a standard C LSP query fixture captures {string} with a {string} selection")
    fun capture(standard: String, binding: String) = fixture.capture(standard, binding)
    @Given("a standard C LSP boundary fixture captures {string} and {string}")
    fun boundary(standard: String, condition: String) = fixture.captureBoundary(standard, condition)
    @Then("the standard C LSP query disposition is {string}")
    fun disposition(expected: String) = fixture.verifyDisposition(expected)
    @When("the editor requests C workspace symbols document symbols definition and references")
    fun query() = fixture.query()
    @Then("the standard C LSP locations match the captured native binding family")
    fun locations() = fixture.verifyLocations()
    @Then("C LSP queries preserve all saved inputs and leave no transaction or compiler process")
    fun intact() = fixture.verifyIntact()
    @After
    fun close() = fixture.close()
}

private class NativeCQueryLspFixture : AutoCloseable {
    private var root: Path? = null
    private lateinit var sdk: Path
    private var session: LspSession? = null
    private var externalSdk: Path? = null
    private var sdkOriginals: Map<Path, String> = emptyMap()
    private var packaged: PackagedCQueryLspClient? = null
    private fun dispatch(method: String, params: JsonObject): JsonElement =
        packaged?.dispatch(method, params) ?: assertNotNull(session).dispatch(method, params)
    private lateinit var binding: String
    private lateinit var selectedName: String
    private lateinit var originals: Map<Path, String>
    private var privateBefore = emptySet<Path>()
    private var failure: JsonRpcException? = null
    private var workspaceSymbols: JsonArray? = null
    private var documentSymbols: JsonArray? = null
    private var definition: JsonElement? = null
    private var invalidPosition = false
    private var sourceFamily = false
    private var sourceMember = false
    private var declaredReferences: JsonArray? = null
    private var references: JsonArray? = null
    private val main = Path.of("src/main.c")
    private val header = Path.of("include/api.h")
    private val api = Path.of("src/api.c")
    private var queryFile: Path = main
    private var recordRead: JsonObject? = null
    private var scopedRead: JsonObject? = null
    private var prototypeRead: JsonObject? = null

    fun capture(standard: String, requestedBinding: String) {
        val sdkMode = requestedBinding.startsWith("sdk:")
        val binding = requestedBinding.removePrefix("packaged:").removePrefix("sdk:")
        val ordinaryShadow = binding in setOf("scope-typedef-ordinary-shadow-alias-before",
            "scope-typedef-ordinary-shadow-value-declaration", "scope-typedef-ordinary-shadow-value-use")
        val ordinary = binding in setOf("ordinary-local", "ordinary-global", "ordinary-static", "ordinary-enumerator", "ordinary-parameter")
        val headerPrototype = binding in setOf("header-prototype-first", "header-prototype-second", "header-prototype-other", "header-prototype-disagreement")
        val functionParameter = binding in setOf("function-parameter-first", "function-parameter-local", "function-parameter-cast")
        val prototype = headerPrototype || functionParameter || binding in setOf("prototype-first", "prototype-second", "prototype-other",
            "prototype-global", "prototype-global-use", "prototype-peer")
        if (prototype) prototypeRead = requireNotNull(CCompilerQueryLspSteps::class.java
            .getResourceAsStream(when {
                binding == "header-prototype-disagreement" -> "/header-prototype-navigation/context-disagreement.json.gz"
                headerPrototype -> "/header-prototype-navigation/native-fixture.json.gz"
                functionParameter -> "/function-parameter-tag-navigation/type-uses.json.gz"
                else -> "/prototype-tag-navigation/native-fixture.json.gz"
            })).use { stream ->
            GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).use { reader ->
                val resource = Json.parseToJsonElement(reader.readText()).jsonObject
                Json.parseToJsonElement(resource.getValue("observationBytes").jsonPrimitive.content).jsonObject
                    .getValue("results").jsonArray.single { it.jsonObject.getValue("standard").jsonPrimitive.content == standard }.jsonObject
            }
        }
        val scopedEnum = binding in setOf("scope-enum-block", "scope-enum-shadow", "scope-enum-nested")
        val scopedTypedef = ordinaryShadow || binding in setOf("scope-typedef-block", "scope-typedef-shadow", "scope-typedef-nested",
            "scope-typedef-pointer-shadow", "scope-typedef-chain-shadow", "scope-typedef-repeated-alias")
        val scoped = scopedTypedef || scopedEnum || binding in setOf("scope-block-struct", "scope-block-union", "scope-shadow-struct", "scope-shadow-union",
            "scope-forward-block-struct", "scope-forward-block-union")
        val recordKey = binding.removePrefix("cursor-")
        val directHeader = binding.startsWith("cursor-header-")
        val recordVariant = (!binding.startsWith("cursor-") || directHeader) && recordKey in setOf("source-forward-struct", "source-forward-union", "header-forward-struct", "header-forward-union",
            "source-incomplete-struct", "source-incomplete-union", "header-incomplete-struct", "header-incomplete-union")
        require(standard in setOf("c11", "c17") && (ordinary || prototype || scoped || recordVariant || binding in setOf("function", "typedef", "field", "source struct", "source union", "source enum", "source typedef", "source struct field", "source union field", "peer struct field", "peer union field")))
        if (recordVariant) recordRead = requireNotNull(CCompilerQueryLspSteps::class.java
            .getResourceAsStream("/record-navigation/native-fixture.json.gz")).use { stream ->
            GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject }
        }.getValue("modes").jsonObject.getValue(standard).jsonObject.getValue(recordKey).jsonObject
        if (scoped) scopedRead = requireNotNull(CCompilerQueryLspSteps::class.java
            .getResourceAsStream(when { scopedTypedef -> "/scoped-typedef-navigation/native-fixture.json.gz"; scopedEnum -> "/scoped-type-navigation/native-fixture.json.gz"; else -> "/scoped-record-navigation/native-fixture.json.gz" })).use { stream ->
            GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject }
        }.getValue("modes").jsonObject.getValue(standard).jsonObject
            .getValue(when { ordinaryShadow -> "ordinary-shadow"; scopedTypedef -> binding.removePrefix("scope-typedef-"); scopedEnum -> binding.removePrefix("scope-enum-"); else -> binding.removePrefix("scope-").substringBeforeLast('-') }).jsonObject
        this.binding = binding
        sourceFamily = ordinary || prototype || scoped || recordVariant || binding.startsWith("source ") || binding.startsWith("peer ")
        sourceMember = binding.endsWith(" field")
        queryFile = if (ordinary) { if (binding in setOf("ordinary-global", "ordinary-enumerator")) main else api } else if (headerPrototype || directHeader) header else if (binding == "prototype-peer" || binding.startsWith("peer ")) main else if (sourceFamily) api else main
        selectedName = if (ordinary) when (binding) { "ordinary-local" -> "local"; "ordinary-global", "ordinary-parameter" -> "value"; "ordinary-enumerator" -> "READY"; else -> "hidden" } else if (prototype) "opaque" else if (scopedTypedef) "count_t" else if (scopedEnum) "stage" else if (scoped) { if (binding.endsWith("-struct")) "meter" else "payload" }
        else recordRead?.getValue("name")?.jsonPrimitive?.content ?: when (binding) {
            "function" -> "compute"; "typedef" -> "value_t"; "field" -> "reading"
            "source struct field" -> "member"; "source union field" -> "amount"
            "peer struct field", "peer union field" -> "other"
            "source struct" -> "meter"; "source union" -> "payload"; "source enum" -> "phase"; "source typedef" -> "scalar_t"
            else -> error("Unknown native C query binding: $binding")
        }
        sdk = Path.of(requireNotNull(System.getenv("REFACTORKIT_C_QUALIFICATION_TOOLCHAIN")))
        val workspace = Files.createTempDirectory("rk-c-lsp-query-"); root = workspace
        val record = recordRead ?: scopedRead ?: prototypeRead
        originals = if (ordinary) Json.parseToJsonElement(requireNotNull(CCompilerQueryLspSteps::class.java
            .getResource("/ordinary-value-navigation/fixture-inputs.json")).readText()).jsonObject.getValue("sources").jsonObject
            .map { (file, text) -> Path.of(file) to text.jsonPrimitive.content }.toMap()
        else if (record != null) record.getValue("sources").jsonObject
            .map { (file, text) -> Path.of(file) to text.jsonPrimitive.content }.toMap()
        else if (sourceFamily) {
            val material = Json.parseToJsonElement(requireNotNull(CCompilerQueryLspSteps::class.java
                .getResource("/source-type-navigation/native-fixture.json")).readText()).jsonObject
            material.getValue("modes").jsonObject.getValue(standard).jsonObject.getValue("sources").jsonObject
                .map { (file, text) -> Path.of(file) to text.jsonPrimitive.content }.toMap()
        } else linkedMapOf(
            header to "#pragma once\ntypedef int value_t;\nstruct meter { int reading; };\nvalue_t compute(value_t input);\n",
            api to "#include \"api.h\"\nvalue_t compute(value_t input) { return input; }\n",
            main to "#include \"api.h\"\nint main(void) { struct meter item = { .reading = 3 }; value_t value = compute(item.reading); return value; }\n",
        )
        if (sdkMode) {
            require(binding in setOf("typedef", "field", "function"))
            val sdkRoot = Files.createTempDirectory("rk-c-lsp-readonly-sdk-"); externalSdk = sdkRoot
            val sdkHeader = Path.of("usr/include/api.h")
            Files.createDirectories(sdkRoot.resolve(sdkHeader).parent)
            sdkOriginals = mapOf(sdkHeader to originals.getValue(header))
            Files.writeString(sdkRoot.resolve(sdkHeader), sdkOriginals.getValue(sdkHeader))
            originals = originals.filterKeys { it != header }
            Files.createDirectories(workspace.resolve("include"))
        }
        val database = JsonArray(listOf(api, main).map { file -> buildJsonObject {
            put("directory", workspace.toString()); put("file", file.toString())
            put("arguments", JsonArray((listOf(sdk.resolve("usr/bin/clang-22").toString(), "-std=$standard", "-Iinclude") +
                externalSdk?.let { listOf("--sysroot=$it") }.orEmpty() + listOf("-c", file.toString())).map(::JsonPrimitive)))
        } }).toString()
        originals = originals + (Path.of("compile_commands.json") to database)
        originals.forEach { (file, text) -> Files.createDirectories(workspace.resolve(file).parent); Files.writeString(workspace.resolve(file), text) }
        privateBefore = privateDirectories()
        if (requestedBinding.startsWith("packaged:")) {
            val candidate = Path.of(assertNotNull(System.getenv("REFACTORKIT_C_QUERY_CANDIDATE_ROOT")))
            packaged = PackagedCQueryLspClient(candidate, workspace)
        } else session = LspSession()
        dispatch("initialize", buildJsonObject {
            put("rootUri", workspace.toUri().toString())
            put("initializationOptions", buildJsonObject { put("c", buildJsonObject {
                put("clang", sdk.resolve("usr/bin/clang-22").toString())
                put("clangd", sdk.resolve("usr/bin/clangd").toString())
                put("clangFormat", sdk.resolve("usr/bin/clang-format").toString())
                externalSdk?.let { sdkRoot -> put("readOnlySdks", buildJsonArray { add(buildJsonObject {
                    put("root", sdkRoot.toString()); put("provenance", "fixture:lsp-readonly-sdk"); put("license", "LicenseRef-RefactorKitFixture")
                }) }) }
            }) })
        })
        dispatch("textDocument/didOpen", buildJsonObject { put("textDocument", buildJsonObject {
            put("uri", workspace.resolve(queryFile).toUri().toString()); put("languageId", "c"); put("version", 1); put("text", originals.getValue(queryFile))
        }) })
    }

    fun captureBoundary(standard: String, requestedCondition: String) {
        val condition = requestedCondition.removePrefix("packaged:")
        if (condition == "header prototype context disagreement") {
            capture(standard, "header-prototype-disagreement"); return
        }
        capture(standard, if (requestedCondition.startsWith("packaged:")) "packaged:function" else "function")
        val workspace = requireNotNull(root)
        when (condition) {
            "missing toolchain" -> {
                dispatch("initialize", buildJsonObject { put("rootUri", workspace.toUri().toString()) })
                dispatch("textDocument/didOpen", buildJsonObject { put("textDocument", buildJsonObject {
                    put("uri", workspace.resolve(queryFile).toUri().toString()); put("languageId", "c"); put("version", 1); put("text", originals.getValue(queryFile))
                }) })
            }
            "dirty document" -> dispatch("textDocument/didChange", buildJsonObject {
                put("textDocument", buildJsonObject { put("uri", workspace.resolve(queryFile).toUri().toString()); put("version", 2) })
                put("contentChanges", JsonArray(listOf(buildJsonObject { put("text", originals.getValue(queryFile) + "\n") })))
            })
            "missing TU" -> {
                val file = Path.of("compile_commands.json")
                val text = JsonArray(Json.parseToJsonElement(originals.getValue(file)).jsonArray.take(1)).toString()
                originals = originals + (file to text); Files.writeString(workspace.resolve(file), text)
            }
            "macro declaration" -> {
                val text = "#define ALIAS 1\n" + originals.getValue(api)
                originals = originals + (api to text); Files.writeString(workspace.resolve(api), text)
            }
            "invalid position" -> invalidPosition = true
            "number cursor" -> selectedName = "3"
            else -> error("Unknown C LSP query boundary: $condition")
        }
    }

    fun query() {
        val workspace = requireNotNull(root)
        val lines = originals.getValue(queryFile).lines()
        val record = recordRead
        val selectedLine = if (ordinaryShadowMarker() != null) { if (binding.endsWith("alias-before")) 2 else 3 } else if (ordinaryValueMarker() != null) { if (binding in setOf("ordinary-global", "ordinary-enumerator")) 2 else 3 } else if (prototypeRead != null) { when (binding.removePrefix("header-")) {
            "prototype-other" -> 2; "prototype-global", "prototype-peer" -> 3; "prototype-global-use" -> 4; else -> 1
        } } else if (scopedRead != null) {
            val keyword = if (binding.startsWith("scope-typedef-")) "typedef" else if (binding.startsWith("scope-enum-")) "enum" else if (binding.endsWith("-struct")) "struct" else "union"
            val marker = if (keyword == "typedef") {
                val indent = if (binding == "scope-typedef-nested") "        " else "    "
                "$indent$selectedName value = " + when (binding) {
                    "scope-typedef-pointer-shadow" -> "&number"; "scope-typedef-nested" -> "4"; else -> "2"
                }
            } else if (keyword == "enum") {
                val indent = if (binding == "scope-enum-nested") "        " else "    "
                "${indent}enum $selectedName state = READY"
            } else if (binding.startsWith("scope-forward-block-")) "        $keyword $selectedName *before"
                else "    $keyword $selectedName " + if (keyword == "struct") "item" else "box"
            lines.indexOfFirst { it.startsWith(marker) }.also { assertTrue(it >= 0) }
        } else if (record != null) {
            val keyword = record.getValue("keyword").jsonPrimitive.content
            val line = if (binding.startsWith("cursor-header-")) lines.indexOfFirst { it.trim() == "$keyword $selectedName;" }
                else lines.indexOfFirst { it.contains("$keyword $selectedName") && !it.contains("$keyword $selectedName {") && it.trim() != "$keyword $selectedName;" }
            line.also { assertTrue(it >= 0) }
        } else if (sourceMember) { if (binding.endsWith("struct field")) 8 else 10 } else if (sourceFamily) lines.indexOfLast { it.contains(selectedName) } else 1
        val sourceLine = lines[selectedLine]
        val document = buildJsonObject { put("uri", workspace.resolve(queryFile).toUri().toString()) }
        val typeMarker = functionParameterMarker() ?: ordinaryValueMarker() ?: ordinaryShadowMarker()
        val typeColumn = typeMarker?.let { marker -> sourceLine.indexOf(marker).also { assertTrue(it >= 0) } + marker.indexOf(selectedName).also { assertTrue(it >= 0) } }
        val position = buildJsonObject { put("line", selectedLine); put("character", if (invalidPosition) sourceLine.length + 5 else if (typeColumn != null) typeColumn else if (binding in setOf("prototype-first", "header-prototype-first")) sourceLine.indexOf(selectedName) else sourceLine.lastIndexOf(selectedName)) }
        try {
            workspaceSymbols = dispatch("workspace/symbol", buildJsonObject { put("query", "") }).jsonArray
            documentSymbols = dispatch("textDocument/documentSymbol", buildJsonObject { put("textDocument", document) }).jsonArray
            definition = dispatch("textDocument/definition", buildJsonObject { put("textDocument", document); put("position", position) })
            references = dispatch("textDocument/references", buildJsonObject {
                put("textDocument", document); put("position", position)
                put("context", buildJsonObject { put("includeDeclaration", false) })
            }).jsonArray
            if (sourceFamily || externalSdk != null) declaredReferences = dispatch("textDocument/references", buildJsonObject {
                put("textDocument", document); put("position", position)
                put("context", buildJsonObject { put("includeDeclaration", true) })
            }).jsonArray
        } catch (error: JsonRpcException) { failure = error }
    }

    fun verifyLocations() {
        assertNull(failure, "Standard C LSP requests must use configured native analysis: ${failure?.message}")
        if (externalSdk != null) { verifySdkLocations(); return }
        if (ordinaryShadowMarker() != null) { verifyOrdinaryShadowLocations(); return }
        if (ordinaryValueMarker() != null) { verifyOrdinaryValueLocations(); return }
        if (prototypeRead != null) { verifyPrototypeLocations(); return }
        if (scopedRead != null) { verifyScopedRecordLocations(); return }
        if (recordRead != null) { verifyRecordLocations(); return }
        val symbols = assertNotNull(workspaceSymbols).map { it.jsonObject }
        val expectedNames = if (sourceFamily) setOf("calculate", "scalar_t", "meter", "payload", "phase", "main")
            else setOf("compute", "value_t", "meter", "reading", "main", "item", "value", "input")
        assertTrue(symbols.map { it.getValue("name").jsonPrimitive.content }.toSet().containsAll(expectedNames))
        symbols.forEach { verifyLocation(it.getValue("location").jsonObject, it.getValue("name").jsonPrimitive.content) }
        val outline = assertNotNull(documentSymbols).map { it.jsonObject }
        val expectedOutline = if (sourceMember && queryFile == main) setOf("scalar_t", "meter", "other", "payload", "phase", "OTHER",
            "main", "value", "item", "box", "state") else if (sourceFamily) setOf("scalar_t", "meter", "member", "payload", "amount", "phase", "PENDING",
            "calculate", "input", "value", "item", "box", "state") else setOf("main", "item", "value")
        assertEquals(expectedOutline, outline.map { it.getValue("name").jsonPrimitive.content }.toSet())
        outline.forEach { symbol ->
            verifyLocation(buildJsonObject { put("uri", requireNotNull(root).resolve(queryFile).toUri().toString()); put("range", symbol.getValue("selectionRange")) }, symbol.getValue("name").jsonPrimitive.content)
        }
        val found = assertNotNull(definition).jsonObject
        val expected = if (sourceMember) queryFile else if (sourceFamily || binding == "function") api else header
        assertEquals(requireNotNull(root).resolve(expected).toUri().toString(), found.getValue("uri").jsonPrimitive.content)
        verifyLocation(found, selectedName)
        val uses = assertNotNull(references).map { it.jsonObject }
        assertEquals(if (sourceMember) 2 else if (sourceFamily) 1 else when (binding) { "function" -> 1; "typedef" -> 5; else -> 2 }, uses.size)
        uses.forEach { verifyLocation(it, selectedName) }
        if (sourceFamily && !sourceMember) {
            val declarationLine = when (binding) { "source struct" -> 2; "source union" -> 3; "source enum" -> 4; "source typedef" -> 1
                else -> error("Unknown source type binding: $binding") }
            val useLine = when (binding) { "source struct" -> 7; "source union" -> 8; "source enum" -> 9; "source typedef" -> 6
                else -> error("Unknown source type binding: $binding") }
            fun expectedTypeLocation(file: Path, line: Int): JsonObject {
                val start = originals.getValue(file).lines()[line].indexOf(selectedName).also { assertTrue(it >= 0) }
                return buildJsonObject {
                    put("uri", requireNotNull(root).resolve(file).toUri().toString())
                    put("range", buildJsonObject {
                        put("start", buildJsonObject { put("line", line); put("character", start) })
                        put("end", buildJsonObject { put("line", line); put("character", start + selectedName.length) })
                    })
                }
            }
            val declaration = expectedTypeLocation(api, declarationLine)
            val use = expectedTypeLocation(api, useLine)
            assertEquals(declaration, found)
            assertEquals(setOf(use), uses.toSet())
            assertEquals(setOf(declaration, expectedTypeLocation(main, declarationLine)), symbols.filter {
                it.getValue("name").jsonPrimitive.content == selectedName
            }.map { it.getValue("location").jsonObject }.toSet())
            val declared = assertNotNull(declaredReferences).map { it.jsonObject }
            assertEquals(2, declared.size)
            assertEquals(setOf(declaration, use), declared.toSet())
        }
        if (sourceMember) {
            val struct = binding.endsWith("struct field")
            val declarationLine = if (struct) 2 else 3
            val text = originals.getValue(queryFile).lines()
            fun expectedLocation(line: Int, marker: String): JsonObject {
                val start = text[line].indexOf(marker).also { assertTrue(it >= 0) } + marker.length - selectedName.length
                return buildJsonObject {
                    put("uri", requireNotNull(root).resolve(queryFile).toUri().toString())
                    put("range", buildJsonObject {
                        put("start", buildJsonObject { put("line", line); put("character", start) })
                        put("end", buildJsonObject { put("line", line); put("character", start + selectedName.length) })
                    })
                }
            }
            assertEquals(expectedLocation(declarationLine, selectedName), found)
            val expectedUses = if (struct) setOf(expectedLocation(7, ".$selectedName"), expectedLocation(8, "item.$selectedName"))
                else setOf(expectedLocation(8, ".$selectedName"), expectedLocation(10, "box.$selectedName"))
            assertEquals(expectedUses, uses.toSet())
            val declared = assertNotNull(declaredReferences).map { it.jsonObject }
            assertEquals(3, declared.size)
            assertEquals(expectedUses + found, declared.toSet())
        }
    }

    private fun ordinaryShadowMarker(): String? = when (binding) {
        "scope-typedef-ordinary-shadow-alias-before" -> "count_t value = 2"
        "scope-typedef-ordinary-shadow-value-declaration" -> "int count_t = 3"
        "scope-typedef-ordinary-shadow-value-use" -> "return count_t"
        else -> null
    }

    private fun verifyOrdinaryShadowLocations() {
        fun location(file: Path, marker: String): JsonObject {
            val text = originals.getValue(file)
            val at = text.indexOf(marker).also { assertTrue(it >= 0, marker) } + marker.indexOf(selectedName).also { assertTrue(it >= 0) }
            val prefix = text.substring(0, at); val line = prefix.count { it == '\n' }
            val column = at - (prefix.lastIndexOf('\n') + 1)
            return buildJsonObject {
                put("uri", requireNotNull(root).resolve(file).toUri().toString())
                put("range", buildJsonObject {
                    put("start", buildJsonObject { put("line", line); put("character", column) })
                    put("end", buildJsonObject { put("line", line); put("character", column + selectedName.length) })
                })
            }
        }
        val alias = location(api, "typedef int count_t;")
        val peerAlias = location(main, "typedef long count_t;")
        val variable = location(api, "int count_t = 3")
        val selectedAlias = binding.endsWith("alias-before")
        val expectedDefinition = if (selectedAlias) alias else variable
        val expectedUses = if (selectedAlias) setOf(location(api, "count_t value = 2"), location(api, "count_t value = 4"))
            else setOf(location(api, "return count_t"))
        assertNotEquals(alias, variable); assertNotEquals(alias, peerAlias)
        assertEquals(expectedDefinition, definition)
        val uses = assertNotNull(references).map { it.jsonObject }
        val included = assertNotNull(declaredReferences).map { it.jsonObject }
        assertEquals(expectedUses.size, uses.size); assertEquals(expectedUses, uses.toSet())
        assertEquals(expectedUses.size + 1, included.size)
        assertEquals(expectedUses + expectedDefinition, included.toSet())
        val symbols = assertNotNull(workspaceSymbols).map { it.jsonObject }
        symbols.forEach { verifyLocation(it.getValue("location").jsonObject, it.getValue("name").jsonPrimitive.content) }
        val named = symbols.filter { it.getValue("name").jsonPrimitive.content == selectedName }.map { it.getValue("location").jsonObject }
        assertEquals(3, named.size); assertEquals(setOf(alias, variable, peerAlias), named.toSet())
        val outline = assertNotNull(documentSymbols).map { it.jsonObject }
        outline.forEach { item -> verifyLocation(buildJsonObject {
            put("uri", requireNotNull(root).resolve(queryFile).toUri().toString()); put("range", item.getValue("selectionRange"))
        }, item.getValue("name").jsonPrimitive.content) }
        val namedOutline = outline.filter { it.getValue("name").jsonPrimitive.content == selectedName }.map { item -> buildJsonObject {
            put("uri", requireNotNull(root).resolve(queryFile).toUri().toString()); put("range", item.getValue("selectionRange"))
        } }
        assertEquals(2, namedOutline.size); assertEquals(setOf(alias, variable), namedOutline.toSet())
        (uses + included + listOf(expectedDefinition)).forEach { verifyLocation(it, selectedName) }
    }

    private fun ordinaryValueMarker(): String? = when (binding) {
        "ordinary-local" -> "return local"
        "ordinary-global" -> "+ value + READY"
        "ordinary-static" -> "+ hidden + READY"
        "ordinary-enumerator" -> "+ READY"
        "ordinary-parameter" -> "int local = value"
        else -> null
    }

    private fun verifyOrdinaryValueLocations() {
        fun location(file: Path, marker: String): JsonObject {
            val text = originals.getValue(file)
            val at = text.indexOf(marker).also { assertTrue(it >= 0, marker) } + marker.indexOf(selectedName).also { assertTrue(it >= 0) }
            val prefix = text.substring(0, at)
            val line = prefix.count { it == '\n' }; val column = at - (prefix.lastIndexOf('\n') + 1)
            return buildJsonObject {
                put("uri", requireNotNull(root).resolve(file).toUri().toString())
                put("range", buildJsonObject {
                    put("start", buildJsonObject { put("line", line); put("character", column) })
                    put("end", buildJsonObject { put("line", line); put("character", column + selectedName.length) })
                })
            }
        }
        val expectedDefinition = location(if (binding == "ordinary-enumerator") header else api, when (binding) {
            "ordinary-local" -> "int local = value"
            "ordinary-global" -> "int value = 7"
            "ordinary-enumerator" -> "enum state { READY"
            "ordinary-parameter" -> "int compute(int value)"
            else -> "static int hidden = 3"
        })
        val expectedUses = when (binding) {
            "ordinary-local" -> setOf(location(api, "local += value"), location(api, "return local"))
            "ordinary-global" -> setOf(location(main, "+ value + READY"))
            "ordinary-enumerator" -> setOf(location(api, "+ READY"), location(main, "+ READY"))
            "ordinary-parameter" -> setOf(location(api, "int local = value"))
            else -> setOf(location(api, "+ hidden + READY"))
        }
        val declarations = if (binding == "ordinary-global")
            setOf(location(header, "extern int value"), expectedDefinition) else setOf(expectedDefinition)
        assertEquals(expectedDefinition, definition)
        val uses = assertNotNull(references).map { it.jsonObject }
        val included = assertNotNull(declaredReferences).map { it.jsonObject }
        assertEquals(expectedUses.size, uses.size); assertEquals(expectedUses, uses.toSet())
        assertEquals(expectedUses.size + declarations.size, included.size)
        assertEquals(expectedUses + declarations, included.toSet())
        val symbols = assertNotNull(workspaceSymbols).map { it.jsonObject }
        symbols.forEach { verifyLocation(it.getValue("location").jsonObject, it.getValue("name").jsonPrimitive.content) }
        val named = symbols.filter { it.getValue("name").jsonPrimitive.content == selectedName }
        val expectedSymbols = when (binding) {
            "ordinary-local" -> setOf(location(api, "int local = value"), location(main, "int local = 2"))
            "ordinary-global", "ordinary-parameter" -> setOf(location(header, "extern int value"), location(api, "int value = 7"),
                location(header, "int compute(int value)"), location(api, "int compute(int value)"), location(api, "int value = 5"))
            "ordinary-enumerator" -> setOf(expectedDefinition)
            else -> setOf(location(api, "static int hidden = 3"), location(main, "static int hidden = 100"))
        }
        assertEquals(expectedSymbols.size, named.size)
        assertEquals(expectedSymbols, named.map { it.getValue("location").jsonObject }.toSet())
        named.forEach { assertEquals(if (binding == "ordinary-enumerator") 14 else 13,
            it.getValue("kind").jsonPrimitive.int, "Ordinary declaration kinds follow the initial LSP client set") }
        val functions = symbols.filter { it.getValue("name").jsonPrimitive.content in setOf("compute", "main") }
        assertEquals(3, functions.size)
        assertEquals(setOf("compute", "main"), functions.map { it.getValue("name").jsonPrimitive.content }.toSet())
        functions.forEach {
            assertEquals(12, it.getValue("kind").jsonPrimitive.int, "Native C function symbols use LSP Function kind")
        }
        val outline = assertNotNull(documentSymbols).map { it.jsonObject }
        outline.forEach { item -> verifyLocation(buildJsonObject {
            put("uri", requireNotNull(root).resolve(queryFile).toUri().toString()); put("range", item.getValue("selectionRange"))
        }, item.getValue("name").jsonPrimitive.content) }
        val namedOutline = outline.filter { it.getValue("name").jsonPrimitive.content == selectedName }.map { item -> buildJsonObject {
            put("uri", requireNotNull(root).resolve(queryFile).toUri().toString()); put("range", item.getValue("selectionRange"))
        } }
        val outlineFunctions = outline.filter { it.getValue("name").jsonPrimitive.content in setOf("compute", "main") }
        assertEquals(1, outlineFunctions.size)
        assertEquals(if (queryFile == main) "main" else "compute", outlineFunctions.single().getValue("name").jsonPrimitive.content)
        outlineFunctions.forEach {
            assertEquals(12, it.getValue("kind").jsonPrimitive.int, "Native C function outline uses LSP Function kind")
        }
        val expectedOutline = when (binding) {
            "ordinary-global", "ordinary-enumerator" -> emptySet()
            "ordinary-parameter" -> setOf(location(api, "int value = 7"), location(api, "int compute(int value)"), location(api, "int value = 5"))
            else -> setOf(expectedDefinition)
        }
        assertEquals(expectedOutline.size, namedOutline.size); assertEquals(expectedOutline, namedOutline.toSet())
        (uses + included + listOf(expectedDefinition)).forEach { verifyLocation(it, selectedName) }
    }

    private fun functionParameterMarker(): String? = when (binding) {
        "function-parameter-first" -> "struct opaque *first"
        "function-parameter-local" -> "struct opaque *local"
        "function-parameter-cast" -> "(struct opaque *)local"
        else -> null
    }

    private fun verifyPrototypeLocations() {
        assertNotNull(prototypeRead)
        fun location(file: Path, line: Int, last: Boolean = false, marker: String? = null): JsonObject {
            val text = originals.getValue(file).lines()[line]
            val at = if (marker != null) text.indexOf(marker).also { assertTrue(it >= 0) } + marker.indexOf(selectedName).also { assertTrue(it >= 0) }
                else (if (last) text.lastIndexOf(selectedName) else text.indexOf(selectedName)).also { assertTrue(it >= 0) }
            return buildJsonObject {
                put("uri", requireNotNull(root).resolve(file).toUri().toString())
                put("range", buildJsonObject {
                    put("start", buildJsonObject { put("line", line); put("character", at) })
                    put("end", buildJsonObject { put("line", line); put("character", at + selectedName.length) })
                })
            }
        }
        val prototypeFile = if (binding.startsWith("header-prototype-")) header else api
        val familyBinding = binding.removePrefix("header-")
        val first = location(prototypeFile, 1)
        val second = location(prototypeFile, 1, last = true)
        val other = location(prototypeFile, 2)
        val sourceGlobal = location(api, if (prototypeFile == header) 1 else 3)
        val peerGlobal = location(main, 3)
        assertNotEquals(first, second)
        val global = familyBinding in setOf("prototype-global", "prototype-global-use", "prototype-peer")
        val expectedDeclaration = when (familyBinding) {
            "prototype-other" -> other; "prototype-peer" -> peerGlobal
            "prototype-global", "prototype-global-use" -> sourceGlobal; else -> first
        }
        if (global) assertEquals(expectedDeclaration, definition, "A source-private definition retains its owning TU")
        else assertEquals(JsonNull, definition, "A prototype-only tag must not acquire a complete definition")
        val expectedUses = when (familyBinding) {
            "prototype-other", "prototype-peer" -> emptySet()
            "prototype-global", "prototype-global-use" -> setOf(location(api, 4))
            else -> if (functionParameterMarker() != null) setOf(
                location(api, 1, marker = "struct opaque *second"),
                location(api, 1, marker = "struct opaque *local"),
                location(api, 1, marker = "(struct opaque *)local"),
                location(api, 1, marker = "sizeof(struct opaque *)")) else setOf(second)
        }
        val uses = assertNotNull(references).map { it.jsonObject }
        assertEquals(expectedUses.size, uses.size); assertEquals(expectedUses, uses.toSet())
        val declared = assertNotNull(declaredReferences).map { it.jsonObject }
        assertEquals(expectedUses.size + 1, declared.size)
        assertEquals(expectedUses + expectedDeclaration, declared.toSet())
        val symbols = assertNotNull(workspaceSymbols).map { it.jsonObject }
        symbols.forEach { verifyLocation(it.getValue("location").jsonObject, it.getValue("name").jsonPrimitive.content) }
        val named = symbols.filter { it.getValue("name").jsonPrimitive.content == selectedName }
            .map { it.getValue("location").jsonObject }
        assertEquals(4, named.size)
        assertEquals(setOf(first, other, sourceGlobal, peerGlobal), named.toSet())
        val outline = assertNotNull(documentSymbols).map { it.jsonObject }
        val namedOutline = outline.filter { it.getValue("name").jsonPrimitive.content == selectedName }.map { item -> buildJsonObject {
            put("uri", requireNotNull(root).resolve(queryFile).toUri().toString()); put("range", item.getValue("selectionRange"))
        } }
        val expectedOutline = when (queryFile) { main -> setOf(peerGlobal); header -> setOf(first, other); else -> setOf(first, other, sourceGlobal) }
        assertEquals(expectedOutline.size, namedOutline.size)
        assertEquals(expectedOutline, namedOutline.toSet())
        outline.forEach { item -> verifyLocation(buildJsonObject {
            put("uri", requireNotNull(root).resolve(queryFile).toUri().toString()); put("range", item.getValue("selectionRange"))
        }, item.getValue("name").jsonPrimitive.content) }
        (uses + declared).forEach { verifyLocation(it, selectedName) }
    }

    private fun scopedTypedefDeclarationMarker(): String = when (binding) {
        "scope-typedef-pointer-shadow" -> "    typedef int *$selectedName;"
        "scope-typedef-chain-shadow" -> "    typedef base_t $selectedName;"
        "scope-typedef-nested" -> "        typedef int $selectedName;"
        else -> "    typedef int $selectedName;"
    }

    private fun verifyScopedRecordLocations() {
        assertNotNull(scopedRead)
        val keyword = if (binding.startsWith("scope-typedef-")) "typedef" else if (binding.startsWith("scope-enum-")) "enum" else if (binding.endsWith("-struct")) "struct" else "union"
        val nestedForward = binding.startsWith("scope-forward-block-")
        val nested = nestedForward || binding in setOf("scope-enum-nested", "scope-typedef-nested")
        val indent = if (nested) "        " else "    "
        fun location(file: Path, line: Int): JsonObject {
            val text = originals.getValue(file).lines()[line]
            val at = if (keyword == "typedef") text.indexOf(selectedName).also { assertTrue(it >= 0) }
                else text.indexOf("$keyword $selectedName").also { assertTrue(it >= 0) } + keyword.length + 1
            return buildJsonObject {
                put("uri", requireNotNull(root).resolve(file).toUri().toString())
                put("range", buildJsonObject {
                    put("start", buildJsonObject { put("line", line); put("character", at) })
                    put("end", buildJsonObject { put("line", line); put("character", at + selectedName.length) })
                })
            }
        }
        fun declaration(text: String) = if (keyword == "typedef") text.trim() in setOf("typedef int $selectedName;", "typedef long $selectedName;", "typedef double $selectedName;",
            "typedef int *$selectedName;", "typedef base_t $selectedName;")
            else text.trim() == "$keyword $selectedName;" || text.contains("$keyword $selectedName {")
        fun declarations(file: Path): List<JsonObject> = originals.getValue(file).lines().mapIndexedNotNull { line, text ->
            if (declaration(text)) location(file, line) else null
        }
        val lines = originals.getValue(api).lines()
        val bodyMarker = if (keyword == "typedef") scopedTypedefDeclarationMarker() else "$indent$keyword $selectedName {"
        val bodyLine = lines.indexOfFirst { it.startsWith(bodyMarker) }.also { assertTrue(it >= 0) }
        val body = location(api, bodyLine)
        val familyDeclarations = if (nestedForward) {
            val forwardLine = lines.indexOfFirst { it == "$indent$keyword $selectedName;" }.also { assertTrue(it >= 0) }
            setOf(location(api, forwardLine), body)
        } else if (binding == "scope-typedef-repeated-alias") {
            val declarations = lines.indices.filter { lines[it] == bodyMarker }.map { location(api, it) }.toSet()
            assertEquals(2, declarations.size); declarations
        } else setOf(body)
        val localValue = if (keyword == "typedef") "$indent$selectedName value = " + when (binding) {
            "scope-typedef-pointer-shadow" -> "&number"; "scope-typedef-nested" -> "4"; else -> "2"
        }
            else "$indent$keyword $selectedName " + when (keyword) { "struct" -> "item"; "enum" -> "state = READY"; else -> "box" }
        val firstDeclarationLine = if (nestedForward) lines.indexOfFirst { it == "$indent$keyword $selectedName;" } else bodyLine
        val scopeEnd = lines.indices.first { it > bodyLine && lines[it] == if (nested) "    }" else "}" }
        val uses = lines.mapIndexedNotNull { line, text ->
            if (line > firstDeclarationLine && line < scopeEnd &&
                (text.startsWith(localValue) || nestedForward && text.startsWith("$indent$keyword $selectedName *before"))) location(api, line) else null
        }.toSet()
        assertEquals(if (nestedForward) 2 else 1, uses.size)
        assertEquals(body, definition, "The use cursor must resolve to its local complete definition")
        val actualUses = assertNotNull(references).map { it.jsonObject }
        assertEquals(uses.size, actualUses.size); assertEquals(uses, actualUses.toSet())
        val declared = assertNotNull(declaredReferences).map { it.jsonObject }
        assertEquals(uses.size + familyDeclarations.size, declared.size)
        assertEquals(uses + familyDeclarations, declared.toSet())
        (actualUses + declared + body).forEach { verifyLocation(it, selectedName) }
        val allDeclarations = listOf(api, main, header).flatMap(::declarations)
        assertEquals(if (binding.startsWith("scope-block-") || binding in setOf("scope-enum-block", "scope-typedef-block")) 2 else if (nestedForward || binding == "scope-typedef-repeated-alias") 4 else 3, allDeclarations.size)
        assertTrue(allDeclarations.containsAll(familyDeclarations))
        val symbols = assertNotNull(workspaceSymbols).map { it.jsonObject }
        symbols.forEach { verifyLocation(it.getValue("location").jsonObject, it.getValue("name").jsonPrimitive.content) }
        val named = symbols.filter { it.getValue("name").jsonPrimitive.content == selectedName }
            .map { it.getValue("location").jsonObject }
        assertEquals(allDeclarations.size, named.size); assertEquals(allDeclarations.toSet(), named.toSet())
        val outline = assertNotNull(documentSymbols).map { it.jsonObject }
        val namedOutline = outline.filter { it.getValue("name").jsonPrimitive.content == selectedName }.map { item -> buildJsonObject {
            put("uri", requireNotNull(root).resolve(api).toUri().toString()); put("range", item.getValue("selectionRange"))
        } }
        val apiDeclarations = declarations(api)
        assertEquals(apiDeclarations.size, namedOutline.size); assertEquals(apiDeclarations.toSet(), namedOutline.toSet())
        outline.forEach { item -> verifyLocation(buildJsonObject {
            put("uri", requireNotNull(root).resolve(api).toUri().toString()); put("range", item.getValue("selectionRange"))
        }, item.getValue("name").jsonPrimitive.content) }
    }

    private fun verifyRecordLocations() {
        val record = assertNotNull(recordRead)
        val keyword = record.getValue("keyword").jsonPrimitive.content
        val complete = record.getValue("hasCompleteDefinition").jsonPrimitive.boolean
        val shared = record.getValue("profile").jsonPrimitive.content == "header"
        fun location(file: Path, line: Int, column: Int) = buildJsonObject {
            put("uri", requireNotNull(root).resolve(file).toUri().toString())
            put("range", buildJsonObject {
                put("start", buildJsonObject { put("line", line); put("character", column) })
                put("end", buildJsonObject { put("line", line); put("character", column + selectedName.length) })
            })
        }
        fun tagLocations(file: Path, select: (String) -> Boolean): Set<JsonObject> = originals.getValue(file).lines()
            .mapIndexedNotNull { line, text ->
                val at = text.indexOf("$keyword $selectedName")
                if (at < 0 || !select(text)) null else location(file, line, at + keyword.length + 1)
            }.toSet()
        fun declaration(text: String) = text.trim() == "$keyword $selectedName;" || text.contains("$keyword $selectedName {")
        val familyFiles = if (shared) listOf(header, api, main) else listOf(api)
        val familyDeclarations = familyFiles.flatMap { tagLocations(it, ::declaration) }.toSet()
        assertEquals(if (complete) 2 else 1, familyDeclarations.size)
        val expectedUses = familyFiles.filter { it.toString().endsWith(".c") }
            .flatMap { tagLocations(it) { text -> !declaration(text) } }.toSet()
        assertEquals(if (shared) 3 else 2, expectedUses.size)
        if (complete) assertEquals(tagLocations(api) { it.contains("$keyword $selectedName {") }.single(), definition)
        else assertEquals(JsonNull, definition, "An incomplete native record has no complete definition")
        val uses = assertNotNull(references).map { it.jsonObject }
        val declared = assertNotNull(declaredReferences).map { it.jsonObject }
        assertEquals(expectedUses.size, uses.size); assertEquals(expectedUses, uses.toSet())
        assertEquals(expectedUses.size + familyDeclarations.size, declared.size)
        assertEquals(expectedUses + familyDeclarations, declared.toSet())
        (uses + declared).forEach { verifyLocation(it, selectedName) }
        val symbols = assertNotNull(workspaceSymbols).map { it.jsonObject }
        symbols.forEach { verifyLocation(it.getValue("location").jsonObject, it.getValue("name").jsonPrimitive.content) }
        val allDeclarations = listOf(header, api, main).flatMap { tagLocations(it, ::declaration) }.toSet()
        assertEquals(allDeclarations, symbols.filter { it.getValue("name").jsonPrimitive.content == selectedName }
            .map { it.getValue("location").jsonObject }.toSet())
        val outline = assertNotNull(documentSymbols).map { it.jsonObject }
        outline.forEach { item -> verifyLocation(buildJsonObject {
            put("uri", requireNotNull(root).resolve(queryFile).toUri().toString()); put("range", item.getValue("selectionRange"))
        }, item.getValue("name").jsonPrimitive.content) }
        assertEquals(tagLocations(queryFile, ::declaration), outline.filter { it.getValue("name").jsonPrimitive.content == selectedName }
            .map { item -> buildJsonObject {
                put("uri", requireNotNull(root).resolve(queryFile).toUri().toString()); put("range", item.getValue("selectionRange"))
            } }.toSet())
    }

    fun verifyDisposition(expected: String) {
        if (expected == "native symbol absent") {
            assertNull(failure)
            assertEquals(JsonNull, definition)
            assertEquals(JsonArray(emptyList()), references)
        } else {
            val refused = assertNotNull(failure, "Unavailable native analysis must not return successful empty locations")
            assertNull(definition); assertNull(references)
            if (binding == "header-prototype-disagreement") {
                assertEquals(org.refactorkit.core.JsonRpcErrorCodes.PLAN_REFUSED, refused.code)
                assertTrue(refused.message.orEmpty().contains("BINDING_MISMATCH"), refused.message)
                assertNull(workspaceSymbols); assertNull(documentSymbols)
            }
            if (expected == "invalid params") assertEquals(org.refactorkit.core.JsonRpcErrorCodes.INVALID_PARAMS, refused.code)
            else assertEquals(expected, refused.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content)
        }
    }

    private fun verifyLocation(location: JsonObject, expectedName: String) {
        val workspace = requireNotNull(root)
        val file = Path.of(java.net.URI(location.getValue("uri").jsonPrimitive.content))
        val sdkRoot = externalSdk
        val text = if (file.startsWith(workspace)) originals.getValue(workspace.relativize(file)) else {
            assertNotNull(sdkRoot); assertTrue(file.startsWith(sdkRoot))
            sdkOriginals.getValue(sdkRoot.relativize(file))
        }
        val range = location.getValue("range").jsonObject
        val start = range.getValue("start").jsonObject; val end = range.getValue("end").jsonObject
        assertEquals(start.getValue("line"), end.getValue("line"))
        assertEquals(expectedName, text.lines()[start.getValue("line").jsonPrimitive.int].substring(start.getValue("character").jsonPrimitive.int, end.getValue("character").jsonPrimitive.int))
    }

    fun verifyIntact() {
        val workspace = requireNotNull(root)
        originals.forEach { (file, text) -> assertEquals(text, Files.readString(workspace.resolve(file))) }
        externalSdk?.let { sdkRoot ->
            val saved = Files.walk(sdkRoot).use { paths -> paths.filter { Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }.toList()
                .associate { sdkRoot.relativize(it) to Files.readString(it) } }
            assertEquals(sdkOriginals, saved)
        }
        assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        Files.walk(workspace).use { files -> assertFalse(files.anyMatch { it.fileName.toString().endsWith(".o") }) }
        assertEquals(privateBefore, privateDirectories())
        ProcessHandle.current().descendants().use { children ->
            assertFalse(children.anyMatch { it.info().command().orElse("").startsWith(sdk.toString()) })
        }
    }

    private fun verifySdkLocations() {
        val workspace = requireNotNull(root)
        val sdkRoot = requireNotNull(externalSdk)
        val sdkHeader = sdkRoot.resolve("usr/include/api.h").toUri().toString()
        val symbols = assertNotNull(workspaceSymbols).map { it.jsonObject }
        assertTrue(symbols.map { it.getValue("name").jsonPrimitive.content }.toSet()
            .containsAll(setOf("compute", "value_t", "meter", "reading", "main", "item", "value", "input")))
        assertTrue(symbols.any { it.getValue("location").jsonObject.getValue("uri").jsonPrimitive.content == sdkHeader })
        symbols.forEach { verifyLocation(it.getValue("location").jsonObject, it.getValue("name").jsonPrimitive.content) }
        val outline = assertNotNull(documentSymbols).map { it.jsonObject }
        assertEquals(setOf("main", "item", "value"), outline.map { it.getValue("name").jsonPrimitive.content }.toSet())
        outline.forEach { item -> verifyLocation(buildJsonObject {
            put("uri", workspace.resolve(queryFile).toUri().toString()); put("range", item.getValue("selectionRange"))
        }, item.getValue("name").jsonPrimitive.content) }
        val found = assertNotNull(definition).jsonObject
        assertEquals(if (binding == "function") workspace.resolve(api).toUri().toString() else sdkHeader,
            found.getValue("uri").jsonPrimitive.content)
        verifyLocation(found, selectedName)
        val uses = assertNotNull(references).map { it.jsonObject }
        val declarations = assertNotNull(declaredReferences).map { it.jsonObject }
        assertEquals(when (binding) { "function" -> 1; "typedef" -> 5; else -> 2 }, uses.size)
        assertEquals(uses.size + if (binding == "function") 2 else 1, declarations.size)
        assertTrue(uses.any { Path.of(java.net.URI(it.getValue("uri").jsonPrimitive.content)).startsWith(workspace) })
        assertTrue(declarations.any { it.getValue("uri").jsonPrimitive.content == sdkHeader })
        (uses + declarations).forEach { verifyLocation(it, selectedName) }
        assertFalse(".refactorkit/readonly-sdk" in symbols.toString() + found + uses + declarations)
    }

    private fun privateDirectories(): Set<Path> = Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { paths ->
        paths.filter { it.fileName.toString().startsWith("refactorkit-clangd-launch-") || it.fileName.toString().startsWith("refactorkit-semantic-overlay-") }.toList().toSet()
    }
    override fun close() {
        packaged?.close()
        externalSdk?.let { sdkRoot -> Files.walk(sdkRoot).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
        root?.let { workspace -> Files.walk(workspace).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
}
