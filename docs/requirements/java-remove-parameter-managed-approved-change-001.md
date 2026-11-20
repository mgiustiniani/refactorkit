# Java changeSignature.removeParameter managed qualification — approved change 001

- Change ID: `REQ-JAVA-CHANGE-SIGNATURE-REMOVE-001-APPROVED-CHANGE-001`
- Parent requirement: [Java JDT change-signature requirement](java-jdt-change-signature.md)
- Recorded at: `2026-10-24`
- Approval authority: the user's explicit instruction, “ok colma il gap”, together with the previously approved numeric BDD override, “Override BDD 10 file”.
- Scope: qualify the already implemented bounded JDT `removeParameter` planner for managed engine Apply; preserve fail-closed behavior outside its proven shape.

## BDD budget and approved override

The default §18.6 maximum of eight changed files is increased to exactly ten for
this delivery, including this approved-change record. The user approved the
numeric limit of ten. The additional files keep production mutation authority,
its operation-bound Story, the API contract, requirement and integrated
architecture/reporting status consistent. No eleventh changed file is included
in this slice.

1. `modules/refactorkit-java/src/main/kotlin/org/refactorkit/java/JavaChangeSignaturePlanner.kt`
2. `modules/refactorkit-java/src/main/kotlin/org/refactorkit/java/JavaAdapterRegistration.kt`
3. `features/java-capability-truth.feature`
4. `modules/refactorkit-cli/src/test/kotlin/org/refactorkit/cli/previewcommand/JavaRefactoringPreviewCommandSurfaceCucumberTest.kt`
5. `docs/api-0.2-contract.json`
6. `docs/requirements/java-jdt-change-signature.md`
7. `docs/integration-contracts.md`
8. `docs/arc42/appendix-requirements.adoc`
9. `docs/releases/v0.8.0-progress.md`
10. this approved-change record

The slice selects one scenario and one expanded case. Its pre-edit effective-glue
inventory records two exact step reuses, three new step definitions, zero shared
reuse candidates, zero ambiguous steps, one runner and zero duplicate
expressions. The post-edit inventory records five exact reuses, zero new steps,
zero ambiguity, one runner and zero duplicate expressions. Receipts and run
artifacts under `.refactorkit/runs/java-remove-parameter-managed-r001/` are
qualification evidence, not additional tracked source files in the BDD budget.

## Approved delta and authority boundary

- Register `changeSignature.removeParameter` as `STABLE` / `MANAGED_STABLE` with compiler evidence, consistent across Java adapter registration, daemon discovery, SDK discovery, and API 0.2.
- Permit managed removal only when JDT identifies one exact non-public method, the selected parameter has no bound body uses, every affected in-workspace invocation has complete binding and expected arity, and staged JDT identity/diagnostic checks pass.
- Discard a call-site argument only when it is a literal or exact JDT-bound local/parameter read. Refuse calls, field/array reads, unproven expressions, call-argument comments, method references, public/protected boundaries, and incomplete hierarchy shapes before mutation. Hierarchy changes continue to require explicit external-consumer-risk acceptance and the same purity guard.
- The operation-bound Story verifies daemon and MCP preview, diagnostics, managed Apply, exact rollback, capability/API agreement, and effectful-argument refusal without transaction creation.
- No authority is granted to the RefactorKit custom LSP signature command or standard `textDocument/rename`; packaged four-platform and native IDE qualification remain open.
