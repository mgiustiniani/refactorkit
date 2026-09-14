package org.refactorkit.c

import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.refactorkit.core.WorkspaceEdit
import java.nio.file.Path

/**
 * Bounded C component relocation: move a set of C source/header files and their
 * component directory to a new location, updating literal include paths and
 * bindings. Refuses when the component directory is also a build/manifest target
 * or when include paths would become ambiguous (macro-computed includes).
 */
class CRelocateComponentPlanner {
    fun preview(snapshot: ProjectSnapshot, componentDir: Path, newDir: Path): PatchPlan {
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        val oldDir = componentDir.normalize()
        val newDirNorm = newDir.normalize()
        if (!root.resolve(oldDir).normalize().startsWith(root) || !root.resolve(newDirNorm).normalize().startsWith(root)) {
            return refused(snapshot, "Component relocation is outside the workspace")
        }
        if (oldDir == newDirNorm) return refused(snapshot, "Component directory is unchanged")
        if (isBuildManifestTarget(snapshot, oldDir)) {
            return refused(snapshot, "Component directory is a build/manifest target; relocation is refused")
        }
        val files = snapshot.files
            .filter { it.languageId in setOf("c", "cpp", "objective-c") && it.path.normalize().startsWith(oldDir) }
            .sortedBy { it.path.toString() }
        if (files.isEmpty()) return refused(snapshot, "No C source/header files under component directory '$oldDir'")
        if (snapshot.files.any { it.path.normalize().startsWith(newDirNorm) }) {
            return refused(snapshot, "Component target directory already contains files; relocation is refused")
        }
        val macroInclude = files.any { file ->
            CIncludeDirectiveParser().parse(file.content).any { it.kind == CIncludeKind.MACRO || isMacroTarget(it.target) }
        }
        if (macroInclude) return refused(snapshot, "Macro-computed includes could target moved files; relocation is refused")

        val edits = mutableListOf<FileEdit>()
        val moved = mutableMapOf<Path, Path>()
        for (file in files) {
            val rel = oldDir.relativize(file.path.normalize())
            val newFile = newDirNorm.resolve(rel).normalize()
            if (snapshot.files.any { it.path.normalize() == newFile }) {
                return refused(snapshot, "Move target collides with an existing file: $newFile")
            }
            edits += FileEdit.Rename(file.path.normalize(), newFile)
            moved[file.path.normalize()] = newFile
        }
        val includeEdits = buildIncludeEdits(snapshot, moved)
        includeEdits.forEach { (file, textEdits) -> edits += FileEdit.Modify(file, textEdits) }

        return PatchPlan(
            operation = "relocateComponent",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            requiresUserApproval = true,
            summary = "Relocate component '$oldDir' to '$newDirNorm' (${files.size} file(s)) and update ${includeEdits.values.sumOf { it.size }} literal include(s)",
            affectedFiles = WorkspaceEdit(edits).affectedFiles(),
            workspaceEdit = WorkspaceEdit(edits),
            diagnosticsBefore = emptyList(),
            diagnosticsAfterPreview = emptyList(),
            warnings = listOf("Literal includes updated; macro-computed includes and build bindings are not rewritten."),
            riskLevel = RiskLevel.MEDIUM,
            evidence = RefactoringEvidence.LANGUAGE_SERVER,
        )
    }

    private fun buildIncludeEdits(snapshot: ProjectSnapshot, moved: Map<Path, Path>): Map<Path, List<TextEdit>> {
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        val editsByFile = mutableMapOf<Path, MutableList<TextEdit>>()
        for (source in snapshot.files) {
            if (source.languageId !in setOf("c", "cpp", "objective-c")) continue
            val directives = CIncludeDirectiveParser().parse(source.content)
            for (directive in directives) {
                val oldTarget = root.resolve(source.path.parent ?: Path.of("")).resolve(directive.target).normalize()
                val match = moved.entries.firstOrNull { (oldFile, _) ->
                    oldFile.normalize() == oldTarget.normalize() || directive.target == oldFile.fileName.toString()
                } ?: continue
                val newFile = moved[match.key]!!
                val newTarget = root.relativize(newFile).toString().replace('\\', '/')
                val lineText = source.content.lines().getOrNull(directive.line - 1) ?: continue
                val idx = lineText.indexOf(directive.target)
                if (idx < 0) continue
                val start = SourcePosition(directive.line - 1, idx)
                val end = SourcePosition(directive.line - 1, idx + directive.target.length)
                editsByFile.getOrPut(source.path.normalize()) { mutableListOf() } += TextEdit(SourceRange(start, end), newTarget)
            }
        }
        return editsByFile.mapValues { it.value.toList() }
    }

    private fun isMacroTarget(target: String): Boolean =
        !target.contains('.') || target.all { !it.isLowerCase() }

    private fun isBuildManifestTarget(snapshot: ProjectSnapshot, dir: Path): Boolean {
        val name = dir.fileName.toString()
        return snapshot.files.any { file ->
            val isManifest = file.path.fileName.toString() in setOf("CMakeLists.txt", "meson.build", "BUILD", "Makefile", "configure.ac")
            isManifest && file.content.contains(name)
        }
    }

    private fun refused(snapshot: ProjectSnapshot, message: String) = PatchPlan(
        operation = "relocateComponent",
        status = PatchStatus.REFUSED,
        snapshotHash = snapshot.hash,
        confidence = 0.0,
        requiresUserApproval = false,
        summary = message,
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        diagnosticsBefore = emptyList(),
        diagnosticsAfterPreview = emptyList(),
        warnings = listOf(message),
        riskLevel = RiskLevel.HIGH,
        evidence = RefactoringEvidence.STRUCTURAL,
    )
}
