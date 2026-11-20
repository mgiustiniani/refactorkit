package org.refactorkit.c

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.ArrayDeque
import java.util.Collections

data class ClangNativePathAlias(val path: Path, val target: Path)

sealed interface ClangNativePathCaptureResult {
    data class Captured(val requestedPath: Path, val aliases: List<ClangNativePathAlias>,
        val artifact: ClangArtifactCaptureResult.Captured) : ClangNativePathCaptureResult
    data class Unavailable(val diagnostic: Diagnostic, val artifactDiagnostic: Diagnostic? = null) : ClangNativePathCaptureResult
}

sealed interface ClangNativePathCurrency {
    data object Current : ClangNativePathCurrency
    data class NotCurrent(val diagnostic: Diagnostic) : ClangNativePathCurrency
}

/** Read-only alias/native-object boundary; a capture never authorizes execution. */
class ClangNativePathCapture(maxFileBytes: Int = 512 * 1024 * 1024) {
    private val artifacts = ClangArtifactInputCapture(maxFileBytes)

    fun capture(file: Path, role: ClangArtifactRole): ClangNativePathCaptureResult = try {
        val requested = file.toAbsolutePath()
        validatePath(requested)
        val before = resolve(requested)
        when (val artifact = artifacts.capture(before.target, role)) {
            is ClangArtifactCaptureResult.Unavailable -> ClangNativePathCaptureResult.Unavailable(
                diagnostic("artifactUnavailable", "Resolved native artifact is unavailable or outside the ELF profile"), artifact.diagnostic)
            is ClangArtifactCaptureResult.Captured -> {
                if (resolve(requested) != before) refuse("changed", "Native path changed during capture")
                ClangNativePathCaptureResult.Captured(requested,
                    Collections.unmodifiableList(ArrayList(before.aliases)), artifact)
            }
        }
    } catch (failure: Refused) {
        ClangNativePathCaptureResult.Unavailable(failure.diagnostic)
    } catch (_: IOException) {
        unavailable("unavailable", "Native path could not be read")
    } catch (_: SecurityException) {
        unavailable("unavailable", "Native path access was refused")
    } catch (_: UnsupportedOperationException) {
        unavailable("unavailable", "Native path requires unsupported filesystem operations")
    } catch (_: IllegalArgumentException) {
        unavailable("pathInvalid", "Native path is outside the admitted representation")
    }

    fun checkCurrent(captured: ClangNativePathCaptureResult.Captured): ClangNativePathCurrency =
        when (val fresh = capture(captured.requestedPath, captured.artifact.role)) {
            is ClangNativePathCaptureResult.Unavailable -> ClangNativePathCurrency.NotCurrent(fresh.diagnostic)
            is ClangNativePathCaptureResult.Captured -> if (fresh == captured) ClangNativePathCurrency.Current
                else ClangNativePathCurrency.NotCurrent(diagnostic("changed", "Native alias or resolved artifact differs from its capture"))
        }

    private fun resolve(requested: Path): Resolution {
        var current = requested.root ?: refuse("pathInvalid", "Native path must be absolute")
        val pending = ArrayDeque<Path>(); requested.forEach(pending::addLast)
        val aliases = mutableListOf<ClangNativePathAlias>()
        val aliasPaths = mutableSetOf<Path>()
        val directories = linkedMapOf<Path, Any?>()
        var visited = 0
        while (pending.isNotEmpty()) {
            if (++visited > MAX_COMPONENTS) refuse("inputLimit", "Native path traversal exceeds its component limit")
            val part = pending.removeFirst()
            if (part.toString() == ".") continue
            if (part.toString() == "..") { current = current.parent ?: current.root; continue }
            val parent = attributes(current)
            if (!parent.isDirectory || parent.isSymbolicLink) refuse("pathInvalid", "Native path parent must be a resolved directory")
            if (directories.containsKey(current) && directories[current] != parent.fileKey()) refuse("changed", "Native path parent changed")
            directories[current] = parent.fileKey()
            val candidate = current.resolve(part)
            validatePath(candidate)
            val node = attributes(candidate)
            if (node.isSymbolicLink) {
                if (!aliasPaths.add(candidate)) refuse("cycle", "Native alias traversal repeats an alias")
                if (aliases.size >= MAX_ALIASES) refuse("inputLimit", "Native alias count exceeds its limit")
                val target = Files.readSymbolicLink(candidate)
                validatePath(target)
                if (stamp(node) != stamp(attributes(candidate))) refuse("changed", "Native alias changed during reading")
                aliases += ClangNativePathAlias(candidate, target)
                if (target.isAbsolute) current = target.root
                target.toList().asReversed().forEach(pending::addFirst)
            } else {
                if (pending.isNotEmpty() && !node.isDirectory) refuse("pathInvalid", "Native path has a non-directory ancestor")
                current = candidate
            }
        }
        return Resolution(current, aliases.toList(), directories.toMap(), stamp(attributes(current)))
    }

    private fun validatePath(path: Path) {
        val text = path.toString()
        if (text.isEmpty() || text.toByteArray(Charsets.UTF_8).size > MAX_PATH_BYTES || text.any(Char::isISOControl) ||
            Path.of(text) != path) refuse("pathInvalid", "Native path representation is invalid or too large")
    }

    private fun attributes(path: Path): BasicFileAttributes =
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)

    private fun stamp(attrs: BasicFileAttributes) = NodeStamp(attrs.fileKey(), attrs.size(),
        attrs.creationTime(), attrs.lastModifiedTime(), attrs.isDirectory, attrs.isRegularFile, attrs.isSymbolicLink)

    private data class Resolution(val target: Path, val aliases: List<ClangNativePathAlias>,
        val directories: Map<Path, Any?>, val leaf: NodeStamp)
    private data class NodeStamp(val key: Any?, val size: Long, val created: java.nio.file.attribute.FileTime,
        val modified: java.nio.file.attribute.FileTime, val directory: Boolean, val regular: Boolean, val link: Boolean)
    private class Refused(val diagnostic: Diagnostic) : RuntimeException()

    private fun refuse(code: String, message: String): Nothing = throw Refused(diagnostic(code, message))
    private fun unavailable(code: String, message: String) = ClangNativePathCaptureResult.Unavailable(diagnostic(code, message))
    private fun diagnostic(code: String, message: String) = Diagnostic(message, Diagnostic.Severity.ERROR,
        code = "clang.nativePath.$code", evidence = DiagnosticEvidence.STRUCTURAL, category = DiagnosticCategory.SAFETY)

    companion object {
        private const val MAX_ALIASES = 32
        private const val MAX_COMPONENTS = 2048
        private const val MAX_PATH_BYTES = 4096
    }
}
