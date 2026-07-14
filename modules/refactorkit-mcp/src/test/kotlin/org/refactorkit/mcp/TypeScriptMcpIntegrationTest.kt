package org.refactorkit.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.ExternalFileEditProposal
import org.refactorkit.core.ExternalWorkspaceEditNormalization
import org.refactorkit.core.ExternalWorkspaceEditNormalizer
import org.refactorkit.core.ExternalWorkspaceEditProposal
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.Reference
import org.refactorkit.core.SourceLocation
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.Symbol
import org.refactorkit.core.SymbolId
import org.refactorkit.core.SymbolIndex
import org.refactorkit.core.SymbolResolution
import org.refactorkit.core.TextEdit
import org.refactorkit.treesitter.ExternalSemanticDiagnostics
import org.refactorkit.treesitter.ExternalSemanticSessionProvenance
import org.refactorkit.typescript.ToolchainFileEvidence
import org.refactorkit.typescript.TypeScriptSemanticAdapter
import org.refactorkit.typescript.TypeScriptSemanticClient
import org.refactorkit.typescript.TypeScriptSemanticToolchain
import org.refactorkit.typescript.TypeScriptToolchainDiscovery
import org.refactorkit.typescript.TypeScriptToolchainProvenance
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TypeScriptMcpIntegrationTest {
    @Test
    fun mcpToolsRunSemanticRenameThroughManagedApplyAndRollback() {
        val root = Files.createTempDirectory("refactorkit-mcp-ts")
        Files.createDirectories(root.resolve("src"))
        root.resolve("src/service.ts").writeText("export class Service {}\n")
        root.resolve("tsconfig.json").writeText(
            """{"compilerOptions":{"rootDir":"src"},"include":["src/**/*.ts"]}""",
        )
        val client = FakeClient()
        val session = McpSession(
            toolchainDiscovery = { _, _ -> TypeScriptToolchainDiscovery.Available(toolchain()) },
            semanticAdapterFactory = { languageId, toolchain, model ->
                TypeScriptSemanticAdapter(languageId, toolchain, model, client)
            },
        )
        assertTrue(call(session, "project_scan", args("root" to root.toString())).contains("Files: 1"))
        val started = call(session, "typescript_semantic_start", args(
            "languageId" to "typescript", "nodeExecutable" to ".",
            "languageServerPackageRoot" to ".", "typeScriptPackageRoot" to ".",
        ))
        assertTrue(started.contains("Started typescript"))
        val lease = Regex("Semantic lease: (semantic-[0-9a-f-]+)").find(started)!!.groupValues[1]
        val snapshotHash = Regex("Snapshot: ([0-9a-f]{64})").find(started)!!.groupValues[1]
        val diagnostics = call(session, "diagnostics_v2", buildJsonObject {
            put("requestId", "mcp-request-1")
            put("languageId", "typescript")
            put("expectedSnapshotHash", snapshotHash)
            put("semanticLease", lease)
            put("sourceAuthority", buildJsonObject { put("kind", "saved-disk") })
        })
        assertEquals("ready", Json.parseToJsonElement(diagnostics).jsonObject.getValue("status").jsonPrimitive.content)
        val search = call(session, "symbol_search", args("languageId" to "typescript", "query" to "Service"))
        assertTrue(search.contains("src/service.ts::Service@0:13"))

        val preview = call(session, "preview_refactoring", buildJsonObject {
            put("operation", "renameSymbol")
            put("languageId", "typescript")
            put("symbol", "src/service.ts::Service@0:13")
            put("arguments", buildJsonObject { put("newName", "AccountService") })
        })
        val planId = Regex("Plan ID\\s+: ([^\\s]+)").find(preview)!!.groupValues[1]
        val applied = call(session, "apply_refactoring", args("planId" to planId))
        val transactionId = Regex("Transaction ID: ([^\\s]+)").find(applied)!!.groupValues[1]
        assertEquals("export class AccountService {}\n", root.resolve("src/service.ts").readText())
        assertTrue(Files.isRegularFile(root.resolve(".refactorkit/transactions/$transactionId.json")))

        call(session, "rollback_refactoring", args("transactionId" to transactionId))
        assertEquals("export class Service {}\n", root.resolve("src/service.ts").readText())
        session.close()
    }

    private fun call(session: McpSession, name: String, arguments: JsonObject): String {
        val response = session.dispatch("tools/call", buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        }).jsonObject
        assertTrue(response["isError"] == null || !response["isError"]!!.jsonPrimitive.content.toBoolean())
        return response["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
    }

    private fun args(vararg values: Pair<String, String>) = buildJsonObject {
        values.forEach { (key, value) -> put(key, value) }
    }

    private fun toolchain(): TypeScriptSemanticToolchain {
        val root = Files.createTempDirectory("refactorkit-mcp-ts-toolchain")
        val files = listOf("node", "server-package.json", "cli.mjs", "typescript-package.json", "tsserver.js", "typescript.js")
            .associateWith { root.resolve(it).also { path -> path.writeText(it) } }
        return TypeScriptSemanticToolchain(
            files.getValue("node"), files.getValue("cli.mjs"), files.getValue("tsserver.js"),
            listOf(files.getValue("node").toString(), files.getValue("cli.mjs").toString(), "--stdio"),
            TypeScriptToolchainProvenance(
                nodeVersion = "22.1.0", languageServerVersion = "4.3.3", typeScriptVersion = "5.7.2",
                evidence = listOf(
                    evidence("node-executable", files.getValue("node")),
                    evidence("language-server-package", files.getValue("server-package.json")),
                    evidence("language-server-entrypoint", files.getValue("cli.mjs")),
                    evidence("typescript-package", files.getValue("typescript-package.json")),
                    evidence("typescript-server-entrypoint", files.getValue("tsserver.js")),
                    evidence("typescript-compiler-entrypoint", files.getValue("typescript.js")),
                ),
            ),
        )
    }

    private fun evidence(role: String, path: Path): ToolchainFileEvidence {
        val bytes = Files.readAllBytes(path)
        return ToolchainFileEvidence(
            role, path, MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }, bytes.size.toLong(),
        )
    }

    private class FakeClient : TypeScriptSemanticClient {
        override var isRunning = false
        override val provenance: ExternalSemanticSessionProvenance? = null
        override fun start(snapshot: ProjectSnapshot) { isRunning = true }
        override fun supports(capability: String) = capability in setOf(
            "definitionProvider", "referencesProvider", "renameProvider", "prepareRenameProvider",
            "documentSymbolProvider", "workspaceSymbolProvider", "textDocumentSync",
        )
        override fun buildSymbols(snapshot: ProjectSnapshot) = SymbolIndex(listOf(symbol()))
        override fun searchWorkspaceSymbols(query: String) = listOf(symbol()).filter { query in it.name }
        override fun resolveSymbol(location: SourceLocation) = SymbolResolution(symbol())
        override fun findReferences(symbolId: SymbolId) = listOf(Reference(symbolId, location()))
        override fun diagnostics(snapshot: ProjectSnapshot): List<Diagnostic> = emptyList()
        override fun synchronizedDiagnostics(snapshot: ProjectSnapshot) = ExternalSemanticDiagnostics.Available(emptyList())
        override fun requestRename(snapshot: ProjectSnapshot, location: SourceLocation, newName: String): ExternalWorkspaceEditNormalization =
            ExternalWorkspaceEditNormalizer().normalize(snapshot, ExternalWorkspaceEditProposal(
                "lsp-typescript", "test", listOf(ExternalFileEditProposal.Modify(
                    Path.of("src/service.ts"), listOf(TextEdit(location().range, newName)), 1,
                )),
            ))
        override fun close() { isRunning = false }
        private fun symbol() = Symbol(SymbolId("src/service.ts::Service@0:13"), "Service", Symbol.Kind.CLASS, location(), "typescript")
        private fun location() = SourceLocation(
            Path.of("src/service.ts"), SourceRange(SourcePosition(0, 13), SourcePosition(0, 20)),
        )
    }
}
