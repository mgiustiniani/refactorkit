package org.refactorkit.daemon

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    fun serverVersionReturnsPublicVersionAndApiVersion() {
        val session = DaemonSession()
        val result = session.dispatch("server.version", null) as JsonObject

        assertEquals(RefactorKitVersion.NAME, result["name"]!!.jsonPrimitive.content)
        assertEquals(RefactorKitVersion.VERSION, result["version"]!!.jsonPrimitive.content)
        assertEquals(RefactorKitVersion.API_VERSION, result["apiVersion"]!!.jsonPrimitive.content)
    }

    @Test
    fun serverCapabilitiesDescribesMethodsAndSafetyWithoutOpenProject() {
        val session = DaemonSession()

        val result = session.dispatch("server.capabilities", null) as JsonObject
        val methods = result["methods"]!!.jsonArray.map { it.jsonObject }
        val byName = methods.associateBy { it["name"]!!.jsonPrimitive.content }
        val safety = result["safety"]!!.jsonObject

        assertEquals(RefactorKitVersion.API_VERSION, result["apiVersion"]!!.jsonPrimitive.content)
        assertEquals("json-rpc-2.0", result["protocol"]!!.jsonPrimitive.content)
        assertEquals("stdio", result["transport"]!!.jsonPrimitive.content)
        assertEquals("beta-contract", byName["refactor.apply"]!!["stability"]!!.jsonPrimitive.content)
        assertEquals("true", byName["refactor.apply"]!!["writesWorkspace"]!!.jsonPrimitive.content)
        val importer = byName["java.importExternalClass"]!!
        assertEquals("experimental", importer["stability"]!!.jsonPrimitive.content)
        val importerFeatures = importer["features"]!!.jsonObject
        listOf("targetDirectory", "preview", "apply", "rollback").forEach {
            assertEquals("true", importerFeatures[it]!!.jsonPrimitive.content)
        }
        assertEquals("false", byName["server.version"]!!["requiresProject"]!!.jsonPrimitive.content)
        assertEquals("true", safety["previewBeforeApply"]!!.jsonPrimitive.content)
        assertEquals("true", safety["transactionRollback"]!!.jsonPrimitive.content)
    }

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
        val session = DaemonSession()
        session.dispatch("project.open", params("root" to root))

        val results = session.dispatch("symbol.search", params("query" to "find(java.lang.String)")).jsonArray

        assertEquals(1, results.size)
        assertEquals("com.example.Lookup#find(java.lang.String)", results.single().jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun symbolDefinitionAndReferencesSupportSignedMemberSelectors() {
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
        val session = DaemonSession()
        session.dispatch("project.open", params("root" to root))
        val symbol = "com.example.Lookup#find(java.lang.String)"

        val definition = session.dispatch("symbol.definition", params("symbol" to symbol)) as JsonObject
        val references = session.dispatch("symbol.references", params("symbol" to symbol)).jsonArray

        assertEquals(symbol, definition["id"]!!.jsonPrimitive.content)
        assertEquals("src/main/java/com/example/Lookup.java", definition["file"]!!.jsonPrimitive.content)
        assertEquals(1, references.size)
        val reference = references.single().jsonObject
        assertEquals("src/main/java/com/example/LookupClient.java", reference["file"]!!.jsonPrimitive.content)
        assertEquals("3", reference["line"]!!.jsonPrimitive.content)
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
    fun previewApplyRollbackRenameClassFlowRestoresWorkspace() {
        val root = createProject(
            "src/main/java/com/example/UserManager.java" to "package com.example;\npublic class UserManager {}\n",
        )
        val rootPath = Paths.get(root)
        val session = DaemonSession()
        session.dispatch("project.open", params("root" to root))
        val planResult = session.dispatch("refactor.preview", buildJsonObject {
            put("operation", "renameClass")
            put("symbol", "com.example.UserManager")
            put("arguments", buildJsonObject { put("newName", "AccountManager") })
        }) as JsonObject
        val planId = planResult["planId"]!!.jsonPrimitive.content
        val stalePlan = session.dispatch("refactor.preview", buildJsonObject {
            put("operation", "renameClass")
            put("symbol", "com.example.UserManager")
            put("arguments", buildJsonObject { put("newName", "LegacyManager") })
        }) as JsonObject

        val applyResult = session.dispatch("refactor.apply", params("planId" to planId)) as JsonObject
        val transactionId = applyResult["transactionId"]!!.jsonPrimitive.content

        assertEquals("applied", applyResult["status"]!!.jsonPrimitive.content)
        assertFalse(Files.exists(rootPath.resolve("src/main/java/com/example/UserManager.java")))
        assertTrue(Files.exists(rootPath.resolve("src/main/java/com/example/AccountManager.java")))
        val summaryAfterApply = session.dispatch("project.summary", null) as JsonObject
        assertEquals(
            applyResult["snapshotHash"]!!.jsonPrimitive.content,
            summaryAfterApply["snapshotHash"]!!.jsonPrimitive.content,
        )
        val symbolsAfterApply = session.dispatch("symbol.search", params("query" to "AccountManager")) as JsonArray
        assertTrue(symbolsAfterApply.any { it.jsonObject["id"]!!.jsonPrimitive.content == "com.example.AccountManager" })
        val stalePlanError = assertFailsWith<JsonRpcException> {
            session.dispatch(
                "refactor.apply",
                params("planId" to stalePlan["planId"]!!.jsonPrimitive.content),
            )
        }
        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, stalePlanError.code)
        assertTrue(stalePlanError.message.contains("Plan not found"))
        val pendingBeforeRollback = session.dispatch("refactor.preview", buildJsonObject {
            put("operation", "renameClass")
            put("symbol", "com.example.AccountManager")
            put("arguments", buildJsonObject { put("newName", "CurrentManager") })
        }) as JsonObject

        val rollbackResult = session.dispatch("patch.rollback", params("transactionId" to transactionId)) as JsonObject

        assertEquals("rolledBack", rollbackResult["status"]!!.jsonPrimitive.content)
        assertTrue(Files.exists(rootPath.resolve("src/main/java/com/example/UserManager.java")))
        assertFalse(Files.exists(rootPath.resolve("src/main/java/com/example/AccountManager.java")))
        val summaryAfterRollback = session.dispatch("project.summary", null) as JsonObject
        assertEquals(
            rollbackResult["snapshotHash"]!!.jsonPrimitive.content,
            summaryAfterRollback["snapshotHash"]!!.jsonPrimitive.content,
        )
        val symbolsAfterRollback = session.dispatch("symbol.search", params("query" to "UserManager")) as JsonArray
        assertTrue(symbolsAfterRollback.any { it.jsonObject["id"]!!.jsonPrimitive.content == "com.example.UserManager" })
        val rollbackClearedPlan = assertFailsWith<JsonRpcException> {
            session.dispatch(
                "refactor.apply",
                params("planId" to pendingBeforeRollback["planId"]!!.jsonPrimitive.content),
            )
        }
        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, rollbackClearedPlan.code)
    }

    @Test
    fun formatFilePreviewAndApplyUsesManagedTransaction() {
        val root = createProject(
            "src/main/java/com/example/Example.java" to
                "package com.example;\npublic class Example{void run(){int value=1;}}\n",
        )
        val session = DaemonSession()
        session.dispatch("project.open", params("root" to root))
        val preview = session.dispatch("refactor.preview", buildJsonObject {
            put("operation", "formatFile")
            put("arguments", buildJsonObject { put("file", "src/main/java/com/example/Example.java") })
        }) as JsonObject

        assertEquals("formatFile", preview["operation"]!!.jsonPrimitive.content)
        assertEquals("PREVIEW", preview["status"]!!.jsonPrimitive.content)
        val applied = session.dispatch("refactor.apply", params("planId" to preview["planId"]!!.jsonPrimitive.content)) as JsonObject
        assertEquals("applied", applied["status"]!!.jsonPrimitive.content)
        assertTrue(Paths.get(root).resolve("src/main/java/com/example/Example.java").toFile().readText().contains("Example {"))
    }

    @Test
    fun rollbackConflictUsesStableCodeAndForceRestores() {
        val root = createProject(
            "src/main/java/com/example/UserManager.java" to "package com.example;\npublic class UserManager {}\n",
        )
        val rootPath = Paths.get(root)
        val session = DaemonSession()
        session.dispatch("project.open", params("root" to root))
        val preview = session.dispatch("refactor.preview", buildJsonObject {
            put("operation", "renameClass")
            put("symbol", "com.example.UserManager")
            put("arguments", buildJsonObject { put("newName", "AccountManager") })
        }) as JsonObject
        val applied = session.dispatch(
            "refactor.apply",
            params("planId" to preview["planId"]!!.jsonPrimitive.content),
        ) as JsonObject
        val transactionId = applied["transactionId"]!!.jsonPrimitive.content
        Files.writeString(
            rootPath.resolve("src/main/java/com/example/AccountManager.java"),
            "package com.example;\npublic class ExternalChange {}\n",
        )

        val conflict = assertFailsWith<JsonRpcException> {
            session.dispatch("patch.rollback", params("transactionId" to transactionId))
        }
        assertEquals(JsonRpcErrorCodes.ROLLBACK_CONFLICT, conflict.code)
        assertTrue(Files.readString(rootPath.resolve("src/main/java/com/example/AccountManager.java")).contains("ExternalChange"))

        val forced = session.dispatch("patch.rollback", buildJsonObject {
            put("transactionId", transactionId)
            put("force", true)
        }) as JsonObject
        assertEquals("rolledBack", forced["status"]!!.jsonPrimitive.content)
        assertTrue(Files.exists(rootPath.resolve("src/main/java/com/example/UserManager.java")))
        assertFalse(Files.exists(rootPath.resolve("src/main/java/com/example/AccountManager.java")))
    }

    @Test
    fun rollbackRejectsMalformedTransactionId() {
        val root = createProject(
            "src/main/java/com/example/Foo.java" to "package com.example;\npublic class Foo {}\n",
        )
        val session = DaemonSession()
        session.dispatch("project.open", params("root" to root))

        val error = assertFailsWith<JsonRpcException> {
            session.dispatch("patch.rollback", params("transactionId" to "../../outside"))
        }

        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, error.code)
        assertTrue(error.message.contains("Invalid transaction ID"))
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
    fun previewRenameMissingNewNameThrowsInvalidParams() {
        val root = createProject(
            "src/main/java/com/example/UserManager.java" to "package com.example;\npublic class UserManager {}\n",
        )
        val session = DaemonSession()
        session.dispatch("project.open", params("root" to root))

        val ex = assertFailsWith<JsonRpcException> {
            session.dispatch("refactor.preview", buildJsonObject {
                put("operation", "renameClass")
                put("symbol", "com.example.UserManager")
                put("arguments", buildJsonObject {})
            })
        }

        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, ex.code)
        assertTrue(ex.message.contains("arguments.newName"))
    }

    @Test
    fun applyUnknownPlanThrowsInvalidParams() {
        val root = createProject(
            "src/main/java/com/example/UserManager.java" to "package com.example;\npublic class UserManager {}\n",
        )
        val session = DaemonSession()
        session.dispatch("project.open", params("root" to root))

        val ex = assertFailsWith<JsonRpcException> {
            session.dispatch("refactor.apply", params("planId" to "plan-missing"))
        }

        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, ex.code)
        assertTrue(ex.message.contains("Plan not found"))
    }

    @Test
    fun applyStalePlanThrowsSnapshotChangedCode() {
        val root = createProject(
            "src/main/java/com/example/UserManager.java" to "package com.example;\npublic class UserManager {}\n",
        )
        val rootPath = Paths.get(root)
        val session = DaemonSession()
        session.dispatch("project.open", params("root" to root))
        val planResult = session.dispatch("refactor.preview", buildJsonObject {
            put("operation", "renameClass")
            put("symbol", "com.example.UserManager")
            put("arguments", buildJsonObject { put("newName", "AccountManager") })
        }) as JsonObject
        val planId = planResult["planId"]!!.jsonPrimitive.content
        rootPath.resolve("src/main/java/com/example/UserManager.java")
            .writeText("package com.example;\npublic class UserManager { int changed; }\n")

        val ex = assertFailsWith<JsonRpcException> {
            session.dispatch("refactor.apply", params("planId" to planId))
        }

        assertEquals(JsonRpcErrorCodes.SNAPSHOT_CHANGED, ex.code)
        assertTrue(ex.message.contains("Project changed since preview"))
    }

    @Test
    fun javaImportExternalClassReturnsProvenanceAndLicenseWarnings() {
        val root = createProject(
            "src/main/java/com/example/App.java" to "package com.example;\npublic class App {}\n",
        )
        val session = DaemonSession()
        session.dispatch("project.open", params("root" to root))

        val result = session.dispatch("java.importExternalClass", buildJsonObject {
            put("code", "// MIT License\npublic class Imported {}\n")
            put("targetPackage", "com.example.imported")
            put("sourceKind", "url")
            put("sourceUrl", "https://example.invalid/Imported.java")
            put("licensePolicy", "warn")
        }) as JsonObject
        val warnings = result["warnings"]!!.jsonArray.joinToString("\n") { it.jsonPrimitive.content }

        assertEquals("importExternalJavaClass", result["operation"]!!.jsonPrimitive.content)
        assertEquals("PREVIEW", result["status"]!!.jsonPrimitive.content)
        assertTrue(warnings.contains("sourceKind=URL"), warnings)
        assertTrue(warnings.contains("sourceUrl=https://example.invalid/Imported.java"), warnings)
        assertTrue(warnings.contains("licenseDetected=MIT"), warnings)
        assertTrue(warnings.contains("licenseRisk=LOW"), warnings)
        assertTrue(Regex("originalHash=[0-9a-f]{64}").containsMatchIn(warnings), warnings)
    }

    @Test
    fun targetDirectoryImportPreviewApplyAndRollbackUsesExactManagedPlan() {
        val original = "package com.example;\npublic class App {}\n"
        val root = createProject("module/src/main/java/com/example/App.java" to original)
        val rootPath = Paths.get(root)
        val session = DaemonSession()
        session.dispatch("project.open", params("root" to root))

        val preview = session.dispatch("java.importExternalClass", buildJsonObject {
            put("code", "package old.pkg;\npublic class Imported {}")
            put("targetDirectory", "module/src/main/java/com/example")
            put("targetPackage", "com.example")
            put("sourceKind", "clipboard")
            put("licensePolicy", "warn")
        }) as JsonObject
        val target = rootPath.resolve("module/src/main/java/com/example/Imported.java")

        assertEquals("PREVIEW", preview["status"]!!.jsonPrimitive.content)
        assertEquals("true", preview["applyEligible"]!!.jsonPrimitive.content)
        assertEquals("module", preview["resolvedModule"]!!.jsonPrimitive.content)
        assertEquals("module/src/main/java", preview["resolvedSourceRoot"]!!.jsonPrimitive.content.replace('\\', '/'))
        assertEquals("MAIN", preview["sourceSet"]!!.jsonPrimitive.content)
        assertEquals("com.example", preview["resolvedPackage"]!!.jsonPrimitive.content)
        assertEquals("module/src/main/java/com/example/Imported.java", preview["primaryFile"]!!.jsonPrimitive.content.replace('\\', '/'))
        assertEquals("old.pkg", preview["packageChanges"]!!.jsonArray.single().jsonObject["from"]!!.jsonPrimitive.content)
        assertFalse(target.exists(), "preview must not write")

        val applied = session.dispatch("refactor.apply", params("planId" to preview["planId"]!!.jsonPrimitive.content)) as JsonObject
        assertEquals("module/src/main/java/com/example/Imported.java", applied["primaryFile"]!!.jsonPrimitive.content.replace('\\', '/'))
        assertTrue(applied["changedFiles"]!!.jsonArray.any { it.jsonPrimitive.content.replace('\\', '/').endsWith("Imported.java") })
        assertTrue(target.readText().startsWith("package com.example;"))
        val transactionId = applied["transactionId"]!!.jsonPrimitive.content

        session.dispatch("patch.rollback", params("transactionId" to transactionId))
        assertFalse(target.exists())
        assertEquals(original, rootPath.resolve("module/src/main/java/com/example/App.java").readText())
    }

    @Test
    fun targetDirectorySupportsDefaultAndTestPackageAndLegacyTargetPackage() {
        val root = createProject(
            "src/test/java/Marker.java" to "public class Marker {}\n",
            "src/main/java/com/example/App.java" to "package com.example; public class App {}\n",
        )
        val session = DaemonSession()
        session.dispatch("project.open", params("root" to root))
        val defaultPackage = session.dispatch("java.importExternalClass", buildJsonObject {
            put("code", "public class DefaultImported {}")
            put("targetDirectory", "src/test/java")
            put("licensePolicy", "allow")
        }).jsonObject
        assertEquals("", defaultPackage["resolvedPackage"]!!.jsonPrimitive.content)
        assertEquals("TEST", defaultPackage["sourceSet"]!!.jsonPrimitive.content)

        val legacy = session.dispatch("java.importExternalClass", buildJsonObject {
            put("code", "public class LegacyImported {}")
            put("targetPackage", "com.example.legacy")
            put("licensePolicy", "allow")
        }).jsonObject
        assertEquals("PREVIEW", legacy["status"]!!.jsonPrimitive.content)
        assertTrue(legacy["affectedFiles"]!!.jsonArray.single().jsonPrimitive.content.replace('\\', '/').endsWith("src/main/java/com/example/legacy/LegacyImported.java"))
    }

    @Test
    fun targetDirectoryRefusalsAreStructuredSafeAndDoNotRetainSource() {
        val secret = "public class SecretClipboardMarker {}"
        val root = createProject("src/main/java/com/example/App.java" to "package com.example; public class App {}")
        val rootPath = Paths.get(root)
        rootPath.resolve("plain-file").writeText("x")
        val session = DaemonSession()
        session.dispatch("project.open", params("root" to root))
        val badTargets = listOf(
            "missing" to "targetDirectory.missing",
            "plain-file" to "targetDirectory.notDirectory",
            "docs" to "targetDirectory.missing",
            "../outside" to "targetDirectory.traversal",
            "C:\\workspace\\src\\main\\java" to "targetDirectory.absolute",
        )
        badTargets.forEach { (directory, refusalCode) ->
            val response = session.dispatch("java.importExternalClass", buildJsonObject {
                put("code", secret)
                put("targetDirectory", directory)
            }).jsonObject
            assertEquals("REFUSED", response["status"]!!.jsonPrimitive.content)
            assertEquals("false", response["applyEligible"]!!.jsonPrimitive.content)
            assertTrue(response["refusalReasons"]!!.jsonArray.any { it.jsonPrimitive.content == refusalCode })
            assertTrue(!response.toString().contains("SecretClipboardMarker"))
        }
        val mismatch = session.dispatch("java.importExternalClass", buildJsonObject {
            put("code", secret)
            put("targetDirectory", "src/main/java/com/example")
            put("targetPackage", "other.package")
        }).jsonObject
        assertTrue(mismatch["refusalReasons"]!!.jsonArray.any { it.jsonPrimitive.content == "targetDirectory.packageMismatch" })
    }

    @Test
    fun targetDirectoryReportsMultiFileDependenciesLicenseAndFilenameConflicts() {
        val root = createProject("src/main/java/com/example/App.java" to "package com.example; public class App {}")
        val session = DaemonSession()
        session.dispatch("project.open", params("root" to root))
        val multi = session.dispatch("java.importExternalClass", buildJsonObject {
            put("code", "import org.acme.Missing;\npublic class Foo {}\npublic class Bar {}")
            put("targetDirectory", "src/main/java/com/example")
            put("licensePolicy", "warn")
        }).jsonObject
        assertEquals(2, multi["affectedFiles"]!!.jsonArray.size)
        assertEquals("unknown", multi["provenance"]!!.jsonObject["licenseDetected"]!!.jsonPrimitive.content)
        assertTrue(multi["unresolvedDependencies"]!!.jsonArray.any { it.jsonPrimitive.content == "org.acme.Missing" })

        val conflict = session.dispatch("java.importExternalClass", buildJsonObject {
            put("code", "public class App {}")
            put("targetDirectory", "src/main/java/com/example")
            put("licensePolicy", "allow")
        }).jsonObject
        assertEquals("REFUSED", conflict["status"]!!.jsonPrimitive.content)
        assertTrue(conflict["conflicts"]!!.jsonArray.any { it.jsonPrimitive.content.replace('\\', '/').endsWith("App.java") })
        assertEquals("false", conflict["applyEligible"]!!.jsonPrimitive.content)
    }

    @Test
    fun targetDirectoryImportStalePlanIsRefused() {
        val root = createProject("src/main/java/com/example/App.java" to "package com.example; public class App {}")
        val rootPath = Paths.get(root)
        val session = DaemonSession()
        session.dispatch("project.open", params("root" to root))
        val preview = session.dispatch("java.importExternalClass", buildJsonObject {
            put("code", "public class Imported {}")
            put("targetDirectory", "src/main/java/com/example")
            put("licensePolicy", "allow")
        }).jsonObject
        rootPath.resolve("src/main/java/com/example/App.java").writeText("package com.example; public class App { int changed; }")

        val error = assertFailsWith<JsonRpcException> {
            session.dispatch("refactor.apply", params("planId" to preview["planId"]!!.jsonPrimitive.content))
        }
        assertEquals(JsonRpcErrorCodes.SNAPSHOT_CHANGED, error.code)
        assertFalse(rootPath.resolve("src/main/java/com/example/Imported.java").exists())
    }

    @Test
    fun machineReadableApiContractMatchesDaemonCapabilities() {
        var root = Paths.get("").toAbsolutePath()
        while (!root.resolve("docs/api-0.2-contract.json").exists()) {
            root = root.parent ?: error("Repository root not found")
        }
        val contract = Json.parseToJsonElement(root.resolve("docs/api-0.2-contract.json").readText()).jsonObject
        val expected = contract["daemon"]!!.jsonObject["methods"]!!.jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }
        val session = DaemonSession()
        val actual = (session.dispatch("server.capabilities", null) as JsonObject)["methods"]!!.jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(expected, actual)
        assertEquals(RefactorKitVersion.API_VERSION, contract["apiVersion"]!!.jsonPrimitive.content)
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
