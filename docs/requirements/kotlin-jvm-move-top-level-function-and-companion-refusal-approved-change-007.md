# Kotlin/JVM move-next requirement change 007

Status: explicit user-approved promotion-gate change for REQ-KOTLIN-MOVE-FUNCTION-001.

## User decision (verbatim intent)

> le quattro piattaforme le testiamo alla fine, adesso serve solo linux
> (we test the four platforms at the end; for now only Linux is needed)

## Approved change

The four-platform execution requirement in the mandatory promotion evidence
(REQ-KOTLIN-MOVE-FUNCTION-001-BASELINE-001, RQ-KMF-QUAL-001) is deferred to
the end of the K5 band. For this slice, Linux-only local source-built Cucumber
GREEN is accepted as the current platform-qualification gate. Packaged GREEN and
four-platform execution are requalified at band close, not for this slice.

Tests-only RED and the independent `requirements-quality-reviewer` gate remain
mandatory for promotion and are not deferred.

## Boundary

This change is bounded to REQ-KOTLIN-MOVE-FUNCTION-001 (and its companion-refusal
half REQ-KOTLIN-MOVE-COMPANION-REFUSAL-001 governed by the same requirement file)
for the v0.7.0 K5 band. It does not weaken any other promotion requirement and
does not authorize claiming packaged or cross-platform evidence for this slice.

## Evidence bound

- Candidate: commit `08dd3c3` (feature + runner/glue + GREEN evidence + ARC42 appendix)
- Local Linux source-built Cucumber GREEN: 29 scenarios / 109 steps passed
  (`docs/requirements/evidence/v0.7.0-k5-move-top-level-function-green-0fcfdb35856b600d9d1510f39021d749cc592ef04f622eb6b34ac37303c2444d.json`)
- Independent review: AC-FUNCTION-001..006 SATISFIED; QUAL-001 was the sole
  remaining blocker before this approved change.
