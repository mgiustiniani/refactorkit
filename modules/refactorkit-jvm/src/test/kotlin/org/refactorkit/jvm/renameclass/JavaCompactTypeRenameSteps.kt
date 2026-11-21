package org.refactorkit.jvm.renameclass

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcException
import org.refactorkit.daemon.DaemonSession
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JavaCompactTypeRenameSteps {
    private lateinit var fixture: Path
    private lateinit var baseline: Map<Path, String>
    private var preview: JsonObject? = null
    private var refusal: JsonRpcException? = null

    @Given("a Maven project has a compact top-level type and a rename destination that is {string}")
    fun prepare(destination: String) {
        fixture = Files.createTempDirectory("rk-java-compact-rename-")
        val root = fixture.resolve("workspace")
        write(root, "pom.xml", """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId>
              <artifactId>compact</artifactId><version>1</version>
              <properties><maven.compiler.release>17</maven.compiler.release></properties>
            </project>
        """.trimIndent() + "\n")
        write(root, MAIN, "package fixture; public final class Main { public Main() {} }\n")
        write(root, CLIENT, "package fixture;\npublic final class Client { Main value = new Main(); }\n")
        if (destination == "occupied") {
            write(root, "src/main/java/fixture/Existing.java", "package fixture; class Renamed {}\n")
        }
        baseline = savedFiles(root)
    }

    @When("an IDE previews renameClass for the compact declaration")
    fun preview() {
        val root = fixture.resolve("workspace")
        DaemonSession().use { daemon ->
            val opened = daemon.dispatch("project.open", buildJsonObject {
                put("root", root.toString())
                put("mavenRepository", Files.createDirectory(fixture.resolve("repository")).toString())
            }).jsonObject
            val snapshot = opened.getValue("snapshotHash")
            val context = daemon.dispatch("project.context", buildJsonObject {
                put("expectedSnapshotHash", snapshot)
                put("sdkHome", System.getProperty("java.home"))
            }).jsonObject
            assertEquals("available", context.getValue("status").jsonPrimitive.content)
            try {
                preview = daemon.dispatch("refactor.preview", buildJsonObject {
                    put("operation", "renameClass")
                    put("symbol", "fixture.Main")
                    put("expectedSnapshotHash", snapshot)
                    put("sourceAuthority", buildJsonObject { put("kind", "saved-snapshot") })
                    put("arguments", buildJsonObject { put("newName", "Renamed") })
                }).jsonObject
            } catch (failure: JsonRpcException) {
                refusal = failure
            }
        }
    }

    @Then("the saved source remains intact and the daemon reports {string}")
    fun verify(outcome: String) {
        val root = fixture.resolve("workspace")
        assertEquals(baseline, savedFiles(root))
        assertFalse(Files.exists(root.resolve(".refactorkit")))
        if (outcome == "an existing target type refusal") {
            val failure = assertNotNull(refusal)
            assertEquals(JsonRpcErrorCodes.PLAN_REFUSED, failure.code)
            assertEquals("Rename target already exists: fixture.Renamed ($RENAMED)", failure.message)
            assertEquals(null, preview)
            return
        }
        assertEquals("a binding-backed rename preview", outcome)
        val plan = assertNotNull(preview, refusal?.message)
        assertEquals("PREVIEW", plan.getValue("status").jsonPrimitive.content)
        assertEquals("JDT_BINDING", plan.getValue("evidence").jsonPrimitive.content)
        val edits = plan.getValue("structuredDiff").jsonArray.map { it.jsonObject }
        assertEquals(listOf(
            buildJsonObject { put("type", "modifyFile"); put("path", MAIN) },
            buildJsonObject { put("type", "modifyFile"); put("path", CLIENT) },
            buildJsonObject { put("type", "renameFile"); put("path", MAIN); put("newPath", RENAMED) },
        ), edits)
        assertEquals(listOf(
            "+public final class Client { Renamed value = new Renamed(); }",
            "+package fixture; public final class Renamed { public Renamed() {} }",
        ), plan.getValue("renderedDiff").jsonPrimitive.content.lineSequence()
            .filter { it.startsWith('+') && !it.startsWith("+++") }.toList())
    }

    @After
    fun cleanUp() {
        if (::fixture.isInitialized) {
            Files.walk(fixture).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private fun write(root: Path, relative: String, content: String) {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    private fun savedFiles(root: Path): Map<Path, String> = Files.walk(root).use { paths ->
        paths.filter(Files::isRegularFile).toList().associate { root.relativize(it) to Files.readString(it) }
    }

    private companion object {
        const val MAIN = "src/main/java/fixture/Main.java"
        const val CLIENT = "src/main/java/fixture/Client.java"
        const val RENAMED = "src/main/java/fixture/Renamed.java"
    }
}
