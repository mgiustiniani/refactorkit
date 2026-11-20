package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.core.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.Comparator
import kotlin.test.*

class OwnedSourceAncestorIdentitySteps {
    private val fixture = OwnedSourceAncestorIdentityFixture()
    @Given("an owned source capture boundary has {string}")
    fun prepare(condition: String) = fixture.prepare(condition)
    @When("the owned NIO capture crosses the controlled directory open boundary")
    fun capture() = fixture.capture()
    @Then("capture reports {string} with exact input authority and no partial result")
    fun verify(outcome: String) = fixture.verify(outcome)
    @Then("capture preserves declared inputs and the external canary")
    fun intact() = fixture.intact()
    @After fun close() = fixture.close()
}

private class OwnedSourceAncestorIdentityFixture : AutoCloseable {
    private lateinit var base: Path
    private lateinit var ancestor: Path
    private lateinit var workspace: Path
    private lateinit var condition: String
    private lateinit var fs: OwnedCaptureTestFileSystem
    private lateinit var finalInputs: Map<String, String>
    private lateinit var originalCanary: String
    private var crossed = false
    private var result: OwnedSourceInventory? = null
    private var input: OwnedWorkspaceTextReadout? = null
    private var failure: OwnedSourceCaptureException? = null
    private val source = "class Main { int value = 1; }\n"
    private val pom = "<project><modelVersion>4.0.0</modelVersion></project>\n"

    fun prepare(value: String) {
        require(value in setOf("flat inventory ancestor churn", "selected roots ancestor churn", "opaque input ancestor churn",
            "ancestor inode substitution", "ancestor link substitution", "workspace membership change"))
        condition = value
        val repo = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) && Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        base = Files.createTempDirectory(repo.resolve(".refactorkit/runs"), "owned-ancestor-case-")
        ancestor = Files.createDirectory(base.resolve("ancestor"))
        workspace = Files.createDirectories(ancestor.resolve("workspace/src")).parent
        Files.writeString(workspace.resolve("src/Main.java"), source)
        Files.writeString(workspace.resolve("pom.xml"), pom)
        val outside = Files.createDirectories(base.resolve("outside/workspace/src"))
        Files.writeString(outside.resolve("Main.java"), "class OutsideCanary {}\n")
        originalCanary = hash(outside.resolve("Main.java"))
        fs = OwnedCaptureTestFileSystem { target ->
            val selected = if (condition == "workspace membership change") workspace else ancestor
            if (!crossed && target == selected) {
                crossed = true
                val before = Files.readAttributes(selected, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                when (condition) {
                    "ancestor inode substitution" -> {
                        Files.move(ancestor, base.resolve("displaced"))
                        Files.createDirectories(ancestor.resolve("workspace/src"))
                        Files.writeString(ancestor.resolve("workspace/src/Main.java"), "class ReplacementCanary {}\n")
                    }
                    "ancestor link substitution" -> {
                        Files.move(ancestor, base.resolve("displaced"))
                        Files.createSymbolicLink(ancestor, base.resolve("outside"))
                    }
                    "workspace membership change" -> {
                        Files.writeString(workspace.resolve("Added.java"), "class Added {}\n")
                        Files.setLastModifiedTime(workspace, FileTime.fromMillis(before.lastModifiedTime().toMillis() + 2000))
                    }
                    else -> {
                        Files.writeString(ancestor.resolve("unrelated-sibling.txt"), "outside workspace\n")
                        Files.setLastModifiedTime(ancestor, FileTime.fromMillis(before.lastModifiedTime().toMillis() + 2000))
                        val after = Files.readAttributes(ancestor, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                        assertEquals(before.fileKey(), after.fileKey(), "Ancestor inode must remain identical")
                        assertNotEquals(before.lastModifiedTime(), after.lastModifiedTime(), "Real sibling membership metadata must change")
                    }
                }
                finalInputs = inventory()
            }
        }
    }

    fun capture() {
        try {
            val root = fs.getPath(workspace.toString())
            val reader = OwnedSourceInventoryReader(mapOf("java" to "java"))
            when (condition) {
                "selected roots ancestor churn" -> result = reader.captureSourceRoots(root, listOf(fs.getPath("src")))
                "opaque input ancestor churn" -> input = OwnedSourceInventoryReader.readWorkspaceTextInput(root, fs.getPath("pom.xml"), 4096)
                else -> result = reader.capture(root)
            }
        } catch (caught: OwnedSourceCaptureException) { failure = caught }
        assertTrue(crossed, "Deterministic owned-directory interleaving must run; unrelated earlier capture failure is setup failure")
    }

    fun verify(outcome: String) {
        if (outcome == "captured") {
            assertNull(failure, "Unowned sibling metadata must not invalidate a stable ancestor inode: $failure")
            if (condition == "opaque input ancestor churn") {
                assertEquals(OwnedWorkspaceTextReadout.Captured(pom), input)
                assertNull(result)
            } else {
                assertEquals(mapOf("src/Main.java" to source), assertNotNull(result).sources.associate { it.path.toString() to it.content })
                assertNull(input)
            }
        } else {
            assertEquals(outcome, assertNotNull(failure, "A changed owned input or ancestor substitution must refuse").code.name)
            assertNull(result, "A refused capture cannot publish a partial inventory")
            assertNull(input, "A refused capture cannot return an opaque input")
        }
        println("OWNED_SOURCE_ANCESTOR condition=$condition outcome=$outcome")
    }

    fun intact() {
        assertEquals(finalInputs, inventory(), "Capture itself must not mutate declared post-interleaving files")
        assertEquals(originalCanary, hash(base.resolve("outside/workspace/src/Main.java")))
        assertFalse(Files.exists(base.resolve(".refactorkit/transactions")))
    }
    private fun hash(path: Path) = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
    private fun inventory(): Map<String, String> = Files.walk(base).use { paths ->
        paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.toList().associate { base.relativize(it).toString() to hash(it) }
    }
    override fun close() {
        if (::fs.isInitialized) fs.close()
        if (::base.isInitialized) Files.walk(base).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}
