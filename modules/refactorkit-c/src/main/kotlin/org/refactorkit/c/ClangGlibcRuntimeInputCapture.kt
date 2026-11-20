package org.refactorkit.c

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

sealed interface ClangGlibcRuntimeInputCaptureResult {
    class Captured internal constructor(val cacheEvidence: ClangToolchainFileEvidence,
        val cacheFacts: CGlibcLoaderCacheFacts, val preloadPath: Path) : ClangGlibcRuntimeInputCaptureResult
    data class Unavailable(val diagnostic: Diagnostic) : ClangGlibcRuntimeInputCaptureResult
}

sealed interface ClangGlibcRuntimeInputCurrency {
    data object Current : ClangGlibcRuntimeInputCurrency
    data class NotCurrent(val diagnostic: Diagnostic) : ClangGlibcRuntimeInputCurrency
}

/** Read-only cache/absence boundary; selectors alone do not certify the actual host or authorize execution. */
class ClangGlibcRuntimeInputCapture(private val maxCacheBytes: Int = 8 * 1024 * 1024) {
    init { require(maxCacheBytes in 48..8 * 1024 * 1024) }

    fun capture(cacheFile: Path = SYSTEM_CACHE, preloadFile: Path = SYSTEM_PRELOAD): ClangGlibcRuntimeInputCaptureResult = try {
        val cache = directPath(cacheFile); val preload = directPath(preloadFile)
        val parentsBefore = parents(cache)
        val preloadParentsBefore = absent(preload)
        val before = attributes(cache)
        if (!before.isRegularFile || before.isSymbolicLink || before.fileKey() == null) refuse("pathInvalid", "Cache must be a direct regular file with observed identity")
        if (before.size() > maxCacheBytes) refuse("inputLimit", "Cache input exceeds its byte limit")
        val bytes = Files.newInputStream(cache, NOFOLLOW_LINKS).use { it.readNBytes(maxCacheBytes + 1) }
        if (bytes.size > maxCacheBytes) refuse("inputLimit", "Cache input exceeds its byte limit")
        val after = attributes(cache)
        if (stamp(before) != stamp(after) || bytes.size.toLong() != before.size() || parents(cache) != parentsBefore ||
            absent(preload) != preloadParentsBefore) refuse("changed", "Cache or preload path changed during capture")
        when (val readout = CGlibcLoaderCache.read(bytes)) {
            is CGlibcCacheReadout.Refused -> unavailable("cache_${readout.reason.name.lowercase()}", "Cache metadata was refused: ${readout.reason.name}")
            is CGlibcCacheReadout.Found -> {
                if (stamp(attributes(cache)) != stamp(after) || parents(cache) != parentsBefore ||
                    absent(preload) != preloadParentsBefore) refuse("changed", "Runtime data paths changed during parsing")
                ClangGlibcRuntimeInputCaptureResult.Captured(
                    ClangToolchainFileEvidence("glibc-loader-cache", cache, readout.facts.sha256, bytes.size.toLong()), readout.facts, preload)
            }
        }
    } catch (failure: Refused) {
        ClangGlibcRuntimeInputCaptureResult.Unavailable(failure.diagnostic)
    } catch (_: IOException) {
        unavailable("unavailable", "Runtime data input could not be read")
    } catch (_: SecurityException) {
        unavailable("unavailable", "Runtime data access was refused")
    } catch (_: UnsupportedOperationException) {
        unavailable("unavailable", "Runtime data requires unsupported filesystem operations")
    } catch (_: IllegalArgumentException) {
        unavailable("pathInvalid", "Runtime data selector is outside the direct path profile")
    }

    fun checkCurrent(captured: ClangGlibcRuntimeInputCaptureResult.Captured): ClangGlibcRuntimeInputCurrency =
        when (val fresh = capture(captured.cacheEvidence.path, captured.preloadPath)) {
            is ClangGlibcRuntimeInputCaptureResult.Unavailable -> ClangGlibcRuntimeInputCurrency.NotCurrent(fresh.diagnostic)
            is ClangGlibcRuntimeInputCaptureResult.Captured -> {
                val before = captured.cacheFacts; val after = fresh.cacheFacts
                if (fresh.cacheEvidence == captured.cacheEvidence && fresh.preloadPath == captured.preloadPath &&
                    before.sha256 == after.sha256 && before.headerFlags == after.headerFlags &&
                    before.entries == after.entries && before.extensions == after.extensions) ClangGlibcRuntimeInputCurrency.Current
                else ClangGlibcRuntimeInputCurrency.NotCurrent(diagnostic("changed", "Cache differs from its original runtime input capture"))
            }
        }

    private fun directPath(input: Path): Path {
        val path = input.toAbsolutePath()
        val text = path.toString()
        if (path != path.normalize() || path.fileName == null || text.length > 4096 || text.any(Char::isISOControl) ||
            Path.of(text) != path) refuse("pathInvalid", "Runtime data requires an absolute normalized direct selector")
        return path
    }

    private fun parents(path: Path): List<Any> {
        val result = mutableListOf<Any>(); var current = path.root ?: refuse("pathInvalid", "Runtime data requires absolute paths")
        val components = path.parent?.toList() ?: refuse("pathInvalid", "Runtime data requires a parent directory")
        if (components.size > 256) refuse("inputLimit", "Runtime data path exceeds its depth limit")
        fun directory(path: Path) {
            val attrs = attributes(path)
            if (!attrs.isDirectory || attrs.isSymbolicLink) refuse("pathInvalid", "Runtime data ancestors must be direct directories")
            result += attrs.fileKey() ?: refuse("unavailable", "Runtime data directory identity is unavailable")
        }
        directory(current)
        for (component in components) { current = current.resolve(component); directory(current) }
        return result
    }

    private fun absent(path: Path): List<Any> {
        val before = parents(path)
        try { attributes(path); refuse("preloadPresent", "Selected preload input is present") }
        catch (_: NoSuchFileException) { /* Only an exact no-follow absence is admitted. */ }
        if (parents(path) != before) refuse("changed", "Preload parent changed during absence inspection")
        return before
    }

    private fun attributes(path: Path) = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    private fun stamp(attrs: BasicFileAttributes) = Stamp(attrs.fileKey(), attrs.size(), attrs.creationTime(), attrs.lastModifiedTime(),
        attrs.isRegularFile, attrs.isSymbolicLink)
    private data class Stamp(val key: Any?, val size: Long, val created: java.nio.file.attribute.FileTime,
        val modified: java.nio.file.attribute.FileTime, val regular: Boolean, val link: Boolean)
    private class Refused(val diagnostic: Diagnostic) : RuntimeException()
    private fun refuse(code: String, message: String): Nothing = throw Refused(diagnostic(code, message))
    private fun unavailable(code: String, message: String) = ClangGlibcRuntimeInputCaptureResult.Unavailable(diagnostic(code, message))
    private fun diagnostic(code: String, message: String) = Diagnostic(message, Diagnostic.Severity.ERROR,
        code = "clang.glibcRuntime.$code", evidence = DiagnosticEvidence.STRUCTURAL, category = DiagnosticCategory.SAFETY)

    companion object {
        val SYSTEM_CACHE: Path = Path.of("/etc/ld.so.cache")
        val SYSTEM_PRELOAD: Path = Path.of("/etc/ld.so.preload")
    }
}
