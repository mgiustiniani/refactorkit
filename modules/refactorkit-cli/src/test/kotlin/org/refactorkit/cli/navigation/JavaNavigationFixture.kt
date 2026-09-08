package org.refactorkit.cli.navigation

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Owns only disposable CLI inputs and process evidence, never production application state. */
internal class JavaNavigationFixture : AutoCloseable {
    private data class Member(val symbol: String, val kind: String, val location: String, val signed: String?)
    private data class Output(val exit: Int, val stdout: String, val stderr: String)

    private val root = Files.createTempDirectory("rk-annotated-navigation-")
    private val workspace = Files.createDirectories(root.resolve("workspace"))
    private val packaged = Path.of(System.getProperty("refactorkit.navigation.package", "build/package/refactorkit"))
        .toAbsolutePath().normalize()
    private val windows = System.getProperty("os.name").startsWith("Windows")
    private val java = packaged.resolve(if (windows) "runtime/bin/java.exe" else "runtime/bin/java")
    private val launcher = packaged.resolve(if (windows) "bin/refactorkit.bat" else "bin/refactorkit")
    private val members = mutableListOf<Member>()
    private val unsignedResults = mutableListOf<Output>()
    private val signedResults = mutableListOf<Pair<Member, Output>>()
    private val readOnlyResults = mutableListOf<Boolean>()
    private lateinit var modules: Output
    private lateinit var compilation: Output
    private lateinit var symbols: Output
    private lateinit var absent: Output
    private lateinit var unavailable: Output
    private lateinit var lexicalDuringFailure: Output
    private lateinit var recovered: Output
    private var commandNumber = 0

    fun prepare(rows: List<Map<String, String>>) {
        assertTrue(Files.isExecutable(java), "build the packaged CLI before running navigation validation: $java")
        assertEquals(6, rows.size)
        workspace.resolve("pom.xml").writeText("""
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion><groupId>navigation</groupId>
              <artifactId>annotated-members</artifactId><version>1</version>
              <properties><maven.compiler.release>21</maven.compiler.release></properties>
            </project>
        """.trimIndent() + "\n")
        val sources = Files.createDirectories(workspace.resolve("src/main/java/navigation"))
        sources.resolve("CompilerApiUse.java").writeText("""
            package navigation;
            import com.sun.source.tree.Tree;
            public class CompilerApiUse { public Tree.Kind kind(Tree tree) { return tree.getKind(); } }
        """.trimIndent() + "\n")
        sources.resolve("Hints.java").writeText("""
            package navigation;
            @interface Hint { String value(); }
            @interface Hints { Hint[] value(); }
        """.trimIndent() + "\n")
        rows.forEach { row ->
            val name = row.getValue("class")
            val kind = row.getValue("member kind")
            val declaration = when (row.getValue("annotation shape")) {
                "inline marker" -> when (kind) {
                    "method" -> "@Override public void close() {}"
                    "field" -> "@Deprecated public String label = \"active\";"
                    "constructor" -> "@Deprecated public $name() {}"
                    else -> error("unknown member kind $kind")
                }
                "preceding marker" -> "@Override\n    public void close() {}"
                "qualified array and string" -> "@java.lang.SuppressWarnings(value = {\"unused\", \"closing ) { @\"}) @Override public void close() {}"
                "multiline nested arguments" -> "@Hints({\n        @Hint(\"nested )\"),\n        @Hint(\"text { @\")\n    }) @Override public void close() {}"
                else -> error("unknown annotation shape")
            }
            val source = "package navigation;\n\npublic class $name" +
                (if (kind == "method") " implements AutoCloseable" else "") +
                " {\n    $declaration\n}\n"
            val path = sources.resolve("$name.java")
            path.writeText(source)
            val memberName = when (kind) { "field" -> "label"; "constructor" -> "<init>"; else -> "close" }
            val anchor = when (kind) { "field" -> "public String label"; "constructor" -> "public $name()"; else -> "public void close()" }
            val position = source.indexOf(anchor)
            assertTrue(position >= 0)
            val line = source.take(position).count { it == '\n' } + 1
            val symbol = "navigation.$name#$memberName"
            members += Member(symbol, kind.uppercase(), "src/main/java/navigation/$name.java:$line",
                if (kind == "field") null else "$symbol()")
        }
    }

    fun navigate() {
        modules = process(listOf(java.toString(), "--list-modules"))
        val sourceFiles = Files.walk(workspace.resolve("src")).use { paths ->
            paths.filter { it.toString().endsWith(".java") }.sorted().map(Path::toString).toList()
        }
        val baseline = digestInputs()
        compilation = process(listOf(java.toString(), "-m", "jdk.compiler/com.sun.tools.javac.Main", "--release", "21",
            "-d", root.resolve("compiled").toString()) + sourceFiles)
        readOnlyResults += digestInputs() == baseline
        symbols = cli("symbols", workspace.toString())
        members.forEach { member ->
            unsignedResults += cli("definition", "--symbol", member.symbol, workspace.toString())
            member.signed?.let { signed ->
                signedResults += member to cli("definition", "--symbol", signed, workspace.toString())
            }
        }
        absent = cli("definition", "--symbol", "navigation.InlineResource#absent()", workspace.toString())
        val unrelated = workspace.resolve("src/main/java/navigation/Unrelated.java")
        unrelated.writeText("package navigation;\npublic class Unrelated { MissingType value; }\n")
        unavailable = cli("definition", "--symbol", "navigation.InlineResource#close()", workspace.toString())
        lexicalDuringFailure = cli("definition", "--symbol", "navigation.InlineResource#close", workspace.toString())
        Files.delete(unrelated)
        recovered = cli("definition", "--symbol", "navigation.InlineResource#close()", workspace.toString())
        readOnlyResults += digestInputs() == baseline
    }

    fun verifyDeclarations() {
        val errors = mutableListOf<String>()
        fun require(condition: Boolean, message: String) { if (!condition) errors += message }
        require(modules.exit == 0, modules.stderr)
        for (module in listOf("java.compiler", "jdk.compiler")) {
            require(modules.stdout.lineSequence().any { it.startsWith("$module@") }, "embedded runtime lacks $module")
        }
        require(compilation.exit == 0, "fixture compilation failed: ${compilation.stdout}${compilation.stderr}")
        require(symbols.exit == 0, "symbols failed: ${symbols.stderr}")
        members.zip(unsignedResults).forEach { (member, result) ->
            val expected = "${member.kind}\t${member.symbol}\t${member.location}"
            require(symbols.stdout.lineSequence().count { it == expected } == 1, "missing or imprecise declaration: $expected")
            require(result.exit == 0 && result.stdout.trim() == member.location, "unsigned lookup failed: ${member.symbol}: $result")
        }
        signedResults.forEach { (member, result) ->
            require(result.exit == 0 && result.stdout.trim() == member.location, "signed lookup failed: ${member.signed}: $result")
        }
        // Report all baseline defects, including the diagnostic distinction, in the RED receipt.
        require(unavailable.exit == 2 && "java.definition.analysisIncomplete" in unavailable.stderr,
            "unavailable analysis was not distinguished: $unavailable")
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }

    fun verifyRefusalsAndReadOnly() {
        assertEquals(1, absent.exit)
        assertEquals("Symbol not found: navigation.InlineResource#absent()", absent.stdout.trim())
        assertEquals(2, unavailable.exit)
        assertTrue(unavailable.stdout.isBlank())
        assertTrue("java.definition.analysisIncomplete" in unavailable.stderr)
        assertTrue("Unrelated.java:2" in unavailable.stderr)
        assertTrue("MissingType" in unavailable.stderr)
        assertFalse("Symbol not found" in unavailable.stderr)
        assertEquals(0, lexicalDuringFailure.exit)
        assertEquals(0, recovered.exit)
        assertEquals(members.first().location, recovered.stdout.trim())
        assertTrue(readOnlyResults.isNotEmpty() && readOnlyResults.all { it }, "a navigation read changed the input workspace")
    }

    private fun cli(vararg arguments: String): Output {
        val before = digestInputs()
        // Invoke the actual bundled launcher, never the parent JVM or its test classpath.
        val command = if (windows) listOf("cmd", "/c", launcher.toString()) else listOf(launcher.toString())
        val output = process(command + arguments)
        readOnlyResults += digestInputs() == before
        return output
    }

    private fun process(command: List<String>): Output {
        val number = commandNumber++
        val stdout = root.resolve("$number.stdout")
        val stderr = root.resolve("$number.stderr")
        val process = ProcessBuilder(command).directory(root.toFile())
            .redirectOutput(stdout.toFile()).redirectError(stderr.toFile())
            .apply { environment().keys.removeAll(setOf("JAVA_HOME", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) }
            .start()
        try {
            check(process.waitFor(45, TimeUnit.SECONDS)) { "packaged CLI timed out: $command" }
            check(Files.size(stdout) <= 1_048_576 && Files.size(stderr) <= 1_048_576) { "unexpected CLI output size" }
            return Output(process.exitValue(), stdout.readText(), stderr.readText())
        } finally {
            if (process.isAlive) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
        }
    }

    private fun digestInputs(): Map<String, String> = Files.walk(workspace).use { paths ->
        paths.filter(Files::isRegularFile).sorted().toList().associate { path ->
            workspace.relativize(path).toString() to MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
        }
    }

    override fun close() {
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}
