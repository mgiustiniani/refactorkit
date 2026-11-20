# Java extractField capability contract — approved change 001

- Change ID: `REQ-JAVA-EXTRACT-FIELD-001-APPROVED-CHANGE-001`
- Parent requirements: `REQ-JAVA-EXTRACT-FIELD-001` and
  `REQ-JAVA-CAPABILITY-TRUTH-001`
- Recorded at: `2026-10-22`
- Approval authority: the user's explicit reply, “Override BDD 10 file”.
- Scope: reconcile the already implemented bounded `extractField` mutation with
  the canonical API 0.2 capability and request contract; no runtime behavior or
  mutation authority is widened.

## Approved budget override

The default §18.6 maximum of eight changed files is increased to exactly ten for
the combined bounded `extractField` delivery, including this approval record.
The canonical API contract is the ninth file; this record is the tenth. No
additional changed file is authorized by this override.

The ten files are:

1. `features/java-extract-field.feature`
2. `modules/refactorkit-cli/src/test/kotlin/org/refactorkit/cli/previewcommand/JavaRefactoringPreviewCommandSurfaceCucumberTest.kt`
3. `modules/refactorkit-java/src/main/kotlin/org/refactorkit/java/JavaExtractConstantPlanner.kt`
4. `modules/refactorkit-java/src/main/kotlin/org/refactorkit/java/JavaLanguageAdapter.kt`
5. `modules/refactorkit-java/src/main/kotlin/org/refactorkit/java/JavaAdapterRegistration.kt`
6. `modules/refactorkit-daemon/src/main/kotlin/org/refactorkit/daemon/DaemonSession.kt`
7. `modules/refactorkit-mcp/src/main/kotlin/org/refactorkit/mcp/McpSession.kt`
8. `docs/arc42/appendix-requirements.adoc`
9. `docs/api-0.2-contract.json`
10. this approved-change record

The capability-contract slice uses one existing capability-truth scenario, one
expanded case, one shared runner, four exact step reuses, no new step
definitions, zero runner ambiguity, and zero duplicate expressions in its
bounded effective glue root. Run artifacts and analyzer views are local
qualification evidence and are not additional tracked files in this budget.

## Approved delta and boundary

- Publish `extractField` as `stable` / `managed-stable` with compiler evidence
  in the daemon Java refactoring catalogue and its operation request contract.
- Preserve the existing `extractField` preview, daemon/MCP Apply, staged
  diagnostics, approval, and exact rollback boundary.
- The operation remains limited to one complete JDT-bound primitive-`int`
  compile-time constant expression inside a non-static method of a top-level
  ordinary class, producing a private final instance field.
- Do not imply dirty-buffer/LSP mutation, broader field extraction, or
  packaged/native IDE acceptance.
- No dependency, CLI operation, or production code change is included in this
  contract reconciliation.

