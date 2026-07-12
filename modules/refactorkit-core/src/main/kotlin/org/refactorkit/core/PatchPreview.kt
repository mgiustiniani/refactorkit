package org.refactorkit.core

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

class PatchPreviewRenderer(
    private val workspaceRoot: Path,
) {
    fun render(plan: PatchPlan): String = buildString {
        appendLine("Operation: ${plan.operation}")
        appendLine("Status: ${plan.status}")
        plan.refusalCode?.let { appendLine("Refusal code: $it") }
        appendLine(plan.summary)
        appendLine("Evidence: ${plan.evidence}")
        if (plan.warnings.isNotEmpty()) {
            appendLine()
            appendLine("Warnings:")
            plan.warnings.forEach { appendLine("- $it") }
        }
        if (plan.diagnosticsAfterPreview.isNotEmpty()) {
            appendLine()
            appendLine("Post-edit diagnostics:")
            plan.diagnosticsAfterPreview.forEach { d ->
                val loc = d.location?.let { " at ${it.path}:${it.range.start.line + 1}" } ?: ""
                appendLine("  [${d.severity}]${d.code?.let { " ($it)" } ?: ""} ${d.message}$loc")
            }
        }
        appendLine()
        appendLine("Affected files:")
        plan.affectedFiles.sortedBy { it.toString() }.forEach { appendLine("- $it") }
        appendLine()
        appendLine("Patch:")
        plan.workspaceEdit.edits.forEach { edit ->
            append(renderEdit(edit))
            if (!endsWith("\n")) appendLine()
        }
    }

    private fun renderEdit(edit: FileEdit): String = when (edit) {
        is FileEdit.Create -> "create ${edit.path}\n"
        is FileEdit.Delete -> "delete ${edit.path}\n"
        is FileEdit.Rename -> "rename ${edit.path} -> ${edit.newPath}\n"
        is FileEdit.Modify -> renderModify(edit)
    }

    private fun renderModify(edit: FileEdit.Modify): String {
        val absolute = workspaceRoot.toAbsolutePath().normalize().resolve(edit.path).normalize()
        val before = if (absolute.exists()) absolute.readText() else ""
        val after = TextEdits.apply(before, edit.textEdits)
        return simpleUnifiedDiff(edit.path.toString(), before, after)
    }

    private fun simpleUnifiedDiff(path: String, before: String, after: String): String {
        val beforeLines = before.lines()
        val afterLines = after.lines()
        return buildString {
            appendLine("--- a/$path")
            appendLine("+++ b/$path")
            appendLine("@@")
            val max = maxOf(beforeLines.size, afterLines.size)
            for (index in 0 until max) {
                val oldLine = beforeLines.getOrNull(index)
                val newLine = afterLines.getOrNull(index)
                when {
                    oldLine == newLine && oldLine != null -> appendLine(" $oldLine")
                    oldLine != null && newLine != null -> {
                        appendLine("-$oldLine")
                        appendLine("+$newLine")
                    }
                    oldLine != null -> appendLine("-$oldLine")
                    newLine != null -> appendLine("+$newLine")
                }
            }
        }
    }
}
