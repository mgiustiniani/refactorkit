package org.refactorkit.cli.navigation

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import org.refactorkit.lsp.LspSession
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class JavaLspImplementationSteps {
    private lateinit var root: Path
    private lateinit var session: LspSession
    private lateinit var initializeResult: JsonObject
    private lateinit var savedImage: Map<Path, String>
    private lateinit var overlayPath: Path
    private lateinit var overlayContent: String
    private lateinit var typeImplementations: JsonArray
    private lateinit var methodImplementations: JsonArray

    @Given("a Java LSP session has a saved handler interface and an abstract base")
    fun createSavedHandlerWorkspace() {
        root = Files.createTempDirectory("rk-lsp-implementations-")
        write("src/main/java/fixture/Handler.java", """
            package fixture;
            public interface Handler {
                String handle(String value);
            }
        """.trimIndent() + "\n")
        write("src/main/java/fixture/AbstractHandler.java", """
            package fixture;
            public abstract class AbstractHandler implements Handler {
                @Override public abstract String handle(String value);
            }
        """.trimIndent() + "\n")
        write("src/main/java/fixture/Unrelated.java", """
            package fixture;
            public final class Unrelated {
                String handle(String value) { return value; }
            }
        """.trimIndent() + "\n")
        savedImage = workspaceImage()
        session = LspSession()
        initializeResult = session.dispatch("initialize", buildJsonObject {
            put("rootUri", root.toUri().toString())
            put("capabilities", buildJsonObject { })
        }) as JsonObject
    }

    @When("an unsaved concrete handler is opened and type and method implementations are requested")
    fun openDirtyConcreteHandlerAndQueryImplementations() {
        overlayPath = Path.of("src/main/java/fixture/ConcreteHandler.java")
        overlayContent = """
            package fixture;
            public final class ConcreteHandler extends AbstractHandler {
                @Override public String handle(String value) { return value; }
            }
        """.trimIndent() + "\n"
        session.dispatch("textDocument/didOpen", buildJsonObject {
            put("textDocument", buildJsonObject {
                put("uri", root.resolve(overlayPath).toUri().toString())
                put("languageId", "java")
                put("version", 1)
                put("text", overlayContent)
            })
        })
        val handlerPath = Path.of("src/main/java/fixture/Handler.java")
        val handlerContent = Files.readString(root.resolve(handlerPath))
        val typeLine = handlerContent.lines().indexOfFirst { "interface Handler" in it }
        val methodLine = handlerContent.lines().indexOfFirst { "String handle(" in it }
        typeImplementations = queryImplementations(
            handlerPath, typeLine, handlerContent.lines()[typeLine].indexOf("Handler"),
        )
        methodImplementations = queryImplementations(
            handlerPath, methodLine, handlerContent.lines()[methodLine].indexOf("handle"),
        )
    }

    @Then("implementation responses identify the dirty concrete type and method locations")
    fun assertImplementationLocations() {
        val expectedUri = root.resolve(overlayPath).toUri().toString()
        assertLocation(typeImplementations, expectedUri, "ConcreteHandler")
        assertLocation(methodImplementations, expectedUri, "handle")
    }

    @Then("the LSP advertises Java implementation lookup and saved workspace files remain unchanged")
    fun assertCapabilityAndReadOnly() {
        val provider = initializeResult.getValue("capabilities").jsonObject
            .getValue("implementationProvider").jsonObject
        val selector = provider.getValue("documentSelector").jsonArray.single().jsonObject
        assertEquals("java", selector.getValue("language").jsonPrimitive.content)
        assertEquals("file", selector.getValue("scheme").jsonPrimitive.content)
        assertEquals(savedImage, workspaceImage())
        assertFalse(Files.exists(root.resolve(overlayPath)))
        assertFalse(Files.exists(root.resolve(".refactorkit")))
    }

    @After
    fun cleanup() {
        if (::session.isInitialized) {
            session.dispatch("shutdown", null)
            session.dispatch("exit", null)
        }
        if (::root.isInitialized) root.toFile().deleteRecursively()
    }

    private fun queryImplementations(path: Path, line: Int, character: Int): JsonArray =
        session.dispatch("textDocument/implementation", buildJsonObject {
            put("textDocument", buildJsonObject { put("uri", root.resolve(path).toUri().toString()) })
            put("position", buildJsonObject {
                put("line", line)
                put("character", character)
            })
        }) as JsonArray

    private fun assertLocation(result: JsonArray, uri: String, expectedName: String) {
        assertEquals(1, result.size, result.toString())
        val location = result.single().jsonObject
        assertEquals(uri, location.getValue("uri").jsonPrimitive.content)
        val range = location.getValue("range").jsonObject
        val start = range.getValue("start").jsonObject
        val end = range.getValue("end").jsonObject
        val line = start.getValue("line").jsonPrimitive.int
        val text = overlayContent.lines()[line]
        assertEquals(
            expectedName,
            text.substring(start.getValue("character").jsonPrimitive.int, end.getValue("character").jsonPrimitive.int),
        )
    }

    private fun write(relative: String, content: String) {
        val path = root.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }

    private fun workspaceImage(): Map<Path, String> = Files.walk(root).use { paths ->
        paths.filter(Files::isRegularFile).toList().associate { path ->
            root.relativize(path) to MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
