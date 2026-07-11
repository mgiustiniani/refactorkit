package org.refactorkit.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

// ── Serializable DTOs (separate from domain models) ────────────────────────

@Serializable
data class TransactionDto(
    val id: String,
    val planId: String,
    val appliedAt: String,
    val snapshotHashBefore: String,
    val rollbackEdit: WorkspaceEditDto,
    val approval: ApprovalRecordDto? = null,
)

@Serializable
data class ApprovalRecordDto(
    val kind: String,
    val surface: String,
    val actor: String,
    val recordedAt: String,
)

@Serializable
data class WorkspaceEditDto(
    val edits: List<FileEditDto>,
)

@Serializable
sealed class FileEditDto {
    abstract val path: String

    @Serializable
    @SerialName("create")
    data class Create(
        override val path: String,
        val content: String,
        val overwrite: Boolean = false,
    ) : FileEditDto()

    @Serializable
    @SerialName("delete")
    data class Delete(override val path: String) : FileEditDto()

    @Serializable
    @SerialName("rename")
    data class Rename(override val path: String, val newPath: String) : FileEditDto()

    @Serializable
    @SerialName("modify")
    data class Modify(override val path: String, val textEdits: List<TextEditDto>) : FileEditDto()
}

@Serializable
data class TextEditDto(
    val startLine: Int,
    val startChar: Int,
    val endLine: Int,
    val endChar: Int,
    val newText: String,
)

// ── Conversion helpers ──────────────────────────────────────────────────────

private val transactionJson = Json { prettyPrint = true; ignoreUnknownKeys = true }

fun Transaction.toJson(): String = transactionJson.encodeToString(toDto())

fun Transaction.toDto(): TransactionDto = TransactionDto(
    id = id.value,
    planId = planId.value,
    appliedAt = appliedAt.toString(),
    snapshotHashBefore = snapshotHashBefore,
    rollbackEdit = rollbackEdit.toDto(),
    approval = ApprovalRecordDto(
        kind = approval.kind.name,
        surface = approval.surface,
        actor = approval.actor,
        recordedAt = approval.recordedAt.toString(),
    ),
)

fun WorkspaceEdit.toDto(): WorkspaceEditDto = WorkspaceEditDto(edits.map { it.toDto() })

fun FileEdit.toDto(): FileEditDto = when (this) {
    is FileEdit.Create -> FileEditDto.Create(path.toString(), content, overwrite)
    is FileEdit.Delete -> FileEditDto.Delete(path.toString())
    is FileEdit.Rename -> FileEditDto.Rename(path.toString(), newPath.toString())
    is FileEdit.Modify -> FileEditDto.Modify(path.toString(), textEdits.map { it.toDto() })
}

fun TextEdit.toDto(): TextEditDto = TextEditDto(
    startLine = range.start.line,
    startChar = range.start.character,
    endLine = range.end.line,
    endChar = range.end.character,
    newText = newText,
)

fun TransactionDto.toDomain(): Transaction = Transaction(
    id = TransactionId(id),
    planId = PlanId(planId),
    appliedAt = Instant.parse(appliedAt),
    snapshotHashBefore = snapshotHashBefore,
    rollbackEdit = rollbackEdit.toDomain(),
    approval = approval?.let {
        ApprovalRecord(
            kind = ApprovalKind.valueOf(it.kind),
            surface = it.surface,
            actor = it.actor,
            recordedAt = Instant.parse(it.recordedAt),
        )
    } ?: ApprovalRecord.legacy(),
)

fun WorkspaceEditDto.toDomain(): WorkspaceEdit = WorkspaceEdit(edits.map { it.toDomain() })

fun FileEditDto.toDomain(): FileEdit = when (this) {
    is FileEditDto.Create -> FileEdit.Create(Paths.get(path), content, overwrite)
    is FileEditDto.Delete -> FileEdit.Delete(Paths.get(path))
    is FileEditDto.Rename -> FileEdit.Rename(Paths.get(path), Paths.get(newPath))
    is FileEditDto.Modify -> FileEdit.Modify(Paths.get(path), textEdits.map { it.toDomain() })
}

fun TextEditDto.toDomain(): TextEdit = TextEdit(
    range = SourceRange(SourcePosition(startLine, startChar), SourcePosition(endLine, endChar)),
    newText = newText,
)

fun transactionFromJson(json: String): Transaction =
    transactionJson.decodeFromString<TransactionDto>(json).toDomain()
