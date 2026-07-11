package org.refactorkit.testkit

import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.java.JavaLanguageAdapter

internal fun PatchEngine.apply(plan: PatchPlan, snapshot: ProjectSnapshot): ApplyResult = apply(
    plan,
    snapshot,
    ApplyAuthorization.explicit("testkit-test"),
    DiagnosticsGate.enabled("java-jdt-test", JavaLanguageAdapter()::diagnostics),
)
