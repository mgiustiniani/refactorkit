package org.refactorkit.lsp

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.JsonRpcRequest
import org.refactorkit.core.errorResponse
import org.refactorkit.core.isNotification
import org.refactorkit.core.successResponse
import java.io.InputStream
import java.io.OutputStream

/**
 * RefactorKit LSP server.
 *
 * Transport: JSON-RPC 2.0 with LSP Content-Length framing over stdio.
 */
fun main() {
    val session = LspSession()
    var running = true
    session.onExit = { running = false }

    System.err.println("RefactorKit LSP server ready")
    val input = System.`in`
    val output = System.out
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    session.onNotification = { method, params ->
        writeLspNotification(output, json, method, params)
    }

    while (running) {
        val message = readLspMessage(input) ?: break

        val request = try {
            json.decodeFromString<JsonRpcRequest>(message)
        } catch (e: Exception) {
            writeLspMessage(output, json, errorResponse(JsonNull, JsonRpcErrorCodes.PARSE_ERROR, "Parse error: ${e.message}"))
            continue
        }

        if (isNotification(request)) {
            runCatching { session.dispatch(request.method, request.params?.jsonObject) }
            continue
        }

        val response = try {
            val result = session.dispatch(request.method, request.params?.jsonObject)
            if (result is JsonNull) successResponse(request.id, JsonNull) else successResponse(request.id, result)
        } catch (e: JsonRpcException) {
            errorResponse(request.id, e.code, e.message)
        } catch (e: Exception) {
            errorResponse(request.id, JsonRpcErrorCodes.INTERNAL_ERROR, e.message ?: "Internal error")
        }

        writeLspMessage(output, json, response)
    }
}

private fun readLspMessage(input: InputStream): String? {
    // Read headers byte by byte until CRLFCRLF
    val header = readUntilDoubleCrLf(input) ?: return null
    val contentLength = header.lines()
        .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
        ?.substringAfter(":")?.trim()?.toIntOrNull()
        ?: return null
    val bytes = ByteArray(contentLength)
    var offset = 0
    while (offset < contentLength) {
        val read = input.read(bytes, offset, contentLength - offset)
        if (read < 0) return null
        offset += read
    }
    return String(bytes, Charsets.UTF_8)
}

private fun readUntilDoubleCrLf(input: InputStream): String? {
    val sb = StringBuilder()
    var prev3 = -1; var prev2 = -1; var prev1 = -1
    while (true) {
        val b = input.read()
        if (b < 0) return if (sb.isNotEmpty()) sb.toString() else null
        sb.append(b.toChar())
        if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) return sb.toString()
        prev3 = prev2; prev2 = prev1; prev1 = b
    }
}

private fun writeLspMessage(output: OutputStream, json: Json, response: org.refactorkit.core.JsonRpcResponse) {
    writeRawLspMessage(output, json.encodeToString(response).toByteArray(Charsets.UTF_8))
}

private fun writeLspNotification(output: OutputStream, json: Json, method: String, params: JsonElement) {
    val notification = JsonRpcRequest(method = method, params = params)
    writeRawLspMessage(output, json.encodeToString(notification).toByteArray(Charsets.UTF_8))
}

private fun writeRawLspMessage(output: OutputStream, body: ByteArray) {
    val header = "Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.UTF_8)
    synchronized(output) {
        output.write(header)
        output.write(body)
        output.flush()
    }
}
