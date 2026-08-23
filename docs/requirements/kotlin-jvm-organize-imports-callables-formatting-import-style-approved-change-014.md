# Kotlin/JVM import-layout requirement change 014

Status: explicit user-approved baseline change for REQ-KOTLIN-IMPORT-STYLE-001 (project-style-aware
formatting), superseding the baseline refusal-code criterion and deferring the tests-only RED gate,
matching actual production behavior.

## User decision (verbatim intent)

> task 1 confermo la scelta del domain expert e poi procedi col resto
> (approve the domain-expert's Option 1: defer the RED gate and supersede the baseline code criterion
> so stale/symlinked layout evidence asserts kotlin.classpathEvidenceChanged, then continue the roadmap)

## Approved change

REQ-KOTLIN-IMPORT-STYLE-001-BASELINE-001 item 6 pinned all layout refusals to
`kotlin.organizeImportsStyleUnsupported`, including stale/symlinked evidence. That criterion is
superseded and split by evidence class:

1. **Malformed/duplicated/escaping/oversized/unsupported layout configuration** refuses before planning
   with `kotlin.organizeImportsStyleUnsupported` (unchanged; reachable and verified GREEN).
2. **Stale/symlinked layout evidence** (fingerprint changed after the snapshot, or a symbolic link /
   non-regular no-follow file) is rejected before planning by the production classpath-evidence
   validator as `kotlin.classpathEvidenceChanged`. The planner-local `kotlin.organizeImportsStyleStale`
   path (KotlinOrganizeImportsPlanner readStyleCandidate) is retained as a defensive guard but is not
   reachable from a valid compiler fixture because `validateClasspathEvidence` runs first.

## RED-gate deferral (R-052)

The tests-only RED gate for this coverage slice is deferred to K5 band close. The production planner
pre-existed the baseline and was unchanged; the feature (10 scenarios / 19 expanded cases) passes
19/19 GREEN with a real K2 toolchain fixture, PatchEngine, WAL, apply, and rollback. This is the same
pattern as approved-change-009 (callable-imports) and approved-change-010 (change-signature RED deferral).

## Boundary

This change is bounded to REQ-KOTLIN-IMPORT-STYLE-001. It supersedes only the baseline refusal-code
criterion (stale/symlinked) and defers the RED gate; all other REQ-KOTLIN-IMPORT-STYLE-001 criteria and
their production gates are unchanged. Packaged CLI/daemon/MCP apply+rollback and four-platform
qualification remain deferred / not claimed (requalify at K5 band close); independent
`requirements-quality-reviewer` PASS remains mandatory.

## Non-fake band-close requalification

At K5 band close, a hash-bound historical pre-capability replay (or reviewer-approved equivalent)
re-verifies this slice against unchanged production and the approved-change history, together with
packaged/four-platform qualification. No manufactured RED, no architecture-by-test, no production
perturbation solely to expose an unreachable planner-local code.

## Evidence bound

- Candidate: commit 5363d3c (19/19 GREEN / 116 steps).
- GREEN evidence: 62a43e2d2cdc167942a27b8435deb887a4760f319e33aec6e8bc398340525270.
- RED evidence: stale/symlinked declared kotlin.organizeImportsStyleStale vs actual
  kotlin.classpathEvidenceChanged (validateClasspathEvidence precedes projectStyle boundary).
- Feature SHA-256: 9684ddb8aaaf0512d43ba80ed88930ad9bdba1aa1850374027d8ab4db90165f2.
