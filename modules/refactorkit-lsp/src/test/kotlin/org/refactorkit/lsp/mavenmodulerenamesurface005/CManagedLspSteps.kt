package org.refactorkit.lsp.mavenmodulerenamesurface005

import io.cucumber.java.After
import io.cucumber.java.en.And
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.c.ClangSemanticToolchain
import org.refactorkit.c.ClangToolchainDiscoverer
import org.refactorkit.c.ClangToolchainDiscovery
import org.refactorkit.c.ClangToolchainRequest
import org.refactorkit.c.ClangToolchainRuntimeCapture
import org.refactorkit.c.ClangToolchainRuntimeCurrency
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcException
import org.refactorkit.lsp.LspSession
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CManagedLspSteps {
    private var root: Path? = null
    private var externalIncludeRoot: Path? = null
    private lateinit var session: LspSession
    private lateinit var originalSource: String
    private lateinit var originalDatabase: String
    private var originalProjectFiles: Map<Path, String> = emptyMap()
    private var preview: JsonObject? = null
    private var applied: JsonObject? = null
    private var rolledBack: JsonObject? = null
    private var refusal: JsonRpcException? = null
    private var requestCase = ""
    private var requestOperation = ""
    private var nativeRenameProbe: NativeCEditorRenameProbe.Result? = null
    private var prefixMatrix: CPrefixRenameMatrixFixture? = null

    @Given("an isolated C LSP workspace with an explicitly captured Clang toolchain")
    fun isolatedWorkspace() {
        val toolchainRoot = Path.of(requireNotNull(System.getenv("REFACTORKIT_C_QUALIFICATION_TOOLCHAIN")))
        val clang = toolchainRoot.resolve("usr/bin/clang-22")
        val clangd = toolchainRoot.resolve("usr/bin/clangd")
        val clangFormat = toolchainRoot.resolve("usr/bin/clang-format")
        listOf(clang, clangd, clangFormat).forEach { executable ->
            assertTrue(Files.isExecutable(executable), "Missing captured Clang executable: $executable")
        }

        val workspace = Files.createTempDirectory("refactorkit-c-lsp-managed-")
        root = workspace
        originalSource = "int required[REQUIRED - 16];\n" +
            "static int weighted(int amount, int right);\n" +
            "static int weighted(int amount, int right) {\n" +
            "    return amount + right;\n" +
            "}\n" +
            "int main(void) { return weighted(5, 2); }\n"
        Files.writeString(workspace.resolve("main.c"), originalSource)
        val unit = buildJsonObject {
            put("directory", workspace.toString())
            put("file", "main.c")
            put("arguments", JsonArray(listOf(clang.toString(), "-std=c17", "-DREQUIRED=17", "-c", "main.c").map(::JsonPrimitive)))
        }
        originalDatabase = JsonArray(listOf(unit)).toString()
        Files.writeString(workspace.resolve("compile_commands.json"), originalDatabase)

        initializeSession(workspace)
    }

    @When("the native LSP client requests C rename for the open document")
    fun requestNativeEditorRename() {
        requestCase = "native editor rename"
        requestOperation = "nativeRename"
        nativeRenameProbe = NativeCEditorRenameProbe.evaluate(session, requireNotNull(root), originalSource)
    }

    @When("the LSP client handles C parameter rename case {string}")
    fun executeManagedLifecycle(case: String) {
        requestCase = case
        requestOperation = "changeSignature"
        val workspace = requireNotNull(root)
        val toolchainRoot = Path.of(requireNotNull(System.getenv("REFACTORKIT_C_QUALIFICATION_TOOLCHAIN")))
        val oldParameter = when (case) {
            "compiler-bound family" -> "amount"
            "unknown parameter" -> "absent"
            else -> error("Unexpected C parameter rename case: $case")
        }
        try {
            preview = session.dispatch("workspace/executeCommand", buildJsonObject {
                put("command", "refactorkit.c.preview")
                put("arguments", JsonArray(listOf(buildJsonObject {
                    put("operation", "changeSignature")
                    put("clang", toolchainRoot.resolve("usr/bin/clang-22").toString())
                    put("clangd", toolchainRoot.resolve("usr/bin/clangd").toString())
                    put("clangFormat", toolchainRoot.resolve("usr/bin/clang-format").toString())
                    put("arguments", buildJsonObject {
                        put("file", "main.c")
                        put("symbol", "weighted")
                        put("oldParam", oldParameter)
                        put("newParam", "total")
                    })
                })))
            }).jsonObject
        } catch (rejection: JsonRpcException) {
            refusal = rejection
            return
        }
        if (case == "unknown parameter") return

        val planId = requireNotNull(preview).getValue("planId").jsonPrimitive.content
        applied = session.dispatch("workspace/executeCommand", buildJsonObject {
            put("command", "refactorkit.applyPlan")
            put("arguments", JsonArray(listOf(buildJsonObject { put("planId", planId) })))
        }).jsonObject
        assertTrue(Files.readString(workspace.resolve("main.c")).contains("int total, int right"))
        val transactionId = requireNotNull(applied).getValue("transactionId").jsonPrimitive.content

        rolledBack = session.dispatch("workspace/executeCommand", buildJsonObject {
            put("command", "refactorkit.rollback")
            put("arguments", JsonArray(listOf(buildJsonObject { put("transactionId", transactionId) })))
        }).jsonObject
    }

    @When("the LSP client handles C scalar extraction case {string}")
    fun executeScalarExtractionLifecycle(case: String) {
        requestCase = case
        requestOperation = "extractExpression"
        val workspace = requireNotNull(root)
        originalSource = "int required[REQUIRED - 16];\n" +
            "int calculate(int a, int b) {\n" +
            "    double ratio = a / b;\n" +
            "    return ratio == 2.0;\n" +
            "}\n" +
            "int main(void) { return calculate(5, 2) ? 0 : 1; }\n"
        Files.writeString(workspace.resolve("main.c"), originalSource)
        originalDatabase = originalDatabase.replace("-std=c17", "-std=c11")
        Files.writeString(workspace.resolve("compile_commands.json"), originalDatabase)
        initializeSession(workspace)
        val toolchainRoot = Path.of(requireNotNull(System.getenv("REFACTORKIT_C_QUALIFICATION_TOOLCHAIN")))
        val expression = "a / b"
        val startLine = originalSource.lines().indexOfFirst { expression in it }
        val startCharacter = originalSource.lineSequence().elementAt(startLine).indexOf(expression)
        assertTrue(startCharacter >= 0)
        val tempName = when (case) {
            "compiler-bound scalar expression" -> "total"
            "temporary collides with parameter" -> "a"
            else -> error("Unexpected C scalar extraction case: $case")
        }
        try {
            preview = session.dispatch("workspace/executeCommand", buildJsonObject {
                put("command", "refactorkit.c.preview")
                put("arguments", JsonArray(listOf(buildJsonObject {
                    put("operation", "extractExpression")
                    put("clang", toolchainRoot.resolve("usr/bin/clang-22").toString())
                    put("clangd", toolchainRoot.resolve("usr/bin/clangd").toString())
                    put("clangFormat", toolchainRoot.resolve("usr/bin/clang-format").toString())
                    put("arguments", buildJsonObject {
                        put("file", "main.c")
                        put("tempName", tempName)
                        put("startLine", startLine.toString())
                        put("startChar", startCharacter.toString())
                        put("endLine", startLine.toString())
                        put("endChar", (startCharacter + expression.length).toString())
                    })
                })))
            }).jsonObject
        } catch (rejection: JsonRpcException) {
            refusal = rejection
            return
        }
        if (case == "temporary collides with parameter") return

        val planId = requireNotNull(preview).getValue("planId").jsonPrimitive.content
        applied = session.dispatch("workspace/executeCommand", buildJsonObject {
            put("command", "refactorkit.applyPlan")
            put("arguments", JsonArray(listOf(buildJsonObject { put("planId", planId) })))
        }).jsonObject
        val appliedSource = Files.readString(requireNotNull(root).resolve("main.c"))
        assertTrue(appliedSource.contains("const int total = a / b;"))
        assertTrue(appliedSource.contains("double ratio = total;"))
        val transactionId = requireNotNull(applied).getValue("transactionId").jsonPrimitive.content
        rolledBack = session.dispatch("workspace/executeCommand", buildJsonObject {
            put("command", "refactorkit.rollback")
            put("arguments", JsonArray(listOf(buildJsonObject { put("transactionId", transactionId) })))
        }).jsonObject
    }

    @When("the LSP client handles C statement helper case {string}")
    fun executeStatementHelperLifecycle(case: String) {
        requestCase = case
        requestOperation = "extractStatements"
        val workspace = requireNotNull(root)
        originalSource = "int required[REQUIRED - 16];\n" +
            "static int calculate(int a, int b) {\n" +
            "    int sum = a + b;\n" +
            "    int doubled = sum * 2;\n" +
            "    return doubled;\n" +
            "}\n" +
            "int main(void) { return calculate(7, 14) == 42 ? 0 : 1; }\n"
        Files.writeString(workspace.resolve("main.c"), originalSource)
        initializeSession(workspace)
        val selectedStart = originalSource.indexOf("int sum =")
        val doubledStart = originalSource.indexOf("int doubled =")
        val selectedEnd = originalSource.indexOf(';', doubledStart) + 1
        assertTrue(selectedStart >= 0 && doubledStart >= 0 && selectedEnd > doubledStart)
        val startByte = originalSource.substring(0, selectedStart).toByteArray(Charsets.UTF_8).size
        val endByte = originalSource.substring(0, selectedEnd).toByteArray(Charsets.UTF_8).size
        val helperName = when (case) {
            "compiler-bound statement range" -> "compute"
            "helper collides with existing function" -> "calculate"
            else -> error("Unexpected C statement helper case: $case")
        }
        val toolchainRoot = Path.of(requireNotNull(System.getenv("REFACTORKIT_C_QUALIFICATION_TOOLCHAIN")))
        try {
            preview = session.dispatch("workspace/executeCommand", buildJsonObject {
                put("command", "refactorkit.c.preview")
                put("arguments", JsonArray(listOf(buildJsonObject {
                    put("operation", "extractStatements")
                    put("clang", toolchainRoot.resolve("usr/bin/clang-22").toString())
                    put("clangd", toolchainRoot.resolve("usr/bin/clangd").toString())
                    put("clangFormat", toolchainRoot.resolve("usr/bin/clang-format").toString())
                    put("arguments", buildJsonObject {
                        put("file", "main.c")
                        put("symbol", "calculate")
                        put("helperName", helperName)
                        put("startByte", startByte.toString())
                        put("endByte", endByte.toString())
                    })
                })))
            }).jsonObject
        } catch (rejection: JsonRpcException) {
            refusal = rejection
            return
        }
        if (case == "helper collides with existing function") return

        val planId = requireNotNull(preview).getValue("planId").jsonPrimitive.content
        applied = session.dispatch("workspace/executeCommand", buildJsonObject {
            put("command", "refactorkit.applyPlan")
            put("arguments", JsonArray(listOf(buildJsonObject { put("planId", planId) })))
        }).jsonObject
        val appliedSource = Files.readString(workspace.resolve("main.c"))
        assertTrue(appliedSource.contains("static int compute(int a, int b)"))
        assertTrue(appliedSource.contains("int doubled = compute(a, b);"))
        val transactionId = requireNotNull(applied).getValue("transactionId").jsonPrimitive.content
        rolledBack = session.dispatch("workspace/executeCommand", buildJsonObject {
            put("command", "refactorkit.rollback")
            put("arguments", JsonArray(listOf(buildJsonObject { put("transactionId", transactionId) })))
        }).jsonObject
    }

    @When("the LSP client handles C prefix rename case {string}")
    fun executePrefixRenameLifecycle(case: String) {
        if (case.startsWith("matrix: ")) {
            prefixMatrix = CPrefixRenameMatrixFixture(requireNotNull(root)).also { it.evaluate(case) }
            return
        }
        requestCase = case
        requestOperation = "renameCSymbolPrefix"
        val workspace = requireNotNull(root)
        val header = "#ifndef API_H\n#define API_H\nint calculate(int value);\n#endif\n"
        val implementation = "#include \"api.h\"\nint calculate(int value) { return value * 2; }\n"
        originalSource = "#include \"api.h\"\nint main(void) { return calculate(21) == 42 ? 0 : 1; }\n"
        Files.writeString(workspace.resolve("api.h"), header)
        Files.writeString(workspace.resolve("api.c"), implementation)
        Files.writeString(workspace.resolve("main.c"), originalSource)
        originalProjectFiles = mapOf(
            workspace.resolve("api.h") to header,
            workspace.resolve("api.c") to implementation,
            workspace.resolve("main.c") to originalSource,
        )
        val toolchainRoot = Path.of(requireNotNull(System.getenv("REFACTORKIT_C_QUALIFICATION_TOOLCHAIN")))
        val clang = toolchainRoot.resolve("usr/bin/clang-22")
        val includePath = if (case == "symlinked include escape") {
            val external = Files.createTempDirectory("refactorkit-c-lsp-external-include-")
            externalIncludeRoot = external
            Files.createDirectory(external.resolve("include"))
            Files.createSymbolicLink(workspace.resolve("linked-includes"), external)
            "linked-includes/include"
        } else "."
        val units = listOf("api.c", "main.c").map { file ->
            buildJsonObject {
                put("directory", workspace.toString())
                put("file", file)
                put("arguments", JsonArray(listOf(
                    clang.toString(), "-std=c17", "-I", includePath, "-c", file,
                ).map(::JsonPrimitive)))
            }
        }
        originalDatabase = JsonArray(units).toString()
        Files.writeString(workspace.resolve("compile_commands.json"), originalDatabase)
        initializeSession(workspace)

        val mapping = when (case) {
            "compiler-bound API family" -> "calculate"
            "unresolved API symbol" -> "absent"
            "symlinked include escape" -> "calculate"
            else -> error("Unexpected C prefix rename case: $case")
        }
        try {
            preview = session.dispatch("workspace/executeCommand", buildJsonObject {
                put("command", "refactorkit.c.preview")
                put("arguments", JsonArray(listOf(buildJsonObject {
                    put("operation", "renamePrefix")
                    put("clang", clang.toString())
                    put("clangd", toolchainRoot.resolve("usr/bin/clangd").toString())
                    put("clangFormat", toolchainRoot.resolve("usr/bin/clang-format").toString())
                    put("arguments", buildJsonObject { put("rename.$mapping", "compute") })
                })))
            }).jsonObject
        } catch (rejection: JsonRpcException) {
            refusal = rejection
            return
        }
        if (case in setOf("unresolved API symbol", "symlinked include escape")) return

        val managedPreview = requireNotNull(preview)
        val changedPaths = (managedPreview.getValue("affectedFiles") as JsonArray)
            .map { Path.of(it.jsonPrimitive.content) }.toSet()
        assertEquals(setOf(Path.of("api.h"), Path.of("api.c"), Path.of("main.c")), changedPaths)
        val planId = managedPreview.getValue("planId").jsonPrimitive.content
        applied = session.dispatch("workspace/executeCommand", buildJsonObject {
            put("command", "refactorkit.applyPlan")
            put("arguments", JsonArray(listOf(buildJsonObject { put("planId", planId) })))
        }).jsonObject
        assertTrue(Files.readString(workspace.resolve("api.h")).contains("int compute(int value);"))
        assertTrue(Files.readString(workspace.resolve("api.c")).contains("int compute(int value)"))
        assertTrue(Files.readString(workspace.resolve("main.c")).contains("compute(21)"))
        val transactionId = requireNotNull(applied).getValue("transactionId").jsonPrimitive.content
        rolledBack = session.dispatch("workspace/executeCommand", buildJsonObject {
            put("command", "refactorkit.rollback")
            put("arguments", JsonArray(listOf(buildJsonObject { put("transactionId", transactionId) })))
        }).jsonObject
    }

    @When("the LSP client handles C safe-delete case {string}")
    fun executeSafeDeleteLifecycle(case: String) {
        requestCase = case
        requestOperation = "safeDelete"
        val workspace = requireNotNull(root)
        originalSource = when (case) {
            "isolated unused private function" ->
                "static int unused(void) { return 3; }\nint main(void) { return 0; }\n"
            "unused file-static object" ->
                "static int unused = 3;\nint main(void) { return 0; }\n"
            "referenced private function" ->
                "static int unused(void) { return 3; }\nint main(void) { return unused(); }\n"
            "public function without external-consumer evidence" ->
                "int unused(void) { return 3; }\nint main(void) { return 0; }\n"
            else -> error("Unexpected C safe-delete case: $case")
        }
        Files.writeString(workspace.resolve("main.c"), originalSource)
        initializeSession(workspace)

        val toolchainRoot = Path.of(requireNotNull(System.getenv("REFACTORKIT_C_QUALIFICATION_TOOLCHAIN")))
        try {
            preview = session.dispatch("workspace/executeCommand", buildJsonObject {
                put("command", "refactorkit.c.preview")
                put("arguments", JsonArray(listOf(buildJsonObject {
                    put("operation", "safeDelete")
                    put("clang", toolchainRoot.resolve("usr/bin/clang-22").toString())
                    put("clangd", toolchainRoot.resolve("usr/bin/clangd").toString())
                    put("clangFormat", toolchainRoot.resolve("usr/bin/clang-format").toString())
                    put("arguments", buildJsonObject { put("symbol", "unused") })
                })))
            }).jsonObject
        } catch (rejection: JsonRpcException) {
            refusal = rejection
            return
        }
        if (case == "referenced private function") return

        val planId = requireNotNull(preview).getValue("planId").jsonPrimitive.content
        applied = session.dispatch("workspace/executeCommand", buildJsonObject {
            put("command", "refactorkit.applyPlan")
            put("arguments", JsonArray(listOf(buildJsonObject { put("planId", planId) })))
        }).jsonObject
        val appliedSource = Files.readString(workspace.resolve("main.c"))
        assertFalse(appliedSource.contains("unused"), appliedSource)
        assertTrue(appliedSource.contains("int main(void)"), appliedSource)
        val transactionId = requireNotNull(applied).getValue("transactionId").jsonPrimitive.content
        rolledBack = session.dispatch("workspace/executeCommand", buildJsonObject {
            put("command", "refactorkit.rollback")
            put("arguments", JsonArray(listOf(buildJsonObject { put("transactionId", transactionId) })))
        }).jsonObject
    }

    @When("the LSP client applies C include ordering for {string}")
    fun applyCIncludeOrdering(case: String) {
        assertEquals("independent prototypes across two captured C17 translation units", case)
        requestCase = case
        requestOperation = "organizeIncludes"
        val workspace = requireNotNull(root)
        val headers = mapOf(
            "alpha.h" to "#pragma once\nlong alpha_api(void);\n",
            "zeta.h" to "#pragma once\nint zeta_api(void);\n",
        )
        originalSource = "#include \"zeta.h\"\n#include \"alpha.h\"\nint main(void) { return 0; }\n"
        val consumer = "#include \"alpha.h\"\n#include \"zeta.h\"\n" +
            "int consumer(void) { return (int) alpha_api() + zeta_api(); }\n"
        Files.writeString(workspace.resolve("main.c"), originalSource)
        Files.writeString(workspace.resolve("consumer.c"), consumer)
        headers.forEach { (name, contents) -> Files.writeString(workspace.resolve(name), contents) }
        originalProjectFiles = buildMap {
            put(workspace.resolve("main.c"), originalSource)
            put(workspace.resolve("consumer.c"), consumer)
            headers.forEach { (name, contents) -> put(workspace.resolve(name), contents) }
        }

        val toolchainRoot = Path.of(requireNotNull(System.getenv("REFACTORKIT_C_QUALIFICATION_TOOLCHAIN")))
        val clang = toolchainRoot.resolve("usr/bin/clang-22")
        val units = listOf("main.c", "consumer.c").map { file ->
            buildJsonObject {
                put("directory", workspace.toString())
                put("file", file)
                put("arguments", JsonArray(listOf(
                    clang.toString(), "-std=c17", "-c", file,
                ).map(::JsonPrimitive)))
            }
        }
        originalDatabase = JsonArray(units).toString()
        Files.writeString(workspace.resolve("compile_commands.json"), originalDatabase)
        initializeSession(workspace)

        val previewResult = session.dispatch("workspace/executeCommand", buildJsonObject {
            put("command", "refactorkit.c.preview")
            put("arguments", JsonArray(listOf(buildJsonObject {
                put("operation", "organizeIncludes")
                put("clang", clang.toString())
                put("clangd", toolchainRoot.resolve("usr/bin/clangd").toString())
                put("clangFormat", toolchainRoot.resolve("usr/bin/clang-format").toString())
                put("arguments", buildJsonObject { put("file", "main.c") })
            })))
        }).jsonObject
        preview = previewResult
        val diff = previewResult.getValue("renderedDiff").jsonPrimitive.content
        assertTrue(diff.contains("+#include \"alpha.h\""), diff)
        assertTrue(diff.contains("+#include \"zeta.h\""), diff)
        assertEquals(listOf(Path.of("main.c")), (previewResult.getValue("affectedFiles") as JsonArray)
            .map { Path.of(it.jsonPrimitive.content) })

        applied = session.dispatch("workspace/executeCommand", buildJsonObject {
            put("command", "refactorkit.applyPlan")
            put("arguments", JsonArray(listOf(buildJsonObject {
                put("planId", previewResult.getValue("planId").jsonPrimitive.content)
            })))
        }).jsonObject
        val appliedSource = Files.readString(workspace.resolve("main.c"))
        assertTrue(appliedSource.startsWith("#include \"alpha.h\"\n#include \"zeta.h\"\n"), appliedSource)
        assertEquals(consumer, Files.readString(workspace.resolve("consumer.c")))

        val transactionId = requireNotNull(applied).getValue("transactionId").jsonPrimitive.content
        rolledBack = session.dispatch("workspace/executeCommand", buildJsonObject {
            put("command", "refactorkit.rollback")
            put("arguments", JsonArray(listOf(buildJsonObject { put("transactionId", transactionId) })))
        }).jsonObject
    }

    @When("the LSP client requests C include ordering for {string}")
    fun requestCIncludeOrdering(case: String) {
        assertEquals("typedef-dependent local headers", case)
        requestCase = case
        requestOperation = "organizeIncludes"
        val workspace = requireNotNull(root)
        val headers = mapOf(
            "alpha.h" to "#pragma once\ntypedef int rk_value_t;\n",
            "zeta.h" to "#pragma once\nrk_value_t zeta_api(void);\n",
        )
        originalSource = "#include \"zeta.h\"\n#include \"alpha.h\"\nint main(void) { return 0; }\n"
        Files.writeString(workspace.resolve("main.c"), originalSource)
        headers.forEach { (name, contents) -> Files.writeString(workspace.resolve(name), contents) }
        originalProjectFiles = buildMap {
            put(workspace.resolve("main.c"), originalSource)
            headers.forEach { (name, contents) -> put(workspace.resolve(name), contents) }
        }

        val toolchainRoot = Path.of(requireNotNull(System.getenv("REFACTORKIT_C_QUALIFICATION_TOOLCHAIN")))
        val clang = toolchainRoot.resolve("usr/bin/clang-22")
        val unit = buildJsonObject {
            put("directory", workspace.toString())
            put("file", "main.c")
            put("arguments", JsonArray(listOf(
                clang.toString(), "-std=c17", "-c", "main.c",
            ).map(::JsonPrimitive)))
        }
        originalDatabase = JsonArray(listOf(unit)).toString()
        Files.writeString(workspace.resolve("compile_commands.json"), originalDatabase)
        initializeSession(workspace)

        try {
            preview = session.dispatch("workspace/executeCommand", buildJsonObject {
                put("command", "refactorkit.c.preview")
                put("arguments", JsonArray(listOf(buildJsonObject {
                    put("operation", "organizeIncludes")
                    put("clang", clang.toString())
                    put("clangd", toolchainRoot.resolve("usr/bin/clangd").toString())
                    put("clangFormat", toolchainRoot.resolve("usr/bin/clang-format").toString())
                    put("arguments", buildJsonObject { put("file", "main.c") })
                })))
            }).jsonObject
        } catch (rejection: JsonRpcException) {
            refusal = rejection
        }
    }

    @Then("the LSP returns the C outcome {string}")
    fun assertProtocolResults(outcome: String) {
        prefixMatrix?.let { it.assertOutcome(outcome); return }
        when (outcome) {
            "applied and rolled back" -> {
                assertTrue(requestCase in setOf(
                    "compiler-bound family", "compiler-bound scalar expression", "compiler-bound statement range",
                    "compiler-bound API family", "isolated unused private function", "unused file-static object",
                    "independent prototypes across two captured C17 translation units",
                ))
                val managedPreview = assertNotNull(preview, "Unexpected preview refusal: ${refusal?.message}; data=${refusal?.data}")
                val managedApply = assertNotNull(applied)
                val managedRollback = assertNotNull(rolledBack)
                assertNull(refusal)
                assertEquals("PREVIEW", managedPreview.getValue("status").jsonPrimitive.content)
                assertEquals(requestOperation, managedPreview.getValue("operation").jsonPrimitive.content)
                val expectedEvidence = if (requestOperation == "organizeIncludes") "STRUCTURAL" else "COMPILER_PROVEN"
                assertEquals(expectedEvidence, managedPreview.getValue("evidence").jsonPrimitive.content)
                val diff = managedPreview.getValue("renderedDiff").jsonPrimitive.content
                if (requestOperation == "changeSignature") {
                    assertTrue(diff.contains("+static int weighted(int total, int right)"))
                } else if (requestOperation == "extractExpression") {
                    assertTrue(diff.contains("+    const int total = a / b;"))
                    assertTrue(diff.contains("+    double ratio = total;"))
                } else {
                    if (requestOperation == "extractStatements") {
                        assertTrue(diff.contains("+static int compute(int a, int b)"))
                        assertTrue(diff.contains("+    int doubled = compute(a, b);"))
                    } else if (requestOperation == "safeDelete") {
                        val deletedDeclaration = if (requestCase == "unused file-static object") {
                            "-static int unused = 3;"
                        } else {
                            "-static int unused(void) { return 3; }"
                        }
                        assertTrue(diff.contains(deletedDeclaration), diff)
                    } else if (requestOperation == "organizeIncludes") {
                        assertTrue(diff.contains("+#include \"alpha.h\""), diff)
                        assertTrue(diff.contains("+#include \"zeta.h\""), diff)
                        val warnings = (managedPreview.getValue("warnings") as JsonArray)
                            .map { it.jsonPrimitive.content }
                        assertTrue(warnings.any { it.contains("exact captured C compiler evaluated all 2 translation units") }, warnings.toString())
                    } else {
                        assertTrue(diff.contains("calculate") && diff.contains("compute"))
                        assertTrue(diff.contains("api.h") && diff.contains("api.c") && diff.contains("main.c"))
                    }
                }
                assertFalse("changes" in managedPreview || "documentChanges" in managedPreview, "C previews must remain managed plans")
                assertTrue(managedApply.getValue("transactionId").jsonPrimitive.content.isNotBlank())
                assertEquals("rolledBack", managedRollback.getValue("status").jsonPrimitive.content)
                assertEquals(managedApply.getValue("transactionId"), managedRollback.getValue("transactionId"))
            }
            "editor-managed refusal" -> {
                assertEquals("native editor rename", requestCase)
                val observation = assertNotNull(nativeRenameProbe)
                assertEquals(JsonNull, observation.prepared)
                assertNull(observation.editorEdit, "Native C rename must return no editor-applied WorkspaceEdit")
                val rejected = assertNotNull(observation.refusal)
                assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, rejected.code)
                assertTrue(rejected.message.orEmpty().contains("C mutations are managed RefactorKit commands"), rejected.message.orEmpty())
                assertTrue(rejected.message.orEmpty().contains("does not offer editor-applied C rename"), rejected.message.orEmpty())
                assertEquals("22.1.8", observation.toolchain.provenance.clangVersion)
                assertEquals("31a40dc31f3c15a47aa119aa148f339bf363d8f61202ddbdacbd3f24b71ba113",
                    observation.toolchain.provenance.evidence.single { it.role == "clang-executable" }.sha256)
                assertEquals(ClangToolchainRuntimeCurrency.Current, ClangToolchainRuntimeCapture().checkCurrent(observation.toolchain))
                assertNull(preview)
                assertNull(applied)
                assertNull(rolledBack)
            }
            "compiler refusal" -> {
                assertTrue(requestCase in setOf(
                    "unknown parameter", "temporary collides with parameter", "helper collides with existing function",
                    "unresolved API symbol", "symlinked include escape", "referenced private function",
                    "public function without external-consumer evidence", "typedef-dependent local headers",
                ))
                val rejected = assertNotNull(refusal)
                assertNull(preview)
                assertNull(applied)
                assertNull(rolledBack)
                assertEquals(JsonRpcErrorCodes.PLAN_REFUSED, rejected.code)
                if (requestOperation == "safeDelete") {
                    when (requestCase) {
                        "referenced private function" -> assertTrue(
                            rejected.message.orEmpty().contains("semantic reference"),
                            rejected.message.orEmpty(),
                        )
                        "public function without external-consumer evidence" -> {
                            assertTrue(rejected.message.orEmpty().contains("extern/public"), rejected.message.orEmpty())
                            assertTrue(
                                rejected.message.orEmpty().contains("external consumer is unavailable"),
                                rejected.message.orEmpty(),
                            )
                        }
                    }
                    return
                }
                if (requestOperation == "organizeIncludes") {
                    assertTrue(
                        rejected.message.orEmpty().contains("Quoted includes can be order-sensitive"),
                        rejected.message.orEmpty(),
                    )
                    assertEquals("c.includeOrder.refused", rejected.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content)
                    return
                }
                val expectedRefusal = when {
                    requestOperation == "changeSignature" -> "c.signatureRename.parameterNotFound"
                    requestOperation == "extractExpression" -> "c.scalarExtract.name_collision"
                    requestOperation == "extractStatements" -> "c.statementExtract.name_collision"
                    requestCase == "symlinked include escape" -> "c.sourceCapture.unavailable"
                    else -> "c.renamePrefix.refused"
                }
                assertEquals(expectedRefusal, rejected.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content)
                if (requestCase == "symlinked include escape") {
                    assertEquals("SYMBOLIC_LINK", rejected.data?.jsonObject?.get("sourceCaptureFailure")?.jsonPrimitive?.content)
                }
            }
            else -> error("Unexpected expected outcome: $outcome")
        }
    }

    @And("the original project files and transaction state match {string}")
    fun assertWorkspaceRestored(outcome: String) {
        prefixMatrix?.let { it.assertRestored(outcome); return }
        val workspace = requireNotNull(root)
        val expectedSources = originalProjectFiles.ifEmpty { mapOf(workspace.resolve("main.c") to originalSource) }
        expectedSources.forEach { (path, content) -> assertEquals(content, Files.readString(path)) }
        assertEquals(originalDatabase, Files.readString(workspace.resolve("compile_commands.json")))
        val transactionDirectory = workspace.resolve(".refactorkit/transactions")
        if (outcome == "applied and rolled back") {
            assertTrue(Files.isDirectory(transactionDirectory))
            val stalePlan = try {
                session.dispatch("workspace/executeCommand", buildJsonObject {
                    put("command", "refactorkit.applyPlan")
                    put("arguments", JsonArray(listOf(buildJsonObject {
                        put("planId", requireNotNull(preview).getValue("planId").jsonPrimitive.content)
                    })))
                })
                null
            } catch (rejection: JsonRpcException) {
                rejection
            }
            val staleRejection = assertIs<JsonRpcException>(stalePlan)
            assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, staleRejection.code)
        } else {
            assertFalse(Files.exists(transactionDirectory), "Refused preview must not create a managed transaction journal")
            if (requestCase == "symlinked include escape") {
                val link = workspace.resolve("linked-includes")
                assertTrue(Files.isSymbolicLink(link))
                assertEquals(requireNotNull(externalIncludeRoot), Files.readSymbolicLink(link))
            }
        }
    }

    @After
    fun cleanWorkspace() {
        root?.let { workspace ->
            if (Files.exists(workspace)) Files.walk(workspace).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
        externalIncludeRoot?.let { external ->
            if (Files.exists(external)) Files.walk(external).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
        root = null
        externalIncludeRoot = null
    }

    private fun initializeSession(workspace: Path) {
        session = LspSession()
        session.dispatch("initialize", buildJsonObject {
            put("rootUri", workspace.toUri().toString())
            put("capabilities", buildJsonObject {
                put("workspace", buildJsonObject {
                    put("workspaceEdit", buildJsonObject { put("documentChanges", true) })
                })
            })
        })
    }
}

/** Stateless protocol fixture; all mutable observations belong to the scenario. */
private object NativeCEditorRenameProbe {
    data class Result(
        val toolchain: ClangSemanticToolchain,
        val prepared: JsonElement,
        val editorEdit: JsonElement?,
        val refusal: JsonRpcException?,
    )

    fun evaluate(session: LspSession, workspace: Path, source: String): Result {
        val sdk = Path.of(requireNotNull(System.getenv("REFACTORKIT_C_QUALIFICATION_TOOLCHAIN")))
        val toolchain = assertIs<ClangToolchainDiscovery.Available>(ClangToolchainDiscoverer().discover(
            ClangToolchainRequest(workspace, sdk.resolve("usr/bin/clang-22"), sdk.resolve("usr/bin/clangd"), sdk.resolve("usr/bin/clang-format")),
        )).toolchain
        val uri = workspace.resolve("main.c").toUri().toString()
        session.dispatch("textDocument/didOpen", buildJsonObject {
            put("textDocument", buildJsonObject {
                put("uri", uri); put("languageId", "c"); put("version", 1); put("text", source)
            })
        })
        val line = source.lineSequence().toList()[1]
        val character = line.indexOf("weighted")
        assertTrue(character >= 0, "Native rename must address the actual declared C function")
        val params = buildJsonObject {
            put("textDocument", buildJsonObject { put("uri", uri) })
            put("position", buildJsonObject { put("line", 1); put("character", character) })
            put("newName", "renamedWeighted")
        }
        val prepared = session.dispatch("textDocument/prepareRename", params)
        var refusal: JsonRpcException? = null
        val edit = try { session.dispatch("textDocument/rename", params) }
            catch (rejection: JsonRpcException) { refusal = rejection; null }
        return Result(toolchain, prepared, edit, refusal)
    }
}
