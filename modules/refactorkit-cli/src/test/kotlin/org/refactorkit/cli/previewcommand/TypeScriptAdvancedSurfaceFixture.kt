package org.refactorkit.cli.previewcommand

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.SemanticProcessSpec
import org.refactorkit.lsp.LspSession
import org.refactorkit.cli.RefactorKitCli
import org.refactorkit.daemon.DaemonSession
import org.refactorkit.mcp.McpSession
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Authored sources and real public dispatchers; toolchain files are borrowed, never copied. */
internal class TypeScriptAdvancedSurfaceFixture(private val surface: String) : AutoCloseable {
    private val root = Files.createTempDirectory("rk-ts-public-migration-")
    private val before = linkedMapOf(
        "package.json" to "{\"name\":\"authored-surface\",\"private\":true,\"scripts\":{\"build\":\"node -e \\\"require('fs').writeFileSync('BUILD_EXECUTED','bad')\\\"\"}}\n",
        "tsconfig.json" to "{\n// Preserve 🧭 ./packages/lib\n\"files\":[],\"references\":[{\"path\":\"./packages/lib\"},{\"path\":\"./packages/app\"}]}\n",
        "packages/lib/tsconfig.json" to "{\"compilerOptions\":{\"strict\":true,\"composite\":true,\"rootDir\":\".\",\"outDir\":\"../../dist/lib\",\"target\":\"ES2020\",\"module\":\"commonjs\"},\"include\":[\"*.ts\"]}\n",
        "packages/lib/library.ts" to "export function twice(value: number): number { return value * 2; }\n",
        "packages/app/tsconfig.json" to "{\"compilerOptions\":{\"strict\":true,\"composite\":true,\"rootDir\":\".\",\"outDir\":\"../../dist/app\",\"target\":\"ES2020\",\"module\":\"commonjs\"},\"include\":[\"*.ts\"],\"references\":[{\"path\":\"../lib\"}]}\n",
        "packages/app/app.ts" to "import { twice } from '../lib/library';\nexport const result = twice(3) + 1;\n",
    )
    private var after = before.map { (path, text) ->
        val destination = if (path.startsWith("packages/lib/")) "packages/domain/" + path.removePrefix("packages/lib/") else path
        destination to when (path) {
            "tsconfig.json" -> text.replace("\"./packages/lib\"", "\"./packages/domain\"")
            "packages/app/tsconfig.json" -> text.replace("\"../lib\"", "\"../domain\"")
            "packages/app/app.ts" -> text.replace("'../lib/library'", "'../domain/library'")
            else -> text
        }
    }.toMap()
    private var daemon: DaemonSession? = null
    private var mcp: McpSession? = null
    private val packaged = System.getProperty("refactorkit.ts.packaged.root")?.let {
        TypeScriptPackagedSurface(Path.of(it), root)
    }
    private var previewJson: JsonObject? = null
    private var previewText: String? = null
    private var catalogueText: String? = null
    private var recipeMode = false
    private var expectedOperation = "projectReferenceMigration"
    private var expectedEditCount = 5
    private var actionArguments: JsonObject? = null
    private var arithmeticAction = false
    private var mutationPaths = emptySet<String>()
    private var refusal: String? = null
    private var lspCapabilities: JsonObject? = null
    private var lspRefusal: JsonRpcException? = null
    private lateinit var planId: String
    private lateinit var lease: String
    private lateinit var snapshotHash: String
    private lateinit var revokedPlan: String
    private val repository = run {
        var path = Path.of("").toAbsolutePath()
        while (!Files.isRegularFile(path.resolve("settings.gradle.kts"))) path = requireNotNull(path.parent)
        path
    }
    private val recipePath get() = repository.resolve("recipes/typescript/relocate-source.yml")
    private val toolchain = run {
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "node.exe" else "node"
        val node = System.getenv("PATH").split(System.getProperty("path.separator"))
            .map { Path.of(it).resolve(executable) }.first { Files.isRegularFile(it) && Files.isExecutable(it) }
        buildJsonObject {
            put("languageId", "typescript"); put("nodeExecutable", node.toString())
            put("languageServerPackageRoot", repository.resolve("qualification/typescript-toolchain/node_modules/typescript-language-server").toString())
            put("typeScriptPackageRoot", repository.resolve("qualification/typescript-toolchain/node_modules/typescript").toString())
        }
    }

    init { try { before.forEach { (p, t) -> Files.createDirectories(root.resolve(p).parent); Files.writeString(root.resolve(p), t) } } catch (failure: Exception) { close(); throw failure } }

    private fun arguments() = actionArguments ?: if (recipeMode) buildJsonObject {
        put("recipeYaml", Files.readString(recipePath)); put("param.file", "packages/lib/library.ts"); put("param.targetFile", "packages/lib/renamed.ts")
    } else buildJsonObject { put("fromDirectory", "packages/lib"); put("toDirectory", "packages/domain") }

    fun previewRecipe() {
        recipeMode = true
        expectedOperation = "sourceFileRelocation"
        expectedEditCount = 2
        after = before.map { (path, text) ->
            (if (path == "packages/lib/library.ts") "packages/lib/renamed.ts" else path) to
                (if (path == "packages/app/app.ts") text.replace("'../lib/library'", "'../lib/renamed'") else text)
        }.toMap()
        preview()
    }

    fun previewOrganize(mode: String) {
        expectedOperation = "organizeImports"
        expectedEditCount = 1
        val file = "packages/lib/imports.ts"
        before[file] = "import { zebra, unused, alpha } from './dep';\nexport const total = zebra + alpha;\n"
        before["packages/lib/dep.ts"] = "export const alpha = 1;\nexport const zebra = 2;\nexport const unused = 3;\n"
        before.forEach { (p, t) -> Files.writeString(root.resolve(p), t) }
        val names = when (mode) { "All" -> "alpha, zebra"; "SortAndCombine" -> "alpha, unused, zebra"; "RemoveUnused" -> "zebra, alpha"; else -> error(mode) }
        after = before + (file to "import { $names } from './dep';\nexport const total = zebra + alpha;\n")
        actionArguments = buildJsonObject { put("file", file); put("mode", mode); put("indentSize", "2"); put("tabSize", "2"); put("quotePreference", "single") }
        preview()
    }

    fun previewAction(operation: String, compositeCaller: Boolean = false) {
        arithmeticAction = true
        expectedOperation = operation
        expectedEditCount = if (operation == "moveDeclaration") 3 else 1
        val library = "packages/lib/library.ts"
        before[library] = when (operation) {
            "extractFunction", "extractConstant" -> "export function evaluate(value: number): number {\n  return value * 2 + 1;\n}\n"
            "inlineVariable" -> "export function evaluate(value: number): number {\n  const doubled = value * 2;\n  return doubled + 1;\n}\n"
            "moveDeclaration" -> "export function twice(value: number): number { return value * 2; }\nexport function evaluate(value: number): number { return twice(value) + 1; }\n"
            else -> error("Unknown action: $operation")
        }
        before["packages/app/app.ts"] = if (operation == "moveDeclaration")
            "import { twice } from '../lib/library';\nexport const result = twice(3) + 1;\n" else
            "import { evaluate } from '../lib/library';\nexport const result = evaluate(3);\n"
        if (operation == "moveDeclaration") {
            before["packages/lib/target.ts"] = "export const stable = 3;\n"
            if (!compositeCaller) {
                // Native move-to-file is qualified within one configured program, not by omitting foreign callers.
                listOf("packages/lib/tsconfig.json", "packages/app/tsconfig.json").forEach { before.remove(it); Files.delete(root.resolve(it)) }
                before["tsconfig.json"] = "{\"compilerOptions\":{\"strict\":true,\"rootDir\":\".\",\"target\":\"ES2020\",\"module\":\"commonjs\"},\"include\":[\"packages/**/*.ts\"]}\n"
            }
        }
        before.forEach { (p, t) -> Files.writeString(root.resolve(p), t) }
        mutationPaths = if (operation == "moveDeclaration") setOf(library, "packages/lib/target.ts", "packages/app/app.ts") else setOf(library)
        val coordinates = when (operation) { "moveDeclaration" -> listOf(0, 0, 1, 0); "inlineVariable" -> listOf(1, 8, 1, 15); else -> listOf(1, 9, 1, 18) }
        actionArguments = buildJsonObject {
            put("file", library); put("startLine", coordinates[0]); put("startCharacter", coordinates[1]); put("endLine", coordinates[2]); put("endCharacter", coordinates[3])
            put("refactor", when (operation) { "moveDeclaration" -> "Move to file"; "inlineVariable" -> "Inline variable"; else -> "Extract Symbol" })
            put("action", when (operation) { "moveDeclaration" -> "Move to file"; "inlineVariable" -> "Inline variable"; "extractFunction" -> "function_scope_0"; else -> "constant_scope_0" })
            put("indentSize", "2"); put("tabSize", "2"); put("quotePreference", "single")
            if (operation == "moveDeclaration") put("targetFile", "packages/lib/target.ts")
        }
        assertArithmetic()
        preview()
    }

    fun previewCompositeMoveRefusal() {
        assertEquals("MCP", surface)
        previewAction("moveDeclaration", compositeCaller = true)
        refusal = requireNotNull(previewText)
        assertTrue(Regex("\\(TS[0-9]+\\).*twice").containsMatchIn(requireNotNull(refusal)), refusal)
    }

    private fun assertActionImage() {
        val current = image()
        assertEquals(before.keys, current.keys)
        assertEquals(mutationPaths, before.keys.filter { before[it] != current[it] }.toSet())
        before.filterKeys { it !in mutationPaths }.forEach { (p, t) -> assertEquals(t, current.getValue(p)) }
        if (expectedOperation == "moveDeclaration") {
            assertEquals(before.getValue("packages/app/app.ts").replace("'../lib/library'", "'../lib/target'"), current.getValue("packages/app/app.ts"))
            assertTrue(current.getValue("packages/lib/target.ts").contains("export const stable = 3;"))
        }
        assertArithmetic()
    }

    private fun assertArithmetic() {
        val source = buildJsonObject { image().filterKeys { it.endsWith(".ts") }.forEach { (p, t) -> put(p, t) } }.toString()
        val script = """
            const ts = require(process.argv[1]), files = JSON.parse(process.argv[2]), vm = require('node:vm'), path = require('node:path').posix;
            const cache = {};
            function load(name) {
              if (!(name in files)) throw Error('Not an authored fixture module');
              if (cache[name]) return cache[name];
              const exports = {}; cache[name] = exports;
              const code = ts.transpileModule(files[name], {compilerOptions:{module:1,target:7}}).outputText;
              vm.runInNewContext(code, {exports, require(spec) {
                if (!spec.startsWith('.')) throw Error('External fixture modules denied');
                return load(path.normalize(path.join(path.dirname(name), spec)) + '.ts');
              }}, {timeout:1000});
              return exports;
            }
            const library = load('packages/lib/library.ts'), app = load('packages/app/app.ts');
            console.log(JSON.stringify([-3,0,2].map(library.evaluate).concat(app.result)));
        """.trimIndent()
        ExternalSemanticProcessManager().use { manager ->
            val process = manager.launch(SemanticProcessSpec("public-arithmetic", Path.of(toolchain.getValue("nodeExecutable").jsonPrimitive.content),
                listOf("--max-old-space-size=128", "-e", script, Path.of(toolchain.getValue("typeScriptPackageRoot").jsonPrimitive.content).resolve("lib/typescript.js").toString(), source), root))
            check(process.awaitExit(15_000)) { "Authored arithmetic timed out" }
            assertEquals(0, process.exitCode, process.stderrText())
            assertEquals("[-5,1,5,7]", process.output.bufferedReader().readText().trim())
        }
    }

    private fun startSession() {
        when (surface) {
            "CLI" -> Unit
            "daemon" -> {
                if (packaged == null) daemon = DaemonSession()
                val opened = daemonDispatch("project.open", buildJsonObject { put("root", root.toString()) }).jsonObject
                val started = daemonDispatch("typescript.semantic.start", toolchain).jsonObject
                lease = started.getValue("semanticLease").jsonPrimitive.content
                snapshotHash = opened.getValue("snapshotHash").jsonPrimitive.content
            }
            "MCP" -> {
                if (packaged == null) mcp = McpSession() else {
                    packaged.dispatch("MCP", "initialize", buildJsonObject {
                        put("protocolVersion", "2024-11-05"); put("capabilities", buildJsonObject {})
                        put("clientInfo", buildJsonObject { put("name", "packaged-story"); put("version", "1") })
                    })
                    packaged.notifyInitialized()
                }
                mcpCall("project_scan", buildJsonObject { put("root", root.toString()) })
                val started = mcpCall("typescript_semantic_start", toolchain)
                lease = Regex("Semantic lease: ([^. ]+)").find(started)?.groupValues?.get(1) ?: error(started)
                snapshotHash = Regex("Snapshot: ([a-f0-9]{64})").find(started)?.groupValues?.get(1) ?: error(started)
            }
            else -> error("Unknown surface: $surface")
        }
    }

    fun preview() {
        startSession()
        when (surface) {
            "CLI" -> previewJson = Json.parseToJsonElement(cli(cliArguments())).jsonObject
            "daemon" -> previewJson = daemonDispatch("refactor.preview", previewArguments()).jsonObject
            "MCP" -> previewText = mcpCall("preview_refactoring", previewArguments())
        }
    }

    fun requestRefusal(operation: String, authority: String) {
        startSession()
        val values = buildJsonObject {
            put("file", "packages/lib/library.ts"); put("symbolName", "twice"); put("targetFile", "packages/app/app.ts")
            put("newSignature", "(value: number): number"); put("startLine", "0"); put("endLine", "1"); put("methodName", "extracted")
        }
        if (surface == "CLI") {
            refusal = cli(cliArguments(operation = operation, values = values), expectedExit = 1)
            return
        }
        val request = buildJsonObject {
            put("operation", operation); put("languageId", "typescript"); put("arguments", values)
            put("semanticLease", if (authority == "a foreign lease") "foreign-lease" else lease)
            put("expectedSnapshotHash", if (authority == "a stale hash") "0".repeat(64) else snapshotHash)
        }
        refusal = if (surface == "daemon") {
            assertFailsWith<JsonRpcException> { daemonDispatch("refactor.preview", request) }.let { "${it.message} ${it.data}" }
        } else mcpCall("preview_refactoring", request)
    }

    fun assertRefusal(code: String) {
        assertTrue(requireNotNull(refusal).contains(code), refusal)
        assertEquals(0, transactions().size)
        assertEquals(before, image())
        if (surface == "MCP") {
            val refusedPlan = Regex("plan-[a-f0-9-]+").find(requireNotNull(refusal))?.value ?: error("Refusal plan identity missing")
            assertFailsWith<JsonRpcException> { mcpCall("apply_refactoring", buildJsonObject { put("planId", refusedPlan) }) }
            assertEquals(0, transactions().size)
            assertEquals(before, image())
        }
    }

    fun replaceRecipeSession() {
        revokedPlan = planId
        val previousLease = lease
        val language = buildJsonObject { put("languageId", "typescript") }
        if (surface == "daemon") {
            daemonDispatch("typescript.semantic.stop", language)
            val started = daemonDispatch("typescript.semantic.start", toolchain).jsonObject
            lease = started.getValue("semanticLease").jsonPrimitive.content
            previewJson = daemonDispatch("refactor.preview", previewArguments()).jsonObject
        } else {
            assertEquals("MCP", surface)
            mcpCall("typescript_semantic_stop", language)
            val started = mcpCall("typescript_semantic_start", toolchain)
            lease = Regex("Semantic lease: ([^. ]+)").find(started)?.groupValues?.get(1) ?: error(started)
            previewText = mcpCall("preview_refactoring", previewArguments())
        }
        assertTrue(previousLease != lease)
        assertPreview()
        assertTrue(revokedPlan != planId)
    }

    fun rejectOldRecipeThenApplyNew() {
        val request = buildJsonObject { put("planId", revokedPlan) }
        val message = if (surface == "daemon") {
            assertFailsWith<JsonRpcException> { daemonDispatch("refactor.apply", request) }.message.orEmpty()
        } else mcpCall("apply_refactoring", request)
        assertTrue(message.contains("diagnostic", ignoreCase = true), message)
        assertFalse(message.contains("Plan not found"), "This scenario must exercise the retained gate, not a missing plan")
        assertEquals(0, transactions().size)
        assertEquals(before, image())
        applyAndRollback()
    }

    fun inspectLspOwnership() {
        assertEquals("LSP", surface)
        check(packaged == null) { "LSP client ownership has a separate framed-protocol qualification" }
        val session = LspSession()
        lspCapabilities = session.dispatch("initialize", buildJsonObject { put("rootUri", root.toUri().toString()) }).jsonObject.getValue("capabilities").jsonObject
        session.dispatch("initialized", null)
        lspRefusal = assertFailsWith<JsonRpcException> {
            session.dispatch("workspace/executeCommand", buildJsonObject { put("command", "refactorkit.projectReferenceMigration") })
        }
        session.dispatch("shutdown", null)
        session.dispatch("exit", null)
    }

    fun assertLspOwnership() {
        val capabilities = requireNotNull(lspCapabilities)
        val ownership = capabilities.getValue("experimental").jsonObject.getValue("refactorkitSemanticOwnership").jsonObject
        assertEquals("client-managed-native-lsp", ownership.getValue("typescript").jsonPrimitive.content)
        assertEquals("client-managed-native-lsp", ownership.getValue("javascript").jsonPrimitive.content)
        assertEquals(setOf("cli", "daemon", "mcp"), ownership.getValue("managedMutationSurfaces").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, requireNotNull(lspRefusal).code)
        assertTrue(requireNotNull(lspRefusal).message.orEmpty().contains("Unknown command"), lspRefusal.toString())
        assertEquals(before, image())
        assertEquals(0, transactions().size)
    }

    fun help() { catalogueText = cli(listOf("--help")) }

    fun assertHelp() {
        val help = requireNotNull(catalogueText)
        for (expected in listOf("refactorings", "typescript refactor <root>", "--operation <id>", "--arguments-json <object>")) {
            assertTrue(help.contains(expected), "CLI help omitted $expected")
        }
        assertFalse(::lease.isInitialized)
        assertEquals(0, transactions().size)
        assertEquals(before, image())
    }

    fun catalogue() {
        startSession()
        val args = buildJsonObject { put("languageId", "typescript") }
        catalogueText = when (surface) {
            "CLI" -> cli(cliArguments("refactorings"))
            "daemon" -> daemonDispatch("typescript.refactorings", args).toString()
            else -> mcpCall("available_refactorings", args)
        }
    }

    fun assertCatalogue() {
        val rows = Json.parseToJsonElement(requireNotNull(catalogueText)).jsonArray
        assertEquals(setOf("renameSymbol", "organizeImports", "sourceFileRelocation", "extractFunction", "extractConstant", "inlineVariable", "moveDeclaration", "projectReferenceMigration"),
            rows.map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet())
        assertEquals(8, rows.size)
        assertFalse(::planId.isInitialized)
        assertEquals(0, transactions().size)
        assertEquals(before, image())
    }

    private fun previewArguments() = buildJsonObject {
        put("operation", if (recipeMode) "recipe" else expectedOperation); put("languageId", "typescript")
        put("semanticLease", lease); put("expectedSnapshotHash", snapshotHash); put("arguments", arguments())
    }

    fun assertPreview() {
        if (surface == "MCP") {
            val text = requireNotNull(previewText)
            assertTrue(text.contains("Status   : PREVIEW"), text)
            assertTrue(text.contains("COMPILER_PROVEN"), text)
            planId = Regex("plan-[a-f0-9-]+").find(text)?.value ?: error(text)
        } else {
            val json = requireNotNull(previewJson)
            assertEquals("PREVIEW", json.getValue("status").jsonPrimitive.content)
            assertEquals(expectedOperation, json.getValue("operation").jsonPrimitive.content)
            assertEquals("COMPILER_PROVEN", json.getValue("evidence").jsonPrimitive.content)
            assertEquals(expectedEditCount, json.getValue("structuredDiff").jsonArray.size)
            planId = json.getValue("planId").jsonPrimitive.content
        }
        assertEquals(before, image())
        assertEquals(0, transactions().size)
    }

    fun applyAndRollback() {
        val transaction = when (surface) {
            "CLI" -> {
                // CLI apply retains a fresh child preview within its own invocation, not the closed preview session.
                val result = Json.parseToJsonElement(cli(cliArguments() + "--apply")).jsonObject
                assertEquals("applied", result.getValue("status").jsonPrimitive.content)
                assertTrue(result.getValue("planId").jsonPrimitive.content != planId)
                result.getValue("transactionId").jsonPrimitive.content
            }
            "daemon" -> {
                val result = daemonDispatch("refactor.apply", buildJsonObject { put("planId", planId) }).jsonObject
                assertEquals("applied", result.getValue("status").jsonPrimitive.content)
                result.getValue("transactionId").jsonPrimitive.content
            }
            else -> {
                val result = mcpCall("apply_refactoring", buildJsonObject { put("planId", planId) })
                assertTrue(result.startsWith("Applied successfully."), result)
                Regex("Transaction ID: ([^\\s]+)").find(result)?.groupValues?.get(1) ?: error(result)
            }
        }
        if (arithmeticAction) assertActionImage() else assertEquals(after, image())
        val journals = transactions()
        assertEquals(1, journals.size)
        val journal = Json.parseToJsonElement(Files.readString(journals.single())).jsonObject
        assertEquals("8", journal.getValue("schemaVersion").jsonPrimitive.content)
        when (surface) {
            "CLI" -> cli(listOf("patch", "rollback", transaction, "--root", root.toString()))
            "daemon" -> daemonDispatch("patch.rollback", buildJsonObject { put("transactionId", transaction) })
            else -> assertEquals("Rolled back transaction $transaction.", mcpCall("rollback_refactoring", buildJsonObject { put("transactionId", transaction) }))
        }
        assertEquals(before, image())
    }

    private fun cliArguments(command: String = "refactor", operation: String = expectedOperation, values: JsonObject = arguments()) = (if (recipeMode) listOf("recipe", "run", recipePath.toString(), "--root", root.toString(),
        "--param.file", "packages/lib/library.ts", "--param.targetFile", "packages/lib/renamed.ts") else listOf("typescript", command, root.toString()) +
        (if (command == "refactor") listOf("--operation", operation, "--arguments-json", values.toString()) else emptyList())) + listOf(
        "--node", toolchain.getValue("nodeExecutable").jsonPrimitive.content,
        "--language-server-package", toolchain.getValue("languageServerPackageRoot").jsonPrimitive.content,
        "--typescript-package", toolchain.getValue("typeScriptPackageRoot").jsonPrimitive.content)

    private fun cli(arguments: List<String>, expectedExit: Int = 0): String {
        packaged?.let { return it.cli(arguments, expectedExit) }
        val oldOut = System.out; val oldErr = System.err
        val output = ByteArrayOutputStream(); val error = ByteArrayOutputStream()
        val code = try {
            System.setOut(PrintStream(output, true, Charsets.UTF_8)); System.setErr(PrintStream(error, true, Charsets.UTF_8))
            RefactorKitCli().run(arguments)
        } finally { System.setOut(oldOut); System.setErr(oldErr) }
        assertEquals(expectedExit, code, "CLI stdout: $output\nstderr: $error")
        return output.toString(Charsets.UTF_8) + if (expectedExit == 0) "" else error.toString(Charsets.UTF_8)
    }

    private fun daemonDispatch(method: String, args: JsonObject) =
        packaged?.dispatch("daemon", method, args) ?: requireNotNull(daemon).dispatch(method, args)

    private fun mcpCall(name: String, args: JsonObject): String {
        val request = buildJsonObject { put("name", name); put("arguments", args) }
        val response = (packaged?.dispatch("MCP", "tools/call", request)
            ?: requireNotNull(mcp).dispatch("tools/call", request)).jsonObject
        assertFalse(response["isError"]?.jsonPrimitive?.content == "true", response.toString())
        return response.getValue("content").jsonArray.joinToString("\n") { it.jsonObject.getValue("text").jsonPrimitive.content }
    }

    private fun transactions(): List<Path> {
        val path = root.resolve(".refactorkit/transactions")
        return if (!Files.exists(path)) emptyList() else Files.list(path).use { paths -> paths.filter { it.toString().endsWith(".json") }.toList() }
    }

    private fun image() = Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) && !it.startsWith(root.resolve(".refactorkit")) }.toList()
        .associate { root.relativize(it).toString().replace('\\', '/') to Files.readString(it) } }

    override fun close() {
        try {
            try { daemon?.close() } finally { try { mcp?.close() } finally { packaged?.close() } }
        } finally {
            if (Files.exists(root)) Files.walk(root).use { paths -> paths.toList().sortedByDescending(Path::getNameCount).forEach {
                check(!Files.isSymbolicLink(it)); Files.delete(it)
            } }
        }
    }
}
