package org.refactorkit.core

import java.nio.file.Path

/**
 * Stateless, language-neutral workspace snapshot composition for
 * REQ-WORKSPACE-SNAPSHOT-COMPOSER-001..005.
 */
class WorkspaceSnapshotComposer {
    /** Acquire the source inventory once, before a base scanner may read workspace inputs. */
    fun composeInventoryFirst(
        root: Path,
        authoritativeBaseScanner: (Path) -> ProjectSnapshot,
        sourceInventoryScanner: (Path) -> ProjectSnapshot,
        conditionalEvidenceAttacher: (ProjectSnapshot) -> ProjectSnapshot,
        finalEvidenceAttacher: ((ProjectSnapshot) -> ProjectSnapshot)? = null,
    ): ProjectSnapshot {
        val inventory = sourceInventoryScanner(root)
        return compose(
            root, authoritativeBaseScanner, { inventory }, conditionalEvidenceAttacher, finalEvidenceAttacher,
        )
    }

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
            val auxiliaryByPath = base.auxiliaryFiles.associateBy { it.path.normalize() }
            val promotedPaths = sourceInventory.files.mapNotNull { source ->
                val path = source.path.normalize()
                val auxiliary = auxiliaryByPath[path] ?: return@mapNotNull null
                if (auxiliary.content != source.content) throw OwnedSourceCaptureException(
                    OwnedSourceCaptureFailure.INPUT_CHANGED, root.resolve(path).normalize(),
                )
                path
            }.toSet()
            val overlaid = base.copy(
                files = (base.files + sourceInventory.files)
                    .associateBy { it.path.normalize() }
                    .values
                    .sortedBy { it.path.toString() },
                sourceExtensions = base.sourceExtensions + sourceInventory.sourceExtensions,
                ignoredDirectories = base.ignoredDirectories + sourceInventory.ignoredDirectories,
                auxiliaryFiles = if (promotedPaths.isEmpty()) base.auxiliaryFiles
                    else base.auxiliaryFiles.filterNot { it.path.normalize() in promotedPaths },
            )
            conditionalEvidenceAttacher(overlaid)
        }
        return finalEvidenceAttacher?.invoke(conditionalOutput) ?: conditionalOutput
    }
}
