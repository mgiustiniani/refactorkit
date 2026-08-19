# Kotlin/JVM change-signature requirement change 012

Status: explicit user-approved baseline change superseding 4 non-inducible REQ-001 family-incompleteness
criteria for REQ-KOTLIN-CHANGE-SIGNATURE-001.

## User decision (verbatim intent)

> +ok
> (approve superseding the 4 non-inducible REQ-001 family-incompleteness criteria, reframe to
> SEMANTIC_PREVIEW + defensive-gate note, and proceed to promotion)

## Approved change

The 4 REQ-001 family-incompleteness refusal criteria that are genuinely NON-INDUCIBLE from a clean
compiler fixture are superseded and reframed to assert the actual production behavior
(SEMANTIC_PREVIEW success, read-only) with an explicit `defensive-gate, not inducible from a clean
compiler fixture` note. They are NOT removed; they are documented as defensive guards the production
retains but which cannot be triggered from valid compiler-proven fixtures.

Superseded criteria (4, REQ-001 family-incompleteness):

- `fewer family functions than family parameters` — the compiler always emits
  `familyParameters.size <= familyFunctions.size`, so the condition is unreachable.
- `function and parameter family disagree` — a compiler parameter always inherits its function's
  override family, so the `familyId != targetEvidence.overrideFamilyId` gate is unreachable.
- `crossing an external or unavailable declaration boundary` — no in-workspace `calculateTotal`
  carries `hasExternalHierarchyBoundary`; only a real external override carries it, which the feature
  does not declare.
- `a hierarchy member with fewer than two family functions` — requires an unavailable external base
  declaring `calculateTotal`.

## Rationale

story-driven proved each condition unreachable with the compiler (KotlinCompilerCallableSignatureExtractor
+ KotlinChangeSignaturePlanner). These are defensive guards, the same class as the 12 superseded by
approved-change-011. This change extends that supersession to the 4 REQ-001 family-incompleteness
criteria. The inducible `lacking one exact parameter declaration at the selected ordinal` case is NOT
superseded (it is reachable GREEN and keeps its refusal).

## Boundary

This change is bounded to REQ-KOTLIN-CHANGE-SIGNATURE-001. It supersedes only the 4 listed criteria;
all other inducible REQ-001/002/003 criteria and their production gates are unchanged. Packaged
CLI/daemon/MCP apply+rollback and four-platform qualification remain deferred/not claimed (requalify
at K5 band close); independent `requirements-quality-reviewer` PASS remains mandatory.

## Evidence bound

- Candidate: commit 9935476 (29 GREEN / 4 RED honest; human-readable feature; glue reconciled).
- Linux GREEN: 29 cases with real production gates.
- RED evidence: 4 non-inducible REQ-001 family-incompleteness cases, recorded honestly.
