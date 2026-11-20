package org.refactorkit.c

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

sealed interface ClangResourceCaptureResult {
    data class Captured(val snapshot: ClangResourceDirectorySnapshot) : ClangResourceCaptureResult
    data class Unavailable(val diagnostic: Diagnostic) : ClangResourceCaptureResult
}

sealed interface ClangResourceCurrency {
    data object Current : ClangResourceCurrency
    data class NotCurrent(val diagnostic: Diagnostic) : ClangResourceCurrency
}

/** Read-only resource acquisition; it is not an execution lease or filesystem lock. */
class ClangResourceDirectoryCapture(private val limits: ClangResourceLimits = ClangResourceLimits()) {
    fun capture(root: Path): ClangResourceCaptureResult {
        return try {
            val path = root.toAbsolutePath().normalize()
            val rootFacts = ClangResourceDirectoryFacts.evaluate(path, emptyList(), emptyList(), limits)
            if (rootFacts is ClangResourceFactsResult.Refused) return refused(rootFacts.reason)
            val parentKeys = parents(path)
            val before = inventory(path)
            val files = before.filterValues { it.kind == FILE }.map { (file, node) ->
                val bytes = Files.newInputStream(file, NOFOLLOW_LINKS).use { it.readNBytes(limits.maxFileBytes + 1) }
                if (bytes.size > limits.maxFileBytes) fail("inputLimit", "Resource file exceeds its byte limit")
                if (bytes.size.toLong() != node.size || node != node(attributes(file))) {
                    fail("changed", "Resource file changed during capture")
                }
                ClangResourceFile(path.relativize(file), bytes.size.toLong(), ClangResourceDirectoryFacts.contentHash(bytes))
            }
            if (inventory(path) != before || parents(path) != parentKeys) {
                fail("changed", "Resource directory changed during capture")
            }
            val directories = before.filter { (file, node) -> file != path && node.kind == DIRECTORY }.keys.map(path::relativize)
            when (val facts = ClangResourceDirectoryFacts.evaluate(path, files, directories, limits)) {
                is ClangResourceFactsResult.Admitted -> ClangResourceCaptureResult.Captured(facts.snapshot)
                is ClangResourceFactsResult.Refused -> refused(facts.reason)
            }
        } catch (failure: CaptureFailure) {
            unavailable(failure.code, requireNotNull(failure.message))
        } catch (_: IOException) {
            unavailable("unavailable", "Resource directory could not be read completely")
        } catch (_: SecurityException) {
            unavailable("unavailable", "Resource directory access was refused")
        } catch (_: UnsupportedOperationException) {
            unavailable("unavailable", "Resource directory requires unsupported file operations")
        }
    }

    fun checkCurrent(snapshot: ClangResourceDirectorySnapshot): ClangResourceCurrency =
        when (val fresh = capture(snapshot.root)) {
            is ClangResourceCaptureResult.Unavailable -> ClangResourceCurrency.NotCurrent(fresh.diagnostic)
            is ClangResourceCaptureResult.Captured -> if (fresh.snapshot == snapshot) ClangResourceCurrency.Current
                else ClangResourceCurrency.NotCurrent(diagnostic("changed", "Resources differ from their captured inputs"))
        }

    private data class NativeNode(val kind: Int, val size: Long, val modified: FileTime,
        val created: FileTime, val key: Any?)

    private fun attributes(path: Path) = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)

    private fun node(attrs: BasicFileAttributes): NativeNode {
        val kind = when {
            attrs.isDirectory && !attrs.isSymbolicLink -> DIRECTORY
            attrs.isRegularFile && !attrs.isSymbolicLink -> FILE
            else -> fail("pathInvalid", "Resources must contain only direct regular files and directories")
        }
        return NativeNode(kind, attrs.size(), attrs.lastModifiedTime(), attrs.creationTime(), attrs.fileKey())
    }

    private fun parents(path: Path): List<Any?> {
        var current = path.root ?: fail("pathInvalid", "Resource root must be absolute")
        val keys = mutableListOf<Any?>()
        for (part in path) {
            current = current.resolve(part)
            val attrs = attributes(current)
            if (!attrs.isDirectory || attrs.isSymbolicLink) fail("pathInvalid", "Resource directory path must be direct")
            keys += attrs.fileKey()
        }
        return keys
    }

    private fun inventory(root: Path): Map<Path, NativeNode> {
        val nodes = linkedMapOf<Path, NativeNode>()
        var total = 0L
        fun record(path: Path, attrs: BasicFileAttributes) {
            val relative = root.relativize(path)
            if (path != root && (nodes.size > limits.maxEntries || relative.nameCount > limits.maxDepth ||
                relative.toString().toByteArray(Charsets.UTF_8).size > limits.maxPathBytes)) {
                fail("inputLimit", "Resource directory exceeds its traversal limits")
            }
            val captured = node(attrs)
            if (captured.kind == FILE) {
                if (captured.size > limits.maxFileBytes || captured.size > limits.maxTotalBytes - total) {
                    fail("inputLimit", "Resource directory exceeds its byte limits")
                }
                total += captured.size
            }
            nodes[path] = captured
        }
        Files.walkFileTree(root, emptySet(), limits.maxDepth + 1, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                record(dir, attrs); return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                record(file, attrs); return FileVisitResult.CONTINUE
            }
        })
        return nodes
    }

    private fun refused(reason: ClangResourceRefusal) = unavailable(
        if (reason == ClangResourceRefusal.INPUT_LIMIT) "inputLimit" else "pathInvalid", "Resource facts were refused: ${reason.name}")

    private fun unavailable(code: String, message: String) = ClangResourceCaptureResult.Unavailable(diagnostic(code, message))
    private class CaptureFailure(val code: String, message: String) : IOException(message)
    private fun fail(code: String, message: String): Nothing = throw CaptureFailure(code, message)

    private companion object {
        const val FILE = 1
        const val DIRECTORY = 2
    }

    private fun diagnostic(code: String, message: String) = Diagnostic(message, Diagnostic.Severity.ERROR,
        code = "clang.resources.$code", evidence = DiagnosticEvidence.STRUCTURAL, category = DiagnosticCategory.SAFETY)
}
