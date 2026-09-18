package org.refactorkit.c

import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CSymbolNavigationTest {
    private fun snapshot(content: String, languageId: String = "c") = ProjectSnapshot(
        workspace = Workspace(Path.of("/workspace")),
        modules = emptyList(),
        files = listOf(SourceFile(Path.of("src/main.c"), content, languageId)),
    )

    @Test
    fun returnsUnavailableWhenNotStarted() {
        val nav = CSymbolNavigation(toolchain())
        val result = nav.definition(Path.of("src/main.c"), 0, 0)
        val unavailable = assertIs<CSymbolNavigationResult.Unavailable>(result)
        assertTrue(unavailable.diagnostics.any { it.code == "clangd.notStarted" })
    }

    @Test
    fun reportsUnavailableReferencesWhenNotStarted() {
        val nav = CSymbolNavigation(toolchain())
        val result = nav.references(Path.of("src/main.c"), 0, 0)
        val unavailable = assertIs<CReferenceResult.Unavailable>(result)
        assertTrue(unavailable.diagnostics.any { it.code == "clangd.notStarted" })
    }

    @Test
    fun searchWorksWithoutStarting() {
        val nav = CSymbolNavigation(toolchain())
        val index = CSymbolScanner().scan("src/main.c", "struct point { int x; };")
        nav.addStructuralUnit(index)
        val found = nav.search("point")
        assertEquals(1, found.size)
    }

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
