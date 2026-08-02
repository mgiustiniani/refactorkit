package org.refactorkit.java

import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.ProjectSnapshot

/**
 * Stateless direct-library planner for one proven direct-child Maven module rename.
 * Directory and artifact identity are independent caller intents; neither is inferred.
 */
class JavaRenameMavenModulePlanner {
    companion object {
        /** Exact managed-operation identity; this constant grants no apply authority. */
        const val OPERATION = "java.renameMavenModule"

        /** Exact operation-owned diagnostics identity; this constant grants no provider authority. */
        const val DIAGNOSTICS_GATE_ID = "java-rename-maven-module-staged-reactor-v1"
    }

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
