package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.refactorkit.c.CClangdSemanticResult
import org.refactorkit.c.ClangResourceDirectoryCapture
import org.refactorkit.c.ClangSemanticToolchain
import org.refactorkit.c.ClangToolchainProvenance
import org.refactorkit.c.ClangToolchainRuntimeCaptureResult
import org.refactorkit.c.ClangToolchainRuntimeCurrency
import org.refactorkit.c.ClangToolchainRuntimeProvider
import org.refactorkit.c.ClangdSemanticClient
import org.refactorkit.core.ExternalSemanticProcessManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CDefinitionResponseSteps {
    private val simulatedRuntimeProvider = object : ClangToolchainRuntimeProvider {
        override fun capture(clang: Path, clangd: Path, formatter: Path): ClangToolchainRuntimeCaptureResult =
            error("The authored protocol-peer fixture does not qualify native runtime capture")

        override fun checkCurrent(captured: ClangToolchainRuntimeCaptureResult.Captured) = ClangToolchainRuntimeCurrency.Current
        override fun checkCurrent(toolchain: ClangSemanticToolchain) = ClangToolchainRuntimeCurrency.Current
    }

    private var root: Path? = null
    private lateinit var source: Path
    private lateinit var peer: Path
    private lateinit var originals: Map<Path, String>
    private var result: CClangdSemanticResult? = null
    private var pid: Long? = null
    private var privateMaterial: Path? = null

    @Given("a C semantic protocol peer returns {string} definition evidence")
    fun peer(response: String) {
        val workspace = Files.createTempDirectory("refactorkit-c-definition-response-"); root = workspace
        source = workspace.resolve(if (response == "escaped file URI") "main space_é.c" else "main.c")
        Files.writeString(source, "int compute(void) { return 1; }\n")
        fun location(uri: String, stringLine: Boolean = false) = buildJsonObject {
            put("uri", uri)
            put("range", buildJsonObject {
                put("start", buildJsonObject { put("line", if (stringLine) JsonPrimitive("0") else JsonPrimitive(0)); put("character", 4) })
                put("end", buildJsonObject { put("line", 0); put("character", 11) })
            })
        }
        val payload = when (response) {
            "null location" -> JsonNull
            "empty location array" -> JsonArray(emptyList())
            "escaped file URI" -> location(source.toUri().toASCIIString())
            "nonnumeric range" -> location(source.toUri().toASCIIString(), stringLine = true)
            "multiple locations" -> JsonArray(listOf(location(source.toUri().toASCIIString()), location(workspace.resolve("other.c").toUri().toASCIIString())))
            "nonfile URI" -> location("https://example.invalid/main.c")
            else -> error("Unexpected response: $response")
        }
        peer = workspace.resolve("authored-protocol-peer.py")
        Files.writeString(peer, """#!/usr/bin/python3
import json, sys
payload = json.loads(${JsonPrimitive(payload.toString())})
expected_uri = ${JsonPrimitive(source.toUri().toASCIIString())}
def read_frame():
    size = None
    while True:
        line = sys.stdin.buffer.readline()
        if not line: return None
        if line == b'\r\n': break
        if line.lower().startswith(b'content-length:'): size = int(line.split(b':', 1)[1])
    if size is None or not 0 <= size <= 1048576: raise RuntimeError('invalid authored frame')
    return json.loads(sys.stdin.buffer.read(size))
def send(message):
    body = json.dumps(message).encode('utf-8')
    sys.stdout.buffer.write(('Content-Length: %d\r\n\r\n' % len(body)).encode('ascii') + body)
    sys.stdout.buffer.flush()
while True:
    request = read_frame()
    if request is None: break
    if 'id' not in request: continue
    if request['method'] == 'initialize': value = {'capabilities': {'definitionProvider': True}}
    elif request['method'] == 'textDocument/definition':
        if request['params']['textDocument']['uri'] != expected_uri:
            send({'jsonrpc':'2.0','id':request['id'],'error':{'code':-32602,'message':'file URI identity was not encoded'}})
            continue
        value = payload
    else: value = None
    send({'jsonrpc':'2.0','id':request['id'],'result':value})
""")
        assertTrue(peer.toFile().setExecutable(true))
        originals = files(workspace)
    }

    @When("the C client requests that definition through its framed protocol")
    fun request() {
        val workspace = requireNotNull(root)
        // Authored transport peer only; no discovered or claimed production Clang provenance.
        val resourceRoot = Files.createDirectory(workspace.resolve("authored-empty-resources"))
        val toolchain = ClangSemanticToolchain(peer, peer, peer, ClangToolchainProvenance(
            clangVersion = "authored-protocol-peer", clangdVersion = "authored-protocol-peer", clangFormatVersion = "authored-protocol-peer",
            targetTriple = null, resourceDir = resourceRoot.toString(), evidence = emptyList(),
        ), resources = ClangResourceDirectoryCapture().capture(resourceRoot))
        ExternalSemanticProcessManager().use { manager ->
            ClangdSemanticClient(toolchain, manager, runtimeProvider = simulatedRuntimeProvider).use { client ->
                client.start(workspace)
                val provenance = assertNotNull(client.processProvenance()); pid = provenance.pid; privateMaterial = provenance.workingDirectory
                assertTrue(client.didOpen(source, Files.readString(source)))
                result = client.definition(source, 0, 4)
            }
            assertTrue(manager.activeProvenance().isEmpty())
        }
    }

    @Then("the definition response disposition is {string} with its exact file identity")
    fun disposition(expected: String) {
        when (expected) {
            "absent" -> assertTrue(assertIs<CClangdSemanticResult.NotFound>(result).diagnostics.any { it.code == "clangd.definitionNotFound" })
            "found" -> {
                val found = assertIs<CClangdSemanticResult.Found>(result)
                assertEquals(source, found.definition.file); assertEquals(0, found.definition.startLine); assertEquals(4, found.definition.startCharacter)
            }
            "invalid", "ambiguous" -> {
                val refused = assertIs<CClangdSemanticResult.Refused>(result)
                assertTrue(refused.diagnostics.any { it.code == if (expected == "invalid") "clangd.resultInvalid" else "clangd.definitionAmbiguous" })
            }
            else -> error("Unexpected disposition: $expected")
        }
    }

    @Then("definition protocol exchanges preserve project bytes and reap owned processes")
    fun preserved() {
        assertEquals(originals, files(requireNotNull(root)))
        assertFalse(ProcessHandle.of(assertNotNull(pid)).map { it.isAlive }.orElse(false))
        assertFalse(Files.exists(assertNotNull(privateMaterial)))
    }

    private fun files(workspace: Path): Map<Path, String> = Files.walk(workspace).use { paths ->
        paths.filter(Files::isRegularFile).toList().associate { workspace.relativize(it) to Files.readString(it) }
    }

    @After
    fun cleanup() {
        root?.let { workspace -> Files.walk(workspace).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }
}
