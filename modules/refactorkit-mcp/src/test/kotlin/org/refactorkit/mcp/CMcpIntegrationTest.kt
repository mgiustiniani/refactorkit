package org.refactorkit.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * C MCP integration through the real session: initialize/tools/list/tools/call with
 * real requests, schemas and exact responses, plus a stale plan and an unavailable
 * provider. Exercises the library entry point, not isolated planner calls.
 */
class CMcpIntegrationTest {

    private val clang = Paths.get("/usr/bin/clang-22")
    private val clangd = Paths.get("/usr/bin/clangd")
    private val clangFormat = Paths.get("/usr/bin/clang-format")

    private fun clangAvailable(): Boolean =
        Files.isExecutable(clang) && Files.isExecutable(clangd) && Files.isExecutable(clangFormat)

    private fun createProject(vararg files: Pair<String, String>): String {
        val root = Files.createTempDirectory("rk-mcp-c-test")
        for ((rel, content) in files) {
            val file = root.resolve(rel)
            Files.createDirectories(file.parent)
            file.writeText(content)
        }
        return root.toString()
    }

    private fun contentText(result: JsonObject): String =
        result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content

    @Test
    fun availableRefactoringsListsCatalogueForC() {
        val session = McpSession()
        val result = session.dispatch("tools/call", buildJsonObject {
            put("name", "available_refactorings")
            put("arguments", buildJsonObject { put("languageId", "c") })
        }) as JsonObject
        val text = contentText(result)
        assertTrue(text.contains("renamePrefix"), text)
        assertTrue(text.contains("relocateComponent"), text)
        session.close()
    }

    @Test
    fun projectScanInventoriesCSources() {
        val root = createProject("src/main.c" to "int main(void) { return 0; }\n")
        val session = McpSession()
        val result = session.dispatch("tools/call", buildJsonObject {
            put("name", "project_scan")
            put("arguments", buildJsonObject { put("root", root) })
        }) as JsonObject
        assertTrue(contentText(result).contains("Files: 1"), contentText(result))
        session.close()
    }

    @Test
    fun cPreviewRequiresExplicitClangToolchain() {
        val root = createProject("src/main.c" to "#include <stdio.h>\nint main(void) { return 0; }\n")
        val session = McpSession()
        session.dispatch("tools/call", buildJsonObject {
            put("name", "project_scan")
            put("arguments", buildJsonObject { put("root", root) })
        })
        // No clang/clangd/clangFormat: the provider is unavailable and must be an error
        // tool result (MCP error content), never a silently applied capability.
        val result = session.dispatch("tools/call", buildJsonObject {
            put("name", "preview_refactoring")
            put("arguments", buildJsonObject {
                put("operation", "organizeIncludes")
                put("languageId", "c")
                put("arguments", buildJsonObject { put("file", "src/main.c") })
            })
        }) as JsonObject
        assertTrue(result["isError"]!!.jsonPrimitive.content.toBoolean(), "unavailable provider must be an error result")
        val text = contentText(result)
        assertTrue(text.contains("clang"), text)
        session.close()
    }

    @Test
    fun cPreviewReturnsPlanWithAffectedFiles() {
        if (!clangAvailable()) return // clang toolchain not installed; MCP C integration not run
        val root = createProject("src/main.c" to "#include <stdlib.h>\n#include <stdio.h>\nint main(void) { return 0; }\n")
        val session = McpSession()
        session.dispatch("tools/call", buildJsonObject {
            put("name", "project_scan")
            put("arguments", buildJsonObject { put("root", root) })
        })
        val result = session.dispatch("tools/call", buildJsonObject {
            put("name", "preview_refactoring")
            put("arguments", buildJsonObject {
                put("operation", "organizeIncludes")
                put("languageId", "c")
                put("arguments", buildJsonObject { put("file", "src/main.c") })
                put("clang", clang.toString())
                put("clangd", clangd.toString())
                put("clangFormat", clangFormat.toString())
            })
        }) as JsonObject
        val text = contentText(result)
        assertTrue(text.contains("Status   : PREVIEW"), text)
        assertTrue(text.contains("Affected : 1 file(s)"), text)
        session.close()
    }

    private fun previewOrganizeIncludes(session: McpSession, root: String): String =
        contentText(session.dispatch("tools/call", buildJsonObject {
            put("name", "preview_refactoring")
            put("arguments", buildJsonObject {
                put("operation", "organizeIncludes")
                put("languageId", "c")
                put("arguments", buildJsonObject { put("file", "src/main.c") })
                put("clang", clang.toString())
                put("clangd", clangd.toString())
                put("clangFormat", clangFormat.toString())
            })
        }) as JsonObject)

    @Test
    fun cApplyAndRollbackThroughMcpSession() {
        if (!clangAvailable()) return // clang toolchain not installed; MCP C integration not run
        val root = createProject("src/main.c" to "#include <stdlib.h>\n#include <stdio.h>\nint main(void) { return 0; }\n")
        val rootPath = Paths.get(root)
        val original = rootPath.resolve("src/main.c").readText()
        val session = McpSession()
        session.dispatch("tools/call", buildJsonObject {
            put("name", "project_scan")
            put("arguments", buildJsonObject { put("root", root) })
        })
        val preview = previewOrganizeIncludes(session, root)
        val planId = Regex("Plan ID\\s+: (\\S+)").find(preview)!!.groupValues[1]

        val applied = contentText(session.dispatch("tools/call", buildJsonObject {
            put("name", "apply_refactoring")
            put("arguments", buildJsonObject { put("planId", planId) })
        }) as JsonObject)
        assertTrue(applied.contains("Applied successfully"), applied)
        val transactionId = Regex("Transaction ID: (\\S+)").find(applied)!!.groupValues[1]
        assertTrue(rootPath.resolve("src/main.c").readText() != original, "apply must change the file")

        val rolled = contentText(session.dispatch("tools/call", buildJsonObject {
            put("name", "rollback_refactoring")
            put("arguments", buildJsonObject { put("transactionId", transactionId) })
        }) as JsonObject)
        assertTrue(rolled.contains("Rolled back"), rolled)
        assertEquals(original, rootPath.resolve("src/main.c").readText(), "rollback must restore exact bytes")
        session.close()
    }

    @Test
    fun cStalePlanRejectedThroughMcp() {
        if (!clangAvailable()) return
        val root = createProject("src/main.c" to "#include <stdlib.h>\n#include <stdio.h>\nint main(void) { return 0; }\n")
        val rootPath = Paths.get(root)
        val session = McpSession()
        session.dispatch("tools/call", buildJsonObject {
            put("name", "project_scan")
            put("arguments", buildJsonObject { put("root", root) })
        })
        val preview = previewOrganizeIncludes(session, root)
        val planId = Regex("Plan ID\\s+: (\\S+)").find(preview)!!.groupValues[1]

        // Mutate the workspace after the preview so the snapshot hash no longer matches.
        rootPath.resolve("src/main.c").writeText("#include <stdlib.h>\nint main(void) { return 0; }\n")

        val applied = contentText(session.dispatch("tools/call", buildJsonObject {
            put("name", "apply_refactoring")
            put("arguments", buildJsonObject { put("planId", planId) })
        }) as JsonObject)
        assertTrue(applied.contains("Apply refused") && applied.contains("Project changed since preview"), applied)
        session.close()
    }
}
