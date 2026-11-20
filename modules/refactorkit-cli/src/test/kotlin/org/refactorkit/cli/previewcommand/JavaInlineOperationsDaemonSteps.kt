package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.*
import org.refactorkit.daemon.DaemonSession
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class JavaInlineOperationsDaemonSteps {
    private lateinit var root: Path
    private lateinit var sourcePath: Path
    private lateinit var original: String
    private lateinit var source: String
    private lateinit var operation: String
    private lateinit var workspaceSnapshotHash: String
    private lateinit var contextAuthority: JsonObject
    private var session: DaemonSession? = null
    private lateinit var preview: JsonObject
    private lateinit var applied: JsonObject
    private lateinit var rolledBack: JsonObject

    @Given("a clean Maven fixture with a local literal and a static final integer constant")
    fun createFixture() {
        root = Files.createTempDirectory("rk-java-inline-daemon-")
        Files.writeString(root.resolve("pom.xml"), """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>fixture</groupId><artifactId>inline-daemon</artifactId><version>1</version>
              <properties><maven.compiler.release>21</maven.compiler.release></properties>
            </project>
        """.trimIndent() + "\n")
        sourcePath = Path.of("src/main/java/fixture/Calculator.java")
        original = """
            package fixture;
            public class Calculator {
                private static final int OFFSET = 7;
                public int add() {
                    int base = 7;
                    return base + base;
                }
                public int calculate(int input) { return input + OFFSET; }
            }
        """.trimIndent() + "\n"
        val file = root.resolve(sourcePath)
        Files.createDirectories(file.parent)
        Files.writeString(file, original)
        source = original
        val sdkHome = Path.of(System.getProperty("java.home")).toRealPath().toString()
        session = DaemonSession()
        val opened = session!!.dispatch("project.open", buildJsonObject { put("root", root.toString()) }).jsonObject
        workspaceSnapshotHash = opened.getValue("snapshotHash").jsonPrimitive.content
        val context = session!!.dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", workspaceSnapshotHash)
            put("sdkHome", sdkHome)
        }).jsonObject
        assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
        contextAuthority = buildJsonObject {
            put("sdkHome", sdkHome)
            put("expectedContextHash", context.getValue("contextHash").jsonPrimitive.content)
        }
    }

    @When("the daemon previews {word} for the selected {word} name {string}")
    fun preview(operation: String, selectedKind: String, name: String) {
        this.operation = operation
        assertTrue(operation in setOf("inlineVariable", "inlineConstant"))
        val capabilities = session!!.dispatch("server.capabilities", buildJsonObject { }).jsonObject
        val javaAdapter = capabilities.getValue("languageKernel").jsonObject.getValue("adapters").jsonArray
            .map { it.jsonObject }.single { it.getValue("languageId").jsonPrimitive.content == "java" }
        val methodCapability = javaAdapter.getValue("capabilities").jsonArray.map { it.jsonObject }
            .single { it.getValue("operation").jsonPrimitive.content == operation }
        assertEquals("stable", methodCapability.getValue("stability").jsonPrimitive.content)
        assertEquals("managed-stable", methodCapability.getValue("mutationAuthority").jsonPrimitive.content)
        val javaContextRefactoring = capabilities.getValue("javaContextRefactoring").jsonObject
        assertTrue(javaContextRefactoring.getValue("operations").jsonArray.any { it.jsonPrimitive.content == operation })
        val operationCapability = javaContextRefactoring.getValue("operationCapabilities").jsonArray
            .map { it.jsonObject }.single { it.getValue("operation").jsonPrimitive.content == operation }
        assertEquals("stable", operationCapability.getValue("stability").jsonPrimitive.content)
        assertEquals("managed-stable", operationCapability.getValue("mutationAuthority").jsonPrimitive.content)
        assertTrue(selectedKind == if (operation == "inlineVariable") "local" else "constant")

        preview = session!!.dispatch("refactor.preview", buildJsonObject {
            put("operation", operation)
            put("languageId", "java")
            put("expectedSnapshotHash", workspaceSnapshotHash)
            put("javaContext", contextAuthority)
            put("arguments", buildJsonObject {
                put("file", sourcePath.toString())
                put("name", name)
            })
        }).jsonObject
        assertEquals("PREVIEW", preview.getValue("status").jsonPrimitive.content, preview.toString())
        assertEquals(operation, preview.getValue("operation").jsonPrimitive.content)
        assertEquals(original, Files.readString(root.resolve(sourcePath)), "Preview must leave source bytes unchanged")
    }

    @Then("{word} is advertised, applied, and rolled back through the daemon")
    fun applyAndRollback(operation: String) {
        assertEquals(this.operation, operation)
        applied = session!!.dispatch("refactor.apply", buildJsonObject {
            put("planId", preview.getValue("planId").jsonPrimitive.content)
            put("expectedSnapshotHash", workspaceSnapshotHash)
            put("javaContext", contextAuthority)
        }).jsonObject
        assertEquals("applied", applied.getValue("status").jsonPrimitive.content, applied.toString())
        source = when (operation) {
            "inlineVariable" -> original.replace("        int base = 7;\n", "").replace("return base + base;", "return 7 + 7;")
            "inlineConstant" -> original.replace("    private static final int OFFSET = 7;\n", "")
                .replace("return input + OFFSET;", "return input + 7;")
            else -> error("Unexpected operation: $operation")
        }
        assertEquals(source, Files.readString(root.resolve(sourcePath)))
        assertTrue(applied.getValue("diagnostics").jsonArray.none {
            it.jsonObject.getValue("severity").jsonPrimitive.content.equals("ERROR", ignoreCase = true)
        })

        rolledBack = session!!.dispatch("patch.rollback", buildJsonObject {
            put("transactionId", applied.getValue("transactionId").jsonPrimitive.content)
            put("expectedSnapshotHash", applied.getValue("snapshotHash").jsonPrimitive.content)
            put("javaContext", contextAuthority)
        }).jsonObject
        assertEquals("rolledBack", rolledBack.getValue("status").jsonPrimitive.content, rolledBack.toString())
        assertEquals(original, Files.readString(root.resolve(sourcePath)), "Rollback must restore exact original source bytes")
    }

    @After
    fun close() {
        session?.close()
        if (::root.isInitialized) Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
