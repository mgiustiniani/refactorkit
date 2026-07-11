package org.refactorkit.core

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

class PatchEngine(
    private val workspaceRoot: Path,
) {
    private val normalizedRoot = workspaceRoot.toAbsolutePath().normalize()

    /** Validate a preview plan against the current snapshot hash before applying. */
    fun validate(plan: PatchPlan, currentSnapshotHash: String): List<Diagnostic> = buildList {
        if (plan.status != PatchStatus.PREVIEW) {
            add(Diagnostic("Only preview plans can be applied", Diagnostic.Severity.ERROR, code = "plan.notPreview"))
        }
        if (plan.snapshotHash != currentSnapshotHash) {
            add(Diagnostic("Project changed since preview; regenerate the plan", Diagnostic.Severity.ERROR, code = "snapshot.changed"))
        }
        addAll(validateEdits(plan.workspaceEdit))
        addAll(validateRuntimeState(plan.workspaceEdit))
    }

    /** Apply a validated preview plan. Aborts if snapshot hash mismatch. */
    fun apply(plan: PatchPlan, currentSnapshotHash: String): ApplyResult {
        val diagnostics = validate(plan, currentSnapshotHash)
        if (diagnostics.any { it.severity == Diagnostic.Severity.ERROR }) {
            return ApplyResult.Refused(diagnostics)
        }
        return applyEdit(plan.id, plan.snapshotHash, plan.workspaceEdit)
    }

    /**
     * Roll back a previously applied transaction.
     * Does NOT check the snapshot hash because the current state is necessarily
     * different from the pre-apply state.
     */
    fun rollback(transaction: Transaction): ApplyResult {
        val errors = validateEdits(transaction.rollbackEdit) + validateRuntimeState(transaction.rollbackEdit)
        if (errors.any { it.severity == Diagnostic.Severity.ERROR }) {
            return ApplyResult.Refused(errors)
        }
        return applyEdit(transaction.planId, snapshotHashBefore = "", transaction.rollbackEdit)
    }

    // ── private ──────────────────────────────────────────────────────────────

    private fun validateEdits(edit: WorkspaceEdit): List<Diagnostic> = buildList {
        edit.edits.forEach { fileEdit ->
            validateInsideWorkspace(fileEdit.path)?.let(::add)
            validateNoSymbolicLinkTraversal(fileEdit.path)?.let(::add)
            if (fileEdit is FileEdit.Rename) {
                validateInsideWorkspace(fileEdit.newPath)?.let(::add)
                validateNoSymbolicLinkTraversal(fileEdit.newPath)?.let(::add)
            }
            if (fileEdit is FileEdit.Modify) validateNoOverlaps(fileEdit)?.let(::add)
        }
    }

    private fun applyEdit(planId: PlanId, snapshotHashBefore: String, workspaceEdit: WorkspaceEdit): ApplyResult {
        val rollbackEdits = mutableListOf<FileEdit>()

        for (edit in workspaceEdit.edits) {
            val absolute = resolveInsideWorkspace(edit.path)
            when (edit) {
                is FileEdit.Create -> {
                    if (absolute.exists() && !edit.overwrite) {
                        return ApplyResult.Refused(listOf(Diagnostic(
                            "Refusing to overwrite existing file: ${edit.path}",
                            Diagnostic.Severity.ERROR, code = "file.exists",
                        )))
                    }
                    val previous = if (absolute.exists()) absolute.readText() else null
                    absolute.parent?.createDirectories()
                    absolute.writeText(edit.content)
                    rollbackEdits += if (previous == null) FileEdit.Delete(edit.path)
                    else FileEdit.Create(edit.path, previous, overwrite = true)
                }

                is FileEdit.Delete -> {
                    if (!absolute.exists()) {
                        return ApplyResult.Refused(listOf(Diagnostic(
                            "Cannot delete missing file: ${edit.path}",
                            Diagnostic.Severity.ERROR, code = "file.missing",
                        )))
                    }
                    rollbackEdits += FileEdit.Create(edit.path, absolute.readText(), overwrite = true)
                    Files.delete(absolute)
                }

                is FileEdit.Rename -> {
                    val absoluteNew = resolveInsideWorkspace(edit.newPath)
                    if (!absolute.exists()) {
                        return ApplyResult.Refused(listOf(Diagnostic(
                            "Cannot rename missing file: ${edit.path}",
                            Diagnostic.Severity.ERROR, code = "file.missing",
                        )))
                    }
                    if (absoluteNew.exists()) {
                        return ApplyResult.Refused(listOf(Diagnostic(
                            "Refusing to overwrite rename target: ${edit.newPath}",
                            Diagnostic.Severity.ERROR, code = "file.exists",
                        )))
                    }
                    absoluteNew.parent?.createDirectories()
                    Files.move(absolute, absoluteNew)
                    rollbackEdits += FileEdit.Rename(edit.newPath, edit.path)
                }

                is FileEdit.Modify -> {
                    if (!absolute.isRegularFile()) {
                        return ApplyResult.Refused(listOf(Diagnostic(
                            "Cannot modify missing file: ${edit.path}",
                            Diagnostic.Severity.ERROR, code = "file.missing",
                        )))
                    }
                    val previous = absolute.readText()
                    absolute.writeText(TextEdits.apply(previous, edit.textEdits))
                    rollbackEdits += FileEdit.Create(edit.path, previous, overwrite = true)
                }
            }
        }

        return ApplyResult.Applied(Transaction(
            planId = planId,
            snapshotHashBefore = snapshotHashBefore,
            rollbackEdit = WorkspaceEdit(rollbackEdits.asReversed()),
        ))
    }

    private fun validateInsideWorkspace(path: Path): Diagnostic? {
        val absolute = resolveInsideWorkspace(path)
        return if (!absolute.startsWith(normalizedRoot)) {
            Diagnostic(
                "Edit path is outside workspace root: $path",
                Diagnostic.Severity.ERROR, code = "path.outsideWorkspace",
            )
        } else null
    }

    private fun validateNoSymbolicLinkTraversal(path: Path): Diagnostic? {
        val absolute = resolveInsideWorkspace(path)
        if (!absolute.startsWith(normalizedRoot)) return null
        var current = normalizedRoot
        for (component in normalizedRoot.relativize(absolute)) {
            current = current.resolve(component)
            if (Files.isSymbolicLink(current)) {
                return Diagnostic(
                    "Edit path traverses a symbolic link: $path",
                    Diagnostic.Severity.ERROR,
                    code = "path.symbolicLink",
                )
            }
        }
        return null
    }

    /**
     * Simulate file existence/type transitions before the first workspace write.
     * This prevents a predictable later refusal from leaving earlier edits applied.
     */
    private fun validateRuntimeState(edit: WorkspaceEdit): List<Diagnostic> {
        val states = mutableMapOf<Path, VirtualFileState>()
        fun state(path: Path): VirtualFileState {
            val absolute = resolveInsideWorkspace(path)
            if (!absolute.startsWith(normalizedRoot)) return VirtualFileState(false, false)
            return states.getOrPut(absolute) {
                VirtualFileState(
                    exists = Files.exists(absolute, LinkOption.NOFOLLOW_LINKS),
                    regular = Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS),
                )
            }
        }
        val diagnostics = mutableListOf<Diagnostic>()
        edit.edits.forEach { fileEdit ->
            when (fileEdit) {
                is FileEdit.Create -> {
                    val current = state(fileEdit.path)
                    if (current.exists && !fileEdit.overwrite) {
                        diagnostics += Diagnostic(
                            "Refusing to overwrite existing file: ${fileEdit.path}",
                            Diagnostic.Severity.ERROR,
                            code = "file.exists",
                        )
                    } else {
                        current.exists = true
                        current.regular = true
                    }
                }
                is FileEdit.Delete -> {
                    val current = state(fileEdit.path)
                    if (!current.exists) {
                        diagnostics += Diagnostic(
                            "Cannot delete missing file: ${fileEdit.path}",
                            Diagnostic.Severity.ERROR,
                            code = "file.missing",
                        )
                    } else {
                        current.exists = false
                        current.regular = false
                    }
                }
                is FileEdit.Rename -> {
                    val source = state(fileEdit.path)
                    val target = state(fileEdit.newPath)
                    when {
                        !source.exists -> diagnostics += Diagnostic(
                            "Cannot rename missing file: ${fileEdit.path}",
                            Diagnostic.Severity.ERROR,
                            code = "file.missing",
                        )
                        target.exists -> diagnostics += Diagnostic(
                            "Refusing to overwrite rename target: ${fileEdit.newPath}",
                            Diagnostic.Severity.ERROR,
                            code = "file.exists",
                        )
                        else -> {
                            target.exists = true
                            target.regular = source.regular
                            source.exists = false
                            source.regular = false
                        }
                    }
                }
                is FileEdit.Modify -> {
                    val current = state(fileEdit.path)
                    if (!current.exists || !current.regular) {
                        diagnostics += Diagnostic(
                            "Cannot modify missing file: ${fileEdit.path}",
                            Diagnostic.Severity.ERROR,
                            code = "file.missing",
                        )
                    }
                }
            }
        }
        return diagnostics
    }

    private fun validateNoOverlaps(edit: FileEdit.Modify): Diagnostic? {
        val sorted = edit.textEdits.sortedWith(
            compareBy<TextEdit> { it.range.start.line }.thenBy { it.range.start.character },
        )
        val overlap = sorted.zipWithNext().firstOrNull { (a, b) -> a.range.overlaps(b.range) }
        return overlap?.let {
            Diagnostic("Overlapping text edits in ${edit.path}", Diagnostic.Severity.ERROR, code = "edit.overlap")
        }
    }

    private fun resolveInsideWorkspace(path: Path): Path = normalizedRoot.resolve(path).normalize()

    private data class VirtualFileState(
        var exists: Boolean,
        var regular: Boolean,
    )
}

sealed interface ApplyResult {
    data class Applied(val transaction: Transaction) : ApplyResult
    data class Refused(val diagnostics: List<Diagnostic>) : ApplyResult
}
