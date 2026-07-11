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
    private val transactionLog: TransactionLog = TransactionLog(
        workspaceRoot.toAbsolutePath().normalize().resolve(".refactorkit/transactions"),
    ),
) {
    private val normalizedRoot = workspaceRoot.toAbsolutePath().normalize()

    /**
     * Acquire the workspace lock and recover any interrupted managed lifecycle.
     * Long-running integrations call this when opening a workspace; apply and
     * rollback also invoke it automatically before mutation.
     */
    fun recover(): List<Diagnostic> {
        val result = withWorkspaceLock {
            ApplyResult.Applied(Transaction(
                planId = PlanId("startup-recovery"),
                snapshotHashBefore = "",
                rollbackEdit = WorkspaceEdit(),
            ))
        }
        return (result as? ApplyResult.Refused)?.diagnostics.orEmpty()
    }

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
            applyPrepared(plan)
        }
    }

    /** Roll back an applied journaled transaction under the workspace lock. */
    fun rollback(transaction: Transaction): ApplyResult = withWorkspaceLock {
        val errors = validateEdits(transaction.rollbackEdit) + validateRuntimeState(transaction.rollbackEdit)
        if (errors.any { it.severity == Diagnostic.Severity.ERROR }) {
            ApplyResult.Refused(errors)
        } else {
            val record = transactionLog.loadRecord(transaction.id)
            try {
                if (record != null) transactionLog.update(record.copy(state = JournalState.ROLLING_BACK))
                executeWorkspaceEdit(transaction.rollbackEdit)
                if (record != null) transactionLog.update(record.copy(state = JournalState.ROLLED_BACK))
                ApplyResult.Applied(transaction)
            } catch (error: Exception) {
                if (record != null) markRecoveryRequired(record, "Rollback failed: ${error.message}")
                ApplyResult.Refused(listOf(Diagnostic(
                    "Rollback failed; recovery is required: ${error.message}",
                    Diagnostic.Severity.ERROR,
                    code = "transaction.recoveryRequired",
                )))
            }
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
                        val recoveryErrors = recoverIncompleteTransactions()
                        if (recoveryErrors.isNotEmpty()) ApplyResult.Refused(recoveryErrors) else action()
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

    private fun applyPrepared(plan: PatchPlan): ApplyResult {
        val record = try {
            prepareJournalRecord(plan)
        } catch (error: Exception) {
            return ApplyResult.Refused(listOf(Diagnostic(
                "Cannot stage transaction before apply: ${error.message}",
                Diagnostic.Severity.ERROR,
                code = "transaction.prepareFailed",
            )))
        }

        try {
            transactionLog.prepare(record)
            transactionLog.update(record.copy(state = JournalState.APPLYING))
        } catch (error: Exception) {
            return ApplyResult.Refused(listOf(Diagnostic(
                "Cannot persist write-ahead transaction intent: ${error.message}",
                Diagnostic.Severity.ERROR,
                code = "transaction.journalFailed",
            )))
        }

        return try {
            executeWorkspaceEdit(plan.workspaceEdit)
            transactionLog.update(record.copy(state = JournalState.APPLIED))
            ApplyResult.Applied(record.transaction)
        } catch (error: Exception) {
            val recovered = recoverJournalRecord(record.copy(state = JournalState.APPLYING), "Apply failed: ${error.message}")
            ApplyResult.Refused(listOf(Diagnostic(
                if (recovered) {
                    "Apply failed and the previous workspace state was restored: ${error.message}"
                } else {
                    "Apply failed; durable recovery is required: ${error.message}"
                },
                Diagnostic.Severity.ERROR,
                code = if (recovered) "transaction.applyFailedCompensated" else "transaction.recoveryRequired",
            )))
        }
    }

    private fun prepareJournalRecord(plan: PatchPlan): TransactionJournalRecord {
        val affected = plan.workspaceEdit.affectedFiles()
            .map { normalizedRoot.relativize(resolveInsideWorkspace(it)) }
            .distinct()
        val initial = linkedMapOf<Path, String?>()
        affected.forEach { relative ->
            val absolute = normalizedRoot.resolve(relative)
            initial[relative] = if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) Files.readString(absolute) else null
        }
        val working = initial.toMutableMap()
        val rollbackEdits = mutableListOf<FileEdit>()

        plan.workspaceEdit.edits.forEach { edit ->
            val path = normalizedRoot.relativize(resolveInsideWorkspace(edit.path))
            when (edit) {
                is FileEdit.Create -> {
                    val previous = working[path]
                    working[path] = edit.content
                    rollbackEdits += if (previous == null) FileEdit.Delete(path)
                    else FileEdit.Create(path, previous, overwrite = true)
                }
                is FileEdit.Delete -> {
                    val previous = requireNotNull(working[path]) { "Missing staged source: $path" }
                    working[path] = null
                    rollbackEdits += FileEdit.Create(path, previous, overwrite = true)
                }
                is FileEdit.Rename -> {
                    val target = normalizedRoot.relativize(resolveInsideWorkspace(edit.newPath))
                    val previous = requireNotNull(working[path]) { "Missing staged rename source: $path" }
                    working[path] = null
                    working[target] = previous
                    rollbackEdits += FileEdit.Rename(target, path)
                }
                is FileEdit.Modify -> {
                    val previous = requireNotNull(working[path]) { "Missing staged modify source: $path" }
                    working[path] = TextEdits.apply(previous, edit.textEdits)
                    rollbackEdits += FileEdit.Create(path, previous, overwrite = true)
                }
            }
        }

        val transaction = Transaction(
            planId = plan.id,
            snapshotHashBefore = plan.snapshotHash,
            rollbackEdit = WorkspaceEdit(rollbackEdits.asReversed()),
        )
        return TransactionJournalRecord(
            transaction = transaction,
            operation = plan.operation,
            forwardEdit = plan.workspaceEdit,
            preImages = initial.map { FileImage(it.key, it.value) },
            postImages = working.map { FileImage(it.key, it.value) },
            state = JournalState.PREPARED,
        )
    }

    private fun executeWorkspaceEdit(workspaceEdit: WorkspaceEdit) {
        workspaceEdit.edits.forEach { edit ->
            val absolute = resolveInsideWorkspace(edit.path)
            when (edit) {
                is FileEdit.Create -> {
                    if (absolute.exists() && !edit.overwrite) error("Refusing to overwrite existing file: ${edit.path}")
                    absolute.parent?.createDirectories()
                    absolute.writeText(edit.content)
                }
                is FileEdit.Delete -> Files.delete(absolute)
                is FileEdit.Rename -> {
                    val target = resolveInsideWorkspace(edit.newPath)
                    target.parent?.createDirectories()
                    Files.move(absolute, target)
                }
                is FileEdit.Modify -> {
                    val previous = absolute.readText()
                    absolute.writeText(TextEdits.apply(previous, edit.textEdits))
                }
            }
        }
    }

    private fun recoverIncompleteTransactions(): List<Diagnostic> {
        val records = try {
            transactionLog.listRecords()
        } catch (error: Exception) {
            return listOf(Diagnostic(
                "Cannot inspect transaction journal during startup recovery: ${error.message}",
                Diagnostic.Severity.ERROR,
                code = "transaction.recoveryRequired",
            ))
        }
        return buildList {
            records.forEach { record ->
                try {
                    when (record.state) {
                        JournalState.PREPARED -> transactionLog.update(record.copy(state = JournalState.ROLLED_BACK))
                        JournalState.APPLYING, JournalState.ROLLING_BACK -> {
                            if (!recoverJournalRecord(record, "Recovered after interrupted ${record.state.name.lowercase()}")) {
                                add(Diagnostic(
                                    "Transaction ${record.transaction.id.value} requires manual recovery",
                                    Diagnostic.Severity.ERROR,
                                    code = "transaction.recoveryRequired",
                                ))
                            }
                        }
                        JournalState.RECOVERY_REQUIRED -> add(Diagnostic(
                            "Transaction ${record.transaction.id.value} requires manual recovery",
                            Diagnostic.Severity.ERROR,
                            code = "transaction.recoveryRequired",
                        ))
                        JournalState.APPLIED, JournalState.ROLLED_BACK -> Unit
                    }
                } catch (error: Exception) {
                    add(Diagnostic(
                        "Transaction ${record.transaction.id.value} recovery failed: ${error.message}",
                        Diagnostic.Severity.ERROR,
                        code = "transaction.recoveryRequired",
                    ))
                }
            }
        }
    }

    private fun recoverJournalRecord(record: TransactionJournalRecord, reason: String): Boolean {
        val pre = record.preImages.associateBy { it.path }
        val post = record.postImages.associateBy { it.path }
        val compatible = try {
            (pre.keys + post.keys).all { path ->
                val current = readImage(path)
                current == pre[path]?.content || current == post[path]?.content
            }
        } catch (error: Exception) {
            markRecoveryRequired(record, "$reason; cannot inspect workspace state: ${error.message}")
            return false
        }
        if (!compatible) {
            markRecoveryRequired(record, "$reason; workspace state conflicts with journal images")
            return false
        }
        return try {
            pre.values.forEach { image -> restoreImage(image) }
            transactionLog.update(record.copy(state = JournalState.ROLLED_BACK, failure = reason))
            true
        } catch (error: Exception) {
            markRecoveryRequired(record, "$reason; compensation failed: ${error.message}")
            false
        }
    }

    private fun readImage(path: Path): String? {
        val absolute = resolveInsideWorkspace(path)
        return if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) error("Recovery path is not a regular file: $path")
            Files.readString(absolute)
        } else null
    }

    private fun restoreImage(image: FileImage) {
        val absolute = resolveInsideWorkspace(image.path)
        if (image.content == null) {
            Files.deleteIfExists(absolute)
        } else {
            absolute.parent?.createDirectories()
            absolute.writeText(image.content)
        }
    }

    private fun markRecoveryRequired(record: TransactionJournalRecord, failure: String) {
        runCatching { transactionLog.update(record.copy(state = JournalState.RECOVERY_REQUIRED, failure = failure)) }
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
