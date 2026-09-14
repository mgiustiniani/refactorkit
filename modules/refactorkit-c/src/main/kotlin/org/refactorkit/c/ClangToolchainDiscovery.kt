package org.refactorkit.c

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.SemanticProcessLimits
import org.refactorkit.core.SemanticProcessSpec
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

/** Bounded policy for an explicitly configured Clang toolchain. */
data class ClangToolchainDiscoveryPolicy(
    val allowPathToolDiscovery: Boolean = false,
    val allowWorkspaceLocalToolchain: Boolean = false,
    val minimumClangMajor: Int = 20,
    val maximumClangMajor: Int = 24,
    val maxExecutableBytes: Long = DEFAULT_MAX_EXECUTABLE_BYTES,
) {
    init {
        require(minimumClangMajor in 1..100 && maximumClangMajor >= minimumClangMajor) {
            "Clang major version range is outside the safe bound"
        }
        require(maxExecutableBytes in 1..DEFAULT_MAX_EXECUTABLE_BYTES) {
            "Clang executable size bound is outside the safe range"
        }
    }

    companion object {
        const val DEFAULT_MAX_EXECUTABLE_BYTES = 512L * 1024L * 1024L
    }
}

data class ClangToolchainRequest(
    val workspaceRoot: Path,
    val clangExecutable: Path? = null,
    val clangdExecutable: Path? = null,
    val clangFormatExecutable: Path? = null,
)

data class ClangToolchainFileEvidence(
    val role: String,
    val path: Path,
    val sha256: String,
    val size: Long,
)

data class ClangToolchainProvenance(
    val providerId: String = PROVIDER_ID,
    val clangVersion: String,
    val clangdVersion: String,
    val clangFormatVersion: String,
    val targetTriple: String?,
    val resourceDir: String?,
    val evidence: List<ClangToolchainFileEvidence>,
) {
    /** Immutable identity that invalidates any prior snapshot authority on change. */
    fun toolchainIdentity(): String = buildString {
        append(providerId).append('|')
        append(clangVersion).append('|')
        append(clangdVersion).append('|')
        append(clangFormatVersion).append('|')
        append(targetTriple ?: "-").append('|')
        append(resourceDir ?: "-").append('|')
        evidence.sortedBy(ClangToolchainFileEvidence::role).forEach { append(it.sha256).append('|') }
    }.let { hash(it) }

    companion object {
        const val PROVIDER_ID = "clang-explicit-v1"

        private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

data class ClangSemanticToolchain(
    val clangExecutable: Path,
    val clangdExecutable: Path,
    val clangFormatExecutable: Path,
    val provenance: ClangToolchainProvenance,
)

sealed interface ClangToolchainDiscovery {
    data class Available(val toolchain: ClangSemanticToolchain) : ClangToolchainDiscovery
    data class Refused(val diagnostics: List<Diagnostic>) : ClangToolchainDiscovery
}

/** Executes only the explicitly selected executable with the constant `--version` argument. */
fun interface ClangVersionProbe {
    fun probe(executable: Path): Result<String>
}

/** Executes only the explicitly selected clang with the constant `-print-resource-dir` argument. */
fun interface ClangResourceDirProbe {
    fun probe(executable: Path): Result<String>
}

/** Bounded resource-dir probe for clang using the shared process manager. */
class ManagedClangResourceDirProbe(
    private val singleAttempt: ClangResourceDirProbe = ClangResourceDirProbe(::probeOnce),
) : ClangResourceDirProbe {
    override fun probe(executable: Path): Result<String> {
        var lastFailure: Throwable? = null
        repeat(MAX_PROBE_ATTEMPTS) {
            val result = singleAttempt.probe(executable)
            if (result.isSuccess) return result
            lastFailure = result.exceptionOrNull()
        }
        return Result.failure(lastFailure ?: IllegalStateException("Clang resource-dir probe failed"))
    }

    companion object {
        const val PROBE_TIMEOUT_MILLIS = 2_000L
        const val MAX_PROBE_ATTEMPTS = 2

        private fun probeOnce(executable: Path): Result<String> = runCatching {
            val working = Files.createTempDirectory("refactorkit-clang-resource-")
            try {
                ExternalSemanticProcessManager(maxProcesses = 1).use { manager ->
                    val process = manager.launch(SemanticProcessSpec(
                        id = "clang-resource-dir",
                        executable = executable,
                        arguments = listOf("-print-resource-dir"),
                        workingDirectory = working,
                        limits = SemanticProcessLimits(
                            maxStdoutBytes = 4_096,
                            maxStderrBytes = 4_096,
                            gracefulShutdownMillis = 250,
                        ),
                    ))
                    if (!process.awaitExit(PROBE_TIMEOUT_MILLIS)) {
                        process.cancel()
                        error("Clang resource-dir probe timed out")
                    }
                    val output = process.output.bufferedReader(Charsets.UTF_8).readText().trim()
                    val error = process.stderrText().trim()
                    check(process.exitCode == 0) {
                        "Clang resource-dir probe failed (exit=${process.exitCode})${error.takeIf(String::isNotEmpty)?.let { ": $it" } ?: ""}"
                    }
                    check(output.toByteArray(Charsets.UTF_8).size <= 4_096) { "Clang resource-dir output exceeds limit" }
                    output
                }
            } finally {
                runCatching { Files.deleteIfExists(working) }
            }
        }
    }
}

/** Bounded version probe for one Clang-family tool using the shared process manager. */
class ManagedClangVersionProbe(
    private val singleAttempt: ClangVersionProbe = ClangVersionProbe(::probeOnce),
) : ClangVersionProbe {
    override fun probe(executable: Path): Result<String> {
        var lastFailure: Throwable? = null
        repeat(MAX_PROBE_ATTEMPTS) {
            val result = singleAttempt.probe(executable)
            if (result.isSuccess) return result
            lastFailure = result.exceptionOrNull()
        }
        return Result.failure(lastFailure ?: IllegalStateException("Clang version probe failed"))
    }

    companion object {
        const val PROBE_TIMEOUT_MILLIS = 2_000L
        const val MAX_PROBE_ATTEMPTS = 2

        private fun probeOnce(executable: Path): Result<String> = runCatching {
            val working = Files.createTempDirectory("refactorkit-clang-probe-")
            try {
                ExternalSemanticProcessManager(maxProcesses = 1).use { manager ->
                    val process = manager.launch(SemanticProcessSpec(
                        id = "clang-version",
                        executable = executable,
                        arguments = listOf("--version"),
                        workingDirectory = working,
                        limits = SemanticProcessLimits(
                            maxStdoutBytes = 4_096,
                            maxStderrBytes = 4_096,
                            gracefulShutdownMillis = 250,
                        ),
                    ))
                    if (!process.awaitExit(PROBE_TIMEOUT_MILLIS)) {
                        process.cancel()
                        error("Clang version probe timed out")
                    }
                    val output = process.output.bufferedReader(Charsets.UTF_8).readText().trim()
                    val error = process.stderrText().trim()
                    check(process.exitCode == 0) {
                        "Clang version probe failed (exit=${process.exitCode})${error.takeIf(String::isNotEmpty)?.let { ": $it" } ?: ""}"
                    }
                    check(output.toByteArray(Charsets.UTF_8).size <= 4_096) { "Clang version output exceeds limit" }
                    output
                }
            } finally {
                runCatching { Files.deleteIfExists(working) }
            }
        }
    }
}

/**
 * Discovers an explicitly configured Clang toolchain without executing project
 * build code, compiler plugins, wrappers, or query-driver commands.
 */
class ClangToolchainDiscoverer(
    private val policy: ClangToolchainDiscoveryPolicy = ClangToolchainDiscoveryPolicy(),
    private val versionProbe: ClangVersionProbe = ManagedClangVersionProbe(),
    private val resourceDirProbe: ClangResourceDirProbe = ManagedClangResourceDirProbe(),
) {
    fun discover(request: ClangToolchainRequest): ClangToolchainDiscovery {
        val diagnostics = mutableListOf<Diagnostic>()
        val workspace = requireDirectory(request.workspaceRoot, "workspace", diagnostics)
            ?: return ClangToolchainDiscovery.Refused(diagnostics)
        val clang = resolveExecutable(request.clangExecutable, "clang", workspace, diagnostics)
            ?: return ClangToolchainDiscovery.Refused(diagnostics)
        val clangd = resolveExecutable(request.clangdExecutable, "clangd", workspace, diagnostics)
            ?: return ClangToolchainDiscovery.Refused(diagnostics)
        val clangFormat = resolveExecutable(request.clangFormatExecutable, "clang-format", workspace, diagnostics)
            ?: return ClangToolchainDiscovery.Refused(diagnostics)

        val clangRaw = probeRaw(clang, "clang", diagnostics)
            ?: return ClangToolchainDiscovery.Refused(diagnostics)
        val clangVersion = parseVersion(clangRaw)
            ?: run {
                diagnostics += refusal("clang.versionInvalid", "clang version output could not be parsed")
                return ClangToolchainDiscovery.Refused(diagnostics)
            }
        val clangdVersion = probeVersion(clangd, "clangd", diagnostics)
            ?: return ClangToolchainDiscovery.Refused(diagnostics)
        val clangFormatVersion = probeVersion(clangFormat, "clang-format", diagnostics)
            ?: return ClangToolchainDiscovery.Refused(diagnostics)

        val clangMajor = parseMajor(clangVersion)
        if (clangMajor == null || clangMajor !in policy.minimumClangMajor..policy.maximumClangMajor) {
            diagnostics += refusal(
                "clang.versionUnsupported",
                "Clang major version must be ${policy.minimumClangMajor}..${policy.maximumClangMajor}; detected '${bounded(clangVersion)}'",
            )
            return ClangToolchainDiscovery.Refused(diagnostics)
        }
        if (parseMajor(clangdVersion) != clangMajor || parseMajor(clangFormatVersion) != clangMajor) {
            diagnostics += refusal(
                "clang.toolchainMismatch",
                "clangd and clang-format must match the clang major version",
            )
            return ClangToolchainDiscovery.Refused(diagnostics)
        }

        val targetTriple = parseTargetTriple(clangRaw)
        val resourceDir = probeResourceDir(clang, diagnostics)
            ?: return ClangToolchainDiscovery.Refused(diagnostics)

        val evidence = listOf(
            evidence("clang-executable", clang, policy.maxExecutableBytes),
            evidence("clangd-executable", clangd, policy.maxExecutableBytes),
            evidence("clang-format-executable", clangFormat, policy.maxExecutableBytes),
        ).mapNotNull { result ->
            result.getOrElse {
                diagnostics += refusal("clang.toolchainEvidenceUnavailable", it.message ?: "Toolchain evidence unavailable")
                null
            }
        }
        if (diagnostics.isNotEmpty()) return ClangToolchainDiscovery.Refused(diagnostics)

        return ClangToolchainDiscovery.Available(ClangSemanticToolchain(
            clangExecutable = clang,
            clangdExecutable = clangd,
            clangFormatExecutable = clangFormat,
            provenance = ClangToolchainProvenance(
                clangVersion = clangVersion,
                clangdVersion = clangdVersion,
                clangFormatVersion = clangFormatVersion,
                targetTriple = targetTriple,
                resourceDir = resourceDir,
                evidence = evidence.sortedBy(ClangToolchainFileEvidence::role),
            ),
        ))
    }

    private fun resolveExecutable(
        configured: Path?,
        label: String,
        workspace: Path,
        diagnostics: MutableList<Diagnostic>,
    ): Path? {
        if (configured != null) {
            val executable = requireFile(configured, label, diagnostics, policy.maxExecutableBytes) ?: return null
            if (executable.startsWith(workspace) && !policy.allowWorkspaceLocalToolchain) {
                diagnostics += refusal("clang.workspaceToolchainRefused", "$label executable is workspace-local and requires explicit policy")
                return null
            }
            if (!isWindows() && !Files.isExecutable(executable)) {
                diagnostics += refusal("clang.toolchainPathInvalid", "$label executable is not executable")
                return null
            }
            return executable
        }
        if (!policy.allowPathToolDiscovery) {
            diagnostics += refusal("clang.toolchainNotConfigured", "Explicit $label executable is required")
            return null
        }
        val names = if (isWindows()) listOf("$label.exe", "$label.cmd", "$label.bat") else listOf(label)
        val resolved = System.getenv("PATH").orEmpty().split(System.getProperty("path.separator"))
            .asSequence().filter(String::isNotBlank)
            .flatMap { directory -> names.asSequence().map { Path.of(directory).resolve(it) } }
            .firstOrNull { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
        if (resolved == null) {
            diagnostics += refusal("clang.toolchainMissing", "$label executable was not found on explicit PATH policy")
        }
        return resolved?.let { requireFile(it, label, diagnostics, policy.maxExecutableBytes) }
            ?.takeIf {
                when {
                    it.startsWith(workspace) && !policy.allowWorkspaceLocalToolchain -> {
                        diagnostics += refusal("clang.workspaceToolchainRefused", "PATH resolved a workspace-local $label executable")
                        false
                    }
                    !isWindows() && !Files.isExecutable(it) -> {
                        diagnostics += refusal("clang.toolchainPathInvalid", "$label executable is not executable")
                        false
                    }
                    else -> true
                }
            }
    }

    private fun probeRaw(executable: Path, label: String, diagnostics: MutableList<Diagnostic>): String? {
        val raw = versionProbe.probe(executable).getOrElse {
            diagnostics += refusal("clang.versionProbeFailed", it.message ?: "$label version probe failed")
            return null
        }
        if (raw.isBlank()) {
            diagnostics += refusal("clang.versionInvalid", "$label version output was empty")
            return null
        }
        return raw
    }

    private fun probeVersion(executable: Path, label: String, diagnostics: MutableList<Diagnostic>): String? {
        val raw = probeRaw(executable, label, diagnostics) ?: return null
        val version = parseVersion(raw)
        if (version == null) {
            diagnostics += refusal("clang.versionInvalid", "$label version output could not be parsed")
            return null
        }
        return version
    }

    private fun probeResourceDir(executable: Path, diagnostics: MutableList<Diagnostic>): String? {
        val raw = resourceDirProbe.probe(executable).getOrElse {
            diagnostics += refusal("clang.resourceDirProbeFailed", it.message ?: "clang resource-dir probe failed")
            return null
        }
        return raw.takeIf(String::isNotBlank)
    }

    private fun requireDirectory(path: Path, label: String, diagnostics: MutableList<Diagnostic>): Path? {
        val normalized = path.toAbsolutePath().normalize()
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) {
            diagnostics += refusal("clang.toolchainPathInvalid", "$label must be an existing non-symlink directory")
            return null
        }
        val real = runCatching { normalized.toRealPath(LinkOption.NOFOLLOW_LINKS) }.getOrNull()
        if (real == null) {
            diagnostics += refusal("clang.toolchainPathInvalid", "$label cannot be canonicalized")
            return null
        }
        return real
    }

    private fun requireFile(path: Path, label: String, diagnostics: MutableList<Diagnostic>, maxBytes: Long): Path? {
        val normalized = path.toAbsolutePath().normalize()
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) {
            diagnostics += refusal("clang.toolchainPathInvalid", "$label must be an existing non-symlink file")
            return null
        }
        val real = runCatching { normalized.toRealPath(LinkOption.NOFOLLOW_LINKS) }.getOrNull()
        val size = real?.let { runCatching { Files.size(it) }.getOrNull() }
        if (real == null || size == null || size !in 1..maxBytes) {
            diagnostics += refusal("clang.toolchainFileLimit", "$label size is outside the bounded range")
            return null
        }
        return real
    }

    private fun evidence(role: String, path: Path, maxBytes: Long): Result<ClangToolchainFileEvidence> = runCatching {
        val size = Files.size(path)
        check(size in 1..maxBytes) { "$role size is outside the bounded range" }
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                check(total <= maxBytes) { "$role changed or exceeded its evidence limit" }
                digest.update(buffer, 0, count)
            }
        }
        ClangToolchainFileEvidence(role, path, digest.digest().joinToString("") { "%02x".format(it) }, size)
    }

    private fun refusal(code: String, message: String) = Diagnostic(
        message = message,
        severity = Diagnostic.Severity.ERROR,
        code = code,
        evidence = DiagnosticEvidence.STRUCTURAL,
        category = DiagnosticCategory.SAFETY,
    )

    private fun bounded(value: String): String = value.take(128)
    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows")
}

/** Parses a Clang-family version from a `--version` output line, e.g. `clang version 22.1.8`. */
internal fun parseVersion(output: String): String? {
    val match = VERSION_PATTERN.find(output)
    return match?.groupValues?.get(1)
}

/** Parses the major component of a semantic version like `22.1.8`. */
internal fun parseMajor(version: String): Int? {
    val match = MAJOR_PATTERN.matchEntire(version.trim()) ?: return null
    return match.groupValues[1].toIntOrNull()
}

/** Parses the target triple from a `clang --version` output, e.g. `Target: x86_64-pc-linux-gnu`. */
internal fun parseTargetTriple(output: String): String? {
    val match = TARGET_PATTERN.find(output)
    return match?.groupValues?.get(1)
}

private val VERSION_PATTERN = Regex("""(?:clang|clangd|clang-format)\s+version\s+(\d+\.\d+\.\d+)""")
private val MAJOR_PATTERN = Regex("""(\d+)\.\d+\.\d+""")
private val TARGET_PATTERN = Regex("""Target:\s+(\S+)""")
