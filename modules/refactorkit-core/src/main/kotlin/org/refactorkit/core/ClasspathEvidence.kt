package org.refactorkit.core

import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.stream.Collectors

enum class ClasspathEvidenceKind {
    ENTRY,
    DECLARATION_FILE,
    JAR_DIRECTORY,
    LOCAL_REPOSITORY_ARTIFACT,
    SYSTEM_PATH_ARTIFACT,
    EFFECTIVE_MODEL_INPUT,
    IMPORTED_BOM,
}

data class ClasspathEvidence(
    val path: Path,
    val kind: ClasspathEvidenceKind,
    val fingerprint: String,
) {
    companion object {
        fun capture(workspaceRoot: Path, path: Path, kind: ClasspathEvidenceKind): ClasspathEvidence {
            val normalizedPath = path.normalize()
            val absolute = if (normalizedPath.isAbsolute) normalizedPath else workspaceRoot.resolve(normalizedPath).normalize()
            return ClasspathEvidence(normalizedPath, kind, fingerprint(absolute, kind))
        }

        fun fingerprint(path: Path, kind: ClasspathEvidenceKind): String {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return if (kind == ClasspathEvidenceKind.LOCAL_REPOSITORY_ARTIFACT) {
                    "absent-nofollow:${hashNoFollowPath(path)}"
                } else {
                    "missing"
                }
            }
            return when (kind) {
                ClasspathEvidenceKind.ENTRY -> when {
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> hashFile(path, "file")
                    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> hashDirectory(path) { true }
                    else -> error("Classpath entry is neither a regular file nor directory: $path")
                }
                ClasspathEvidenceKind.LOCAL_REPOSITORY_ARTIFACT -> when {
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> hashFile(path, kind.name.lowercase())
                    else -> "present-nonregular-nofollow:${hashNoFollowPath(path)}"
                }
                ClasspathEvidenceKind.DECLARATION_FILE,
                ClasspathEvidenceKind.SYSTEM_PATH_ARTIFACT,
                ClasspathEvidenceKind.EFFECTIVE_MODEL_INPUT,
                ClasspathEvidenceKind.IMPORTED_BOM -> {
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        error("Classpath/model evidence is not a no-follow regular file: $path")
                    }
                    hashFile(path, kind.name.lowercase())
                }
                ClasspathEvidenceKind.JAR_DIRECTORY -> {
                    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                        error("Classpath JAR location is not a no-follow directory: $path")
                    }
                    hashDirectory(path) { it.fileName.toString().endsWith(".jar", ignoreCase = true) }
                }
            }
        }

        private fun hashNoFollowPath(path: Path): String {
            val absolute = path.toAbsolutePath().normalize()
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("no-follow-path\u0000$absolute\u0000".toByteArray(Charsets.UTF_8))
            var current = requireNotNull(absolute.root) { "No filesystem root for $absolute" }
            fun record(component: String, candidate: Path) {
                digest.update(component.toByteArray(Charsets.UTF_8))
                digest.update(0)
                val attributes = runCatching {
                    Files.readAttributes(
                        candidate,
                        BasicFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                }.getOrNull()
                val state = when {
                    attributes == null -> "ABSENT"
                    attributes.isSymbolicLink -> "SYMLINK:${runCatching { Files.readSymbolicLink(candidate) }.getOrNull()}"
                    attributes.isDirectory -> "DIRECTORY:${attributes.fileKey()}"
                    attributes.isRegularFile -> "FILE:${attributes.fileKey()}"
                    else -> "OTHER:${attributes.fileKey()}"
                }
                digest.update(state.toByteArray(Charsets.UTF_8))
                digest.update(0)
            }
            record("<root>", current)
            absolute.forEach { component ->
                current = current.resolve(component)
                record(component.toString(), current)
            }
            return digest.hexDigest()
        }

        private fun hashDirectory(root: Path, include: (Path) -> Boolean): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("directory\u0000".toByteArray(Charsets.UTF_8))
            val files = Files.walk(root, FileVisitOption.FOLLOW_LINKS).use { stream ->
                stream.filter { Files.isRegularFile(it) && include(it) }
                    .collect(Collectors.toList())
                    .sortedBy { root.relativize(it).toString() }
            }
            files.forEach { file ->
                digest.update(root.relativize(file).toString().toByteArray(Charsets.UTF_8))
                digest.update(0)
                updateFileContent(digest, file)
                digest.update(0)
            }
            return digest.hexDigest()
        }

        private fun hashFile(path: Path, marker: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("$marker\u0000".toByteArray(Charsets.UTF_8))
            updateFileContent(digest, path)
            return digest.hexDigest()
        }

        private fun updateFileContent(digest: MessageDigest, path: Path) {
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
        }

        private fun MessageDigest.hexDigest(): String = digest().joinToString("") { "%02x".format(it) }
    }
}
