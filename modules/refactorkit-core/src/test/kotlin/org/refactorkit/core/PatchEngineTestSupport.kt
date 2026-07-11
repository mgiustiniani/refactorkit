package org.refactorkit.core

internal fun PatchEngine.apply(plan: PatchPlan, snapshot: ProjectSnapshot): ApplyResult = apply(
    plan,
    snapshot,
    ApplyAuthorization.explicit("core-test"),
    DiagnosticsGate.disabled("core-test"),
)

internal fun PatchEngine.apply(
    plan: PatchPlan,
    snapshot: ProjectSnapshot,
    authorization: ApplyAuthorization,
): ApplyResult = apply(plan, snapshot, authorization, DiagnosticsGate.disabled("core-test"))
