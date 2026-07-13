package org.refactorkit.treesitter

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.ExternalWorkspaceEditNormalization
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.FileEdit
import org.refactorkit.core.SourceLocation
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExternalLspAdapterLifecycleTest {
    @Test
    fun boundedManagerOwnsHandshakeUnicodeFramesAndShutdown() {
        val workspace = Files.createTempDirectory("refactorkit lsp ünicode ")
        val source = workspace.resolve("sample.ts")
        val snapshot = org.refactorkit.core.ProjectSnapshot(
            workspace = org.refactorkit.core.Workspace(workspace),
            modules = emptyList(),
            files = listOf(org.refactorkit.core.SourceFile(Path.of("sample.ts"), "const foo = 1;\n", "typescript")),
        )
        val adapter = adapter("normal")

        adapter.start(snapshot)

        assertTrue(adapter.isRunning)
        val provenance = assertNotNull(adapter.sessionProvenance)
        assertEquals("Sémantique", provenance.serverName)
        assertEquals("1.2.3", provenance.serverVersion)
        assertEquals(64, provenance.capabilitiesSha256.length)
        assertEquals(64, provenance.process.executableSha256.length)
        assertEquals(true, provenance.advertisedCapabilities["definitionProvider"])
        assertEquals(true, provenance.advertisedCapabilities["renameProvider"])
        assertEquals(false, provenance.advertisedCapabilities["referencesProvider"])
        assertEquals(listOf("Service"), adapter.buildSymbols(snapshot).symbols.map { it.name })
        val exact = assertIs<ExternalSemanticDiagnostics.Available>(adapter.synchronizedDiagnostics(snapshot))
        assertTrue(exact.diagnostics.isEmpty())

        val position = SourceLocation(
            workspace.resolve("sample.ts"),
            SourceRange(SourcePosition(0, 0), SourcePosition(0, 1)),
        )
        val resolved = assertNotNull(adapter.resolveSymbol(position).symbol)
        assertEquals(position.path.toAbsolutePath().normalize(), resolved.location.path.toAbsolutePath().normalize())
        assertEquals(listOf("Service"), adapter.searchWorkspaceSymbols("Serv").map { it.name })
        val diagnostics = adapter.diagnostics(projectSnapshot())
        assertEquals(listOf("échec contrôlé"), diagnostics.map(Diagnostic::message))
        assertEquals("TS1001", diagnostics.single().code)
        assertEquals(source.toAbsolutePath().normalize(), diagnostics.single().location?.path)
        assertEquals(DiagnosticEvidence.COMPILER, diagnostics.single().evidence)

        assertFalse(adapter.openDocument(snapshot.files.single(), 3), "unchanged documents are not resent")
        assertFalse(adapter.changeDocument(snapshot.files.single(), 3), "unchanged documents are not resent")
        assertTrue(adapter.changeDocument(snapshot.files.single().copy(content = "const foo = 2;\n"), 4))
        val stale = adapter.requestWorkspaceEdit("workspace/versionedStale", "{}", snapshot)
        assertEquals(
            listOf("externalEdit.documentVersionStale"),
            assertIs<ExternalWorkspaceEditNormalization.Refused>(stale).diagnostics.map(Diagnostic::code),
        )
        val normalized = adapter.requestRename(snapshot, position, "bar")
        val accepted = assertIs<ExternalWorkspaceEditNormalization.Accepted>(normalized, normalized.toString()).normalized
        val modification = assertIs<FileEdit.Modify>(accepted.workspaceEdit.edits.single())
        assertEquals("bar", modification.textEdits.single().newText)
        assertEquals("lsp-typescript", accepted.providerId)
        val invalidSurrogate = snapshot.copy(files = listOf(snapshot.files.single().copy(content = "const 😀 = 1;\n")))
        val invalidPosition = position.copy(range = SourceRange(SourcePosition(0, 7), SourcePosition(0, 7)))
        assertEquals(
            listOf("externalEdit.positionInvalid"),
            assertIs<ExternalWorkspaceEditNormalization.Refused>(adapter.requestRename(invalidSurrogate, invalidPosition, "value"))
                .diagnostics.map(Diagnostic::code),
        )
        val outside = adapter.requestWorkspaceEdit("workspace/outside", "{}", snapshot)
        assertEquals(
            listOf("externalEdit.pathOutsideOverlay"),
            assertIs<ExternalWorkspaceEditNormalization.Refused>(outside).diagnostics.map(Diagnostic::code),
        )

        adapter.stop()
        assertFalse(adapter.isRunning)
        assertEquals(null, adapter.lastFailure)
    }

    @Test
    fun exactDiagnosticsRefuseUnversionedPublications() {
        val workspace = Files.createTempDirectory("refactorkit-lsp-unversioned")
        val snapshot = org.refactorkit.core.ProjectSnapshot(
            org.refactorkit.core.Workspace(workspace), emptyList(),
            listOf(org.refactorkit.core.SourceFile(Path.of("sample.ts"), "const value = 1;\n", "typescript")),
        )
        val adapter = adapter("unversioned")
        adapter.start(snapshot)

        val refused = assertIs<ExternalSemanticDiagnostics.Unavailable>(adapter.synchronizedDiagnostics(snapshot))
        assertEquals("semantic.diagnosticsIncomplete", refused.diagnostic.code)
        adapter.close()
    }

    @Test
    fun initializationDeadlineCancelsTheOwnedProcess() {
        val workspace = Files.createTempDirectory("refactorkit-lsp-timeout")
        val adapter = adapter("hang", timeoutMillis = 150)

        assertFailsWith<IllegalStateException> { adapter.start(workspace.toUri().toString()) }

        assertFalse(adapter.isRunning)
        assertEquals("semantic.requestTimeout", adapter.lastFailure?.code)
    }

    @Test
    fun oversizedProtocolFrameFailsClosedAndTerminatesProcess() {
        val workspace = Files.createTempDirectory("refactorkit-lsp-oversized")
        val adapter = adapter("oversized")

        assertFailsWith<IllegalStateException> { adapter.start(workspace.toUri().toString()) }

        assertFalse(adapter.isRunning)
        assertEquals("semantic.protocolFailure", adapter.lastFailure?.code)
        assertTrue(adapter.lastFailure?.message.orEmpty().contains("frame exceeds"))
    }

    @Test
    fun rootMustBeAnExistingFileUri() {
        val adapter = adapter("normal")
        assertFailsWith<IllegalArgumentException> { adapter.start("https://example.invalid/workspace") }
        assertFailsWith<IllegalArgumentException> { adapter.start(Path.of("missing-workspace").toUri().toString()) }
    }

    private fun adapter(mode: String, timeoutMillis: Long = 5_000): ExternalLspAdapter = ExternalLspAdapter(
        languageId = "typescript",
        command = listOf(
            javaExecutable().toString(),
            "-cp", System.getProperty("java.class.path"),
            ExternalLspFixture::class.java.name,
            mode,
        ),
        requestTimeoutMillis = timeoutMillis,
    )

    private fun javaExecutable(): Path = Path.of(
        System.getProperty("java.home"), "bin",
        if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java",
    ).toAbsolutePath().normalize()

    private fun projectSnapshot() = org.refactorkit.core.ProjectSnapshot(
        workspace = org.refactorkit.core.Workspace(Path.of(".")),
        modules = emptyList(),
        files = emptyList(),
    )
}

object ExternalLspFixture {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val mode = arguments.single()
        val input = System.`in`
        val output = System.out
        var lastDocumentUri = "\"file:///sample.ts\""
        while (true) {
            val request = readFrame(input) ?: return
            val method = LspJson.extractField(request, "method")?.let(LspJson::unquote)
            val id = LspJson.extractField(request, "id")
            when (method) {
                "initialize" -> when (mode) {
                    "hang" -> Thread.sleep(30_000)
                    "oversized" -> {
                        output.write("Content-Length: ${ExternalLspAdapter.MAX_FRAME_BYTES + 1}\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        output.flush()
                        Thread.sleep(30_000)
                    }
                    else -> writeFrame(output, """{"jsonrpc":"2.0","id":$id,"result":{"capabilities":{"definitionProvider":true,"documentSymbolProvider":true,"workspaceSymbolProvider":true,"renameProvider":{"prepareProvider":true},"textDocumentSync":{"change":1,"openClose":true}},"serverInfo":{"name":"Sémantique","version":"1.2.3"}}}""")
                }
                "textDocument/didOpen", "textDocument/didChange" -> {
                    val params = LspJson.extractField(request, "params").orEmpty()
                    val document = LspJson.extractField(params, "textDocument").orEmpty()
                    val uri = LspJson.extractField(document, "uri") ?: "\"file:///sample.ts\""
                    val version = LspJson.extractField(document, "version") ?: "0"
                    val versionField = if (mode == "unversioned") "" else "\"version\":$version,"
                    writeFrame(output, """{"jsonrpc":"2.0","method":"textDocument/publishDiagnostics","params":{"uri":$uri,$versionField"diagnostics":[]}}""")
                }
                "initialized", "textDocument/didClose" -> Unit
                "textDocument/documentSymbol" -> writeFrame(output, """{"jsonrpc":"2.0","id":$id,"result":[{"name":"Service","kind":5,"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":21}},"selectionRange":{"start":{"line":0,"character":13},"end":{"line":0,"character":20}}}]}""")
                "workspace/symbol" -> writeFrame(output, """{"jsonrpc":"2.0","id":$id,"result":[{"name":"Service","kind":5,"location":{"uri":$lastDocumentUri,"range":{"start":{"line":0,"character":13},"end":{"line":0,"character":20}}}}]}""")
                "textDocument/definition" -> {
                    val params = LspJson.extractField(request, "params").orEmpty()
                    val document = LspJson.extractField(params, "textDocument").orEmpty()
                    val uri = LspJson.extractField(document, "uri") ?: "\"file:///sample.ts\""
                    writeFrame(output, """{"jsonrpc":"2.0","method":"textDocument/publishDiagnostics","params":{"uri":$uri,"diagnostics":[{"severity":1,"code":"TS1001","message":"échec contrôlé","range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}}}]}}""")
                    lastDocumentUri = uri
                    writeFrame(output, """{"jsonrpc":"2.0","id":$id,"result":{"uri":$uri,"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}}}}""")
                }
                "textDocument/rename" -> {
                    writeFrame(output, """{"jsonrpc":"2.0","id":$id,"result":{"changes":{$lastDocumentUri:[{"range":{"start":{"line":0,"character":6},"end":{"line":0,"character":9}},"newText":"bar"}]}}}""")
                }
                "workspace/outside" -> writeFrame(output, """{"jsonrpc":"2.0","id":$id,"result":{"changes":{"file:///outside.ts":[]}}}""")
                "workspace/versionedStale" -> writeFrame(output, """{"jsonrpc":"2.0","id":$id,"result":{"documentChanges":[{"textDocument":{"uri":$lastDocumentUri,"version":0},"edits":[{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}},"newText":"x"}]}]}}""")
                "shutdown" -> writeFrame(output, """{"jsonrpc":"2.0","id":$id,"result":null}""")
                "exit" -> return
            }
        }
    }

    private fun readFrame(input: InputStream): String? {
        var length: Int? = null
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
        }
        val bytes = ByteArray(length ?: return null)
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count < 0) return null
            offset += count
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun readLine(input: InputStream): String? {
        val output = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) return null
            if (value == '\n'.code) {
                val bytes = output.toByteArray()
                val size = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, size, Charsets.US_ASCII)
            }
            output.write(value)
        }
    }

    private fun writeFrame(output: OutputStream, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        output.write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }
}
