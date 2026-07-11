package org.refactorkit.core

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.util.stream.Collectors

/**
 * File-based transaction log stored under [logDir].
 *
 * Each transaction is persisted as a JSON file named <transaction-id>.json.
 * Transaction identifiers and every existing path component are validated before
 * access so client input cannot escape the log directory or traverse symlinks.
 */
class TransactionLog(logDir: Path) {
    val logDir: Path = logDir.toAbsolutePath().normalize()

    fun save(transaction: Transaction) {
        prepareLogDirectory()
        val file = secureFile(transaction.id)
        try {
            Files.writeString(
                file,
                transaction.toJson(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            setOwnerOnlyPermissions(file, directory = false)
        } catch (error: TransactionLogException) {
            throw error
        } catch (error: Exception) {
            throw TransactionLogException(
                code = "transaction.writeFailed",
                message = "Cannot persist transaction ${transaction.id.value}",
                cause = error,
            )
        }
    }

    fun load(id: TransactionId): Transaction? {
        ensureSecureLogDirectory()
        val file = secureFile(id)
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null
        requireRegularFile(file)
        return try {
            transactionFromJson(Files.readString(file)).also { transaction ->
                if (transaction.id != id) {
                    throw TransactionLogException(
                        "transaction.corrupt",
                        "Transaction record ID does not match file name: ${id.value}",
                    )
                }
            }
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

    fun delete(id: TransactionId) {
        ensureSecureLogDirectory()
        val file = secureFile(id)
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return
        requireRegularFile(file)
        try {
            Files.delete(file)
        } catch (error: Exception) {
            throw TransactionLogException(
                code = "transaction.deleteFailed",
                message = "Cannot delete transaction record: ${id.value}",
                cause = error,
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
