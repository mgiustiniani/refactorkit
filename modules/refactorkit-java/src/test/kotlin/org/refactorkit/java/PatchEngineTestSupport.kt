package org.refactorkit.java

import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.ProjectSnapshot

internal fun PatchEngine.apply(plan: PatchPlan, snapshot: ProjectSnapshot): ApplyResult = apply(
    plan,
    snapshot,
    ApplyAuthorization.explicit("java-test"),
    DiagnosticsGate.enabled("java-jdt-test", JavaLanguageAdapter()::diagnostics),
)
