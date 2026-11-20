package org.refactorkit.lsp.mavenmodulerenamesurface005

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcException
import org.refactorkit.lsp.LspSession
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Scenario-owned observations; expected source images are authored independently of the planner. */
internal class CPrefixRenameMatrixFixture(private val root: Path) {
    private val session = LspSession()
    private lateinit var original: Map<String, String>
    private lateinit var expected: Map<String, String>
    private lateinit var selectedCase: String
    private var preview: JsonObject? = null
    private var applied: JsonObject? = null
    private var rolledBack: JsonObject? = null
    private var refusal: JsonRpcException? = null

    fun evaluate(case: String) {
        selectedCase = case
        val occupied = case == "matrix: occupied destination"
        val header = "#ifndef API_H\n#define API_H\nint calculate(int value);\nint load_item(int value);\n" +
            (if (occupied) "int compute(int value);\n" else "") + "#endif\n"
        val implementation = "#include \"api.h\"\nint calculate(int value) { return value * 2; }\n" +
            "int load_item(int value) { return value + 1; }\n" +
            (if (occupied) "int compute(int value) { return value; }\n" else "") +
            "int calculate_suffix(void) { return 9; }\n/* calculate load_item stay in comments */\n"
        val main = "#include \"api.h\"\nconst char *description = \"calculate load_item\";\n" +
            "int main(void) { return calculate(21) + load_item(1) == 44 ? 0 : 1; }\n"
        val tools = Path.of(requireNotNull(System.getenv("REFACTORKIT_C_QUALIFICATION_TOOLCHAIN")))
        val dialect = if (case == "matrix: two C11 API symbols") "c11" else "c17"
        val database = JsonArray(listOf("api.c", "main.c").map { file -> buildJsonObject {
            put("directory", root.toString()); put("file", file)
            put("arguments", JsonArray(listOf(tools.resolve("usr/bin/clang-22").toString(),
                "-std=$dialect", "-I", ".", "-c", file).map(::JsonPrimitive)))
        } }).toString()
        original = mapOf("api.h" to header, "api.c" to implementation, "main.c" to main,
            "compile_commands.json" to database)
        original.forEach { (name, content) -> Files.writeString(root.resolve(name), content) }
        expected = mapOf(
            "api.h" to "#ifndef API_H\n#define API_H\nint compute(int value);\nint read_item(int value);\n#endif\n",
            "api.c" to "#include \"api.h\"\nint compute(int value) { return value * 2; }\n" +
                "int read_item(int value) { return value + 1; }\n" +
                "int calculate_suffix(void) { return 9; }\n/* calculate load_item stay in comments */\n",
            "main.c" to "#include \"api.h\"\nconst char *description = \"calculate load_item\";\n" +
                "int main(void) { return compute(21) + read_item(1) == 44 ? 0 : 1; }\n",
            "compile_commands.json" to database,
        )
        val mapping = when (case) {
            "matrix: two C11 API symbols", "matrix: two C17 API symbols", "matrix: occupied destination" ->
                mapOf("calculate" to "compute", "load_item" to "read_item")
            "matrix: duplicate destination" -> mapOf("calculate" to "compute", "load_item" to "compute")
            "matrix: overlapping source target" -> mapOf("calculate" to "load_item", "load_item" to "read_item")
            "matrix: unavailable second symbol" -> mapOf("calculate" to "compute", "absent" to "read_item")
            else -> error("Unadmitted multi-symbol prefix case: $case")
        }
        session.dispatch("initialize", buildJsonObject {
            put("rootUri", root.toUri().toString())
            put("capabilities", buildJsonObject { put("workspace", buildJsonObject {
                put("workspaceEdit", buildJsonObject { put("documentChanges", true) })
            }) })
        })
        try {
            preview = execute("refactorkit.c.preview", buildJsonObject {
                put("operation", "renamePrefix")
                put("clang", tools.resolve("usr/bin/clang-22").toString())
                put("clangd", tools.resolve("usr/bin/clangd").toString())
                put("clangFormat", tools.resolve("usr/bin/clang-format").toString())
                put("arguments", buildJsonObject { mapping.forEach { (old, new) -> put("rename.$old", new) } })
            })
        } catch (rejected: JsonRpcException) {
            refusal = rejected
            return
        }
        // Inspect read-only preview before invoking the separately requested managed Apply.
        original.forEach { (name, content) -> assertEquals(content, Files.readString(root.resolve(name))) }
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
        if (case !in setOf("matrix: two C11 API symbols", "matrix: two C17 API symbols")) return
        val plan = requireNotNull(preview)
        assertEquals(setOf("api.h", "api.c", "main.c"),
            (plan.getValue("affectedFiles") as JsonArray).map { it.jsonPrimitive.content }.toSet())
        applied = execute("refactorkit.applyPlan", buildJsonObject { put("planId", plan.getValue("planId")) })
        expected.forEach { (name, content) -> assertEquals(content, Files.readString(root.resolve(name)), name) }
        assertSingleJournal("APPLIED")
        rolledBack = execute("refactorkit.rollback", buildJsonObject {
            put("transactionId", requireNotNull(applied).getValue("transactionId"))
        })
    }

    fun assertOutcome(outcome: String) {
        if (outcome == "applied and rolled back") {
            assertNull(refusal)
            val plan = assertNotNull(preview)
            assertEquals("PREVIEW", plan.getValue("status").jsonPrimitive.content)
            assertEquals("renameCSymbolPrefix", plan.getValue("operation").jsonPrimitive.content)
            assertEquals("COMPILER_PROVEN", plan.getValue("evidence").jsonPrimitive.content)
            assertFalse("changes" in plan || "documentChanges" in plan, "Preview remains an engine-managed plan")
            assertTrue((plan.getValue("warnings") as JsonArray).any {
                it.jsonPrimitive.content.contains("external ABI consumers")
            }, "Local consumer evidence must retain its explicit external ABI boundary")
            assertTrue(assertNotNull(applied).getValue("transactionId").jsonPrimitive.content.isNotBlank())
            assertEquals("rolledBack", assertNotNull(rolledBack).getValue("status").jsonPrimitive.content)
        } else {
            assertEquals("compiler refusal", outcome)
            val rejected = assertNotNull(refusal, "Unexpected accepted collision/unavailable family: $preview")
            assertEquals(JsonRpcErrorCodes.PLAN_REFUSED, rejected.code)
            val expectedCodes = when (selectedCase) {
                "matrix: duplicate destination", "matrix: overlapping source target" -> setOf("c.renamePrefix.nonInvertibleMapping")
                "matrix: occupied destination" -> setOf("c.renamePrefix.compilerDiagnostics", "c.renamePrefix.refused")
                else -> setOf("c.renamePrefix.refused")
            }
            assertTrue(rejected.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content in expectedCodes,
                "Unexpected refusal: ${rejected.message}; ${rejected.data}")
            assertNull(preview); assertNull(applied); assertNull(rolledBack)
        }
    }

    fun assertRestored(outcome: String) {
        original.forEach { (name, content) -> assertEquals(content, Files.readString(root.resolve(name)), name) }
        if (outcome == "applied and rolled back") {
            assertSingleJournal("ROLLED_BACK")
            val replay = try {
                execute("refactorkit.applyPlan", buildJsonObject { put("planId", requireNotNull(preview).getValue("planId")) })
                null
            } catch (rejected: JsonRpcException) { rejected }
            assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, assertNotNull(replay).code)
        } else assertFalse(Files.exists(root.resolve(".refactorkit/transactions")), "Refusal must precede WAL")
    }

    private fun assertSingleJournal(state: String) {
        val directory = root.resolve(".refactorkit/transactions")
        val transactionId = requireNotNull(applied).getValue("transactionId").jsonPrimitive.content
        val records = Files.list(directory).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".json") }.toList()
        }
        assertEquals(listOf(directory.resolve("$transactionId.json")), records,
            "Both API families must share one transaction")
        val record = Json.parseToJsonElement(Files.readString(records.single())).jsonObject
        assertEquals(state, record.getValue("state").jsonPrimitive.content)
        assertEquals("renameCSymbolPrefix", record.getValue("operation").jsonPrimitive.content)
        val transaction = record.getValue("transaction").jsonObject
        assertEquals(transactionId, transaction.getValue("id").jsonPrimitive.content)
        assertEquals(requireNotNull(preview).getValue("planId"), transaction.getValue("planId"))
        assertNotNull(transaction["approval"], "Managed Apply must retain its explicit approval record")
    }

    private fun execute(command: String, arguments: JsonObject): JsonObject =
        session.dispatch("workspace/executeCommand", buildJsonObject {
            put("command", command); put("arguments", JsonArray(listOf(arguments)))
        }).jsonObject
}
