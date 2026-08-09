package org.refactorkit.cli.reqjavacliresult001

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.refactorkit.cli.RefactorKitCli
import org.refactorkit.java.JavaProjectScanner
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.security.Permission
import java.util.Base64
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.system.exitProcess

/** Runs RESULT-001 scan and preview boundaries in an explicitly configured child JDK 21 JVM. */
internal object JavaCliOperationResultProcessHarness {
    private const val HARNESS_PROTOCOL = "refactorkit.test.cli-result-preview-guard/v1"
    private const val PROBE_MODE = "probe"
    private const val SCAN_MODE = "scan"
    private const val INVOKE_MODE = "invoke"
    private const val HARNESS_FAILURE_EXIT = 125

    @JvmStatic
    @Suppress("DEPRECATION")
    fun main(arguments: Array<String>) {
        val originalOut = System.out
        val originalErr = System.err
        val originalSecurityManager = System.getSecurityManager()
        val managerDiagnostics = ByteArrayOutputStream()
        val cliStdout = ByteArrayOutputStream()
        val cliStderr = ByteArrayOutputStream()

        var managerInstalled = false
        var cliExitCode = -1
        var snapshotSha256 = ""
        var moduleCount = -1
        lateinit var mode: String
        lateinit var manager: ResultPreviewBoundarySecurityManager

        try {
            require(arguments.size >= 5) {
                "expected mode, repository root, fixture root, installed root, and disposable workspace root"
            }
            mode = arguments[0]
            require(mode == PROBE_MODE || mode == SCAN_MODE || mode == INVOKE_MODE) {
                "unsupported harness mode: $mode"
            }
            val repositoryRoot = Path.of(arguments[1]).toAbsolutePath().normalize()
            val fixtureRoot = Path.of(arguments[2]).toAbsolutePath().normalize()
            val installedRoot = Path.of(arguments[3]).toAbsolutePath().normalize()
            val workspaceRoot = Path.of(arguments[4]).toAbsolutePath().normalize()
            manager = ResultPreviewBoundarySecurityManager(workspaceRoot, fixtureRoot, installedRoot, repositoryRoot)
            manager.checkRead(repositoryRoot.resolve("modules/refactorkit-cli/build/result-guard-warmup").toString())
            probeBoundary(manager, workspaceRoot, fixtureRoot, installedRoot, repositoryRoot)
            manager.violations.clear()

            val scanner = if (mode == SCAN_MODE || mode == INVOKE_MODE) {
                JavaProjectScanner(localMavenRepository = workspaceRoot.resolve("fixture-repository"))
            } else {
                null
            }
            val cli = if (mode == INVOKE_MODE) RefactorKitCli(scanner = checkNotNull(scanner)) else null

            System.setErr(PrintStream(managerDiagnostics, true, Charsets.UTF_8.name()))
            System.setSecurityManager(manager)
            managerInstalled = System.getSecurityManager() === manager
            check(managerInstalled) { "SecurityManager tripwire was not installed in the child JVM" }

            when (mode) {
                PROBE_MODE -> probeBoundary(manager, workspaceRoot, fixtureRoot, installedRoot, repositoryRoot)
                SCAN_MODE -> {
                    val snapshot = checkNotNull(scanner).scan(workspaceRoot)
                    snapshotSha256 = snapshot.hash
                    moduleCount = snapshot.buildModels.single().modules.size
                }
                INVOKE_MODE -> {
                    System.setOut(PrintStream(cliStdout, true, Charsets.UTF_8.name()))
                    System.setErr(PrintStream(cliStderr, true, Charsets.UTF_8.name()))
                    cliExitCode = checkNotNull(cli).run(arguments.drop(5))
                    System.out.flush()
                    System.err.flush()
                }
            }
        } catch (failure: Throwable) {
            restoreRuntime(originalOut, originalErr, originalSecurityManager)
            failure.printStackTrace(originalErr)
            exitProcess(HARNESS_FAILURE_EXIT)
        }

        restoreRuntime(originalOut, originalErr, originalSecurityManager)
        val response = buildJsonObject {
            put("protocol", HARNESS_PROTOCOL)
            put("mode", mode)
            put("runtimeFeature", Runtime.version().feature())
            put("securityManagerInstalled", managerInstalled)
            put("cliExitCode", cliExitCode)
            put("snapshotSha256", snapshotSha256)
            put("moduleCount", moduleCount)
            put("stdoutBase64", Base64.getEncoder().encodeToString(cliStdout.toByteArray()))
            put("stderrBase64", Base64.getEncoder().encodeToString(cliStderr.toByteArray()))
            put("managerDiagnosticsBase64", Base64.getEncoder().encodeToString(managerDiagnostics.toByteArray()))
            put("violations", JsonArray(manager.violations.map(::JsonPrimitive)))
        }
        originalOut.print(response.toString())
        originalOut.flush()
    }

    private fun probeBoundary(
        manager: ResultPreviewBoundarySecurityManager,
        workspaceRoot: Path,
        fixtureRoot: Path,
        installedRoot: Path,
        repositoryRoot: Path,
    ) {
        assertBlocked { manager.checkExec(installedRoot.resolve("bin/refactorkit").toString()) }
        assertBlocked { manager.checkRead(installedRoot.resolve("bin/refactorkit").toString()) }
        assertBlocked { manager.checkRead(fixtureRoot.resolve("pom.xml").toString()) }
        assertBlocked { manager.checkRead(repositoryRoot.resolve("modules/refactorkit-cli/src/main/kotlin").toString()) }
        assertBlocked { manager.checkRead(workspaceRoot.resolve(".refactorkit/transactions").toString()) }
        assertBlocked { manager.checkWrite(workspaceRoot.resolve(".refactorkit/workspace.lock").toString()) }
        assertBlocked { manager.checkDelete(workspaceRoot.resolve("catalog-model/pom.xml").toString()) }
        assertBlocked { manager.checkConnect("example.invalid", 443) }
    }

    private fun assertBlocked(action: () -> Unit) {
        try {
            action()
            error("tripwire action was not blocked")
        } catch (_: SecurityException) {
            // Expected: this probes the same fail-closed checks installed around the scanner and CLI.
        }
    }

    @Suppress("DEPRECATION")
    private fun restoreRuntime(
        originalOut: PrintStream,
        originalErr: PrintStream,
        originalSecurityManager: SecurityManager?,
    ) {
        System.setOut(originalOut)
        System.setErr(originalErr)
        if (System.getSecurityManager() !== originalSecurityManager) {
            System.setSecurityManager(originalSecurityManager)
        }
    }
}

@Suppress("DEPRECATION")
internal class ResultPreviewBoundarySecurityManager(
    workspaceRoot: Path,
    fixtureRoot: Path,
    private val installedRoot: Path,
    private val repositoryRoot: Path,
) : SecurityManager() {
    private val workspace = workspaceRoot.toAbsolutePath().normalize()
    private val fixture = fixtureRoot.toAbsolutePath().normalize()
    val violations = mutableListOf<String>()

    override fun checkPermission(permission: Permission?) = Unit
    override fun checkPermission(permission: Permission?, context: Any?) = Unit

    override fun checkRead(file: String?) {
        val path = normalized(file) ?: return
        when {
            path.startsWith(installedRoot) -> block("installed-read", path)
            path.startsWith(fixture) -> block("fixture-read", path)
            path.startsWith(workspace.resolve(".refactorkit")) -> block("engine-read", path)
            path.startsWith(repositoryRoot) && !isBuildInfrastructure(path) -> block("repository-read", path)
        }
    }

    override fun checkWrite(file: String?) {
        val path = normalized(file) ?: return
        if (isProtected(path)) block("protected-write", path)
    }

    override fun checkDelete(file: String?) {
        val path = normalized(file) ?: return
        if (isProtected(path)) block("protected-delete", path)
    }

    override fun checkExec(command: String?) = block("exec", normalized(command), command.orEmpty())

    override fun checkConnect(host: String?, port: Int) = block("network", null, "${host.orEmpty()}:$port")

    private fun isBuildInfrastructure(path: Path): Boolean {
        val relative = repositoryRoot.relativize(path).invariantSeparatorsPathString
        return relative.split('/').any { it == "build" || it == ".gradle" }
    }

    private fun isProtected(path: Path): Boolean =
        path.startsWith(workspace) || path.startsWith(fixture) || path.startsWith(installedRoot) ||
            (path.startsWith(repositoryRoot) && !isBuildInfrastructure(path))

    private fun normalized(raw: String?): Path? = raw?.let { value ->
        runCatching { Path.of(value).toAbsolutePath().normalize() }.getOrNull()
    }

    private fun block(kind: String, path: Path?, fallback: String = ""): Nothing {
        val display = path?.toString() ?: fallback
        violations += "$kind:$display"
        throw SecurityException("$kind boundary denied")
    }
}
