package org.refactorkit.c

import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CRenamePrefixPlannerTest {
    @Test
    fun refusesWhenNotStarted() {
        val planner = CRenamePrefixPlanner(toolchain())
        val plan = planner.preview(snapshot(), mapOf("old" to "new"))
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun refusesEmptyMapping() {
        val planner = CRenamePrefixPlanner(toolchain())
        val plan = planner.preview(snapshot(), emptyMap())
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun refusesInvalidMapping() {
        val planner = CRenamePrefixPlanner(toolchain())
        val plan = planner.preview(snapshot(), mapOf("old" to "old"))
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun resolveOccurrenceRefusesAmbiguousSameNameAcrossFiles() {
        val content = "int old_fn(void) { return 0; }\n"
        val snap = ProjectSnapshot(
            workspace = Workspace(Path.of("/workspace")),
            modules = emptyList(),
            files = listOf(
                SourceFile(Path.of("src/a.c"), content, "c"),
                SourceFile(Path.of("src/b.c"), content, "c"),
            ),
        )
        val resolution = CRenamePrefixPlanner(toolchain()).resolveOccurrence(snap, "old_fn")
        assertIs<CRenamePrefixPlanner.OccurrenceResolution.Refused>(resolution)
        assertTrue(resolution.message.contains("ambiguous"))
    }

    @Test
    fun resolveOccurrenceRefusesAbsentSymbol() {
        val resolution = CRenamePrefixPlanner(toolchain()).resolveOccurrence(snapshot(), "absent")
        assertIs<CRenamePrefixPlanner.OccurrenceResolution.Refused>(resolution)
        assertTrue(resolution.message.contains("no semantic occurrence"))
    }

    @Test
    fun resolveOccurrenceFindsSingleBinding() {
        val resolution = CRenamePrefixPlanner(toolchain()).resolveOccurrence(snapshot(), "old_fn")
        assertIs<CRenamePrefixPlanner.OccurrenceResolution.Found>(resolution)
        assertEquals(Path.of("src/main.c"), resolution.occurrence.file)
    }

    @Test
    fun resolveOccurrenceSeedsAtTokenColumnNotFirstSubstring() {
        // fooPrefix shares the 'foo' prefix; the standalone foo token sits at column 16,
        // but a naive first-substring indexOf would seed at column 4 (inside fooPrefix).
        val snap = ProjectSnapshot(
            workspace = Workspace(Path.of("/workspace")),
            modules = emptyList(),
            files = listOf(SourceFile(Path.of("src/main.c"), "int fooPrefix = foo;\n", "c")),
        )
        val resolution = CRenamePrefixPlanner(toolchain()).resolveOccurrence(snap, "foo")
        assertIs<CRenamePrefixPlanner.OccurrenceResolution.Found>(resolution)
        assertEquals(0, resolution.occurrence.line)
        assertEquals(16, resolution.occurrence.character, "seed must use the foo token column, not the fooPrefix substring")
    }

    private fun snapshot() = ProjectSnapshot(
        workspace = Workspace(Path.of("/workspace")),
        modules = emptyList(),
        files = listOf(SourceFile(Path.of("src/main.c"), "int old_fn(void) { return 0; }\nint main(void) { return old_fn(); }\n", "c")),
    )

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
