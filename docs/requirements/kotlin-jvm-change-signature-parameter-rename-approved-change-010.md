# Kotlin/JVM change-signature requirement change 010

Status: explicit user-approved promotion-gate change for REQ-KOTLIN-CHANGE-SIGNATURE-001..003.

## User decision (verbatim intent)

> 2
> (Option 2: approve the baseline change deferring the tests-only RED gate)

## Approved change

The mandatory tests-only RED gate (REQ-KOTLIN-CHANGE-SIGNATURE-001..003-BASELINE-001) is deferred for
REQ-KOTLIN-CHANGE-SIGNATURE-001..003. Justification: the production planners
(`KotlinChangeSignaturePlanner` + `KotlinJvmChangeSignaturePlanner`) pre-existed and were not weakened;
the reconciled feature (14 scenarios / 32 expanded cases / 179 steps) passes GREEN against that
existing production with no production code changed in this slice. The feature header honestly states
no genuine executable tests-only RED exists for a production gap.

This deferral is recorded as ARCHITECTURAL / PROCESS technical debt in ARC42 (Risks and Technical
Debt, chapter 11) — see the ARC42 reconciliation. It must be requalified at K5 band close.

## Boundary

This change is bounded to REQ-KOTLIN-CHANGE-SIGNATURE-001..003 (baseline
REQ-KOTLIN-CHANGE-SIGNATURE-001..003-BASELINE-001). It does not defer any other promotion gate:
packaged CLI/daemon/MCP apply+rollback, four-platform qualification, and the broader
change-signature authority remain deferred/not claimed (requalify at K5 band close); independent
`requirements-quality-reviewer` PASS remains mandatory.

## Evidence bound

- Candidate: commit a778933 (+ appendix af004c9)
- Linux GREEN: 14 scenarios / 32 expanded cases / 179 steps passed
  (`docs/requirements/evidence/v0.7.0-k5-change-signature-green-d86e3ff66d7e357a406ddfcd0afd3ab2d8d684c6a6119134c6c4e4bddb260fa8.json`)
- Feature reconciled anti-fake: 11 declared refusal codes match production 1:1; fake refusals removed/
  converted to SEMANTIC_PREVIEW success outlines.
- Independent review: not yet granted (comes after this documentation).
