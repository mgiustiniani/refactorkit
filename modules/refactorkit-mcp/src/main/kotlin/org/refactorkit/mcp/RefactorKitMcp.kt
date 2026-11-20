package org.refactorkit.mcp

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcException
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
 * RefactorKit MCP server.
 *
 * Transport: JSON-RPC 2.0 over stdio, newline-delimited JSON (same as daemon).
 * Protocol: Model Context Protocol 2024-11-05
 */
fun main() {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val session = McpSession()
    var running = true
    session.onExit = { running = false }

    System.err.println("RefactorKit MCP server ready (protocol ${"2024-11-05"})")

    val reader = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
    val out = PrintStream(System.out, true, Charsets.UTF_8)

    while (running) {
        val line = reader.readLine() ?: break
        if (line.isBlank()) continue
        if (line.toByteArray(Charsets.UTF_8).size > ProtocolLimits.MAX_REQUEST_BYTES) {
            out.println(json.encodeToString(errorResponse(
                JsonNull,
                JsonRpcErrorCodes.INVALID_REQUEST,
                "Request exceeds ${ProtocolLimits.MAX_REQUEST_BYTES} bytes",
            )))
            continue
        }

        val request = try {
            json.decodeFromString<JsonRpcRequest>(line)
        } catch (_: Exception) {
            out.println(json.encodeToString(errorResponse(JsonNull, JsonRpcErrorCodes.PARSE_ERROR, "Parse error")))
            continue
        }

        if (isNotification(request)) {
            runCatching { session.dispatch(request.method, request.params?.jsonObject) }
            continue
        }

        val response = try {
            val result = session.dispatch(request.method, request.params?.jsonObject)
            successResponse(request.id, result)
        } catch (e: JsonRpcException) {
            errorResponse(request.id, e)
        } catch (_: Exception) {
            errorResponse(request.id, JsonRpcErrorCodes.INTERNAL_ERROR, "Internal error")
        }

        out.println(McpResponseEncoder.encode(response, json))
    }
    session.close()
}

/** Applies the MCP stdio whole-response budget before writing an NDJSON frame. */
object McpResponseEncoder {
    fun encode(response: JsonRpcResponse, json: Json): String {
        val encoded = json.encodeToString(response)
        if (encoded.toByteArray(Charsets.UTF_8).size <= ProtocolLimits.MAX_MCP_RESPONSE_BYTES) return encoded

        val message = "MCP response exceeds ${ProtocolLimits.MAX_MCP_RESPONSE_BYTES} bytes"
        val refusal = json.encodeToString(errorResponse(response.id, JsonRpcErrorCodes.INTERNAL_ERROR, message))
        if (refusal.toByteArray(Charsets.UTF_8).size <= ProtocolLimits.MAX_MCP_RESPONSE_BYTES) return refusal

        val fallback = json.encodeToString(errorResponse(JsonNull, JsonRpcErrorCodes.INTERNAL_ERROR, message))
        check(fallback.toByteArray(Charsets.UTF_8).size <= ProtocolLimits.MAX_MCP_RESPONSE_BYTES) {
            "MCP bounded error exceeds ${ProtocolLimits.MAX_MCP_RESPONSE_BYTES} bytes"
        }
        return fallback
    }
}
