package org.refactorkit.cli.previewcommand

import kotlinx.serialization.json.*
import org.refactorkit.cli.RefactorKitCli
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.TransactionLog
import org.refactorkit.daemon.DaemonSession
import org.refactorkit.mcp.McpSession
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class CManagedFormattingSurfaceFixture(private val condition: String) : AutoCloseable {
    private val root = Files.createTempDirectory("rk-managed-c-style-")
    private val tools = Path.of(requireNotNull(System.getenv("REFACTORKIT_C_QUALIFICATION_TOOLCHAIN"))).resolve("usr/bin")
    private val journal = root.resolve(".refactorkit/transactions")
    private val source = "src/main.c"
    private val nestedStyle = "src/_clang-format"
    private val expectedSource = """
        int main(void) {
            int result = 7;
            return result == 7 ? 0 : 1;
        }
    """.trimIndent() + "\n"
    private val original = buildMap {
        put(source, "int main(void){int result=7;return result==7?0:1;}\n")
        put(".clang-format", style(2))
        put(nestedStyle, style(4))
        if (condition == "conflicting") put("src/.clang-format", style(3))
        put("compile_commands.json", buildJsonArray {
            add(buildJsonObject {
                put("directory", root.toString())
                put("file", source)
                put("arguments", JsonArray(listOf(tools.resolve("clang-22").toString(), "-std=c17", "-c", source).map(::JsonPrimitive)))
            })
        }.toString())
    }
    private val request = buildJsonObject {
        put("operation", "formatFile")
        put("languageId", "c")
        put("arguments", buildJsonObject { put("file", source) })
        put("clang", tools.resolve("clang-22").toString())
        put("clangd", tools.resolve("clangd").toString())
        put("clangFormat", tools.resolve("clang-format").toString())
    }
    private var outcome = ""
    private var detail = ""
    private var transaction: String? = null

    init {
        original.forEach { (name, text) ->
            val file = root.resolve(name)
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
        }
    }

    fun execute(surface: String) {
        when (surface) {
            "CLI" -> cliLifecycle()
            "daemon" -> DaemonSession().use(::daemonLifecycle)
            "MCP" -> McpSession().use(::mcpLifecycle)
            else -> error("Unknown C formatting surface: $surface")
        }
    }

    private fun cliLifecycle() {
        val command = listOf(
            "c", "format", root.toString(), "--file", source, "--clang", tools.resolve("clang-22").toString(),
            "--clangd", tools.resolve("clangd").toString(), "--clang-format", tools.resolve("clang-format").toString(),
        )
        val preview = cli(command + "--preview")
        detail = preview.second
        unchangedPreview()
        if (preview.first != 0) {
            if (preview.second.contains("clang.formatStyleUnavailable") &&
                preview.second.contains("Both .clang-format and _clang-format exist")) {
                outcome = "ambiguous style refused"
            }
            return
        }
        validatePreview(Json.parseToJsonElement(preview.second).jsonObject)
        val applied = cli(command + "--apply")
        assertEquals(0, applied.first, applied.second)
        val id = Json.parseToJsonElement(applied.second).jsonObject.getValue("transactionId").jsonPrimitive.content
        verifyApplied(id)
        val rolled = cli(listOf("patch", "rollback", id, "--root", root.toString()))
        assertEquals(0, rolled.first, rolled.second)
        assertTrue("Rolled back" in rolled.second, rolled.second)
        outcome = "applied and restored"
    }

    private fun daemonLifecycle(session: DaemonSession) {
        session.dispatch("project.open", buildJsonObject { put("root", root.toString()) })
        val preview = session.dispatch("c.preview", request).jsonObject
        unchangedPreview()
        validatePreview(preview)
        if (condition == "changed") Files.writeString(root.resolve(nestedStyle), style(6))
        val applied = try {
            session.dispatch("refactor.apply", planId(preview.getValue("planId").jsonPrimitive.content)).jsonObject
        } catch (failure: JsonRpcException) {
            detail = failure.message.orEmpty()
            assertEquals(JsonRpcErrorCodes.SNAPSHOT_CHANGED, failure.code, detail)
            outcome = "stale style refused"
            return
        }
        assertEquals("applied", applied.getValue("status").jsonPrimitive.content)
        val id = applied.getValue("transactionId").jsonPrimitive.content
        verifyApplied(id)
        val rolled = session.dispatch("patch.rollback", transactionId(id)).jsonObject
        assertEquals("rolledBack", rolled.getValue("status").jsonPrimitive.content)
        outcome = "applied and restored"
    }

    private fun mcpLifecycle(session: McpSession) {
        fun call(name: String, args: JsonObject): String = session.dispatch("tools/call", buildJsonObject {
            put("name", name)
            put("arguments", args)
        }).jsonObject.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
        call("project_scan", buildJsonObject { put("root", root.toString()) })
        val preview = call("preview_refactoring", request)
        detail = preview
        unchangedPreview()
        assertTrue(preview.contains("Status   : PREVIEW") && preview.contains("c.previewDiagnosticsVerified"), preview)
        val plan = assertNotNull(Regex("Plan ID\\s+: (\\S+)").find(preview)).groupValues[1]
        val applied = call("apply_refactoring", planId(plan))
        assertTrue("Applied successfully" in applied, applied)
        val id = assertNotNull(Regex("Transaction ID: (\\S+)").find(applied)).groupValues[1]
        verifyApplied(id)
        val rolled = call("rollback_refactoring", transactionId(id))
        assertTrue("Rolled back" in rolled && id in rolled, rolled)
        outcome = "applied and restored"
    }

    private fun validatePreview(plan: JsonObject) {
        assertEquals("PREVIEW", plan.getValue("status").jsonPrimitive.content)
        assertEquals(listOf(source), plan.getValue("affectedFiles").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(plan.getValue("warnings").jsonArray.any {
            it.jsonPrimitive.content.startsWith("c.previewDiagnosticsVerified:")
        }, plan.toString())
    }

    private fun unchangedPreview() {
        assertImage(original)
        assertFalse(Files.exists(journal))
    }

    private fun verifyApplied(id: String) {
        transaction = id
        assertImage(original + (source to expectedSource))
        assertHistory(id, "APPLIED")
    }

    private fun assertHistory(id: String, expected: String) {
        val record = TransactionLog(journal).listRecordsReadOnly().single()
        assertEquals(id, record.transaction.id.value)
        assertEquals("formatFile", record.operation)
        assertEquals(expected, record.state.name)
    }

    fun assertOutcome(expected: String) = assertEquals(expected, outcome, detail)

    fun assertIntact() {
        assertImage(if (condition == "changed") original + (nestedStyle to style(6)) else original)
        val id = transaction
        if (id == null) assertFalse(Files.exists(journal)) else assertHistory(id, "ROLLED_BACK")
    }

    private fun assertImage(expected: Map<String, String>) {
        val actual = Files.walk(root).use { files ->
            files.filter(Files::isRegularFile).map(root::relativize).filter { !it.startsWith(".refactorkit") }
                .toList().associate { it.toString() to Files.readString(root.resolve(it)) }
        }
        assertEquals(expected, actual)
    }

    private fun cli(command: List<String>): Pair<Int, String> = synchronized(RefactorKitCli::class.java) {
        val out = System.out
        val err = System.err
        val bytes = ByteArrayOutputStream()
        PrintStream(bytes, true, Charsets.UTF_8).use { stream ->
            try {
                System.setOut(stream)
                System.setErr(stream)
                RefactorKitCli().run(command) to bytes.toString(Charsets.UTF_8)
            } finally {
                System.setOut(out)
                System.setErr(err)
            }
        }
    }

    private fun planId(id: String) = buildJsonObject { put("planId", id) }
    private fun transactionId(id: String) = buildJsonObject { put("transactionId", id) }
    private fun style(width: Int) = "BasedOnStyle: LLVM\nIndentWidth: $width\nAllowShortFunctionsOnASingleLine: None\n"

    override fun close() {
        check(root.toFile().deleteRecursively())
    }
}
