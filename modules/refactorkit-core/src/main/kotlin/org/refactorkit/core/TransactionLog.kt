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
class TransactionLog(logDir: Path) {
    val logDir: Path = logDir.toAbsolutePath().normalize()

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
        writeNewDurably(file, record.toJson())
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
        replaceDurably(file, record.copy(updatedAt = Instant.now()).toJson())
    }

    fun load(id: TransactionId): Transaction? =
        loadRecord(id)?.takeIf { it.state == JournalState.APPLIED }?.transaction

    fun loadRecord(id: TransactionId): TransactionJournalRecord? {
        ensureSecureLogDirectory()
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
            throw error
        } catch (error: Exception) {
            throw TransactionLogException(
                code = "transaction.corrupt",
                message = "Transaction record is malformed or corrupt: ${id.value}",
                cause = error,
            )
        }
    }

    fun list(): List<TransactionId> {
        ensureSecureLogDirectory()
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

    private fun writeNewDurably(file: Path, content: String) {
        try {
            FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                writeFully(channel, content)
                channel.force(true)
            }
            setOwnerOnlyPermissions(file, directory = false)
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
            FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
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
        var current = absolute.root ?: return
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
