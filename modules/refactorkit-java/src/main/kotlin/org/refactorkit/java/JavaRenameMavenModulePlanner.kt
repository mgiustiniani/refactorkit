package org.refactorkit.java

import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.ProjectSnapshot

/**
 * Stateless direct-library planner for one proven direct-child Maven module rename.
 * Directory and artifact identity are independent caller intents; neither is inferred.
 */
class JavaRenameMavenModulePlanner {
    fun preview(
        snapshot: ProjectSnapshot,
        oldModuleDir: String,
        newModuleDir: String,
        newArtifactId: String? = null,
    ): PatchPlan = JavaRenameMavenModuleAuthority.preview(
        snapshot = snapshot,
        oldModuleDir = oldModuleDir,
        newModuleDir = newModuleDir,
        newArtifactId = newArtifactId,
    )

    /** Operation-owned apply/post-apply gate; every invocation rebuilds Maven offline before JDT diagnostics. */
    fun diagnosticsGate(plan: PatchPlan): DiagnosticsGate = JavaRenameMavenModuleAuthority.diagnosticsGate(plan)
}
