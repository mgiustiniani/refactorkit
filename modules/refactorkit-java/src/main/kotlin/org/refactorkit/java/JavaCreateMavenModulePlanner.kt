package org.refactorkit.java

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.TextEdit
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.exactBuildSourceRootOwnerships
import java.nio.file.Path

/**
 * Creates a new Maven module under an existing parent POM.
 *
 * Generates a new POM file, adds a `<module>` entry to the parent POM's
 * `<modules>` section, and creates the standard source directory structure.
 */
class JavaCreateMavenModulePlanner {
    fun preview(snapshot: ProjectSnapshot, moduleName: String, parentPomPath: Path): PatchPlan {
        val parentPom = snapshot.auxiliaryFiles.singleOrNull {
            it.languageId == "maven-pom" && it.path.normalize() == parentPomPath.normalize()
        } ?: return refused(snapshot, "mavenOwnership.descriptorUnavailable",
            "Parent POM not found: $parentPomPath")

        // Parse parent groupId, version from parent POM content
        val parentContent = parentPom.content
        val groupId = extractParentValue(parentContent, "groupId") ?: return refused(snapshot,
            "mavenOwnership.descriptorUnavailable", "Cannot determine parent groupId")
        val version = extractParentValue(parentContent, "version") ?: return refused(snapshot,
            "mavenOwnership.descriptorUnavailable", "Cannot determine parent version")
        val parentDir = parentPomPath.parent.normalize()
        val newPomPath = parentDir.resolve("$moduleName/pom.xml").normalize()

        // Check for existing module POM
        if (snapshot.auxiliaryFiles.any { it.path.normalize() == newPomPath }) {
            return refused(snapshot, "mavenOwnership.destinationUnrecognized",
                "Module already exists: $moduleName")
        }

        // Generate new POM content
        val newPomContent = buildString {
            append("<project><modelVersion>4.0.0</modelVersion>\n")
            append("  <parent><groupId>$groupId</groupId><artifactId>$parentDir.fileName.toString()</artifactId>")
            append("<version>$version</version><relativePath>../pom.xml</relativePath></parent>\n")
            append("  <artifactId>$moduleName</artifactId>\n")
            append("</project>\n")
        }

        // Create source directory marker file (module-info or placeholder)
        val sourceDir = newPomPath.parent.resolve("src/main/java").normalize()
        val edits = mutableListOf<FileEdit>()

        // Create the POM file
        edits += FileEdit.Create(newPomPath, newPomContent)

        // Add module to parent POM's <modules> section
        val modulesInsertion = insertModuleInParentPom(parentContent, moduleName) ?: return refused(
            snapshot, "mavenOwnership.descriptorUnavailable",
            "Cannot locate <modules> section in parent POM for insertion",
        )
        edits += FileEdit.Modify(parentPomPath, listOf(modulesInsertion))

        // Create directory structure (empty marker)
        val markerPath = sourceDir.resolve(".module-created").normalize()
        edits += FileEdit.Create(markerPath, "This directory marks the Maven module '$moduleName' source root.\n")

        val workspaceEdit = WorkspaceEdit(edits)
        val before = emptyList<Diagnostic>()
        val after = emptyList<Diagnostic>()
        return PatchPlan(
            operation = "java.createMavenModule",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            requiresUserApproval = true,
            summary = "Create Maven module '$moduleName' under parent POM $parentPomPath",
            affectedFiles = setOf(newPomPath, parentPomPath, markerPath),
            workspaceEdit = workspaceEdit,
            diagnosticsBefore = before,
            diagnosticsAfterPreview = after,
            warnings = listOf(
                "New module POM created with minimal configuration; add dependencies as needed.",
                "Source directory $sourceDir created; add Java compilation units.",
            ),
            riskLevel = RiskLevel.LOW,
            evidence = RefactoringEvidence.STRUCTURAL,
        )
    }

    /**
     * Insert a `<module>` element in the parent POM's `<modules>` section.
     * Returns a TextEdit that adds the new module name before the closing `</modules>`.
     */
    private fun insertModuleInParentPom(pomContent: String, moduleName: String): TextEdit? {
        val modulesClose = "</modules>"
        val closeIndex = pomContent.indexOf(modulesClose)
        if (closeIndex < 0) return null
        val beforeClose = pomContent.substring(0, closeIndex)
        val lastNewline = beforeClose.lastIndexOf('\n')
        val indent = if (lastNewline >= 0) pomContent.substring(lastNewline + 1, closeIndex).takeWhile { it.isWhitespace() } else ""
        val insertion = "\n${indent}<module>$moduleName</module>"
        return TextEdit(
            org.refactorkit.core.SourceRange(
                org.refactorkit.core.SourcePosition(
                    pomContent.substring(0, closeIndex).count { it == '\n' } + 1,
                    closeIndex - pomContent.substring(0, closeIndex).lastIndexOf('\n') - 1,
                ),
                org.refactorkit.core.SourcePosition(
                    pomContent.substring(0, closeIndex).count { it == '\n' } + 1,
                    closeIndex - pomContent.substring(0, closeIndex).lastIndexOf('\n') - 1,
                ),
            ),
            insertion,
        )
    }

    /** Extract a top-level XML element value from parent POM content. */
    private fun extractParentValue(content: String, element: String): String? {
        val regex = Regex("""<$element>\s*([^<]+)\s*</$element>""")
        return regex.find(content)?.groupValues?.get(1)?.trim()
    }

    private fun refused(
        snapshot: ProjectSnapshot,
        code: String,
        reason: String,
    ) = PatchPlan(
        operation = "java.createMavenModule",
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
