package org.refactorkit.daemon

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.ProtocolLimits
import org.refactorkit.core.RefactorKitVersion
import org.refactorkit.typescript.TypeScriptProjectModel
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
        val languageKernel = result["languageKernel"]!!.jsonObject

        assertEquals(RefactorKitVersion.API_VERSION, result["apiVersion"]!!.jsonPrimitive.content)
        assertEquals("json-rpc-2.0", result["protocol"]!!.jsonPrimitive.content)
        assertEquals("stdio", result["transport"]!!.jsonPrimitive.content)
        assertEquals("beta-contract", byName["refactor.apply"]!!["stability"]!!.jsonPrimitive.content)
        assertEquals("true", byName["refactor.apply"]!!["writesWorkspace"]!!.jsonPrimitive.content)
        assertEquals("beta-contract", byName["refactor.discard"]!!["stability"]!!.jsonPrimitive.content)
        assertEquals("false", byName["refactor.discard"]!!["writesWorkspace"]!!.jsonPrimitive.content)
        val summaryFeatures = byName["project.summary"]!!["features"]!!.jsonObject
        assertTrue(summaryFeatures["buildModelSummary"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(summaryFeatures["sourceSets"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(summaryFeatures["credentialRedaction"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(summaryFeatures["boundedBuildModelSummary"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(summaryFeatures["typedBuildModelSchema"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(summaryFeatures["offlineMissingStatus"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(summaryFeatures["executionRefusedStatus"]!!.jsonPrimitive.content.toBoolean())
        val importer = byName["java.importExternalClass"]!!
        assertEquals("experimental", importer["stability"]!!.jsonPrimitive.content)
        val importerFeatures = importer["features"]!!.jsonObject
        listOf(
            "targetDirectory", "preview", "renderedDiff", "structuredDiff",
            "previewDiagnostics", "apply", "discard", "rollback",
        ).forEach {
            assertEquals("true", importerFeatures[it]!!.jsonPrimitive.content)
        }
        assertEquals("false", byName["server.version"]!!["requiresProject"]!!.jsonPrimitive.content)
        assertEquals("true", safety["previewBeforeApply"]!!.jsonPrimitive.content)
        assertEquals("true", safety["transactionRollback"]!!.jsonPrimitive.content)
        assertEquals("1", languageKernel["schemaVersion"]!!.jsonPrimitive.content)
        val languageAdapters = languageKernel["adapters"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("java", "javascript", "kotlin", "typescript"), languageAdapters.map {
            it["languageId"]!!.jsonPrimitive.content
        })
        val kotlin = languageAdapters.single { it["languageId"]!!.jsonPrimitive.content == "kotlin" }
        val kotlinCapabilities = kotlin["capabilities"]!!.jsonArray.map { it.jsonObject }
        assertTrue(kotlinCapabilities.isNotEmpty())
        val kotlinDiagnostics = kotlinCapabilities.single { it["operation"]!!.jsonPrimitive.content == "diagnostics" }
        assertEquals("experimental", kotlinDiagnostics["stability"]!!.jsonPrimitive.content)
        assertEquals("compiler", kotlinDiagnostics["evidence"]!!.jsonPrimitive.content)
        assertEquals("kotlin-compiler-diagnostics-k2-v1", kotlinDiagnostics["backend"]!!.jsonPrimitive.content)
        assertTrue(kotlinCapabilities.filter { it !== kotlinDiagnostics }
            .all { it["stability"]!!.jsonPrimitive.content == "refused" && it["evidence"]!!.jsonPrimitive.content == "none" })
        assertTrue(kotlinCapabilities.all { it["mutationAuthority"]!!.jsonPrimitive.content == "none" })
        val typescript = languageAdapters.single { it["languageId"]!!.jsonPrimitive.content == "typescript" }
        val capabilities = typescript["capabilities"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            listOf(
                "definition", "diagnostics", "identifierSearch", "localRename", "outline", "references",
                "renameSymbol", "workspaceSymbols",
            ),
            capabilities.map { it["operation"]!!.jsonPrimitive.content },
        )
        val outline = capabilities.single { it["operation"]!!.jsonPrimitive.content == "outline" }
        assertEquals("in-process", outline["runtime"]!!.jsonObject["executionMode"]!!.jsonPrimitive.content)
        val rename = capabilities.single { it["operation"]!!.jsonPrimitive.content == "renameSymbol" }
        assertEquals("external-process", rename["runtime"]!!.jsonObject["executionMode"]!!.jsonPrimitive.content)
        assertEquals("proposal-only", rename["mutationAuthority"]!!.jsonPrimitive.content)
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
    fun projectOpenBuildsMixedJavaTypeScriptSnapshotAndDeclarativeModel() {
        val root = createProject(
            "src/main/java/com/example/Foo.java" to "package com.example; public class Foo {}\n",
            "src/client.ts" to "export class Client {}\n",
            "tsconfig.json" to """{"compilerOptions":{"rootDir":"src"},"include":["src/**/*.ts"]}""",
        )
        val session = DaemonSession()
        val opened = session.dispatch("project.open", params("root" to root)).jsonObject
        assertEquals(2, opened["fileCount"]!!.jsonPrimitive.content.toInt())

        val summary = session.dispatch("project.summary", null).jsonObject
        val models = summary["buildModels"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("java-conventional-v1", TypeScriptProjectModel.PROVIDER_ID),
            models.map { it["providerId"]!!.jsonPrimitive.content },
        )
        val typescript = models.single { it["providerId"]!!.jsonPrimitive.content == TypeScriptProjectModel.PROVIDER_ID }
        assertEquals("available", typescript["status"]!!.jsonPrimitive.content)
        assertTrue(typescript.toString().contains("src"))
    }

    @Test
    fun projectSummaryRequiresOpenProject() {
        val session = DaemonSession()
        val ex = assertFailsWith<JsonRpcException> { session.dispatch("project.summary", null) }
        assertEquals(JsonRpcErrorCodes.PROJECT_NOT_OPEN, ex.code)
    }

    @Test
    fun projectSummaryExposesBuildModelCapabilityWithoutClasspathSecrets() {
        val root = createProject(
            "src/main/java/com/example/Foo.java" to "package com.example; public class Foo {}\n",
        )
        val session = DaemonSession()
        session.dispatch("project.open", params("root" to root))

        val result = session.dispatch("project.summary", null).jsonObject
        val model = result["buildModels"]!!.jsonArray.single().jsonObject

        assertEquals("java-conventional-v1", model["providerId"]!!.jsonPrimitive.content)
        assertEquals("partial", model["status"]!!.jsonPrimitive.content)
        assertEquals("java", model["ecosystem"]!!.jsonPrimitive.content)
        assertEquals("conventional-layout", model["strategy"]!!.jsonPrimitive.content)
        assertEquals("denied", model["buildCodeExecution"]!!.jsonPrimitive.content)
        assertEquals("denied", model["credentialsAccess"]!!.jsonPrimitive.content)
        assertTrue(model.toString().contains("src/main/java"))
        assertFalse(model.toString().contains(System.getProperty("user.home")))
    }

    @Test
    fun projectSummaryMatchesVersionedBuildModelSchemaAndLimits() {
        val root = createProject(
            "src/main/java/com/example/Foo.java" to "package com.example; public class Foo {}\n",
        )
        val session = DaemonSession()
        session.dispatch("project.open", params("root" to root))
        val result = session.dispatch("project.summary", null).jsonObject
        val schema = Json.parseToJsonElement(
            requireNotNull(javaClass.getResource("/build-model-summary-schema-keys.json")).readText(),
        ).jsonObject
        fun expected(name: String) = schema[name]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        fun assertKeys(name: String, value: JsonObject) = assertEquals(expected(name), value.keys, name)

        assertKeys("response", result)
        assertKeys("limits", result["buildModelLimits"]!!.jsonObject)
        val model = result["buildModels"]!!.jsonArray.single().jsonObject
        assertKeys("model", model)
        assertKeys("diagnostic", model["diagnostics"]!!.jsonArray.single().jsonObject)
        val module = model["modules"]!!.jsonArray.single().jsonObject
        assertKeys("module", module)
        assertKeys("sourceSet", module["sourceSets"]!!.jsonArray.first().jsonObject)
        assertKeys("legacyModule", result["modules"]!!.jsonArray.single().jsonObject)
        val limits = result["buildModelLimits"]!!.jsonObject
        assertEquals(ProtocolLimits.MAX_BUILD_MODELS, limits["maxModels"]!!.jsonPrimitive.content.toInt())
        assertEquals(ProtocolLimits.MAX_BUILD_MODULES, limits["maxModules"]!!.jsonPrimitive.content.toInt())
        assertFalse(result["buildModelsTruncated"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(result["modulesTruncated"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(".", result["modules"]!!.jsonArray.single().jsonObject["root"]!!.jsonPrimitive.content)

        val dependency = Json.encodeToJsonElement(
            BuildModuleDependencySummaryDto("upstream", "compile"),
        ).jsonObject
        assertKeys("dependency", dependency)
    }

    @Test
    fun projectSummaryTruncatesOversizedBuildModelCollectionsDeterministically() {
        val declarations = (0..ProtocolLimits.MAX_BUILD_ROOTS_PER_SOURCE_SET).joinToString("\n") { index ->
            "sourceSets[\"main\"].java.srcDir(\"roots/r$index\")"
        }
        val root = createProject(
            "build.gradle.kts" to "plugins { java }\n$declarations\n",
            "src/main/java/example/App.java" to "package example; class App {}\n",
        )
        val session = DaemonSession()
        session.dispatch("project.open", params("root" to root))

        val result = session.dispatch("project.summary", null).jsonObject
        val model = result["buildModels"]!!.jsonArray.single().jsonObject
        val main = model["modules"]!!.jsonArray.single().jsonObject["sourceSets"]!!.jsonArray
            .map { it.jsonObject }.single { it["id"]!!.jsonPrimitive.content == "main" }

        assertEquals(
            ProtocolLimits.MAX_BUILD_ROOTS_PER_SOURCE_SET,
            main["sourceRoots"]!!.jsonArray.size,
        )
        assertTrue(main["truncated"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(model["truncated"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(result["buildModelsTruncated"]!!.jsonPrimitive.content.toBoolean())
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
        assertEquals("preview", result["status"]!!.jsonPrimitive.content)
        assertEquals("PREVIEW", result["legacyStatus"]!!.jsonPrimitive.content)
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

        assertEquals("preview", preview["status"]!!.jsonPrimitive.content)
        assertEquals("PREVIEW", preview["legacyStatus"]!!.jsonPrimitive.content)
        assertEquals("true", preview["applyEligible"]!!.jsonPrimitive.content)
        assertEquals("true", preview["applyEligibility"]!!.jsonObject["eligible"]!!.jsonPrimitive.content)
        assertEquals("module", preview["resolvedModule"]!!.jsonPrimitive.content)
        assertEquals("module/src/main/java", preview["resolvedSourceRoot"]!!.jsonPrimitive.content.replace('\\', '/'))
        assertEquals("MAIN", preview["sourceSet"]!!.jsonPrimitive.content)
        assertEquals("com.example", preview["resolvedPackage"]!!.jsonPrimitive.content)
        assertEquals("module/src/main/java/com/example/Imported.java", preview["primaryFile"]!!.jsonPrimitive.content.replace('\\', '/'))
        assertEquals("old.pkg", preview["packageChanges"]!!.jsonArray.single().jsonObject["from"]!!.jsonPrimitive.content)
        assertTrue(preview["renderedDiff"]!!.jsonPrimitive.content.contains("+++ b/module/src/main/java/com/example/Imported.java"))
        val diff = preview["structuredDiff"]!!.jsonArray.single().jsonObject
        assertEquals("create", diff["change"]!!.jsonPrimitive.content)
        assertTrue(diff["hunks"]!!.jsonArray.single().jsonObject["lines"]!!.jsonArray.any {
            it.jsonPrimitive.content == "+package com.example;"
        })
        assertFalse(target.exists(), "preview must not write")

        val applied = session.dispatch("refactor.apply", params("planId" to preview["planId"]!!.jsonPrimitive.content)) as JsonObject
        assertEquals("module/src/main/java/com/example/Imported.java", applied["primaryFile"]!!.jsonPrimitive.content)
        assertTrue(applied["diagnostics"]!!.jsonArray.none {
            it.jsonObject["severity"]!!.jsonPrimitive.content == "error"
        })
        assertTrue(applied["changedFiles"]!!.jsonArray.any {
            val change = it.jsonObject
            change["change"]!!.jsonPrimitive.content == "create" &&
                change["path"]!!.jsonPrimitive.content.endsWith("Imported.java") &&
                change["primary"]!!.jsonPrimitive.content == "true"
        })
        assertTrue(target.readText().startsWith("package com.example;"))
        val transactionId = applied["transactionId"]!!.jsonPrimitive.content

        val rolledBack = session.dispatch("patch.rollback", params("transactionId" to transactionId)).jsonObject
        assertEquals("true", rolledBack["rolledBack"]!!.jsonPrimitive.content)
        assertTrue(rolledBack["changedFiles"]!!.jsonArray.any {
            it.jsonObject["change"]!!.jsonPrimitive.content == "delete"
        })
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
        assertEquals("preview", legacy["status"]!!.jsonPrimitive.content)
        assertTrue(legacy["affectedFiles"]!!.jsonArray.single().jsonObject["path"]!!.jsonPrimitive.content
            .endsWith("src/main/java/com/example/legacy/LegacyImported.java"))
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
            assertEquals("refused", response["status"]!!.jsonPrimitive.content)
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
        assertEquals("unknown", multi["provenance"]!!.jsonObject["licenseRisk"]!!.jsonPrimitive.content)
        assertTrue(multi["provenance"]!!.jsonObject["detectedLicense"] is kotlinx.serialization.json.JsonNull)
        assertEquals("unknown", multi["provenance"]!!.jsonObject["licenseDetected"]!!.jsonPrimitive.content)
        assertTrue(multi["unresolvedDependencies"]!!.jsonArray.any { it.jsonPrimitive.content == "org.acme.Missing" })

        val conflict = session.dispatch("java.importExternalClass", buildJsonObject {
            put("code", "public class App {}")
            put("targetDirectory", "src/main/java/com/example")
            put("licensePolicy", "allow")
        }).jsonObject
        assertEquals("refused", conflict["status"]!!.jsonPrimitive.content)
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
    fun previewDiagnosticsBlockInvalidImportWithoutWorkspaceWritesOrPendingPlan() {
        val root = createProject("src/main/java/com/example/App.java" to "package com.example; public class App {}")
        val rootPath = Paths.get(root)
        val session = DaemonSession()
        session.dispatch("project.open", params("root" to root))

        val preview = session.dispatch("java.importExternalClass", buildJsonObject {
            put("code", "public class BrokenImport { MissingDependency value; }")
            put("targetDirectory", "src/main/java/com/example")
            put("licensePolicy", "allow")
        }).jsonObject

        assertEquals("false", preview["applyEligibility"]!!.jsonObject["eligible"]!!.jsonPrimitive.content)
        assertTrue(preview["applyEligibility"]!!.jsonObject["blockers"]!!.jsonArray.isNotEmpty())
        assertTrue(preview["diagnosticsAfterPreview"]!!.jsonArray.any { diagnostic ->
            val item = diagnostic.jsonObject
            item["severity"]!!.jsonPrimitive.content == "error" &&
                item["path"]!!.jsonPrimitive.content == "src/main/java/com/example/BrokenImport.java" &&
                item["line"]!!.jsonPrimitive.content.toInt() >= 1 &&
                item["column"]!!.jsonPrimitive.content.toInt() >= 1
        })
        assertFalse(rootPath.resolve("src/main/java/com/example/BrokenImport.java").exists())
        val apply = assertFailsWith<JsonRpcException> {
            session.dispatch("refactor.apply", params("planId" to preview["planId"]!!.jsonPrimitive.content))
        }
        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, apply.code)
    }

    @Test
    fun projectSwitchAndDiscardImmediatelyInvalidateSourceBearingPlans() {
        val firstRoot = createProject("src/main/java/com/example/App.java" to "package com.example; public class App {}")
        val secondRoot = createProject("src/main/java/com/other/App.java" to "package com.other; public class App {}")
        val session = DaemonSession()
        session.dispatch("project.open", params("root" to firstRoot))
        fun preview(name: String) = session.dispatch("java.importExternalClass", buildJsonObject {
            put("code", "public class $name {}")
            put("targetDirectory", "src/main/java/com/example")
            put("licensePolicy", "allow")
        }).jsonObject

        val discardedPlan = preview("DiscardedSourceMarker")
        val discarded = session.dispatch("refactor.discard", params("planId" to discardedPlan["planId"]!!.jsonPrimitive.content)).jsonObject
        assertEquals("true", discarded["discarded"]!!.jsonPrimitive.content)
        val discardedAgain = session.dispatch("refactor.discard", params("planId" to discardedPlan["planId"]!!.jsonPrimitive.content)).jsonObject
        assertEquals("false", discardedAgain["discarded"]!!.jsonPrimitive.content)
        assertFalse(Paths.get(firstRoot).resolve("src/main/java/com/example/DiscardedSourceMarker.java").exists())
        assertFailsWith<JsonRpcException> {
            session.dispatch("refactor.apply", params("planId" to discardedPlan["planId"]!!.jsonPrimitive.content))
        }

        val switchedPlan = preview("SwitchedSourceMarker")
        session.dispatch("project.open", params("root" to secondRoot))
        assertFailsWith<JsonRpcException> {
            session.dispatch("refactor.apply", params("planId" to switchedPlan["planId"]!!.jsonPrimitive.content))
        }
        assertFalse(Paths.get(firstRoot).resolve("src/main/java/com/example/SwitchedSourceMarker.java").exists())
        assertFalse(Paths.get(secondRoot).resolve("src/main/java/com/other/SwitchedSourceMarker.java").exists())
    }

    @Test
    fun refusedImportIsNotPendingAndUnknownLicenseIsHighRiskAcknowledgement() {
        val root = createProject("src/main/java/com/example/App.java" to "package com.example; public class App {}")
        val session = DaemonSession()
        session.dispatch("project.open", params("root" to root))
        val warned = session.dispatch("java.importExternalClass", buildJsonObject {
            put("code", "public class UnknownLicenseImport {}")
            put("targetDirectory", "src/main/java/com/example")
            put("licensePolicy", "warn")
        }).jsonObject
        assertEquals("high", warned["riskLevel"]!!.jsonPrimitive.content)
        assertTrue(warned["applyEligibility"]!!.jsonObject["acknowledgementRequirements"]!!.jsonArray.isNotEmpty())
        assertEquals("true", warned["applyEligibility"]!!.jsonObject["eligible"]!!.jsonPrimitive.content)

        val refused = session.dispatch("java.importExternalClass", buildJsonObject {
            put("code", "public class BlockedUnknownLicense {}")
            put("targetDirectory", "src/main/java/com/example")
            put("licensePolicy", "block-unknown")
        }).jsonObject
        assertEquals("refused", refused["status"]!!.jsonPrimitive.content)
        assertFailsWith<JsonRpcException> {
            session.dispatch("refactor.apply", params("planId" to refused["planId"]!!.jsonPrimitive.content))
        }
    }

    @Test
    fun multiTypePrimaryFollowsDeclarationOrderAndDiffOrderIsDeterministic() {
        val root = createProject("src/main/java/com/example/App.java" to "package com.example; public class App {}")
        val session = DaemonSession()
        session.dispatch("project.open", params("root" to root))
        val preview = session.dispatch("java.importExternalClass", buildJsonObject {
            put("code", "public class ZFirst {}\npublic class ASecond {}")
            put("targetDirectory", "src/main/java/com/example")
            put("licensePolicy", "allow")
        }).jsonObject

        assertEquals("src/main/java/com/example/ZFirst.java", preview["primaryFile"]!!.jsonPrimitive.content)
        val affected = preview["affectedFiles"]!!.jsonArray.map { it.jsonObject }
        assertTrue(affected.single { it["path"]!!.jsonPrimitive.content.endsWith("ZFirst.java") }["primary"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(
            listOf("src/main/java/com/example/ASecond.java", "src/main/java/com/example/ZFirst.java"),
            preview["structuredDiff"]!!.jsonArray.map { it.jsonObject["path"]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun pendingPlanLruEvictsOldestPlan() {
        val root = createProject("src/main/java/com/example/App.java" to "package com.example; public class App {}")
        val session = DaemonSession()
        session.dispatch("project.open", params("root" to root))
        val planIds = (0..org.refactorkit.core.ProtocolLimits.MAX_PENDING_PLANS).map { index ->
            session.dispatch("java.importExternalClass", buildJsonObject {
                put("code", "// MIT License\npublic class Lru$index {}")
                put("targetDirectory", "src/main/java/com/example")
                put("licensePolicy", "allow")
            }).jsonObject["planId"]!!.jsonPrimitive.content
        }
        assertFailsWith<JsonRpcException> {
            session.dispatch("refactor.apply", params("planId" to planIds.first()))
        }
        val newest = session.dispatch("refactor.discard", params("planId" to planIds.last())).jsonObject
        assertEquals("true", newest["discarded"]!!.jsonPrimitive.content)
    }

    @Test
    fun importerPreviewSchemaMatchesProtocolSnapshot() {
        val root = createProject("src/main/java/com/example/App.java" to "package com.example; public class App {}")
        val session = DaemonSession()
        session.dispatch("project.open", params("root" to root))
        val preview = session.dispatch("java.importExternalClass", buildJsonObject {
            put("code", "// MIT License\npublic class SchemaImport {}")
            put("targetDirectory", "src/main/java/com/example")
            put("licensePolicy", "allow")
        }).jsonObject
        val expected = Json.parseToJsonElement(
            requireNotNull(javaClass.getResource("/import-preview-schema-keys.json")).readText(),
        ).jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(expected, preview.keys)
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
    fun moveSourceRootPreviewUsesStableFromAndToArguments() {
        val root = createProject(
            "pom.xml" to "<project><modelVersion>4.0.0</modelVersion><groupId>x</groupId><artifactId>root</artifactId><version>1</version><packaging>pom</packaging><properties><maven.compiler.release>21</maven.compiler.release></properties><modules><module>a</module><module>b</module></modules></project>",
            "a/pom.xml" to "<project><modelVersion>4.0.0</modelVersion><parent><groupId>x</groupId><artifactId>root</artifactId><version>1</version><relativePath>../pom.xml</relativePath></parent><artifactId>a</artifactId></project>",
            "b/pom.xml" to "<project><modelVersion>4.0.0</modelVersion><parent><groupId>x</groupId><artifactId>root</artifactId><version>1</version><relativePath>../pom.xml</relativePath></parent><artifactId>b</artifactId></project>",
            "a/src/main/java/x/Value.java" to "package x; public record Value(String text) {}\n",
        )
        val session = DaemonSession()
        session.dispatch("project.open", params("root" to root))

        val result = session.dispatch("refactor.preview", buildJsonObject {
            put("operation", "moveSourceRoot")
            put("arguments", buildJsonObject {
                put("from", "a/src/main/java")
                put("to", "b/src/main/java")
            })
        }).jsonObject

        assertEquals("moveSourceRoot", result["operation"]!!.jsonPrimitive.content)
        assertEquals("PREVIEW", result["status"]!!.jsonPrimitive.content)
        assertTrue(result["affectedFiles"]!!.jsonArray.any { it.jsonPrimitive.content == "b/src/main/java/x/Value.java" })

        val refusal = assertFailsWith<JsonRpcException> {
            session.dispatch("refactor.preview", buildJsonObject {
                put("operation", "moveSourceRoot")
                put("arguments", buildJsonObject {
                    put("from", "a/src/main/java")
                    put("to", "a/src/main/java")
                })
            })
        }
        assertEquals(JsonRpcErrorCodes.PLAN_REFUSED, refusal.code)
        assertEquals("sourceRoot.overlap", refusal.data!!.jsonObject["refusalCode"]!!.jsonPrimitive.content)
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
