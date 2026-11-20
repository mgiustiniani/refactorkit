package org.refactorkit.daemon

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcRequest
import org.refactorkit.core.JsonRpcResponse
import org.refactorkit.core.ProtocolLimits
import org.refactorkit.core.errorResponse
import org.refactorkit.core.isNotification
import org.refactorkit.core.successResponse
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintStream

/**
 * RefactorKit JSON-RPC daemon.
 *
 * Transport: newline-delimited JSON (NDJSON) over stdio.
 * One JSON object per line; responses are also single-line JSON.
 *
 * Protocol: JSON-RPC 2.0
 */
fun main() {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val session = DaemonSession()
    val reader = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
    val out = PrintStream(System.out, true, Charsets.UTF_8)
    val writeResponse: (org.refactorkit.core.JsonRpcResponse) -> Unit = { response ->
        synchronized(out) { out.println(DaemonResponseEncoder.encode(response, json)) }
    }
    val scheduler = DaemonRequestScheduler(session, writeResponse)

    System.err.println("RefactorKit daemon ready (JSON-RPC / NDJSON)")

    try {
        reader.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            if (line.toByteArray(Charsets.UTF_8).size > ProtocolLimits.MAX_REQUEST_BYTES) {
                out.println(json.encodeToString(errorResponse(
                    JsonNull,
                    JsonRpcErrorCodes.INVALID_REQUEST,
                    "Request exceeds ${ProtocolLimits.MAX_REQUEST_BYTES} bytes",
                )))
                return@forEachLine
            }

            val request = try {
                json.decodeFromString<JsonRpcRequest>(line)
            } catch (_: Exception) {
                out.println(json.encodeToString(errorResponse(JsonNull, JsonRpcErrorCodes.PARSE_ERROR, "Parse error")))
                return@forEachLine
            }

            if (request.method == "intelligence.cancel") {
                val params = request.params as? JsonObject
                val requestId = (params?.get("requestId") as? JsonPrimitive)?.content
                if (requestId.isNullOrBlank()) {
                    if (!isNotification(request)) writeResponse(errorResponse(
                        request.id, JsonRpcErrorCodes.INVALID_PARAMS, "intelligence.cancel requires requestId",
                    ))
                } else {
                    val cancelled = scheduler.cancel(requestId)
                    if (!isNotification(request)) writeResponse(successResponse(request.id, buildJsonObject {
                        put("requestId", requestId); put("status", if (cancelled) "cancelled" else "not-found")
                    }))
                }
                return@forEachLine
            }
            scheduler.submit(request)
        }
    } finally {
        scheduler.close()
        session.close()
    }
}

/** Applies the daemon's whole-frame response budget before writing to stdio. */
object DaemonResponseEncoder {
    const val ENTRY_POINTS_FRAME_BYTES = 1_048_576
    const val ENTRY_POINTS_RPC_ID_BYTES = 1_024
    private val entryPointJson = Json { encodeDefaults = true }

    /** Encoded ID bytes, including JSON quoting/escaping; unrelated methods retain their contract. */
    internal fun validEntryPointRpcId(id: JsonElement?): Boolean {
        if (id == null || id is JsonNull) return true
        if (id !is JsonPrimitive || (!id.isString && id.booleanOrNull != null)) return false
        return entryPointJson.encodeToString(JsonElement.serializer(), id).toByteArray(Charsets.UTF_8).size <= ENTRY_POINTS_RPC_ID_BYTES
    }

    /** Include the NDJSON delimiter and bound errors as well as successful results. */
    internal fun boundEntryPointResponse(response: JsonRpcResponse): JsonRpcResponse {
        val encoded = entryPointJson.encodeToString(response) + System.lineSeparator()
        if (encoded.toByteArray(Charsets.UTF_8).size <= ENTRY_POINTS_FRAME_BYTES) return response
        return errorResponse(
            if (validEntryPointRpcId(response.id)) response.id else JsonNull,
            JsonRpcErrorCodes.INTERNAL_ERROR,
            "Entry-point response exceeds its complete UTF-8 frame budget",
            buildJsonObject { put("refusalCode", "java.entryPoints.responseLimit") },
        )
    }

    fun encode(response: JsonRpcResponse, json: Json): String {
        val encoded = json.encodeToString(response)
        if (encoded.toByteArray(Charsets.UTF_8).size <= ProtocolLimits.MAX_DAEMON_RESPONSE_BYTES) return encoded
        val refusal = errorResponse(
            response.id,
            JsonRpcErrorCodes.INTERNAL_ERROR,
            "Daemon response exceeds ${ProtocolLimits.MAX_DAEMON_RESPONSE_BYTES} bytes",
        )
        return json.encodeToString(refusal)
    }
}
