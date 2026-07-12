package org.refactorkit.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

object JsonRpcErrorCodes {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
    // RefactorKit application errors
    const val PLAN_REFUSED = -32001
    const val SNAPSHOT_CHANGED = -32002
    const val SYMBOL_NOT_FOUND = -32003
    const val PROJECT_NOT_OPEN = -32004
    const val ROLLBACK_CONFLICT = -32005
    const val RECOVERY_REQUIRED = -32006
    const val DOCUMENT_VERSION_MISMATCH = -32007
    const val PLAN_VALIDATION_FAILED = -32008
    const val WORKSPACE_LOCKED = -32009
    const val FILESYSTEM_UNSUPPORTED = -32010
    const val APPLY_FAILED = -32011
    const val UNSAFE_PATH = -32012
    const val FILE_CONFLICT = -32013
    const val APPROVAL_REQUIRED = -32014
    const val DIAGNOSTICS_FAILED = -32015

    fun applyRefusalCode(diagnostics: List<Diagnostic>): Int {
        val codes = diagnostics.mapNotNull(Diagnostic::code)
        return when {
            codes.any { it == "transaction.recoveryRequired" } -> RECOVERY_REQUIRED
            codes.any { it == "workspace.locked" || it == "workspace.lockFailed" } -> WORKSPACE_LOCKED
            codes.any { it.startsWith("filesystem.") } -> FILESYSTEM_UNSUPPORTED
            codes.any { it.startsWith("path.") || it == "workspace.lockUnsafe" } -> UNSAFE_PATH
            codes.any { it == "file.exists" || it == "file.missing" } -> FILE_CONFLICT
            codes.any { it == "approval.required" } -> APPROVAL_REQUIRED
            codes.any { it.startsWith("diagnostics.") } -> DIAGNOSTICS_FAILED
            codes.any { it.startsWith("snapshot.") || it.startsWith("file.precondition") } -> SNAPSHOT_CHANGED
            codes.any { it.startsWith("edit.") || it == "plan.notPreview" || it == "evidence.insufficient" } -> PLAN_VALIDATION_FAILED
            codes.any { it.startsWith("transaction.") } -> APPLY_FAILED
            else -> INTERNAL_ERROR
        }
    }

    fun rollbackRefusalCode(diagnostics: List<Diagnostic>): Int = when {
        diagnostics.any { it.code == "rollback.conflict" } -> ROLLBACK_CONFLICT
        diagnostics.any { it.code == "transaction.recoveryRequired" } -> RECOVERY_REQUIRED
        diagnostics.any { it.code?.startsWith("path.") == true } -> UNSAFE_PATH
        diagnostics.any { it.code?.startsWith("filesystem.") == true } -> FILESYSTEM_UNSUPPORTED
        else -> APPLY_FAILED
    }
}

class JsonRpcException(val code: Int, override val message: String) : RuntimeException(message)

fun successResponse(id: JsonElement?, result: JsonElement): JsonRpcResponse =
    JsonRpcResponse(id = id, result = result)

fun errorResponse(id: JsonElement?, code: Int, message: String): JsonRpcResponse =
    JsonRpcResponse(id = id, error = JsonRpcError(code = code, message = message))

fun isNotification(request: JsonRpcRequest): Boolean =
    request.id == null || request.id is JsonNull
