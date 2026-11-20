package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.refactorkit.c.CClangdSemanticResult
import org.refactorkit.c.ClangSemanticToolchain
import org.refactorkit.c.ClangToolchainDiscoverer
import org.refactorkit.c.ClangToolchainDiscovery
import org.refactorkit.c.ClangToolchainRequest
import org.refactorkit.c.ClangdSemanticClient
import org.refactorkit.c.ClangToolchainRuntimeCaptureResult
import org.refactorkit.c.ClangToolchainRuntimeCurrency
import org.refactorkit.c.ClangToolchainRuntimeProvider
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.SemanticProcessLimitException
import org.refactorkit.core.SemanticProcessLimits
import org.refactorkit.core.SemanticProcessSpec
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CClangdLaunchConfigurationSteps {
    private val simulatedRuntimeProvider = object : ClangToolchainRuntimeProvider {
        override fun capture(clang: Path, clangd: Path, formatter: Path): ClangToolchainRuntimeCaptureResult =
            error("The process-tree fixture does not qualify native runtime capture")

        override fun checkCurrent(captured: ClangToolchainRuntimeCaptureResult.Captured) =
            ClangToolchainRuntimeCurrency.Current

        override fun checkCurrent(toolchain: ClangSemanticToolchain) =
            ClangToolchainRuntimeCurrency.Current
    }

    private var root: Path? = null
    private var recordDirectory: Path? = null
    private lateinit var originals: Map<Path, String>
    private lateinit var toolchain: ClangSemanticToolchain
    private var launchFailure: Throwable? = null
    private var pid: Long? = null
    private var childPid: Long? = null
    private var timeoutElapsedMillis: Long? = null
    private var cancellationElapsedMillis: Long? = null
    private var processTreeClosedBeforeManagerShutdown: Boolean? = null
    private var processManagerEmptyAfterTimeout: Boolean? = null
    private var processManagerEmptyAfterCancellation: Boolean? = null
    private var firstClangdProcessId: String? = null
    private var restartedPid: Long? = null
    private var restartedClangdProcessId: String? = null
    private var restartCompletedWithFreshProcess: Boolean? = null
    private var processManagerEmptyAfterRestart: Boolean? = null
    private var concurrentCloseFailures: List<Throwable> = emptyList()
    private var concurrentCloseCallCount: Int? = null
    private var concurrentCloseElapsedMillis: Long? = null
    private var processTreeClosedAfterConcurrentClose: Boolean? = null
    private var processManagerEmptyAfterConcurrentClose: Boolean? = null
    private var startupSettledAfterAdmissionClose: Boolean? = null
    private var processManagerEmptyAfterAdmissionClose: Boolean? = null
    private var processManagerEmptyAfterMalformedInitialize: Boolean? = null
    private var processManagerEmptyAfterOversizedInitialize: Boolean? = null
    private var processManagerEmptyAfterTruncatedInitialize: Boolean? = null
    private var processManagerEmptyAfterStdoutOverflow: Boolean? = null
    private var processManagerEmptyAfterStderrOverflow: Boolean? = null
    private var oversizedInitializeElapsedMillis: Long? = null
    private var truncatedInitializeElapsedMillis: Long? = null
    private var stdoutOverflowElapsedMillis: Long? = null
    private var stderrOverflowObserved: Boolean? = null
    private var capturedStderrBytes: Int? = null
    private var definition: CClangdSemanticResult? = null
    private var privateDatabase: Path? = null
    private var admittedDatabase: String? = null

    @Given("an isolated C project with {string} launch inputs")
    fun launchInputs(configuration: String) {
        check(System.getProperty("os.name").startsWith("Linux") && System.getProperty("os.arch") == "amd64") {
            "Reference qualification requires Linux x86-64"
        }
        val workspace = Files.createTempDirectory("refactorkit-c-clangd-launch-")
        root = workspace
        val reference = Path.of(requireNotNull(System.getProperty("refactorkit.c.qualificationToolchain")) {
            "C semantic-process tests require the configured qualification toolchain"
        })
        toolchain = when (val result = ClangToolchainDiscoverer().discover(ClangToolchainRequest(
            workspaceRoot = workspace, clangExecutable = reference.resolve("usr/bin/clang-22"),
            clangdExecutable = reference.resolve("usr/bin/clangd"),
            clangFormatExecutable = reference.resolve("usr/bin/clang-format"),
        ))) {
            is ClangToolchainDiscovery.Available -> result.toolchain
            is ClangToolchainDiscovery.Refused -> error("Reference toolchain unavailable: ${result.diagnostics}")
        }
        assertTrue(listOf(toolchain.provenance.clangVersion, toolchain.provenance.clangdVersion,
            toolchain.provenance.clangFormatVersion).all { it.contains("22.1.8") })
        val wrapper = workspace.resolve("project-compiler")
        Files.writeString(wrapper, "#!/usr/bin/python3\nfrom pathlib import Path\nPath(${JsonPrimitive(workspace.resolve("command-ran").toString())}).write_text('executed')\n")
        check(wrapper.toFile().setExecutable(true))
        Files.writeString(workspace.resolve("main.c"), """
            #ifdef ENABLED
            int compute(void) { return 17; }
            #else
            int missing_context(void) { return 0; }
            #endif
            int main(void) { return compute(); }
        """.trimIndent() + "\n")
        val extra = when (configuration) {
            "explicit C17 arguments", "hostile project config", "command string" -> emptyList()
            "plugin arguments" -> listOf("-Xclang", "-load", workspace.resolve("unknown-plugin.so").toString())
            "response file" -> listOf("@${workspace.resolve("flags.rsp")}")
            "compiler config file" -> listOf("--config=${workspace.resolve("compiler.cfg")}")
            else -> error("Unexpected launch configuration: $configuration")
        }
        Files.writeString(workspace.resolve("flags.rsp"), "-Xclang -load unknown-plugin.so\n")
        Files.writeString(workspace.resolve("compiler.cfg"), "-Xclang -load unknown-plugin.so\n")
        if (configuration == "hostile project config") {
            Files.writeString(workspace.resolve(".clangd"),
                "CompileFlags:\n  Add: [-UENABLED, -Xclang, -load, '${workspace.resolve("unknown-plugin.so")}']\n")
        }
        val arguments = listOf(wrapper.toString(), "-std=c17", "-DENABLED=1") + extra + listOf("-c", "main.c", "-o", "main.o")
        val unit = buildJsonObject {
            put("file", "main.c"); put("directory", workspace.toString())
            if (configuration == "command string") put("command", arguments.joinToString(" "))
            else put("arguments", JsonArray(arguments.map(::JsonPrimitive)))
        }
        Files.writeString(workspace.resolve("compile_commands.json"), JsonArray(listOf(unit)).toString())
        originals = projectFiles(workspace)
    }

    @Given("a C project with a fake clangd that starts a child and waits")
    fun fakeClangdWithLiveChild() {
        check(System.getProperty("os.name").startsWith("Linux") && System.getProperty("os.arch") == "amd64") {
            "Reference qualification requires Linux x86-64"
        }
        val workspace = Files.createTempDirectory("refactorkit-c-clangd-timeout-")
        root = workspace
        val records = Files.createTempDirectory("refactorkit-c-clangd-timeout-record-")
        recordDirectory = records
        val reference = Path.of(requireNotNull(System.getProperty("refactorkit.c.qualificationToolchain")) {
            "C semantic-process tests require the configured qualification toolchain"
        })
        val baseToolchain = when (val result = ClangToolchainDiscoverer().discover(ClangToolchainRequest(
            workspaceRoot = workspace,
            clangExecutable = reference.resolve("usr/bin/clang-22"),
            clangdExecutable = reference.resolve("usr/bin/clangd"),
            clangFormatExecutable = reference.resolve("usr/bin/clang-format"),
        ))) {
            is ClangToolchainDiscovery.Available -> result.toolchain
            is ClangToolchainDiscovery.Refused -> error("Reference toolchain unavailable: ${result.diagnostics}")
        }
        assertTrue(listOf(baseToolchain.provenance.clangVersion, baseToolchain.provenance.clangdVersion,
            baseToolchain.provenance.clangFormatVersion).all { it.contains("22.1.8") })

        val main = workspace.resolve("main.c")
        Files.writeString(main, "int compute(void) { return 17; }\n")
        val arguments = listOf(baseToolchain.clangExecutable.toString(), "-std=c17", "-c", "main.c", "-o", "main.o")
        val unit = buildJsonObject {
            put("file", "main.c")
            put("directory", workspace.toString())
            put("arguments", JsonArray(arguments.map(::JsonPrimitive)))
        }
        Files.writeString(workspace.resolve("compile_commands.json"), JsonArray(listOf(unit)).toString())

        val parentRecord = records.resolve("parent.pid")
        val childRecord = records.resolve("child.pid")
        val fake = records.resolve("fake-clangd.sh")
        Files.writeString(fake, """
            #!/bin/sh
            printf '%s\n' "${'$'}${'$'}" > ${shellQuote(parentRecord.toString())}
            sleep 30 &
            child=${'$'}!
            printf '%s\n' "${'$'}child" > ${shellQuote(childRecord.toString())}
            wait "${'$'}child"
        """.trimIndent() + "\n")
        check(fake.toFile().setExecutable(true))
        toolchain = baseToolchain.copy(clangdExecutable = fake.toAbsolutePath().normalize())
        originals = projectFiles(workspace)
    }

    @When("clangd initialization exceeds its deadline")
    fun clangdInitializeTimeout() {
        val workspace = requireNotNull(root)
        val manager = ExternalSemanticProcessManager()
        val client = ClangdSemanticClient(toolchain, manager, initializeTimeoutMillis = 1_200L, runtimeProvider = simulatedRuntimeProvider)
        val startedAt = System.nanoTime()
        launchFailure = runCatching { client.start(workspace) }.exceptionOrNull()
        timeoutElapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        val records = requireNotNull(recordDirectory)
        val recordedParent = records.resolve("parent.pid")
        val recordedChild = records.resolve("child.pid")
        if (Files.isRegularFile(recordedParent)) pid = Files.readString(recordedParent).trim().toLongOrNull()
        if (Files.isRegularFile(recordedChild)) childPid = Files.readString(recordedChild).trim().toLongOrNull()
        val processIds = listOfNotNull(pid, childPid)
        processTreeClosedBeforeManagerShutdown = processIds.size == 2 && awaitProcessExit(processIds)
        processManagerEmptyAfterTimeout = manager.activeProvenance().isEmpty()
        client.close()
        manager.close()
    }

    @Then("startup refuses after the timeout and closes the complete process tree")
    fun startupTimesOutAndClosesTree() {
        val failure = assertNotNull(launchFailure, "the fake server withheld its initialize response")
        assertTrue(failure.message.orEmpty().contains("initialize handshake"), failure.toString())
        assertTrue((timeoutElapsedMillis ?: 0L) in 900L..5_000L,
            "startup did not wait for and enforce the 1.2s initialize deadline: $timeoutElapsedMillis ms")
        val parent = assertNotNull(pid, "fake clangd did not record that the process started")
        val child = assertNotNull(childPid, "fake clangd did not start its descendant before the timeout")
        assertTrue(parent > 0 && child > 0 && parent != child)
        assertEquals(true, processManagerEmptyAfterTimeout, "timed-out clangd remained registered")
        assertEquals(true, processTreeClosedBeforeManagerShutdown,
            "the timeout path must reap the server and child before manager shutdown")
    }

    @When("clangd initialization is cancelled after its child starts")
    fun cancelClangdInitialization() {
        val workspace = requireNotNull(root)
        val manager = ExternalSemanticProcessManager()
        val client = ClangdSemanticClient(toolchain, manager, initializeTimeoutMillis = 20_000L, runtimeProvider = simulatedRuntimeProvider)
        val executor = Executors.newSingleThreadExecutor()
        val startup = executor.submit<Throwable?> { runCatching { client.start(workspace) }.exceptionOrNull() }
        val records = requireNotNull(recordDirectory)
        try {
            val parentPath = records.resolve("parent.pid")
            val childPath = records.resolve("child.pid")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while ((!Files.isRegularFile(parentPath) || !Files.isRegularFile(childPath)) && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            pid = Files.readString(parentPath).trim().toLongOrNull()
            childPid = Files.readString(childPath).trim().toLongOrNull()
            val processIds = listOfNotNull(pid, childPid)
            check(processIds.size == 2 && processIds.all { ProcessHandle.of(it).map(ProcessHandle::isAlive).orElse(false) }) {
                "fake clangd and its child must both be alive before cancellation"
            }

            val cancelledAt = System.nanoTime()
            client.close()
            cancellationElapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelledAt)
            launchFailure = try {
                startup.get(5, TimeUnit.SECONDS)
            } catch (failure: ExecutionException) {
                failure.cause ?: failure
            }
            processTreeClosedBeforeManagerShutdown = awaitProcessExit(processIds)
            processManagerEmptyAfterCancellation = manager.activeProvenance().isEmpty()
        } finally {
            client.close()
            manager.close()
            executor.shutdownNow()
        }
    }

    @Then("cancellation promptly refuses startup and closes the complete process tree")
    fun cancellationRefusesAndClosesTree() {
        val failure = assertNotNull(launchFailure, "cancelling an in-flight initialize handshake must refuse startup")
        assertTrue(failure.message.orEmpty().contains("initialize handshake"), failure.toString())
        assertTrue((cancellationElapsedMillis ?: Long.MAX_VALUE) in 0L..5_000L,
            "cancellation did not close the client promptly: $cancellationElapsedMillis ms")
        assertNotNull(pid, "fake clangd did not record its process ID")
        assertNotNull(childPid, "fake clangd did not record its child process ID")
        assertEquals(true, processTreeClosedBeforeManagerShutdown,
            "cancellation must reap the server and child before manager shutdown")
        assertEquals(true, processManagerEmptyAfterCancellation, "cancelled clangd remained registered")
    }

    @When("the cancelled clangd client restarts against a responding server")
    fun restartClangdAfterCancellation() {
        val workspace = requireNotNull(root)
        val records = requireNotNull(recordDirectory)
        val manager = ExternalSemanticProcessManager()
        val client = ClangdSemanticClient(toolchain, manager, initializeTimeoutMillis = 20_000L, runtimeProvider = simulatedRuntimeProvider)
        val executor = Executors.newSingleThreadExecutor()
        val startup = executor.submit<Throwable?> { runCatching { client.start(workspace) }.exceptionOrNull() }
        val parentPath = records.resolve("parent.pid")
        val childPath = records.resolve("child.pid")
        val initialProcessIds = mutableListOf<Long>()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while ((!Files.isRegularFile(parentPath) || !Files.isRegularFile(childPath)) && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            pid = Files.readString(parentPath).trim().toLongOrNull()
            childPid = Files.readString(childPath).trim().toLongOrNull()
            initialProcessIds += listOfNotNull(pid, childPid)
            check(initialProcessIds.size == 2 && initialProcessIds.all { ProcessHandle.of(it).map(ProcessHandle::isAlive).orElse(false) }) {
                "fake clangd and its child must both be alive before cancellation"
            }
            firstClangdProcessId = manager.activeProvenance().singleOrNull()?.id

            client.close()
            launchFailure = startup.get(5, TimeUnit.SECONDS)
            check(launchFailure?.message.orEmpty().contains("initialize handshake")) {
                "cancelled initialize must refuse before restart: $launchFailure"
            }
            check(awaitProcessExit(initialProcessIds)) { "cancelled clangd process tree did not exit before restart" }
            check(manager.activeProvenance().isEmpty()) { "cancelled clangd stayed registered before restart" }

            val responder = records.resolve("fake-clangd.sh")
            val restartedPidFile = records.resolve("restart.pid")
            Files.writeString(responder, respondingClangdScript(restartedPidFile))
            check(responder.toFile().setExecutable(true))

            client.start(workspace)
            val restarted = client.processProvenance()
            restartedPid = restarted?.pid
            restartedClangdProcessId = restarted?.id
            val active = manager.activeProvenance()
            restartCompletedWithFreshProcess = restarted != null && active.size == 1 &&
                active.single().id == restarted.id && restarted.id != firstClangdProcessId &&
                restarted.pid !in initialProcessIds
            client.close()
            processTreeClosedBeforeManagerShutdown = awaitProcessExit(initialProcessIds + listOfNotNull(restartedPid))
            processManagerEmptyAfterRestart = manager.activeProvenance().isEmpty()
        } finally {
            client.close()
            manager.close()
            executor.shutdownNow()
        }
    }

    @Then("the restart completes initialization with fresh process provenance")
    fun restartCompletesWithFreshProvenance() {
        assertTrue(launchFailure?.message.orEmpty().contains("initialize handshake"),
            "the first server must be cancelled before restart: $launchFailure")
        assertNotNull(firstClangdProcessId, "the initial process must have registered provenance")
        assertNotNull(restartedPid, "the restarted server must expose process provenance")
        assertNotNull(restartedClangdProcessId, "the restarted server must expose a process identity")
        assertNotEquals(firstClangdProcessId, restartedClangdProcessId,
            "restart must use a fresh semantic process identity")
        assertEquals(true, restartCompletedWithFreshProcess,
            "restart must register exactly the fresh live process")
        assertEquals(true, processTreeClosedBeforeManagerShutdown,
            "the old tree and restarted process must exit before manager shutdown")
        assertEquals(true, processManagerEmptyAfterRestart,
            "the process manager must not retain either session after restart cleanup")
    }

    @When("concurrent callers close the client while initialization is pending")
    fun concurrentCloseDuringInitialize() {
        val workspace = requireNotNull(root)
        val records = requireNotNull(recordDirectory)
        val manager = ExternalSemanticProcessManager()
        val client = ClangdSemanticClient(toolchain, manager, initializeTimeoutMillis = 20_000L, runtimeProvider = simulatedRuntimeProvider)
        val starter = Executors.newSingleThreadExecutor()
        val closers = Executors.newFixedThreadPool(8)
        val startEntered = CountDownLatch(1)
        val startup = starter.submit<Throwable?> {
            startEntered.countDown()
            runCatching { client.start(workspace) }.exceptionOrNull()
        }
        val parentPath = records.resolve("parent.pid")
        val childPath = records.resolve("child.pid")
        try {
            check(startEntered.await(5, TimeUnit.SECONDS)) { "startup worker did not enter the client call" }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while ((!Files.isRegularFile(parentPath) || !Files.isRegularFile(childPath)) &&
                !startup.isDone && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            pid = Files.readString(parentPath).trim().toLongOrNull()
            childPid = Files.readString(childPath).trim().toLongOrNull()
            val processIds = listOfNotNull(pid, childPid)
            check(processIds.size == 2 && processIds.all { ProcessHandle.of(it).map(ProcessHandle::isAlive).orElse(false) }) {
                "fake clangd and its child must both be alive before concurrent close"
            }

            val ready = CountDownLatch(8)
            val release = CountDownLatch(1)
            val closeTasks = (1..8).map {
                closers.submit<Throwable?> {
                    ready.countDown()
                    if (!release.await(5, TimeUnit.SECONDS)) error("close barrier timed out")
                    runCatching { client.close() }.exceptionOrNull()
                }
            }
            check(ready.await(5, TimeUnit.SECONDS)) { "all close callers must reach the barrier" }
            val closeStartedAt = System.nanoTime()
            release.countDown()
            concurrentCloseFailures = closeTasks.mapNotNull { it.get(5, TimeUnit.SECONDS) }
            concurrentCloseElapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closeStartedAt)
            concurrentCloseCallCount = closeTasks.size
            launchFailure = startup.get(5, TimeUnit.SECONDS)
            processTreeClosedAfterConcurrentClose = awaitProcessExit(processIds)
            processManagerEmptyAfterConcurrentClose = manager.activeProvenance().isEmpty()
        } finally {
            client.close()
            manager.close()
            closers.shutdownNow()
            starter.shutdownNow()
        }
    }

    @Then("startup refuses and every close caller completes cleanly")
    fun concurrentCloseRefusesStartupCleanly() {
        assertNotNull(launchFailure, "closing the client must cancel its pending initialize handshake")
        assertEquals(8, concurrentCloseCallCount)
        assertTrue(concurrentCloseFailures.isEmpty(), concurrentCloseFailures.joinToString("\n"))
        assertTrue((concurrentCloseElapsedMillis ?: Long.MAX_VALUE) in 0L..5_000L,
            "concurrent closes did not return promptly: $concurrentCloseElapsedMillis ms")
        assertEquals(true, processTreeClosedAfterConcurrentClose,
            "concurrent closes must reap the clangd process tree before manager shutdown")
        assertEquals(true, processManagerEmptyAfterConcurrentClose,
            "concurrent closes must leave no process provenance registered")
    }

    @When("the client closes while native runtime admission is blocked")
    fun closeDuringRuntimeAdmission() {
        val workspace = requireNotNull(root)
        val records = requireNotNull(recordDirectory)
        val manager = ExternalSemanticProcessManager()
        val admissionEntered = CountDownLatch(1)
        val releaseAdmission = CountDownLatch(1)
        val blockingRuntimeProvider = object : ClangToolchainRuntimeProvider {
            override fun capture(clang: Path, clangd: Path, formatter: Path): ClangToolchainRuntimeCaptureResult =
                error("This controlled race does not qualify native runtime capture")

            override fun checkCurrent(captured: ClangToolchainRuntimeCaptureResult.Captured) =
                ClangToolchainRuntimeCurrency.Current

            override fun checkCurrent(toolchain: ClangSemanticToolchain): ClangToolchainRuntimeCurrency {
                admissionEntered.countDown()
                check(releaseAdmission.await(5, TimeUnit.SECONDS)) { "runtime admission release timed out" }
                return ClangToolchainRuntimeCurrency.Current
            }
        }
        val client = ClangdSemanticClient(
            toolchain,
            manager,
            initializeTimeoutMillis = 20_000L,
            runtimeProvider = blockingRuntimeProvider,
        )
        val starter = Executors.newSingleThreadExecutor()
        val startup = starter.submit<Throwable?> { runCatching { client.start(workspace) }.exceptionOrNull() }
        try {
            assertTrue(admissionEntered.await(5, TimeUnit.SECONDS), "startup did not enter runtime admission")
            client.close()
            releaseAdmission.countDown()

            val parentPath = records.resolve("parent.pid")
            val childPath = records.resolve("child.pid")
            val launchDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while ((!Files.exists(parentPath) || !Files.exists(childPath)) && !startup.isDone &&
                System.nanoTime() < launchDeadline) {
                Thread.sleep(10)
            }
            pid = parentPath.takeIf(Files::isRegularFile)?.let { Files.readString(it).trim().toLongOrNull() }
            childPid = childPath.takeIf(Files::isRegularFile)?.let { Files.readString(it).trim().toLongOrNull() }
            launchFailure = try {
                startup.get(5, TimeUnit.SECONDS).also { startupSettledAfterAdmissionClose = true }
            } catch (failure: ExecutionException) {
                startupSettledAfterAdmissionClose = true
                failure.cause ?: failure
            } catch (_: TimeoutException) {
                startupSettledAfterAdmissionClose = false
                null
            }
            processManagerEmptyAfterAdmissionClose = manager.activeProvenance().isEmpty()
        } finally {
            releaseAdmission.countDown()
            client.close()
            manager.close()
            starter.shutdownNow()
        }
    }

    @Then("startup refuses without launching clangd or retaining process provenance")
    fun admissionClosePreventsLateLaunch() {
        assertNull(pid, "clangd must not launch after its pending startup was closed")
        assertNull(childPid, "no clangd descendant may be created after startup cancellation")
        assertNotNull(launchFailure, "a close during runtime admission must refuse the pending startup")
        assertEquals(true, startupSettledAfterAdmissionClose, "cancelled startup must settle promptly")
        assertEquals(true, processManagerEmptyAfterAdmissionClose,
            "cancelled startup must leave no semantic process provenance registered")
    }

    @Given("a C project with fake clangd that starts a child and sends malformed LSP")
    fun fakeClangdWithMalformedInitializeResponse() {
        fakeClangdWithLiveChild()
        val records = requireNotNull(recordDirectory)
        val parentRecord = records.resolve("parent.pid")
        val childRecord = records.resolve("child.pid")
        val fake = records.resolve("fake-clangd.sh")
        Files.writeString(fake, """
            #!/bin/sh
            printf '%s\n' "${'$'}${'$'}" > ${shellQuote(parentRecord.toString())}
            sleep 30 &
            child=${'$'}!
            printf '%s\n' "${'$'}child" > ${shellQuote(childRecord.toString())}
            printf 'Content-Length: malformed\r\n\r\n'
            wait "${'$'}child"
        """.trimIndent() + "\n")
        check(fake.toFile().setExecutable(true))
    }

    @Given("a C project with fake clangd that starts a child and announces an oversized LSP frame")
    fun fakeClangdWithOversizedInitializeFrame() {
        fakeClangdWithLiveChild()
        val records = requireNotNull(recordDirectory)
        val fake = records.resolve("fake-clangd.sh")
        Files.writeString(fake, """
            #!/bin/sh
            printf '%s\n' "${'$'}${'$'}" > ${shellQuote(records.resolve("parent.pid").toString())}
            sleep 30 &
            child=${'$'}!
            printf '%s\n' "${'$'}child" > ${shellQuote(records.resolve("child.pid").toString())}
            printf 'Content-Length: 16777217\r\n\r\n'
            wait "${'$'}child"
        """.trimIndent() + "\n")
        check(fake.toFile().setExecutable(true))
    }

    @Given("a C project with fake clangd that starts a child and announces a truncated LSP frame")
    fun fakeClangdWithTruncatedInitializeFrame() {
        fakeClangdWithLiveChild()
        val records = requireNotNull(recordDirectory)
        val fake = records.resolve("fake-clangd.sh")
        Files.writeString(fake, """
            #!/bin/sh
            printf '%s\n' "${'$'}${'$'}" > ${shellQuote(records.resolve("parent.pid").toString())}
            sleep 30 >/dev/null 2>&1 &
            child=${'$'}!
            printf '%s\n' "${'$'}child" > ${shellQuote(records.resolve("child.pid").toString())}
            printf 'Content-Length: 64\r\n\r\n{"jsonrpc":"2.0","id":1,"result":'
            exec 1>&-
            wait "${'$'}child"
        """.trimIndent() + "\n")
        check(fake.toFile().setExecutable(true))
    }

    @Given("a bounded C semantic process that starts a child and exceeds its stdout budget")
    fun boundedCSemanticProcessWithStdoutOverflow() {
        fakeClangdWithLiveChild()
        val records = requireNotNull(recordDirectory)
        val fake = records.resolve("fake-clangd.sh")
        Files.writeString(fake, """
            #!/usr/bin/python3
            import os
            import subprocess
            from pathlib import Path

            Path(${JsonPrimitive(records.resolve("parent.pid").toString())}).write_text(str(os.getpid()))
            child = subprocess.Popen(
                ["/usr/bin/sleep", "30"],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            Path(${JsonPrimitive(records.resolve("child.pid").toString())}).write_text(str(child.pid))
            remaining = memoryview(b"\0" * 4096)
            while remaining:
                remaining = remaining[os.write(1, remaining):]
            child.wait()
        """.trimIndent() + "\n")
        check(fake.toFile().setExecutable(true))
    }

    @Given("a bounded C semantic process that starts a child and exceeds its stderr budget")
    fun boundedCSemanticProcessWithStderrOverflow() {
        fakeClangdWithLiveChild()
        val records = requireNotNull(recordDirectory)
        val fake = records.resolve("fake-clangd.sh")
        Files.writeString(fake, """
            #!/usr/bin/python3
            import os
            import subprocess
            from pathlib import Path

            Path(${JsonPrimitive(records.resolve("parent.pid").toString())}).write_text(str(os.getpid()))
            child = subprocess.Popen(
                ["/usr/bin/sleep", "30"],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            Path(${JsonPrimitive(records.resolve("child.pid").toString())}).write_text(str(child.pid))
            remaining = memoryview(b"x" * 4096)
            while remaining:
                remaining = remaining[os.write(2, remaining):]
            child.wait()
        """.trimIndent() + "\n")
        check(fake.toFile().setExecutable(true))
    }

    @When("clangd returns malformed initialize framing while its child is alive")
    fun malformedInitializeResponse() {
        val workspace = requireNotNull(root)
        val manager = ExternalSemanticProcessManager()
        val client = ClangdSemanticClient(toolchain, manager, initializeTimeoutMillis = 20_000L, runtimeProvider = simulatedRuntimeProvider)
        launchFailure = runCatching { client.start(workspace) }.exceptionOrNull()
        val records = requireNotNull(recordDirectory)
        pid = records.resolve("parent.pid").takeIf(Files::isRegularFile)
            ?.let { Files.readString(it).trim().toLongOrNull() }
        childPid = records.resolve("child.pid").takeIf(Files::isRegularFile)
            ?.let { Files.readString(it).trim().toLongOrNull() }
        val processIds = listOfNotNull(pid, childPid)
        processTreeClosedBeforeManagerShutdown = processIds.size == 2 && awaitProcessExit(processIds)
        processManagerEmptyAfterMalformedInitialize = manager.activeProvenance().isEmpty()
        client.close()
        manager.close()
    }

    @Then("startup refuses and closes the malformed server process tree")
    fun malformedInitializeRefusesAndClosesTree() {
        val failure = assertNotNull(launchFailure, "malformed initialize framing must refuse clangd startup")
        assertTrue(failure.message.orEmpty().contains("initialize handshake"), failure.toString())
        assertNotNull(pid, "malformed fake clangd did not record its process ID")
        assertNotNull(childPid, "malformed fake clangd did not start and record its child")
        assertEquals(true, processTreeClosedBeforeManagerShutdown,
            "malformed initialize response must reap the server and child before manager shutdown")
        assertEquals(true, processManagerEmptyAfterMalformedInitialize,
            "malformed initialize response must leave no process provenance registered")
    }

    @When("clangd announces an initialize frame over the protocol size limit")
    fun oversizedInitializeFrame() {
        val workspace = requireNotNull(root)
        val manager = ExternalSemanticProcessManager()
        val client = ClangdSemanticClient(toolchain, manager, initializeTimeoutMillis = 20_000L,
            runtimeProvider = simulatedRuntimeProvider)
        val startedAt = System.nanoTime()
        launchFailure = runCatching { client.start(workspace) }.exceptionOrNull()
        oversizedInitializeElapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        val records = requireNotNull(recordDirectory)
        pid = records.resolve("parent.pid").takeIf(Files::isRegularFile)
            ?.let { Files.readString(it).trim().toLongOrNull() }
        childPid = records.resolve("child.pid").takeIf(Files::isRegularFile)
            ?.let { Files.readString(it).trim().toLongOrNull() }
        val processIds = listOfNotNull(pid, childPid)
        processTreeClosedBeforeManagerShutdown = processIds.size == 2 && awaitProcessExit(processIds)
        processManagerEmptyAfterOversizedInitialize = manager.activeProvenance().isEmpty()
        client.close()
        manager.close()
    }

    @Then("startup refuses the oversized frame and closes the complete process tree")
    fun oversizedInitializeRefusesAndClosesTree() {
        val failure = assertNotNull(launchFailure, "an oversized initialize frame must refuse clangd startup")
        assertTrue(failure.message.orEmpty().contains("initialize handshake"), failure.toString())
        assertTrue((oversizedInitializeElapsedMillis ?: Long.MAX_VALUE) < 5_000L,
            "oversized framing should be rejected before the 20s initialize deadline: $oversizedInitializeElapsedMillis ms")
        assertNotNull(pid, "fake clangd did not record its process ID")
        assertNotNull(childPid, "fake clangd did not start and record its child")
        assertEquals(true, processTreeClosedBeforeManagerShutdown,
            "oversized initialize framing must reap the server and child before manager shutdown")
        assertEquals(true, processManagerEmptyAfterOversizedInitialize,
            "oversized initialize framing must leave no process provenance registered")
    }

    @When("clangd closes initialize output before sending the declared body length")
    fun truncatedInitializeFrame() {
        val workspace = requireNotNull(root)
        val manager = ExternalSemanticProcessManager()
        val client = ClangdSemanticClient(toolchain, manager, initializeTimeoutMillis = 20_000L,
            runtimeProvider = simulatedRuntimeProvider)
        val startedAt = System.nanoTime()
        launchFailure = runCatching { client.start(workspace) }.exceptionOrNull()
        truncatedInitializeElapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        val records = requireNotNull(recordDirectory)
        pid = records.resolve("parent.pid").takeIf(Files::isRegularFile)
            ?.let { Files.readString(it).trim().toLongOrNull() }
        childPid = records.resolve("child.pid").takeIf(Files::isRegularFile)
            ?.let { Files.readString(it).trim().toLongOrNull() }
        val processIds = listOfNotNull(pid, childPid)
        processTreeClosedBeforeManagerShutdown = processIds.size == 2 && awaitProcessExit(processIds)
        processManagerEmptyAfterTruncatedInitialize = manager.activeProvenance().isEmpty()
        client.close()
        manager.close()
    }

    @Then("startup refuses the truncated frame and closes the complete process tree")
    fun truncatedInitializeRefusesAndClosesTree() {
        val failure = assertNotNull(launchFailure, "a truncated initialize body must refuse clangd startup")
        assertTrue(failure.message.orEmpty().contains("initialize handshake"), failure.toString())
        assertTrue((truncatedInitializeElapsedMillis ?: Long.MAX_VALUE) < 5_000L,
            "truncated framing should be rejected before the 20s initialize deadline: $truncatedInitializeElapsedMillis ms")
        assertNotNull(pid, "fake clangd did not record its process ID")
        assertNotNull(childPid, "fake clangd did not start and record its child")
        assertEquals(true, processTreeClosedBeforeManagerShutdown,
            "truncated initialize framing must reap the server and child before manager shutdown")
        assertEquals(true, processManagerEmptyAfterTruncatedInitialize,
            "truncated initialize framing must leave no process provenance registered")
    }

    @When("the C semantic process writes beyond its managed stdout limit")
    fun cSemanticProcessStdoutOverflow() {
        val workspace = requireNotNull(root)
        val records = requireNotNull(recordDirectory)
        val manager = ExternalSemanticProcessManager()
        val fake = records.resolve("fake-clangd.sh").toAbsolutePath().normalize()
        val process = manager.launch(SemanticProcessSpec(
            id = "c02-stdout-overflow",
            executable = fake,
            workingDirectory = workspace,
            limits = SemanticProcessLimits(maxStdoutBytes = 1_024, maxStderrBytes = 1_024, gracefulShutdownMillis = 200),
        ))
        val startedAt = System.nanoTime()
        launchFailure = runCatching { process.output.readBytes() }.exceptionOrNull()
        stdoutOverflowElapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        pid = records.resolve("parent.pid").takeIf(Files::isRegularFile)
            ?.let { Files.readString(it).trim().toLongOrNull() }
        childPid = records.resolve("child.pid").takeIf(Files::isRegularFile)
            ?.let { Files.readString(it).trim().toLongOrNull() }
        val processIds = listOfNotNull(pid, childPid)
        processTreeClosedBeforeManagerShutdown = processIds.size == 2 && awaitProcessExit(processIds)
        val managerDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (manager.activeProvenance().isNotEmpty() && System.nanoTime() < managerDeadline) Thread.sleep(10)
        processManagerEmptyAfterStdoutOverflow = manager.activeProvenance().isEmpty()
        process.close()
        manager.close()
    }

    @Then("stdout overflow refuses the process and reaps its complete process tree")
    fun cSemanticProcessStdoutOverflowRefusesAndClosesTree() {
        val failure = assertIs<SemanticProcessLimitException>(
            assertNotNull(launchFailure, "stdout beyond the managed budget must be refused"),
            "managed stdout overflow must return the typed process-limit failure",
        )
        assertTrue(failure.message.orEmpty().contains("stdout exceeded 1024 bytes"), failure.message.orEmpty())
        assertTrue((stdoutOverflowElapsedMillis ?: Long.MAX_VALUE) < 5_000L,
            "stdout overflow must terminate the process before its child wait completes")
        assertNotNull(pid, "overflowing semantic process did not record its process ID")
        assertNotNull(childPid, "overflowing semantic process did not start its child")
        assertEquals(true, processTreeClosedBeforeManagerShutdown,
            "stdout overflow must reap the managed process and child")
        assertEquals(true, processManagerEmptyAfterStdoutOverflow,
            "stdout overflow must clear process-manager provenance")
    }

    @When("the C semantic process writes beyond its managed stderr limit")
    fun cSemanticProcessStderrOverflow() {
        val workspace = requireNotNull(root)
        val records = requireNotNull(recordDirectory)
        val manager = ExternalSemanticProcessManager()
        val fake = records.resolve("fake-clangd.sh").toAbsolutePath().normalize()
        val process = manager.launch(SemanticProcessSpec(
            id = "c02-stderr-overflow",
            executable = fake,
            workingDirectory = workspace,
            limits = SemanticProcessLimits(maxStdoutBytes = 4_096, maxStderrBytes = 1_024, gracefulShutdownMillis = 200),
        ))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!process.stderrTruncated() && process.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
        stderrOverflowObserved = process.stderrTruncated()
        capturedStderrBytes = process.stderrText().toByteArray().size
        val recordsDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while ((!Files.isRegularFile(records.resolve("parent.pid")) || !Files.isRegularFile(records.resolve("child.pid"))) &&
            System.nanoTime() < recordsDeadline) Thread.sleep(10)
        pid = records.resolve("parent.pid").takeIf(Files::isRegularFile)
            ?.let { Files.readString(it).trim().toLongOrNull() }
        childPid = records.resolve("child.pid").takeIf(Files::isRegularFile)
            ?.let { Files.readString(it).trim().toLongOrNull() }
        val processIds = listOfNotNull(pid, childPid)
        processTreeClosedBeforeManagerShutdown = processIds.size == 2 && awaitProcessExit(processIds)
        val managerDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (manager.activeProvenance().isNotEmpty() && System.nanoTime() < managerDeadline) Thread.sleep(10)
        processManagerEmptyAfterStderrOverflow = manager.activeProvenance().isEmpty()
        process.close()
        manager.close()
    }

    @Then("stderr overflow truncates diagnostics and reaps the complete process tree")
    fun cSemanticProcessStderrOverflowTruncatesAndClosesTree() {
        assertEquals(true, stderrOverflowObserved, "stderr beyond the managed budget must be recorded as truncated")
        assertTrue((capturedStderrBytes ?: Int.MAX_VALUE) <= 1_024,
            "captured stderr must stay within its configured budget: $capturedStderrBytes bytes")
        assertNotNull(pid, "overflowing semantic process did not record its process ID")
        assertNotNull(childPid, "overflowing semantic process did not start its child")
        assertEquals(true, processTreeClosedBeforeManagerShutdown,
            "stderr overflow must reap the managed process and child")
        assertEquals(true, processManagerEmptyAfterStderrOverflow,
            "stderr overflow must clear process-manager provenance")
    }

    @When("the configured reference clangd is asked for the C function definition")
    fun queryDefinition() {
        val workspace = requireNotNull(root)
        ExternalSemanticProcessManager().use { manager ->
            ClangdSemanticClient(toolchain, manager).use { client ->
                launchFailure = runCatching { client.start(workspace) }.exceptionOrNull()
                if (launchFailure == null) {
                    pid = assertNotNull(client.processProvenance()).pid
                    val arguments = Files.readString(Path.of("/proc/$pid/cmdline")).split('\u0000')
                    val joined = arguments.firstOrNull { it.startsWith("--compile-commands-dir=") }
                    val directory = joined?.substringAfter('=') ?: arguments.indexOf("--compile-commands-dir")
                        .takeIf { it >= 0 }?.let { arguments.getOrNull(it + 1) }
                    privateDatabase = directory?.let(Path::of)?.resolve("compile_commands.json")
                    admittedDatabase = privateDatabase?.takeIf(Files::isRegularFile)?.let(Files::readString)
                    val source = workspace.resolve("main.c")
                    assertTrue(client.didOpen(source, Files.readString(source)))
                    definition = client.definition(source, 5, 24)
                }
            }
            assertTrue(manager.activeProvenance().isEmpty())
        }
    }

    @Then("semantic launch is {string} with the captured C context")
    fun admittedOrRefused(disposition: String) {
        val workspace = requireNotNull(root)
        when (disposition) {
            "admitted" -> {
                assertNull(launchFailure)
                val found = assertIs<CClangdSemanticResult.Found>(definition)
                assertEquals(workspace.resolve("main.c"), found.definition.file)
                assertEquals(1, found.definition.startLine)
                assertEquals(4, found.definition.startCharacter)
                val database = assertNotNull(privateDatabase)
                assertFalse(database.startsWith(workspace), "clangd must consume an engine-owned database")
                assertNotEquals(workspace.resolve("compile_commands.json"), database)
                val captured = assertNotNull(admittedDatabase)
                assertTrue(captured.contains(toolchain.clangExecutable.toString()))
                assertFalse(captured.contains("project-compiler"))
                assertTrue(captured.contains("-std=c17") && captured.contains("-DENABLED=1"))
            }
            "refused" -> {
                assertNotNull(launchFailure, "unsupported project flags must refuse before clangd launch")
                assertTrue(launchFailure?.message.orEmpty().contains("c.clangdConfigurationRefused"), launchFailure.toString())
                assertNull(pid, "a refused configuration must never launch a semantic process")
                assertNull(definition)
            }
            else -> error("Unexpected disposition: $disposition")
        }
    }

    @Then("the query preserves project inputs and reaps every owned process")
    fun noProjectEffects() {
        val workspace = requireNotNull(root)
        assertEquals(originals, projectFiles(workspace))
        assertFalse(Files.exists(workspace.resolve("command-ran")))
        assertFalse(Files.exists(workspace.resolve("main.o")))
        assertFalse(Files.exists(workspace.resolve(".refactorkit")))
        listOfNotNull(pid, childPid, restartedPid).forEach { processId ->
            assertFalse(ProcessHandle.of(processId).map { process -> process.isAlive }.orElse(false),
                "managed process $processId is still alive")
        }
        privateDatabase?.takeUnless { it.startsWith(workspace) }?.let {
            assertFalse(Files.exists(it.parent), "private launch material must be removed after closing clangd")
        }
    }

    private fun projectFiles(workspace: Path): Map<Path, String> = Files.walk(workspace).use { paths ->
        paths.filter(Files::isRegularFile).toList().associate { workspace.relativize(it) to Files.readString(it) }
    }

    @After
    fun cleanup() {
        listOfNotNull(root, recordDirectory).forEach { directory ->
            if (Files.exists(directory)) Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun awaitProcessExit(processIds: List<Long>): Boolean {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (processIds.any { ProcessHandle.of(it).map { process -> process.isAlive }.orElse(false) } && System.nanoTime() < deadline) {
            Thread.sleep(25)
        }
        return processIds.none { ProcessHandle.of(it).map { process -> process.isAlive }.orElse(false) }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun respondingClangdScript(pidFile: Path): String = """
        #!/usr/bin/python3
        import json, os, sys
        from pathlib import Path
        Path(${JsonPrimitive(pidFile.toString())}).write_text(str(os.getpid()))
        while True:
            content_length = None
            while True:
                line = sys.stdin.buffer.readline()
                if not line:
                    sys.exit(0)
                if line in (bytes([13, 10]), bytes([10])):
                    break
                if line.lower().startswith(b'content-length:'):
                    content_length = int(line.split(b':', 1)[1].strip())
            if content_length is None:
                sys.exit(1)
            request = json.loads(sys.stdin.buffer.read(content_length).decode('utf-8'))
            if 'id' in request:
                response = {'jsonrpc': '2.0', 'id': request['id'], 'result': {'capabilities': {}}}
                payload = json.dumps(response, separators=(',', ':')).encode('utf-8')
                header = b'Content-Length: ' + str(len(payload)).encode('ascii') + bytes([13, 10, 13, 10])
                sys.stdout.buffer.write(header + payload)
                sys.stdout.buffer.flush()
    """.trimIndent() + "\n"
}
