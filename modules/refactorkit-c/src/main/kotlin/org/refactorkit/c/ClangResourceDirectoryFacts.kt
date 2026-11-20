package org.refactorkit.c

import java.nio.ByteBuffer
import java.nio.file.Path
import java.security.MessageDigest
import java.util.ArrayList
import java.util.Collections

data class ClangResourceFile(val path: Path, val size: Long, val sha256: String)

class ClangResourceDirectorySnapshot(
    val root: Path,
    files: List<ClangResourceFile>,
    directories: List<Path>,
    val identity: String,
    val totalBytes: Long,
) {
    val files: List<ClangResourceFile> = immutableClangResourceList(files)
    val directories: List<Path> = immutableClangResourceList(directories)

    operator fun component1(): Path = root
    operator fun component2(): List<ClangResourceFile> = files
    operator fun component3(): List<Path> = directories
    operator fun component4(): String = identity
    operator fun component5(): Long = totalBytes

    fun copy(
        root: Path = this.root,
        files: List<ClangResourceFile> = this.files,
        directories: List<Path> = this.directories,
        identity: String = this.identity,
        totalBytes: Long = this.totalBytes,
    ) = ClangResourceDirectorySnapshot(root, files, directories, identity, totalBytes)

    override fun equals(other: Any?): Boolean = other === this ||
        other is ClangResourceDirectorySnapshot && root == other.root && files == other.files &&
        directories == other.directories && identity == other.identity && totalBytes == other.totalBytes

    override fun hashCode(): Int {
        var result = root.hashCode()
        result = 31 * result + files.hashCode()
        result = 31 * result + directories.hashCode()
        result = 31 * result + identity.hashCode()
        result = 31 * result + totalBytes.hashCode()
        return result
    }

    override fun toString(): String =
        "ClangResourceDirectorySnapshot(root=$root, files=$files, directories=$directories, identity=$identity, totalBytes=$totalBytes)"
}

private fun <T> immutableClangResourceList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

data class ClangResourceLimits(val maxEntries: Int = 8192, val maxDepth: Int = 16,
    val maxPathBytes: Int = 4096, val maxFileBytes: Int = 32 * 1024 * 1024,
    val maxTotalBytes: Long = 128L * 1024 * 1024) {
    init {
        require(maxEntries in 1..8192 && maxDepth in 1..16 && maxPathBytes in 1..4096 &&
            maxFileBytes in 1..32 * 1024 * 1024 && maxTotalBytes in 1..128L * 1024 * 1024)
    }
}

internal enum class ClangResourceRefusal { INVALID_ROOT, INVALID_TREE, INPUT_LIMIT }
internal sealed interface ClangResourceFactsResult {
    data class Admitted(val snapshot: ClangResourceDirectorySnapshot) : ClangResourceFactsResult
    data class Refused(val reason: ClangResourceRefusal) : ClangResourceFactsResult
}

/** Pure complete tree facts; native capture and current-input checks are separate. */
internal object ClangResourceDirectoryFacts {
    fun contentHash(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun evaluate(root: Path, files: List<ClangResourceFile>, directories: List<Path>,
        limits: ClangResourceLimits = ClangResourceLimits()): ClangResourceFactsResult {
        fun refuse(reason: ClangResourceRefusal) = ClangResourceFactsResult.Refused(reason)
        if (!root.isAbsolute || root != root.normalize() || !representable(root) ||
            root.toString().toByteArray(Charsets.UTF_8).size > 4096) return refuse(ClangResourceRefusal.INVALID_ROOT)
        if (files.size.toLong() + directories.size > limits.maxEntries) return refuse(ClangResourceRefusal.INPUT_LIMIT)
        val ownedFiles = files.toList(); val ownedDirs = directories.toList()
        val paths = ownedFiles.map { it.path } + ownedDirs
        if (paths.any { it.isAbsolute || it != it.normalize() || it.startsWith("..") || !representable(it) } ||
            paths.toSet().size != paths.size) return refuse(ClangResourceRefusal.INVALID_TREE)
        if (paths.any { it.nameCount > limits.maxDepth || it.toString().toByteArray(Charsets.UTF_8).size > limits.maxPathBytes }) {
            return refuse(ClangResourceRefusal.INPUT_LIMIT)
        }
        val directorySet = ownedDirs.toSet()
        if (paths.any { it.parent != null && it.parent !in directorySet } ||
            ownedFiles.any { it.size < 0 || !HASH.matches(it.sha256) }) return refuse(ClangResourceRefusal.INVALID_TREE)
        var total = 0L
        for (file in ownedFiles) {
            if (file.size > limits.maxFileBytes || file.size > limits.maxTotalBytes - total) {
                return refuse(ClangResourceRefusal.INPUT_LIMIT)
            }
            total += file.size
        }
        val sortedFiles = ownedFiles.sortedBy { it.path.toString() }
        val sortedDirs = ownedDirs.sortedBy(Path::toString)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.frame("refactorkit.clang.resources.v1"); digest.frame(root.toString())
        digest.update(ByteBuffer.allocate(4).putInt(paths.size).array())
        val byPath = sortedFiles.associateBy { it.path }
        for (path in paths.sortedBy(Path::toString)) {
            val file = byPath[path]
            digest.update(if (file == null) 2.toByte() else 1.toByte()); digest.frame(path.toString())
            if (file != null) {
                digest.update(ByteBuffer.allocate(8).putLong(file.size).array()); digest.frame(file.sha256)
            }
        }
        return ClangResourceFactsResult.Admitted(ClangResourceDirectorySnapshot(
            root, sortedFiles, sortedDirs, hex(digest.digest()), total,
        ))
    }

    private fun representable(path: Path): Boolean {
        val value = path.toString()
        return value.isNotEmpty() && value.none(Char::isISOControl) &&
            runCatching { Path.of(value) == path }.getOrDefault(false)
    }

    private fun MessageDigest.frame(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        update(ByteBuffer.allocate(4).putInt(bytes.size).array()); update(bytes)
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private val HASH = Regex("[a-f0-9]{64}")
}
