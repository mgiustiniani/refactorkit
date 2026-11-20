package org.refactorkit.cli.previewcommand

import kotlinx.serialization.json.*
import org.refactorkit.cli.RefactorKitCli
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcException
import org.refactorkit.daemon.DaemonSession
import org.refactorkit.lsp.LspSession
import org.refactorkit.mcp.McpSession
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertFalse

internal class CManagedClangdRefusalFixture : AutoCloseable {
    private val root = Files.createTempDirectory("rk-c-managed-clangd-refusal-")
    private val tools = Path.of(requireNotNull(System.getenv("REFACTORKIT_C_QUALIFICATION_TOOLCHAIN"))).resolve("usr/bin")
    private val source = "static int value = 7;\nint main(void) { return value == 7 ? 0 : 1; }\n"
    private val database = buildJsonArray {
        add(buildJsonObject {
            put("directory", root.toString())
            put("file", "main.c")
            put("arguments", JsonArray(listOf(clang("clang-22"), "-std=c17", "-Imissing-include", "-c", "main.c")
                .map(::JsonPrimitive)))
        })
    }.toString()
    private val request = buildJsonObject {
        put("operation", "renameSymbol")
        put("languageId", "c")
        put("clang", clang("clang-22"))
        put("clangd", clang("clangd"))
        put("clangFormat", clang("clang-format"))
        put("arguments", buildJsonObject {
            put("file", "main.c")
            put("symbol", "value")
            put("newName", "renamed")
            put("line", 0)
            put("character", 11)
        })
    }
    private lateinit var observation: Observation

    init {
        Files.writeString(root.resolve("main.c"), source)
        Files.writeString(root.resolve("compile_commands.json"), database)
    }

    fun preview(surface: String) {
        observation = when (surface) {
            "CLI" -> observeCli()
            "daemon" -> DaemonSession().use { session ->
                session.dispatch("project.open", buildJsonObject { put("root", root.toString()) })
                observeRpc { session.dispatch("c.preview", request) }
            }
            "LSP" -> observeLsp()
            "MCP" -> observeMcp()
            else -> error("Unknown C preview surface: $surface")
        }
    }

    fun assertRefusal() {
        assertEquals("c.clangdConfigurationRefused", observation.typedCode, observation.detail)
    }

    fun assertUnchanged() {
        assertEquals(source, Files.readString(root.resolve("main.c")))
        assertEquals(database, Files.readString(root.resolve("compile_commands.json")))
        assertFalse(Files.exists(root.resolve("missing-include")))
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
    }

    private fun observeCli(): Observation = synchronized(RefactorKitCli::class.java) {
        val original = System.err
        val bytes = ByteArrayOutputStream()
        val command = listOf("c", "rename", root.toString(), "--preview", "--file", "main.c",
            "--symbol", "value", "--to", "renamed", "--line", "0", "--character", "11",
            "--clang", clang("clang-22"), "--clangd", clang("clangd"), "--clang-format", clang("clang-format"))
        val code = PrintStream(bytes, true, Charsets.UTF_8).use { capture ->
            try {
                System.setErr(capture)
                RefactorKitCli().run(command)
            } finally {
                System.setErr(original)
            }
        }
        val detail = bytes.toString(Charsets.UTF_8)
        val refusal = Regex("C command refused \\[([^]]+)]").find(detail)?.groupValues?.get(1)
        Observation(refusal.takeIf { code == 1 }, "CLI exit $code: $detail")
    }

    private fun observeLsp(): Observation {
        val session = LspSession()
        session.dispatch("initialize", buildJsonObject { put("rootUri", root.toUri().toString()) })
        session.dispatch("initialized", null)
        return try {
            observeRpc {
                session.dispatch("workspace/executeCommand", buildJsonObject {
                    put("command", "refactorkit.c.preview")
                    put("arguments", JsonArray(listOf(request)))
                })
            }
        } finally {
            session.dispatch("shutdown", null)
            session.dispatch("exit", null)
        }
    }

    private fun observeMcp(): Observation = McpSession().use { session ->
        session.dispatch("tools/call", buildJsonObject {
            put("name", "project_scan")
            put("arguments", buildJsonObject { put("root", root.toString()) })
        })
        val result = session.dispatch("tools/call", buildJsonObject {
            put("name", "preview_refactoring")
            put("arguments", request)
        }).jsonObject
        val message = result.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
        val fields = message.lineSequence().filter { ':' in it }.associate { line ->
            line.substringBefore(':').trim() to line.substringAfter(':').trim()
        }
        val refused = result["isError"]?.jsonPrimitive?.booleanOrNull != true &&
            fields["Status"] == "REFUSED" && fields["Affected"] == "0 file(s)"
        Observation(fields["Refusal"].takeIf { refused }, result.toString())
    }

    private fun observeRpc(call: () -> JsonElement): Observation = try {
        Observation(null, "Preview unexpectedly returned ${call()}")
    } catch (failure: JsonRpcException) {
        val code = (failure.data as? JsonObject)?.get("refusalCode")?.jsonPrimitive?.content
        Observation(code.takeIf { failure.code == JsonRpcErrorCodes.PLAN_REFUSED }, failure.toString())
    } catch (failure: IllegalArgumentException) {
        Observation(null, failure.toString())
    }

    private fun clang(name: String) = tools.resolve(name).toString()

    override fun close() {
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private data class Observation(val typedCode: String?, val detail: String)
}
