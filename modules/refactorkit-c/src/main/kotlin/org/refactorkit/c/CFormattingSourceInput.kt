package org.refactorkit.c

import org.refactorkit.core.ProjectSnapshot
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Acquired source boundary; equality checks do not lock a live filesystem. */
internal class CFormattingSourceInput private constructor(
    val path: Path,
    val content: String,
    private val bytes: ByteArray,
) {
    fun isCurrent(): Boolean = runCatching {
        val parentsBefore = directParents() ?: return@runCatching false
        val before = attributes(path)
        if (!before.isRegularFile || before.isSymbolicLink || before.size() != bytes.size.toLong()) return@runCatching false
        val observed = Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(bytes.size + 1) }
        val after = attributes(path)
        after.isRegularFile && !after.isSymbolicLink && before.fileKey() == after.fileKey() &&
            before.size() == after.size() && before.lastModifiedTime() == after.lastModifiedTime() &&
            before.creationTime() == after.creationTime() && directParents() == parentsBefore &&
            bytes.contentEquals(observed)
    }.getOrDefault(false)

    private fun directParents(): List<Any?>? {
        var current = path.root ?: return null
        val parent = path.parent ?: return null
        if (parent.nameCount > MAX_PARENT_COMPONENTS) return null
        val identities = mutableListOf<Any?>()
        for (part in parent) {
            current = current.resolve(part)
            val attrs = attributes(current)
            if (!attrs.isDirectory || attrs.isSymbolicLink) return null
            identities += attrs.fileKey()
        }
        return identities
    }

    private fun attributes(file: Path): BasicFileAttributes =
        Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)

    companion object {
        private const val MAX_SOURCE_BYTES = 8 * 1024 * 1024
        private const val MAX_PARENT_COMPONENTS = 256

        fun select(snapshot: ProjectSnapshot, file: Path): CFormattingSourceInput? = runCatching {
            val root = snapshot.workspace.root.toAbsolutePath().normalize()
            val selected = root.resolve(file).normalize()
            if (!selected.startsWith(root) || selected == root) return@runCatching null
            val source = snapshot.files.asSequence().filter { root.resolve(it.path).normalize() == selected }
                .take(2).toList().singleOrNull() ?: return@runCatching null
            if (source.content.length > MAX_SOURCE_BYTES) return@runCatching null
            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(source.content))
            if (encoded.remaining() > MAX_SOURCE_BYTES) return@runCatching null
            val bytes = ByteArray(encoded.remaining()); encoded.get(bytes)
            CFormattingSourceInput(selected, source.content, bytes)
        }.getOrNull()
    }
}
