package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.lsp.LspSession
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LspWorkspaceInitializationSteps {
    private lateinit var fixture: Path
    private lateinit var parameters: JsonObject
    private lateinit var savedSources: Map<Path, ByteArray>
    private lateinit var visibleSources: Set<Path>

    @Given("an LSP initialization fixture requests {string} root URI and {string} workspace folders")
    fun prepare(root: String, folders: String) {
        fixture = Files.createTempDirectory("rk-lsp-workspace-initialize-")
        val explicit = Files.createDirectories(fixture.resolve("explicit"))
        val fallback = Files.createDirectories(fixture.resolve("folder"))
        val explicitSource = writeSource(explicit, "ExplicitChoice")
        val fallbackSource = writeSource(fallback, "FolderChoice")
        savedSources = listOf(explicitSource, fallbackSource).associateWith(Files::readAllBytes)
        parameters = buildJsonObject {
            put("processId", JsonNull)
            put("capabilities", buildJsonObject {})
            when (root) {
                "file" -> put("rootUri", explicit.toUri().toString())
                "null" -> put("rootUri", JsonNull)
                "omitted" -> Unit
                else -> error("Unexpected root URI fixture $root")
            }
            when (folders) {
                "present" -> put("workspaceFolders", buildJsonArray {
                    add(buildJsonObject {
                        put("uri", fallback.toUri().toString())
                        put("name", "folder")
                    })
                })
                "absent" -> Unit
                else -> error("Unexpected workspace folder fixture $folders")
            }
        }
    }

    @When("the client initializes that LSP session and asks for the saved document symbols")
    fun initializeAndReadSymbols() {
        val session = LspSession()
        val result = session.dispatch("initialize", parameters).jsonObject
        assertTrue(result.getValue("capabilities").jsonObject.isNotEmpty())
        visibleSources = savedSources.keys.filterTo(linkedSetOf()) { source ->
            val symbols: JsonElement = session.dispatch("textDocument/documentSymbol", buildJsonObject {
                put("textDocument", buildJsonObject { put("uri", source.toUri().toString()) })
            })
            if (symbols == JsonNull) {
                false
            } else {
                assertTrue(symbols.jsonArray.any {
                    it.jsonObject.getValue("name").jsonPrimitive.content == source.fileName.toString().removeSuffix(".java")
                }, symbols.toString())
                true
            }
        }
    }

    @Then("the LSP initialization exposes {string} and preserves both saved sources")
    fun checkWorkspace(selected: String) {
        assertEquals(
            savedSources.keys.filterTo(linkedSetOf()) { it.startsWith(fixture.resolve(selected)) },
            visibleSources,
        )
        savedSources.forEach { (source, bytes) -> assertContentEquals(bytes, Files.readAllBytes(source)) }
    }

    private fun writeSource(root: Path, typeName: String): Path {
        val sourceDirectory = Files.createDirectories(root.resolve("src/main/java"))
        return Files.writeString(sourceDirectory.resolve("$typeName.java"), "public class $typeName {}\n")
    }

    @After
    fun cleanFixture() {
        if (::fixture.isInitialized) {
            fixture.toFile().deleteRecursively()
        }
    }
}
