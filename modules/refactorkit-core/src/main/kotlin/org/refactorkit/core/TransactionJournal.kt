package org.refactorkit.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
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

@Serializable
data class FileAclEntryImage(
    val type: String,
    val principal: String,
    val permissions: List<String>,
    val flags: List<String>,
)

data class FileImage(
    val path: Path,
    val content: String?,
    val contentSha256: String? = content?.let(::sha256),
    val posixPermissions: Set<PosixFilePermission>? = null,
    val lastModifiedMillis: Long? = null,
    val ownerName: String? = null,
    val groupName: String? = null,
    val userDefinedAttributes: Map<String, String>? = null,
    val aclEntries: List<FileAclEntryImage>? = null,
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
        const val CURRENT_SCHEMA_VERSION = 8
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
    val contentSha256: String? = null,
    val posixPermissions: List<String>? = null,
    val lastModifiedMillis: Long? = null,
    val ownerName: String? = null,
    val groupName: String? = null,
    val userDefinedAttributes: Map<String, String>? = null,
    val aclEntries: List<FileAclEntryImage>? = null,
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
            4 -> legacyV4Checksum(dto)
            5 -> legacyV5Checksum(dto)
            6 -> legacyV6Checksum(dto)
            7 -> legacyV7Checksum(dto)
            else -> checksum(dto.copy(checksum = null))
        }
        require(dto.checksum == expected) { "Transaction journal checksum mismatch" }
    }
    if (dto.schemaVersion >= 8) {
        (dto.preImages + dto.postImages).forEach { image ->
            val expectedContentHash = image.content?.let(::sha256)
            require(image.contentSha256 == expectedContentHash) {
                "Transaction journal file-image content hash mismatch: ${image.path}"
            }
        }
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
    contentSha256 = contentSha256,
    posixPermissions = posixPermissions?.map(PosixFilePermission::name)?.sorted(),
    lastModifiedMillis = lastModifiedMillis,
    ownerName = ownerName,
    groupName = groupName,
    userDefinedAttributes = userDefinedAttributes?.toSortedMap(),
    aclEntries = aclEntries,
)

private fun FileImageDto.toDomain() = FileImage(
    path = Paths.get(path),
    content = content,
    contentSha256 = contentSha256 ?: content?.let(::sha256),
    posixPermissions = posixPermissions?.map(PosixFilePermission::valueOf)?.toSet(),
    lastModifiedMillis = lastModifiedMillis,
    ownerName = ownerName,
    groupName = groupName,
    userDefinedAttributes = userDefinedAttributes?.toSortedMap(),
    aclEntries = aclEntries,
)

private fun legacyV2Checksum(dto: TransactionJournalDto): String {
    val current = canonicalJournalJson.encodeToJsonElement(TransactionJournalDto.serializer(), dto.copy(checksum = null)).jsonObject
    val legacy = JsonObject(
        current.filterKeys { it !in setOf(
            "implementationVersion", "apiVersion", "preSnapshotHash", "postSnapshotHash", "history",
        ) }.mapValues { (key, value) ->
            if (key == "preImages" || key == "postImages") {
                removeImageMetadata(value, "lastModifiedMillis", "ownerName", "groupName", "userDefinedAttributes", "aclEntries")
            } else value
        },
    )
    return sha256(canonicalJournalJson.encodeToString(JsonObject.serializer(), legacy))
}

private fun legacyV3Checksum(dto: TransactionJournalDto): String {
    val current = canonicalJournalJson.encodeToJsonElement(TransactionJournalDto.serializer(), dto.copy(checksum = null)).jsonObject
    val legacy = JsonObject(current.mapValues { (key, value) ->
        if (key == "preImages" || key == "postImages") {
            removeImageMetadata(value, "lastModifiedMillis", "ownerName", "groupName", "userDefinedAttributes", "aclEntries")
        } else value
    })
    return sha256(canonicalJournalJson.encodeToString(JsonObject.serializer(), legacy))
}

private fun legacyV4Checksum(dto: TransactionJournalDto): String {
    val current = canonicalJournalJson.encodeToJsonElement(TransactionJournalDto.serializer(), dto.copy(checksum = null)).jsonObject
    val legacy = JsonObject(current.mapValues { (key, value) ->
        if (key == "preImages" || key == "postImages") {
            removeImageMetadata(value, "ownerName", "groupName", "userDefinedAttributes", "aclEntries")
        } else value
    })
    return sha256(canonicalJournalJson.encodeToString(JsonObject.serializer(), legacy))
}

private fun legacyV5Checksum(dto: TransactionJournalDto): String {
    val current = canonicalJournalJson.encodeToJsonElement(TransactionJournalDto.serializer(), dto.copy(checksum = null)).jsonObject
    val legacy = JsonObject(current.mapValues { (key, value) ->
        if (key == "preImages" || key == "postImages") {
            removeImageMetadata(value, "userDefinedAttributes", "aclEntries")
        } else value
    })
    return sha256(canonicalJournalJson.encodeToString(JsonObject.serializer(), legacy))
}

private fun legacyV6Checksum(dto: TransactionJournalDto): String {
    val current = canonicalJournalJson.encodeToJsonElement(TransactionJournalDto.serializer(), dto.copy(checksum = null)).jsonObject
    val legacy = JsonObject(current.mapValues { (key, value) ->
        if (key == "preImages" || key == "postImages") removeImageMetadata(value, "aclEntries") else value
    })
    return sha256(canonicalJournalJson.encodeToString(JsonObject.serializer(), legacy))
}

private fun legacyV7Checksum(dto: TransactionJournalDto): String {
    val current = canonicalJournalJson.encodeToJsonElement(TransactionJournalDto.serializer(), dto.copy(checksum = null)).jsonObject
    val legacy = JsonObject(current.mapValues { (key, value) ->
        if (key == "preImages" || key == "postImages") removeImageMetadata(value) else value
    })
    return sha256(canonicalJournalJson.encodeToString(JsonObject.serializer(), legacy))
}

private fun removeImageMetadata(
    value: kotlinx.serialization.json.JsonElement,
    vararg fields: String,
): kotlinx.serialization.json.JsonElement {
    val excluded = fields.toSet() + "contentSha256"
    return kotlinx.serialization.json.JsonArray(value.jsonArray.map { element ->
        JsonObject(element.jsonObject.filterKeys { it !in excluded })
    })
}

/** Private resource receipt in TransactionLog; it does not add lifecycle events or change WAL-v8. */
internal fun stagingOwnershipToJson(record: TransactionJournalRecord, files: Map<String, Pair<String, String>>): String {
    val body = JsonObject(linkedMapOf(
        "version" to JsonPrimitive(1),
        "transactionId" to JsonPrimitive(record.transaction.id.value),
        "planId" to JsonPrimitive(record.transaction.planId.value),
        "preSnapshotHash" to JsonPrimitive(record.preSnapshotHash),
        "postSnapshotHash" to JsonPrimitive(record.postSnapshotHash),
        "editSha256" to JsonPrimitive(WorkspaceEditIdentity.sha256(record.forwardEdit)),
        "files" to JsonObject(files.toSortedMap().mapValues { (_, proof) ->
            JsonArray(listOf(JsonPrimitive(proof.first), JsonPrimitive(proof.second)))
        }),
    ))
    return JsonObject(body + ("checksum" to JsonPrimitive(sha256(body.toString())))).toString()
}

internal fun stagingOwnershipFromJson(content: String, record: TransactionJournalRecord): Map<String, Pair<String, String>> {
    val document = canonicalJournalJson.parseToJsonElement(content).jsonObject
    val body = JsonObject(document - "checksum")
    require(document["checksum"] == JsonPrimitive(sha256(body.toString()))) { "Staging receipt checksum mismatch" }
    val expected = canonicalJournalJson.parseToJsonElement(stagingOwnershipToJson(record, emptyMap())).jsonObject
    require(document.filterKeys { it != "checksum" && it != "files" } == expected.filterKeys { it != "checksum" && it != "files" }) {
        "Staging receipt does not belong to the transaction"
    }
    val files = requireNotNull(document["files"]).jsonObject
    require(files.size <= record.preImages.size + record.postImages.size)
    return files.mapValues { (_, value) ->
        val parts = value.jsonArray
        require(parts.size == 2 && parts.all { it is JsonPrimitive && it.isString })
        val identity = (parts[0] as JsonPrimitive).content
        val digest = (parts[1] as JsonPrimitive).content
        require(identity.isNotBlank() && identity.length <= 512 && digest.matches(Regex("[0-9a-f]{64}")))
        identity to digest
    }
}

private fun checksum(dto: TransactionJournalDto): String =
    sha256(canonicalJournalJson.encodeToString(dto))

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
