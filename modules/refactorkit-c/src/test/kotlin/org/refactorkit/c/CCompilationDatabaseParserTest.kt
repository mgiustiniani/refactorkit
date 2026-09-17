package org.refactorkit.c

import org.refactorkit.core.Diagnostic
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CCompilationDatabaseParserTest {
    @Test
    fun parsesArgumentsArrayAndDerivesFlags() {
        val fixture = fixture()
        val json = """
            [
              {
                "directory": "${fixture.workspace}",
                "arguments": ["clang", "-std=c17", "-DDEBUG=1", "-Iinclude", "-Isrc", "-c", "src/main.c", "-o", "out/main.o"],
                "file": "src/main.c",
                "output": "out/main.o"
              }
            ]
        """.trimIndent()
        val result = parser().parse(fixture.workspace, json.toByteArray())
        val database = assertIs<CCompilationDatabaseDiscovery.Available>(result).database
        val unit = database.units.single()
        assertEquals(fixture.workspace.resolve("src/main.c"), unit.file)
        assertEquals("c17", unit.standard)
        assertEquals(listOf("DEBUG=1"), unit.defines)
        assertEquals(listOf(fixture.workspace.resolve("include"), fixture.workspace.resolve("src")), unit.includeDirectories)
        assertEquals("out/main.o", unit.output)
    }

    @Test
    fun parsesCommandStringAndDerivesTargetAndSysroot() {
        val fixture = fixture()
        val json = """
            [
              {
                "directory": "${fixture.workspace}",
                "command": "clang -std=c11 -target x86_64-pc-linux-gnu --sysroot ${fixture.workspace.resolve("sysroot")} -c src/main.c",
                "file": "src/main.c"
              }
            ]
        """.trimIndent()
        val result = parser().parse(fixture.workspace, json.toByteArray())
        val unit = assertIs<CCompilationDatabaseDiscovery.Available>(result).database.units.single()
        assertEquals("c11", unit.standard)
        assertEquals("x86_64-pc-linux-gnu", unit.target)
        assertEquals(fixture.workspace.resolve("sysroot"), unit.sysroot)
    }

    @Test
    fun refusesInvalidJsonEmptyDatabaseAndMissingFields() {
        val fixture = fixture()
        assertCodes(parser().parse(fixture.workspace, "not json".toByteArray()), "c.compilationDatabaseInvalid")
        assertCodes(parser().parse(fixture.workspace, "[]".toByteArray()), "c.compilationDatabaseEmpty")
        assertCodes(parser().parse(fixture.workspace, "{}".toByteArray()), "c.compilationDatabaseInvalid")
        assertCodes(parser().parse(fixture.workspace, """
            [ { "directory": "${fixture.workspace}" } ]
        """.trimIndent().toByteArray()), "c.compilationDatabaseInvalid")
    }

    @Test
    fun refusesPathOutsideWorkspaceAndDuplicateUnits() {
        val fixture = fixture()
        val outside = fixture.workspace.resolve("../outside.c")
        assertCodes(parser().parse(fixture.workspace, """
            [ { "directory": "${fixture.workspace}", "arguments": ["clang", "-c", "${outside}"], "file": "${outside}" } ]
        """.trimIndent().toByteArray()), "c.pathOutsideWorkspace")

        assertCodes(parser().parse(fixture.workspace, """
            [
              { "directory": "${fixture.workspace}", "arguments": ["clang", "-c", "src/main.c"], "file": "src/main.c" },
              { "directory": "${fixture.workspace}", "arguments": ["clang", "-c", "src/main.c"], "file": "src/main.c" }
            ]
        """.trimIndent().toByteArray()), "c.duplicateTranslationUnit")
    }

    @Test
    fun refusesShellOperatorsAndRequiresArgumentsArray() {
        val fixture = fixture()
        assertCodes(parser().parse(fixture.workspace, """
            [ { "directory": "${fixture.workspace}", "command": "clang -c src/main.c | tee log", "file": "src/main.c" } ]
        """.trimIndent().toByteArray()), "c.commandShellOperator")

        assertCodes(CCompilationDatabaseParser(CCompilationDatabasePolicy(requireArgumentsArray = true))
            .parse(fixture.workspace, """
                [ { "directory": "${fixture.workspace}", "command": "clang -c src/main.c", "file": "src/main.c" } ]
            """.trimIndent().toByteArray()), "c.compilationDatabaseInvalid")
    }

    @Test
    fun enforcesBoundedDatabaseAndUnitCounts() {
        val fixture = fixture()
        val big = " ".repeat(9 * 1024 * 1024)
        assertCodes(parser().parse(fixture.workspace, big.toByteArray()), "c.compilationDatabaseLimit")

        val manyUnits = (0 until 5).joinToString(",") {
            """{ "directory": "${fixture.workspace}", "arguments": ["clang", "-c", "src/f$it.c"], "file": "src/f$it.c" }"""
        }
        assertCodes(CCompilationDatabaseParser(CCompilationDatabasePolicy(maxUnits = 3))
            .parse(fixture.workspace, "[$manyUnits]".toByteArray()), "c.compilationDatabaseLimit")
    }

    @Test
    fun tokenizesQuotedCommandWithoutExecuting() {
        assertEquals(listOf("clang", "-std=c17", "-DNAME=hello world"), tokenizeQuoted("clang -std=c17 -DNAME=\"hello world\""))
        assertEquals(listOf("clang", "-Iinclude dir"), tokenizeQuoted("clang -I'include dir'"))
        assertEquals(listOf("clang", "-c", "src/main.c"), tokenizeQuoted("  clang   -c   src/main.c  "))
    }

    @Test
    fun refusesUnsafeCompilerFlags() {
        val fixture = fixture()
        listOf(
            "-Xclang -load -Xclang /tmp/evil.so",
            "-fplugin=/tmp/evil.so",
            "-plugin /tmp/evil.so",
            "-include /tmp/injected.h",
            "-imacros /tmp/injected.h",
            "-B /tmp/toolchain",
            "--prefix=/tmp/toolchain",
            "-specs=/tmp/evil.spec",
            "-mllvm -evil",
        ).forEach { unsafe ->
            assertCodes(parser().parse(fixture.workspace, """
                [ { "directory": "${fixture.workspace}", "command": "clang $unsafe -c src/main.c", "file": "src/main.c" } ]
            """.trimIndent().toByteArray()), "c.unsafeCompilerFlag")
        }
    }

    @Test
    fun allowsBoundedAnalysisFlags() {
        val fixture = fixture()
        val result = parser().parse(fixture.workspace, """
            [ { "directory": "${fixture.workspace}", "arguments": ["clang", "-std=c17", "-DDEBUG=1", "-UFOO", "-Iinclude", "-isystem", "${fixture.workspace.resolve("include")}", "-Wall", "-Wextra", "-c", "src/main.c"], "file": "src/main.c" } ]
        """.trimIndent().toByteArray())
        assertIs<CCompilationDatabaseDiscovery.Available>(result)
    }

    @Test
    fun unsafeFlagRejectionOnlyRelaxesOnExplicitOptIn() {
        val fixture = fixture()
        val json = """
            [ { "directory": "${fixture.workspace}", "arguments": ["clang", "-Xclang", "-load", "/tmp/evil.so", "-c", "src/main.c"], "file": "src/main.c" } ]
        """.trimIndent()
        assertCodes(parser().parse(fixture.workspace, json.toByteArray()), "c.unsafeCompilerFlag")
        val optedIn = CCompilationDatabaseParser(CCompilationDatabasePolicy(allowUnsafeCompilerFlags = true))
            .parse(fixture.workspace, json.toByteArray())
        assertIs<CCompilationDatabaseDiscovery.Available>(optedIn)
    }

    private fun parser() = CCompilationDatabaseParser()
    private fun assertCodes(result: CCompilationDatabaseDiscovery, vararg expected: String) {
        val refused = assertIs<CCompilationDatabaseDiscovery.Refused>(result)
        val actual = refused.diagnostics.mapNotNull(Diagnostic::code)
        expected.forEach { assertTrue(it in actual, "missing $it in $actual") }
    }

    private fun fixture(): Fixture {
        val workspace = Files.createTempDirectory("refactorkit-c-db")
        val src = workspace.resolve("src")
        src.resolve("main.c").toFile().parentFile?.mkdirs() ?: src.toFile().mkdirs()
        workspace.resolve("include").toFile().mkdirs()
        workspace.resolve("sysroot").toFile().mkdirs()
        return Fixture(workspace)
    }

    private data class Fixture(val workspace: Path)
}
