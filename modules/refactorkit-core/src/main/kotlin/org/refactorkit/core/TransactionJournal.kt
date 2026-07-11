package org.refactorkit.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
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
    val posixPermissions: Set<PosixFilePermission>? = null,
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
        const val CURRENT_SCHEMA_VERSION = 2
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
    val checksum: String? = null,
)

@Serializable
internal data class FileImageDto(
    val path: String,
    val content: String? = null,
    val posixPermissions: List<String>? = null,
)

private val journalJson = Json { prettyPrint = true; ignoreUnknownKeys = true }
private val canonicalJournalJson = Json { prettyPrint = false; encodeDefaults = true }

internal fun TransactionJournalRecord.toJson(): String {
    val unsigned = TransactionJournalDto(
        schemaVersion = TransactionJournalRecord.CURRENT_SCHEMA_VERSION,
        transaction = transaction.toDto(),
        operation = operation,
        forwardEdit = forwardEdit.toDto(),
        preImages = preImages.map { it.toDto() },
        postImages = postImages.map { it.toDto() },
        state = state.name,
        updatedAt = updatedAt.toString(),
        failure = failure,
        checksum = null,
    )
    return journalJson.encodeToString(unsigned.copy(checksum = checksum(unsigned)))
}

internal fun journalRecordFromJson(json: String): TransactionJournalRecord {
    val dto = journalJson.decodeFromString<TransactionJournalDto>(json)
    require(dto.schemaVersion in 1..TransactionJournalRecord.CURRENT_SCHEMA_VERSION) {
        "Unsupported transaction journal schema: ${dto.schemaVersion}"
    }
    if (dto.schemaVersion >= 2) {
        require(!dto.checksum.isNullOrBlank()) { "Transaction journal checksum is missing" }
        require(dto.checksum == checksum(dto.copy(checksum = null))) { "Transaction journal checksum mismatch" }
    }
    return TransactionJournalRecord(
        schemaVersion = dto.schemaVersion,
        transaction = dto.transaction.toDomain(),
        operation = dto.operation,
        forwardEdit = dto.forwardEdit.toDomain(),
        preImages = dto.preImages.map { it.toDomain() },
        postImages = dto.postImages.map { it.toDomain() },
        state = JournalState.valueOf(dto.state),
        updatedAt = Instant.parse(dto.updatedAt),
        failure = dto.failure,
    )
}

private fun FileImage.toDto() = FileImageDto(
    path = path.toString(),
    content = content,
    posixPermissions = posixPermissions?.map(PosixFilePermission::name)?.sorted(),
)

private fun FileImageDto.toDomain() = FileImage(
    path = Paths.get(path),
    content = content,
    posixPermissions = posixPermissions?.map(PosixFilePermission::valueOf)?.toSet(),
)

private fun checksum(dto: TransactionJournalDto): String {
    val bytes = canonicalJournalJson.encodeToString(dto).toByteArray(Charsets.UTF_8)
    return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
