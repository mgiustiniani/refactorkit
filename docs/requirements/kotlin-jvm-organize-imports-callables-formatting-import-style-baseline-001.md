# REQ-KOTLIN-IMPORT-STYLE-001 Initial Requirements Baseline

- Receipt ID: `REQ-KOTLIN-IMPORT-STYLE-001-BASELINE-001`
- Captured: 2026-08-22
- Baseline Git commit: `19dbd4e`
- Workflow: `bdd-java`
- Release ledger target: `docs/releases/v0.7.0-plan.md` K5 "Add project-style-aware formatting" sub-row (line 642)

## Initial user intent

Continue the K5 (Kotlin) band. Qualify the bounded snapshot-bound import-layout operation
(REQ-KOTLIN-IMPORT-STYLE-001): the import formatter preserves every retained directive byte-for-byte
and changes only directive order and configured blank-line separators; LF/CRLF preserved; default Kotlin
official layout is one Unicode-code-point-sorted group; project override via one bounded `.editorconfig`
`ij_kotlin_imports_layout` value captured as no-follow ClasspathEvidence; tokens are one catch-all `*`,
optional alias group `^`, and unique `<package>.**` prefixes; nearest captured ancestor wins; malformed/
duplicated/escaping/stale/oversized/symlinked/unsupported layout refuses before planning;
`gradle.properties` exact `kotlin.code.style=official` recognized; captured style re-fingerprinted before
use and must match the preview snapshot.

Source: `docs/requirements/kotlin-jvm-organize-imports-callables-formatting.md` REQ-KOTLIN-IMPORT-STYLE-001.

## Approved scope

1. One saved authoritative non-generated `.kt` source with retained imports.
2. Default official layout = one Unicode-code-point-sorted group.
3. `.editorconfig` `ij_kotlin_imports_layout` override: catch-all `*`, optional `^` alias group, unique
   `<package>.**` prefixes; group order per tokens; groups separated by one empty line.
4. Nearest captured ancestor configuration wins; re-fingerprinted before use, must match snapshot.
5. `gradle.properties` `kotlin.code.style=official` recognized.
6. Refusals: malformed/duplicated/escaping/stale/oversized/symlinked/unsupported layout
   (`kotlin.organizeImportsStyleUnsupported`).
7. Preview read-only; managed apply + byte-exact rollback per operation-owned diagnostics gate + PatchEngine + WAL.

## Status

Initial status: `@not-implemented`. Production planner `KotlinOrganizeImportsPlanner` (ImportStyle/
LayoutToken/editorconfig) exists on main, but this slice has NO Cucumber feature/runner/glue, no
executable RED/GREEN, no packaged CLI/daemon/MCP apply+rollback verification, no four-platform
qualification, and no independent `requirements-quality-reviewer` PASS. None of these promotion gates
is claimed at baseline.

## Promotion gates (not yet claimed)

Genuine tests-only RED (or user-approved deferral per established pattern); GREEN against the agreed
runner; packaged CLI/daemon/MCP apply+rollback; four-platform qualification; independent
`requirements-quality-reviewer` PASS. Packaged/four-platform requalify at K5 band close. This baseline
bounds the executable slice to REQ-KOTLIN-IMPORT-STYLE-001; it does not narrow any promotion gate.
