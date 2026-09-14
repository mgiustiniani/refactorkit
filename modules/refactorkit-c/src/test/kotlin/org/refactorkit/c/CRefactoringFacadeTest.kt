package org.refactorkit.c

import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class CRefactoringFacadeTest {
    @Test
    fun previewsOrganizeIncludes() {
        val snap = snapshot("#include <stdlib.h>\n#include <stdio.h>\nint main(void) { return 0; }\n")
        val plan = facade().preview(snap, "organizeIncludes", mapOf("file" to "src/main.c"))
        assertEquals(PatchStatus.PREVIEW, plan.status)
    }

    @Test
    fun previewsMoveSource() {
        val snap = snapshot("int a(void) { return 1; }\n")
        val plan = facade().preview(snap, "moveSource", mapOf("file" to "src/main.c", "target" to "src/other.c"))
        assertEquals(PatchStatus.PREVIEW, plan.status)
    }

    @Test
    fun previewsExtractExpression() {
        val snap = snapshot("int compute(int x) { int y = x + 1; return y; }\n")
        val plan = facade().preview(snap, "extractExpression", mapOf(
            "file" to "src/main.c", "startLine" to "0", "startChar" to "30", "endLine" to "0", "endChar" to "35", "tempName" to "tmp",
        ))
        assertEquals(PatchStatus.PREVIEW, plan.status)
    }

    private fun snapshot(content: String) = ProjectSnapshot(
        workspace = Workspace(Path.of("/workspace")),
        modules = emptyList(),
        files = listOf(SourceFile(Path.of("src/main.c"), content, "c")),
    )

    private fun facade() = CRefactoringFacade(toolchain())

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
