package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.And
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.daemon.DaemonSession
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaMovePackageTextSteps {
    private var workspace: MovePackageTextWorkspace? = null

    @Given("a Maven workspace has package references and unrelated occurrences of the same text")
    fun createWorkspace() {
        workspace = MovePackageTextWorkspace()
    }

    @When("the daemon previews and applies its package migration")
    fun migrate() = requireNotNull(workspace).migrate()

    @Then("the Java package move changes the owned code and only exact package or source-path literals")
    fun verifyMove() = requireNotNull(workspace).verifyMove()

    @And("the package migration rollback restores all original paths and bytes")
    fun verifyRollback() = requireNotNull(workspace).verifyRollback()

    @After
    fun close() {
        workspace?.close()
    }
}

private class MovePackageTextWorkspace : AutoCloseable {
    private val scratch = Files.createTempDirectory("rk-move-package-text-")
    private val root = Files.createDirectory(scratch.resolve("project"))
    private val repository = Files.createDirectory(scratch.resolve("maven-repository"))
    private val sdkHome = System.getProperty("java.home")
    private val session = DaemonSession()
    private val originalLibrary = "src/main/java/pkg/Library.java"
    private val movedLibrary = "src/main/java/renamed/pkg/Library.java"
    private val consumerFile = "src/main/java/consumer/Consumer.java"
    private val journal = root.resolve(".refactorkit/transactions")
    private val pom = """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>fixture</groupId><artifactId>package-text</artifactId><version>1</version>
          <properties><maven.compiler.release>21</maven.compiler.release></properties>
        </project>
    """.trimIndent() + "\n"
    private val original = mapOf(
        "pom.xml" to pom,
        originalLibrary to library("pkg"),
        consumerFile to consumer("pkg", originalLibrary),
    )
    private lateinit var preview: JsonObject
    private lateinit var applied: JsonObject
    private lateinit var originalContext: JsonObject

    init {
        original.forEach { (path, content) ->
            val destination = root.resolve(path)
            Files.createDirectories(destination.parent)
            Files.writeString(destination, content)
        }
    }

    fun migrate() {
        val opened = rpc("project.open") {
            put("root", root.toString())
            put("mavenRepository", repository.toString())
        }
        val snapshot = opened.string("snapshotHash")
        originalContext = contextFor(snapshot)
        preview = rpc("refactor.preview") {
            put("operation", "movePackage")
            put("languageId", "java")
            put("expectedSnapshotHash", snapshot)
            put("javaContext", originalContext)
            put("arguments", buildJsonObject {
                put("fromPackage", "pkg")
                put("toPackage", "renamed.pkg")
            })
        }
        assertEquals("PREVIEW", preview.string("status"), preview.toString())
        assertTrue(preview.getValue("diagnosticsAfterPreview").jsonArray.none {
            it.jsonObject.string("severity").equals("error", ignoreCase = true)
        })
        assertEquals(original, projectImages())
        assertFalse(Files.exists(journal))
        applied = rpc("refactor.apply") {
            put("planId", preview.string("planId"))
            put("expectedSnapshotHash", snapshot)
            put("javaContext", originalContext)
        }
    }

    fun verifyMove() {
        assertEquals("applied", applied.string("status"))
        val expectedConsumer = consumer("renamed.pkg", movedLibrary)
        // Inspect the whole image, including the unaffected newlines and quoted text.
        assertEquals(expectedConsumer, Files.readString(root.resolve(consumerFile)))
        assertFalse(preview.string("renderedDiff").contains("https://docs.example.test/renamed.pkg.Library"))
        val expected = original - originalLibrary + mapOf(
            movedLibrary to library("renamed.pkg"), consumerFile to expectedConsumer,
        )
        assertEquals(expected, projectImages())
        assertEquals(1L, Files.list(journal).use { files -> files.filter { it.toString().endsWith(".json") }.count() })
    }

    fun verifyRollback() {
        val snapshot = applied.string("snapshotHash")
        val rolled = rpc("patch.rollback") {
            put("transactionId", applied.string("transactionId"))
            put("expectedSnapshotHash", snapshot)
            put("javaContext", originalContext)
        }
        assertEquals("rolledBack", rolled.string("status"))
        assertEquals(original, projectImages())
    }

    private fun contextFor(snapshot: String): JsonObject {
        val context = rpc("project.context") {
            put("expectedSnapshotHash", snapshot)
            put("sdkHome", sdkHome)
        }
        assertEquals("available", context.string("status"), context.toString())
        return buildJsonObject {
            put("sdkHome", sdkHome)
            put("expectedContextHash", context.string("contextHash"))
            put("contextSchemaVersion", context.string("schemaVersion"))
        }
    }

    private fun rpc(method: String, params: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        session.dispatch(method, buildJsonObject(params)).jsonObject

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun projectImages(): Map<String, String> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) && !root.relativize(it).startsWith(".refactorkit") }.toList()
            .associate { root.relativize(it).toString() to Files.readString(it) }
    }

    private fun library(pkg: String) = """
        package $pkg;
        public final class Library { public static int value() { return 7; } }
    """.trimIndent() + "\n"

    private fun consumer(pkg: String, movedPath: String): String = listOf(
        "package consumer;",
        "import $pkg.Library;",
        "import $pkg.*;",
        "public final class Consumer {",
        "    // pkg.Library and \"pkg\" and src/main/java/pkg/Library.java",
        "    /* pkg.Library and \"pkg\" and src/main/java/pkg/Library.java */",
        "    static final String URL = \"https://docs.example.test/pkg.Library\";",
        "    static final String CLASS_NAME = \"pkg.Library\";",
        "    static final String PATH_NOTE = \"see src/main/java/pkg/Library.java for details\";",
        "    static final String PACKAGE = \"$pkg\";",
        "    static final String SOURCE_PATH = \"$movedPath\";",
        "    static final String TEXT = \"\"\"",
        "        pkg.Library src/main/java/pkg/Library.java \"pkg\"",
        "        \"\"\";",
        "    public static int total() { return Library.value() + $pkg.Library.value(); }",
        "}",
        "",
    ).joinToString("\r\n")

    override fun close() {
        session.close()
        Files.walk(scratch).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}
