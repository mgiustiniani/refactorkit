package org.refactorkit.c

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Real clang syntax diagnostics on an actual translation unit. */
class CCompilerDiagnosticsIntegrationTest {
    private val clang = Path.of("/usr/bin/clang")

    @Test
    fun reportsRealSyntaxError() {
        if (!Files.isExecutable(clang)) return // clang not installed; integration not run
        val workspace = Files.createTempDirectory("refactorkit-c-diagnostics")
        val mainC = workspace.resolve("main.c")
        val content = "int broken(void) { return undefined_thing; }\n"
        Files.writeString(mainC, content)
        val snapshot = ProjectSnapshot(
            workspace = Workspace(workspace),
            modules = emptyList(),
            files = listOf(SourceFile(Path.of("main.c"), content, "c")),
        )
        val result = CCompilerDiagnostics(toolchain()).analyze(snapshot, Path.of("main.c"))
        val available = assertIs<CDiagnosticsResult.Available>(result)
        assertTrue(available.diagnostics.any { it.severity == Diagnostic.Severity.ERROR && it.message.contains("undeclared identifier") })
    }

    @Test
    fun cleanFileReportsNoDiagnostics() {
        if (!Files.isExecutable(clang)) return // clang not installed; integration not run
        val workspace = Files.createTempDirectory("refactorkit-c-clean")
        val mainC = workspace.resolve("main.c")
        val content = "int compute(int x) { return x + 1; }\n"
        Files.writeString(mainC, content)
        val snapshot = ProjectSnapshot(
            workspace = Workspace(workspace),
            modules = emptyList(),
            files = listOf(SourceFile(Path.of("main.c"), content, "c")),
        )
        val result = CCompilerDiagnostics(toolchain()).analyze(snapshot, Path.of("main.c"))
        val available = assertIs<CDiagnosticsResult.Available>(result)
        assertTrue(available.diagnostics.isEmpty())
    }

    private fun toolchain() = ClangSemanticToolchain(
        clangExecutable = clang,
        clangdExecutable = Path.of("/usr/bin/clangd"),
        clangFormatExecutable = Path.of("/usr/bin/clang-format"),
        provenance = ClangToolchainProvenance(
            clangVersion = "22.1.8", clangdVersion = "22.1.8", clangFormatVersion = "22.1.8",
            targetTriple = "x86_64-pc-linux-gnu", resourceDir = "/usr/lib/clang/22", evidence = emptyList(),
        ),
    )
}
