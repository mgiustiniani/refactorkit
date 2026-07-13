package org.refactorkit.typescript

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
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

data class TypeScriptToolchainDiscoveryPolicy(
    val allowPathNodeDiscovery: Boolean = false,
    val allowWorkspaceLocalToolchain: Boolean = false,
    val minimumNodeMajor: Int = 18,
    val maximumNodeMajor: Int = 24,
    val minimumTypeScriptMajor: Int = 5,
    val maximumTypeScriptMajor: Int = 5,
) {
    init {
        require(minimumNodeMajor in 1..100 && maximumNodeMajor >= minimumNodeMajor)
        require(minimumTypeScriptMajor in 1..100 && maximumTypeScriptMajor >= minimumTypeScriptMajor)
    }
}

data class TypeScriptToolchainRequest(
    val workspaceRoot: Path,
    val nodeExecutable: Path? = null,
    val languageServerPackageRoot: Path? = null,
    val typeScriptPackageRoot: Path? = null,
)

data class ToolchainFileEvidence(
    val role: String,
    val path: Path,
    val sha256: String,
    val size: Long,
)

data class TypeScriptToolchainProvenance(
    val providerId: String = PROVIDER_ID,
    val nodeVersion: String,
    val languageServerVersion: String,
    val typeScriptVersion: String,
    val evidence: List<ToolchainFileEvidence>,
) {
    companion object {
        const val PROVIDER_ID = "typescript-lsp-explicit-v1"
    }
}

data class TypeScriptSemanticToolchain(
    val nodeExecutable: Path,
    val languageServerEntrypoint: Path,
    val typeScriptServerEntrypoint: Path,
    val command: List<String>,
    val provenance: TypeScriptToolchainProvenance,
    val typeScriptCompilerEntrypoint: Path = typeScriptServerEntrypoint.resolveSibling("typescript.js"),
)

sealed interface TypeScriptToolchainDiscovery {
    data class Available(val toolchain: TypeScriptSemanticToolchain) : TypeScriptToolchainDiscovery
    data class Refused(val diagnostics: List<Diagnostic>) : TypeScriptToolchainDiscovery
}

fun interface NodeVersionProbe {
    fun probe(executable: Path): Result<String>
}

/** Executes only the explicitly selected Node binary with the constant `--version` argument. */
class ManagedNodeVersionProbe : NodeVersionProbe {
    override fun probe(executable: Path): Result<String> = runCatching {
        val working = Files.createTempDirectory("refactorkit-node-probe-")
        try {
            ExternalSemanticProcessManager(maxProcesses = 1).use { manager ->
                val process = manager.launch(SemanticProcessSpec(
                    id = "node-version",
                    executable = executable,
                    arguments = listOf("--version"),
                    workingDirectory = working,
                    limits = SemanticProcessLimits(
                        maxStdoutBytes = 1_024,
                        maxStderrBytes = 4_096,
                        gracefulShutdownMillis = 250,
                    ),
                ))
                if (!process.awaitExit(NODE_PROBE_TIMEOUT_MILLIS)) {
                    process.cancel()
                    error("Node version probe timed out")
                }
                val output = process.output.bufferedReader(Charsets.UTF_8).readText().trim()
                val error = process.stderrText().trim()
                check(process.exitCode == 0) { "Node version probe failed${error.takeIf(String::isNotEmpty)?.let { ": $it" } ?: ""}" }
                check(output.toByteArray(Charsets.UTF_8).size <= 1_024) { "Node version output exceeds limit" }
                output
            }
        } finally {
            runCatching { Files.deleteIfExists(working) }
        }
    }

    companion object {
        const val NODE_PROBE_TIMEOUT_MILLIS = 2_000L
    }
}

/**
 * Discovers an explicitly configured TypeScript LSP toolchain without executing
 * package scripts, package managers, project code, or language-server code.
 */
class TypeScriptToolchainDiscoverer(
    private val policy: TypeScriptToolchainDiscoveryPolicy = TypeScriptToolchainDiscoveryPolicy(),
    private val nodeVersionProbe: NodeVersionProbe = ManagedNodeVersionProbe(),
) {
    fun discover(request: TypeScriptToolchainRequest): TypeScriptToolchainDiscovery {
        val diagnostics = mutableListOf<Diagnostic>()
        val workspace = requireDirectory(request.workspaceRoot, "workspace", diagnostics, allowWorkspace = true)
            ?: return TypeScriptToolchainDiscovery.Refused(diagnostics)
        val node = resolveNode(request.nodeExecutable, workspace, diagnostics)
            ?: return TypeScriptToolchainDiscovery.Refused(diagnostics)
        val languageServerRoot = request.languageServerPackageRoot?.let {
            requireDirectory(it, "language-server package", diagnostics, workspace = workspace)
        } ?: run {
            diagnostics += refusal("typescript.toolchainNotConfigured", "Explicit languageServerPackageRoot is required")
            null
        }
        val typeScriptRoot = request.typeScriptPackageRoot?.let {
            requireDirectory(it, "TypeScript package", diagnostics, workspace = workspace)
        } ?: run {
            diagnostics += refusal("typescript.toolchainNotConfigured", "Explicit typeScriptPackageRoot is required")
            null
        }
        if (languageServerRoot == null || typeScriptRoot == null) return TypeScriptToolchainDiscovery.Refused(diagnostics)

        val nodeVersionRaw = nodeVersionProbe.probe(node).getOrElse {
            diagnostics += refusal("typescript.nodeProbeFailed", it.message ?: "Node version probe failed")
            return TypeScriptToolchainDiscovery.Refused(diagnostics)
        }
        val nodeVersion = SemanticVersion.parse(nodeVersionRaw.removePrefix("v"))
        if (nodeVersion == null || nodeVersion.major !in policy.minimumNodeMajor..policy.maximumNodeMajor) {
            diagnostics += refusal(
                "typescript.nodeVersionUnsupported",
                "Node version must be ${policy.minimumNodeMajor}..${policy.maximumNodeMajor}; detected '${bounded(nodeVersionRaw)}'",
            )
            return TypeScriptToolchainDiscovery.Refused(diagnostics)
        }

        val languageServerPackage = readPackage(languageServerRoot, EXPECTED_LANGUAGE_SERVER_PACKAGE, diagnostics)
        val typeScriptPackage = readPackage(typeScriptRoot, EXPECTED_TYPESCRIPT_PACKAGE, diagnostics)
        if (languageServerPackage == null || typeScriptPackage == null) return TypeScriptToolchainDiscovery.Refused(diagnostics)
        if (typeScriptPackage.version.major !in policy.minimumTypeScriptMajor..policy.maximumTypeScriptMajor) {
            diagnostics += refusal(
                "typescript.compilerVersionUnsupported",
                "TypeScript major version must be ${policy.minimumTypeScriptMajor}..${policy.maximumTypeScriptMajor}",
            )
            return TypeScriptToolchainDiscovery.Refused(diagnostics)
        }

        val languageServerEntrypoint = resolvePackageFile(
            languageServerRoot,
            languageServerPackage.binEntrypoint,
            "language-server entrypoint",
            diagnostics,
        )
        val typeScriptEntrypoint = resolvePackageFile(
            typeScriptRoot,
            "lib/tsserver.js",
            "TypeScript server entrypoint",
            diagnostics,
        )
        val typeScriptCompilerEntrypoint = resolvePackageFile(
            typeScriptRoot,
            "lib/typescript.js",
            "TypeScript compiler API entrypoint",
            diagnostics,
        )
        if (languageServerEntrypoint == null || typeScriptEntrypoint == null || typeScriptCompilerEntrypoint == null) {
            return TypeScriptToolchainDiscovery.Refused(diagnostics)
        }

        val evidence = listOf(
            evidence("node-executable", node, MAX_EXECUTABLE_BYTES),
            evidence("language-server-package", languageServerPackage.manifest, MAX_MANIFEST_BYTES),
            evidence("language-server-entrypoint", languageServerEntrypoint, MAX_ENTRYPOINT_BYTES),
            evidence("typescript-package", typeScriptPackage.manifest, MAX_MANIFEST_BYTES),
            evidence("typescript-server-entrypoint", typeScriptEntrypoint, MAX_ENTRYPOINT_BYTES),
            evidence("typescript-compiler-entrypoint", typeScriptCompilerEntrypoint, MAX_ENTRYPOINT_BYTES),
        ).mapNotNull { result ->
            result.getOrElse {
                diagnostics += refusal("typescript.toolchainEvidenceUnavailable", it.message ?: "Toolchain evidence unavailable")
                null
            }
        }
        if (diagnostics.isNotEmpty()) return TypeScriptToolchainDiscovery.Refused(diagnostics)

        return TypeScriptToolchainDiscovery.Available(TypeScriptSemanticToolchain(
            nodeExecutable = node,
            languageServerEntrypoint = languageServerEntrypoint,
            typeScriptServerEntrypoint = typeScriptEntrypoint,
            typeScriptCompilerEntrypoint = typeScriptCompilerEntrypoint,
            command = listOf(
                node.toString(), "--max-old-space-size=$LANGUAGE_SERVER_HEAP_MIB",
                languageServerEntrypoint.toString(), "--stdio",
            ),
            provenance = TypeScriptToolchainProvenance(
                nodeVersion = nodeVersion.toString(),
                languageServerVersion = languageServerPackage.version.toString(),
                typeScriptVersion = typeScriptPackage.version.toString(),
                evidence = evidence.sortedBy(ToolchainFileEvidence::role),
            ),
        ))
    }

    private fun resolveNode(configured: Path?, workspace: Path, diagnostics: MutableList<Diagnostic>): Path? {
        if (configured != null) {
            val node = requireFile(configured, "Node executable", diagnostics, MAX_EXECUTABLE_BYTES) ?: return null
            if (node.startsWith(workspace) && !policy.allowWorkspaceLocalToolchain) {
                diagnostics += refusal("typescript.workspaceToolchainRefused", "Node executable is workspace-local and requires explicit policy")
                return null
            }
            if (!isWindows() && !Files.isExecutable(node)) {
                diagnostics += refusal("typescript.toolchainPathInvalid", "Node executable is not executable")
                return null
            }
            return node
        }
        if (!policy.allowPathNodeDiscovery) {
            diagnostics += refusal("typescript.toolchainNotConfigured", "Explicit nodeExecutable is required")
            return null
        }
        val names = if (isWindows()) listOf("node.exe", "node.cmd", "node.bat") else listOf("node")
        val resolved = System.getenv("PATH").orEmpty().split(System.getProperty("path.separator"))
            .asSequence().filter(String::isNotBlank)
            .flatMap { directory -> names.asSequence().map { Path.of(directory).resolve(it) } }
            .firstOrNull { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
        if (resolved == null) diagnostics += refusal("typescript.nodeMissing", "Node executable was not found on explicit PATH policy")
        return resolved?.let { requireFile(it, "Node executable", diagnostics, MAX_EXECUTABLE_BYTES) }
            ?.takeIf {
                when {
                    it.startsWith(workspace) && !policy.allowWorkspaceLocalToolchain -> {
                        diagnostics += refusal("typescript.workspaceToolchainRefused", "PATH resolved a workspace-local Node executable")
                        false
                    }
                    !isWindows() && !Files.isExecutable(it) -> {
                        diagnostics += refusal("typescript.toolchainPathInvalid", "Node executable is not executable")
                        false
                    }
                    else -> true
                }
            }
    }

    private fun requireDirectory(
        path: Path,
        label: String,
        diagnostics: MutableList<Diagnostic>,
        workspace: Path? = null,
        allowWorkspace: Boolean = false,
    ): Path? {
        val normalized = path.toAbsolutePath().normalize()
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) {
            diagnostics += refusal("typescript.toolchainPathInvalid", "$label must be an existing non-symlink directory")
            return null
        }
        val real = runCatching { normalized.toRealPath(LinkOption.NOFOLLOW_LINKS) }.getOrNull()
        if (real == null) {
            diagnostics += refusal("typescript.toolchainPathInvalid", "$label cannot be canonicalized")
            return null
        }
        if (!allowWorkspace && workspace != null && real.startsWith(workspace) && !policy.allowWorkspaceLocalToolchain) {
            diagnostics += refusal("typescript.workspaceToolchainRefused", "$label is workspace-local and requires explicit policy")
            return null
        }
        return real
    }

    private fun requireFile(path: Path, label: String, diagnostics: MutableList<Diagnostic>, maxBytes: Long): Path? {
        val normalized = path.toAbsolutePath().normalize()
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) {
            diagnostics += refusal("typescript.toolchainPathInvalid", "$label must be an existing non-symlink file")
            return null
        }
        val real = runCatching { normalized.toRealPath(LinkOption.NOFOLLOW_LINKS) }.getOrNull()
        val size = real?.let { runCatching { Files.size(it) }.getOrNull() }
        if (real == null || size == null || size !in 1..maxBytes) {
            diagnostics += refusal("typescript.toolchainFileLimit", "$label size is outside the bounded range")
            return null
        }
        return real
    }

    private fun readPackage(root: Path, expectedName: String, diagnostics: MutableList<Diagnostic>): PackageMetadata? {
        val manifest = resolvePackageFile(root, "package.json", "$expectedName manifest", diagnostics, MAX_MANIFEST_BYTES)
            ?: return null
        val text = runCatching { Files.readString(manifest, Charsets.UTF_8) }.getOrElse {
            diagnostics += refusal("typescript.packageManifestInvalid", "$expectedName package.json cannot be read")
            return null
        }
        val json = runCatching { JSON.parseToJsonElement(text).jsonObject }.getOrElse {
            diagnostics += refusal("typescript.packageManifestInvalid", "$expectedName package.json is invalid JSON")
            return null
        }
        val name = json.string("name")
        val version = json.string("version")?.let(SemanticVersion::parse)
        if (name != expectedName || version == null) {
            diagnostics += refusal("typescript.packageIdentityInvalid", "Expected package '$expectedName' with semantic version")
            return null
        }
        val bin = when (val value = json["bin"]) {
            is JsonPrimitive -> value.takeIf(JsonPrimitive::isString)?.content
            is JsonObject -> (value[EXPECTED_LANGUAGE_SERVER_PACKAGE] as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)?.content
            else -> null
        }
        if (expectedName == EXPECTED_LANGUAGE_SERVER_PACKAGE && bin.isNullOrBlank()) {
            diagnostics += refusal("typescript.packageEntrypointMissing", "Language-server package has no canonical bin entry")
            return null
        }
        return PackageMetadata(manifest, version, bin.orEmpty())
    }

    private fun resolvePackageFile(
        root: Path,
        relativeValue: String,
        label: String,
        diagnostics: MutableList<Diagnostic>,
        maxBytes: Long = MAX_ENTRYPOINT_BYTES,
    ): Path? {
        if (relativeValue.isBlank()) {
            diagnostics += refusal("typescript.packageEntrypointMissing", "$label is missing")
            return null
        }
        val relative = runCatching { Path.of(relativeValue) }.getOrNull()
        if (relative == null || relative.isAbsolute || relative.startsWith("..")) {
            diagnostics += refusal("typescript.packageEntrypointEscape", "$label escapes its package")
            return null
        }
        val candidate = root.resolve(relative).normalize()
        if (!candidate.startsWith(root)) {
            diagnostics += refusal("typescript.packageEntrypointEscape", "$label escapes its package")
            return null
        }
        val file = requireFile(candidate, label, diagnostics, maxBytes) ?: return null
        if (!file.startsWith(root)) {
            diagnostics += refusal("typescript.packageEntrypointEscape", "$label resolves outside its package")
            return null
        }
        return file
    }

    private fun evidence(role: String, path: Path, maxBytes: Long): Result<ToolchainFileEvidence> = runCatching {
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
        ToolchainFileEvidence(role, path, digest.digest().joinToString("") { "%02x".format(it) }, size)
    }

    private fun refusal(code: String, message: String) = Diagnostic(
        message = message,
        severity = Diagnostic.Severity.ERROR,
        code = code,
        evidence = DiagnosticEvidence.STRUCTURAL,
        category = DiagnosticCategory.SAFETY,
    )

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)?.content

    private fun bounded(value: String): String = value.take(128)
    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows")

    private data class PackageMetadata(val manifest: Path, val version: SemanticVersion, val binEntrypoint: String)

    companion object {
        const val LANGUAGE_SERVER_HEAP_MIB = 512
        private val JSON = Json { isLenient = false; ignoreUnknownKeys = true }
        private const val EXPECTED_LANGUAGE_SERVER_PACKAGE = "typescript-language-server"
        private const val EXPECTED_TYPESCRIPT_PACKAGE = "typescript"
        private const val MAX_MANIFEST_BYTES = 65_536L
        private const val MAX_ENTRYPOINT_BYTES = 32L * 1024L * 1024L
        private const val MAX_EXECUTABLE_BYTES = 512L * 1024L * 1024L
    }
}

data class SemanticVersion(val major: Int, val minor: Int, val patch: Int, val suffix: String? = null) {
    override fun toString(): String = "$major.$minor.$patch${suffix?.let { "-$it" }.orEmpty()}"

    companion object {
        private val PATTERN = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z.-]{1,64}))?")
        fun parse(value: String): SemanticVersion? {
            val match = PATTERN.matchEntire(value.trim()) ?: return null
            return SemanticVersion(
                match.groupValues[1].toIntOrNull() ?: return null,
                match.groupValues[2].toIntOrNull() ?: return null,
                match.groupValues[3].toIntOrNull() ?: return null,
                match.groupValues[4].ifBlank { null },
            )
        }
    }
}
