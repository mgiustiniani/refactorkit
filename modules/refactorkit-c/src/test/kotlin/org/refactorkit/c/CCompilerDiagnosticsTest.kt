package org.refactorkit.c

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SemanticProcessProvenance
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CCompilerDiagnosticsTest {
    private val provenance = SemanticProcessProvenance(
        id = "clang-syntax-1", executable = Path.of("/usr/bin/clang"),
        executableSha256 = "a", argumentsSha256 = "b", workingDirectory = Path.of("/tmp"),
        pid = 1, startedAt = Instant.EPOCH,
    )

    @Test
    fun cleanOutputWithZeroExitIsAvailable() {
        val result = diag.parseForTest("", Path.of("main.c"), provenance, exitCode = 0)
        val available = assertIs<CDiagnosticsResult.Available>(result)
        assertTrue(available.diagnostics.isEmpty())
    }

    @Test
    fun parsesErrorDiagnostic() {
        val output = "main.c:3:27: error: use of undeclared identifier 'x'\n    3 | int f(void) { return x; }\n      |                           ^\n1 error generated.\n"
        val result = diag.parseForTest(output, Path.of("main.c"), provenance, exitCode = 1)
        val available = assertIs<CDiagnosticsResult.Available>(result)
        assertEquals(1, available.diagnostics.size)
        assertEquals(Diagnostic.Severity.ERROR, available.diagnostics[0].severity)
        assertEquals("use of undeclared identifier 'x'", available.diagnostics[0].message)
        assertEquals(2, available.diagnostics[0].location?.range?.start?.line)
    }

    @Test
    fun parsesWarningDiagnostic() {
        val output = "main.c:2:7: warning: unused variable 'y'\n    2 | int f(void) { int y = 0; return 1; }\n      |       ^\n1 warning generated.\n"
        val result = diag.parseForTest(output, Path.of("main.c"), provenance, exitCode = 0)
        val available = assertIs<CDiagnosticsResult.Available>(result)
        assertEquals(Diagnostic.Severity.WARNING, available.diagnostics[0].severity)
    }

    @Test
    fun attributesDiagnosticToTheReportedHeader() {
        // clang reports the file that owns the diagnostic; an error inside an included
        // header must not be attributed to the including translation unit.
        val output = "include/api.h:4:9: error: expected ';' after struct\n    4 | struct point { int x }\n      |                       ^\n1 error generated.\n"
        val result = diag.parseForTest(output, Path.of("main.c"), provenance, exitCode = 1)
        val available = assertIs<CDiagnosticsResult.Available>(result)
        assertEquals(1, available.diagnostics.size)
        assertEquals("include/api.h", available.diagnostics[0].location?.path?.toString())
    }

    @Test
    fun malformedOutputIsUnavailable() {
        val result = diag.parseForTest("garbage output without diagnostic line", Path.of("main.c"), provenance, exitCode = 1)
        assertIs<CDiagnosticsResult.Unavailable>(result)
    }

    @Test
    fun incompleteOutputWithoutSummaryIsUnavailable() {
        val result = diag.parseForTest("main.c:3:27: error: use of undeclared identifier 'x'\n", Path.of("main.c"), provenance, exitCode = 1)
        assertIs<CDiagnosticsResult.Unavailable>(result)
    }

    @Test
    fun unavailableClangIsRefused() {
        val toolchain = ClangSemanticToolchain(
            clangExecutable = Path.of("/nonexistent/clang"),
            clangdExecutable = Path.of("/nonexistent/clangd"),
            clangFormatExecutable = Path.of("/nonexistent/clang-format"),
            provenance = ClangToolchainProvenance(
                clangVersion = "22.1.8", clangdVersion = "22.1.8", clangFormatVersion = "22.1.8",
                targetTriple = "x86_64-pc-linux-gnu", resourceDir = "/usr/lib/clang/22", evidence = emptyList(),
            ),
        )
        val result = CCompilerDiagnostics(toolchain).analyze(
            ProjectSnapshot(Workspace(Path.of("/tmp")), emptyList(), listOf(SourceFile(Path.of("main.c"), "int x;", "c"))),
            Path.of("main.c"),
        )
        assertIs<CDiagnosticsResult.Unavailable>(result)
    }

    private val diag = CCompilerDiagnostics(toolchain())
    private fun toolchain() = ClangSemanticToolchain(
        clangExecutable = Path.of("/usr/bin/clang"),
        clangdExecutable = Path.of("/usr/bin/clangd"),
        clangFormatExecutable = Path.of("/usr/bin/clang-format"),
        provenance = ClangToolchainProvenance(
            clangVersion = "22.1.8", clangdVersion = "22.1.8", clangFormatVersion = "22.1.8",
            targetTriple = "x86_64-pc-linux-gnu", resourceDir = "/usr/lib/clang/22", evidence = emptyList(),
        ),
    )
}
