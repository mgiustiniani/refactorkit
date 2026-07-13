package org.refactorkit.core

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.DosFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.Comparator

/** Materialized source-only workspace used as the root exposed to an external semantic process. */
class SemanticWorkspaceOverlay private constructor(
    val root: Path,
    private val workspaceRoot: Path,
    private val sourceHashes: Map<Path, String>,
) : AutoCloseable {
    fun toOverlayPath(workspacePath: Path): Path? {
        val normalized = if (workspacePath.isAbsolute) workspacePath.normalize() else workspaceRoot.resolve(workspacePath).normalize()
        if (!normalized.startsWith(workspaceRoot) || normalized == workspaceRoot) return null
        return root.resolve(workspaceRoot.relativize(normalized)).normalize()
    }

    fun toWorkspacePath(overlayPath: Path): Path? {
        val normalized = overlayPath.toAbsolutePath().normalize()
        if (!normalized.startsWith(root) || normalized == root) return null
        return workspaceRoot.resolve(root.relativize(normalized)).normalize()
    }

    /** Detects a server that wrote, removed, or replaced source material in its overlay. */
    fun verifySourcesUnchanged(): List<Diagnostic> = sourceHashes.mapNotNull { (relative, expected) ->
        val path = root.resolve(relative).normalize()
        val actual = if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            sha256(Files.readAllBytes(path))
        } else null
        if (actual == expected) null else Diagnostic(
            message = "External semantic process modified overlay source '${ProtocolPath.serialize(relative)}'",
            severity = Diagnostic.Severity.ERROR,
            code = "semantic.overlaySourceModified",
            evidence = DiagnosticEvidence.STRUCTURAL,
            category = DiagnosticCategory.SAFETY,
        )
    }.sortedBy { it.message }

    override fun close() {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path ->
                makeWritable(path)
                runCatching { Files.deleteIfExists(path) }
            }
        }
    }

    companion object {
        fun create(
            snapshot: ProjectSnapshot,
            maxFiles: Int = ProtocolLimits.MAX_SEMANTIC_OVERLAY_FILES,
            maxBytes: Long = ProtocolLimits.MAX_SEMANTIC_OVERLAY_BYTES,
        ): SemanticWorkspaceOverlay {
            require(maxFiles in 1..1_000_000) { "semantic overlay file limit is outside safe range" }
            require(maxBytes in 1..4L * 1024L * 1024L * 1024L) { "semantic overlay byte limit is outside safe range" }
            require(snapshot.files.size <= maxFiles) { "semantic overlay exceeds $maxFiles source files" }
            val workspaceRoot = snapshot.workspace.root.toAbsolutePath().normalize()
            val overlayRoot = Files.createTempDirectory("refactorkit-semantic-overlay-").toAbsolutePath().normalize()
            val hashes = linkedMapOf<Path, String>()
            var totalBytes = 0L
            try {
                snapshot.files.sortedBy { it.path.toString() }.forEach { source ->
                    val relative = normalizeRelative(source.path, workspaceRoot)
                    require(relative !in hashes) { "semantic overlay contains duplicate source path" }
                    val bytes = source.content.toByteArray(Charsets.UTF_8)
                    totalBytes += bytes.size
                    require(totalBytes <= maxBytes) { "semantic overlay exceeds $maxBytes source bytes" }
                    val target = overlayRoot.resolve(relative).normalize()
                    require(target.startsWith(overlayRoot)) { "semantic overlay source escapes root" }
                    Files.createDirectories(target.parent)
                    Files.write(target, bytes)
                    makeReadOnly(target)
                    hashes[relative] = sha256(bytes)
                }
                return SemanticWorkspaceOverlay(overlayRoot, workspaceRoot, hashes)
            } catch (failure: Throwable) {
                runCatching {
                    Files.walk(overlayRoot).use { paths ->
                        paths.sorted(Comparator.reverseOrder()).forEach { path ->
                            makeWritable(path)
                            runCatching { Files.deleteIfExists(path) }
                        }
                    }
                }
                throw failure
            }
        }

        private fun normalizeRelative(path: Path, workspaceRoot: Path): Path {
            val relative = if (path.isAbsolute) {
                val normalized = path.normalize()
                require(normalized.startsWith(workspaceRoot)) { "semantic overlay source is outside workspace" }
                workspaceRoot.relativize(normalized)
            } else path.normalize()
            require(relative.toString().isNotBlank() && !relative.isAbsolute && !relative.startsWith("..")) {
                "semantic overlay source path is unsafe"
            }
            return relative
        }

        private fun makeReadOnly(path: Path) {
            runCatching {
                val view = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                    ?: return@runCatching
                val permissions = view.readAttributes().permissions().filterNotTo(mutableSetOf()) {
                    it in setOf(PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE)
                }
                view.setPermissions(permissions)
            }
            runCatching {
                Files.getFileAttributeView(path, DosFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)?.setReadOnly(true)
            }
        }

        private fun makeWritable(path: Path) {
            runCatching {
                Files.getFileAttributeView(path, DosFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)?.setReadOnly(false)
            }
            runCatching {
                val view = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                    ?: return@runCatching
                val permissions = view.readAttributes().permissions().toMutableSet()
                permissions += PosixFilePermission.OWNER_WRITE
                view.setPermissions(permissions)
            }
        }

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
