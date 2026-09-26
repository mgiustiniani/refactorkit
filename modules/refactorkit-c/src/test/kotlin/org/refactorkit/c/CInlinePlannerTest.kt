package org.refactorkit.c

import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import org.refactorkit.core.WorkspaceEditSimulator
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

    @Test
    fun substituteHandlesSwappedArgumentsAtomically() {
        // Passing the caller's 'b' and 'a' into (a, b) must inline to 'b - a'. A
        // sequential per-parameter replacement would corrupt it to 'a - a'.
        val snap = snapshot(
            "static int sub(int a, int b) { return a - b; }\n" +
                "int main(void) { int a = 1; int b = 2; return sub(b, a); }\n",
        )
        val plan = planner().preview(snap, Path.of("src/main.c"), "sub")
        assertEquals(PatchStatus.PREVIEW, plan.status)
        val applied = WorkspaceEditSimulator.apply(snap, plan.workspaceEdit)
        val result = applied.files.single { it.path.toString() == "src/main.c" }.content
        assertTrue(result.contains("b - a"), "swapped arguments must inline to 'b - a'; got: $result")
        assertTrue(!result.contains("a - a"), "sequential substitution corrupted the result; got: $result")
    }

    @Test
    fun inlinesCompoundArgumentWithParentheses() {
        // sq(a + b) with body 'x * x' must inline to (a + b) * (a + b). Substituting the
        // compound argument without parentheses silently drops precedence to a + b * a + b.
        val snap = snapshot(
            "static int sq(int x) { return x * x; }\n" +
                "int main(void) { int a = 1; int b = 2; return sq(a + b); }\n",
        )
        val plan = planner().preview(snap, Path.of("src/main.c"), "sq")
        assertEquals(PatchStatus.PREVIEW, plan.status)
        val applied = WorkspaceEditSimulator.apply(snap, plan.workspaceEdit)
        val result = applied.files.single { it.path.toString() == "src/main.c" }.content
        assertTrue(result.contains("(a+b)"), "compound argument must be parenthesized; got: $result")
        assertTrue(!result.contains("a+b *"), "unparenthesized substitution drops precedence; got: $result")
    }

    @Test
    fun removesMultiLineDeclarationHeaderWithoutResidue() {
        // The storage/type prefix may sit on a line above the function name. The removal
        // must span the whole header; a line-equality walk-back left 'static int' behind.
        val snap = snapshot(
            "static int\n" +
                "sq(int x) { return x * x; }\n" +
                "int main(void) { return sq(2); }\n",
        )
        val plan = planner().preview(snap, Path.of("src/main.c"), "sq")
        assertEquals(PatchStatus.PREVIEW, plan.status)
        val applied = WorkspaceEditSimulator.apply(snap, plan.workspaceEdit)
        val result = applied.files.single { it.path.toString() == "src/main.c" }.content
        assertTrue(!result.contains("static int"), "multi-line header must be fully removed; got: $result")
        assertTrue(result.contains("2 * 2"), "the single call must be inlined; got: $result")
        assertTrue(!result.contains("sq("), "definition and call must both be gone; got: $result")
    }

    private fun snapshot(content: String) = ProjectSnapshot(
        workspace = Workspace(Path.of("/workspace")),
        modules = emptyList(),
        files = listOf(SourceFile(Path.of("src/main.c"), content, "c")),
    )

    private fun planner() = CInlinePlanner()
}
