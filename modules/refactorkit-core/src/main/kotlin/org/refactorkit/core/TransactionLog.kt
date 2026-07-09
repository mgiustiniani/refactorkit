package org.refactorkit.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * File-based transaction log stored under [logDir].
 *
 * Each transaction is persisted as a JSON file named <transaction-id>.json.
 * The directory is created on first save.
 */
class TransactionLog(val logDir: Path) {

    fun save(transaction: Transaction) {
        if (!logDir.exists()) logDir.createDirectories()
        logDir.resolve("${transaction.id.value}.json").writeText(transaction.toJson())
    }

    fun load(id: TransactionId): Transaction? {
        val file = logDir.resolve("${id.value}.json")
        return if (file.exists()) transactionFromJson(file.readText()) else null
    }

    fun list(): List<TransactionId> {
        if (!logDir.exists()) return emptyList()
        return Files.list(logDir).use { stream ->
            stream
                .filter { it.fileName.toString().endsWith(".json") }
                .map { TransactionId(it.fileName.toString().removeSuffix(".json")) }
                .collect(Collectors.toList())
        }
    }

    fun delete(id: TransactionId) {
        val file = logDir.resolve("${id.value}.json")
        if (file.exists()) Files.delete(file)
    }
}
