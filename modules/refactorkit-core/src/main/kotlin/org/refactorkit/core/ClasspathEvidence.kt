package org.refactorkit.core

import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.stream.Collectors

enum class ClasspathEvidenceKind {
    ENTRY,
    DECLARATION_FILE,
    JAR_DIRECTORY,
    LOCAL_REPOSITORY_ARTIFACT,
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
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return "missing"
            return when (kind) {
                ClasspathEvidenceKind.ENTRY -> when {
                    Files.isRegularFile(path) -> hashFile(path, "file")
                    Files.isDirectory(path) -> hashDirectory(path) { true }
                    else -> error("Classpath entry is neither a regular file nor directory: $path")
                }
                ClasspathEvidenceKind.DECLARATION_FILE,
                ClasspathEvidenceKind.LOCAL_REPOSITORY_ARTIFACT,
                ClasspathEvidenceKind.EFFECTIVE_MODEL_INPUT,
                ClasspathEvidenceKind.IMPORTED_BOM -> {
                    if (!Files.isRegularFile(path)) error("Classpath/model evidence is not a regular file: $path")
                    hashFile(path, kind.name.lowercase())
                }
                ClasspathEvidenceKind.JAR_DIRECTORY -> {
                    if (!Files.isDirectory(path)) error("Classpath JAR location is not a directory: $path")
                    hashDirectory(path) { it.fileName.toString().endsWith(".jar", ignoreCase = true) }
                }
            }
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
