package org.refactorkit.c

import org.refactorkit.core.ExternalSemanticProcessManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reproducible fault injection on the managed C semantic process (clangd).
 *
 * R28 forbids evidence that stops only at a "tool not started" guard and forbids
 * treating an unavailable tool as a PASS. These tests launch a fake clangd that
 * actually starts, then inject a real fault (malformed handshake, deadline
 * timeout, oversized frame) and assert the client refuses the fault while the
 * whole managed process tree is reaped - no dangling child, no silent pass.
 */
class CCrashRecoveryTest {
    @Test
    fun malformedHandshakeIsRefusedAndProcessReaped() {
        assertFaultReaped("printf 'Content-Length: 6\\r\\n\\r\\n{bad!}'")
    }

    @Test
    fun deadlineTimeoutIsRefusedAndProcessReaped() {
        // sleep 5 exceeds the 2s initialize deadline (so the tool is still alive when
        // the client times out) but self-exits quickly even if tree termination were
        // ever to miss, so no fake clangd can linger.
        assertFaultReaped("exec sleep 5")
    }

    @Test
    fun oversizedFrameIsRefusedAndProcessReaped() {
        assertFaultReaped("printf 'Content-Length: 20000000\\r\\n\\r\\n'")
    }

    /**
     * Launches a fake clangd that records its own pid then produces the given
     * fault command, drives the client through the initialize handshake, and
     * asserts: the tool actually started (pid recorded), the handshake failed (a
     * real refusal, not a not-started guard), the process manager has no active
     * provenance left, and the recorded pid is no longer alive.
     */
    private fun assertFaultReaped(faultCommand: String) {
        val dir = Files.createTempDirectory("refactorkit-c-fake-clangd")
        val workspace = Files.createTempDirectory("refactorkit-c-fake-ws")
        val pidFile = dir.resolve("fake.pid")
        val script = dir.resolve("fake-clangd.sh")
        Files.writeString(script, "#!/bin/sh\necho \$\$ > ${pidFile}\n$faultCommand\n")
        Files.setPosixFilePermissions(script, setOf(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE,
        ))

        val manager = ExternalSemanticProcessManager()
        val client = ClangdSemanticClient(
            toolchain = ClangSemanticToolchain(
                clangExecutable = Path.of("/usr/bin/clang"),
                clangdExecutable = script,
                clangFormatExecutable = Path.of("/usr/bin/clang-format"),
                provenance = ClangToolchainProvenance(
                    clangVersion = "22.1.8", clangdVersion = "22.1.8", clangFormatVersion = "22.1.8",
                    targetTriple = "x86_64-pc-linux-gnu", resourceDir = "/usr/lib/clang/22", evidence = emptyList(),
                ),
            ),
            processManager = manager,
            requestTimeoutMillis = 2_000L,
            initializeTimeoutMillis = 2_000L,
        )

        var threw = false
        try {
            client.start(workspace)
        } catch (error: IllegalStateException) {
            threw = true
            assertTrue(error.message!!.contains("initialize handshake"), "unexpected refusal: ${error.message}")
        } finally {
            client.close()
        }
        assertTrue(threw, "the fake clangd must be refused (handshake failure), not accepted")

        // The tool actually started: its pid was recorded before the fault.
        assertTrue(Files.exists(pidFile), "fake clangd never started (no pid recorded); not a real fault injection")
        val pid = Files.readString(pidFile).trim().toLong()
        assertTrue(pid > 0, "recorded pid invalid")

        // The fault path must leave no active managed process and no live child.
        assertEquals(0, manager.activeProvenance().size, "process manager leaked an active provenance")
        waitForProcessExit(pid)
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "managed clangd child pid $pid was not reaped")
        manager.close()
    }

    private fun waitForProcessExit(pid: Long) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false) && System.nanoTime() < deadline) {
            Thread.sleep(50)
        }
    }
}
