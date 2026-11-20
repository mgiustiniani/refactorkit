package org.refactorkit.c

import org.refactorkit.core.OwnedSourceCaptureException
import org.refactorkit.core.OwnedSourceCaptureFailure
import org.refactorkit.core.OwnedSourceInventoryReader
import org.refactorkit.core.SourceCapturePolicy
import org.refactorkit.core.SourceFile
import java.io.IOException
import java.nio.file.Path

internal data class CSourceInventory(val sources: List<SourceFile>, val regularPaths: List<Path>)

/** C compatibility adapter over the language-neutral owned source reader. */
internal class CSourceSnapshotReader(private val policy: CProjectScanPolicy) {
    fun capture(root: Path): CSourceInventory = try {
        val inventory = OwnedSourceInventoryReader(
            mapOf("c" to "c", "h" to "c", "cc" to "cpp", "cpp" to "cpp", "cxx" to "cpp",
                "hh" to "cpp", "hpp" to "cpp", "hxx" to "cpp", "m" to "objective-c", "mm" to "objective-c"),
            SourceCapturePolicy(policy.maxFileBytes, policy.maxTotalBytes, policy.maxSources, policy.maxEntries, policy.maxDepth),
            caseSensitiveExtensions = false,
        ).capture(root)
        CSourceInventory(inventory.sources, inventory.regularPaths)
    } catch (failure: OwnedSourceCaptureException) {
        val code = when (failure.code) {
            OwnedSourceCaptureFailure.SYMBOLIC_LINK -> CProjectScanFailure.SYMBOLIC_LINK
            OwnedSourceCaptureFailure.FILE_BYTES_EXCEEDED -> CProjectScanFailure.FILE_BYTES_EXCEEDED
            OwnedSourceCaptureFailure.TOTAL_BYTES_EXCEEDED -> CProjectScanFailure.TOTAL_BYTES_EXCEEDED
            OwnedSourceCaptureFailure.SOURCES_EXCEEDED -> CProjectScanFailure.SOURCES_EXCEEDED
            OwnedSourceCaptureFailure.ENTRIES_EXCEEDED -> CProjectScanFailure.ENTRIES_EXCEEDED
            OwnedSourceCaptureFailure.DEPTH_EXCEEDED -> CProjectScanFailure.DEPTH_EXCEEDED
            OwnedSourceCaptureFailure.INVALID_UTF8 -> CProjectScanFailure.INVALID_UTF8
            OwnedSourceCaptureFailure.INPUT_CHANGED -> CProjectScanFailure.INPUT_CHANGED
            OwnedSourceCaptureFailure.OWNERSHIP_UNAVAILABLE -> CProjectScanFailure.OWNERSHIP_UNAVAILABLE
            OwnedSourceCaptureFailure.IO_UNAVAILABLE -> CProjectScanFailure.IO_UNAVAILABLE
        }
        throw CProjectScanException(code, failure.path, failure)
    }
}

/** Limits may be tightened by a caller, never widened beyond the qualified profile. */
data class CProjectScanPolicy(
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

enum class CProjectScanFailure {
    SYMBOLIC_LINK, FILE_BYTES_EXCEEDED, TOTAL_BYTES_EXCEEDED, SOURCES_EXCEEDED,
    ENTRIES_EXCEEDED, DEPTH_EXCEEDED, INVALID_UTF8, INPUT_CHANGED,
    OWNERSHIP_UNAVAILABLE, IO_UNAVAILABLE,
}

/** No partial ProjectSnapshot accompanies a failed structural capture. */
class CProjectScanException(
    val code: CProjectScanFailure,
    val path: Path,
    cause: Throwable? = null,
) : IOException("${code.name}: C structural source capture unavailable at $path", cause)
