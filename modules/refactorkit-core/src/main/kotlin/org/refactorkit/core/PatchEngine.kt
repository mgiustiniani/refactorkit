package org.refactorkit.core

import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID
import java.util.stream.Collectors

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

    /** Probe the workspace-root filesystem used by managed staging and journals. */
    fun filesystemCapabilities(): WorkspaceFilesystemCapabilities {
        val metadataDir = normalizedRoot.resolve(".refactorkit")
        val source = metadataDir.resolve(".capability-${UUID.randomUUID()}.tmp")
        val target = metadataDir.resolve(".capability-${UUID.randomUUID()}.moved")
        var fileForce = false
        var atomicMove = false
        var directoryForce = false
        val failures = mutableListOf<String>()
        try {
            Files.createDirectories(metadataDir)
            FileChannel.open(source, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                channel.write(java.nio.ByteBuffer.wrap(byteArrayOf(0)))
                channel.force(true)
                fileForce = true
            }
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
                atomicMove = true
            } catch (error: AtomicMoveNotSupportedException) {
                failures += "atomicMove:${error.message}"
            }
            try {
                forceWorkspaceDirectory(metadataDir)
                directoryForce = true
            } catch (error: Exception) {
                failures += "directoryForce:${error.message}"
            }
        } catch (error: Exception) {
            failures += "probe:${error.message}"
        } finally {
            runCatching { Files.deleteIfExists(source) }
            runCatching { Files.deleteIfExists(target) }
        }
        val store = runCatching { Files.getFileStore(normalizedRoot) }.getOrNull()
        return WorkspaceFilesystemCapabilities(
            fileStoreName = store?.name() ?: "unknown",
            fileStoreType = store?.type() ?: "unknown",
            atomicMoveSupported = atomicMove,
            durableFileForceSupported = fileForce,
            durableDirectoryForceSupported = directoryForce,
            replacementStrategy = "same-directory-temp-file+atomic-move+directory-force",
            failures = failures,
        )
    }

    /** Validate a preview plan against the current snapshot hash before applying. */
    fun validate(plan: PatchPlan, currentSnapshotHash: String): List<Diagnostic> = buildList {
        val normalizedEdit = WorkspaceEditSimulator.normalize(plan.workspaceEdit)
        if (plan.status != PatchStatus.PREVIEW) {
            add(Diagnostic("Only preview plans can be applied", Diagnostic.Severity.ERROR, code = "plan.notPreview"))
        }
        if (plan.snapshotHash != currentSnapshotHash) {
            add(Diagnostic("Project changed since preview; regenerate the plan", Diagnostic.Severity.ERROR, code = "snapshot.changed"))
        }
        addAll(validateEdits(normalizedEdit))
        addAll(validateRuntimeState(normalizedEdit))
        if (none { it.severity == Diagnostic.Severity.ERROR }) {
            try {
                stageWorkspaceEdit(normalizedEdit)
            } catch (error: IllegalArgumentException) {
                val rangeFailure = error.message?.contains("outside line") == true ||
                    error.message?.startsWith("line ") == true
                add(Diagnostic(
                    "Cannot render workspace edit before mutation: ${error.message}",
                    Diagnostic.Severity.ERROR,
                    code = if (rangeFailure) "edit.rangeOutOfBounds" else "edit.renderFailed",
                ))
            } catch (error: Exception) {
                add(Diagnostic(
                    "Cannot render workspace edit before mutation: ${error.message}",
                    Diagnostic.Severity.ERROR,
                    code = "edit.renderFailed",
                ))
            }
        }
    }

    /**
     * Apply a preview while holding the workspace's one-writer lock. The supplied
     * scan is treated as the pre-lock expectation and every initially affected
     * file is revalidated after lock acquisition before the first mutation.
     */
    fun apply(plan: PatchPlan, currentSnapshot: ProjectSnapshot): ApplyResult = withWorkspaceLock {
        val normalizedEdit = WorkspaceEditSimulator.normalize(plan.workspaceEdit)
        val normalizedPlan = plan.copy(
            workspaceEdit = normalizedEdit,
            affectedFiles = normalizedEdit.affectedFiles(),
        )
        val diagnostics = buildList {
            val capabilities = filesystemCapabilities()
            if (!capabilities.supportsDurableAtomicReplacement) {
                add(Diagnostic(
                    "Workspace filesystem does not satisfy durable atomic replacement: ${capabilities.failures.joinToString()}",
                    Diagnostic.Severity.ERROR,
                    code = "filesystem.capabilityUnsupported",
                ))
            }
            if (currentSnapshot.workspace.root.toAbsolutePath().normalize() != normalizedRoot) {
                add(Diagnostic(
                    "Snapshot workspace does not match PatchEngine workspace",
                    Diagnostic.Severity.ERROR,
                    code = "snapshot.workspaceMismatch",
                ))
            }
            addAll(validate(normalizedPlan, currentSnapshot.hash))
            addAll(validateEngineOwnedSnapshot(currentSnapshot))
            addAll(validateAffectedFilePreconditions(normalizedEdit, currentSnapshot))
        }
        if (diagnostics.any { it.severity == Diagnostic.Severity.ERROR }) {
            ApplyResult.Refused(diagnostics)
        } else {
            applyPrepared(normalizedPlan)
        }
    }

    /**
     * Roll back an applied journaled transaction under the workspace lock.
     * Normal mode refuses any post-apply path divergence; force mode is an
     * explicit destructive override that restores journaled pre-images.
     */
    fun rollback(
        transaction: Transaction,
        mode: RollbackMode = RollbackMode.NORMAL,
    ): ApplyResult = withWorkspaceLock {
        val record = transactionLog.loadRecord(transaction.id)
            ?: return@withWorkspaceLock ApplyResult.Refused(listOf(Diagnostic(
                "Transaction journal record is missing: ${transaction.id.value}",
                Diagnostic.Severity.ERROR,
                code = "transaction.journalMissing",
            )))
        if (record.state != JournalState.APPLIED) {
            return@withWorkspaceLock ApplyResult.Refused(listOf(Diagnostic(
                "Transaction is not in APPLIED state: ${record.state}",
                Diagnostic.Severity.ERROR,
                code = "transaction.notApplied",
            )))
        }
        val journalErrors = validateJournalImages(record)
        if (journalErrors.isNotEmpty()) return@withWorkspaceLock ApplyResult.Refused(journalErrors)
        if (record.preImages.isEmpty() || record.postImages.isEmpty()) {
            return@withWorkspaceLock ApplyResult.Refused(listOf(Diagnostic(
                "Transaction lacks stable rollback pre/post images",
                Diagnostic.Severity.ERROR,
                code = "rollback.preconditionUnavailable",
            )))
        }
        if (mode == RollbackMode.NORMAL) {
            val conflicts = validateRollbackPostImages(record)
            if (conflicts.isNotEmpty()) {
                transactionLog.update(record.copy(failure = conflicts.joinToString("; ") { it.message }))
                return@withWorkspaceLock ApplyResult.Refused(conflicts)
            }
        }

        try {
            val currentImages = record.preImages.map { image -> FileImage(image.path, readImage(image.path)) }
            val permissions = derivePostPermissions(transaction.rollbackEdit)
            transactionLog.update(record.copy(state = JournalState.ROLLING_BACK))
            commitPostImages(currentImages, record.preImages, permissions)
            transactionLog.update(record.copy(
                state = JournalState.ROLLED_BACK,
                failure = if (mode == RollbackMode.FORCE) "Forced rollback explicitly requested" else null,
            ))
            ApplyResult.Applied(transaction)
        } catch (error: Exception) {
            markRecoveryRequired(record, "Rollback failed: ${error.message}")
            ApplyResult.Refused(listOf(Diagnostic(
                "Rollback failed; recovery is required: ${error.message}",
                Diagnostic.Severity.ERROR,
                code = "transaction.recoveryRequired",
            )))
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

    private fun validateJournalImages(record: TransactionJournalRecord): List<Diagnostic> = buildList {
        val prePaths = record.preImages.map { it.path }.toSet()
        val postPaths = record.postImages.map { it.path }.toSet()
        if (prePaths != postPaths) {
            add(Diagnostic(
                "Transaction pre/post image path sets do not match",
                Diagnostic.Severity.ERROR,
                code = "transaction.corrupt",
            ))
        }
        (prePaths + postPaths).forEach { path ->
            validateInsideWorkspace(path)?.let(::add)
            validateNoSymbolicLinkTraversal(path)?.let(::add)
        }
    }

    private fun validateRollbackPostImages(record: TransactionJournalRecord): List<Diagnostic> = buildList {
        record.postImages.forEach { expected ->
            val actual = try {
                readImage(expected.path)
            } catch (error: Exception) {
                add(Diagnostic(
                    "Cannot verify rollback state for ${expected.path}: ${error.message}",
                    Diagnostic.Severity.ERROR,
                    code = "rollback.conflict",
                ))
                return@forEach
            }
            if (actual != expected.content) {
                add(Diagnostic(
                    "Rollback conflict: ${expected.path} changed after apply",
                    Diagnostic.Severity.ERROR,
                    code = "rollback.conflict",
                ))
            }
        }
    }

    private fun validateEngineOwnedSnapshot(snapshot: ProjectSnapshot): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        if (snapshot.sourceExtensions.isEmpty()) {
            return listOf(Diagnostic(
                "Snapshot source scope must declare at least one file extension",
                Diagnostic.Severity.ERROR,
                code = "snapshot.scopeInvalid",
            ))
        }
        diagnostics += validateClasspathEvidence(snapshot)
        if (diagnostics.isNotEmpty()) return diagnostics

        val declaredRoots = snapshot.modules.flatMap { it.sourceRoots }
            .map(::resolveInsideWorkspace)
        val roots = (declaredRoots.ifEmpty { listOf(normalizedRoot) })
            .map { it.toAbsolutePath().normalize() }
            .distinct()
            .sortedBy { it.nameCount }
            .fold(mutableListOf<Path>()) { selected, candidate ->
                if (selected.none { candidate.startsWith(it) }) selected.add(candidate)
                selected
            }

        roots.forEach { root ->
            if (!root.startsWith(normalizedRoot)) {
                diagnostics += Diagnostic(
                    "Snapshot source root is outside workspace: $root",
                    Diagnostic.Severity.ERROR,
                    code = "snapshot.scopeInvalid",
                )
            } else {
                validateNoSymbolicLinkTraversal(normalizedRoot.relativize(root))?.let(diagnostics::add)
            }
        }
        if (diagnostics.isNotEmpty()) return diagnostics

        val languageByExtension = snapshot.files.mapNotNull { file ->
            val extension = extensionOf(file.path) ?: return@mapNotNull null
            extension to file.languageId
        }.toMap()
        val actualByPath = linkedMapOf<Path, SourceFile>()
        try {
            roots.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.forEach { sourceRoot ->
                Files.walk(sourceRoot).use { stream ->
                    stream
                        .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                        .filter { absolute ->
                            val relative = normalizedRoot.relativize(absolute)
                            relative.none { component -> component.toString() in snapshot.ignoredDirectories }
                        }
                        .filter { extensionOf(it) in snapshot.sourceExtensions }
                        .map { absolute ->
                            val relative = normalizedRoot.relativize(absolute)
                            val extension = requireNotNull(extensionOf(relative))
                            SourceFile(relative, Files.readString(absolute), languageByExtension[extension] ?: extension)
                        }
                        .collect(Collectors.toList())
                        .forEach { actualByPath[it.path] = it }
                }
            }
        } catch (error: Exception) {
            return listOf(Diagnostic(
                "Cannot rescan snapshot scope under workspace lock: ${error.message}",
                Diagnostic.Severity.ERROR,
                code = "snapshot.scopeUnreadable",
            ))
        }

        val actualHash = ProjectSnapshot.hashSnapshot(
            snapshot.modules,
            actualByPath.values.toList(),
            snapshot.sourceExtensions,
            snapshot.ignoredDirectories,
            snapshot.classpathEvidence,
        )
        if (actualHash != snapshot.hash) {
            val expectedByPath = snapshot.files.associateBy { it.path }
            val added = actualByPath.keys - expectedByPath.keys
            val missing = expectedByPath.keys - actualByPath.keys
            val changed = (actualByPath.keys intersect expectedByPath.keys).filter { path ->
                val actual = actualByPath.getValue(path)
                val expected = expectedByPath.getValue(path)
                actual.content != expected.content || actual.languageId != expected.languageId
            }
            val detail = buildList {
                if (added.isNotEmpty()) add("added=${added.sortedBy(Path::toString)}")
                if (missing.isNotEmpty()) add("missing=${missing.sortedBy(Path::toString)}")
                if (changed.isNotEmpty()) add("changed=${changed.sortedBy(Path::toString)}")
                if (isEmpty()) add("scope metadata changed")
            }.joinToString(", ")
            diagnostics += Diagnostic(
                "Workspace source scope changed since the supplied snapshot ($detail); rescan and regenerate the plan",
                Diagnostic.Severity.ERROR,
                code = "snapshot.scopeChanged",
            )
        }
        return diagnostics
    }

    private fun validateClasspathEvidence(snapshot: ProjectSnapshot): List<Diagnostic> {
        val evidenceByKey = snapshot.classpathEvidence.groupBy { it.path.normalize() to it.kind }
        val duplicates = evidenceByKey.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            return listOf(Diagnostic(
                "Snapshot contains duplicate classpath evidence: ${duplicates.sortedBy { it.first.toString() }}",
                Diagnostic.Severity.ERROR,
                code = "snapshot.scopeInvalid",
            ))
        }

        val entryEvidencePaths = snapshot.classpathEvidence
            .filter { it.kind == ClasspathEvidenceKind.ENTRY }
            .map { it.path.normalize() }
            .toSet()
        val missingEvidence = snapshot.modules.flatMap(Module::classpathEntries)
            .map(Path::normalize)
            .filterNot(entryEvidencePaths::contains)
            .distinct()
        if (missingEvidence.isNotEmpty()) {
            return listOf(Diagnostic(
                "Snapshot classpath entries lack content evidence: ${missingEvidence.sortedBy(Path::toString)}",
                Diagnostic.Severity.ERROR,
                code = "snapshot.scopeInvalid",
            ))
        }

        val changed = mutableListOf<Path>()
        try {
            snapshot.classpathEvidence.forEach { evidence ->
                val absolute = resolveInsideWorkspace(evidence.path)
                val actual = ClasspathEvidence.fingerprint(absolute, evidence.kind)
                if (actual != evidence.fingerprint) changed.add(evidence.path)
            }
        } catch (error: Exception) {
            return listOf(Diagnostic(
                "Cannot verify classpath evidence under workspace lock: ${error.message}",
                Diagnostic.Severity.ERROR,
                code = "snapshot.classpathUnreadable",
            ))
        }
        return if (changed.isEmpty()) emptyList() else listOf(Diagnostic(
            "Classpath changed since the supplied snapshot: ${changed.distinct().sortedBy(Path::toString)}; rescan and regenerate the plan",
            Diagnostic.Severity.ERROR,
            code = "snapshot.classpathChanged",
        ))
    }

    private fun extensionOf(path: Path): String? = path.fileName?.toString()
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.takeIf(String::isNotEmpty)

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
            commitPostImages(record.preImages, record.postImages, derivePostPermissions(plan.workspaceEdit))
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
                code = if (!recovered) {
                    "transaction.recoveryRequired"
                } else {
                    (error as? WorkspaceWriteException)?.code ?: "transaction.applyFailedCompensated"
                },
            )))
        }
    }

    private fun prepareJournalRecord(plan: PatchPlan): TransactionJournalRecord {
        val staged = stageWorkspaceEdit(plan.workspaceEdit)
        val transaction = Transaction(
            planId = plan.id,
            snapshotHashBefore = plan.snapshotHash,
            rollbackEdit = staged.rollbackEdit,
        )
        return TransactionJournalRecord(
            transaction = transaction,
            operation = plan.operation,
            forwardEdit = plan.workspaceEdit,
            preImages = staged.preImages,
            postImages = staged.postImages,
            state = JournalState.PREPARED,
        )
    }

    private fun stageWorkspaceEdit(workspaceEdit: WorkspaceEdit): StagedWorkspaceEdit {
        val affected = workspaceEdit.affectedFiles()
            .map { normalizedRoot.relativize(resolveInsideWorkspace(it)) }
            .distinct()
        val initial = linkedMapOf<Path, String?>()
        affected.forEach { relative ->
            val absolute = normalizedRoot.resolve(relative)
            initial[relative] = if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) Files.readString(absolute) else null
        }
        val working = initial.toMutableMap()
        val rollbackEdits = mutableListOf<FileEdit>()
        workspaceEdit.edits.forEach { edit ->
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
        return StagedWorkspaceEdit(
            preImages = initial.map { FileImage(it.key, it.value) },
            postImages = working.map { FileImage(it.key, it.value) },
            rollbackEdit = WorkspaceEdit(rollbackEdits.asReversed()),
        )
    }

    private fun derivePostPermissions(workspaceEdit: WorkspaceEdit): Map<Path, Set<PosixFilePermission>?> {
        val permissions = workspaceEdit.affectedFiles().associate { path ->
            val relative = normalizedRoot.relativize(resolveInsideWorkspace(path))
            val absolute = normalizedRoot.resolve(relative)
            relative to if (
                Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS) &&
                Files.getFileAttributeView(absolute, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS) != null
            ) {
                Files.getPosixFilePermissions(absolute, LinkOption.NOFOLLOW_LINKS)
            } else null
        }.toMutableMap()
        workspaceEdit.edits.forEach { edit ->
            val path = normalizedRoot.relativize(resolveInsideWorkspace(edit.path))
            when (edit) {
                is FileEdit.Create -> if (!permissions.containsKey(path)) permissions[path] = null
                is FileEdit.Delete -> permissions[path] = null
                is FileEdit.Rename -> {
                    val target = normalizedRoot.relativize(resolveInsideWorkspace(edit.newPath))
                    permissions[target] = permissions[path]
                    permissions[path] = null
                }
                is FileEdit.Modify -> Unit
            }
        }
        return permissions
    }

    private fun commitPostImages(
        preImages: List<FileImage>,
        postImages: List<FileImage>,
        desiredPermissions: Map<Path, Set<PosixFilePermission>?> = emptyMap(),
    ) {
        val stagedFiles = linkedMapOf<Path, Path>()
        val preByPath = preImages.associateBy { it.path }
        val postByPath = postImages.associateBy { it.path }
        try {
            postImages.filter { it.content != null }.forEach { image ->
                val target = resolveInsideWorkspace(image.path)
                createDirectoriesDurably(requireNotNull(target.parent))
                val temporary = target.parent.resolve(".refactorkit-stage-${UUID.randomUUID()}.tmp")
                FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                    val buffer = java.nio.ByteBuffer.wrap(requireNotNull(image.content).toByteArray(Charsets.UTF_8))
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                applyStagedPermissions(
                    temporary,
                    target,
                    image,
                    desiredPermissions[image.path],
                    preByPath,
                    postByPath,
                )
                FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
                stagedFiles[image.path] = temporary
            }

            postImages.sortedBy { it.content == null }.forEach { image ->
                val target = resolveInsideWorkspace(image.path)
                if (image.content == null) {
                    Files.deleteIfExists(target)
                    target.parent?.let(::forceWorkspaceDirectory)
                } else {
                    val temporary = requireNotNull(stagedFiles.remove(image.path))
                    try {
                        Files.move(
                            temporary,
                            target,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    } catch (error: AtomicMoveNotSupportedException) {
                        throw WorkspaceWriteException(
                            "filesystem.atomicMoveUnsupported",
                            "Filesystem does not support atomic replacement for ${image.path}",
                            error,
                        )
                    }
                    target.parent?.let(::forceWorkspaceDirectory)
                }
            }
        } finally {
            stagedFiles.values.forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun applyStagedPermissions(
        temporary: Path,
        target: Path,
        image: FileImage,
        desiredPermissions: Set<PosixFilePermission>?,
        preByPath: Map<Path, FileImage>,
        postByPath: Map<Path, FileImage>,
    ) {
        if (Files.getFileAttributeView(temporary, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS) == null) return
        val source = when {
            Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) -> target
            else -> preByPath.values.firstOrNull { candidate ->
                candidate.content == image.content && postByPath[candidate.path]?.content == null
            }?.path?.let(::resolveInsideWorkspace)?.takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
        }
        val permissions = desiredPermissions ?: if (source != null) {
            Files.getPosixFilePermissions(source, LinkOption.NOFOLLOW_LINKS)
        } else {
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ,
            )
        }
        Files.setPosixFilePermissions(temporary, permissions)
    }

    private fun createDirectoriesDurably(directory: Path) {
        val missing = mutableListOf<Path>()
        var current: Path? = directory
        while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            missing.add(current)
            current = current.parent
        }
        missing.asReversed().forEach { path ->
            Files.createDirectory(path)
            forceWorkspaceDirectory(path)
            path.parent?.let(::forceWorkspaceDirectory)
        }
    }

    private fun forceWorkspaceDirectory(directory: Path) {
        try {
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        } catch (error: Exception) {
            throw WorkspaceWriteException(
                "filesystem.directoryForceFailed",
                "Cannot durably flush workspace directory: $directory",
                error,
            )
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
        val current = try {
            (pre.keys + post.keys).associateWith(::readImage)
        } catch (error: Exception) {
            markRecoveryRequired(record, "$reason; cannot inspect workspace state: ${error.message}")
            return false
        }
        val compatible = current.all { (path, content) ->
            content == pre[path]?.content || content == post[path]?.content
        }
        if (!compatible) {
            markRecoveryRequired(record, "$reason; workspace state conflicts with journal images")
            return false
        }
        return try {
            if (current.any { (path, content) -> content != pre[path]?.content }) {
                commitPostImages(post.values.toList(), pre.values.toList())
            }
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

    private data class StagedWorkspaceEdit(
        val preImages: List<FileImage>,
        val postImages: List<FileImage>,
        val rollbackEdit: WorkspaceEdit,
    )

    private enum class InitialPathRole {
        MAY_BE_ABSENT,
        MUST_BE_SCANNED,
    }
}

enum class RollbackMode {
    NORMAL,
    FORCE,
}

data class WorkspaceFilesystemCapabilities(
    val fileStoreName: String,
    val fileStoreType: String,
    val atomicMoveSupported: Boolean,
    val durableFileForceSupported: Boolean,
    val durableDirectoryForceSupported: Boolean,
    val replacementStrategy: String,
    val failures: List<String> = emptyList(),
) {
    val supportsDurableAtomicReplacement: Boolean
        get() = atomicMoveSupported && durableFileForceSupported && durableDirectoryForceSupported
}

class WorkspaceWriteException(
    val code: String,
    override val message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

sealed interface ApplyResult {
    data class Applied(val transaction: Transaction) : ApplyResult
    data class Refused(val diagnostics: List<Diagnostic>) : ApplyResult
}
