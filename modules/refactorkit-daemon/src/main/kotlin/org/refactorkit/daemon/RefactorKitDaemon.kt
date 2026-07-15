package org.refactorkit.daemon

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcRequest
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
        synchronized(out) { out.println(json.encodeToString(response)) }
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
