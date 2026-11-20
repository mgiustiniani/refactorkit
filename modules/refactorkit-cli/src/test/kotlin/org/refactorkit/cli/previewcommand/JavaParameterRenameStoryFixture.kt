package org.refactorkit.cli.previewcommand

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.core.JournalState
import org.refactorkit.core.TransactionLog
import org.refactorkit.daemon.DaemonSession
import org.refactorkit.mcp.McpSession
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class JavaParameterRenameStoryFixture(private val root: Path, private val source: Path, private val sdkHome: Path) {
    private val original = Files.readAllBytes(source)
    private val originalPom = Files.readAllBytes(root.resolve("pom.xml"))
    private val renamed = original.decodeToString()
        .replace("execute(String value)", "execute(String item)")
        .replace("helper(value)", "helper(item)")
        .encodeToByteArray()
    private val journal = TransactionLog(root.resolve(".refactorkit/transactions"))
    private val arguments = buildJsonObject {
        put("oldName", "value")
        put("newName", "item")
    }

    fun exercise(daemon: DaemonSession, snapshotHash: String, context: JsonObject) {
        val preview = daemon.dispatch("refactor.preview", buildJsonObject {
            put("operation", OPERATION)
            put("languageId", "java")
            put("symbol", "com.acme.Service#execute")
            put("arguments", arguments)
            put("expectedSnapshotHash", snapshotHash)
            put("javaContext", context)
        }).jsonObject
        assertEquals("PREVIEW", preview.getValue("status").jsonPrimitive.content)
        assertEquals(OPERATION, preview.getValue("operation").jsonPrimitive.content)
        assertContentEquals(original, Files.readAllBytes(source))
        assertTrue(journal.listRecordsReadOnly().isEmpty())
        val applied = daemon.dispatch("refactor.apply", buildJsonObject {
            put("planId", preview.getValue("planId"))
            put("expectedSnapshotHash", snapshotHash)
            put("javaContext", context)
        }).jsonObject
        assertEquals("applied", applied.getValue("status").jsonPrimitive.content)
        assertContentEquals(renamed, Files.readAllBytes(source))
        assertTrue(applied.getValue("diagnostics").jsonArray.none {
            it.jsonObject.getValue("severity").jsonPrimitive.content.equals("error", ignoreCase = true)
        }, applied.toString())
        val rolled = daemon.dispatch("patch.rollback", buildJsonObject {
            put("transactionId", applied.getValue("transactionId"))
            put("expectedSnapshotHash", applied.getValue("snapshotHash"))
            put("javaContext", context)
        }).jsonObject
        assertEquals("rolledBack", rolled.getValue("status").jsonPrimitive.content)
        assertContentEquals(original, Files.readAllBytes(source))
        exerciseMcp()
    }

    private fun exerciseMcp() {
        McpSession().use { mcp ->
            mcpTool(mcp, "project_scan", buildJsonObject { put("root", root.toString()) })
            val context = Json.parseToJsonElement(mcpTool(mcp, "java_project_context", buildJsonObject {
                put("sdkHome", sdkHome.toString())
            })).jsonObject.getValue("javaContext").jsonObject
            val preview = mcpTool(mcp, "preview_refactoring", buildJsonObject {
                put("operation", OPERATION)
                put("languageId", "java")
                put("symbol", "com.acme.Service#execute")
                put("arguments", arguments)
                put("expectedSnapshotHash", context.getValue("expectedSnapshotHash"))
                put("javaContext", context)
            })
            assertTrue(preview.contains("Rename JDT-proven parameter"), preview)
            assertContentEquals(original, Files.readAllBytes(source))
            assertEquals(1, journal.listRecordsReadOnly().size)
            val planId = preview.lineSequence().single { it.trimStart().startsWith("Plan ID") }.substringAfter(':').trim()
            val applied = mcpTool(mcp, "apply_refactoring", buildJsonObject {
                put("planId", planId)
                put("javaContext", context)
            })
            assertTrue(applied.startsWith("Applied successfully."), applied)
            assertContentEquals(renamed, Files.readAllBytes(source))
            val transactionId = applied.lineSequence().single { it.startsWith("Transaction ID:") }.substringAfter(':').trim()
            val diagnostics = mcpTool(mcp, "diagnostics", buildJsonObject {
                put("languageId", "java")
                put("jdkHome", sdkHome.toString())
            })
            assertEquals("No diagnostics.", diagnostics)
            val rolled = mcpTool(mcp, "rollback_refactoring", buildJsonObject {
                put("transactionId", transactionId)
                put("javaContext", context)
            })
            assertEquals("Rolled back transaction $transactionId.", rolled)
        }
    }

    fun assertRestored() {
        assertContentEquals(original, Files.readAllBytes(source))
        assertContentEquals(originalPom, Files.readAllBytes(root.resolve("pom.xml")))
        val records = journal.listRecordsReadOnly()
        assertEquals(2, records.size)
        assertTrue(records.all { it.operation == OPERATION && it.state == JournalState.ROLLED_BACK }, records.toString())
    }

    private fun mcpTool(mcp: McpSession, name: String, arguments: JsonObject): String {
        val result = mcp.dispatch("tools/call", buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        }).jsonObject
        assertFalse(result["isError"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true, result.toString())
        return result.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
    }

    companion object {
        const val OPERATION = "changeSignature.renameParameter"
    }
}
