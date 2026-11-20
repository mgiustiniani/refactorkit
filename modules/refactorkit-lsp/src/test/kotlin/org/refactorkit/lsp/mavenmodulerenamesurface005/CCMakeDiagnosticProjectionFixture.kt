package org.refactorkit.lsp.mavenmodulerenamesurface005

import kotlinx.serialization.json.*
import org.refactorkit.core.ProtocolLimits
import org.refactorkit.lsp.LspSession
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class CCMakeDiagnosticProjectionFixture(private val condition: String) : AutoCloseable {
    private val root = Files.createTempDirectory("rk-lsp-cmake-diagnostics-")
    private val tools = Path.of(requireNotNull(System.getenv("REFACTORKIT_C_QUALIFICATION_TOOLCHAIN"))).resolve("usr/bin")
    private val main = "src/app/main.c"
    private val helper = "src/app/helper.c"
    private val moved = "src/app/moved/main.c"
    private val warningsPerFile = if (condition == "many warnings") 251 else 1
    private val original = mapOf(
        main to source("MAIN_ONLY", 3, 201112, "rk_main_context", "main"),
        helper to source("HELPER_ONLY", 7, 201710, "rk_helper_context", "helper"),
        "CMakeLists.txt" to "cmake_minimum_required(VERSION 3.20)\nproject(demo LANGUAGES C)\nadd_executable(app $main $helper)\n",
        "compile_commands.json" to buildJsonArray {
            add(command(main, "-std=c11", "-DMAIN_ONLY=3"))
            if (condition != "missing TU entry") add(command(helper, "-std=c17", "-DHELPER_ONLY=7"))
        }.toString(),
    )
    private val packageRoot = System.getProperty("refactorkit.c.lsp.component.package.root")?.let(Path::of)
    private val packaged = packageRoot?.let { PackagedCQueryLspClient(it, root) }
    private val session = if (packaged == null) LspSession() else null
    private lateinit var response: JsonObject

    init {
        original.forEach { (name, content) ->
            val path = root.resolve(name)
            Files.createDirectories(path.parent)
            Files.writeString(path, content)
        }
        dispatch("initialize", buildJsonObject {
            put("rootUri", root.toUri().toString())
            put("capabilities", buildJsonObject {})
        })
    }

    private fun source(define: String, value: Int, version: Int, marker: String, function: String) = buildString {
        append("#if !defined($define) || $define != $value\n#error captured_flags_required\n#endif\n")
        append("_Static_assert(__STDC_VERSION__ == ${version}L, \"captured dialect required\");\n")
        repeat(warningsPerFile) { index -> append("#pragma message(\"${marker}_$index\")\n") }
        append("int $function(void) { return 0; }\n")
    }

    private fun command(file: String, dialect: String, flag: String) = buildJsonObject {
        put("directory", root.toString())
        put("file", file)
        put("arguments", JsonArray(listOf(tools.resolve("clang-22").toString(), dialect, flag, "-c", file).map(::JsonPrimitive)))
    }

    fun preview() {
        response = dispatch("workspace/executeCommand", buildJsonObject {
            put("command", "refactorkit.c.preview")
            put("arguments", buildJsonArray {
                add(buildJsonObject {
                    put("operation", "moveSource")
                    put("clang", tools.resolve("clang-22").toString())
                    put("clangd", tools.resolve("clangd").toString())
                    put("clangFormat", tools.resolve("clang-format").toString())
                    put("arguments", buildJsonObject { put("file", main); put("target", moved) })
                })
            })
        }).jsonObject
        assertEquals("PREVIEW", response.getValue("status").jsonPrimitive.content)
        assertFalse("changes" in response || "documentChanges" in response)
    }

    fun assertProjection(evidence: String) {
        val warnings = response.getValue("warnings").jsonArray.map { it.jsonPrimitive.content }
        if (evidence == "unavailable") {
            assertTrue(warnings.any { it.startsWith("c.previewDiagnosticsUnavailable:") })
            assertFalse(warnings.any { it.startsWith("c.previewDiagnosticsVerified:") })
            assertFalse("diagnosticsBefore" in response || "diagnosticsAfterPreview" in response)
            return
        }
        assertTrue(warnings.any { it.contains("evaluated all 2 captured CMake TUs") })
        val truncated = evidence == "bounded projection"
        assertPhase("diagnosticsBefore", main, truncated)
        assertPhase("diagnosticsAfterPreview", moved, truncated)
    }

    private fun assertPhase(key: String, mainPath: String, truncated: Boolean) {
        val items = assertNotNull(response[key], "Missing $key on a preview with Clang warnings").jsonArray
        assertEquals(truncated, response.getValue("${key}Truncated").jsonPrimitive.boolean)
        val limit = if (truncated) ProtocolLimits.MAX_PREVIEW_DIAGNOSTICS else 2
        assertEquals(limit, items.size)
        assertTrue(items.toString().toByteArray(Charsets.UTF_8).size <= ProtocolLimits.MAX_PREVIEW_DIAGNOSTIC_BYTES)
        val expected = mapOf(
            "rk_main_context" to root.resolve(mainPath).toUri().toString(),
            "rk_helper_context" to root.resolve(helper).toUri().toString(),
        )
        val markers = items.map { element ->
            val item = element.jsonObject
            val message = item.getValue("message").jsonPrimitive.content
            val marker = expected.keys.single { message.startsWith(it) }
            assertEquals(expected.getValue(marker), item.getValue("uri").jsonPrimitive.content)
            assertEquals(2, item.getValue("severity").jsonPrimitive.int)
            assertEquals("clang.warning", item.getValue("code").jsonPrimitive.content)
            assertEquals("COMPILER", item.getValue("data").jsonObject.getValue("evidence").jsonPrimitive.content)
            val line = item.getValue("range").jsonObject.getValue("start").jsonObject.getValue("line").jsonPrimitive.int
            assertTrue(line in 4 until 4 + warningsPerFile)
            marker
        }
        assertEquals(expected.keys, markers.toSet())
    }

    fun assertIntact() {
        val image = Files.walk(root).use { paths ->
            paths.filter(Files::isRegularFile).toList().associate {
                root.relativize(it).toString() to Files.readString(it)
            }
        }
        assertEquals(original, image)
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
    }

    private fun dispatch(method: String, parameters: JsonObject): JsonElement =
        packaged?.dispatch(method, parameters) ?: requireNotNull(session).dispatch(method, parameters)

    override fun close() {
        try {
            if (packaged != null) packaged.close() else requireNotNull(session).dispatch("shutdown", null)
        } finally {
            check(root.toFile().deleteRecursively())
        }
    }
}
