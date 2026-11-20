# Java extractMethod managed slice — approved change 001

- Change ID: `REQ-JAVA-EXTRACT-METHOD-CHAR-001-APPROVED-CHANGE-001`
- Parent requirement: `REQ-JAVA-EXTRACT-METHOD-CHAR-001` (C-EXTRACT)
- Approval authority: the user's explicit instruction, “ok colma il gap”.
- Scope: one requirement-bound Story scenario qualifying the bounded Java
  extractMethod operation for managed daemon/MCP use.

## Approved budget override

The default §18.6 changed-file maximum of 8 is increased to exactly 10 tracked
workspace files for this slice, including this approved-change record. The
acceptance remains one active scenario and one expanded case; it adds no step
definitions and reuses all seven effective steps.

The numeric override is needed to keep the Java planner and operation catalogue,
the requirement feature and its existing runner/glue, the test classpath, the
API contract, the integration contract, the operation guide, and the ARC42
integrated requirements narrative consistent in one reviewable slice. ARC42
must remain the central architecture narrative under §18.8, so omitting it to
meet the default file count would leave the managed status unintegrated.

The ten files in scope are:

1. `modules/refactorkit-java/src/main/kotlin/org/refactorkit/java/JavaExtractMethodPlanner.kt`
2. `modules/refactorkit-java/src/main/kotlin/org/refactorkit/java/JavaAdapterRegistration.kt`
3. `features/java-extract-method-characterization.feature`
4. `modules/refactorkit-jvm/build.gradle.kts`
5. `modules/refactorkit-jvm/src/test/kotlin/org/refactorkit/jvm/extractmethod/KotlinJvmExtractMethodCharacterizationSteps.kt`
6. `docs/api-0.2-contract.json`
7. `docs/integration-contracts.md`
8. `docs/extract-method.md`
9. `docs/arc42/appendix-requirements.adoc`
10. this approved-change record

Generated inventories, logs, reports, and the hash-bound receipt under
`.refactorkit/runs/java-extract-method-managed-r001/` are local qualification
evidence and are not tracked workspace files in this budget.

## Approved delta

- Managed `extractMethod` is limited to a JDT-binding-backed single initialized
  local declaration with resolved enclosing-parameter inputs, one denotable
  output, and checked-exception propagation.
- Staged JDT binding validation and diagnostics run before preview acceptance;
  daemon and MCP cover preview, managed Apply, and exact rollback.
- Legacy lexical previews remain review-only and PatchEngine rejects their
  managed Apply before mutation or transaction creation.
- Broader selections and data-flow shapes remain open. This slice does not
  qualify custom LSP transactional Apply, packaged/native consumers, or
  independent quality review.

The ARC42 requirements appendix remains the integrated architecture authority.
The scenario identity, exact feature hash, glue inventories, and test results are
recorded in `.refactorkit/runs/java-extract-method-managed-r001/gherkin-receipt.json`.
