package org.refactorkit.core

import java.nio.file.Path

/**
 * Applies a workspace edit to an immutable source snapshot without filesystem writes.
 *
 * Modify entries in each structural segment use one original-content coordinate
 * space, matching [PatchEngine] normalization. This is used to evaluate a
 * multi-step plan against one evolving staged workspace before a single commit.
 */
object WorkspaceEditSimulator {
    fun apply(snapshot: ProjectSnapshot, workspaceEdit: WorkspaceEdit): ProjectSnapshot {
        val working = snapshot.files.associateBy { it.path.normalize() }.toMutableMap()
        normalize(workspaceEdit).edits.forEach { edit ->
            val path = edit.path.normalize()
            when (edit) {
                is FileEdit.Create -> {
                    require(edit.overwrite || path !in working) { "Create target already exists: $path" }
                    val previousLanguage = working[path]?.languageId
                    working[path] = SourceFile(path, edit.content, previousLanguage ?: languageId(snapshot, path))
                }
                is FileEdit.Delete -> requireNotNull(working.remove(path)) { "Delete source is missing: $path" }
                is FileEdit.Rename -> {
                    val target = edit.newPath.normalize()
                    require(target !in working) { "Rename target already exists: $target" }
                    val source = requireNotNull(working.remove(path)) { "Rename source is missing: $path" }
                    working[target] = source.copy(path = target)
                }
                is FileEdit.Modify -> {
                    val source = requireNotNull(working[path]) { "Modify source is missing: $path" }
                    val sorted = edit.textEdits.sortedWith(
                        compareBy<TextEdit> { it.range.start.line }.thenBy { it.range.start.character },
                    )
                    require(sorted.zipWithNext().none { (left, right) -> left.range.overlaps(right.range) }) {
                        "Overlapping text edits in $path"
                    }
                    working[path] = source.copy(content = TextEdits.apply(source.content, edit.textEdits))
                }
            }
        }
        return snapshot.copy(files = working.values.sortedBy { it.path.toString() })
    }

    fun normalize(workspaceEdit: WorkspaceEdit): WorkspaceEdit {
        val normalized = mutableListOf<FileEdit>()
        val pending = linkedMapOf<Path, MutableList<TextEdit>>()
        fun flushPending() {
            pending.forEach { (path, edits) -> normalized.add(FileEdit.Modify(path, edits.toList())) }
            pending.clear()
        }
        workspaceEdit.edits.forEach { edit ->
            when (edit) {
                is FileEdit.Modify -> pending.getOrPut(edit.path.normalize()) { mutableListOf() }.addAll(edit.textEdits)
                is FileEdit.Create, is FileEdit.Delete, is FileEdit.Rename -> {
                    flushPending()
                    normalized.add(edit)
                }
            }
        }
        flushPending()
        return WorkspaceEdit(normalized)
    }

    private fun languageId(snapshot: ProjectSnapshot, path: Path): String {
        val extension = path.fileName?.toString()?.substringAfterLast('.', missingDelimiterValue = "").orEmpty()
        return snapshot.files.firstOrNull {
            it.path.fileName?.toString()?.substringAfterLast('.', missingDelimiterValue = "") == extension
        }?.languageId ?: extension
    }
}
