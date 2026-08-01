package org.refactorkit.core

import java.nio.file.Path

/**
 * Stateless, language-neutral workspace snapshot composition for
 * REQ-WORKSPACE-SNAPSHOT-COMPOSER-001..005.
 */
class WorkspaceSnapshotComposer {
    fun compose(
        root: Path,
        authoritativeBaseScanner: (Path) -> ProjectSnapshot,
        sourceInventoryScanner: (Path) -> ProjectSnapshot,
        conditionalEvidenceAttacher: (ProjectSnapshot) -> ProjectSnapshot,
        finalEvidenceAttacher: ((ProjectSnapshot) -> ProjectSnapshot)? = null,
    ): ProjectSnapshot {
        val base = authoritativeBaseScanner(root)
        val sourceInventory = sourceInventoryScanner(root)
        val conditionalOutput = if (sourceInventory.files.isEmpty()) {
            base
        } else {
            val overlaid = base.copy(
                files = (base.files + sourceInventory.files)
                    .associateBy { it.path.normalize() }
                    .values
                    .sortedBy { it.path.toString() },
                sourceExtensions = base.sourceExtensions + sourceInventory.sourceExtensions,
                ignoredDirectories = base.ignoredDirectories + sourceInventory.ignoredDirectories,
            )
            conditionalEvidenceAttacher(overlaid)
        }
        return finalEvidenceAttacher?.invoke(conditionalOutput) ?: conditionalOutput
    }
}
