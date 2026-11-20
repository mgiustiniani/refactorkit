# Java Extract Method multi-statement slice — approved change 002

- Change ID: `REQ-JAVA-EXTRACT-METHOD-CHAR-001-APPROVED-CHANGE-002`
- Parent requirement: `REQ-JAVA-EXTRACT-METHOD-CHAR-001` (C-EXTRACT)
- Approval authority: the user's instruction to prioritize IDE requests and extend engine-backed Extract Method planning with binding/control-flow, inputs/outputs, conflict validation, and operation-specific authority while refusing unsupported shapes.
- Scope: extend the existing managed Story to qualify one contiguous multi-statement Java selection; no duplicate requirement is introduced.

## BDD slice budget

This slice changes eight tracked files, within the default §18.6 maximum of eight:

1. `modules/refactorkit-java/src/main/kotlin/org/refactorkit/java/JavaExtractMethodPlanner.kt`
2. `features/java-extract-method-characterization.feature`
3. `modules/refactorkit-jvm/src/test/kotlin/org/refactorkit/jvm/extractmethod/KotlinJvmExtractMethodCharacterizationSteps.kt`
4. `modules/refactorkit-jvm/src/test/kotlin/org/refactorkit/jvm/extractmethod/KotlinJvmExtractMethodCharacterizationCucumberTest.kt`
5. `docs/extract-method.md`
6. `docs/integration-contracts.md`
7. `docs/arc42/appendix-requirements.adoc`
8. this approved-change record

One existing scenario is selected, with one expanded case, seven reused steps, no new step
definitions, and no runner ambiguity. No numeric budget override is needed. The hash-bound
pre-edit receipt and effective-glue inventory are under
`.refactorkit/runs/java-extract-method-multistatement-r001/`.

## Approved delta

- The JDT planner accepts a contiguous range of complete direct statements in one resolved
  method body when the selection contains local declarations.
- Resolved enclosing-method parameters read in the selected statements become helper parameters.
- Exactly one selected local used after the range becomes the helper result.
- Conditional control flow inside the selection is preserved; resolved checked exceptions are
  propagated through the helper signature.
- Writes to enclosing locals/parameters, multiple live-out locals, unresolved/recovered bindings,
  return/throw/break/continue/yield, and unsupported output shapes fail closed.
- Staged JDT binding checks and diagnostics validate the generated call and helper before the
  plan is accepted.
- Daemon and MCP retain `STABLE`/`MANAGED_STABLE` authority for the qualified engine shape and
  verify Apply plus exact rollback. Lexical fallback remains review-only.
- The custom RefactorKit LSP command remains experimental/client-managed without a transaction or
  rollback route. The reported native IDE offer remains one-line/proposal-only; IDE consumer
  acceptance is still open.

The current feature SHA, exact scenario result, analyzer hashes, and before/after inventories are
recorded in `.refactorkit/runs/java-extract-method-multistatement-r001/gherkin-receipt.json`.
