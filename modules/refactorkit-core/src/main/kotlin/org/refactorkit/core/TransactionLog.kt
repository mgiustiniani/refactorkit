package org.refactorkit.core

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import java.util.UUID
import java.util.stream.Collectors

/**
 * Versioned write-ahead transaction journal stored under [logDir].
 *
 * Records are created durably before workspace mutation and replaced through a
 * same-directory temporary file plus atomic move for every lifecycle transition.
 */
enum class JournalFaultPoint {
    AFTER_NEW_FILE_FORCE,
    AFTER_UPDATE_TEMP_FORCE,
    AFTER_UPDATE_ATOMIC_MOVE,
}

fun interface JournalFaultInjector {
    fun inject(point: JournalFaultPoint, path: Path)

    companion object {
        val NONE = JournalFaultInjector { _, _ -> }
    }
}

class TransactionLog(
    logDir: Path,
    private val faultInjector: JournalFaultInjector = JournalFaultInjector.NONE,
) {
    val logDir: Path = logDir.toAbsolutePath().normalize()
    private val orphanTempPattern = Regex(
        "\\.transaction-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.json\\.tmp-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
    )

    /** Compatibility helper for importing an already-applied transaction. */
    fun save(transaction: Transaction) {
        prepare(TransactionJournalRecord(
            transaction = transaction,
            operation = "legacyAppliedTransaction",
            forwardEdit = WorkspaceEdit(),
            preImages = emptyList(),
            postImages = emptyList(),
            state = JournalState.APPLIED,
        ))
    }

    fun prepare(record: TransactionJournalRecord) {
        require(record.state == JournalState.PREPARED || record.state == JournalState.APPLIED) {
            "New journal records must start PREPARED"
        }
        prepareLogDirectory()
        val file = secureFile(record.transaction.id)
        val now = Instant.now()
        val initial = if (record.history.isEmpty()) {
            record.copy(history = listOf(JournalEvent(record.state, now, record.failure)), updatedAt = now)
        } else record
        writeNewDurably(file, initial.toJson())
    }

    fun update(record: TransactionJournalRecord) {
        prepareLogDirectory()
        val file = secureFile(record.transaction.id)
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw TransactionLogException(
                "transaction.missing",
                "Transaction journal record is missing: ${record.transaction.id.value}",
            )
        }
        val persisted = loadRecord(record.transaction.id) ?: throw TransactionLogException(
            "transaction.missing",
            "Transaction journal record is missing: ${record.transaction.id.value}",
        )
        val now = Instant.now()
        replaceDurably(file, record.copy(
            history = persisted.history + JournalEvent(record.state, now, record.failure),
            updatedAt = now,
        ).toJson())
    }

    fun load(id: TransactionId): Transaction? =
        loadRecord(id)?.takeIf { it.state == JournalState.APPLIED }?.transaction

    fun loadRecord(id: TransactionId): TransactionJournalRecord? {
        ensureSecureLogDirectory()
        ensureNoQuarantinedRecords()
        val file = secureFile(id)
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null
        requireRegularFile(file)
        return try {
            val json = Files.readString(file)
            val record = if (json.contains("\"schemaVersion\"")) {
                journalRecordFromJson(json)
            } else {
                val transaction = transactionFromJson(json)
                TransactionJournalRecord(
                    transaction = transaction,
                    operation = "legacyAppliedTransaction",
                    forwardEdit = WorkspaceEdit(),
                    preImages = emptyList(),
                    postImages = emptyList(),
                    state = JournalState.APPLIED,
                )
            }
            if (record.transaction.id != id) {
                throw TransactionLogException(
                    "transaction.corrupt",
                    "Transaction record ID does not match file name: ${id.value}",
                )
            }
            record
        } catch (error: TransactionLogException) {
            if (error.code == "transaction.corrupt") quarantine(file, id, error)
            throw error
        } catch (error: Exception) {
            quarantine(file, id, error)
        }
    }

    fun list(): List<TransactionId> {
        ensureSecureLogDirectory()
        ensureNoQuarantinedRecords()
        if (!Files.exists(logDir, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return try {
            Files.list(logDir).use { stream ->
                stream
                    .filter { it.fileName.toString().endsWith(".json") }
                    .map { file ->
                        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                            throw TransactionLogException(
                                "transaction.pathUnsafe",
                                "Transaction record is not a regular file: ${file.fileName}",
                            )
                        }
                        val rawId = file.fileName.toString().removeSuffix(".json")
                        TransactionId.parseOrNull(rawId) ?: throw TransactionLogException(
                            "transaction.invalidId",
                            "Invalid transaction record name: ${file.fileName}",
                        )
                    }
                    .collect(Collectors.toList())
            }
        } catch (error: TransactionLogException) {
            throw error
        } catch (error: Exception) {
            throw TransactionLogException("transaction.readFailed", "Cannot list transaction records", error)
        }
    }

    fun listRecords(): List<TransactionJournalRecord> = list().mapNotNull(::loadRecord)

    /** Remove uncommitted lifecycle temps while the caller holds the workspace lock. */
    fun cleanupOrphanedTemps(): Int {
        ensureSecureLogDirectory()
        if (!Files.exists(logDir, LinkOption.NOFOLLOW_LINKS)) return 0
        val candidates = Files.list(logDir).use { stream ->
            stream.filter { orphanTempPattern.matches(it.fileName.toString()) }.collect(Collectors.toList())
        }
        candidates.forEach { path ->
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw TransactionLogException(
                    "transaction.pathUnsafe",
                    "Unsafe orphan transaction temp path: ${path.fileName}",
                )
            }
            Files.delete(path)
        }
        if (candidates.isNotEmpty()) forceDirectory()
        return candidates.size
    }

    fun delete(id: TransactionId) {
        ensureSecureLogDirectory()
        val file = secureFile(id)
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return
        requireRegularFile(file)
        try {
            Files.delete(file)
            forceDirectory()
        } catch (error: Exception) {
            throw TransactionLogException(
                code = "transaction.deleteFailed",
                message = "Cannot delete transaction record: ${id.value}",
                cause = error,
            )
        }
    }

    private fun quarantine(file: Path, id: TransactionId, cause: Throwable): Nothing {
        val quarantineDir = logDir.resolve(".quarantine")
        val destination = quarantineDir.resolve(
            "${id.value}-${Instant.now().toEpochMilli()}-${UUID.randomUUID()}.json.corrupt",
        )
        try {
            ensureNoSymbolicLinkComponents(quarantineDir)
            Files.createDirectories(quarantineDir)
            if (!Files.isDirectory(quarantineDir, LinkOption.NOFOLLOW_LINKS)) {
                throw IllegalStateException("Quarantine path is not a directory")
            }
            setOwnerOnlyPermissions(quarantineDir, directory = true)
            Files.move(file, destination, StandardCopyOption.ATOMIC_MOVE)
            setOwnerOnlyPermissions(destination, directory = false)
            forceDirectory(quarantineDir)
            forceDirectory(logDir)
        } catch (error: Exception) {
            throw TransactionLogException(
                "transaction.quarantineFailed",
                "Corrupt transaction ${id.value} could not be quarantined",
                error,
            )
        }
        throw TransactionLogException(
            "transaction.quarantined",
            "Corrupt transaction ${id.value} was quarantined at ${destination.fileName}; manual review is required",
            cause,
        )
    }

    private fun ensureNoQuarantinedRecords() {
        val quarantineDir = logDir.resolve(".quarantine")
        ensureNoSymbolicLinkComponents(quarantineDir)
        if (!Files.exists(quarantineDir, LinkOption.NOFOLLOW_LINKS)) return
        if (!Files.isDirectory(quarantineDir, LinkOption.NOFOLLOW_LINKS)) {
            throw TransactionLogException("transaction.pathUnsafe", "Transaction quarantine path is unsafe")
        }
        val hasRecords = Files.list(quarantineDir).use { it.findAny().isPresent }
        if (hasRecords) {
            throw TransactionLogException(
                "transaction.quarantined",
                "Quarantined transaction records require manual review: $quarantineDir",
            )
        }
    }

    private fun writeNewDurably(file: Path, content: String) {
        try {
            FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                writeFully(channel, content)
                channel.force(true)
            }
            setOwnerOnlyPermissions(file, directory = false)
            faultInjector.inject(JournalFaultPoint.AFTER_NEW_FILE_FORCE, file)
            forceDirectory()
        } catch (error: TransactionLogException) {
            throw error
        } catch (error: Exception) {
            throw TransactionLogException(
                "transaction.writeFailed",
                "Cannot persist transaction journal ${file.fileName}",
                error,
            )
        }
    }

    private fun replaceDurably(file: Path, content: String) {
        val temporary = logDir.resolve(".${file.fileName}.tmp-${UUID.randomUUID()}")
        try {
            FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                writeFully(channel, content)
                channel.force(true)
            }
            setOwnerOnlyPermissions(temporary, directory = false)
            faultInjector.inject(JournalFaultPoint.AFTER_UPDATE_TEMP_FORCE, temporary)
            try {
                Files.move(
                    temporary,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (error: AtomicMoveNotSupportedException) {
                throw TransactionLogException(
                    "transaction.atomicMoveUnsupported",
                    "Filesystem does not support atomic transaction journal replacement",
                    error,
                )
            }
            faultInjector.inject(JournalFaultPoint.AFTER_UPDATE_ATOMIC_MOVE, file)
            forceDirectory()
        } catch (error: TransactionLogException) {
            Files.deleteIfExists(temporary)
            throw error
        } catch (error: Exception) {
            Files.deleteIfExists(temporary)
            throw TransactionLogException(
                "transaction.writeFailed",
                "Cannot update transaction journal ${file.fileName}",
                error,
            )
        }
    }

    private fun writeFully(channel: FileChannel, content: String) {
        val buffer = ByteBuffer.wrap(content.toByteArray(Charsets.UTF_8))
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    private fun forceDirectory(path: Path = logDir) {
        try {
            FilesystemDurability.forceDirectory(path)
        } catch (error: Exception) {
            throw TransactionLogException(
                "transaction.durabilityFailed",
                "Cannot durably flush transaction journal directory: $path",
                error,
            )
        }
    }

    private fun prepareLogDirectory() {
        ensureNoSymbolicLinkComponents(logDir)
        try {
            Files.createDirectories(logDir)
        } catch (error: Exception) {
            throw TransactionLogException("transaction.pathUnsafe", "Cannot create transaction log directory", error)
        }
        ensureSecureLogDirectory()
        ensureNoQuarantinedRecords()
        setOwnerOnlyPermissions(logDir, directory = true)
        forceDirectory(logDir)
        logDir.parent?.let(::forceDirectory)
        logDir.parent?.parent?.let(::forceDirectory)
    }

    private fun ensureSecureLogDirectory() {
        ensureNoSymbolicLinkComponents(logDir)
        if (Files.exists(logDir, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isDirectory(logDir, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw TransactionLogException(
                "transaction.pathUnsafe",
                "Transaction log path is not a directory: $logDir",
            )
        }
    }

    private fun secureFile(id: TransactionId): Path {
        val file = logDir.resolve("${id.value}.json").normalize()
        if (file.parent != logDir) {
            throw TransactionLogException("transaction.pathUnsafe", "Transaction path escapes the log directory")
        }
        if (Files.isSymbolicLink(file)) {
            throw TransactionLogException(
                "transaction.pathUnsafe",
                "Transaction record must not be a symbolic link: ${id.value}",
            )
        }
        return file
    }

    private fun requireRegularFile(file: Path) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw TransactionLogException(
                "transaction.pathUnsafe",
                "Transaction record is not a regular file: ${file.fileName}",
            )
        }
    }

    private fun ensureNoSymbolicLinkComponents(path: Path) {
        val absolute = path.toAbsolutePath().normalize()
        var trustedAncestor: Path? = absolute
        while (trustedAncestor != null && !Files.exists(trustedAncestor, LinkOption.NOFOLLOW_LINKS)) {
            trustedAncestor = trustedAncestor.parent
        }
        var current = trustedAncestor ?: absolute.root ?: return
        if (Files.isSymbolicLink(current)) {
            throw TransactionLogException(
                "transaction.pathUnsafe",
                "Transaction log path traverses a symbolic link: $current",
            )
        }
        for (component in current.relativize(absolute)) {
            current = current.resolve(component)
            if (Files.isSymbolicLink(current)) {
                throw TransactionLogException(
                    "transaction.pathUnsafe",
                    "Transaction log path traverses a symbolic link: $current",
                )
            }
        }
    }

    private fun setOwnerOnlyPermissions(path: Path, directory: Boolean) {
        if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS) == null) return
        val permissions = if (directory) {
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
        } else {
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        }
        try {
            Files.setPosixFilePermissions(path, permissions)
        } catch (error: Exception) {
            throw TransactionLogException(
                "transaction.permissionsFailed",
                "Cannot restrict transaction log permissions: $path",
                error,
            )
        }
    }
}

class TransactionLogException(
    val code: String,
    override val message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
