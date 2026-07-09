package org.refactorkit.mcp

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.JsonRpcRequest
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

        val request = try {
            json.decodeFromString<JsonRpcRequest>(line)
        } catch (e: Exception) {
            out.println(json.encodeToString(errorResponse(JsonNull, JsonRpcErrorCodes.PARSE_ERROR, "Parse error: ${e.message}")))
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
            errorResponse(request.id, e.code, e.message)
        } catch (e: Exception) {
            errorResponse(request.id, JsonRpcErrorCodes.INTERNAL_ERROR, e.message ?: "Internal error")
        }

        out.println(json.encodeToString(response))
    }
}
