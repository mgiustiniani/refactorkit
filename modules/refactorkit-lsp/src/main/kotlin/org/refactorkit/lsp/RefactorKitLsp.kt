package org.refactorkit.lsp

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.JsonRpcRequest
import org.refactorkit.core.ProtocolLimits
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
        val message = try {
            readLspMessage(input) ?: break
        } catch (error: IllegalArgumentException) {
            writeLspMessage(output, json, errorResponse(
                JsonNull,
                JsonRpcErrorCodes.INVALID_REQUEST,
                error.message ?: "Invalid LSP frame",
            ))
            break
        }

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
            errorResponse(request.id, e)
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
    require(contentLength in 0..ProtocolLimits.MAX_LSP_FRAME_BYTES) {
        "LSP frame exceeds ${ProtocolLimits.MAX_LSP_FRAME_BYTES} bytes"
    }
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
    val encoded = json.encodeToString(response).toByteArray(Charsets.UTF_8)
    if (encoded.size <= ProtocolLimits.MAX_LSP_RESPONSE_BYTES) {
        writeRawLspMessage(output, encoded)
        return
    }

    val message = "LSP response exceeds ${ProtocolLimits.MAX_LSP_RESPONSE_BYTES} bytes"
    val error = errorResponse(response.id, JsonRpcErrorCodes.INTERNAL_ERROR, message)
    val bounded = json.encodeToString(error).toByteArray(Charsets.UTF_8)
    val safeError = if (bounded.size <= ProtocolLimits.MAX_LSP_RESPONSE_BYTES) {
        bounded
    } else {
        json.encodeToString(errorResponse(JsonNull, JsonRpcErrorCodes.INTERNAL_ERROR, message))
            .toByteArray(Charsets.UTF_8)
    }
    writeRawLspMessage(output, safeError)
}

private fun writeLspNotification(output: OutputStream, json: Json, method: String, params: JsonElement) {
    val notification = JsonRpcRequest(method = method, params = params)
    val encoded = json.encodeToString(notification).toByteArray(Charsets.UTF_8)
    if (encoded.size <= ProtocolLimits.MAX_LSP_RESPONSE_BYTES) {
        writeRawLspMessage(output, encoded)
    } else {
        val warning = JsonRpcRequest(
            method = "window/logMessage",
            params = kotlinx.serialization.json.JsonObject(mapOf(
                "type" to kotlinx.serialization.json.JsonPrimitive(2),
                "message" to kotlinx.serialization.json.JsonPrimitive(
                    "RefactorKit omitted an oversized LSP notification",
                ),
            )),
        )
        writeRawLspMessage(output, json.encodeToString(warning).toByteArray(Charsets.UTF_8))
    }
}

private fun writeRawLspMessage(output: OutputStream, body: ByteArray) {
    require(body.size <= ProtocolLimits.MAX_LSP_RESPONSE_BYTES) {
        "LSP output frame exceeds ${ProtocolLimits.MAX_LSP_RESPONSE_BYTES} bytes"
    }
    val header = "Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.UTF_8)
    synchronized(output) {
        output.write(header)
        output.write(body)
        output.flush()
    }
}
