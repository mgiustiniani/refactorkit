# REQ-KOTLIN-JVM-ORGANIZE-IMPORTS-CALLABLE-001 Initial Requirements Baseline

- Receipt ID: `REQ-KOTLIN-JVM-ORGANIZE-IMPORTS-CALLABLE-001-BASELINE-001`
- Captured: 2026-08-16
- Baseline Git commit: `5ebdee0`
- Workflow: `bdd-java`
- Release ledger target: `docs/releases/v0.7.0-plan.md` K5 "Add callable imports" sub-row

## Initial user intent

Continue the K5 (Kotlin) band. Narrowly qualify one bounded organize-imports behavior: preserve
compiler-proven top-level callable imports while removing unused type imports in one contiguous
import block, with retained imports sorted and no compiler error introduced. This is a bounded
executable sub-slice of the broader requirement
`docs/requirements/kotlin-jvm-organize-imports-callables-formatting.md` (REQ-KOTLIN-ORGANIZE-CALLABLE-001,
SHA-256 `0d28daa1...`). The broader requirement's packaged CLI/daemon/MCP, four-platform, RED, and
independent-review promotion gates are NOT all inherited here; this baseline narrows the executable
slice to the single Cucumber scenario @REQ-KOTLIN-JVM-ORGANIZE-IMPORTS-CALLABLE-001.

## Approved scope

1. One saved, authoritative, non-generated `.kt` file imports a used top-level callable
   `fixture.api.greeting` and an unused type `fixture.api.Unused` in one contiguous import block.
2. The callable is compiler-proven used (K2); the type has no reference.
3. organize-imports preview removes `fixture.api.Unused`, preserves `fixture.api.greeting`, sorts
   retained imports, and introduces no compiler error.
4. Preview is read-only; no workspace/transaction write during preview.
5. Promotion under this baseline: Linux source-built Cucumber GREEN + RED (or user-approved RED
   deferral) + independent `requirements-quality-reviewer` PASS. Packaged/four-platform and the
   broader REQ-KOTLIN-ORGANIZE-CALLABLE-001 gates are NOT claimed here; they requalify at K5 band close.

## Status

Initial status: `@partial`. The feature was prematurely tagged `@implemented-and-validated` without a
recorded independent PASS; this baseline corrects that to `@partial` pending the review gate.
