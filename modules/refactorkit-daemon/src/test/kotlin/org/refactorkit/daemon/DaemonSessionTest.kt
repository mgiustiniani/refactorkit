package org.refactorkit.daemon

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcException
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DaemonSessionTest {

    private fun createProject(vararg files: Pair<String, String>): String {
        val root = Files.createTempDirectory("rk-daemon-test")
        for ((rel, content) in files) {
            val file = root.resolve(rel)
            Files.createDirectories(file.parent)
            file.writeText(content)
        }
        return root.toString()
    }

    private fun params(vararg pairs: Pair<String, String>): JsonObject =
        buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    @Test
    fun projectOpenReturnsFileCount() {
        val root = createProject(
            "src/main/java/com/example/Foo.java" to "package com.example;\npublic class Foo {}\n",
        )
        val session = DaemonSession()
        val result = session.dispatch("project.open", params("root" to root)) as JsonObject
        assertEquals(root, result["root"]!!.jsonPrimitive.content)
        assertTrue((result["fileCount"]?.jsonPrimitive?.content?.toInt() ?: 0) >= 1)
    }

    @Test
    fun projectSummaryRequiresOpenProject() {
        val session = DaemonSession()
        val ex = assertFailsWith<JsonRpcException> { session.dispatch("project.summary", null) }
        assertEquals(JsonRpcErrorCodes.PROJECT_NOT_OPEN, ex.code)
    }

    @Test
    fun symbolSearchFindsClass() {
        val root = createProject(
            "src/main/java/com/example/UserManager.java" to "package com.example;\npublic class UserManager {}\n",
        )
        val session = DaemonSession()
        session.dispatch("project.open", params("root" to root))
        val results = session.dispatch("symbol.search", params("query" to "UserManager"))
        assertTrue(results.toString().contains("UserManager"))
    }

    @Test
    fun symbolDefinitionReturnsLocation() {
        val root = createProject(
            "src/main/java/com/example/UserManager.java" to "package com.example;\npublic class UserManager {}\n",
        )
        val session = DaemonSession()
        session.dispatch("project.open", params("root" to root))
        val result = session.dispatch("symbol.definition", params("symbol" to "com.example.UserManager")) as JsonObject

        assertEquals("com.example.UserManager", result["id"]!!.jsonPrimitive.content)
        assertEquals("src/main/java/com/example/UserManager.java", result["file"]!!.jsonPrimitive.content)
    }

    @Test
    fun previewRenameReturnsPlanId() {
        val root = createProject(
            "src/main/java/com/example/UserManager.java" to "package com.example;\npublic class UserManager {}\n",
        )
        val session = DaemonSession()
        session.dispatch("project.open", params("root" to root))
        val planResult = session.dispatch("refactor.preview", buildJsonObject {
            put("operation", "renameClass")
            put("symbol", "com.example.UserManager")
            put("arguments", buildJsonObject { put("newName", "AccountManager") })
        }) as JsonObject
        assertTrue(planResult["planId"]!!.jsonPrimitive.content.isNotBlank())
        assertEquals("PREVIEW", planResult["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun previewExtractMethodReturnsPlanId() {
        val root = createProject(
            "src/main/java/com/example/App.java" to """
                package com.example;
                public class App {
                    public void run() {
                        System.out.println("a");
                    }
                }
            """.trimIndent() + "\n",
        )
        val session = DaemonSession()
        session.dispatch("project.open", params("root" to root))
        val result = session.dispatch("refactor.preview", buildJsonObject {
            put("operation", "extractMethod")
            put("arguments", buildJsonObject {
                put("file", "src/main/java/com/example/App.java")
                put("startLine", "4")
                put("endLine", "4")
                put("methodName", "printA")
            })
        }) as JsonObject
        assertEquals("extractMethod", result["operation"]!!.jsonPrimitive.content)
        assertEquals("PREVIEW", result["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun previewRenameParameterReturnsPlanId() {
        val root = createProject(
            "src/main/java/com/example/UserService.java" to """
                package com.example;
                public class UserService {
                    public String findName(String id) { return id; }
                }
            """.trimIndent() + "\n",
        )
        val session = DaemonSession()
        session.dispatch("project.open", params("root" to root))
        val result = session.dispatch("refactor.preview", buildJsonObject {
            put("operation", "changeSignature.renameParameter")
            put("symbol", "com.example.UserService#findName")
            put("arguments", buildJsonObject {
                put("oldName", "id")
                put("newName", "userId")
            })
        }) as JsonObject
        assertEquals("changeSignature.renameParameter", result["operation"]!!.jsonPrimitive.content)
        assertEquals("PREVIEW", result["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun previewStructuralChangeSignatureOperationsReturnPlanIds() {
        val root = createProject(
            "src/main/java/com/example/UserService.java" to """
                package com.example;
                class UserService {
                    String findName(String id, boolean unused) { return id; }
                    String local() { return findName("a", true); }
                }
            """.trimIndent() + "\n",
        )
        val session = DaemonSession()
        session.dispatch("project.open", params("root" to root))

        val add = session.dispatch("refactor.preview", buildJsonObject {
            put("operation", "changeSignature.addParameter")
            put("symbol", "com.example.UserService#findName")
            put("arguments", buildJsonObject {
                put("type", "int")
                put("name", "limit")
                put("default", "10")
            })
        }) as JsonObject
        assertEquals("changeSignature.addParameter", add["operation"]!!.jsonPrimitive.content)
        assertEquals("PREVIEW", add["status"]!!.jsonPrimitive.content)

        val reorder = session.dispatch("refactor.preview", buildJsonObject {
            put("operation", "changeSignature.reorderParameters")
            put("symbol", "com.example.UserService#findName")
            put("arguments", buildJsonObject { put("order", "unused,id") })
        }) as JsonObject
        assertEquals("changeSignature.reorderParameters", reorder["operation"]!!.jsonPrimitive.content)

        val remove = session.dispatch("refactor.preview", buildJsonObject {
            put("operation", "changeSignature.removeParameter")
            put("symbol", "com.example.UserService#findName")
            put("arguments", buildJsonObject { put("name", "unused") })
        }) as JsonObject
        assertEquals("changeSignature.removeParameter", remove["operation"]!!.jsonPrimitive.content)
    }

    @Test
    fun methodNotFoundThrowsCorrectCode() {
        val session = DaemonSession()
        val ex = assertFailsWith<JsonRpcException> { session.dispatch("bogus.method", null) }
        assertEquals(JsonRpcErrorCodes.METHOD_NOT_FOUND, ex.code)
    }

    @Test
    fun diagnosticsReturnsEmptyListForCleanProject() {
        val root = createProject(
            "src/main/java/com/example/Foo.java" to "package com.example;\npublic class Foo {}\n",
        )
        val session = DaemonSession()
        session.dispatch("project.open", params("root" to root))
        val diags = session.dispatch("diagnostics", null)
        assertEquals("[]", diags.toString())
    }
}
