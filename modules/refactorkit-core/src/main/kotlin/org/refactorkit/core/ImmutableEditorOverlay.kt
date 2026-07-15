package org.refactorkit.core

import java.nio.file.Path
import java.security.MessageDigest

/** One versioned unsaved document supplied by an editor without filesystem writes. */
data class ImmutableEditorOverlayDocument(
    val path: Path,
    val version: Long,
    val content: String,
)

/**
 * Validated immutable editor view derived from one exact saved ProjectSnapshot.
 * The overlay never mutates the saved snapshot or workspace filesystem.
 */
class ImmutableEditorOverlay private constructor(
    val baseSnapshotHash: String,
    val providerSnapshot: ProjectSnapshot,
    val documents: List<ImmutableEditorOverlayDocument>,
    val overlayHash: String,
    val totalUtf8Bytes: Long,
) {
    private val byPath = documents.associateBy { it.path.normalize() }

    fun document(path: Path): ImmutableEditorOverlayDocument? = byPath[path.normalize()]

    fun authority(path: Path): SemanticDocumentAuthority? = document(path)?.let { document ->
        SemanticDocumentAuthority(
            path = document.path.normalize(),
            mode = SemanticDocumentMode.IMMUTABLE_EDITOR_OVERLAY,
            contentSha256 = sha256(document.content.toByteArray(Charsets.UTF_8)),
            version = document.version,
        )
    }

    companion object {
        fun create(
            savedSnapshot: ProjectSnapshot,
            documents: List<ImmutableEditorOverlayDocument>,
            expectedLanguageId: String? = null,
            maxDocuments: Int = ProtocolLimits.MAX_EDITOR_OVERLAY_DOCUMENTS,
            maxBytes: Long = ProtocolLimits.MAX_EDITOR_OVERLAY_BYTES,
        ): ImmutableEditorOverlay {
            require(maxDocuments in 1..ProtocolLimits.MAX_SEMANTIC_OVERLAY_FILES) {
                "editor overlay document limit is outside the safe range"
            }
            require(maxBytes in 1..ProtocolLimits.MAX_SEMANTIC_OVERLAY_BYTES) {
                "editor overlay byte limit is outside the safe range"
            }
            require(documents.size in 1..maxDocuments) {
                "editor overlay document count is outside the bounded range"
            }
            val savedByPath = savedSnapshot.files.associateBy { normalizePath(it.path) }
            val normalized = documents.map { document ->
                val path = normalizePath(document.path)
                require(document.version >= 0) { "editor overlay document version must be non-negative" }
                require('\u0000' !in document.content) { "editor overlay content contains NUL" }
                val bytes = document.content.toByteArray(Charsets.UTF_8)
                require(bytes.size <= ProtocolLimits.MAX_SOURCE_FILE_BYTES) {
                    "editor overlay document exceeds the source-file byte limit"
                }
                val saved = savedByPath[path] ?: error("editor overlay path is not present in the saved snapshot")
                require(expectedLanguageId == null || saved.languageId == expectedLanguageId) {
                    "editor overlay document language does not match the request"
                }
                ImmutableEditorOverlayDocument(path, document.version, document.content)
            }.sortedBy { ProtocolPath.serialize(it.path) }
            require(normalized.map { it.path }.distinct().size == normalized.size) {
                "editor overlay contains duplicate paths"
            }
            val totalBytes = normalized.sumOf { it.content.toByteArray(Charsets.UTF_8).size.toLong() }
            require(totalBytes <= maxBytes) { "editor overlay exceeds $maxBytes UTF-8 bytes" }

            val replacements = normalized.associateBy { it.path }
            val providerFiles = savedSnapshot.files.map { source ->
                replacements[normalizePath(source.path)]?.let { replacement ->
                    SourceFile(replacement.path, replacement.content, source.languageId)
                } ?: source
            }.sortedBy { it.path.toString() }
            return ImmutableEditorOverlay(
                baseSnapshotHash = savedSnapshot.hash,
                providerSnapshot = savedSnapshot.copy(files = providerFiles),
                documents = normalized,
                overlayHash = computeHash(normalized),
                totalUtf8Bytes = totalBytes,
            )
        }

        fun computeHash(documents: List<ImmutableEditorOverlayDocument>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            documents.sortedBy { ProtocolPath.serialize(it.path.normalize()) }.forEach { document ->
                digest.update(ProtocolPath.serialize(normalizePath(document.path)).toByteArray(Charsets.UTF_8))
                digest.update(0)
                digest.update(document.version.toString().toByteArray(Charsets.UTF_8))
                digest.update(0)
                digest.update(document.content.toByteArray(Charsets.UTF_8))
                digest.update(0)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun normalizePath(path: Path): Path {
            val normalized = path.normalize()
            require(!normalized.isAbsolute && normalized.toString().isNotBlank() &&
                !normalized.startsWith("..") && !WINDOWS_DRIVE.containsMatchIn(normalized.toString())) {
                "editor overlay path is unsafe"
            }
            return normalized
        }

        private val WINDOWS_DRIVE = Regex("^[A-Za-z]:")
        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
