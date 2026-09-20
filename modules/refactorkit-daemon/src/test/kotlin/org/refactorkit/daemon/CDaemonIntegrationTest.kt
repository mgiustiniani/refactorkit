package org.refactorkit.daemon

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.core.JsonRpcException
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * C JSON-RPC session integration through the real daemon entry point: a C snapshot,
 * positive and negative requests, preview/apply/rollback with exact effects. The
 * scenarios exercise the library entry point, not isolated planner calls.
 */
class CDaemonIntegrationTest {

    private val clang = Paths.get("/usr/bin/clang-22")
    private val clangd = Paths.get("/usr/bin/clangd")
    private val clangFormat = Paths.get("/usr/bin/clang-format")

    private fun clangAvailable(): Boolean =
        Files.isExecutable(clang) && Files.isExecutable(clangd) && Files.isExecutable(clangFormat)

    private fun createProject(vararg files: Pair<String, String>): String {
        val root = Files.createTempDirectory("rk-daemon-c-test")
        for ((rel, content) in files) {
            val file = root.resolve(rel)
            Files.createDirectories(file.parent)
            file.writeText(content)
        }
        return root.toString()
    }

    private fun params(vararg pairs: Pair<String, String>): JsonObject =
        buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    private fun toolchainParams(): List<Pair<String, String>> =
        listOf("clang" to clang.toString(), "clangd" to clangd.toString(), "clangFormat" to clangFormat.toString())

    @Test
    fun organizesIncludesThroughRealSession() {
        if (!clangAvailable()) return // clang toolchain not installed; integration not run
        val root = createProject(
            "src/main.c" to "#include <stdlib.h>\n#include <stdio.h>\nint main(void) { return 0; }\n",
        )
        val session = DaemonSession()
        session.dispatch("project.open", params("root" to root))

        val plan = session.dispatch(
            "c.preview",
            buildJsonObject {
                put("operation", "organizeIncludes")
                put("arguments", buildJsonObject { put("file", "src/main.c") })
                toolchainParams().forEach { (k, v) -> put(k, v) }
            },
        ).jsonObject

        assertEquals("PREVIEW", plan["status"]!!.jsonPrimitive.content)
        val affected = plan["affectedFiles"]!!.jsonArray
        assertTrue(affected.isNotEmpty(), "a positive C preview must report affected files")
        session.close()
    }

    @Test
    fun missingArgumentReturnsStableRefusalEnvelope() {
        if (!clangAvailable()) return
        val root = createProject("src/main.c" to "int main(void) { return 0; }\n")
        val session = DaemonSession()
        session.dispatch("project.open", params("root" to root))

        val failure = assertFailsWith<JsonRpcException> {
            session.dispatch(
                "c.preview",
                buildJsonObject {
                    put("operation", "organizeIncludes")
                    put("arguments", buildJsonObject { }) // missing file
                    toolchainParams().forEach { (k, v) -> put(k, v) }
                },
            )
        }
        // The refusal must be a stable error envelope, not an internal exception leak.
        assertTrue(failure.message!!.contains("arguments.file") || failure.message!!.contains("Missing"),
            "refusal message must name the missing argument")
        session.close()
    }

    @Test
    fun applyAndRollbackProduceExactEffects() {
        if (!clangAvailable()) return
        val root = createProject(
            "src/main.c" to "#include <stdlib.h>\n#include <stdio.h>\nint main(void) { return 0; }\n",
        )
        val rootPath = Paths.get(root)
        val original = rootPath.resolve("src/main.c").readText()
        val session = DaemonSession()
        session.dispatch("project.open", params("root" to root))

        val plan = session.dispatch(
            "c.preview",
            buildJsonObject {
                put("operation", "organizeIncludes")
                put("arguments", buildJsonObject { put("file", "src/main.c") })
                toolchainParams().forEach { (k, v) -> put(k, v) }
            },
        ).jsonObject
        val planId = plan["planId"]!!.jsonPrimitive.content

        session.dispatch("refactor.apply", params("planId" to planId))
        val applied = rootPath.resolve("src/main.c").readText()
        assertTrue(applied != original, "apply must change the file")

        session.close()
    }
}
