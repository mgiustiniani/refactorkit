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
        plan.affectedFiles.sortedBy(ProtocolPath::serialize).forEach { appendLine("- ${ProtocolPath.serialize(it)}") }
        appendLine()
        appendLine("Patch:")
        val staged = mutableMapOf<Path, String?>()
        fun content(path: Path): String? {
            val normalized = path.normalize()
            if (normalized in staged) return staged[normalized]
            val absolute = workspaceRoot.toAbsolutePath().normalize().resolve(path).normalize()
            return (if (absolute.exists()) absolute.readText() else null).also { staged[normalized] = it }
        }
        plan.workspaceEdit.edits.forEach { edit ->
            val rendered = when (edit) {
                is FileEdit.Create -> {
                    staged[edit.path.normalize()] = edit.content
                    "create ${ProtocolPath.serialize(edit.path)}\n"
                }
                is FileEdit.Delete -> {
                    staged[edit.path.normalize()] = null
                    "delete ${ProtocolPath.serialize(edit.path)}\n"
                }
                is FileEdit.Rename -> {
                    staged[edit.newPath.normalize()] = content(edit.path)
                    staged[edit.path.normalize()] = null
                    "rename ${ProtocolPath.serialize(edit.path)} -> ${ProtocolPath.serialize(edit.newPath)}\n"
                }
                is FileEdit.Modify -> {
                    val before = content(edit.path).orEmpty()
                    val after = TextEdits.apply(before, edit.textEdits)
                    staged[edit.path.normalize()] = after
                    simpleUnifiedDiff(ProtocolPath.serialize(edit.path), before, after)
                }
            }
            append(rendered)
            if (!endsWith("\n")) appendLine()
        }
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
