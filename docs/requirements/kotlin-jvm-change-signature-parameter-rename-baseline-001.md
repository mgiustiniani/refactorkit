# REQ-KOTLIN-CHANGE-SIGNATURE-001..003 Initial Requirements Baseline

- Receipt ID: `REQ-KOTLIN-CHANGE-SIGNATURE-001..003-BASELINE-001`
- Captured: 2026-08-16
- Baseline Git commit: `0d51cfc`
- Workflow: `bdd-java`
- Release ledger target: `docs/releases/v0.7.0-plan.md` K5 "Change signature across overrides, overloads, default/named arguments and Java callers" sub-row

## Initial user intent

Continue the K5 (Kotlin) band. Qualify the bounded `changeSignature.renameParameter` operation:
exact target/parameter identity (REQ-001), exact edits (REQ-002), and mixed staged K2+JDT proof (REQ-003).
Executable source: `docs/requirements/kotlin-jvm-change-signature-parameter-rename.md`
(REQ-KOTLIN-CHANGE-SIGNATURE-001..003). Feature: `features/kotlin-jvm-change-signature-parameter-rename.feature`
(33 expanded cases, 10 scenario outlines, feature-level `@not-implemented`).

## Approved scope

1. One compiler-catalogued Kotlin function and one exact value-parameter name; target identified by
   K2-to-JVM owner, JVM name and descriptor; parameter by callable identity plus zero-based ordinal.
   Same-name overloads and unrelated same-descriptor methods are NOT members of the target
   (REQ-001; family-incompleteness refusals).
2. The planner changes only the bound parameter declaration and references; same-name/same-descriptor
   methods excluded; external-consumer-risk approval; unsafe-name/conflict refusals; token-range/
   refusal codes; staged-regression refusals (REQ-002).
3. Mixed K2+JDT staged proof preserving Java caller bindings; mixed fail-closed refusals;
   apply/rollback via the lazy `kotlin-k2-java-jdt-change-signature` gate (REQ-003).
4. Multi-runner: single feature file; runner/glue differences via story-driven.

## Status

Initial status: `@not-implemented` (feature-level). Production planners
(`KotlinChangeSignaturePlanner` + `KotlinJvmChangeSignaturePlanner`) exist on main, but this slice
has NO Cucumber runner/glue, no executable RED/GREEN, no packaged CLI/daemon/MCP apply+rollback
verification, no four-platform qualification, and no independent `requirements-quality-reviewer` PASS.
None of these promotion gates is claimed at baseline.

## Promotion gates (not yet claimed)

Genuine tests-only RED; GREEN against the agreed runner; packaged CLI/daemon/MCP apply+rollback;
four-platform qualification; independent `requirements-quality-reviewer` PASS. All requalify at K5
band close. This baseline bounds the executable slice to the Cucumber scenarios
REQ-KOTLIN-CHANGE-SIGNATURE-001..003; it does not narrow any promotion gate.
