package org.refactorkit.daemon

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.DiagnosticLocationPrecision
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TypeScriptDaemonIntegrationTest {
    @Test
    fun diagnosticsV2PreservesExactRangesAuthorityCorrelationAndStructuredFailures() {
        val root = Files.createTempDirectory("refactorkit-daemon-ts-diagnostics-v2")
        Files.createDirectories(root.resolve("src"))
        root.resolve("src/service.ts").writeText("const value = missing;\n")
        root.resolve("tsconfig.json").writeText(
            """{"compilerOptions":{"rootDir":"src"},"include":["src/**/*.ts"]}""",
        )
        val client = FakeSemanticClient()
        val lease = "semantic-00000000-0000-4000-8000-000000000001"
        val session = DaemonSession(
            toolchainDiscovery = { _, _ -> TypeScriptToolchainDiscovery.Available(toolchain()) },
            semanticAdapterFactory = { languageId, discovered, model ->
                TypeScriptSemanticAdapter(languageId, discovered, model, client)
            },
            semanticLeaseFactory = { lease },
        )
        val opened = session.dispatch("project.open", objectParams("root" to root.toString())).jsonObject
        val snapshotHash = opened.getValue("snapshotHash").jsonPrimitive.content
        val notReady = session.dispatch("diagnostics.v2", diagnosticsV2Params(
            requestId = "ide-request-before-start",
            snapshotHash = snapshotHash,
            lease = lease,
            sourceAuthority = buildJsonObject { put("kind", "saved-disk") },
        )).jsonObject
        assertEquals("refused", notReady.getValue("status").jsonPrimitive.content)
        assertEquals("diagnostics.semanticSessionNotReady", notReady.getValue("failure").jsonObject
            .getValue("code").jsonPrimitive.content)
        assertTrue(notReady.getValue("diagnostics").jsonArray.isEmpty())
        val started = session.dispatch("typescript.semantic.start", objectParams("languageId" to "typescript")).jsonObject
        assertEquals(lease, started.getValue("semanticLease").jsonPrimitive.content)
        client.synchronized = ExternalSemanticDiagnostics.Available(listOf(
            Diagnostic(
                message = "Cannot find name 'missing'.",
                severity = Diagnostic.Severity.ERROR,
                location = SourceLocation(
                    Path.of("src/service.ts"),
                    SourceRange(SourcePosition(0, 14), SourcePosition(0, 21)),
                ),
                code = "TS2304",
                evidence = DiagnosticEvidence.COMPILER,
                category = DiagnosticCategory.TYPE_RESOLUTION,
            ),
            Diagnostic(
                message = "Line-only fixture",
                severity = Diagnostic.Severity.WARNING,
                location = SourceLocation(
                    Path.of("src/service.ts"),
                    SourceRange(SourcePosition(0, 0), SourcePosition(0, 0)),
                ),
                locationPrecision = DiagnosticLocationPrecision.LINE_ONLY,
            ),
            Diagnostic("Project-level fixture", Diagnostic.Severity.INFO),
        ))

        val saved = session.dispatch("diagnostics.v2", diagnosticsV2Params(
            requestId = "ide-request-1",
            snapshotHash = snapshotHash,
            lease = lease,
            sourceAuthority = buildJsonObject { put("kind", "saved-disk") },
        )).jsonObject
        assertEquals("ready", saved.getValue("status").jsonPrimitive.content)
        assertEquals("ide-request-1", saved.getValue("requestId").jsonPrimitive.content)
        assertEquals(snapshotHash, saved.getValue("projectSnapshotHash").jsonPrimitive.content)
        assertEquals(snapshotHash, saved.getValue("providerSnapshotHash").jsonPrimitive.content)
        assertEquals("typescript-compiler-exact-v1", saved.getValue("backend").jsonPrimitive.content)
        assertEquals("saved-disk", saved.getValue("sourceAuthority").jsonObject.getValue("kind").jsonPrimitive.content)
        val locations = saved.getValue("diagnostics").jsonArray.map { it.jsonObject.getValue("location").jsonObject }
        val exact = locations[0]
        assertEquals("range", exact.getValue("kind").jsonPrimitive.content)
        assertEquals("src/service.ts", exact.getValue("path").jsonPrimitive.content)
        assertEquals("14", exact.getValue("range").jsonObject.getValue("start").jsonObject.getValue("character").jsonPrimitive.content)
        assertEquals("21", exact.getValue("range").jsonObject.getValue("end").jsonObject.getValue("character").jsonPrimitive.content)
        assertEquals("line", locations[1].getValue("kind").jsonPrimitive.content)
        assertTrue("character" !in locations[1])
        assertEquals("none", locations[2].getValue("kind").jsonPrimitive.content)
        assertTrue(saved.getValue("compilerAttestation").jsonObject.getValue("compilerSha256").jsonPrimitive.content.length == 64)
        assertEquals("30000", saved.getValue("runtime").jsonObject.getValue("limits").jsonObject
            .getValue("requestTimeoutMillis").jsonPrimitive.content)

        val overlay = session.dispatch("diagnostics.v2", diagnosticsV2Params(
            requestId = "ide-request-2",
            snapshotHash = snapshotHash,
            lease = lease,
            sourceAuthority = buildJsonObject {
                put("kind", "immutable-editor-overlay")
                put("documents", buildJsonArray {
                    add(buildJsonObject {
                        put("path", "src/service.ts")
                        put("version", 7)
                        put("content", "const value = unsaved;\n")
                    })
                })
            },
        )).jsonObject
        assertEquals("ready", overlay.getValue("status").jsonPrimitive.content)
        assertEquals("immutable-editor-overlay", overlay.getValue("sourceAuthority").jsonObject.getValue("kind").jsonPrimitive.content)
        assertNotEquals(snapshotHash, overlay.getValue("providerSnapshotHash").jsonPrimitive.content)
        assertEquals("7", overlay.getValue("sourceAuthority").jsonObject.getValue("documents").jsonArray.single()
            .jsonObject.getValue("version").jsonPrimitive.content)
        assertEquals("const value = unsaved;\n", client.lastSynchronizedSnapshot?.files?.single { it.path == Path.of("src/service.ts") }?.content)

        root.resolve("src/service.ts").writeText("externally changed\n")
        val staleDisk = session.dispatch("diagnostics.v2", diagnosticsV2Params(
            requestId = "ide-request-stale-disk",
            snapshotHash = snapshotHash,
            lease = lease,
            sourceAuthority = buildJsonObject { put("kind", "saved-disk") },
        )).jsonObject
        assertEquals("refused", staleDisk.getValue("status").jsonPrimitive.content)
        assertEquals("diagnostics.savedSnapshotStale", staleDisk.getValue("failure").jsonObject
            .getValue("code").jsonPrimitive.content)
        root.resolve("src/service.ts").writeText("const value = missing;\n")

        val stale = session.dispatch("diagnostics.v2", diagnosticsV2Params(
            requestId = "ide-request-stale",
            snapshotHash = "0".repeat(64),
            lease = lease,
            sourceAuthority = buildJsonObject { put("kind", "saved-disk") },
        )).jsonObject
        assertEquals("refused", stale.getValue("status").jsonPrimitive.content)
        assertEquals("diagnostics.snapshotStale", stale.getValue("failure").jsonObject.getValue("code").jsonPrimitive.content)
        assertTrue(stale.getValue("diagnostics").jsonArray.isEmpty())

        client.synchronized = ExternalSemanticDiagnostics.Available((0 until 500).map { index ->
            Diagnostic("$index:${"\\\"".repeat(4_096)}", Diagnostic.Severity.ERROR, code = "TS$index")
        })
        val bounded = session.dispatch("diagnostics.v2", diagnosticsV2Params(
            requestId = "ide-request-bounded",
            snapshotHash = snapshotHash,
            lease = lease,
            sourceAuthority = buildJsonObject { put("kind", "saved-disk") },
        )).jsonObject
        assertTrue(bounded.toString().toByteArray().size <= org.refactorkit.core.ProtocolLimits.MAX_DIAGNOSTICS_RESPONSE_BYTES)
        assertTrue(bounded.getValue("truncated").jsonPrimitive.content.toBoolean())
        assertTrue(bounded.getValue("diagnosticsReturned").jsonPrimitive.content.toInt() < 500)

        client.synchronized = ExternalSemanticDiagnostics.Unavailable(Diagnostic(
            "compiler timed out", Diagnostic.Severity.ERROR, code = "typescript.compilerDiagnosticsTimeout",
        ))
        val failed = session.dispatch("diagnostics.v2", diagnosticsV2Params(
            requestId = "ide-request-timeout",
            snapshotHash = snapshotHash,
            lease = lease,
            sourceAuthority = buildJsonObject { put("kind", "saved-disk") },
        )).jsonObject
        assertEquals("error", failed.getValue("status").jsonPrimitive.content)
        assertEquals("typescript.compilerDiagnosticsTimeout", failed.getValue("failure").jsonObject.getValue("code").jsonPrimitive.content)
        assertTrue(failed.getValue("diagnostics").jsonArray.isEmpty())
        session.close()
    }

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
        assertNotEquals(started["semanticLease"]!!.jsonPrimitive.content, restarted["semanticLease"]!!.jsonPrimitive.content)

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

    private fun diagnosticsV2Params(
        requestId: String,
        snapshotHash: String,
        lease: String,
        sourceAuthority: JsonObject,
    ) = buildJsonObject {
        put("requestId", requestId)
        put("languageId", "typescript")
        put("expectedSnapshotHash", snapshotHash)
        put("semanticLease", lease)
        put("sourceAuthority", sourceAuthority)
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
        val compiler = root.resolve("typescript.js").also { it.writeText("exact compiler") }
        return TypeScriptSemanticToolchain(
            node, server, tsserver, listOf(node.toString(), server.toString(), "--stdio"),
            TypeScriptToolchainProvenance(
                nodeVersion = "22.1.0", languageServerVersion = "4.3.3", typeScriptVersion = "5.7.2",
                evidence = listOf(
                    evidence("node-executable", node), evidence("language-server-package", serverManifest),
                    evidence("language-server-entrypoint", server), evidence("typescript-package", compilerManifest),
                    evidence("typescript-server-entrypoint", tsserver),
                    evidence("typescript-compiler-entrypoint", compiler),
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
        var synchronized: ExternalSemanticDiagnostics = ExternalSemanticDiagnostics.Available(emptyList())
        var lastSynchronizedSnapshot: ProjectSnapshot? = null
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
        override fun synchronizedDiagnostics(snapshot: ProjectSnapshot): ExternalSemanticDiagnostics {
            lastSynchronizedSnapshot = snapshot
            return synchronized
        }
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
