package org.refactorkit.typescript.relocation

import kotlinx.serialization.json.JsonPrimitive
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.ExternalWorkspaceEditNormalization
import org.refactorkit.core.ExternalWorkspaceEditNormalizer
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import org.refactorkit.typescript.TypeScriptCompilerServerClient
import org.refactorkit.typescript.TypeScriptSemanticToolchain
import org.refactorkit.typescript.TypeScriptToolchainProvenance
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Authored hostile protocol peers provide negative evidence only, not compiler authority. */
internal class TypeScriptCompilerServerTimeoutFixture(
    private val phase: String,
    private val responseFault: String? = null,
) : AutoCloseable {
    private val root = Files.createTempDirectory("rk-ts-deadline-")
    private val workspace = Files.createDirectories(root.resolve("workspace"))
    private val commands = root.resolve("commands.txt")
    private val pid = root.resolve("pid.txt")
    private val processes = ExternalSemanticProcessManager()
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "rk-ts-timeout-test-watchdog").apply { isDaemon = true }
    }
    private val snapshot: ProjectSnapshot
    private val before: Map<String, String>
    private val client: TypeScriptCompilerServerClient
    private var outcome: Result<ExternalWorkspaceEditNormalization>? = null
    private var watchdogExpired = false

    init {
        require(phase in setOf("open", "rename", "partial rename header", "partial rename body"))
        require(responseFault == null || responseFault in setOf(
            "the body is not an array",
            "the file list contains a malformed entry",
            "the text changes contain a malformed entry",
            "the success field is missing",
            "the success field is a string",
            "the replacement text is not a string",
        ))
        val source = "export const A = 1;\n"
        workspace.resolve("a.ts").writeText(source)
        snapshot = ProjectSnapshot(Workspace(workspace), emptyList(), listOf(
            SourceFile(Path.of("a.ts"), source, "typescript"),
        ))
        before = manifest()
        val script = root.resolve("hostile-peer.cjs")
        script.writeText("""
            const fs = require('node:fs');
            const readline = require('node:readline');
            const phase = ${JsonPrimitive(phase)};
            const fault = ${JsonPrimitive(responseFault)};
            fs.writeFileSync(${JsonPrimitive(pid.toString())}, String(process.pid));
            function frame(message) {
                const body = JSON.stringify(message) + '\n';
                process.stdout.write('Content-Length: ' + Buffer.byteLength(body) + '\r\n\r\n' + body);
            }
            readline.createInterface({input: process.stdin}).on('line', line => {
                const request = JSON.parse(line);
                fs.appendFileSync(${JsonPrimitive(commands.toString())}, request.command + '\n');
                if (request.command === 'open' && phase !== 'open') {
                    frame({type: 'event', event: 'projectLoadingFinish', body: {projectName: 'fixture'}});
                    frame({type: 'response', request_seq: request.seq, command: 'open', success: true});
                } else if (request.command === 'getEditsForFileRename') {
                    if (fault !== null) {
                        const edit = {start: {line: 1, offset: 1}, end: {line: 1, offset: 1}, newText: ' '};
                        const file = {fileName: request.arguments.oldFilePath, textChanges: [edit]};
                        const response = {type: 'response', request_seq: request.seq,
                            command: request.command, success: true, body: [file]};
                        switch (fault) {
                            case 'the body is not an array': response.body = {}; break;
                            case 'the file list contains a malformed entry': response.body.push(null); break;
                            case 'the text changes contain a malformed entry': file.textChanges.push({}); break;
                            case 'the success field is missing': delete response.success; break;
                            case 'the success field is a string': response.success = 'true'; break;
                            case 'the replacement text is not a string': edit.newText = 42; break;
                            default: throw new Error('Unknown test response fault');
                        }
                        frame(response);
                    } else {
                        if (phase === 'partial rename header') process.stdout.write('Content-Length: ');
                        if (phase === 'partial rename body') process.stdout.write('Content-Length: 200\r\n\r\n{');
                    }
                }
            });
            setInterval(() => {}, 1000);
        """.trimIndent() + "\n")
        val nodeName = if (System.getProperty("os.name").startsWith("Windows")) "node.exe" else "node"
        val node = System.getenv("PATH").orEmpty().split(System.getProperty("path.separator"))
            .filter(String::isNotBlank).map { Path.of(it).resolve(nodeName).toAbsolutePath() }
            .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
            ?: error("Node is required for the hostile compiler-server peer")
        val toolchain = TypeScriptSemanticToolchain(
            node, script, script, listOf(node.toString(), script.toString()),
            TypeScriptToolchainProvenance(
                nodeVersion = "test-host", languageServerVersion = "hostile-peer",
                typeScriptVersion = "hostile-peer", evidence = emptyList(),
            ),
        )
        client = TypeScriptCompilerServerClient(
            toolchain, processes, requestTimeoutMillis = 1_000, projectLoadTimeoutMillis = 1_000,
        )
    }

    fun request() {
        val pending = worker.submit<Result<ExternalWorkspaceEditNormalization>> {
            runCatching {
                client.start(snapshot)
                client.getEditsForFileRename(
                    Path.of("a.ts"), Path.of("renamed.ts"), snapshot, ExternalWorkspaceEditNormalizer(),
                )
            }
        }
        try {
            outcome = pending.get(6, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            // A harness kill must never masquerade as the client's own bounded refusal.
            watchdogExpired = true
            pending.cancel(true)
            processes.close()
        }
    }

    fun verifyNoProposal() {
        assertFalse(watchdogExpired, "Compiler client exceeded the six-second watchdog with a one-second deadline")
        val result = requireNotNull(outcome)
        val seen = commands.readText().lineSequence().filter(String::isNotBlank).toList()
        assertEquals(if (phase == "open") listOf("open") else listOf("open", "getEditsForFileRename"), seen)
        if (phase == "open") {
            assertIs<IllegalStateException>(result.exceptionOrNull())
        } else {
            val refused = assertIs<ExternalWorkspaceEditNormalization.Refused>(result.getOrThrow())
            assertTrue(refused.diagnostics.isNotEmpty())
            assertTrue(refused.diagnostics.all { it.code?.startsWith("typescript.compilerServer") == true })
        }
    }

    fun verifyMalformedResponse() {
        requireNotNull(responseFault)
        verifyNoProposal()
        val refused = assertIs<ExternalWorkspaceEditNormalization.Refused>(requireNotNull(outcome).getOrThrow())
        assertEquals(listOf("typescript.compilerServerResultInvalid"), refused.diagnostics.map { it.code })
    }

    fun verifyStoppedAndReadOnly() {
        assertTrue(processes.activeProvenance().isEmpty(), "Failed compiler process is still managed")
        assertFalse(ProcessHandle.of(pid.readText().toLong()).map(ProcessHandle::isAlive).orElse(false))
        assertEquals(before, manifest(), "Compiler exchange wrote into the source workspace")
    }

    private fun manifest(): Map<String, String> = Files.walk(workspace).use { paths ->
        paths.filter(Files::isRegularFile).toList().associate { path ->
            workspace.relativize(path).toString() to MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
        }
    }

    override fun close() {
        processes.close()
        client.close()
        worker.shutdownNow()
        worker.awaitTermination(2, TimeUnit.SECONDS)
        root.toFile().deleteRecursively()
    }
}
