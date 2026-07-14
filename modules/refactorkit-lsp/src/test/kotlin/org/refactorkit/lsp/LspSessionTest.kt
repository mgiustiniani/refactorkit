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
import org.refactorkit.core.RefactorKitVersion
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
        put("capabilities", buildJsonObject {
            put("workspace", buildJsonObject {
                put("workspaceEdit", buildJsonObject { put("documentChanges", true) })
            })
        })
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
        val serverInfo = result["serverInfo"]!!.jsonObject

        assertEquals(RefactorKitVersion.NAME, serverInfo["name"]!!.jsonPrimitive.content)
        assertEquals(RefactorKitVersion.VERSION, serverInfo["version"]!!.jsonPrimitive.content)
        assertEquals("true", capabilities["codeActionProvider"]!!.jsonPrimitive.content)
        val sync = capabilities["textDocumentSync"]!!.jsonObject
        assertEquals("1", sync["change"]!!.jsonPrimitive.content)
        assertEquals("true", sync["save"]!!.jsonObject["includeText"]!!.jsonPrimitive.content)
        assertTrue(capabilities["semanticTokensProvider"]!!.jsonObject["legend"]!!.jsonObject["tokenTypes"]!!.jsonArray.isNotEmpty())
        assertEquals("refactorkit", capabilities["diagnosticProvider"]!!.jsonObject["identifier"]!!.jsonPrimitive.content)
        assertEquals("true", capabilities["documentFormattingProvider"]!!.jsonPrimitive.content)
        val experimental = capabilities["experimental"]!!.jsonObject
        val languageKernel = experimental["refactorkitLanguageKernel"]!!.jsonObject
        assertEquals(listOf("java", "javascript", "kotlin", "typescript"), languageKernel["adapters"]!!.jsonArray.map {
            it.jsonObject["languageId"]!!.jsonPrimitive.content
        })
        val kotlinAdapter = languageKernel["adapters"]!!.jsonArray.single {
            it.jsonObject["languageId"]!!.jsonPrimitive.content == "kotlin"
        }.jsonObject
        val kotlinDiagnostics = kotlinAdapter["capabilities"]!!.jsonArray.single {
            it.jsonObject["operation"]!!.jsonPrimitive.content == "diagnostics"
        }.jsonObject
        assertEquals("experimental", kotlinDiagnostics["stability"]!!.jsonPrimitive.content)
        assertEquals("kotlin-compiler-diagnostics-k2-v1", kotlinDiagnostics["backend"]!!.jsonPrimitive.content)
        val ownership = experimental["refactorkitSemanticOwnership"]!!.jsonObject
        assertEquals("client-managed-native-lsp", ownership["typescript"]!!.jsonPrimitive.content)
        assertEquals("refactorkit-external-compiler-read-only-via-cli-daemon-mcp",
            ownership["kotlin"]!!.jsonPrimitive.content)
        assertEquals(listOf("cli", "daemon", "mcp"), ownership["managedMutationSurfaces"]!!.jsonArray.map {
            it.jsonPrimitive.content
        })
        val commands = capabilities["executeCommandProvider"]!!.jsonObject["commands"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        assertTrue(commands.contains("refactorkit.extractMethod"))
        assertTrue(commands.contains("refactorkit.changeSignature.renameParameter"))
        assertTrue(commands.contains("refactorkit.changeSignature.addParameter"))
        assertTrue(commands.contains("refactorkit.changeSignature.reorderParameters"))
        assertTrue(commands.contains("refactorkit.changeSignature.removeParameter"))
        assertTrue(commands.contains("refactorkit.formatFile"))
    }

    @Test
    fun typescriptLspOwnershipProvidesStructuralOutlineWithoutFalseJavaServices() {
        val root = createProject("src/service.ts" to "export class Service {}\n")
        val file = "src/service.ts"
        val uri = Paths.get(root).resolve(file).toUri().toString()
        val session = LspSession()
        session.dispatch("initialize", initializeParams(root))
        session.dispatch("textDocument/didOpen", buildJsonObject {
            put("textDocument", buildJsonObject {
                put("uri", uri); put("languageId", "typescript"); put("version", 1)
                put("text", "export class Service {}\n")
            })
        })

        val symbols = session.dispatch("textDocument/documentSymbol", textDocumentParams(root, file)).jsonArray
        assertEquals(listOf("Service"), symbols.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertTrue(session.dispatch("textDocument/codeAction", buildJsonObject {
            put("textDocument", buildJsonObject { put("uri", uri) })
        }).jsonArray.isEmpty())
        assertTrue(session.dispatch("textDocument/semanticTokens/full", textDocumentParams(root, file))
            .jsonObject["data"]!!.jsonArray.isEmpty())
        assertTrue(session.dispatch("textDocument/formatting", textDocumentParams(root, file)).jsonArray.isEmpty())
    }

    @Test
    fun nativeFormattingReturnsClientManagedEditsWithoutWritingWorkspace() {
        val file = "src/main/java/com/example/Example.java"
        val root = createProject(
            file to "package com.example;\npublic class Example{void run(){int value=1;}}\n",
        )
        val session = LspSession()
        session.dispatch("initialize", initializeParams(root))

        val edits = session.dispatch("textDocument/formatting", textDocumentParams(root, file)) as JsonArray

        assertTrue(edits.isNotEmpty())
        assertTrue(edits.any { it.jsonObject["newText"]!!.jsonPrimitive.content.isNotEmpty() })
        assertTrue(Paths.get(root).resolve(file).toFile().readText().contains("Example{"))
    }

    @Test
    fun nativeRenameUsesOpenBufferAndCarriesDocumentVersion() {
        val diskContent = "package com.example;\npublic class Foo {}\n"
        val openContent = "package com.example;\npublic class Foo { int unsaved; }\n"
        val root = createProject("src/main/java/com/example/Foo.java" to diskContent)
        val fileUri = Paths.get(root).resolve("src/main/java/com/example/Foo.java").toUri().toString()
        val session = LspSession()
        session.dispatch("initialize", initializeParams(root))
        session.dispatch("textDocument/didOpen", buildJsonObject {
            put("textDocument", buildJsonObject {
                put("uri", fileUri)
                put("languageId", "java")
                put("version", 7)
                put("text", openContent)
            })
        })

        val symbols = session.dispatch("textDocument/documentSymbol", textDocumentParams(root, "src/main/java/com/example/Foo.java")) as JsonArray
        assertTrue(symbols.any { it.jsonObject["name"]!!.jsonPrimitive.content == "unsaved" })
        val rename = session.dispatch("textDocument/rename", buildJsonObject {
            put("textDocument", buildJsonObject { put("uri", fileUri) })
            put("position", buildJsonObject { put("line", 1); put("character", 14) })
            put("newName", "Bar")
        }) as JsonObject

        val versionedEdits = rename["documentChanges"]!!.jsonArray.filter {
            it.jsonObject["textDocument"] != null
        }
        assertTrue(versionedEdits.isNotEmpty())
        assertTrue(versionedEdits.all {
            it.jsonObject["textDocument"]!!.jsonObject["version"]!!.jsonPrimitive.content == "7"
        })
        assertEquals("client-managed", rename["refactorkitEditOwnership"]!!.jsonPrimitive.content)
        assertEquals("false", rename["refactorkitRollbackAvailable"]!!.jsonPrimitive.content)
        assertEquals(diskContent, Files.readString(Paths.get(root).resolve("src/main/java/com/example/Foo.java")))
    }

    @Test
    fun didChangeRejectsNonIncreasingDocumentVersion() {
        val root = createProject(
            "src/main/java/com/example/Foo.java" to "package com.example;\npublic class Foo {}\n",
        )
        val fileUri = Paths.get(root).resolve("src/main/java/com/example/Foo.java").toUri().toString()
        val session = LspSession()
        session.dispatch("initialize", initializeParams(root))
        session.dispatch("textDocument/didOpen", buildJsonObject {
            put("textDocument", buildJsonObject {
                put("uri", fileUri); put("version", 3); put("text", "package com.example;\npublic class Foo {}\n")
            })
        })
        val notifications = mutableListOf<String>()
        session.onNotification = { method, _ -> notifications += method }

        session.dispatch("textDocument/didChange", buildJsonObject {
            put("textDocument", buildJsonObject { put("uri", fileUri); put("version", 4) })
            put("contentChanges", buildJsonArray {
                add(buildJsonObject { put("text", "package com.example;\npublic class Foo { int changed; }\n") })
            })
        })
        val symbols = session.dispatch(
            "textDocument/documentSymbol",
            textDocumentParams(root, "src/main/java/com/example/Foo.java"),
        ) as JsonArray
        assertTrue(symbols.any { it.jsonObject["name"]!!.jsonPrimitive.content == "changed" })

        val error = assertFailsWith<JsonRpcException> {
            session.dispatch("textDocument/didChange", buildJsonObject {
                put("textDocument", buildJsonObject { put("uri", fileUri); put("version", 4) })
                put("contentChanges", buildJsonArray {
                    add(buildJsonObject { put("text", "package com.example;\npublic class Foo { int stale; }\n") })
                })
            })
        }

        assertEquals(JsonRpcErrorCodes.DOCUMENT_VERSION_MISMATCH, error.code)
        assertTrue("window/showMessage" in notifications)
    }

    @Test
    fun clientWithoutDocumentChangesCannotReceiveStructuralEdit() {
        val root = createProject(
            "src/main/java/com/example/Foo.java" to "package com.example;\npublic class Foo {}\n",
        )
        val session = LspSession()
        session.dispatch("initialize", buildJsonObject {
            put("rootUri", Paths.get(root).toUri().toString())
        })

        val error = assertFailsWith<JsonRpcException> {
            session.dispatch("workspace/executeCommand", buildJsonObject {
                put("command", "refactorkit.renameClass")
                put("arguments", buildJsonArray {
                    add(buildJsonObject { put("symbol", "com.example.Foo"); put("newName", "Bar") })
                })
            })
        }

        assertEquals(JsonRpcErrorCodes.DOCUMENT_VERSION_MISMATCH, error.code)
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
    fun definitionAndReferencesUseJdtForOverloadedMethodCalls() {
        val lookup = """
            package com.example;
            public class Lookup {
                public String find(String key) { return key; }
                public String find(int id) { return String.valueOf(id); }
            }
        """.trimIndent() + "\n"
        val client = """
            package com.example;
            public class LookupClient {
                String text(Lookup lookup) { return lookup.find("abc"); }
                String number(Lookup lookup) { return lookup.find(7); }
            }
        """.trimIndent() + "\n"
        val root = createProject(
            "src/main/java/com/example/Lookup.java" to lookup,
            "src/main/java/com/example/LookupClient.java" to client,
        )
        val session = LspSession()
        session.dispatch("initialize", initializeParams(root))
        val line = client.lines().indexOfFirst { it.contains("find(\"abc\")") }
        val character = client.lines()[line].indexOf("find")
        val params = buildJsonObject {
            put("textDocument", textDocumentParams(root, "src/main/java/com/example/LookupClient.java")["textDocument"]!!)
            put("position", buildJsonObject { put("line", line); put("character", character) })
        }

        val definition = session.dispatch("textDocument/definition", params) as JsonObject
        val references = session.dispatch("textDocument/references", params) as JsonArray

        assertTrue(definition["uri"]!!.jsonPrimitive.content.endsWith("/src/main/java/com/example/Lookup.java"), definition.toString())
        assertEquals(lookup.lines().indexOfFirst { it.contains("find(String key)") }.toString(), definition["range"]!!.jsonObject["start"]!!.jsonObject["line"]!!.jsonPrimitive.content)
        assertEquals(1, references.size)
        val reference = references.single().jsonObject
        assertTrue(reference["uri"]!!.jsonPrimitive.content.endsWith("/src/main/java/com/example/LookupClient.java"), reference.toString())
        assertEquals(line.toString(), reference["range"]!!.jsonObject["start"]!!.jsonObject["line"]!!.jsonPrimitive.content)
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
        val changes = result["documentChanges"]!!.jsonArray
        assertTrue(changes.any {
            it.jsonObject["textDocument"]?.jsonObject?.get("uri")?.jsonPrimitive?.content == fileUri
        }, "expected versionable edit for $fileUri in $changes")
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
    fun managedApplyRefusesAffectedOpenDocument() {
        val content = "package com.example;\npublic class UserManager {}\n"
        val root = createProject("src/main/java/com/example/UserManager.java" to content)
        val fileUri = Paths.get(root).resolve("src/main/java/com/example/UserManager.java").toUri().toString()
        val session = LspSession()
        session.dispatch("initialize", initializeParams(root))
        session.dispatch("textDocument/didOpen", buildJsonObject {
            put("textDocument", buildJsonObject {
                put("uri", fileUri); put("languageId", "java"); put("version", 1); put("text", content)
            })
        })
        val preview = session.dispatch("workspace/executeCommand", buildJsonObject {
            put("command", "refactorkit.renameClass")
            put("arguments", buildJsonArray {
                add(buildJsonObject { put("symbol", "com.example.UserManager"); put("newName", "AccountManager") })
            })
        }) as JsonObject

        val error = assertFailsWith<JsonRpcException> {
            session.dispatch("workspace/executeCommand", buildJsonObject {
                put("command", "refactorkit.applyPlan")
                put("arguments", buildJsonArray {
                    add(buildJsonObject { put("planId", preview["refactorkitPlanId"]!!.jsonPrimitive.content) })
                })
            })
        }

        assertEquals(JsonRpcErrorCodes.DOCUMENT_VERSION_MISMATCH, error.code)
        assertTrue(Files.exists(Paths.get(root).resolve("src/main/java/com/example/UserManager.java")))
        assertFalse(Files.exists(Paths.get(root).resolve("src/main/java/com/example/AccountManager.java")))
    }

    @Test
    fun executeCommandPreviewApplyRollbackRenameClassFlowRestoresWorkspace() {
        val root = createProject(
            "src/main/java/com/example/UserManager.java" to "package com.example;\npublic class UserManager {}\n",
        )
        val rootPath = Paths.get(root)
        val session = LspSession()
        session.dispatch("initialize", initializeParams(root))

        val preview = session.dispatch("workspace/executeCommand", buildJsonObject {
            put("command", "refactorkit.renameClass")
            put("arguments", JsonArray(listOf(buildJsonObject {
                put("symbol", "com.example.UserManager")
                put("newName", "AccountManager")
            })))
        }) as JsonObject
        val planId = preview["refactorkitPlanId"]!!.jsonPrimitive.content

        val apply = session.dispatch("workspace/executeCommand", buildJsonObject {
            put("command", "refactorkit.applyPlan")
            put("arguments", JsonArray(listOf(buildJsonObject { put("planId", planId) })))
        }) as JsonObject
        val transactionId = apply["transactionId"]!!.jsonPrimitive.content

        assertFalse(Files.exists(rootPath.resolve("src/main/java/com/example/UserManager.java")))
        val accountPath = rootPath.resolve("src/main/java/com/example/AccountManager.java")
        assertTrue(Files.exists(accountPath))
        val accountUri = accountPath.toUri().toString()
        session.dispatch("textDocument/didOpen", buildJsonObject {
            put("textDocument", buildJsonObject {
                put("uri", accountUri); put("languageId", "java"); put("version", 1)
                put("text", Files.readString(accountPath))
            })
        })
        val openRollbackError = assertFailsWith<JsonRpcException> {
            session.dispatch("workspace/executeCommand", buildJsonObject {
                put("command", "refactorkit.rollback")
                put("arguments", JsonArray(listOf(buildJsonObject { put("transactionId", transactionId) })))
            })
        }
        assertEquals(JsonRpcErrorCodes.DOCUMENT_VERSION_MISMATCH, openRollbackError.code)
        assertTrue(Files.exists(accountPath))
        session.dispatch("textDocument/didClose", buildJsonObject {
            put("textDocument", buildJsonObject { put("uri", accountUri) })
        })

        val rollback = session.dispatch("workspace/executeCommand", buildJsonObject {
            put("command", "refactorkit.rollback")
            put("arguments", JsonArray(listOf(buildJsonObject { put("transactionId", transactionId) })))
        }) as JsonObject

        assertEquals("rolledBack", rollback["status"]!!.jsonPrimitive.content)
        assertTrue(Files.exists(rootPath.resolve("src/main/java/com/example/UserManager.java")))
        assertFalse(Files.exists(rootPath.resolve("src/main/java/com/example/AccountManager.java")))
    }

    @Test
    fun executeCommandRollbackRejectsUnknownTransactionId() {
        val root = createProject(
            "src/main/java/com/example/Foo.java" to "package com.example;\npublic class Foo {}\n",
        )
        val session = LspSession()
        session.dispatch("initialize", initializeParams(root))

        val ex = assertFailsWith<JsonRpcException> {
            session.dispatch("workspace/executeCommand", buildJsonObject {
                put("command", "refactorkit.rollback")
                put("arguments", JsonArray(listOf(buildJsonObject {
                    put("transactionId", "transaction-550e8400-e29b-41d4-a716-446655440000")
                })))
            })
        }

        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, ex.code)
        assertTrue(ex.message.contains("Transaction not found"))
        assertFalse(ex.message.contains("Exception"), ex.message)
    }

    @Test
    fun executeCommandRollbackRejectsMalformedTransactionId() {
        val root = createProject(
            "src/main/java/com/example/Foo.java" to "package com.example;\npublic class Foo {}\n",
        )
        val session = LspSession()
        session.dispatch("initialize", initializeParams(root))

        val ex = assertFailsWith<JsonRpcException> {
            session.dispatch("workspace/executeCommand", buildJsonObject {
                put("command", "refactorkit.rollback")
                put("arguments", JsonArray(listOf(buildJsonObject { put("transactionId", "../../outside") })))
            })
        }

        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, ex.code)
        assertTrue(ex.message.contains("Invalid transaction ID"))
    }

    @Test
    fun executeCommandSafeDeleteRefusalUsesPlanRefusedCode() {
        val root = createProject(
            "src/main/java/com/example/Foo.java" to "package com.example;\npublic class Foo {}\n",
            "src/main/java/com/example/UseFoo.java" to "package com.example;\npublic class UseFoo { Foo foo; }\n",
        )
        val session = LspSession()
        session.dispatch("initialize", initializeParams(root))

        val ex = assertFailsWith<JsonRpcException> {
            session.dispatch("workspace/executeCommand", buildJsonObject {
                put("command", "refactorkit.safeDelete")
                put("arguments", JsonArray(listOf(buildJsonObject { put("symbol", "com.example.Foo") })))
            })
        }

        assertEquals(JsonRpcErrorCodes.PLAN_REFUSED, ex.code)
        assertTrue(ex.message.contains("reference", ignoreCase = true), ex.message)
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
