package org.refactorkit.c

import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.OwnedSourceCaptureException
import org.refactorkit.core.OwnedSourceInventoryReader
import org.refactorkit.core.OwnedWorkspaceTextReadout
import org.refactorkit.core.SourceFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitOption
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet

/**
 * Captures only a regular root CMakeLists.txt as hash-bound auxiliary input.
 * Never invokes CMake or a command recorded in a project manifest. Unsupported,
 * oversized or unsafe files remain uncaptured; mutation planners must refuse
 * while a manifest exists but is absent from the tracked snapshot.
 */
object CMakeManifestCapture {
    private val MANIFEST = Path.of("CMakeLists.txt")
    private const val MAX_BYTES = 512 * 1024
    private const val MAX_MANIFEST_SEARCH_ENTRIES = 25_000
    private const val MAX_MANIFEST_SEARCH_DEPTH = 64

    /**
     * Fail closed before managed apply if *any* workspace CMake project could
     * affect the candidate, including a nested manifest introduced after preview.
     * Never follows symlinks, skips scanner-ignored directories, and treats
     * traversal failure or resource-limit exhaustion as unknown authority.
     */
    fun hasPotentialCMakeAuthority(snapshot: ProjectSnapshot): Boolean =
        findManifestOrUnknown(snapshot, allowRegularRootManifest = false)

    /** Any nested CMake input or incomplete traversal blocks the qualified root-only apply cell. */
    fun hasUncapturedCMakeAuthority(snapshot: ProjectSnapshot): Boolean =
        findManifestOrUnknown(snapshot, allowRegularRootManifest = true)

    private fun findManifestOrUnknown(snapshot: ProjectSnapshot, allowRegularRootManifest: Boolean): Boolean {
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return true
        var unknownOrPresent = false
        var visited = 0
        return try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption::class.java),
                MAX_MANIFEST_SEARCH_DEPTH, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (dir != root && dir.fileName.toString() in snapshot.ignoredDirectories) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        if (++visited > MAX_MANIFEST_SEARCH_ENTRIES) {
                            unknownOrPresent = true
                            return FileVisitResult.TERMINATE
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (++visited > MAX_MANIFEST_SEARCH_ENTRIES || attrs.isDirectory ||
                            (file.fileName.toString() == MANIFEST.toString() &&
                                (!allowRegularRootManifest || file != root.resolve(MANIFEST) || !attrs.isRegularFile))) {
                            unknownOrPresent = true
                            return FileVisitResult.TERMINATE
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                        unknownOrPresent = true
                        return FileVisitResult.TERMINATE
                    }
                })
            unknownOrPresent
        } catch (_: java.io.IOException) {
            true
        } catch (_: SecurityException) {
            true
        }
    }

    fun capture(snapshot: ProjectSnapshot): ProjectSnapshot {
        if (snapshot.files.none { it.languageId == "c" }) return snapshot
        if (snapshot.trackedFiles.any { it.path.normalize() == MANIFEST }) return snapshot
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        val input = try {
            OwnedSourceInventoryReader.readWorkspaceTextInput(root, MANIFEST, MAX_BYTES)
        } catch (_: OwnedSourceCaptureException) {
            return snapshot
        }
        val content = when (input) {
            OwnedWorkspaceTextReadout.Absent -> return snapshot
            is OwnedWorkspaceTextReadout.Captured -> input.content
        }
        return snapshot.copy(auxiliaryFiles = snapshot.auxiliaryFiles + SourceFile(MANIFEST, content, "cmake"))
    }
}
