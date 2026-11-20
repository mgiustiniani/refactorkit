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
import java.nio.file.Files
import java.nio.file.LinkOption
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
        val generated = files.firstOrNull { isGenerated(it.path) }
        if (generated != null) {
            return refused(snapshot, "Component relocation contains generated C source/header '${generated.path}'; relocation is refused")
        }
        if (snapshot.files.any { it.path.normalize().startsWith(newDirNorm) }) {
            return refused(snapshot, "Component target directory already contains files; relocation is refused")
        }
        val macroInclude = files.any { file ->
            CIncludeDirectiveParser().parse(file.content).any { it.kind == CIncludeKind.MACRO || isMacroTarget(it.target) }
        }
        if (macroInclude) return refused(snapshot, "Macro-computed includes could target moved files; relocation is refused")
        val movedPaths = files.map { it.path.normalize() }.toSet()
        val uncapturedInclude = findUncapturedLiteralInclude(snapshot, movedPaths, oldDir)
        if (uncapturedInclude != null) {
            return refused(snapshot, "Component relocation has uncaptured literal include target '$uncapturedInclude'; relocation is refused")
        }

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
        val cmakeBinding = CMakeLiteralBindings.relocate(snapshot, moved, oldDir, newDirNorm)
        if (cmakeBinding is CMakeBindingDecision.Refused) return refused(snapshot, cmakeBinding.reason)
        val includeEdits = buildIncludeEdits(snapshot, moved)
        includeEdits.forEach { (file, textEdits) -> edits += FileEdit.Modify(file, textEdits) }
        if (cmakeBinding is CMakeBindingDecision.Bound) {
            edits += FileEdit.Modify(Path.of("CMakeLists.txt"), cmakeBinding.edits)
        }

        return PatchPlan(
            operation = "relocateComponent",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            requiresUserApproval = true,
            summary = "Relocate component '$oldDir' to '$newDirNorm' (${files.size} file(s)) and update ${includeEdits.values.sumOf { it.size }} literal include(s)" +
                if (cmakeBinding is CMakeBindingDecision.Bound) " and root CMake target ${cmakeBinding.target}" else "",
            affectedFiles = WorkspaceEdit(edits).affectedFiles(),
            workspaceEdit = WorkspaceEdit(edits),
            diagnosticsBefore = emptyList(),
            diagnosticsAfterPreview = emptyList(),
            warnings = listOf(if (cmakeBinding is CMakeBindingDecision.Bound)
                "Root literal CMake binding updated; staged TU/configuration and consumer proof remain unqualified."
            else "Literal includes updated; build bindings without a root CMakeLists.txt are not qualified."),
            riskLevel = RiskLevel.HIGH,
            evidence = RefactoringEvidence.STRUCTURAL,
        )
    }

    private fun buildIncludeEdits(snapshot: ProjectSnapshot, moved: Map<Path, Path>): Map<Path, List<TextEdit>> {
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        val editsByFile = mutableMapOf<Path, MutableList<TextEdit>>()
        for (source in snapshot.files) {
            if (source.languageId !in setOf("c", "cpp", "objective-c")) continue
            val directives = CIncludeDirectiveParser().parse(source.content)
            val includingDir = root.resolve(source.path.parent ?: Path.of("")).normalize()
            // When the including file is itself relocated, the rewritten target must be
            // relative to its destination directory, not its current one.
            val includerDest = moved[source.path.normalize()] ?: source.path.normalize()
            val newIncludingDir = root.resolve(includerDest.parent ?: Path.of("")).normalize()
            for (directive in directives) {
                // Resolve quoted includes relative to the including file and angled
                // includes relative to the workspace root; never fall back to basename.
                val oldTarget = if (directive.kind == CIncludeKind.QUOTED) {
                    includingDir.resolve(directive.target).normalize()
                } else {
                    root.resolve(directive.target).normalize()
                }
                // Compare in one coordinate space: the snapshot may carry relative file
                // paths while the workspace root is absolute, so resolve the moved file
                // against the root before matching (otherwise rewrites would be a silent no-op).
                val match = moved.entries.firstOrNull { (oldFile, _) -> root.resolve(oldFile).normalize() == oldTarget.normalize() } ?: continue
                val newFile = root.resolve(moved[match.key]!!).normalize()
                // The rewritten path is relative to the including file's destination directory.
                val newTarget = newIncludingDir.relativize(newFile).toString().replace('\\', '/')
                val lineText = source.content.lines().getOrNull(directive.line - 1) ?: continue
                val idx = lineText.indexOf(directive.target)
                if (idx < 0) continue
                val start = SourcePosition(directive.line - 1, idx)
                val end = SourcePosition(directive.line - 1, idx + directive.target.length)
                // A file that is itself relocated must have its content edit recorded under
                // the destination path so it applies after the rename (Rename then Modify(new)).
                val editKey = moved[source.path.normalize()] ?: source.path.normalize()
                editsByFile.getOrPut(editKey) { mutableListOf() } += TextEdit(SourceRange(start, end), newTarget)
            }
        }
        return editsByFile.mapValues { it.value.toList() }
    }

    private fun isMacroTarget(target: String): Boolean =
        !target.contains('.') || target.all { !it.isLowerCase() }

    private fun findUncapturedLiteralInclude(
        snapshot: ProjectSnapshot,
        movedPaths: Set<Path>,
        componentDir: Path,
    ): Path? {
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        val componentRoot = root.resolve(componentDir).normalize()
        for (source in snapshot.files) {
            if (source.languageId !in setOf("c", "cpp", "objective-c")) continue
            val sourcePath = source.path.normalize()
            val sourceDirectory = root.resolve(sourcePath.parent ?: Path.of("")).normalize()
            for (directive in CIncludeDirectiveParser().parse(source.content)) {
                if (directive.kind == CIncludeKind.MACRO) continue
                val target = if (directive.kind == CIncludeKind.QUOTED) {
                    sourceDirectory.resolve(directive.target).normalize()
                } else {
                    root.resolve(directive.target).normalize()
                }
                if (!target.startsWith(componentRoot)) continue
                val relative = root.relativize(target).normalize()
                if (relative in movedPaths) continue
                if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
                    return relative
                }
            }
        }
        return null
    }

    private fun isGenerated(path: Path): Boolean {
        val name = path.fileName?.toString().orEmpty()
        return name.endsWith(".generated.c") || name.endsWith(".generated.h")
    }

    private fun isBuildManifestTarget(snapshot: ProjectSnapshot, dir: Path): Boolean {
        val name = dir.fileName.toString()
        return snapshot.files.any { file ->
            val isManifest = file.path.fileName.toString() in setOf("meson.build", "BUILD", "Makefile", "configure.ac")
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
