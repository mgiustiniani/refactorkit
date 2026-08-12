# REQ-KOTLIN-MOVE-FUNCTION-001 Initial Requirements Baseline

- Receipt ID: `REQ-KOTLIN-MOVE-FUNCTION-001-BASELINE-001`
- Captured: 2026-08-12
- Baseline Git commit: `b7bf1b2a63ae6cc78d8f3fcaa35d4e0d800d172e`
- Baseline Git tree: `0adeaa655cf950179ea1cd52a34860673e8f5dba`
- Workflow: `bdd-java`
- Release ledger target: `docs/releases/v0.7.0-plan.md` K5 "Expand to the next bounded move shape" row
- Requirement classification: Story BDD packaged/native managed-mutation qualification
- Initial status: `@absent`

## Initial user intent

Continue the K5 (Kotlin) band with priority. Expand `moveDeclaration` to the next
bounded move shape: one compiler-proven public top-level Kotlin function
(REQ-KOTLIN-MOVE-FUNCTION-001), governed by the existing requirement
`docs/requirements/kotlin-jvm-move-top-level-function-and-companion-refusal.md`
(SHA-256 `52dd7de86022e2d86e143c453fe2c445c2718149a6393d5ba462bbceb2642a0f`).
The companion-refusal half (REQ-KOTLIN-MOVE-COMPANION-REFUSAL-001) is a separate
row and remains governed by the same requirement file.

## Approved scope (from approved-change-001..006, incorporated before candidate commit)

1. One compiler-proven public top-level Kotlin function (explicit or implicit
   PUBLIC visibility), with compiler-reported file-facade owner authoritative
   independent of source-filename casing.
2. Overload family includes a private same-name sibling; extension, suspend,
   default-argument, literal-`@JvmName`, local/member, script, generated,
   plugin-dependent, delegated, annotation-evaluated, or non-public selected
   functions remain outside this row.
3. Source file contains the selected function and only compiler-proven private
   top-level helper declarations; other public/internal/protected declarations
   refuse.
4. In-workspace consumers are Kotlin sources with exactly one compiler-proven
   unaliased explicit import of the source callable FQN. Aliased, same-package
   implicit, package-star, fully-qualified, callable-reference, Java, generated,
   mixed, unresolved, recovered, or truncated consumer forms refuse.
5. No same-name top-level function may already exist in the destination package.
6. No implicit outbound source binding may silently rebind to a different
   target-package declaration.
7. Preview edits only package declaration, exact consumer import directives, and
   source-file path. Apply uses `PatchEngine`, explicit authorization, operation
   diagnostics, WAL, post-image attestation, and exact rollback.
8. No source text other than package/import tokens rewritten or formatted; line
   endings and all private helper bytes remain exact.

## Exclusions

- No Java function consumers, aliased callable imports, overload families
  beyond the bounded private-same-name case, source-file facade renaming,
  multifile facades, `@JvmName` facades, extension/suspend/default functions,
  callable references, member extraction, arbitrary declaration splitting, or
  general Kotlin move support.

## Acceptance criteria (from requirement REQ-KOTLIN-MOVE-FUNCTION-001)

- AC-FUNCTION-001: selection of one compiler-proven public top-level function
  with exact file-facade owner succeeds as `moveDeclaration`.
- AC-FUNCTION-002: unsupported function shapes refuse with stable typed codes.
- AC-FUNCTION-003: only private helper declarations co-locate; other
  declarations refuse.
- AC-FUNCTION-004: consumer forms restricted to one unaliased explicit import;
  all other forms refuse.
- AC-FUNCTION-005: destination same-name function family absence enforced.
- AC-FUNCTION-006: preview edits only package/import/path; apply uses
  `PatchEngine`, WAL, post-image attestation, exact rollback; line endings and
  helper bytes preserved.

## Evidence requirements (from requirement Status)

- Tests-only RED, local/package GREEN, four-platform execution, and independent
  review required before promotion.
- Existing RED companion evidence: `docs/requirements/evidence/v0.7.0-k5-companion-evidence-red-bbaf93d-*.log`.
- No feature/Gherkin file exists yet for REQ-KOTLIN-MOVE-FUNCTION-001; no RED or
  GREEN evidence for the top-level-function shape beyond the planner
  implementation.

## Quality constraints

- Story BDD/Cucumber only; no direct hand-written JUnit behavior tests.
- No production code before a meaningful RED test exists.
- Preview before apply; inspect affected files; run diagnostics after plan;
  rollback on unexpected diagnostics.
- K2/JDT evidence, source-root ownership, destination absence, staged K2/JDT
  diagnostics, exact new facade callable identity, and every retained consumer
  use must be complete and clean.

## Explicit user-approved changes

- K5 band has priority over J1 packaged qualification (user directive).
- Commit current state and continue with K5 (user directive).
