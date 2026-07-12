package org.refactorkit.core

import java.nio.file.Path

enum class FileChangeKind { CREATE, MODIFY, MOVE, DELETE }

data class PatchDiffHunk(
    val oldStart: Int,
    val oldLines: Int,
    val newStart: Int,
    val newLines: Int,
    val lines: List<String>,
    val truncated: Boolean = false,
)

data class PatchFileDiff(
    val change: FileChangeKind,
    val path: Path,
    val previousPath: Path? = null,
    val hunks: List<PatchDiffHunk> = emptyList(),
    val truncated: Boolean = false,
)

data class PatchDiffResult(
    val renderedDiff: String,
    val files: List<PatchFileDiff>,
    val truncated: Boolean,
    val truncationReasons: List<String>,
)

/** Bounded, deterministic diff projection of an exact [WorkspaceEdit]. */
object PatchDiffRenderer {
    fun render(
        snapshot: ProjectSnapshot,
        workspaceEdit: WorkspaceEdit,
        maxBytes: Int = ProtocolLimits.MAX_PREVIEW_DIFF_BYTES,
        maxFiles: Int = ProtocolLimits.MAX_PREVIEW_DIFF_FILES,
        maxHunksPerFile: Int = ProtocolLimits.MAX_PREVIEW_HUNKS_PER_FILE,
        maxLinesPerHunk: Int = ProtocolLimits.MAX_PREVIEW_LINES_PER_HUNK,
    ): PatchDiffResult {
        require(maxBytes > 0 && maxFiles > 0 && maxHunksPerFile > 0 && maxLinesPerHunk > 0)
        val working = snapshot.files.associate { it.path.normalize() to it.content }.toMutableMap()
        val raw = mutableListOf<RawDiff>()
        WorkspaceEditSimulator.normalize(workspaceEdit).edits.forEach { edit ->
            when (edit) {
                is FileEdit.Create -> {
                    val path = edit.path.normalize()
                    val before = working[path].orEmpty()
                    raw += RawDiff(FileChangeKind.CREATE, path, null, before, edit.content)
                    working[path] = edit.content
                }
                is FileEdit.Modify -> {
                    val path = edit.path.normalize()
                    val before = requireNotNull(working[path]) { "Modify source is missing: $path" }
                    val after = TextEdits.apply(before, edit.textEdits)
                    raw += RawDiff(FileChangeKind.MODIFY, path, null, before, after)
                    working[path] = after
                }
                is FileEdit.Delete -> {
                    val path = edit.path.normalize()
                    val before = requireNotNull(working.remove(path)) { "Delete source is missing: $path" }
                    raw += RawDiff(FileChangeKind.DELETE, path, null, before, "")
                }
                is FileEdit.Rename -> {
                    val previous = edit.path.normalize()
                    val path = edit.newPath.normalize()
                    val content = requireNotNull(working.remove(previous)) { "Rename source is missing: $previous" }
                    raw += RawDiff(FileChangeKind.MOVE, path, previous, content, content)
                    working[path] = content
                }
            }
        }

        val reasons = linkedSetOf<String>()
        val boundedFiles = raw
            .sortedWith(compareBy<RawDiff> { ProtocolPath.serialize(it.path) }.thenBy { it.change.name })
            .take(maxFiles)
        if (raw.size > maxFiles) reasons += "fileLimit:$maxFiles"
        var remainingBytes = maxBytes / 2
        val files = boundedFiles.map { item ->
            if (item.change == FileChangeKind.MOVE && item.before == item.after) {
                return@map PatchFileDiff(item.change, item.path, item.previousPath)
            }
            val rawHunk = changedHunk(item.before, item.after)
            val candidateLines = rawHunk.lines.take(maxLinesPerHunk)
            var lineTruncated = rawHunk.lines.size > candidateLines.size
            if (lineTruncated) reasons += "lineLimit:$maxLinesPerHunk"
            val accepted = mutableListOf<String>()
            candidateLines.forEach { line ->
                val bytes = line.toByteArray(Charsets.UTF_8).size + 1
                if (bytes <= remainingBytes) {
                    accepted += line
                    remainingBytes -= bytes
                } else {
                    lineTruncated = true
                    reasons += "byteLimit:$maxBytes"
                }
            }
            val hunk = rawHunk.copy(lines = accepted, truncated = lineTruncated)
            PatchFileDiff(
                change = item.change,
                path = item.path,
                previousPath = item.previousPath,
                hunks = listOf(hunk).take(maxHunksPerFile),
                truncated = lineTruncated,
            )
        }
        val structuredBytes = maxBytes / 2 - remainingBytes
        val renderedRaw = renderUnified(files, reasons.isNotEmpty())
        val rendered = truncateUtf8(renderedRaw, (maxBytes - structuredBytes).coerceAtLeast(1))
        if (rendered != renderedRaw) reasons += "byteLimit:$maxBytes"
        return PatchDiffResult(rendered, files, reasons.isNotEmpty(), reasons.toList())
    }

    private fun changedHunk(before: String, after: String): PatchDiffHunk {
        val old = logicalLines(before)
        val new = logicalLines(after)
        var prefix = 0
        while (prefix < old.size && prefix < new.size && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < old.size - prefix && suffix < new.size - prefix &&
            old[old.lastIndex - suffix] == new[new.lastIndex - suffix]
        ) suffix++
        val oldChanged = old.subList(prefix, old.size - suffix)
        val newChanged = new.subList(prefix, new.size - suffix)
        return PatchDiffHunk(
            oldStart = if (old.isEmpty()) 0 else prefix + 1,
            oldLines = oldChanged.size,
            newStart = if (new.isEmpty()) 0 else prefix + 1,
            newLines = newChanged.size,
            lines = oldChanged.map { "-$it" } + newChanged.map { "+$it" },
        )
    }

    private fun logicalLines(content: String): List<String> {
        if (content.isEmpty()) return emptyList()
        val normalized = content.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split('\n')
        return if (lines.lastOrNull().isNullOrEmpty()) lines.dropLast(1) else lines
    }

    private fun truncateUtf8(value: String, maxBytes: Int): String {
        var index = 0
        var bytes = 0
        val result = StringBuilder()
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val text = String(Character.toChars(codePoint))
            val size = text.toByteArray(Charsets.UTF_8).size
            if (bytes + size > maxBytes) break
            result.append(text)
            bytes += size
            index += Character.charCount(codePoint)
        }
        return result.toString()
    }

    private fun renderUnified(files: List<PatchFileDiff>, truncated: Boolean): String = buildString {
        files.forEach { file ->
            val oldPath = when (file.change) {
                FileChangeKind.CREATE -> "/dev/null"
                FileChangeKind.MOVE -> "a/${ProtocolPath.serialize(requireNotNull(file.previousPath))}"
                else -> "a/${ProtocolPath.serialize(file.path)}"
            }
            val newPath = when (file.change) {
                FileChangeKind.DELETE -> "/dev/null"
                else -> "b/${ProtocolPath.serialize(file.path)}"
            }
            appendLine("--- $oldPath")
            appendLine("+++ $newPath")
            file.hunks.forEach { hunk ->
                appendLine("@@ -${hunk.oldStart},${hunk.oldLines} +${hunk.newStart},${hunk.newLines} @@")
                hunk.lines.forEach(::appendLine)
                if (hunk.truncated) appendLine("... diff truncated ...")
            }
        }
        if (truncated) appendLine("... preview diff truncated by protocol limits ...")
    }

    private data class RawDiff(
        val change: FileChangeKind,
        val path: Path,
        val previousPath: Path?,
        val before: String,
        val after: String,
    )
}
