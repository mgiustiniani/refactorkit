package org.refactorkit.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.core.RefactorKitVersion
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpSessionTest {

    private fun createProject(vararg files: Pair<String, String>): String {
        val root = Files.createTempDirectory("rk-mcp-test")
        for ((rel, content) in files) {
            val file = root.resolve(rel)
            Files.createDirectories(file.parent)
            file.writeText(content)
        }
        return root.toString()
    }

    private fun contentText(result: JsonObject): String =
        result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content

    private fun firstValueAfter(prefix: String, text: String): String =
        text.lineSequence().first { it.trimStart().startsWith(prefix) }.substringAfter(prefix).trim()

    @Test
    fun initializeReturnsProtocolVersionAndServerVersion() {
        val session = McpSession()
        val result = session.dispatch("initialize", null) as JsonObject
        assertEquals("2024-11-05", result["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals(RefactorKitVersion.VERSION, result["serverInfo"]!!.jsonObject["version"]!!.jsonPrimitive.content)
        assertEquals(listOf("java", "javascript", "typescript"),
            result["refactorkitLanguageKernel"]!!.jsonObject["adapters"]!!.jsonArray.map {
                it.jsonObject["languageId"]!!.jsonPrimitive.content
            })
    }

    @Test
    fun toolsListContainsExpectedTools() {
        val session = McpSession()
        val result = session.dispatch("tools/list", null) as JsonObject
        val tools = result["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(tools.contains("project_scan"))
        assertTrue(tools.contains("preview_refactoring"))
        assertTrue(tools.contains("apply_refactoring"))
        assertTrue(tools.contains("rollback_refactoring"))
        assertTrue(tools.contains("generate_context_bundle"))
        val preview = result["tools"]!!.jsonArray.single { it.jsonObject["name"]!!.jsonPrimitive.content == "preview_refactoring" }
        assertTrue(preview.toString().contains("moveSourceRoot"), preview.toString())
    }

    @Test
    fun toolCallProjectScanReturnsFileCount() {
        val root = createProject(
            "src/main/java/com/example/Foo.java" to "package com.example;\npublic class Foo {}\n",
        )
        val session = McpSession()
        val result = session.dispatch("tools/call", buildJsonObject {
            put("name", "project_scan")
            put("arguments", buildJsonObject { put("root", root) })
        }) as JsonObject
        val text = result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.contains("Files: 1"))
    }

    @Test
    fun symbolSearchIncludesSignedMemberSelectors() {
        val root = createProject(
            "src/main/java/com/example/Lookup.java" to """
                package com.example;
                public class Lookup {
                    public String find(String key) { return key; }
                    public String find(int id) { return String.valueOf(id); }
                }
            """.trimIndent(),
        )
        val session = McpSession()
        session.dispatch("tools/call", buildJsonObject {
            put("name", "project_scan")
            put("arguments", buildJsonObject { put("root", root) })
        })

        val search = session.dispatch("tools/call", buildJsonObject {
            put("name", "symbol_search")
            put("arguments", buildJsonObject { put("query", "find(java.lang.String)") })
        }) as JsonObject

        assertTrue(contentText(search).contains("METHOD com.example.Lookup#find(java.lang.String)"), contentText(search))
        assertFalse(contentText(search).contains("com.example.Lookup#find(int)"), contentText(search))
    }

    @Test
    fun symbolToolsSupportSignedMemberSelectors() {
        val root = createProject(
            "src/main/java/com/example/Lookup.java" to """
                package com.example;
                public class Lookup {
                    public String find(String key) { return key; }
                    public String find(int id) { return String.valueOf(id); }
                }
            """.trimIndent(),
            "src/main/java/com/example/LookupClient.java" to """
                package com.example;
                public class LookupClient {
                    String text(Lookup lookup) { return lookup.find("abc"); }
                    String number(Lookup lookup) { return lookup.find(7); }
                }
            """.trimIndent(),
        )
        val session = McpSession()
        session.dispatch("tools/call", buildJsonObject {
            put("name", "project_scan")
            put("arguments", buildJsonObject { put("root", root) })
        })
        val symbol = "com.example.Lookup#find(java.lang.String)"

        val definition = session.dispatch("tools/call", buildJsonObject {
            put("name", "symbol_definition")
            put("arguments", buildJsonObject { put("symbol", symbol) })
        }) as JsonObject
        val references = session.dispatch("tools/call", buildJsonObject {
            put("name", "symbol_references")
            put("arguments", buildJsonObject { put("symbol", symbol) })
        }) as JsonObject

        assertTrue(contentText(definition).contains("METHOD $symbol"), contentText(definition))
        assertTrue(contentText(definition).contains("Lookup.java:3"), contentText(definition))
        assertTrue(contentText(references).contains("LookupClient.java:3"), contentText(references))
        assertFalse(contentText(references).contains("LookupClient.java:4"), contentText(references))
    }

    @Test
    fun toolCallPreviewRenameReturnsPlanId() {
        val root = createProject(
            "src/main/java/com/example/UserManager.java" to "package com.example;\npublic class UserManager {}\n",
        )
        val session = McpSession()
        session.dispatch("tools/call", buildJsonObject {
            put("name", "project_scan")
            put("arguments", buildJsonObject { put("root", root) })
        })
        val result = session.dispatch("tools/call", buildJsonObject {
            put("name", "preview_refactoring")
            put("arguments", buildJsonObject {
                put("operation", "renameClass")
                put("symbol", "com.example.UserManager")
                put("arguments", buildJsonObject { put("newName", "AccountManager") })
            })
        }) as JsonObject
        val text = contentText(result)
        assertTrue(text.contains("Plan ID"))
        assertTrue(text.contains("PREVIEW"))
        assertEquals(false, result["isError"]!!.jsonPrimitive.content.toBooleanStrict())
    }

    @Test
    fun toolCallPreviewApplyRollbackRenameClassFlowRestoresWorkspace() {
        val root = createProject(
            "src/main/java/com/example/UserManager.java" to "package com.example;\npublic class UserManager {}\n",
        )
        val rootPath = Paths.get(root)
        val session = McpSession()
        session.dispatch("tools/call", buildJsonObject {
            put("name", "project_scan")
            put("arguments", buildJsonObject { put("root", root) })
        })
        val preview = session.dispatch("tools/call", buildJsonObject {
            put("name", "preview_refactoring")
            put("arguments", buildJsonObject {
                put("operation", "renameClass")
                put("symbol", "com.example.UserManager")
                put("arguments", buildJsonObject { put("newName", "AccountManager") })
            })
        }) as JsonObject
        val planId = firstValueAfter("Plan ID  :", contentText(preview))

        val apply = session.dispatch("tools/call", buildJsonObject {
            put("name", "apply_refactoring")
            put("arguments", buildJsonObject { put("planId", planId) })
        }) as JsonObject
        val transactionId = firstValueAfter("Transaction ID:", contentText(apply))

        assertEquals(false, apply["isError"]!!.jsonPrimitive.content.toBooleanStrict())
        assertFalse(Files.exists(rootPath.resolve("src/main/java/com/example/UserManager.java")))
        assertTrue(Files.exists(rootPath.resolve("src/main/java/com/example/AccountManager.java")))

        val rollback = session.dispatch("tools/call", buildJsonObject {
            put("name", "rollback_refactoring")
            put("arguments", buildJsonObject { put("transactionId", transactionId) })
        }) as JsonObject

        assertEquals(false, rollback["isError"]!!.jsonPrimitive.content.toBooleanStrict())
        assertTrue(contentText(rollback).contains("Rolled back"))
        assertTrue(Files.exists(rootPath.resolve("src/main/java/com/example/UserManager.java")))
        assertFalse(Files.exists(rootPath.resolve("src/main/java/com/example/AccountManager.java")))
    }

    @Test
    fun invalidToolReturnsErrorWithoutStackTrace() {
        val session = McpSession()

        val result = session.dispatch("tools/call", buildJsonObject {
            put("name", "not_a_tool")
            put("arguments", buildJsonObject {})
        }) as JsonObject
        val text = contentText(result)

        assertEquals(true, result["isError"]!!.jsonPrimitive.content.toBooleanStrict())
        assertTrue(text.contains("Unknown tool"))
        assertFalse(text.contains("Exception"), text)
        assertFalse(text.contains("\tat "), text)
    }

    @Test
    fun toolCallPreviewExtractMethodReturnsPlanId() {
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
        val session = McpSession()
        session.dispatch("tools/call", buildJsonObject {
            put("name", "project_scan")
            put("arguments", buildJsonObject { put("root", root) })
        })
        val result = session.dispatch("tools/call", buildJsonObject {
            put("name", "preview_refactoring")
            put("arguments", buildJsonObject {
                put("operation", "extractMethod")
                put("arguments", buildJsonObject {
                    put("file", "src/main/java/com/example/App.java")
                    put("startLine", "4")
                    put("endLine", "4")
                    put("methodName", "printA")
                })
            })
        }) as JsonObject
        val text = result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.contains("Plan ID"))
        assertTrue(text.contains("extract") || text.contains("Extract"))
    }

    @Test
    fun toolCallPreviewRenameParameterReturnsPlanId() {
        val root = createProject(
            "src/main/java/com/example/UserService.java" to """
                package com.example;
                public class UserService {
                    public String findName(String id) { return id; }
                }
            """.trimIndent() + "\n",
        )
        val session = McpSession()
        session.dispatch("tools/call", buildJsonObject {
            put("name", "project_scan")
            put("arguments", buildJsonObject { put("root", root) })
        })
        val result = session.dispatch("tools/call", buildJsonObject {
            put("name", "preview_refactoring")
            put("arguments", buildJsonObject {
                put("operation", "changeSignature.renameParameter")
                put("symbol", "com.example.UserService#findName")
                put("arguments", buildJsonObject {
                    put("oldName", "id")
                    put("newName", "userId")
                })
            })
        }) as JsonObject
        val text = result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.contains("Plan ID"))
        assertTrue(text.contains("userId"))
    }

    @Test
    fun toolCallPreviewStructuralChangeSignatureReturnsPlanId() {
        val root = createProject(
            "src/main/java/com/example/UserService.java" to """
                package com.example;
                class UserService {
                    String findName(String id, boolean unused) { return id; }
                    String local() { return findName("a", true); }
                }
            """.trimIndent() + "\n",
        )
        val session = McpSession()
        session.dispatch("tools/call", buildJsonObject {
            put("name", "project_scan")
            put("arguments", buildJsonObject { put("root", root) })
        })
        val result = session.dispatch("tools/call", buildJsonObject {
            put("name", "preview_refactoring")
            put("arguments", buildJsonObject {
                put("operation", "changeSignature.removeParameter")
                put("symbol", "com.example.UserService#findName")
                put("arguments", buildJsonObject { put("name", "unused") })
            })
        }) as JsonObject
        val text = result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.contains("Plan ID"), text)
        assertTrue(text.contains("remove"), text)
    }

    @Test
    fun previewFormatFileReturnsManagedPlanId() {
        val root = createProject(
            "src/main/java/com/example/Example.java" to
                "package com.example;\npublic class Example{void run(){int value=1;}}\n",
        )
        val session = McpSession()
        session.dispatch("tools/call", buildJsonObject {
            put("name", "project_scan")
            put("arguments", buildJsonObject { put("root", root) })
        })
        val result = session.dispatch("tools/call", buildJsonObject {
            put("name", "preview_refactoring")
            put("arguments", buildJsonObject {
                put("operation", "formatFile")
                put("arguments", buildJsonObject { put("file", "src/main/java/com/example/Example.java") })
            })
        }) as JsonObject
        val text = result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.contains("Plan ID"), text)
        assertTrue(text.contains("Format Java compilation unit"), text)
    }

    @Test
    fun importExternalJavaClassToolReturnsProvenanceAndLicenseFields() {
        val root = createProject(
            "src/main/java/com/example/App.java" to "package com.example;\npublic class App {}\n",
        )
        val session = McpSession()
        session.dispatch("tools/call", buildJsonObject {
            put("name", "project_scan")
            put("arguments", buildJsonObject { put("root", root) })
        })

        val result = session.dispatch("tools/call", buildJsonObject {
            put("name", "import_external_java_class")
            put("arguments", buildJsonObject {
                put("code", "// MIT License\npublic class Imported {}\n")
                put("targetPackage", "com.example.imported")
                put("sourceUrl", "https://example.invalid/Imported.java")
                put("licensePolicy", "warn")
            })
        }) as JsonObject
        val text = contentText(result)

        assertEquals(false, result["isError"]!!.jsonPrimitive.content.toBooleanStrict())
        assertTrue(text.contains("sourceKind=LLM"), text)
        assertTrue(text.contains("sourceUrl=https://example.invalid/Imported.java"), text)
        assertTrue(text.contains("licenseDetected=MIT"), text)
        assertTrue(text.contains("licenseRisk=LOW"), text)
        assertTrue(Regex("originalHash=[0-9a-f]{64}").containsMatchIn(text), text)
    }

    @Test
    fun resourcesListContainsStandardResources() {
        val session = McpSession()
        val result = session.dispatch("resources/list", null) as JsonObject
        val uris = result["resources"]!!.jsonArray.map { it.jsonObject["uri"]!!.jsonPrimitive.content }
        assertTrue(uris.contains("project://summary"))
        assertTrue(uris.contains("diagnostics://latest"))
    }

    @Test
    fun promptsListContainsRefactorSafely() {
        val session = McpSession()
        val result = session.dispatch("prompts/list", null) as JsonObject
        val names = result["prompts"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(names.contains("refactor_safely"))
        assertTrue(names.contains("generate_tests_for_refactor"))
    }

    @Test
    fun resourcesListIncludesSymbolAndDependencyResourcesAfterScan() {
        val root = createProject(
            "build.gradle.kts" to "plugins { java }\n",
            "src/main/java/com/example/Foo.java" to "package com.example;\npublic class Foo {}\n",
        )
        val session = McpSession()
        session.dispatch("tools/call", buildJsonObject {
            put("name", "project_scan")
            put("arguments", buildJsonObject { put("root", root) })
        })

        val result = session.dispatch("resources/list", null) as JsonObject
        val uris = result["resources"]!!.jsonArray.map { it.jsonObject["uri"]!!.jsonPrimitive.content }

        assertTrue(uris.contains("project://dependencies"))
        assertTrue(uris.contains("symbol://com.example.Foo"))
        assertTrue(uris.any { it.startsWith("file://") && it.endsWith("Foo.java") })
    }

    @Test
    fun symbolResourceReturnsDefinitionAndReferences() {
        val root = createProject(
            "src/main/java/com/example/Foo.java" to "package com.example;\npublic class Foo {}\n",
            "src/main/java/com/example/UseFoo.java" to "package com.example;\npublic class UseFoo { Foo foo; }\n",
        )
        val session = McpSession()
        session.dispatch("tools/call", buildJsonObject {
            put("name", "project_scan")
            put("arguments", buildJsonObject { put("root", root) })
        })

        val result = session.dispatch("resources/read", buildJsonObject {
            put("uri", "symbol://com.example.Foo")
        }) as JsonObject
        val text = result["contents"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content

        assertTrue(text.contains("CLASS com.example.Foo"))
        assertTrue(text.contains("Definition:"))
        assertTrue(text.contains("References:"))
    }

    @Test
    fun fileResourceRejectsPathOutsideWorkspace() {
        val root = createProject(
            "src/main/java/com/example/Foo.java" to "package com.example;\npublic class Foo {}\n",
        )
        val outside = Files.createTempFile("rk-outside", ".txt")
        outside.writeText("secret")
        val session = McpSession()
        session.dispatch("tools/call", buildJsonObject {
            put("name", "project_scan")
            put("arguments", buildJsonObject { put("root", root) })
        })

        val result = session.dispatch("resources/read", buildJsonObject {
            put("uri", outside.toUri().toString())
        }) as JsonObject
        val text = result["contents"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content

        assertTrue(text.contains("Access denied"))
    }

    @Test
    fun fileResourceRejectsWorkspaceFileNotInScannedSnapshot() {
        val root = createProject(
            "src/main/java/com/example/Foo.java" to "package com.example;\npublic class Foo {}\n",
        )
        val rootPath = Paths.get(root)
        val session = McpSession()
        session.dispatch("tools/call", buildJsonObject {
            put("name", "project_scan")
            put("arguments", buildJsonObject { put("root", root) })
        })
        val lateFile = rootPath.resolve("src/main/java/com/example/Late.java")
        lateFile.writeText("package com.example;\npublic class Late {}\n")

        val result = session.dispatch("resources/read", buildJsonObject {
            put("uri", lateFile.toUri().toString())
        }) as JsonObject
        val text = result["contents"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content

        assertTrue(text.contains("not part of the scanned workspace snapshot"), text)
    }

    @Test
    fun contextBundleIncludesSnippetsAndBuildFiles() {
        val root = createProject(
            "build.gradle.kts" to "plugins { java }\n",
            "src/main/java/com/example/Foo.java" to "package com.example;\npublic class Foo {}\n",
        )
        val session = McpSession()
        session.dispatch("tools/call", buildJsonObject {
            put("name", "project_scan")
            put("arguments", buildJsonObject { put("root", root) })
        })

        val result = session.dispatch("tools/call", buildJsonObject {
            put("name", "generate_context_bundle")
            put("arguments", buildJsonObject {
                put("query", "Foo")
                put("maxSymbols", "1")
            })
        }) as JsonObject
        val text = result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content

        assertTrue(text.contains("Build files: build.gradle.kts"))
        assertTrue(text.contains("```java"))
    }
}
