package org.refactorkit.java

import org.refactorkit.core.AuthoritativeDiagnosticsProvider
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.java.JavaRenameMavenModuleContract.DESCRIPTOR_UNAVAILABLE
import org.refactorkit.java.JavaRenameMavenModuleContract.DIAGNOSTICS_GATE_ID
import org.refactorkit.java.JavaRenameMavenModuleContract.PlannerRefusal
import org.refactorkit.java.JavaRenameMavenModuleContract.refused

/** Fieldless coordinator for bounded Maven module-rename preview and diagnostics-gate construction. */
internal object JavaRenameMavenModuleAuthority {
    fun preview(
        snapshot: ProjectSnapshot,
        oldModuleDir: String,
        newModuleDir: String,
        newArtifactId: String?,
    ): PatchPlan = try {
        JavaRenameMavenModulePlanning.previewAuthorized(snapshot, oldModuleDir, newModuleDir, newArtifactId)
    } catch (refusal: PlannerRefusal) {
        refused(snapshot, refusal.code, refusal.message.orEmpty())
    } catch (failure: Exception) {
        refused(
            snapshot,
            DESCRIPTOR_UNAVAILABLE,
            "Bounded offline Maven module-rename planning failed (${failure::class.simpleName ?: "failure"})",
        )
    }

    fun diagnosticsGate(plan: PatchPlan): DiagnosticsGate {
        val authority = JavaRenameMavenModuleEvidence.gateAuthority(plan)
        return DiagnosticsGate.authoritative(
            DIAGNOSTICS_GATE_ID,
            AuthoritativeDiagnosticsProvider { candidate ->
                JavaRenameMavenModuleStaging.authoritativeEvaluation(candidate, authority)
            },
        )
    }
}
