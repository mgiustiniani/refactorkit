package org.refactorkit.daemon

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import org.refactorkit.typescript.TypeScriptProjectModel
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

class TypeScriptDaemonIntegrationTest {
    @Test
    fun semanticReadPreviewApplyWalAndRollbackUseDaemonProtocol() {
        val root = Files.createTempDirectory("refactorkit-daemon-ts")
        Files.createDirectories(root.resolve("src"))
        root.resolve("src/service.ts").writeText("export class Service {}\n")
        root.resolve("tsconfig.json").writeText(
            """{"compilerOptions":{"rootDir":"src"},"include":["src/**/*.ts"]}""",
        )
        val toolchain = toolchain()
        val client = FakeSemanticClient()
        val session = DaemonSession(
            toolchainDiscovery = { _, _ -> TypeScriptToolchainDiscovery.Available(toolchain) },
            semanticAdapterFactory = { languageId, discovered, model ->
                TypeScriptSemanticAdapter(languageId, discovered, model, client)
            },
        )
        session.dispatch("project.open", objectParams("root" to root.toString()))
        val started = session.dispatch(
            "typescript.semantic.start", objectParams("languageId" to "typescript"),
        ).jsonObject
        assertEquals("started", started["status"]!!.jsonPrimitive.content)
        assertEquals("full-typescript", started["completeness"]!!.jsonPrimitive.content)
        client.isRunning = false
        val restarted = session.dispatch(
            "typescript.semantic.restart", objectParams("languageId" to "typescript"),
        ).jsonObject
        assertEquals("restarted", restarted["status"]!!.jsonPrimitive.content)

        val symbols = session.dispatch(
            "symbol.search", objectParams("languageId" to "typescript", "query" to "Service"),
        ).jsonArray
        val symbolId = symbols.single().jsonObject["id"]!!.jsonPrimitive.content
        val definition = session.dispatch(
            "symbol.definition", objectParams("languageId" to "typescript", "symbol" to symbolId),
        ).jsonObject
        assertEquals("Service", definition["name"]!!.jsonPrimitive.content)
        assertEquals(1, session.dispatch(
            "symbol.references", objectParams("languageId" to "typescript", "symbol" to symbolId),
        ).jsonArray.size)
        assertTrue(session.dispatch(
            "diagnostics", objectParams("languageId" to "typescript"),
        ).jsonArray.isEmpty())

        val preview = session.dispatch("refactor.preview", buildJsonObject {
            put("operation", "renameSymbol")
            put("languageId", "typescript")
            put("symbol", symbolId)
            put("arguments", buildJsonObject { put("newName", "AccountService") })
        }).jsonObject
        val planId = preview["planId"]!!.jsonPrimitive.content
        val applied = session.dispatch("refactor.apply", objectParams("planId" to planId)).jsonObject
        assertEquals("export class AccountService {}\n", root.resolve("src/service.ts").readText())
        val transactionId = applied["transactionId"]!!.jsonPrimitive.content
        assertTrue(Files.isRegularFile(root.resolve(".refactorkit/transactions/$transactionId.json")))

        session.dispatch("patch.rollback", objectParams("transactionId" to transactionId))
        assertEquals("export class Service {}\n", root.resolve("src/service.ts").readText())
        session.close()
    }

    private fun objectParams(vararg values: Pair<String, String>): JsonObject = buildJsonObject {
        values.forEach { (key, value) -> put(key, value) }
    }

    private fun toolchain(): TypeScriptSemanticToolchain {
        val root = Files.createTempDirectory("refactorkit-daemon-ts-toolchain")
        val node = root.resolve("node").also { it.writeText("node") }
        val serverManifest = root.resolve("server-package.json").also { it.writeText("{}") }
        val server = root.resolve("cli.mjs").also { it.writeText("server") }
        val compilerManifest = root.resolve("typescript-package.json").also { it.writeText("{}") }
        val tsserver = root.resolve("tsserver.js").also { it.writeText("compiler") }
        return TypeScriptSemanticToolchain(
            node, server, tsserver, listOf(node.toString(), server.toString(), "--stdio"),
            TypeScriptToolchainProvenance(
                nodeVersion = "22.1.0", languageServerVersion = "4.3.3", typeScriptVersion = "5.7.2",
                evidence = listOf(
                    evidence("node-executable", node), evidence("language-server-package", serverManifest),
                    evidence("language-server-entrypoint", server), evidence("typescript-package", compilerManifest),
                    evidence("typescript-server-entrypoint", tsserver),
                ),
            ),
        )
    }

    private fun evidence(role: String, path: Path): ToolchainFileEvidence {
        val bytes = Files.readAllBytes(path)
        return ToolchainFileEvidence(
            role, path,
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
            bytes.size.toLong(),
        )
    }

    private class FakeSemanticClient : TypeScriptSemanticClient {
        override var isRunning = false
        override val provenance: ExternalSemanticSessionProvenance? = null
        override fun start(snapshot: ProjectSnapshot) { isRunning = true }
        override fun supports(capability: String) = capability in setOf(
            "definitionProvider", "referencesProvider", "renameProvider", "prepareRenameProvider", "documentSymbolProvider",
            "workspaceSymbolProvider", "textDocumentSync",
        )
        override fun buildSymbols(snapshot: ProjectSnapshot) = SymbolIndex(listOf(symbol()))
        override fun searchWorkspaceSymbols(query: String) = listOf(symbol()).filter { query in it.name }
        override fun resolveSymbol(location: SourceLocation) = SymbolResolution(symbol())
        override fun findReferences(symbolId: SymbolId) = listOf(Reference(symbolId, location()))
        override fun diagnostics(snapshot: ProjectSnapshot): List<Diagnostic> = emptyList()
        override fun synchronizedDiagnostics(snapshot: ProjectSnapshot): ExternalSemanticDiagnostics =
            ExternalSemanticDiagnostics.Available(emptyList())
        override fun requestRename(
            snapshot: ProjectSnapshot,
            location: SourceLocation,
            newName: String,
        ): ExternalWorkspaceEditNormalization = ExternalWorkspaceEditNormalizer().normalize(
            snapshot,
            ExternalWorkspaceEditProposal(
                "lsp-typescript", "test",
                listOf(ExternalFileEditProposal.Modify(
                    Path.of("src/service.ts"), listOf(TextEdit(location().range, newName)), documentVersion = 1,
                )),
            ),
        )
        override fun close() { isRunning = false }
        private fun symbol() = Symbol(SymbolId("src/service.ts::Service@0:13"), "Service", Symbol.Kind.CLASS, location(), "typescript")
        private fun location() = SourceLocation(
            Path.of("src/service.ts"), SourceRange(SourcePosition(0, 13), SourcePosition(0, 20)),
        )
    }
}
