# Kotlin/JVM callable-imports requirement change 009

Status: explicit user-approved promotion-gate change for REQ-KOTLIN-JVM-ORGANIZE-IMPORTS-CALLABLE-001.

## User decision (verbatim intent)

> la 2.
> (Option 2: approve the baseline change deferring the tests-only RED gate)

## Approved change

The mandatory tests-only RED gate (REQ-KOTLIN-JVM-ORGANIZE-IMPORTS-CALLABLE-001-BASELINE-001) is
deferred for REQ-KOTLIN-JVM-ORGANIZE-IMPORTS-CALLABLE-001. Justification: the production planner
(`KotlinOrganizeImportsPlanner`, callable-import preservation) pre-existed and was not weakened; the
single Cucumber scenario passes GREEN against that existing production with no production code changed
in this slice. The feature header honestly states no genuine executable tests-only RED exists.

This deferral is recorded as ARCHITECTURAL / PROCESS technical debt in ARC42 (Risks and Technical
Debt, chapter 11) — see the ARC42 reconciliation. It must be requalified at K5 band close.

## Boundary

This change is bounded to REQ-KOTLIN-JVM-ORGANIZE-IMPORTS-CALLABLE-001 (narrow sub-slice of
REQ-KOTLIN-ORGANIZE-CALLABLE-001, baseline SHA-256 `020a9c93895574a827f58736e0b5531dccdb267df3aa75f5fb79045eae94624d`).
It does not defer any other promotion gate; packaged/four-platform and the broader
REQ-KOTLIN-ORGANIZE-CALLABLE-001 gates remain deferred/not claimed (requalify at K5 band close);
independent `requirements-quality-reviewer` PASS remains mandatory.

## Evidence bound

- Candidate: commit 2bfa135 (feature + runner/glue + baseline + GREEN evidence + ARC42 appendix)
- Linux GREEN: 1 scenario / 8 steps passed
  (`docs/requirements/evidence/v0.7.0-k5-organize-imports-callable-green-3ee8c374eb38c7078932325cf47b0a96b7f71b648b833d32af609a57e5963f33.json`)
- Independent review: scenario SATISFIED; RED gate + premature-status were the blockers before this
  approved change and the status correction.
