package org.refactorkit.java

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourceRange
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.TextEdit
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.TextEdits
import java.nio.file.Path

/**
 * Renames a Maven module: updates its directory, artifactId in its POM,
 * the parent `<modules>` entry, and all dependency references across the reactor.
 *
 * All changes are explicit, independently previewable arguments — never inferred.
 */
class JavaRenameMavenModulePlanner {
    fun preview(
        snapshot: ProjectSnapshot,
        oldModuleDir: String,
        newModuleDir: String,
        newArtifactId: String? = null,
    ): PatchPlan {
        val oldDir = safeRelative(Path.of(oldModuleDir)) ?: return refused(snapshot,
            "mavenOwnership.sourceUnrecognized", "Old module directory must be safe workspace-relative: $oldModuleDir")
        val newDir = safeRelative(Path.of(newModuleDir)) ?: return refused(snapshot,
            "mavenOwnership.destinationUnrecognized", "New module directory must be safe workspace-relative: $newModuleDir")
        if (oldDir == newDir) return refused(snapshot,
            "mavenOwnership.destinationUnrecognized", "Old and new module directories are identical")

        // Find the module's POM
        val oldPom = snapshot.auxiliaryFiles.singleOrNull {
            it.languageId == "maven-pom" && it.path.normalize().startsWith(oldDir)
        } ?: return refused(snapshot, "mavenOwnership.descriptorUnavailable",
            "Module POM not found: $oldDir/pom.xml")

        val oldArtifactId = extractModuleArtifactId(oldPom.content) ?: return refused(snapshot,
            "mavenOwnership.descriptorUnavailable", "Cannot determine old artifactId from $oldPom.path")
        val artifactId = newArtifactId ?: newDir.fileName.toString()

        // Find the parent POM
        val parentPomPath = findParentPom(snapshot, oldPom) ?: return refused(snapshot,
            "mavenOwnership.descriptorUnavailable", "Cannot locate parent POM for module $oldModuleDir")

        val edits = mutableListOf<FileEdit>()

        // 1. Rename module directory (move all files)
        val moduleFiles = snapshot.trackedFiles.filter { it.path.normalize().startsWith(oldDir) }
        moduleFiles.forEach { file ->
            val newPath = newDir.resolve(oldDir.relativize(file.path.normalize())).normalize()
            edits += FileEdit.Rename(file.path, newPath)
        }

        // 2. Update artifactId in module's POM content
        val pomContent = oldPom.content
        val artifactIdTag = "<artifactId>$oldArtifactId</artifactId>"
        val artifactIdIndex = pomContent.indexOf(artifactIdTag)
        if (artifactIdIndex >= 0) {
            val before = pomContent.substring(0, artifactIdIndex)
            val lineNum = before.count { it == '\n' }
            val col = artifactIdIndex - before.lastIndexOf('\n') - 1
            val endCol = col + artifactIdTag.length
            edits += FileEdit.Modify(oldPom.path, listOf(TextEdit(
                SourceRange(SourcePosition(lineNum, col), SourcePosition(lineNum, endCol)),
                "<artifactId>$artifactId</artifactId>",
            )))
        }

        // 3. Update parent `<modules>` entry
        val parentPomContent = snapshot.auxiliaryFiles
            .singleOrNull { it.path.normalize() == parentPomPath }?.content ?: return refused(snapshot,
            "mavenOwnership.descriptorUnavailable", "Parent POM not found: $parentPomPath")
        val oldModuleEntry = "<module>$oldDir.fileName.toString()</module>"
        val newModuleEntry = "<module>$newDir.fileName.toString()</module>"
        val moduleIndex = parentPomContent.indexOf(oldModuleEntry)
        if (moduleIndex >= 0) {
            val before = parentPomContent.substring(0, moduleIndex)
            val lineNum = before.count { it == '\n' }
            val col = moduleIndex - before.lastIndexOf('\n') - 1
            edits += FileEdit.Modify(parentPomPath, listOf(TextEdit(
                SourceRange(SourcePosition(lineNum, col), SourcePosition(lineNum, col + oldModuleEntry.length)),
                newModuleEntry,
            )))
        }

        // 4. Update dependency references in all POMs
        val dependencyTag = "<artifactId>$oldArtifactId</artifactId>"
        snapshot.auxiliaryFiles.filter { it.languageId == "maven-pom" && it.path.normalize() != oldPom.path }.forEach { pom ->
            val depIndex = pom.content.indexOf(dependencyTag)
            if (depIndex >= 0) {
                val before = pom.content.substring(0, depIndex)
                val lineNum = before.count { it == '\n' }
                val col = depIndex - before.lastIndexOf('\n') - 1
                edits += FileEdit.Modify(pom.path, listOf(TextEdit(
                    SourceRange(SourcePosition(lineNum, col), SourcePosition(lineNum, col + dependencyTag.length)),
                    "<artifactId>$artifactId</artifactId>",
                )))
            }
        }

        val workspaceEdit = WorkspaceEdit(edits)
        val before = emptyList<Diagnostic>()
        val after = emptyList<Diagnostic>()

        return PatchPlan(
            operation = "java.renameMavenModule",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            requiresUserApproval = true,
            summary = "Rename Maven module '$oldArtifactId' to '$artifactId': move directory, update artifactId, parent modules, and ${edits.filterIsInstance<FileEdit.Modify>().size - 1} dependency reference(s) in other POMs",
            affectedFiles = edits.map { it.path }.toSet() + edits.filterIsInstance<FileEdit.Rename>().map { it.newPath }.toSet(),
            workspaceEdit = workspaceEdit,
            diagnosticsBefore = before,
            diagnosticsAfterPreview = after,
            warnings = listOf(
                "Module directory renamed, artifactId updated, parent modules entry updated.",
                "All dependency references to old artifactId are updated across the reactor.",
            ),
            riskLevel = RiskLevel.MEDIUM,
            evidence = RefactoringEvidence.STRUCTURAL,
        )
    }

    private fun safeRelative(path: Path): Path? = path.normalize().takeIf {
        path.toString().isNotBlank() && !it.isAbsolute && !it.startsWith("..")
    }

    private fun extractModuleArtifactId(pomContent: String): String? {
        val regex = Regex("""<artifactId>\s*([^<]+)\s*</artifactId>""")
        return regex.find(pomContent)?.groupValues?.get(1)?.trim()
    }

    private fun findParentPom(snapshot: ProjectSnapshot, modulePom: org.refactorkit.core.SourceFile): Path? {
        val content = modulePom.content
        val relPathRegex = Regex("""<relativePath>\s*([^<]+)\s*</relativePath>""")
        val relPath = relPathRegex.find(content)?.groupValues?.get(1)?.trim() ?: return null
        return modulePom.path.parent.normalize().resolve(relPath).normalize()
    }

    private fun refused(
        snapshot: ProjectSnapshot,
        code: String,
        reason: String,
    ) = PatchPlan(
        operation = "java.renameMavenModule",
        status = PatchStatus.REFUSED,
        snapshotHash = snapshot.hash,
        confidence = 0.0,
        requiresUserApproval = false,
        summary = reason,
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        diagnosticsBefore = emptyList(),
        diagnosticsAfterPreview = emptyList(),
        warnings = listOf(reason),
        riskLevel = RiskLevel.HIGH,
        evidence = RefactoringEvidence.STRUCTURAL,
        refusalCode = code,
    )
}
