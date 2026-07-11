package org.refactorkit.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

enum class JournalState {
    PREPARED,
    APPLYING,
    APPLIED,
    ROLLING_BACK,
    ROLLED_BACK,
    RECOVERY_REQUIRED,
}

data class FileImage(
    val path: Path,
    val content: String?,
)

data class TransactionJournalRecord(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val transaction: Transaction,
    val operation: String,
    val forwardEdit: WorkspaceEdit,
    val preImages: List<FileImage>,
    val postImages: List<FileImage>,
    val state: JournalState,
    val updatedAt: Instant = Instant.now(),
    val failure: String? = null,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

@Serializable
internal data class TransactionJournalDto(
    val schemaVersion: Int,
    val transaction: TransactionDto,
    val operation: String,
    val forwardEdit: WorkspaceEditDto,
    val preImages: List<FileImageDto>,
    val postImages: List<FileImageDto>,
    val state: String,
    val updatedAt: String,
    val failure: String? = null,
)

@Serializable
internal data class FileImageDto(
    val path: String,
    val content: String? = null,
)

private val journalJson = Json { prettyPrint = true; ignoreUnknownKeys = true }

internal fun TransactionJournalRecord.toJson(): String = journalJson.encodeToString(
    TransactionJournalDto(
        schemaVersion = schemaVersion,
        transaction = transaction.toDto(),
        operation = operation,
        forwardEdit = forwardEdit.toDto(),
        preImages = preImages.map { FileImageDto(it.path.toString(), it.content) },
        postImages = postImages.map { FileImageDto(it.path.toString(), it.content) },
        state = state.name,
        updatedAt = updatedAt.toString(),
        failure = failure,
    ),
)

internal fun journalRecordFromJson(json: String): TransactionJournalRecord {
    val dto = journalJson.decodeFromString<TransactionJournalDto>(json)
    require(dto.schemaVersion == TransactionJournalRecord.CURRENT_SCHEMA_VERSION) {
        "Unsupported transaction journal schema: ${dto.schemaVersion}"
    }
    return TransactionJournalRecord(
        schemaVersion = dto.schemaVersion,
        transaction = dto.transaction.toDomain(),
        operation = dto.operation,
        forwardEdit = dto.forwardEdit.toDomain(),
        preImages = dto.preImages.map { FileImage(Paths.get(it.path), it.content) },
        postImages = dto.postImages.map { FileImage(Paths.get(it.path), it.content) },
        state = JournalState.valueOf(dto.state),
        updatedAt = Instant.parse(dto.updatedAt),
        failure = dto.failure,
    )
}
