package org.refactorkit.c

import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.Workspace
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Real clang-format formatting on an actual translation unit. */
class CFormatterIntegrationTest {
    private val clangFormat = Path.of("/usr/bin/clang-format")

    @Test
    fun formatsWholeFile() {
        if (!Files.isExecutable(clangFormat)) return // clang-format not installed; integration not run
        val workspace = Files.createTempDirectory("refactorkit-c-format")
        val mainC = workspace.resolve("main.c")
        val content = "int compute(int x){return x+1;}\nint main(void){return compute(2);}\n"
        Files.writeString(mainC, content)
        val snapshot = ProjectSnapshot(
            workspace = Workspace(workspace),
            modules = emptyList(),
            files = listOf(SourceFile(Path.of("main.c"), content, "c")),
        )
        val result = CFormatter(toolchain()).formatWholeFile(snapshot, Path.of("main.c"))
        val accepted = assertIs<CFormatResult.Accepted>(result)
        assertTrue(accepted.edits.isNotEmpty())
        assertTrue(accepted.idempotent)
    }

    @Test
    fun formatsBoundedRange() {
        if (!Files.isExecutable(clangFormat)) return // clang-format not installed; integration not run
        val workspace = Files.createTempDirectory("refactorkit-c-format-range")
        val mainC = workspace.resolve("main.c")
        val content = "int compute(int x){return x+1;}\nint main(void){return compute(2);}\n"
        Files.writeString(mainC, content)
        val snapshot = ProjectSnapshot(
            workspace = Workspace(workspace),
            modules = emptyList(),
            files = listOf(SourceFile(Path.of("main.c"), content, "c")),
        )
        val result = CFormatter(toolchain()).formatRange(snapshot, Path.of("main.c"), SourceRange(SourcePosition(0, 0), SourcePosition(0, 40)))
        val accepted = assertIs<CFormatResult.Accepted>(result)
        assertTrue(accepted.edits.isNotEmpty())
    }

    @Test
    fun idempotenceCheckNeverTouchesWorkspaceScratch() {
        if (!Files.isExecutable(clangFormat)) return // clang-format not installed; integration not run
        val workspace = Files.createTempDirectory("refactorkit-c-format-scratch")
        val content = "int compute(int x){return x+1;}\nint main(void){return compute(2);}\n"
        Files.writeString(workspace.resolve("main.c"), content)
        // User-owned files that collide with the formatter's old scratch naming pattern.
        val sentinel = "/* user file: must never be overwritten or deleted */\n"
        val colliding = (1..12).map { workspace.resolve(".refactorkit-format-$it.c") }
        colliding.forEach { Files.writeString(it, sentinel) }
        val snapshot = ProjectSnapshot(
            workspace = Workspace(workspace),
            modules = emptyList(),
            files = listOf(SourceFile(Path.of("main.c"), content, "c")),
        )
        val result = CFormatter(toolchain()).formatWholeFile(snapshot, Path.of("main.c"))
        assertIs<CFormatResult.Accepted>(result)
        colliding.forEach { path ->
            assertTrue(
                Files.exists(path),
                "user file $path was deleted by the formatter scratch cleanup",
            )
            assertEquals(
                sentinel,
                Files.readString(path),
                "user file $path was overwritten by the formatter scratch",
            )
        }
    }

    private fun toolchain() = ClangSemanticToolchain(
        clangExecutable = Path.of("/usr/bin/clang"),
        clangdExecutable = Path.of("/usr/bin/clangd"),
        clangFormatExecutable = clangFormat,
        provenance = ClangToolchainProvenance(
            clangVersion = "22.1.8", clangdVersion = "22.1.8", clangFormatVersion = "22.1.8",
            targetTriple = "x86_64-pc-linux-gnu", resourceDir = "/usr/lib/clang/22", evidence = emptyList(),
        ),
    )
}
