package org.refactorkit.cli.previewcommand

import kotlinx.serialization.json.*
import org.refactorkit.cli.RefactorKitCli
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class CFormattingRangesCliFixture : AutoCloseable {
    private val root = Files.createTempDirectory("rk-c-cli-line-ranges-")
    private val tools = Path.of(requireNotNull(System.getenv("REFACTORKIT_C_QUALIFICATION_TOOLCHAIN"))).resolve("usr/bin")
    private val source = root.resolve("main.c")
    private val database = root.resolve("compile_commands.json")
    private val original = "int first(void){return 1;}\nint middle(void){return 2;}\nint last(void){return 3;}\n"
    private val compileCommands = buildJsonArray {
        add(buildJsonObject {
            put("directory", root.toString())
            put("file", "main.c")
            put("arguments", JsonArray(listOf(tools.resolve("clang-22").toString(), "-std=c17", "-c", "main.c").map(::JsonPrimitive)))
        })
    }.toString()
    private var code = -1
    private var transcript = ""

    init {
        Files.writeString(source, original)
        Files.writeString(database, compileCommands)
    }

    fun preview(selection: String) {
        val options = when (selection) {
            "disjoint" -> listOf("--ranges", "0:0,2:2")
            "single range" -> listOf("--start-line", "0", "--end-line", "0")
            "overlapping" -> listOf("--ranges", "0:1,1:2")
            "both forms" -> listOf("--ranges", "0:0,2:2", "--start-line", "0", "--end-line", "0")
            "missing list value" -> listOf("--ranges")
            else -> error("Unknown CLI formatter selection: $selection")
        }
        val command = listOf(
            "c", "format", root.toString(), "--file", "main.c", "--preview",
            "--clang", tools.resolve("clang-22").toString(), "--clangd", tools.resolve("clangd").toString(),
            "--clang-format", tools.resolve("clang-format").toString(),
        ) + options
        synchronized(RefactorKitCli::class.java) {
            val oldOut = System.out
            val oldErr = System.err
            val bytes = ByteArrayOutputStream()
            PrintStream(bytes, true, Charsets.UTF_8).use { stream ->
                try {
                    System.setOut(stream)
                    System.setErr(stream)
                    code = RefactorKitCli().run(command)
                    transcript = bytes.toString(Charsets.UTF_8)
                } finally {
                    System.setOut(oldOut)
                    System.setErr(oldErr)
                }
            }
        }
    }

    fun assertOutcome(outcome: String) {
        when (outcome) {
            "only the selected lines", "only the first line" -> {
                assertEquals(0, code, transcript)
                val plan = Json.parseToJsonElement(transcript).jsonObject
                assertEquals("PREVIEW", plan.getValue("status").jsonPrimitive.content)
                assertTrue(plan.getValue("warnings").jsonArray.any { "idempotent=true" in it.jsonPrimitive.content })
                val diff = plan.getValue("renderedDiff").jsonPrimitive.content
                assertTrue("+int first(void) { return 1; }" in diff, diff)
                diff.lineSequence().firstOrNull { it.startsWith("+int middle(void)") }?.let {
                    assertEquals("+int middle(void){return 2;}", it, diff)
                }
                assertEquals(outcome == "only the selected lines", "+int last(void) { return 3; }" in diff, diff)
            }
            "invalid range refused" -> {
                assertEquals(1, code, transcript)
                assertTrue("[clang.formatInvalidRange]" in transcript, transcript)
            }
            "malformed option refused" -> {
                assertEquals(2, code, transcript)
                assertTrue("requires a value" in transcript, transcript)
            }
            else -> error("Unknown CLI formatting outcome: $outcome")
        }
    }

    fun assertIntact() {
        assertEquals(original, Files.readString(source))
        assertEquals(compileCommands, Files.readString(database))
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
    }

    override fun close() {
        check(root.toFile().deleteRecursively())
    }
}
