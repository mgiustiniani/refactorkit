package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.c.CSafeDeletePlanner
import org.refactorkit.c.ClangResourceCaptureResult
import org.refactorkit.c.ClangResourceDirectoryCapture
import org.refactorkit.c.ClangToolchainDiscoverer
import org.refactorkit.c.ClangToolchainDiscovery
import org.refactorkit.c.ClangSemanticToolchain
import org.refactorkit.c.ClangToolchainProvenance
import org.refactorkit.c.ClangToolchainRequest
import org.refactorkit.c.ClangToolchainRuntimeCapture
import org.refactorkit.c.ClangToolchainRuntimeCaptureResult
import org.refactorkit.c.ClangToolchainRuntimeCurrency
import org.refactorkit.c.ClangToolchainRuntimeProvider
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.Workspace
import org.refactorkit.core.WorkspaceEditSimulator
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Drives the real bounded clangd protocol client through a controlled response peer. */
class CReferenceFailureSafeDeleteSteps {
    private var sandbox: Path? = null
    private lateinit var workspace: Path
    private lateinit var original: ByteArray
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var peer: Path
    private lateinit var peerRequestMarker: Path
    private lateinit var plan: PatchPlan
    private var useCapturedToolchain = false
    private var startSemanticClient = true
    private var symbolToPreview = "unused"
    private val simulatedRuntimeProvider = object : ClangToolchainRuntimeProvider {
        override fun capture(clang: Path, clangd: Path, formatter: Path): ClangToolchainRuntimeCaptureResult =
            error("The controlled semantic peer does not qualify native runtime capture")

        override fun checkCurrent(captured: ClangToolchainRuntimeCaptureResult.Captured) =
            ClangToolchainRuntimeCurrency.Current

        override fun checkCurrent(toolchain: ClangSemanticToolchain) =
            ClangToolchainRuntimeCurrency.Current
    }

    @Given("an isolated C safe-delete workspace and a semantic peer with {string} references")
    fun peerFixture(reply: String) {
        useCapturedToolchain = reply in setOf("declaration only", "address-taking use")
        symbolToPreview = if (reply == "public function") "exported" else "unused"
        require(System.getProperty("os.name").startsWith("Linux")) { "This C process qualification targets Linux x86-64 only" }
        val mode = when (reply) {
            "protocol error" -> "error"
            "malformed result" -> "malformed"
            "truncated 257 results" -> "overflow"
            "complete empty" -> "empty"
            "public function" -> "empty"
            "declaration only" -> "declaration"
            "address-taking use" -> "address"
            else -> error("Unexpected peer response $reply")
        }
        val root = Files.createTempDirectory("refactorkit-c-reference-failure-")
        sandbox = root
        workspace = Files.createDirectory(root.resolve("workspace"))
        val content = when (reply) {
            "declaration only" -> "static int unused(void) { return 3; }\nint main(void) { return 0; }\n"
            "address-taking use" -> "static int unused(void) { return 3; }\nstatic int (*callback)(void) = unused;\nint main(void) { return callback(); }\n"
            "public function" -> "int exported(void) { return 3; }\nint main(void) { return 0; }\n"
            else -> "static int unused(void) { return 3; }\nint main(void) { return unused(); }\n"
        }
        original = content.toByteArray(Charsets.UTF_8)
        Files.write(workspace.resolve("main.c"), original)
        snapshot = ProjectSnapshot(
            Workspace(workspace), emptyList(), listOf(SourceFile(Path.of("main.c"), content, "c")),
        )
        peer = root.resolve("semantic-peer.py")
        peerRequestMarker = root.resolve("references-requested.txt")
        // The peer is test-authored and bounded to initialize, definition and references.
        // For overflow it emits 256 declaration duplicates and one real call-site use;
        // dropping the tail would falsely make this function look unused.
        Files.writeString(peer, """#!/usr/bin/python3
import json
import sys
MODE = "$mode"
REQUEST_MARKER = "$peerRequestMarker"

def receive():
    length = None
    while True:
        line = sys.stdin.buffer.readline()
        if not line:
            return None
        if line in (b'\r\n', b'\n'):
            break
        if line.lower().startswith(b'content-length:'):
            length = int(line.split(b':', 1)[1].strip())
    if length is None or length > 65536:
        raise ValueError('invalid test request')
    return json.loads(sys.stdin.buffer.read(length))

def send(identity, body):
    encoded = json.dumps(dict(jsonrpc='2.0', id=identity, **body), separators=(',', ':')).encode('utf-8')
    sys.stdout.buffer.write(b'Content-Length: ' + str(len(encoded)).encode() + b'\r\n\r\n' + encoded)
    sys.stdout.buffer.flush()

while True:
    request = receive()
    if request is None:
        break
    identity = request.get('id')
    if identity is None:
        continue
    method = request.get('method')
    if method == 'initialize':
        send(identity, {'result': {'capabilities': {}}})
    elif method == 'textDocument/definition':
        uri = request['params']['textDocument']['uri']
        send(identity, {'result': {'uri': uri, 'range': {'start': {'line': 0, 'character': 11}, 'end': {'line': 0, 'character': 17}}}})
    elif method == 'textDocument/references':
        with open(REQUEST_MARKER, 'a', encoding='utf-8') as marker:
            marker.write('textDocument/references\\n')
        uri = request['params']['textDocument']['uri']
        declaration = {'uri': uri, 'range': {'start': {'line': 0, 'character': 11}, 'end': {'line': 0, 'character': 17}}}
        if MODE == 'error':
            send(identity, {'error': {'code': -32603, 'message': 'reference analysis failed'}})
        elif MODE == 'malformed':
            send(identity, {'result': {'unexpected': True}})
        elif MODE == 'empty':
            send(identity, {'result': []})
        elif MODE == 'declaration':
            send(identity, {'result': [declaration]})
        elif MODE == 'address':
            call_line = 'static int (*callback)(void) = unused;'
            start = call_line.index('unused')
            address_use = {'uri': uri, 'range': {'start': {'line': 1, 'character': start}, 'end': {'line': 1, 'character': start + 6}}}
            send(identity, {'result': [declaration, address_use]})
        else:
            call = {'uri': uri, 'range': {'start': {'line': 1, 'character': 24}, 'end': {'line': 1, 'character': 30}}}
            send(identity, {'result': [declaration] * 256 + [call]})
    else:
        send(identity, {'error': {'code': -32601, 'message': 'unsupported test method'}})
""".trimIndent() + "\n")
        check(peer.toFile().setExecutable(true)) { "Test-owned semantic peer is not executable" }
    }

    @Given("an isolated C safe-delete workspace with an unavailable semantic runtime")
    fun publicFunctionWithoutSemanticRuntime() = peerFixture("public function")

    @Given("an unused {string} and a distinct declaration share a C source line")
    fun sameLineDeclaration(form: String) {
        val content = when (form) {
            "function" -> "static int unused(void) { return 3; } int exported(void) { return 7; }\nint main(void) { return exported(); }\n"
            "variable" -> "static int unused = 3; int exported = 7;\nint main(void) { return exported; }\n"
            else -> error("Unexpected declaration form: $form")
        }
        original = content.toByteArray(Charsets.UTF_8)
        Files.write(workspace.resolve("main.c"), original)
        snapshot = ProjectSnapshot(
            Workspace(workspace), emptyList(), listOf(SourceFile(Path.of("main.c"), content, "c")),
        )
    }

    @Given("an isolated C source with a {string} branch containing an alternate reference")
    fun conditionalConfiguration(directive: String) {
        val prefix = when (directive) {
            "if" -> "#if"
            "ifdef" -> "#ifdef"
            "digraph-if" -> "%:if"
            "trigraph-if" -> "??=if"
            "trigraph-spliced-if" -> "??=??/\nif"
            else -> error("Unexpected preprocessor directive: $directive")
        }
        peerFixture("complete empty")
        val content = """static int unused(void) { return 3; }
int main(void) {
$prefix FEATURE
    return unused();
#else
    return 0;
#endif
}
"""
        original = content.toByteArray(Charsets.UTF_8)
        Files.write(workspace.resolve("main.c"), original)
        snapshot = ProjectSnapshot(
            Workspace(workspace), emptyList(), listOf(SourceFile(Path.of("main.c"), content, "c")),
        )
        startSemanticClient = false
    }

    @When("safe delete previews the private C symbol on that line")
    fun previewSameLine() = preview()

    @When("safe delete previews an apparently unused private C function")
    fun preview() {
        val toolchain = if (useCapturedToolchain) {
            val root = Path.of(requireNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
            assertIs<ClangToolchainDiscovery.Available>(ClangToolchainDiscoverer().discover(
                ClangToolchainRequest(
                    workspace,
                    root.resolve("usr/bin/clang-22"),
                    root.resolve("usr/bin/clangd"),
                    root.resolve("usr/bin/clang-format"),
                ),
            )).toolchain
        } else {
            val resourcesRoot = Files.createDirectory(sandbox!!.resolve("clang-resources"))
            val resources = assertIs<ClangResourceCaptureResult.Captured>(ClangResourceDirectoryCapture().capture(resourcesRoot))
            ClangSemanticToolchain(
                clangExecutable = workspace.resolve("unused-clang"),
                clangdExecutable = peer,
                clangFormatExecutable = workspace.resolve("unused-formatter"),
                provenance = ClangToolchainProvenance(
                    clangVersion = "22.1.8", clangdVersion = "22.1.8", clangFormatVersion = "22.1.8",
                    targetTriple = "x86_64-pc-linux-gnu", resourceDir = resourcesRoot.toString(), evidence = emptyList(),
                ),
                resources = resources,
            )
        }
        ExternalSemanticProcessManager().use { manager ->
            val runtimeProvider = if (useCapturedToolchain) ClangToolchainRuntimeCapture() else simulatedRuntimeProvider
            CSafeDeletePlanner(toolchain, manager, runtimeProvider).use { planner ->
                if (startSemanticClient) planner.start(snapshot)
                plan = planner.preview(snapshot, symbolToPreview)
            }
            assertTrue(manager.activeProvenance().isEmpty(), "semantic peer must be reaped")
        }
    }

    @Then("the reference result is unavailable and safe delete proposes no edit")
    fun refused() {
        assertEquals(PatchStatus.REFUSED, plan.status, "incomplete references must not authorize safe delete")
        assertTrue(plan.summary.contains("unavailable"), plan.summary)
        assertTrue(plan.workspaceEdit.edits.isEmpty(), "no edit may be proposed from incomplete evidence")
        assertTrue(plan.affectedFiles.isEmpty())
        assertTrue(Files.readString(peerRequestMarker).contains("textDocument/references"), "refusal must follow an actual reference request")
    }

    @Then("safe delete refuses to remove the neighboring declaration")
    fun refusesUnsafeDeclarationRange() {
        assertEquals(PatchStatus.REFUSED, plan.status, "safe delete must not erase a neighboring declaration")
        assertTrue(plan.summary.contains("exact deletion range"), plan.summary)
        assertTrue(plan.workspaceEdit.edits.isEmpty(), "no partial-name fallback may be used")
    }

    @Then("safe delete refuses because the conditional compilation context is incomplete")
    fun refusesConditionalConfiguration() {
        assertEquals(PatchStatus.REFUSED, plan.status, plan.summary)
        assertTrue(plan.summary.contains("Conditional preprocessor branches"), plan.summary)
        assertTrue(plan.summary.contains("configuration set unavailable"), plan.summary)
        assertTrue(plan.workspaceEdit.edits.isEmpty(), "one active configuration cannot authorize deletion")
        assertTrue(plan.affectedFiles.isEmpty())
    }

    @Then("safe delete previews only the unused private definition and preserves the entry point without workspace writes")
    fun previewsExactPrivateDefinition() {
        assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
        assertTrue(plan.requiresUserApproval)
        assertEquals(RefactoringEvidence.COMPILER_PROVEN, plan.evidence)
        assertEquals(setOf(Path.of("main.c")), plan.affectedFiles)
        val edit = plan.workspaceEdit.edits.single() as FileEdit.Modify
        assertEquals(Path.of("main.c"), edit.path)
        val textEdit = edit.textEdits.single()
        val declaration = "static int unused(void) { return 3; }"
        assertEquals(SourceRange(SourcePosition(0, 0), SourcePosition(0, declaration.length)), textEdit.range)
        assertEquals("", textEdit.newText)

        val staged = WorkspaceEditSimulator.apply(snapshot, plan.workspaceEdit)
        assertEquals("\nint main(void) { return 0; }\n", staged.files.single { it.path == Path.of("main.c") }.content)
        assertContentEquals(original, Files.readAllBytes(workspace.resolve("main.c")))
        assertFalse(Files.exists(workspace.resolve(".refactorkit")), "preview must not create WAL/transactions")
    }

    @Then("safe delete refuses the callback target because its address is used")
    fun refusesAddressTakenFunction() {
        assertEquals(PatchStatus.REFUSED, plan.status, plan.summary)
        assertTrue(plan.summary.contains("semantic reference"), plan.summary)
        assertTrue(plan.workspaceEdit.edits.isEmpty(), "address-taking use must not produce an edit")
        assertTrue(plan.affectedFiles.isEmpty())
    }

    @When("safe delete previews a public C function")
    fun previewPublicFunction() = preview()

    @Then("safe delete refuses the public function because external consumers are unavailable")
    fun refusesPublicFunctionWithoutConsumerEvidence() {
        assertEquals(PatchStatus.REFUSED, plan.status, plan.summary)
        assertTrue(plan.summary.contains("public") && plan.summary.contains("external consumer"), plan.summary)
        assertTrue(plan.workspaceEdit.edits.isEmpty(), "public API without consumer closure must not produce an edit")
        assertTrue(plan.affectedFiles.isEmpty())
    }

    @Then("the source bytes and transaction journal remain unchanged after the refused delete")
    fun unchanged() {
        assertContentEquals(original, Files.readAllBytes(workspace.resolve("main.c")))
        assertFalse(Files.exists(workspace.resolve(".refactorkit")), "refusal must not create WAL/transactions")
    }

    @After
    fun cleanup() {
        sandbox?.let { dir ->
            Files.walk(dir).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
