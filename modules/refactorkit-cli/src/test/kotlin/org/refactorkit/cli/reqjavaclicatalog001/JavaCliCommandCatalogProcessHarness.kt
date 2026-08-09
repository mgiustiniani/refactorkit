package org.refactorkit.cli.reqjavaclicatalog001

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.refactorkit.cli.RefactorKitCli
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.security.Permission
import java.util.Base64
import kotlin.system.exitProcess

/** Runs the CATALOG-001 CLI boundary tripwires in an explicitly configured child JDK 21 JVM. */
internal object JavaCliCommandCatalogProcessHarness {
    private const val HARNESS_PROTOCOL = "refactorkit.test.catalog-v1-guard/v1"
    private const val PROBE_MODE = "probe"
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
        var scannerTripwireArmed = false
        var semanticSessionAttempts = 0
        var cliExitCode = -1
        lateinit var mode: String
        lateinit var manager: CatalogV1BoundarySecurityManager

        try {
            require(arguments.size >= 3) { "expected mode, repository root, and installed root" }
            mode = arguments[0]
            require(mode == PROBE_MODE || mode == INVOKE_MODE) { "unsupported harness mode: $mode" }
            val repositoryRoot = Path.of(arguments[1]).toAbsolutePath().normalize()
            val installedRoot = Path.of(arguments[2]).toAbsolutePath().normalize()
            manager = CatalogV1BoundarySecurityManager(repositoryRoot, installedRoot)
            manager.checkRead(repositoryRoot.resolve("build/catalog-v1-guard-warmup").toString())
            probeInstalledBoundary(manager, installedRoot.resolve("bin/refactorkit"))
            manager.violations.clear()

            val cli = if (mode == INVOKE_MODE) {
                RefactorKitCli(
                    semanticSessionFactory = {
                        semanticSessionAttempts++
                        error("workspace/session tripwire: catalogue discovery must not create a semantic session")
                    },
                ).also { candidate ->
                    val scannerField = RefactorKitCli::class.java.getDeclaredField("scanner")
                    check(scannerField.trySetAccessible())
                    scannerField.set(candidate, null)
                    scannerTripwireArmed = scannerField.get(candidate) == null
                    check(scannerTripwireArmed) { "scanner reflection tripwire was not armed" }
                }
            } else {
                null
            }

            System.setErr(PrintStream(managerDiagnostics, true, Charsets.UTF_8.name()))
            System.setSecurityManager(manager)
            managerInstalled = System.getSecurityManager() === manager
            check(managerInstalled) { "SecurityManager tripwire was not installed in the child JVM" }

            if (mode == PROBE_MODE) {
                probeInstalledBoundary(manager, installedRoot.resolve("bin/refactorkit"))
            } else {
                System.setOut(PrintStream(cliStdout, true, Charsets.UTF_8.name()))
                System.setErr(PrintStream(cliStderr, true, Charsets.UTF_8.name()))
                cliExitCode = checkNotNull(cli).run(arguments.drop(3))
                System.out.flush()
                System.err.flush()
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
            put("scannerTripwireArmed", scannerTripwireArmed)
            put("semanticSessionAttempts", semanticSessionAttempts)
            put("cliExitCode", cliExitCode)
            put("stdoutBase64", Base64.getEncoder().encodeToString(cliStdout.toByteArray()))
            put("stderrBase64", Base64.getEncoder().encodeToString(cliStderr.toByteArray()))
            put("managerDiagnosticsBase64", Base64.getEncoder().encodeToString(managerDiagnostics.toByteArray()))
            put("violations", JsonArray(manager.violations.map(::JsonPrimitive)))
        }
        originalOut.print(response.toString())
        originalOut.flush()
    }

    private fun probeInstalledBoundary(manager: CatalogV1BoundarySecurityManager, installedExecutable: Path) {
        assertBlocked { manager.checkExec(installedExecutable.toString()) }
        assertBlocked { manager.checkRead(installedExecutable.toString()) }
        assertBlocked { manager.checkWrite(installedExecutable.toString()) }
        assertBlocked { manager.checkDelete(installedExecutable.toString()) }
    }

    private fun assertBlocked(action: () -> Unit) {
        try {
            action()
            error("tripwire action was not blocked")
        } catch (_: SecurityException) {
            // Expected: this probes the same fail-closed checks installed around each CLI invocation.
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
internal class CatalogV1BoundarySecurityManager(
    private val repositoryRoot: Path,
    private val installedRoot: Path,
) : SecurityManager() {
    val violations = mutableListOf<String>()

    override fun checkPermission(permission: Permission?) = Unit
    override fun checkPermission(permission: Permission?, context: Any?) = Unit

    override fun checkRead(file: String?) {
        val path = normalized(file) ?: return
        if (path.startsWith(installedRoot) ||
            (path.startsWith(repositoryRoot) && !isBuildInfrastructure(path))
        ) {
            block("read", path, "workspace or installed-runtime read")
        }
    }

    override fun checkWrite(file: String?) {
        block("write", normalized(file), "filesystem write")
    }

    override fun checkDelete(file: String?) {
        block("delete", normalized(file), "filesystem delete")
    }

    override fun checkExec(command: String?) {
        val normalized = normalized(command)
        val display = normalized?.toString() ?: command.orEmpty()
        violations += "exec:$display"
        throw SecurityException("process execution is forbidden during source-built catalogue discovery: $display")
    }

    private fun isBuildInfrastructure(path: Path): Boolean {
        val relative = repositoryRoot.relativize(path)
        return relative.any { segment -> segment.toString() in setOf("build", ".gradle") }
    }

    private fun normalized(raw: String?): Path? = raw?.let { value ->
        runCatching { Path.of(value).toAbsolutePath().normalize() }.getOrNull()
    }

    private fun block(kind: String, path: Path?, description: String): Nothing {
        val display = path?.toString().orEmpty()
        violations += "$kind:$display"
        throw SecurityException("$description is forbidden during source-built catalogue discovery: $display")
    }
}
