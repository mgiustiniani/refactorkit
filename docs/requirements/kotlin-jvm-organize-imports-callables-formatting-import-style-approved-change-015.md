# Kotlin/JVM import-layout requirement change 015

Status: explicit user-approved baseline change for REQ-KOTLIN-IMPORT-STYLE-001, superseding the
ITEM-2 "Unicode code-point-sorted group" wording to match actual production behavior.

## User decision (verbatim intent)

> si confermo
> (approve superseding ITEM-2 "one Unicode-code-point-sorted group" to String natural order within the
> ASCII-bounded import grammar, then continue the roadmap)

## Approved change

REQ-KOTLIN-IMPORT-STYLE-001 ITEM-2 claimed the default official layout is "one Unicode-code-point-sorted
group". Independent reviewer finding RQR-KCS-RQ005 (BLOCKER): production orders directives with
`sortedBy { it.directive.trim() }` (KotlinOrganizeImportsPlanner.kt:405), which is JVM String natural
order (UTF-16), not Unicode code-point order. The import grammar (`IMPORT_REGEX`, KotlinOrganizeImportsPlanner.kt:427)
is ASCII-only (`[A-Za-z_][A-Za-z0-9_]*`), so valid Kotlin import directives cannot contain non-ASCII
code points; code-point vs UTF-16 ordering coincides for every valid input. ITEM-2 is therefore
superseded and reframed: the default official layout is **one String-natural-order-sorted group within
the ASCII-bounded Kotlin import grammar**; Unicode code-point ordering is unobservable for valid import
directives. The criterion is NOT removed; it is documented as an overclaimed/unobservable property the
production cannot exercise from valid compiler-proven fixtures.

## Boundary

This change is bounded to REQ-KOTLIN-IMPORT-STYLE-001 ITEM-2. All other ITEM-1..7 criteria, the
approved-change-014 RED deferral (R-052), and the baseline code-criterion supersession
(stale/symlinked -> kotlin.classpathEvidenceChanged) are unchanged. Packaged CLI/daemon/MCP apply+rollback
and four-platform qualification remain deferred / not claimed (requalify at K5 band close); independent
`requirements-quality-reviewer` PASS remains mandatory.

## Evidence bound

- Candidate: commit 4cc8890 (19/19 GREEN / 116 steps).
- Fresh GREEN evidence: 3f9dfac463fadd6f99891b798641bfa6c6ca30ce7a3eb7c658bd61c48236b4ff.
- Reviewer evidence: JDK 21 boundary U+F900/U+10000 codePointCompare=-1 vs String.compareTo=8448;
  fixtures ASCII-bounded; production sorting is String natural order.
- Feature SHA-256: cb791f8f8f073a5e41993287bd4bbd6d1ae381faaeb37ba5a450857a43b69445.
