package org.refactorkit.c

import org.refactorkit.core.Diagnostic
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Simulated IO for legacy metadata tests with mocked probes. Never native artifact or semantic qualification. */
internal class SimulatedClangRuntimeProvider : ClangToolchainRuntimeProvider {
    override fun capture(clang: Path, clangd: Path, formatter: Path): ClangToolchainRuntimeCaptureResult {
        val bytes = ByteArray(48); "glibc-ld.so.cache1.1".toByteArray().copyInto(bytes); bytes[28] = 2
        val cache = (CGlibcLoaderCache.read(bytes) as CGlibcCacheReadout.Found).facts
        val input = ClangGlibcRuntimeInputCaptureResult.Captured(ClangToolchainFileEvidence("glibc-loader-cache", Path.of("/etc/ld.so.cache"), cache.sha256, 48),
            cache, Path.of("/etc/ld.so.preload"))
        val loaderPath = Path.of("/simulated-metadata-loader")
        val loader = ClangNativePathCaptureResult.Captured(loaderPath, emptyList(), ClangArtifactCaptureResult.Captured(ClangArtifactRole.INTERPRETER,
            ClangToolchainFileEvidence("program-interpreter", loaderPath, "d".repeat(64), 64),
            CElfDynamicInputFacts("d".repeat(64), null, emptyList(), "simulated-loader", null, null, 0, 0, true, CElfObjectType.DYNAMIC)))
        fun supplied(path: Path, role: ClangArtifactRole): ClangGlibcRuntimeResolution.Captured {
            val sha = hash(path)
            val executable = ClangNativePathCaptureResult.Captured(path, emptyList(), ClangArtifactCaptureResult.Captured(role,
                ClangToolchainFileEvidence(role.evidenceRole, path, sha, Files.size(path)),
                CElfDynamicInputFacts(sha, loaderPath.toString(), emptyList(), null, null, null, 0, 0, true, CElfObjectType.DYNAMIC)))
            val graph = (ClangRuntimeManifest.compose(executable, loader, emptyList(), emptyList()) as ClangRuntimeManifestComposition.Composed).manifest
            return ClangGlibcRuntimeResolution.Captured(graph, input, emptyList())
        }
        val facts = (ClangToolchainRuntimeFacts.compose(supplied(clang, ClangArtifactRole.CLANG), supplied(clangd, ClangArtifactRole.CLANGD),
            supplied(formatter, ClangArtifactRole.FORMATTER)) as ClangToolchainRuntimeComposition.Composed).facts
        return ClangToolchainRuntimeCaptureResult.Captured(facts)
    }

    override fun checkCurrent(captured: ClangToolchainRuntimeCaptureResult.Captured): ClangToolchainRuntimeCurrency =
        if (captured.facts.runtimes.all { runtime ->
                val record = runtime.manifest.executable.artifact.evidence
                Files.isRegularFile(record.path) && Files.size(record.path) == record.size && hash(record.path) == record.sha256
            }) ClangToolchainRuntimeCurrency.Current
        else ClangToolchainRuntimeCurrency.NotCurrent(Diagnostic("Simulated metadata fixture changed", Diagnostic.Severity.ERROR,
            code = "test.simulatedRuntime.changed"))

    private fun hash(path: Path) = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
}
