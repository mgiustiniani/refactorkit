package org.refactorkit.lsp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcException
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LspSessionTest {

    private fun createProject(vararg files: Pair<String, String>): String {
        val root = Files.createTempDirectory("rk-lsp-test")
        for ((rel, content) in files) {
            val file = root.resolve(rel)
            Files.createDirectories(file.parent)
            file.writeText(content)
        }
        return root.toString()
    }

    private fun initializeParams(root: String): JsonObject = buildJsonObject {
        put("rootUri", Paths.get(root).toUri().toString())
    }

    private fun textDocumentParams(root: String, file: String): JsonObject = buildJsonObject {
        put("textDocument", buildJsonObject {
            put("uri", Paths.get(root).resolve(file).toUri().toString())
        })
    }

    @Test
    fun initializeAdvertisesCodeActionsSemanticTokensAndDiagnostics() {
        val root = createProject(
            "src/main/java/com/example/Foo.java" to "package com.example;\npublic class Foo {}\n",
        )
        val session = LspSession()
        val result = session.dispatch("initialize", initializeParams(root)) as JsonObject
        val capabilities = result["capabilities"]!!.jsonObject

        assertEquals("true", capabilities["codeActionProvider"]!!.jsonPrimitive.content)
        assertTrue(capabilities["semanticTokensProvider"]!!.jsonObject["legend"]!!.jsonObject["tokenTypes"]!!.jsonArray.isNotEmpty())
        assertEquals("refactorkit", capabilities["diagnosticProvider"]!!.jsonObject["identifier"]!!.jsonPrimitive.content)
        val commands = capabilities["executeCommandProvider"]!!.jsonObject["commands"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        assertTrue(commands.contains("refactorkit.extractMethod"))
        assertTrue(commands.contains("refactorkit.changeSignature.renameParameter"))
        assertTrue(commands.contains("refactorkit.changeSignature.addParameter"))
        assertTrue(commands.contains("refactorkit.changeSignature.reorderParameters"))
        assertTrue(commands.contains("refactorkit.changeSignature.removeParameter"))
    }

    @Test
    fun codeActionReturnsOrganizeAndSymbolRefactorCommands() {
        val root = createProject(
            "src/main/java/com/example/Foo.java" to "package com.example;\npublic class Foo {}\n",
        )
        val session = LspSession()
        session.dispatch("initialize", initializeParams(root))

        val result = session.dispatch("textDocument/codeAction", buildJsonObject {
            put("textDocument", textDocumentParams(root, "src/main/java/com/example/Foo.java")["textDocument"]!!)
            put("range", buildJsonObject {
                put("start", buildJsonObject { put("line", 1); put("character", 14) })
                put("end", buildJsonObject { put("line", 1); put("character", 17) })
            })
        }) as JsonArray
        val titles = result.map { it.jsonObject["title"]!!.jsonPrimitive.content }

        assertTrue(titles.any { it.contains("Organize imports") })
        assertTrue(titles.any { it.contains("Rename Foo") })
        assertTrue(titles.any { it.contains("Safe delete Foo") })
    }

    @Test
    fun codeActionOnMethodSymbolIncludesChangeSignatureActions() {
        val root = createProject(
            "src/main/java/com/example/UserService.java" to """
                package com.example;
                class UserService {
                    String findName(String id, boolean unused) { return id; }
                }
            """.trimIndent() + "\n",
        )
        val session = LspSession()
        session.dispatch("initialize", initializeParams(root))
        val result = session.dispatch("textDocument/codeAction", buildJsonObject {
            put("textDocument", textDocumentParams(root, "src/main/java/com/example/UserService.java")["textDocument"]!!)
            put("range", buildJsonObject {
                put("start", buildJsonObject { put("line", 2); put("character", 11) })
                put("end",   buildJsonObject { put("line", 2); put("character", 19) })
            })
        }) as JsonArray
        val titles = result.map { it.jsonObject["title"]!!.jsonPrimitive.content }
        assertTrue(titles.any { it.contains("Change signature") }, "expected Change signature: $titles")
        assertTrue(titles.any { it.contains("Add parameter") }, "expected Add parameter: $titles")
        assertTrue(titles.any { it.contains("Reorder parameters") }, "expected Reorder parameters: $titles")
        assertTrue(titles.any { it.contains("Remove parameter") }, "expected Remove parameter: $titles")
    }

    @Test
    fun executeCommandChangeSignatureRemoveParameterReturnsDiff() {
        val root = createProject(
            "src/main/java/com/example/UserService.java" to """
                package com.example;
                class UserService {
                    String findName(String id, boolean unused) { return id; }
                    String local() { return findName("a", true); }
                }
            """.trimIndent() + "\n",
        )
        val session = LspSession()
        session.dispatch("initialize", initializeParams(root))
        val fileUri = Paths.get(root).resolve("src/main/java/com/example/UserService.java").toUri().toString()
        val result = session.dispatch("workspace/executeCommand", buildJsonObject {
            put("command", "refactorkit.changeSignature.removeParameter")
            put("arguments", buildJsonArray {
                add(buildJsonObject {
                    put("symbol", "com.example.UserService#findName")
                    put("name", "unused")
                })
            } as kotlinx.serialization.json.JsonElement)
        }) as JsonObject
        val changes = result["changes"]!!.jsonObject
        assertTrue(changes.containsKey(fileUri), "expected edit for $fileUri in $changes")
    }

    @Test
    fun semanticTokensFullReturnsSymbolTokens() {
        val root = createProject(
            "src/main/java/com/example/Foo.java" to "package com.example;\npublic class Foo {\n  private String name;\n  public String name() { return name; }\n}\n",
        )
        val session = LspSession()
        session.dispatch("initialize", initializeParams(root))

        val result = session.dispatch("textDocument/semanticTokens/full", textDocumentParams(root, "src/main/java/com/example/Foo.java")) as JsonObject
        val data = result["data"]!!.jsonArray

        assertTrue(data.size >= 5, "expected at least one encoded semantic token")
    }

    @Test
    fun initializePublishesDiagnostics() {
        val root = createProject(
            "src/main/java/com/example/Foo.java" to "package com.other;\npublic class Foo {}\n",
        )
        val session = LspSession()
        val notifications = mutableListOf<Pair<String, JsonObject>>()
        session.onNotification = { method, params -> notifications += method to params.jsonObject }

        session.dispatch("initialize", initializeParams(root))

        val publish = notifications.firstOrNull { it.first == "textDocument/publishDiagnostics" }
        assertTrue(publish != null, "expected publishDiagnostics notification")
        val diagnostics = publish!!.second["diagnostics"]!!.jsonArray
        assertTrue(diagnostics.any { it.jsonObject["message"]!!.jsonPrimitive.content.contains("Package declaration") })
    }

    @Test
    fun executeCommandExtractMethodReturnsWorkspaceEdit() {
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
        val session = LspSession()
        session.dispatch("initialize", initializeParams(root))

        val result = session.dispatch("workspace/executeCommand", buildJsonObject {
            put("command", "refactorkit.extractMethod")
            put("arguments", JsonArray(listOf(buildJsonObject {
                put("file", "src/main/java/com/example/App.java")
                put("startLine", "4")
                put("endLine", "4")
                put("methodName", "printA")
            })))
        }) as JsonObject
        val changes = result["documentChanges"]!!.jsonArray
        assertTrue(changes.isNotEmpty())
    }

    @Test
    fun executeCommandRenameParameterReturnsWorkspaceEdit() {
        val root = createProject(
            "src/main/java/com/example/UserService.java" to """
                package com.example;
                public class UserService {
                    public String findName(String id) { return id; }
                }
            """.trimIndent() + "\n",
        )
        val session = LspSession()
        session.dispatch("initialize", initializeParams(root))

        val result = session.dispatch("workspace/executeCommand", buildJsonObject {
            put("command", "refactorkit.changeSignature.renameParameter")
            put("arguments", JsonArray(listOf(buildJsonObject {
                put("symbol", "com.example.UserService#findName")
                put("oldName", "id")
                put("newName", "userId")
            })))
        }) as JsonObject
        val changes = result["documentChanges"]!!.jsonArray
        assertTrue(changes.isNotEmpty())
    }

    @Test
    fun workspaceEditIncludesDocumentChangesForFileRename() {
        val root = createProject(
            "src/main/java/com/example/UserManager.java" to "package com.example;\npublic class UserManager {}\n",
        )
        val session = LspSession()
        session.dispatch("initialize", initializeParams(root))

        val result = session.dispatch("workspace/executeCommand", buildJsonObject {
            put("command", "refactorkit.renameClass")
            put("arguments", JsonArray(listOf(buildJsonObject {
                put("symbol", "com.example.UserManager")
                put("newName", "AccountManager")
            })))
        }) as JsonObject
        val documentChanges = result["documentChanges"]!!.jsonArray

        assertTrue(documentChanges.any { it.jsonObject["kind"]?.jsonPrimitive?.content == "rename" })
    }

    @Test
    fun executeCommandApplyPlanRejectsUnknownPlanId() {
        val root = createProject(
            "src/main/java/com/example/Foo.java" to "package com.example;\npublic class Foo {}\n",
        )
        val session = LspSession()
        session.dispatch("initialize", initializeParams(root))

        val ex = assertFailsWith<JsonRpcException> {
            session.dispatch("workspace/executeCommand", buildJsonObject {
                put("command", "refactorkit.applyPlan")
                put("arguments", JsonArray(listOf(buildJsonObject { put("planId", "plan-missing") })))
            })
        }

        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, ex.code)
        assertTrue(ex.message.contains("Plan not found"))
        assertFalse(ex.message.contains("Exception"), ex.message)
    }

    @Test
    fun executeCommandUnknownCommandReturnsInvalidParamsWithoutStackTrace() {
        val root = createProject(
            "src/main/java/com/example/Foo.java" to "package com.example;\npublic class Foo {}\n",
        )
        val session = LspSession()
        session.dispatch("initialize", initializeParams(root))

        val ex = assertFailsWith<JsonRpcException> {
            session.dispatch("workspace/executeCommand", buildJsonObject {
                put("command", "refactorkit.notImplemented")
            })
        }

        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, ex.code)
        assertTrue(ex.message.contains("Unknown command"))
        assertFalse(ex.message.contains("\tat "), ex.message)
    }
}
