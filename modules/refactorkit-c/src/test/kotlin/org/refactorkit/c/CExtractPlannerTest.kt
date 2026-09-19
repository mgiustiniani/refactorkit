package org.refactorkit.c

import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.Workspace
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CExtractPlannerTest {
    @Test
    fun refusesInvalidTempName() {
        val plan = planner().preview(snapshot(), Path.of("src/main.c"), range(0, 30, 0, 35), "bad-name")
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun refusesSideEffectfulExpression() {
        val callSnapshot = snapshot("int compute(int x) { int y = f(x); return y; }\n")
        val plan = planner().preview(callSnapshot, Path.of("src/main.c"), range(0, 30, 0, 34), "tmp")
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun refusesUninferableType() {
        val noDeclSnapshot = snapshot("int compute(int x) { y = x + 1; return y; }\n")
        val plan = planner().preview(noDeclSnapshot, Path.of("src/main.c"), range(0, 26, 0, 31), "tmp")
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun extractsPureExpression() {
        val plan = planner().preview(snapshot(), Path.of("src/main.c"), range(0, 30, 0, 35), "tmp")
        assertEquals(PatchStatus.PREVIEW, plan.status)
    }

    @Test
    fun refusesOutOfBoundsRangeWithoutThrowing() {
        // end character beyond the line length must be a typed refusal, not a
        // StringIndexOutOfBoundsException from substring().
        val plan = planner().preview(snapshot(), Path.of("src/main.c"), range(0, 30, 0, 999), "tmp")
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun refusesRangeOnMissingLineWithoutThrowing() {
        val plan = planner().preview(snapshot(), Path.of("src/main.c"), range(99, 0, 99, 3), "tmp")
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun declarationUsesConstWhenContextAllows() {
        val plan = planner().preview(snapshot(), Path.of("src/main.c"), range(0, 30, 0, 35), "tmp")
        assertEquals(PatchStatus.PREVIEW, plan.status)
        val inserted = plan.workspaceEdit.edits.filterIsInstance<org.refactorkit.core.FileEdit.Modify>().single()
            .textEdits.first { it.newText.contains("tmp") }
        assertTrue(inserted.newText.contains("const"), "declaration must match the const-temporary contract")
    }

    @Test
    fun infersTypeFromEnclosingScopeNotFileWideFirstMatch() {
        // 'y' is declared as int in one function and long in another; the type must
        // come from the enclosing scope, not the first file-wide regex match.
        val content = "void a(void) { int y = 1; }\nvoid b(void) { long y = x + 1; return; }\n"
        val expression = "x + 1"
        val type = planner().inferType(content, SourceRange(SourcePosition(1, 24), SourcePosition(1, 29)), expression)
        assertEquals("long", type)
    }

    private fun snapshot(content: String = "int compute(int x) { int y = x + 1; return y; }\n") = ProjectSnapshot(
        workspace = Workspace(Path.of("/workspace")),
        modules = emptyList(),
        files = listOf(SourceFile(Path.of("src/main.c"), content, "c")),
    )

    private fun range(startLine: Int, startChar: Int, endLine: Int, endChar: Int) =
        SourceRange(SourcePosition(startLine, startChar), SourcePosition(endLine, endChar))

    private fun planner() = CExtractPlanner()
}
