package org.refactorkit.jvm.renamemethod

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.refactorkit.core.ClasspathEvidence
import org.refactorkit.core.ClasspathEvidenceKind
import org.refactorkit.core.Module
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourceLocation
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.Workspace
import org.refactorkit.java.JavaJdtDefinitionProjection
import org.refactorkit.java.JavaLanguageAdapter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JavaKotlinDefinitionAuthoritySteps {
    private lateinit var root: Path
    private lateinit var sources: List<SourceFile>
    private lateinit var fixtures: Map<Path, String>
    private lateinit var classpath: List<Path>
    private lateinit var definition: JavaJdtDefinitionProjection
    private val caller = Path.of("src/fixture/Caller.java")
    private val local = Path.of("src/fixture/LocalJava.java")
    private val kotlin = Path.of("src/fixture/Greeter.kt")
    private val binaryJava = Path.of("build/compiler-input/BinaryJava.java")

    @Given("a Java definition fixture with compiled Java and Kotlin types")
    fun prepare() {
        root = Files.createTempDirectory("rk-java-kotlin-definition-")
        fixtures = mapOf(
            caller to """
                package fixture;
                class Caller {
                    Object type() { return Greeter.class; }
                    String call() { return Greeter.message(); }
                    String read() { return Greeter.value; }
                    Object local() { return LocalJava.class; }
                    Object binary() { return BinaryJava.class; }
                    Object missing() { return Missing.class; }
                }
            """.trimIndent(),
            local to "package fixture; class LocalJava {}",
            kotlin to """
                package fixture
                object Greeter {
                    @JvmStatic fun message(): String = "message"
                    @JvmField val value: String = "value"
                }
            """.trimIndent(),
            binaryJava to "package fixture; public class BinaryJava {}",
        )
        fixtures.forEach { (path, text) ->
            val file = root.resolve(path)
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
        }
        val output = Files.createDirectories(root.resolve("build/classes"))
        val stdlib = System.getProperty("kotlin.compiler.test.classpath").split(File.pathSeparator).map(Path::of)
            .single { it.fileName.toString().matches(Regex("kotlin-stdlib-[0-9][^/]*\\.jar")) }
        val messages = ByteArrayOutputStream()
        val exit = PrintStream(messages).use { log ->
            K2JVMCompiler().exec(log, "-no-stdlib", "-no-reflect", "-classpath", stdlib.toString(),
                "-d", output.toString(), root.resolve(kotlin).toString())
        }
        assertEquals(ExitCode.OK, exit, messages.toString(Charsets.UTF_8))
        val javac = assertNotNull(ToolProvider.getSystemJavaCompiler())
        assertEquals(0, javac.run(null, null, null, "-d", output.toString(), root.resolve(binaryJava).toString()))
        classpath = listOf(output, stdlib)
        sources = listOf(caller, local, kotlin).map { path ->
            SourceFile(path, fixtures.getValue(path), if (path == kotlin) "kotlin" else "java")
        }
    }

    @When("the caller requests the definition of {string} in the saved Java source")
    fun navigate(token: String) {
        val project = ProjectSnapshot(
            workspace = Workspace(root),
            modules = listOf(Module("fixture", Path.of("."), listOf(Path.of("src")), classpath)),
            files = sources,
            classpathEvidence = classpath.map { ClasspathEvidence.capture(root, it, ClasspathEvidenceKind.ENTRY) },
        )
        val line = fixtures.getValue(caller).lines().indexOfFirst { token in it }
        val column = fixtures.getValue(caller).lines()[line].indexOf(token)
        val position = SourcePosition(line, column)
        definition = JavaLanguageAdapter().semanticDefinition(project, SourceLocation(caller, SourceRange(position, position)))
    }

    @Then("the definition reports {string} and preserves the workspace sources")
    fun verify(outcome: String) {
        when (outcome) {
            "Kotlin source authority required" -> assertEquals("java.definitionKotlinSourceUnqualified",
                assertIs<JavaJdtDefinitionProjection.Refused>(definition).diagnostic.code)
            "exact saved Java declaration" -> {
                val first = fixtures.getValue(local).indexOf("LocalJava")
                val expected = SourceLocation(local, SourceRange(SourcePosition(0, first), SourcePosition(0, first + "LocalJava".length)))
                assertEquals(expected, assertIs<JavaJdtDefinitionProjection.Available>(definition).location)
            }
            "no workspace Java declaration" -> assertNull(assertIs<JavaJdtDefinitionProjection.Available>(definition).location)
            else -> error(outcome)
        }
        fixtures.forEach { (path, text) -> assertEquals(text, Files.readString(root.resolve(path)), path.toString()) }
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
    }

    @After
    fun close() {
        if (::root.isInitialized) root.toFile().deleteRecursively()
    }
}
