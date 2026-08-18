# Kotlin/JVM change-signature requirement change 011

Status: explicit user-approved baseline change superseding 12 non-inducible defensive-gate criteria
for REQ-KOTLIN-CHANGE-SIGNATURE-001..003.

## User decision (verbatim intent)

> procedi
> (proceed with the honest-evidence path: keep the 12 non-inducible defensive-gate RED as honest
> evidence, reframe them under an explicit approved baseline change, and promote)

## Approved change

The 12 defensive-gate refusal criteria of REQ-KOTLIN-CHANGE-SIGNATURE-001..003 that are
genuinely NON-INDUCIBLE from a clean compiler fixture are superseded and reframed to assert the
actual production behavior (SEMANTIC_PREVIEW success, read-only) with an explicit
`defensive-gate, not inducible from a clean compiler fixture` note. They are NOT removed; they are
documented as defensive guards the production retains but which cannot be triggered from valid
compiler-proven fixtures because K2 symbols are always paired with their declaration and valid,
and a parameter rename preserves bindings so the staged/mixed proof never regresses.

Superseded criteria (12):

- REQ-002 evidence: `kotlin.changeSignatureIdentityMissing`, `kotlin.changeSignatureParameterIdentityInvalid`
- REQ-002 baseline: `kotlin.changeSignatureBaselineIncomplete` (K2 refuses first with `kotlin.symbolCompilationFailed`)
- REQ-002 staged regression: `kotlin.changeSignatureDiagnosticsRegression`, `kotlin.changeSignatureBindingChanged`, `kotlin.changeSignaturePreviewInvalid`, `kotlin.changeSignaturePostImageIdentityMissing`
- REQ-003 mixed regression: `kotlin.changeSignatureMixedDiagnosticsRegression`, `kotlin.changeSignatureJavaBindingChanged`, `kotlin.changeSignatureBinaryEvidenceUnavailable`, `kotlin.changeSignatureUsageEvidenceUnavailable`, `kotlin.changeSignaturePreviewInvalid`

## Rationale

Per the independent reviewer FAIL (RQR-KCS-002-FALSE-GREEN-PREMISES): the production planners retain
these refusal gates, but no valid fixture reaches them (forcing them would require manufacturing
defective production code, which the workflow forbids). The 21 inducible refusal/positive cases pass
GREEN with real production gates; the 12 defensive gates are recorded as honest RED evidence and
reframed under this explicit approval, resolving the unapproved-drift blocker (RQR-KCS-001).

## Boundary

This change is bounded to REQ-KOTLIN-CHANGE-SIGNATURE-001..003. It supersedes only the 12 listed
defensive-gate refusal criteria; the 21 inducible criteria and their production gates are unchanged.
Packaged CLI/daemon/MCP apply+rollback and four-platform qualification remain deferred/not claimed
(requalify at K5 band close); independent `requirements-quality-reviewer` PASS remains mandatory.

## Evidence bound

- Candidate: commit a50c070 (21 GREEN / 12 honest RED; REQ-003 oracles strengthened)
- Linux GREEN: 21 cases passed with real production gates.
- RED evidence: 12 non-inducible defensive-gate cases, recorded honestly.
