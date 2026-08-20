# Kotlin/JVM change-signature requirement change 013

Status: explicit user-approved baseline change superseding the duplicate-range refusal criterion of
REQ-KOTLIN-CHANGE-SIGNATURE-002.

## User decision (verbatim intent)

> ok
> (approve superseding the duplicate-range refusal criterion, reframe to coalesce/dedupe matching
> production, and proceed to promotion)

## Approved change

The REQ-002 `duplicate ranges refuse` refusal criterion is superseded and reframed. Production
(`KotlinChangeSignaturePlanner:153`) applies `distinctBy { it.first }` before the range-invalid check,
so duplicate token ranges are COALESCED (deduplicated) before comparison and the "duplicate ranges
refuse" predicate is unreachable. The criterion is reframed to assert the actual production behavior:
duplicate ranges are coalesced/deduped and never trigger a refusal.

## Rationale

Independent reviewer finding RQR-KCS-REQ002-DUPLICATE-RANGE-001 (BLOCKER): the requirement says
"duplicate ranges refuse", but production dedupes first, making the refusal condition unreachable; the
Cucumber composite row uses only a generated-source fixture, so 33/33 GREEN does not verify duplicate
rejection. Production's dedupe is correct behavior; the requirement's "refuse" wording was wrong. This
is a non-inducible defensive-gate supersession, the same class as approved-change-011 (12) and
approved-change-012 (4 REQ-001).

## Boundary

This change is bounded to REQ-KOTLIN-CHANGE-SIGNATURE-002. It supersedes only the duplicate-range
refusal criterion; all other REQ-001/002/003 criteria and their production gates are unchanged.
Packaged CLI/daemon/MCP apply+rollback and four-platform qualification remain deferred/not claimed
(requalify at K5 band close); independent `requirements-quality-reviewer` PASS remains mandatory.

## Evidence bound

- Candidate: commit 69d2942 (33/33 GREEN / 175 steps; feature SHA da7b9a0a).
- Linux GREEN: 33 cases with real production gates.
- RED evidence: duplicate-range criterion non-inducible, reframed under this approval.
