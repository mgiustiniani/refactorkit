package org.refactorkit.core

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributeView
import java.security.MessageDigest

/** Parks the emptied source tree until its transaction is rolled back. */
internal object RetiredSourceDirectories {
    fun capture(
        root: Path,
        relative: Path,
        id: TransactionId,
        preImages: List<FileImage>,
        postImages: List<FileImage>,
        edit: WorkspaceEdit,
    ): RetiredSourceDirectoryImage {
        val source = sourcePath(root, relative)
        require(Files.notExists(holdingPath(root, id), NOFOLLOW_LINKS)) {
            "Reserved source directory backup already exists"
        }
        val oldFiles = preImages.filter { it.content != null && it.path.startsWith(relative) }.map { it.path }.toSet()
        val moves = edit.edits.filterIsInstance<FileEdit.Rename>().filter { it.path.startsWith(relative) }
        require(oldFiles.isNotEmpty() && moves.map { it.path }.toSet() == oldFiles &&
            moves.none { it.newPath.startsWith(relative) } &&
            postImages.none { it.content != null && it.path.startsWith(relative) }
        ) { "Source directory retirement requires a complete outbound file move" }
        return RetiredSourceDirectoryImage(ProtocolPath.serialize(relative), treeIdentity(source, allowRegularFiles = true))
    }

    fun retire(root: Path, record: TransactionJournalRecord, forceDirectory: (Path) -> Unit) {
        val image = record.retiredSourceDirectory ?: return
        val source = sourcePath(root, Path.of(image.path))
        val holding = holdingPath(root, record.transaction.id)
        requireIdentity(source, image, allowRegularFiles = false)
        move(source, holding, forceDirectory)
        requireIdentity(holding, image, allowRegularFiles = false)
    }

    fun requireReadyForRollback(root: Path, record: TransactionJournalRecord) {
        val image = record.retiredSourceDirectory ?: return
        val source = sourcePath(root, Path.of(image.path))
        require(Files.notExists(source, NOFOLLOW_LINKS)) { "Retired source directory was recreated: ${image.path}" }
        requireIdentity(holdingPath(root, record.transaction.id), image, allowRegularFiles = false)
    }

    fun restore(
        root: Path,
        record: TransactionJournalRecord,
        forceDirectory: (Path) -> Unit,
        recovering: Boolean = false,
    ) {
        val image = record.retiredSourceDirectory ?: return
        val source = sourcePath(root, Path.of(image.path))
        val holding = holdingPath(root, record.transaction.id)
        if (recovering && Files.notExists(holding, NOFOLLOW_LINKS)) {
            requireIdentity(source, image, allowRegularFiles = true)
            return
        }
        requireIdentity(holding, image, allowRegularFiles = recovering)
        move(holding, source, forceDirectory)
        requireIdentity(source, image, allowRegularFiles = recovering)
    }

    private fun sourcePath(root: Path, relative: Path): Path {
        require(!relative.isAbsolute && relative == relative.normalize() && relative.toString().isNotBlank() &&
            !relative.startsWith("..") && !relative.startsWith(".refactorkit")
        ) { "Unsafe source directory for retirement" }
        return root.resolve(relative)
    }

    private fun holdingPath(root: Path, id: TransactionId): Path =
        root.resolve(".refactorkit/retired-source-${id.value}")

    private fun requireIdentity(path: Path, image: RetiredSourceDirectoryImage, allowRegularFiles: Boolean) {
        require(treeIdentity(path, allowRegularFiles) == image.directoryIdentity) {
            "Source directory tree changed since retirement was prepared: ${image.path}"
        }
    }

    private fun treeIdentity(path: Path, allowRegularFiles: Boolean): String {
        val digest = MessageDigest.getInstance("SHA-256")
        withDirectory(path.parent) { parent ->
            digestTree(parent, path.fileName, "", allowRegularFiles, digest)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun digestTree(
        parent: SecureDirectoryStream<Path>,
        name: Path,
        relative: String,
        allowRegularFiles: Boolean,
        digest: MessageDigest,
    ) {
        val attrs = parent.getFileAttributeView(name, BasicFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes()
        if (allowRegularFiles && attrs.isRegularFile) return
        require(attrs.isDirectory) { "Source tree contains an unexpected entry: $relative" }
        val key = requireNotNull(attrs.fileKey()) { "Filesystem cannot identify source directory: $relative" }
        digest.update(relative.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(key.toString().toByteArray(Charsets.UTF_8))
        digest.update(0)
        parent.newDirectoryStream(name, NOFOLLOW_LINKS).use { directory ->
            directory.map { it.fileName }.sorted().forEach { child ->
                val childPath = if (relative.isEmpty()) child.toString() else "$relative/$child"
                digestTree(directory, child, childPath, allowRegularFiles, digest)
            }
        }
    }

    private fun move(source: Path, destination: Path, forceDirectory: (Path) -> Unit) {
        withDirectory(source.parent) { from ->
            withDirectory(destination.parent) { to ->
                require(Files.notExists(destination, NOFOLLOW_LINKS)) { "Directory move target already exists: $destination" }
                from.move(source.fileName, to, destination.fileName)
            }
        }
        forceDirectory(source.parent)
        forceDirectory(destination.parent)
    }

    private fun <T> withDirectory(path: Path, action: (SecureDirectoryStream<Path>) -> T): T {
        val root = path.root
        Files.newDirectoryStream(root).use { opened ->
            val secure = requireNotNull(opened as? SecureDirectoryStream<Path>) {
                "Filesystem does not support no-follow source directory retirement"
            }
            return descend(secure, root.relativize(path).iterator(), action)
        }
    }

    private fun <T> descend(
        parent: SecureDirectoryStream<Path>,
        names: Iterator<Path>,
        action: (SecureDirectoryStream<Path>) -> T,
    ): T {
        if (!names.hasNext()) return action(parent)
        val next = names.next()
        if (next.toString().isEmpty()) return action(parent)
        return parent.newDirectoryStream(next, NOFOLLOW_LINKS).use { descend(it, names, action) }
    }
}
