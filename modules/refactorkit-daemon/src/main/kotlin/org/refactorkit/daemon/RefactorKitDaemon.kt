package org.refactorkit.daemon

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcException
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

    System.err.println("RefactorKit daemon ready (JSON-RPC / NDJSON)")

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
        } catch (e: Exception) {
            out.println(json.encodeToString(errorResponse(JsonNull, JsonRpcErrorCodes.PARSE_ERROR, "Parse error: ${e.message}")))
            return@forEachLine
        }

        // Notifications: process but don't respond
        if (isNotification(request)) {
            runCatching { session.dispatch(request.method, request.params?.jsonObject) }
            return@forEachLine
        }

        val response = try {
            val result = session.dispatch(request.method, request.params?.jsonObject)
            successResponse(request.id, result)
        } catch (e: JsonRpcException) {
            errorResponse(request.id, e.code, e.message)
        } catch (e: Exception) {
            errorResponse(request.id, JsonRpcErrorCodes.INTERNAL_ERROR, e.message ?: "Internal error")
        }

        out.println(json.encodeToString(response))
    }
}
