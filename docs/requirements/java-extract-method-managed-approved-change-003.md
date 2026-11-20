# Java Extract Method preceding-local inputs — approved change 003

- Change ID: `REQ-JAVA-EXTRACT-METHOD-CHAR-001-APPROVED-CHANGE-003`
- Parent requirement: `REQ-JAVA-EXTRACT-METHOD-CHAR-001` (C-EXTRACT)
- Approval authority: the user's instruction to continue the bounded provider implementation and the explicit direction to capture read-only preceding direct-local values while refusing unsupported writes.
- Scope: extend the existing JDT-bound managed Extract Method shape to capture a resolved local declared by a direct method-body statement before the selected range. No duplicate requirement or operation is introduced.

## BDD slice budget

This slice changes eight tracked files, within the §18.6 maximum of eight:

1. `modules/refactorkit-java/src/main/kotlin/org/refactorkit/java/JavaExtractMethodPlanner.kt`
2. `features/java-extract-method-characterization.feature`
3. `modules/refactorkit-jvm/src/test/kotlin/org/refactorkit/jvm/extractmethod/KotlinJvmExtractMethodCharacterizationSteps.kt`
4. `docs/java-capabilities.md`
5. `docs/integration-contracts.md`
6. `docs/extract-method.md`
7. `docs/arc42/appendix-requirements.adoc`
8. this approved-change record

One existing scenario is selected, with one expanded case, eight exact reused steps, no new step definitions, and no runner ambiguity. The effective-glue inventory is recorded with the selected scenario receipt under `.refactorkit/runs/java-extract-method-local-input-r001/`.

## Approved delta

- A selection may read a local declared by a preceding direct `VariableDeclarationStatement` in the same resolved method body. The exact JDT variable binding must match; nested declarations, recovered bindings, fields, and parameters are not treated as captured locals.
- Used captured locals are passed to the helper in declaration order and only when their types are denotable. The helper call and staged JDT binding checks verify names and ordered parameter types.
- Writes, increments, or decrements to any local declared outside the selection remain refused. Unsupported types, unresolved bindings, multiple outputs, and existing control-flow refusals remain unchanged.
- The selected Story verifies compilation and runtime equivalence for a preceding local input, daemon/MCP preview, staged diagnostics, Apply, exact rollback, lexical-fallback refusal, and a managed-planner refusal when the selection writes to a preceding local.
- `extractMethod` retains its existing `STABLE`/`MANAGED_STABLE` operation authority only for binding-backed plans satisfying the planner checks. This slice does not qualify additional IDE, LSP, CLI-package, or platform surfaces.

Test receipt and pre/post analyzer inventories are generated under `.refactorkit/runs/java-extract-method-local-input-r001/`. The selected Story passed with Gradle 9.6.1 and JDK 21 using the `:modules:refactorkit-jvm:test --tests org.refactorkit.jvm.extractmethod.KotlinJvmExtractMethodCharacterizationCucumberTest --offline --no-daemon` task and the scenario-specific `cucumber.filter.tags` system property. The test invokes the real JDT-backed adapter, daemon/MCP dispatch, Java compiler and exact rollback checks. Status remains bounded to this selected Story; no broader package or native IDE qualification is inferred.
