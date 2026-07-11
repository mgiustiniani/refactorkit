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
}

class JsonRpcException(val code: Int, override val message: String) : RuntimeException(message)

fun successResponse(id: JsonElement?, result: JsonElement): JsonRpcResponse =
    JsonRpcResponse(id = id, result = result)

fun errorResponse(id: JsonElement?, code: Int, message: String): JsonRpcResponse =
    JsonRpcResponse(id = id, error = JsonRpcError(code = code, message = message))

fun isNotification(request: JsonRpcRequest): Boolean =
    request.id == null || request.id is JsonNull
