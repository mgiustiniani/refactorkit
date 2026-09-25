package org.refactorkit.c

import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CRelocateComponentPlannerTest {
    @Test
    fun refusesBuildManifestTarget() {
        val snap = snapshot(
            listOf(
                SourceFile(Path.of("src/component/a.c"), "int a(void) { return 1; }\n", "c"),
                SourceFile(Path.of("CMakeLists.txt"), "add_library(component src/component/a.c)\n", "c"),
            ),
        )
        val plan = planner().preview(snap, Path.of("src/component"), Path.of("src/other"))
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun refusesMacroInclude() {
        val snap = snapshot(
            listOf(
                SourceFile(Path.of("src/component/a.c"), "#include <HEADER>\nint a(void) { return 1; }\n", "c"),
            ),
        )
        val plan = planner().preview(snap, Path.of("src/component"), Path.of("src/other"))
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun relocatesComponent() {
        val snap = snapshot(
            listOf(
                SourceFile(Path.of("src/component/a.c"), "int a(void) { return 1; }\n", "c"),
                SourceFile(Path.of("src/component/sub/b.c"), "int b(void) { return 2; }\n", "c"),
            ),
        )
        val plan = planner().preview(snap, Path.of("src/component"), Path.of("src/other"))
        assertEquals(PatchStatus.PREVIEW, plan.status)
        assertTrue(plan.workspaceEdit.edits.isNotEmpty())
    }

    @Test
    fun relocateEvidenceIsStructuralNotLanguageServer() {
        // The relocation is planned from snapshot literal includes; no language-server
        // operation produced it, so the evidence must not claim one.
        val snap = snapshot(
            listOf(
                SourceFile(Path.of("src/component/a.c"), "int a(void) { return 1; }\n", "c"),
            ),
        )
        val plan = planner().preview(snap, Path.of("src/component"), Path.of("src/other"))
        assertEquals(PatchStatus.PREVIEW, plan.status)
        assertEquals(org.refactorkit.core.RefactoringEvidence.STRUCTURAL, plan.evidence)
    }

    @Test
    fun includeTargetStaysRelativeToIncludingDirectory() {
        // main.c includes "a.h" from the same directory; after both move to src/other,
        // the include stays "a.h" and must not become a root-relative path.
        val snap = snapshot(
            listOf(
                SourceFile(Path.of("src/component/main.c"), "#include \"a.h\"\nint main(void) { return 0; }\n", "c"),
                SourceFile(Path.of("src/component/a.h"), "#define A_H\n", "c"),
            ),
        )
        val plan = planner().preview(snap, Path.of("src/component"), Path.of("src/other"))
        assertEquals(PatchStatus.PREVIEW, plan.status)
        // The rewrite is recorded under the destination path so it composes after the rename.
        val mainEdits = plan.workspaceEdit.edits.filterIsInstance<org.refactorkit.core.FileEdit.Modify>()
            .filter { it.path == Path.of("src/other/main.c") }
            .flatMap { it.textEdits }
        assertTrue(mainEdits.all { it.newText == "a.h" },
            "a same-directory include must stay relative 'a.h'; got: " + mainEdits.map { it.newText })
        assertTrue(mainEdits.none { it.newText.startsWith("/") || it.newText.contains("src/other/") },
            "a same-directory include must not become a root-relative path")
    }

    @Test
    fun rewritesExternalConsumerIncludeAndApplies() {
        // An external consumer pointing into the component must be rewritten when the
        // component moves; applying the composed plan updates the consumer file.
        val snap = snapshot(
            listOf(
                SourceFile(Path.of("src/app.c"), "#include \"component/a.h\"\nint app(void) { return 0; }\n", "c"),
                SourceFile(Path.of("src/component/a.h"), "#define A_H\n", "c"),
            ),
        )
        val plan = planner().preview(snap, Path.of("src/component"), Path.of("src/other"))
        assertEquals(PatchStatus.PREVIEW, plan.status)
        val appEdits = plan.workspaceEdit.edits.filterIsInstance<org.refactorkit.core.FileEdit.Modify>()
            .filter { it.path == Path.of("src/app.c") }.flatMap { it.textEdits }
        assertTrue(appEdits.any { it.newText == "other/a.h" },
            "the external include must be rewritten to the new relative path; got: " + appEdits.map { it.newText })
        val applied = org.refactorkit.core.WorkspaceEditSimulator.apply(snap, plan.workspaceEdit)
        val appResult = applied.files.single { it.path.toString() == "src/app.c" }.content
        assertTrue(appResult.contains("#include \"other/a.h\""), "applied consumer must reference the new path; got: $appResult")
        val movedHeader = applied.files.single { it.path.toString() == "src/other/a.h" }.content
        assertTrue(movedHeader.contains("#define A_H"), "moved header content must be preserved; got: $movedHeader")
    }

    @Test
    fun doesNotRewriteIncludeByAmbiguousBasename() {
        // Two moved files share the basename a.h; an include outside the component that
        // only names 'a.h' must not be attributed by basename to either binding.
        val snap = snapshot(
            listOf(
                SourceFile(Path.of("src/component/x/a.h"), "#define X_A_H\n", "c"),
                SourceFile(Path.of("src/component/y/a.h"), "#define Y_A_H\n", "c"),
                SourceFile(Path.of("src/app.c"), "#include \"a.h\"\nint app(void) { return 0; }\n", "c"),
            ),
        )
        val plan = planner().preview(snap, Path.of("src/component"), Path.of("src/other"))
        assertEquals(PatchStatus.PREVIEW, plan.status)
        val appEdits = plan.workspaceEdit.edits.filterIsInstance<org.refactorkit.core.FileEdit.Modify>()
            .filter { it.path == Path.of("src/app.c") }
            .flatMap { it.textEdits }
        assertTrue(appEdits.none { it.newText.contains("a.h") && it.newText != "a.h" },
            "an include must not be rewritten by an ambiguous basename fallback")
    }

    private fun snapshot(files: List<SourceFile>) = ProjectSnapshot(
        workspace = Workspace(Path.of("/workspace")),
        modules = emptyList(),
        files = files,
    )

    private fun planner() = CRelocateComponentPlanner()
}
