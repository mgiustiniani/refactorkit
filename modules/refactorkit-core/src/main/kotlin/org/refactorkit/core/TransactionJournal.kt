package org.refactorkit.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
    val lastModifiedMillis: Long? = null,
)

data class JournalEvent(
    val state: JournalState,
    val at: Instant,
    val detail: String? = null,
)

data class TransactionJournalRecord(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val implementationVersion: String = RefactorKitVersion.VERSION,
    val apiVersion: String = RefactorKitVersion.API_VERSION,
    val transaction: Transaction,
    val operation: String,
    val forwardEdit: WorkspaceEdit,
    val preImages: List<FileImage>,
    val postImages: List<FileImage>,
    val createdDirectories: List<Path> = emptyList(),
    val preSnapshotHash: String? = null,
    val postSnapshotHash: String? = null,
    val state: JournalState,
    val history: List<JournalEvent> = emptyList(),
    val updatedAt: Instant = Instant.now(),
    val failure: String? = null,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 4
    }
}

@Serializable
internal data class TransactionJournalDto(
    val schemaVersion: Int,
    val implementationVersion: String? = null,
    val apiVersion: String? = null,
    val transaction: TransactionDto,
    val operation: String,
    val forwardEdit: WorkspaceEditDto,
    val preImages: List<FileImageDto>,
    val postImages: List<FileImageDto>,
    val createdDirectories: List<String> = emptyList(),
    val preSnapshotHash: String? = null,
    val postSnapshotHash: String? = null,
    val state: String,
    val history: List<JournalEventDto> = emptyList(),
    val updatedAt: String,
    val failure: String? = null,
    val checksum: String? = null,
)

@Serializable
internal data class JournalEventDto(
    val state: String,
    val at: String,
    val detail: String? = null,
)

@Serializable
internal data class FileImageDto(
    val path: String,
    val content: String? = null,
    val posixPermissions: List<String>? = null,
    val lastModifiedMillis: Long? = null,
)

private val journalJson = Json { prettyPrint = true; ignoreUnknownKeys = true }
private val canonicalJournalJson = Json { prettyPrint = false; encodeDefaults = true }

internal fun TransactionJournalRecord.toJson(): String {
    val unsigned = TransactionJournalDto(
        schemaVersion = TransactionJournalRecord.CURRENT_SCHEMA_VERSION,
        implementationVersion = implementationVersion,
        apiVersion = apiVersion,
        transaction = transaction.toDto(),
        operation = operation,
        forwardEdit = forwardEdit.toDto(),
        preImages = preImages.map { it.toDto() },
        postImages = postImages.map { it.toDto() },
        createdDirectories = createdDirectories.map(Path::toString).sorted(),
        preSnapshotHash = preSnapshotHash,
        postSnapshotHash = postSnapshotHash,
        state = state.name,
        history = history.map { JournalEventDto(it.state.name, it.at.toString(), it.detail) },
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
        val expected = when (dto.schemaVersion) {
            2 -> legacyV2Checksum(dto)
            3 -> legacyV3Checksum(dto)
            else -> checksum(dto.copy(checksum = null))
        }
        require(dto.checksum == expected) { "Transaction journal checksum mismatch" }
    }
    return TransactionJournalRecord(
        schemaVersion = dto.schemaVersion,
        implementationVersion = dto.implementationVersion ?: "unknown",
        apiVersion = dto.apiVersion ?: "unknown",
        transaction = dto.transaction.toDomain(),
        operation = dto.operation,
        forwardEdit = dto.forwardEdit.toDomain(),
        preImages = dto.preImages.map { it.toDomain() },
        postImages = dto.postImages.map { it.toDomain() },
        createdDirectories = dto.createdDirectories.map(Paths::get),
        preSnapshotHash = dto.preSnapshotHash,
        postSnapshotHash = dto.postSnapshotHash,
        state = JournalState.valueOf(dto.state),
        history = dto.history.map { JournalEvent(JournalState.valueOf(it.state), Instant.parse(it.at), it.detail) },
        updatedAt = Instant.parse(dto.updatedAt),
        failure = dto.failure,
    )
}

private fun FileImage.toDto() = FileImageDto(
    path = path.toString(),
    content = content,
    posixPermissions = posixPermissions?.map(PosixFilePermission::name)?.sorted(),
    lastModifiedMillis = lastModifiedMillis,
)

private fun FileImageDto.toDomain() = FileImage(
    path = Paths.get(path),
    content = content,
    posixPermissions = posixPermissions?.map(PosixFilePermission::valueOf)?.toSet(),
    lastModifiedMillis = lastModifiedMillis,
)

private fun legacyV2Checksum(dto: TransactionJournalDto): String {
    val current = canonicalJournalJson.encodeToJsonElement(
        TransactionJournalDto.serializer(),
        dto.copy(checksum = null),
    ).jsonObject
    val legacy = JsonObject(
        current.filterKeys { it !in setOf(
            "implementationVersion", "apiVersion", "preSnapshotHash", "postSnapshotHash", "history",
        ) }.mapValues { (key, value) ->
            if (key == "preImages" || key == "postImages") removeImageMetadata(value, "lastModifiedMillis") else value
        },
    )
    return sha256(canonicalJournalJson.encodeToString(JsonObject.serializer(), legacy))
}

private fun legacyV3Checksum(dto: TransactionJournalDto): String {
    val current = canonicalJournalJson.encodeToJsonElement(
        TransactionJournalDto.serializer(),
        dto.copy(checksum = null),
    ).jsonObject
    val legacy = JsonObject(current.mapValues { (key, value) ->
        if (key == "preImages" || key == "postImages") removeImageMetadata(value, "lastModifiedMillis") else value
    })
    return sha256(canonicalJournalJson.encodeToString(JsonObject.serializer(), legacy))
}

private fun removeImageMetadata(value: kotlinx.serialization.json.JsonElement, field: String): kotlinx.serialization.json.JsonElement =
    kotlinx.serialization.json.JsonArray(value.jsonArray.map { element ->
        JsonObject(element.jsonObject.filterKeys { it != field })
    })

private fun checksum(dto: TransactionJournalDto): String =
    sha256(canonicalJournalJson.encodeToString(dto))

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
