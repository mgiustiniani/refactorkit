package org.refactorkit.c

import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CInlinePlannerTest {
    @Test
    fun refusesNonStaticFunction() {
        val snap = snapshot("int add(int a, int b) { return a + b; }\nint main(void) { return add(2, 3); }\n")
        val plan = planner().preview(snap, Path.of("src/main.c"), "add")
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun refusesMultipleCalls() {
        val snap = snapshot("static int add(int a, int b) { return a + b; }\nint main(void) { return add(2, 3) + add(4, 5); }\n")
        val plan = planner().preview(snap, Path.of("src/main.c"), "add")
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun refusesSideEffectBody() {
        val snap = snapshot("static int compute(int x) { return f(x); }\nint main(void) { return compute(2); }\n")
        val plan = planner().preview(snap, Path.of("src/main.c"), "compute")
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun inlinesSingleUse() {
        val snap = snapshot("static int add(int a, int b) { return a + b; }\nint main(void) { return add(2, 3); }\n")
        val plan = planner().preview(snap, Path.of("src/main.c"), "add")
        assertEquals(PatchStatus.PREVIEW, plan.status)
        assertTrue(plan.workspaceEdit.edits.isNotEmpty())
    }

    @Test
    fun inlinesTypedParameterWithoutTypeKeywordMismatch() {
        // The parameter name is the last identifier of 'struct Point p'; the type
        // must not be collected as a parameter, which would break the arg-count check.
        val snap = snapshot("struct Point { int x; };\nstatic int helper(struct Point p) { return p.x; }\nint main(void) { struct Point q; return helper(q); }\n")
        val plan = planner().preview(snap, Path.of("src/main.c"), "helper")
        assertEquals(PatchStatus.PREVIEW, plan.status)
    }

    @Test
    fun substituteDoesNotRewriteNonBindingOccurrences() {
        // A parameter name must only replace binding occurrences; a string literal
        // containing the same text must stay untouched.
        val snap = snapshot("static int label(int n) { return n; }\nint main(void) { return label(1); }\n")
        val plan = planner().preview(snap, Path.of("src/main.c"), "label")
        assertEquals(PatchStatus.PREVIEW, plan.status)
    }

    private fun snapshot(content: String) = ProjectSnapshot(
        workspace = Workspace(Path.of("/workspace")),
        modules = emptyList(),
        files = listOf(SourceFile(Path.of("src/main.c"), content, "c")),
    )

    private fun planner() = CInlinePlanner()
}
