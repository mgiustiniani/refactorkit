# Kotlin/JVM companion-refusal requirement change 008

Status: explicit user-approved promotion-gate change for REQ-KOTLIN-MOVE-COMPANION-REFUSAL-001.

## User decision (verbatim intent)

> opzione 2. ma documentare su arc42 technical debt
> (Option 2: approve the baseline change; document it on ARC42 as technical debt)

## Approved change

The mandatory tests-only RED gate (RQ-KMF baseline "meaningful tests-only RED") is deferred for
REQ-KOTLIN-MOVE-COMPANION-REFUSAL-001. Justification: the production planner
(`KotlinJvmMoveDeclarationPlanner` line ~79, `kotlin.moveCompanionStandaloneUnsupported`) pre-existed
on main and was not weakened; all 3 companion-refusal Cucumber scenarios pass GREEN against that
existing production with no production code changed in this slice. The feature header honestly states
no genuine executable tests-only RED exists.

This deferral is recorded as ARCHITECTURAL / PROCESS technical debt in ARC42 (Risks and Technical
Debt, chapter 11) — see the ARC42 reconciliation. It must be requalified at K5 band close.

## Boundary

This change is bounded to REQ-KOTLIN-MOVE-COMPANION-REFUSAL-001 (governed by
docs/requirements/kotlin-jvm-move-top-level-function-and-companion-refusal.md, SHA-256
52dd7de86022e2d86e143c453fe2c445c2718149a6393d5ba462bbceb2642a0f) for the v0.7.0 K5 band. It does not
defer any other promotion gate; packaged/four-platform qualification remains deferred per
approved-change-007; independent `requirements-quality-reviewer` PASS remains mandatory.

## Evidence bound

- Candidate: commit 4be82f1 (feature + runner/glue + RED/GREEN evidence + ARC42 appendix)
- Linux GREEN: 3 scenarios / 14 steps passed
  (`docs/requirements/evidence/v0.7.0-k5-move-companion-refusal-green-07e8d6a60b8c60c97063498e238e962497f30a9ec71dae0839fc83c557898bc8.json`)
- Independent review: AC-COMPANION-REFUSAL-001 SATISFIED; RED gate was the sole blocker before this
  approved change.
