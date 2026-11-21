package org.refactorkit.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes

private const val MAX_ROOT_COMPONENTS = 256
private const val MAX_WORKSPACE_INPUT_BYTES = 8 * 1024 * 1024

sealed interface OwnedWorkspaceTextReadout {
    data class Captured(val content: String) : OwnedWorkspaceTextReadout
    data object Absent : OwnedWorkspaceTextReadout
}

data class OwnedSourceInventory(
    val sources: List<SourceFile>,
    val regularPaths: List<Path>,
    val directories: Set<Path>,
)

/** Stateless filesystem port: each capture owns independent descriptors, counters and buffers. */
class OwnedSourceInventoryReader(
    extensionLanguageIds: Map<String, String>,
    private val policy: SourceCapturePolicy = SourceCapturePolicy(),
    ignoredDirectories: Set<String> = ProjectSnapshot.DEFAULT_IGNORED_DIRECTORIES,
    private val caseSensitiveExtensions: Boolean = true,
    private val allowMissingRoot: Boolean = false,
    private val ignoreRootDirectory: Boolean = false,
) {
    private val extensions = extensionLanguageIds.toMap()
    private val ignored = ignoredDirectories.toSet()

    fun capture(root: Path): OwnedSourceInventory = OwnedSourceCapture(
        policy, extensions, ignored, caseSensitiveExtensions, allowMissingRoot, ignoreRootDirectory,
    ).capture(root.toAbsolutePath().normalize())

    /** Capture only model-selected relative source roots; explicit roots may live below generated directories. */
    fun captureSourceRoots(root: Path, sourceRoots: List<Path>): OwnedSourceInventory {
        val normalized = root.toAbsolutePath().normalize()
        if (sourceRoots.size > policy.maxEntries)
            throw OwnedSourceCaptureException(OwnedSourceCaptureFailure.ENTRIES_EXCEEDED, normalized)
        return OwnedSourceCapture(
            policy, extensions, ignored, caseSensitiveExtensions, allowMissingRoot, ignoreRootDirectory,
            selectedRoots = sourceRoots.toList(),
        ).capture(normalized)
    }

    companion object {
        /** Enumerate owned workspace file membership without opening the file contents. */
        fun captureWorkspaceFilePaths(snapshot: ProjectSnapshot): Set<Path> {
            val root = snapshot.workspace.root.toAbsolutePath().normalize()
            return OwnedSourceCapture(
                policy = SourceCapturePolicy(), extensions = emptyMap(), ignoredDirectories = snapshot.ignoredDirectories,
                caseSensitiveExtensions = true, allowMissingRoot = false, ignoreRootDirectory = false,
                requireRegularEntries = true,
            ).capture(root).regularPaths.mapTo(linkedSetOf(), root::relativize)
        }

        /** Verify every declared output root through owned handles, including in otherwise ignored trees. */
        fun requireEmptyBuildOutputDirectories(snapshot: ProjectSnapshot) {
            val root = snapshot.workspace.root.toAbsolutePath().normalize()
            val outputs = snapshot.buildModels.flatMap(BuildModel::modules).flatMap(BuildModule::sourceSets)
                .flatMap(BuildSourceSet::outputDirectories).map(Path::normalize).distinct()
            val policy = SourceCapturePolicy()
            if (outputs.isEmpty() || outputs.any { it.toString().isBlank() }) {
                throw OwnedSourceCaptureException(OwnedSourceCaptureFailure.OWNERSHIP_UNAVAILABLE, root)
            }
            if (outputs.size > policy.maxEntries) {
                throw OwnedSourceCaptureException(OwnedSourceCaptureFailure.ENTRIES_EXCEEDED, root)
            }
            OwnedSourceCapture(
                policy, emptyMap(), emptySet(), caseSensitiveExtensions = true, allowMissingRoot = false,
                ignoreRootDirectory = false, selectedRoots = outputs, requireEmptySelectedRoots = true,
            ).capture(root)
        }

        /** Bounded opaque UTF-8 input acquisition; unavailable is an explicit typed failure, never absence. */
        fun readWorkspaceTextInput(root: Path, relative: Path, maxBytes: Int): OwnedWorkspaceTextReadout {
            require(maxBytes in 1..MAX_WORKSPACE_INPUT_BYTES)
            val capture = OwnedSourceCapture(
                policy = SourceCapturePolicy(), extensions = emptyMap(), ignoredDirectories = emptySet(),
                caseSensitiveExtensions = true, allowMissingRoot = false, ignoreRootDirectory = false,
                selectedInput = relative, inputByteBound = maxBytes,
            ).capture(root.toAbsolutePath().normalize())
            return if (capture.sources.isEmpty()) OwnedWorkspaceTextReadout.Absent
                else OwnedWorkspaceTextReadout.Captured(capture.sources.single().content)
        }

        /** Validate the current root before journal inspection, without enumerating or reading sources. */
        fun requireOwnedRoot(root: Path) {
            OwnedSourceCapture(
                policy = SourceCapturePolicy(), extensions = emptyMap(), ignoredDirectories = emptySet(),
                caseSensitiveExtensions = true, allowMissingRoot = false, ignoreRootDirectory = false,
                captureContents = false,
            ).capture(root.toAbsolutePath().normalize())
        }
    }
}

/** Filesystem adapter. All source reads are relative to owned, no-follow directory handles. */
private class OwnedSourceCapture(
    private val policy: SourceCapturePolicy,
    private val extensions: Map<String, String>,
    private val ignoredDirectories: Set<String>,
    private val caseSensitiveExtensions: Boolean,
    private val allowMissingRoot: Boolean,
    private val ignoreRootDirectory: Boolean,
    private val captureContents: Boolean = true,
    private val selectedRoots: List<Path>? = null,
    private val selectedInput: Path? = null,
    private val inputByteBound: Int? = null,
    private val requireRegularEntries: Boolean = false,
    private val requireEmptySelectedRoots: Boolean = false,
) {
    private var entries = 0
    private var totalBytes = 0
    private val sources = mutableListOf<SourceFile>()
    private val paths = mutableListOf<Path>()
    private val directories = linkedSetOf<Path>()

    fun capture(root: Path): OwnedSourceInventory = try {
        if (root.nameCount > MAX_ROOT_COMPONENTS) unavailable(OwnedSourceCaptureFailure.DEPTH_EXCEEDED, root)
        Files.newDirectoryStream(root.root).use { stream ->
            val secure = stream as? SecureDirectoryStream<Path>
                ?: unavailable(OwnedSourceCaptureFailure.OWNERSHIP_UNAVAILABLE, root)
            descendToRoot(secure, root, 0) { owned ->
                val identity = attributes(owned)
                requireIdentity(identity, root)
                countEntry(root)
                if (selectedInput != null) {
                    val relative = selectedInput.normalize()
                    if (relative.fileSystem != root.fileSystem || relative.isAbsolute ||
                        relative.toString().isBlank() || relative.startsWith(".."))
                        unavailable(OwnedSourceCaptureFailure.OWNERSHIP_UNAVAILABLE, root)
                    visitSelectedInput(owned, root, relative, 0)
                } else if (selectedRoots != null) {
                    selectedRoots.map { relative ->
                        if (relative.fileSystem != root.fileSystem || relative.isAbsolute)
                            unavailable(OwnedSourceCaptureFailure.OWNERSHIP_UNAVAILABLE, root)
                        val normalized = relative.normalize()
                        if (normalized.startsWith(".."))
                            unavailable(OwnedSourceCaptureFailure.OWNERSHIP_UNAVAILABLE, root.resolve(normalized))
                        normalized
                    }.distinct().forEach { relative ->
                        if (relative !in directories) visitSelectedRoot(owned, root, relative, 0)
                    }
                } else if (captureContents && (!ignoreRootDirectory || root.fileName?.toString() !in ignoredDirectories))
                    visit(owned, root, Path.of(""), 0)
                val current = Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (!sameIdentity(identity, current)) unavailable(OwnedSourceCaptureFailure.INPUT_CHANGED, root)
            }
        }
        OwnedSourceInventory(sources.sortedBy { it.path.toString() }, paths.toList(), directories.toSet())
    } catch (failure: OwnedSourceCaptureException) {
        throw failure
    } catch (failure: IOException) {
        throw OwnedSourceCaptureException(OwnedSourceCaptureFailure.IO_UNAVAILABLE, root, failure)
    } catch (failure: DirectoryIteratorException) {
        throw OwnedSourceCaptureException(OwnedSourceCaptureFailure.IO_UNAVAILABLE, root, failure)
    } catch (failure: SecurityException) {
        throw OwnedSourceCaptureException(OwnedSourceCaptureFailure.IO_UNAVAILABLE, root, failure)
    }

    private fun descendToRoot(
        parent: SecureDirectoryStream<Path>, root: Path, index: Int,
        action: (SecureDirectoryStream<Path>) -> Unit,
    ) {
        if (index == root.nameCount) { action(parent); return }
        val name = root.getName(index)
        val display = root.root.resolve(root.subpath(0, index + 1))
        val attrs = try { attributes(parent, name) }
        catch (failure: NoSuchFileException) {
            // Legacy generic inventories accept an absent root, only after owning every existing ancestor.
            if (allowMissingRoot) return else throw failure
        }
        if (attrs.isSymbolicLink) unavailable(OwnedSourceCaptureFailure.SYMBOLIC_LINK, display)
        if (!attrs.isDirectory) unavailable(OwnedSourceCaptureFailure.OWNERSHIP_UNAVAILABLE, display)
        parent.newDirectoryStream(name, NOFOLLOW_LINKS).use { child ->
            val opened = attributes(child)
            // Ancestor membership is outside the input set; its no-follow directory handle must retain identity.
            val stable = if (index + 1 < root.nameCount) sameAncestorDirectory(attrs, opened)
                else sameIdentity(attrs, opened)
            if (!stable) unavailable(OwnedSourceCaptureFailure.INPUT_CHANGED, display)
            descendToRoot(child, root, index + 1, action)
        }
    }

    private fun visitSelectedInput(directory: SecureDirectoryStream<Path>, root: Path, relative: Path, index: Int) {
        if (relative.nameCount - 1 > policy.maxDepth)
            unavailable(OwnedSourceCaptureFailure.DEPTH_EXCEEDED, root.resolve(relative))
        val parentBefore = attributes(directory)
        requireIdentity(parentBefore, root.resolve(relative))
        val name = relative.getName(index)
        val display = root.resolve(relative.subpath(0, index + 1))
        countEntry(display)
        val attrs = try { attributes(directory, name) }
        catch (failure: NoSuchFileException) {
            if (!sameIdentity(parentBefore, attributes(directory))) unavailable(OwnedSourceCaptureFailure.INPUT_CHANGED, display)
            return
        }
        if (attrs.isSymbolicLink) unavailable(OwnedSourceCaptureFailure.SYMBOLIC_LINK, display)
        if (index == relative.nameCount - 1) {
            if (!attrs.isRegularFile) unavailable(OwnedSourceCaptureFailure.OWNERSHIP_UNAVAILABLE, display)
            val content = read(directory, name, attrs, display)
            sources.add(SourceFile(relative, content, "workspace-input")); paths.add(display)
        } else {
            if (!attrs.isDirectory) unavailable(OwnedSourceCaptureFailure.OWNERSHIP_UNAVAILABLE, display)
            directory.newDirectoryStream(name, NOFOLLOW_LINKS).use { child ->
                if (!sameIdentity(attrs, attributes(child))) unavailable(OwnedSourceCaptureFailure.INPUT_CHANGED, display)
                visitSelectedInput(child, root, relative, index + 1)
            }
        }
        if (!sameIdentity(parentBefore, attributes(directory))) unavailable(OwnedSourceCaptureFailure.INPUT_CHANGED, display)
    }

    private fun visitSelectedRoot(directory: SecureDirectoryStream<Path>, root: Path, relative: Path, index: Int) {
        val depth = if (relative.toString().isEmpty()) 0 else relative.nameCount
        if (depth > policy.maxDepth) unavailable(OwnedSourceCaptureFailure.DEPTH_EXCEEDED, root.resolve(relative))
        if (index == depth) {
            if (requireEmptySelectedRoots) {
                val display = root.resolve(relative)
                val before = attributes(directory)
                requireIdentity(before, display)
                if (directory.iterator().hasNext() || !sameIdentity(before, attributes(directory))) {
                    unavailable(OwnedSourceCaptureFailure.INPUT_CHANGED, display)
                }
            } else {
                visit(directory, root, relative, depth)
            }
            return
        }
        val before = attributes(directory)
        requireIdentity(before, root.resolve(relative))
        val name = relative.getName(index)
        val display = root.resolve(relative.subpath(0, index + 1))
        countEntry(display)
        val attrs = try { attributes(directory, name) }
        catch (failure: NoSuchFileException) {
            // A model may declare a conventional source directory that does not exist yet.
            if (!sameIdentity(before, attributes(directory))) unavailable(OwnedSourceCaptureFailure.INPUT_CHANGED, display)
            return
        }
        if (attrs.isSymbolicLink) unavailable(OwnedSourceCaptureFailure.SYMBOLIC_LINK, display)
        if (!attrs.isDirectory) unavailable(OwnedSourceCaptureFailure.OWNERSHIP_UNAVAILABLE, display)
        directory.newDirectoryStream(name, NOFOLLOW_LINKS).use { child ->
            if (!sameIdentity(attrs, attributes(child))) unavailable(OwnedSourceCaptureFailure.INPUT_CHANGED, display)
            visitSelectedRoot(child, root, relative, index + 1)
        }
        if (!sameIdentity(before, attributes(directory))) unavailable(OwnedSourceCaptureFailure.INPUT_CHANGED, display)
    }

    private fun visit(directory: SecureDirectoryStream<Path>, root: Path, relative: Path, depth: Int) {
        if (relative in directories) return
        val before = attributes(directory)
        directories.add(relative)
        requireIdentity(before, root.resolve(relative))
        for (entry in directory) {
            val name = entry.fileName
            val rel = relative.resolve(name)
            val display = root.resolve(rel)
            countEntry(display)
            val attrs = attributes(directory, name)
            if ((attrs.isDirectory || attrs.isSymbolicLink) && name.toString() in ignoredDirectories) continue
            if (attrs.isSymbolicLink) unavailable(OwnedSourceCaptureFailure.SYMBOLIC_LINK, display)
            when {
                attrs.isDirectory -> {
                    if (depth + 1 > policy.maxDepth) unavailable(OwnedSourceCaptureFailure.DEPTH_EXCEEDED, display)
                    directory.newDirectoryStream(name, NOFOLLOW_LINKS).use { child ->
                        if (!sameIdentity(attrs, attributes(child))) unavailable(OwnedSourceCaptureFailure.INPUT_CHANGED, display)
                        visit(child, root, rel, depth + 1)
                    }
                }
                attrs.isRegularFile -> {
                    paths.add(display)
                    languageId(name)?.let { language ->
                        if (sources.size >= policy.maxSources) unavailable(OwnedSourceCaptureFailure.SOURCES_EXCEEDED, display)
                        sources.add(SourceFile(rel, read(directory, name, attrs, display), language))
                    }
                }
                requireRegularEntries || languageId(name) != null -> unavailable(OwnedSourceCaptureFailure.OWNERSHIP_UNAVAILABLE, display)
            }
        }
        if (!sameIdentity(before, attributes(directory))) unavailable(OwnedSourceCaptureFailure.INPUT_CHANGED, root.resolve(relative))
    }

    private fun read(parent: SecureDirectoryStream<Path>, name: Path, before: BasicFileAttributes, display: Path): String {
        requireIdentity(before, display)
        val fileBound = inputByteBound ?: policy.maxFileBytes
        if (before.size() > fileBound) unavailable(OwnedSourceCaptureFailure.FILE_BYTES_EXCEEDED, display)
        val remaining = policy.maxTotalBytes - totalBytes
        if (before.size() > remaining) unavailable(OwnedSourceCaptureFailure.TOTAL_BYTES_EXCEEDED, display)
        val bound = minOf(fileBound, remaining)
        val buffer = ByteBuffer.allocate(minOf(bound.toLong(), before.size()).toInt() + 1)
        parent.newByteChannel(name, setOf(StandardOpenOption.READ, NOFOLLOW_LINKS)).use { channel ->
            while (buffer.hasRemaining()) { if (channel.read(buffer) < 0) break }
        }
        val count = buffer.position()
        if (count > fileBound) unavailable(OwnedSourceCaptureFailure.FILE_BYTES_EXCEEDED, display)
        if (count > remaining) unavailable(OwnedSourceCaptureFailure.TOTAL_BYTES_EXCEEDED, display)
        if (count.toLong() != before.size() || !sameIdentity(before, attributes(parent, name)))
            unavailable(OwnedSourceCaptureFailure.INPUT_CHANGED, display)
        buffer.flip()
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(buffer).toString()
        } catch (failure: CharacterCodingException) {
            throw OwnedSourceCaptureException(OwnedSourceCaptureFailure.INVALID_UTF8, display, failure)
        }
        totalBytes += count
        return text
    }

    private fun countEntry(path: Path) {
        if (++entries > policy.maxEntries) unavailable(OwnedSourceCaptureFailure.ENTRIES_EXCEEDED, path)
    }

    private fun attributes(directory: SecureDirectoryStream<Path>): BasicFileAttributes =
        directory.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes()

    private fun attributes(directory: SecureDirectoryStream<Path>, name: Path): BasicFileAttributes =
        directory.getFileAttributeView(name, BasicFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes()

    private fun requireIdentity(attrs: BasicFileAttributes, path: Path) {
        if (attrs.fileKey() == null) unavailable(OwnedSourceCaptureFailure.OWNERSHIP_UNAVAILABLE, path)
    }

    private fun sameAncestorDirectory(a: BasicFileAttributes, b: BasicFileAttributes): Boolean =
        a.fileKey() != null && a.fileKey() == b.fileKey() && a.isDirectory && b.isDirectory &&
            !a.isSymbolicLink && !b.isSymbolicLink

    private fun sameIdentity(a: BasicFileAttributes, b: BasicFileAttributes): Boolean =
        a.fileKey() != null && a.fileKey() == b.fileKey() && a.size() == b.size() &&
            a.lastModifiedTime() == b.lastModifiedTime() && a.isDirectory == b.isDirectory &&
            a.isRegularFile == b.isRegularFile && !b.isSymbolicLink

    private fun languageId(path: Path): String? {
        val extension = path.fileName.toString().substringAfterLast('.', "")
        return extensions[if (caseSensitiveExtensions) extension else extension.lowercase()]
    }

    private fun unavailable(code: OwnedSourceCaptureFailure, path: Path): Nothing = throw OwnedSourceCaptureException(code, path)
}


/** Resource limits for a single operational source capture; callers may tighten them. */
data class SourceCapturePolicy(
    val maxFileBytes: Int = 2 * 1024 * 1024,
    val maxTotalBytes: Int = 64 * 1024 * 1024,
    val maxSources: Int = 4_096,
    val maxEntries: Int = 25_000,
    val maxDepth: Int = 64,
) {
    init {
        require(maxFileBytes in 1..2 * 1024 * 1024)
        require(maxTotalBytes in 1..64 * 1024 * 1024)
        require(maxSources in 1..4_096)
        require(maxEntries in 1..25_000)
        require(maxDepth in 1..64)
    }
}

enum class OwnedSourceCaptureFailure {
    SYMBOLIC_LINK, FILE_BYTES_EXCEEDED, TOTAL_BYTES_EXCEEDED, SOURCES_EXCEEDED,
    ENTRIES_EXCEEDED, DEPTH_EXCEEDED, INVALID_UTF8, INPUT_CHANGED,
    OWNERSHIP_UNAVAILABLE, IO_UNAVAILABLE,
}

/** A failed capture never carries a partial inventory. */
class OwnedSourceCaptureException(
    val code: OwnedSourceCaptureFailure,
    val path: Path,
    cause: Throwable? = null,
) : IOException("${code.name}: Source capture unavailable at $path", cause)
