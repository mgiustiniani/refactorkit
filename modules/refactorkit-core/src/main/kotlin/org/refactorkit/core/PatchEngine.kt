package org.refactorkit.core

import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
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

    /**
     * Apply a preview while holding the workspace's one-writer lock. The supplied
     * scan is treated as the pre-lock expectation and every initially affected
     * file is revalidated after lock acquisition before the first mutation.
     */
    fun apply(plan: PatchPlan, currentSnapshot: ProjectSnapshot): ApplyResult = withWorkspaceLock {
        val diagnostics = buildList {
            if (currentSnapshot.workspace.root.toAbsolutePath().normalize() != normalizedRoot) {
                add(Diagnostic(
                    "Snapshot workspace does not match PatchEngine workspace",
                    Diagnostic.Severity.ERROR,
                    code = "snapshot.workspaceMismatch",
                ))
            }
            addAll(validate(plan, currentSnapshot.hash))
            addAll(validateAffectedFilePreconditions(plan.workspaceEdit, currentSnapshot))
        }
        if (diagnostics.any { it.severity == Diagnostic.Severity.ERROR }) {
            ApplyResult.Refused(diagnostics)
        } else {
            applyEdit(plan.id, plan.snapshotHash, plan.workspaceEdit)
        }
    }

    /**
     * Roll back a previously applied transaction while holding the same one-writer
     * workspace lock. Post-apply conflict validation remains tracked by TX-004.
     */
    fun rollback(transaction: Transaction): ApplyResult = withWorkspaceLock {
        val errors = validateEdits(transaction.rollbackEdit) + validateRuntimeState(transaction.rollbackEdit)
        if (errors.any { it.severity == Diagnostic.Severity.ERROR }) {
            ApplyResult.Refused(errors)
        } else {
            applyEdit(transaction.planId, snapshotHashBefore = "", transaction.rollbackEdit)
        }
    }

    // ── private ──────────────────────────────────────────────────────────────

    private fun withWorkspaceLock(action: () -> ApplyResult): ApplyResult {
        val metadataDir = normalizedRoot.resolve(".refactorkit")
        val lockPath = metadataDir.resolve("workspace.lock")
        validateLockPath(metadataDir, lockPath)?.let { return ApplyResult.Refused(listOf(it)) }
        var actionStarted = false
        return try {
            Files.createDirectories(metadataDir)
            validateLockPath(metadataDir, lockPath)?.let { return ApplyResult.Refused(listOf(it)) }
            FileChannel.open(
                lockPath,
                setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
            ).use { channel ->
                restrictLockPermissions(lockPath)
                val lock = try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                if (lock == null) {
                    ApplyResult.Refused(listOf(Diagnostic(
                        "Workspace is locked by another RefactorKit writer",
                        Diagnostic.Severity.ERROR,
                        code = "workspace.locked",
                    )))
                } else {
                    lock.use {
                        actionStarted = true
                        action()
                    }
                }
            }
        } catch (error: Exception) {
            if (actionStarted) throw error
            ApplyResult.Refused(listOf(Diagnostic(
                "Cannot acquire workspace lock: ${error.message ?: error::class.simpleName}",
                Diagnostic.Severity.ERROR,
                code = "workspace.lockFailed",
            )))
        }
    }

    private fun validateLockPath(metadataDir: Path, lockPath: Path): Diagnostic? {
        validateNoSymbolicLinkTraversal(normalizedRoot.relativize(metadataDir))?.let { return it.copy(
            message = "Workspace metadata path traverses a symbolic link: $metadataDir",
            code = "workspace.lockUnsafe",
        ) }
        if (Files.exists(metadataDir, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isDirectory(metadataDir, LinkOption.NOFOLLOW_LINKS)
        ) {
            return Diagnostic(
                "Workspace metadata path is not a directory: $metadataDir",
                Diagnostic.Severity.ERROR,
                code = "workspace.lockUnsafe",
            )
        }
        if (Files.isSymbolicLink(lockPath) ||
            (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS))
        ) {
            return Diagnostic(
                "Workspace lock path is not a safe regular file: $lockPath",
                Diagnostic.Severity.ERROR,
                code = "workspace.lockUnsafe",
            )
        }
        return null
    }

    private fun restrictLockPermissions(lockPath: Path) {
        if (Files.getFileAttributeView(lockPath, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS) != null) {
            Files.setPosixFilePermissions(
                lockPath,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }

    private fun validateAffectedFilePreconditions(
        edit: WorkspaceEdit,
        snapshot: ProjectSnapshot,
    ): List<Diagnostic> {
        val expectedFiles = snapshot.files.associateBy { resolveInsideWorkspace(it.path) }
        val initialRoles = linkedMapOf<Path, InitialPathRole>()
        edit.edits.forEach { fileEdit ->
            val source = resolveInsideWorkspace(fileEdit.path)
            initialRoles.putIfAbsent(source, when (fileEdit) {
                is FileEdit.Create -> InitialPathRole.MAY_BE_ABSENT
                is FileEdit.Modify, is FileEdit.Delete, is FileEdit.Rename -> InitialPathRole.MUST_BE_SCANNED
            })
            if (fileEdit is FileEdit.Rename) {
                initialRoles.putIfAbsent(resolveInsideWorkspace(fileEdit.newPath), InitialPathRole.MAY_BE_ABSENT)
            }
        }
        return buildList {
            initialRoles.forEach { (path, role) ->
                if (!path.startsWith(normalizedRoot)) return@forEach
                val relative = normalizedRoot.relativize(path)
                if (validateNoSymbolicLinkTraversal(relative) != null) return@forEach
                val expected = expectedFiles[path]
                if (expected != null) {
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        add(Diagnostic(
                            "Affected file changed after the apply snapshot was scanned: $relative",
                            Diagnostic.Severity.ERROR,
                            code = "file.preconditionChanged",
                        ))
                    } else {
                        val actualContent = try {
                            Files.readString(path)
                        } catch (error: Exception) {
                            add(Diagnostic(
                                "Cannot verify affected file precondition for $relative: ${error.message}",
                                Diagnostic.Severity.ERROR,
                                code = "file.preconditionUnreadable",
                            ))
                            return@forEach
                        }
                        if (actualContent != expected.content) {
                            add(Diagnostic(
                                "Affected file changed after the apply snapshot was scanned: $relative",
                                Diagnostic.Severity.ERROR,
                                code = "file.preconditionChanged",
                            ))
                        }
                    }
                } else if (role == InitialPathRole.MUST_BE_SCANNED) {
                    add(Diagnostic(
                        "Affected existing file was not present in the apply snapshot: $relative",
                        Diagnostic.Severity.ERROR,
                        code = "file.preconditionUnavailable",
                    ))
                } else if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    add(Diagnostic(
                        "Affected path appeared after the apply snapshot was scanned: $relative",
                        Diagnostic.Severity.ERROR,
                        code = "file.preconditionChanged",
                    ))
                }
            }
        }
    }

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

    private enum class InitialPathRole {
        MAY_BE_ABSENT,
        MUST_BE_SCANNED,
    }
}

sealed interface ApplyResult {
    data class Applied(val transaction: Transaction) : ApplyResult
    data class Refused(val diagnostics: List<Diagnostic>) : ApplyResult
}
