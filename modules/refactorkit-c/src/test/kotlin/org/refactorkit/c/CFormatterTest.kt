package org.refactorkit.c

import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.Workspace
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CFormatterTest {
    @Test
    fun refusesMissingClangFormat() {
        val formatter = CFormatter(toolchain(nonexistentFormat = true))
        val result = formatter.formatWholeFile(snapshot(), Path.of("main.c"))
        val refused = assertIs<CFormatResult.Refused>(result)
        assertTrue(refused.diagnostics.any { it.code == "clang.formatUnavailable" })
    }

    @Test
    fun refusesSourceOutsideSnapshot() {
        val formatter = CFormatter(toolchain())
        val result = formatter.formatWholeFile(snapshot(), Path.of("/outside/main.c"))
        val refused = assertIs<CFormatResult.Refused>(result)
        assertTrue(refused.diagnostics.any { it.code == "clang.formatSourceMissing" })
    }

    @Test
    fun diffProducesSingleSpanEdit() {
        val formatter = CFormatter(toolchain())
        val edits = formatter.diff("int compute(int x){return x+1;}", "int compute(int x) { return x + 1; }")
        assertEquals(1, edits.size)
        // The common suffix `}` is preserved; the edit replaces the inner region.
        assertEquals(" { return x + 1; ", edits[0].newText)
    }

    @Test
    fun diffIdenticalProducesNoEdits() {
        val formatter = CFormatter(toolchain())
        assertTrue(formatter.diff("int x;", "int x;").isEmpty())
    }

    private fun snapshot() = ProjectSnapshot(
        workspace = Workspace(Path.of("/workspace")),
        modules = emptyList(),
        files = listOf(SourceFile(Path.of("main.c"), "int compute(int x){return x+1;}", "c")),
    )

    private fun toolchain(nonexistentFormat: Boolean = false) = ClangSemanticToolchain(
        clangExecutable = Path.of("/usr/bin/clang"),
        clangdExecutable = Path.of("/usr/bin/clangd"),
        clangFormatExecutable = if (nonexistentFormat) Path.of("/nonexistent/clang-format") else Path.of("/usr/bin/clang-format"),
        provenance = ClangToolchainProvenance(
            clangVersion = "22.1.8", clangdVersion = "22.1.8", clangFormatVersion = "22.1.8",
            targetTriple = "x86_64-pc-linux-gnu", resourceDir = "/usr/lib/clang/22", evidence = emptyList(),
        ),
    )
}
