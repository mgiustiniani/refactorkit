package org.refactorkit.typescript.relocation

import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.treesitter.ExternalSemanticDiagnostics
import org.refactorkit.typescript.TypeScriptCompilerDiagnostics
import org.refactorkit.typescript.TypeScriptProjectModel
import org.refactorkit.typescript.TypeScriptSemanticToolchain
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Independent source/coordinate oracle for the existing all-import/export Story scenario. */
internal class TypeScriptRelocationImportsFixture {
    private val oldPath = Path.of("src/a.ts")
    val targetPath: Path = Path.of("src/nested/renamed.ts")
    val sources: Map<Path, String> = linkedMapOf(
        oldPath to "import { value } from './dep';\nexport const A = value;\n",
        Path.of("src/b.ts") to "import { A } from './a';\nconsole.log(A);\n",
        Path.of("src/barrel.ts") to "export { A } from './a';\n",
        Path.of("src/dep.ts") to "export const value = 1;\n",
        Path.of("src/unchanged.ts") to "export const untouched = 'unchanged';\n",
    )
    private val expectedEdits = linkedMapOf(
        oldPath to listOf(TextEdit(SourceRange(SourcePosition(0, 23), SourcePosition(0, 28)), "../dep")),
        Path.of("src/b.ts") to listOf(TextEdit(SourceRange(SourcePosition(0, 19), SourcePosition(0, 22)), "./nested/renamed")),
        Path.of("src/barrel.ts") to listOf(TextEdit(SourceRange(SourcePosition(0, 19), SourcePosition(0, 22)), "./nested/renamed")),
    )
    private val expectedSources = sources.filterKeys { it != oldPath } + mapOf(
        targetPath to "import { value } from '../dep';\nexport const A = value;\n",
        Path.of("src/b.ts") to "import { A } from './nested/renamed';\nconsole.log(A);\n",
        Path.of("src/barrel.ts") to "export { A } from './nested/renamed';\n",
    )
    private var baselineImage: Map<Path, String> = emptyMap()

    fun assertReady(snapshot: ProjectSnapshot, model: TypeScriptProjectModel, toolchain: TypeScriptSemanticToolchain) {
        assertEquals(sources, snapshot.files.associate { it.path to it.content })
        assertEquals("5.9.3", toolchain.provenance.typeScriptVersion)
        baselineImage = image(snapshot.workspace.root)
        assertCompilerClean(snapshot, model, toolchain)
    }

    fun assertRewrites(
        plan: PatchPlan,
        snapshot: ProjectSnapshot,
        model: TypeScriptProjectModel,
        toolchain: TypeScriptSemanticToolchain,
    ) {
        assertEquals(expectedEdits.keys, modifications(plan).map { it.path }.toSet(), "All compiler-updated sources must be retained")
        val rename = plan.workspaceEdit.edits.filterIsInstance<FileEdit.Rename>().single()
        assertEquals(FileEdit.Rename(oldPath, targetPath), rename)
        val oldModifyIndex = plan.workspaceEdit.edits.indexOfFirst { it is FileEdit.Modify && it.path == oldPath }
        assertTrue(oldModifyIndex < plan.workspaceEdit.edits.indexOf(rename), "Original-source coordinates must be applied before rename")
        val staged = WorkspaceEditSimulator.apply(snapshot, plan.workspaceEdit)
        assertEquals(expectedSources, staged.files.associate { it.path to it.content })
        assertCompilerClean(staged, model, toolchain)
    }

    fun assertAffectedFiles(plan: PatchPlan) = assertEquals(expectedEdits.keys + setOf(targetPath), plan.affectedFiles)

    fun assertOneModifyPerFile(plan: PatchPlan) {
        val modifies = modifications(plan)
        assertEquals(expectedEdits.size, modifies.size)
        assertEquals(expectedEdits.keys, modifies.map { it.path }.toSet())
    }

    fun assertExactEdits(plan: PatchPlan) =
        assertEquals(expectedEdits, modifications(plan).associate { it.path to it.textEdits })

    fun assertUnchangedFiles(plan: PatchPlan, root: Path) {
        assertEquals(expectedEdits.keys, modifications(plan).map { it.path }.toSet())
        assertTrue(baselineImage.isNotEmpty())
        assertEquals(baselineImage, image(root), "Preview and diagnostics must leave the entire original workspace unchanged")
    }

    private fun modifications(plan: PatchPlan) = plan.workspaceEdit.edits.filterIsInstance<FileEdit.Modify>()

    private fun assertCompilerClean(
        snapshot: ProjectSnapshot,
        model: TypeScriptProjectModel,
        toolchain: TypeScriptSemanticToolchain,
    ) {
        val auxiliary = listOf("tsconfig.json", "package.json").map { path ->
            SourceFile(Path.of(path), Files.readString(snapshot.workspace.root.resolve(path)), "json")
        }
        val result = TypeScriptCompilerDiagnostics(toolchain, model).analyze(snapshot, auxiliary)
        val available = assertIs<ExternalSemanticDiagnostics.Available>(result)
        assertEquals(emptyList(), available.diagnostics, "Real compiler diagnostics must be clean for the exact snapshot")
    }

    private fun image(root: Path): Map<Path, String> = Files.walk(root).use { paths ->
        paths.sorted().toList().associate { path ->
            check(!Files.isSymbolicLink(path)) { "Unexpected symbolic link in test workspace" }
            root.relativize(path) to if (Files.isDirectory(path)) "directory" else {
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
                    .joinToString("") { "%02x".format(it) }
            }
        }
    }
}
