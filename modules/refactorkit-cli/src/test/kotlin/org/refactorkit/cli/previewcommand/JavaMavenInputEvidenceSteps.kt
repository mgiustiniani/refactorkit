package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.When
import io.cucumber.java.en.Then
import kotlinx.serialization.json.*
import org.refactorkit.core.ClasspathEvidence
import org.refactorkit.core.ClasspathEvidenceKind
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.*

class JavaMavenInputEvidenceSteps {
    private val fixture = MavenInputEvidenceFixture()

    @Given("an offline Maven editor context fixture uses {string}")
    fun capture(shape: String) = fixture.capture(shape)

    @When("the native daemon captures its Maven context before and after an authoritative POM edit")
    fun query() = fixture.query()

    @Then("all provider-captured Maven POMs have unique content-bound public build evidence")
    fun evidence() = fixture.verifyEvidence()

    @Then("changing a captured Maven POM invalidates its snapshot and context without running project code")
    fun intact() = fixture.verifyInputs()

    @After
    fun close() = fixture.close()
}

private class MavenInputEvidenceFixture : AutoCloseable {
    private lateinit var root: Path
    private lateinit var native: NativeJavaDaemonRpcSession
    private lateinit var repository: Path
    private lateinit var changed: Path
    private lateinit var before: Map<Path, String>
    private lateinit var context: JsonObject
    private lateinit var refreshed: JsonObject
    private lateinit var staleSnapshot: JsonObject
    private lateinit var staleContext: JsonObject
    private val expected = linkedMapOf<Path, ClasspathEvidenceKind>()
    private var changedImage = ""
    private val compiler = "<properties><maven.compiler.source>8</maven.compiler.source><maven.compiler.target>8</maven.compiler.target></properties>" +
        "<build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin></plugins></build>"

    fun capture(shape: String) {
        require(shape in setOf("inherited reactor parent", "aggregation-only reactor", "nested aggregator",
            "workspace relative parent", "repository parent", "imported BOM"))
        root = Files.createTempDirectory("rk-maven-input-evidence-")
        repository = root.resolve(".refactorkit/local-repository")
        Files.createDirectories(repository)
        val externalParent = when (shape) {
            "workspace relative parent" -> root.resolve("configuration/compiler-parent.xml")
            "repository parent" -> repository.resolve("fixture/compiler-parent/1/compiler-parent-1.pom")
            else -> null
        }
        externalParent?.let {
            write(it, pom("compiler-parent", "<packaging>pom</packaging>$compiler"))
            expected[it] = ClasspathEvidenceKind.EFFECTIVE_MODEL_INPUT
        }
        val bom = if (shape == "imported BOM") repository.resolve("fixture/platform/1/platform-1.pom") else null
        bom?.let {
            write(it, pom("platform", "<packaging>pom</packaging>"))
            expected[it] = ClasspathEvidenceKind.IMPORTED_BOM
        }
        val managedBom = if (bom == null) "" else """<dependencyManagement><dependencies><dependency>
            <groupId>fixture</groupId><artifactId>platform</artifactId><version>1</version><type>pom</type><scope>import</scope>
            </dependency></dependencies></dependencyManagement>"""
        val nested = shape == "nested aggregator"
        val inherited = shape in setOf("inherited reactor parent", "nested aggregator", "imported BOM")
        val modules = if (nested) "<module>branch</module>" else "<module>app</module><module>library</module>"
        declare("pom.xml", pom("reactor", "<packaging>pom</packaging>$compiler$managedBom<modules>$modules</modules>"))
        if (nested) declare("branch/pom.xml", child("branch", "reactor", "../pom.xml",
            "<packaging>pom</packaging><modules><module>app</module><module>library</module></modules>"))
        for (name in listOf("app", "library")) {
            val directory = if (nested) "branch/$name" else name
            val body = when {
                externalParent != null -> child(name, "compiler-parent",
                    if (shape == "repository parent") "" else "../configuration/compiler-parent.xml", "")
                inherited -> child(name, if (nested) "branch" else "reactor", "../pom.xml", "")
                else -> pom(name, compiler)
            }
            declare("$directory/pom.xml", body)
            write(root.resolve("$directory/src/main/java/$name/Value.java"), "package $name; public class Value {}\n")
        }
        changed = externalParent ?: bom ?: root.resolve("pom.xml")
        before = images()
        native = NativeJavaDaemonRpcSession(root)
    }

    fun query() {
        val snapshot = open().getValue("snapshotHash")
        context = context(snapshot)
        assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
        assertEquals("java-project-context-v2", context.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals(context, context(snapshot), "Identical inputs must retain exact context authority")
        changedImage = before.getValue(changed) + "\n<!-- caller changed authoritative build input -->\n"
        write(changed, changedImage)
        staleSnapshot = context(snapshot)
        val currentSnapshot = open().getValue("snapshotHash")
        assertNotEquals(snapshot, currentSnapshot, "Every selected Maven input must affect the snapshot")
        staleContext = context(currentSnapshot, context.getValue("contextHash"))
        refreshed = context(currentSnapshot)
        assertEquals("available", refreshed.getValue("status").jsonPrimitive.content, refreshed.toString())
        assertNotEquals(context.getValue("contextHash"), refreshed.getValue("contextHash"),
            "Every selected Maven input must affect context authority even for a semantics-neutral edit")
    }

    fun verifyEvidence() {
        for (response in listOf(context, refreshed)) {
            val rows = response.getValue("buildEvidence").jsonArray.map { it.jsonObject }
            assertEquals(rows.size, rows.map { it.getValue("path") to it.getValue("kind") }.distinct().size)
            for ((path, kind) in expected) {
                val publicPath = if (path.startsWith(root)) root.relativize(path).toString() else path.toString()
                val publicKind = kind.name.lowercase().replace('_', '-')
                val row = rows.singleOrNull { it.getValue("path").jsonPrimitive.content == publicPath &&
                    it.getValue("kind").jsonPrimitive.content == publicKind }
                assertNotNull(row, "Missing exact provider Maven input $publicPath/$publicKind in $response")
                val image = if (response === refreshed && path == changed) changedImage else before.getValue(path)
                // Recompute against the corresponding captured bytes, without changing the fixture.
                val capturePath = Files.createTempFile("rk-maven-input-fingerprint-", ".pom")
                try {
                    Files.writeString(capturePath, image)
                    assertEquals(ClasspathEvidence.capture(root, capturePath, kind).fingerprint,
                        row.getValue("fingerprint").jsonPrimitive.content)
                } finally { Files.deleteIfExists(capturePath) }
            }
        }
    }

    fun verifyInputs() {
        assertEquals("refused", staleSnapshot.getValue("status").jsonPrimitive.content, staleSnapshot.toString())
        assertEquals("java.context.snapshotStale", staleSnapshot.getValue("refusal").jsonObject.getValue("code").jsonPrimitive.content)
        assertEquals("refused", staleContext.getValue("status").jsonPrimitive.content, staleContext.toString())
        assertEquals("java.context.contextStale", staleContext.getValue("refusal").jsonObject.getValue("code").jsonPrimitive.content)
        assertEquals(before + (changed to changedImage), images(), "Only the deliberate caller POM edit is allowed")
        Files.walk(root).use { paths ->
            assertFalse(paths.anyMatch { it.fileName.toString().let { n -> n.endsWith(".class") || n.endsWith(".jar") } },
                "Context acquisition must not compile or execute project lifecycle code")
        }
    }

    private fun open() = native.dispatch("project.open", buildJsonObject {
        put("root", root.toString()); put("mavenRepository", repository.toString()); put("resolveDependencies", false)
    })

    private fun context(snapshot: JsonElement, hash: JsonElement? = null) = native.dispatch("project.context", buildJsonObject {
        put("expectedSnapshotHash", snapshot); put("sdkHome", System.getProperty("java.home"))
        hash?.let { put("expectedContextHash", it) }
    })

    private fun declare(path: String, text: String) {
        val absolute = root.resolve(path)
        write(absolute, text); expected[absolute] = ClasspathEvidenceKind.DECLARATION_FILE
    }

    private fun pom(name: String, content: String) = "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>$name</artifactId><version>1</version>$content</project>\n"
    private fun child(name: String, parent: String, relative: String, content: String) = "<project><modelVersion>4.0.0</modelVersion><parent><groupId>fixture</groupId><artifactId>$parent</artifactId><version>1</version><relativePath>$relative</relativePath></parent><artifactId>$name</artifactId>$content</project>\n"
    private fun write(path: Path, text: String) { Files.createDirectories(path.parent); Files.writeString(path, text) }
    private fun images(): Map<Path, String> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) && !it.startsWith(root.resolve(".refactorkit/transactions")) }
            .toList().associateWith(Files::readString)
    }

    override fun close() {
        if (::native.isInitialized) native.close()
        if (::root.isInitialized) Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}
