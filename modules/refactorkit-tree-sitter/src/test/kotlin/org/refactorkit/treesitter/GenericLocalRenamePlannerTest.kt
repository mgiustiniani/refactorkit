package org.refactorkit.treesitter

import org.refactorkit.core.Module
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringRequest
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GenericLocalRenamePlannerTest {

    private val planner = GenericLocalRenamePlanner()
    private val root = Paths.get("/workspace")

    private fun snap(vararg files: Pair<String, String>): ProjectSnapshot {
        val sourceFiles = files.map { (rel, content) ->
            SourceFile(Paths.get(rel), content, languageId = rel.substringAfterLast('.').let {
                when (it) { "java" -> "java"; "ts" -> "typescript"; "py" -> "python"; "rs" -> "rust"; else -> it }
            })
        }
        return ProjectSnapshot(
            workspace = Workspace(root),
            modules = listOf(Module("test", root)),
            files = sourceFiles,
        )
    }

    @Test
    fun renamedOccurrencesProducePreviewPlan() {
        val snap = snap("Foo.java" to "public class Foo { Foo() {} }")
        val plan = planner.preview(snap, Paths.get("Foo.java"), "Foo", "Bar")
        assertEquals(PatchStatus.PREVIEW, plan.status)
        assertTrue(plan.summary.contains("Bar"))
        // Two occurrences: class declaration + constructor
        assertEquals(setOf(Paths.get("Foo.java")), plan.affectedFiles)
    }

    @Test
    fun noOccurrencesProducesPreviewWithWarning() {
        val snap = snap("Foo.java" to "public class Other {}")
        val plan = planner.preview(snap, Paths.get("Foo.java"), "Foo", "Bar")
        assertEquals(PatchStatus.PREVIEW, plan.status)
        assertTrue(plan.affectedFiles.isEmpty())
        assertTrue(plan.warnings.any { it.contains("No edits") })
    }

    @Test
    fun refusedForMissingFile() {
        val snap = snap("Foo.java" to "class Foo {}")
        val plan = planner.preview(snap, Paths.get("Missing.java"), "Foo", "Bar")
        assertEquals(PatchStatus.REFUSED, plan.status)
        assertTrue(plan.summary.contains("not found"))
    }

    @Test
    fun refusedForInvalidSourceIdentifier() {
        val snap = snap("Foo.java" to "class Foo {}")
        val plan = planner.preview(snap, Paths.get("Foo.java"), "123invalid", "Bar")
        assertEquals(PatchStatus.REFUSED, plan.status)
        assertTrue(plan.summary.contains("Invalid source"))
    }

    @Test
    fun refusedForInvalidTargetIdentifier() {
        val snap = snap("Foo.java" to "class Foo {}")
        val plan = planner.preview(snap, Paths.get("Foo.java"), "Foo", "has-hyphen")
        assertEquals(PatchStatus.REFUSED, plan.status)
        assertTrue(plan.summary.contains("Invalid target"))
    }

    @Test
    fun refusedForSameIdentifier() {
        val snap = snap("Foo.java" to "class Foo {}")
        val plan = planner.preview(snap, Paths.get("Foo.java"), "Foo", "Foo")
        assertEquals(PatchStatus.REFUSED, plan.status)
        assertTrue(plan.summary.contains("same"))
    }

    @Test
    fun doesNotRenamePartialIdentifiers() {
        val snap = snap("Foo.java" to "class FooBar { Foo foo; }")
        val plan = planner.preview(snap, Paths.get("Foo.java"), "Foo", "Baz")
        assertEquals(PatchStatus.PREVIEW, plan.status)
        // Only "Foo foo;" should match (1 occurrence), not "FooBar"
        val modify = plan.workspaceEdit.edits.single() as org.refactorkit.core.FileEdit.Modify
        assertEquals(1, modify.textEdits.size)
    }

    @Test
    fun skipsLineComments() {
        val snap = snap("Foo.java" to "class Foo { // Foo is here\n  Foo x;\n}")
        val plan = planner.preview(snap, Paths.get("Foo.java"), "Foo", "Bar")
        assertEquals(PatchStatus.PREVIEW, plan.status)
        val modify = plan.workspaceEdit.edits.single() as org.refactorkit.core.FileEdit.Modify
        // "class Foo" + "Foo x" = 2; "// Foo is here" is skipped
        assertEquals(2, modify.textEdits.size)
    }

    @Test
    fun skipsPythonHashComments() {
        val snap = snap("module.py" to "class Foo:  # Foo is important\n    def foo(self): pass\n")
        val plan = planner.preview(snap, Paths.get("module.py"), "Foo", "Bar")
        assertEquals(PatchStatus.PREVIEW, plan.status)
        val modify = plan.workspaceEdit.edits.single() as org.refactorkit.core.FileEdit.Modify
        // "class Foo:" only; "# Foo is important" skipped; method "foo" != "Foo" (case-sensitive)
        assertEquals(1, modify.textEdits.size)
    }

    @Test
    fun treeSitterAdapterRoutesLocalRename() {
        val adapter = TreeSitterAdapter()
        val snap = snap("Foo.java" to "class Foo { Foo() {} }")
        val request = RefactoringRequest(
            operation = "localRename",
            arguments = mapOf("file" to "Foo.java", "from" to "Foo", "to" to "Bar"),
            snapshot = snap,
        )
        val plan = adapter.applyRefactoring(request)
        assertEquals(PatchStatus.PREVIEW, plan.status)
        assertTrue(plan.affectedFiles.isNotEmpty())
    }

    @Test
    fun csharpVerbatimStringOccurrencesAreSkipped() {
        val snap = snap("Config.cs" to "string s = @\"Foo is here\"; Foo real;")
        val plan = planner.preview(snap, Paths.get("Config.cs"), "Foo", "Bar")
        assertEquals(PatchStatus.PREVIEW, plan.status)
        val modify = plan.workspaceEdit.edits.single() as org.refactorkit.core.FileEdit.Modify
        assertEquals(1, modify.textEdits.size, "Only 'Foo real' should be renamed; verbatim string content is skipped")
    }

    @Test
    fun externalLspAdapterReturnsEmptyWhenNotRunning() {
        val adapter = ExternalLspAdapter("typescript", listOf("nonexistent-lsp"))
        // adapter is not started; should return empty gracefully
        assertTrue(adapter.findReferences(org.refactorkit.core.SymbolId("x::Foo")).isEmpty())
        val sym = adapter.resolveSymbol(
            org.refactorkit.core.SourceLocation(
                Paths.get("Foo.ts"),
                org.refactorkit.core.SourceRange(
                    org.refactorkit.core.SourcePosition(0, 0),
                    org.refactorkit.core.SourcePosition(0, 3),
                ),
            )
        )
        assertIs<org.refactorkit.core.SymbolResolution>(sym)
        assertEquals(null, sym.symbol)
    }

    @Test
    fun treeSitterAdapterRefusesUnknownOperation() {
        val adapter = TreeSitterAdapter()
        val snap = snap("Foo.java" to "class Foo {}")
        val request = RefactoringRequest(operation = "renameClass", arguments = emptyMap(), snapshot = snap)
        val plan = adapter.applyRefactoring(request)
        assertEquals(PatchStatus.REFUSED, plan.status)
    }
}
