package org.refactorkit.c

import java.nio.file.Path

/**
 * Discovers the exact integration-test toolchain selected by Gradle. A configured
 * qualification bundle is mandatory and fails loudly when unavailable; an
 * unsupported system fallback returns null so legacy tests do not invent pinned
 * version/resource metadata for whatever happens to be installed.
 */
internal fun capturedClangTestToolchainOrNull(): ClangSemanticToolchain? {
    val configured = System.getProperty("refactorkit.c.qualificationToolchain")?.let(Path::of)
    val executables = configured?.let { root ->
        listOf(root.resolve("usr/bin/clang-22"), root.resolve("usr/bin/clangd"), root.resolve("usr/bin/clang-format"))
    } ?: listOf(Path.of("/usr/bin/clang"), Path.of("/usr/bin/clangd"), Path.of("/usr/bin/clang-format"))
    if (executables.any { !java.nio.file.Files.isExecutable(it) }) {
        if (configured != null) error("Explicit Clang qualification toolchain is incomplete: $executables")
        return null
    }
    val request = ClangToolchainRequest(
        Path.of(System.getProperty("java.io.tmpdir")),
        executables[0],
        executables[1],
        executables[2],
    )
    return when (val discovery = ClangToolchainDiscoverer().discover(request)) {
        is ClangToolchainDiscovery.Available -> discovery.toolchain
        is ClangToolchainDiscovery.Refused -> if (configured != null) {
            error("Explicit Clang qualification toolchain is unavailable: ${discovery.diagnostics}")
        } else null
    }
}

/** Retained inputs for existing test ports; no version or production qualification is inferred. */
internal fun ClangSemanticToolchain.withCapturedTestResources(ownedRoot: Path? = null): ClangSemanticToolchain {
    val root = ownedRoot ?: Path.of(ManagedClangResourceDirProbe().probe(clangExecutable).getOrThrow())
    return copy(provenance = provenance.copy(resourceDir = root.toString()),
        resources = ClangResourceDirectoryCapture().capture(root))
}
