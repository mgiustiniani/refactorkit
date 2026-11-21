package org.refactorkit.core

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView

/** Inspects and removes only operation-authorized directories inside the journaled destination. */
internal object RollbackEmptyDirectories {
    fun validateScope(scope: Set<Path>, created: List<Path>, images: List<FileImage>) {
        require(scope.all { path ->
            !path.isAbsolute && path == path.normalize() && path.toString().isNotBlank() &&
                !path.startsWith("..") && !path.startsWith(".refactorkit") &&
                path !in created && (path.parent in scope || path.parent in created) &&
                images.none { it.path == path || it.path.startsWith(path) }
        }) { "Rollback empty-directory authorization escapes the new destination or overlaps a file image" }
    }

    fun observe(root: Path, scope: Set<Path>): Map<Path, String> = scope
        .filterNot { Files.notExists(root.resolve(it), NOFOLLOW_LINKS) }
        .associateWith { path -> withParent(root.resolve(path)) { parent -> identity(parent, path.fileName) } }

    fun verifyRecorded(root: Path, identities: Map<Path, String>) {
        val present = observe(root, identities.keys)
        require(present.all { (path, identity) -> identities[path] == identity }) {
            "A directory authorized for rollback has changed identity or metadata"
        }
    }

    fun remove(root: Path, identities: Map<Path, String>) {
        verifyRecorded(root, identities)
        identities.entries.sortedByDescending { it.key.nameCount }.forEach { (path, expected) ->
            val absolute = root.resolve(path)
            if (Files.notExists(absolute, NOFOLLOW_LINKS)) return@forEach
            withParent(absolute) { parent ->
                require(identity(parent, path.fileName) == expected) { "Rollback directory changed: $path" }
                parent.deleteDirectory(path.fileName)
            }
        }
    }

    private fun identity(parent: SecureDirectoryStream<Path>, name: Path): String {
        val attrs = parent.getFileAttributeView(name, BasicFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes()
        require(attrs.isDirectory && !attrs.isSymbolicLink) { "Rollback output is not a no-follow directory: $name" }
        val key = requireNotNull(attrs.fileKey()) { "Filesystem does not expose rollback directory identity: $name" }
        val posix = parent.getFileAttributeView(name, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)?.readAttributes()
        return listOf(key, attrs.creationTime(), posix?.owner()?.name, posix?.group()?.name, posix?.permissions()?.sorted())
            .joinToString("|")
    }

    private fun <T> withParent(path: Path, action: (SecureDirectoryStream<Path>) -> T): T {
        val root = requireNotNull(path.root)
        Files.newDirectoryStream(root).use { directory ->
            val secure = requireNotNull(directory as? SecureDirectoryStream<Path>) {
                "Filesystem does not expose no-follow directory removal"
            }
            return descend(secure, root.relativize(requireNotNull(path.parent)), 0, action)
        }
    }

    private fun <T> descend(
        directory: SecureDirectoryStream<Path>, path: Path, index: Int,
        action: (SecureDirectoryStream<Path>) -> T,
    ): T {
        if (index == path.nameCount || path.toString().isEmpty()) return action(directory)
        return directory.newDirectoryStream(path.getName(index), NOFOLLOW_LINKS).use {
            descend(it, path, index + 1, action)
        }
    }
}
