# REQ004-BDD-EVIDENCE-001 — Immutable Baseline Receipt

Stable ID: `REQ004-BDD-EVIDENCE-001`
Requirement: `REQ-JAVA-MAVEN-MODULE-RENAME-SURFACE-004`
Feature: `features/java-maven-module-rename-managed-surfaces.feature`
Initial feature SHA-256: `e685cb4f0f179c163a4fb699d2b38b77be922c6579d16837b69500f8a116cf91`
Scenario: `LSP keeps a versioned module-rename proposal client-managed and non-rollbackable`
Qualified request: old module directory `catalog-model`, new module directory `catalog-domain`, caller-explicit new artifact ID `catalog-domain`.

## Initial user intent

The bounded verbatim user input for this continuation was:

> procedi

The planner announced that execution would continue with the next absent managed surface, SURFACE-004 (LSP), beginning with the baseline and architecture gates. This receipt bounds that continuation to the already-declared SURFACE-004 scenario; it does not authorize SURFACE-005 or broader LSP support.

## Requirement and acceptance-criterion IDs

- `REQ-JAVA-MAVEN-MODULE-RENAME-SURFACE-004` (`@non-functional-requirement`, initially `@absent`).
- AC-004-01: An API `0.2` version-capable LSP session returns the canonical Maven module-rename proposal using versioned `documentChanges`.
- AC-004-02: The response reports operation `java.renameMavenModule`, status `PREVIEW`, `client-managed` edit ownership, rollback unavailable, document versions checked, and `documentChanges` edit shape.
- AC-004-03: The proposal contains the canonical ordered three POM modifications followed by two file renames. Closed text documents carry JSON `null` versions; no open-document version is fabricated.
- AC-004-04: The returned plan ID is correlation-only. It grants no approval and creates no RefactorKit transaction.
- AC-004-05: Proposal creation invokes no operation apply gate, `PatchEngine` apply, workspace lock, WAL, transaction, state refresh, or rollback.
- AC-004-06: A harness-side editor/client may apply the returned edit, but RefactorKit still owns no journal record, transaction ID, recovery claim, or rollback capability for those editor-owned writes.
- AC-004-07: Proposal or editor-application evidence must not be counted as managed-apply validation.
- AC-004-08: A legacy client without versioned `documentChanges` support receives `DOCUMENT_VERSION_MISMATCH` before edit return or plan retention, with unchanged workspace bytes and journal state.

## Quality constraints

- Classify this as Story BDD/Cucumber because the LSP protocol and ownership boundary are executable living documentation.
- Use observed source-built runtime behavior under JDK 21 (`JAVA_HOME=/usr/lib/jvm/java-21-openjdk`); source-only or reconstructed success claims are insufficient.
- Use the public LSP session dispatch boundary for negotiation, command invocation, response, and refusal evidence.
- Build the expected five-entry protocol oracle independently from literal fixture paths/ranges; do not derive it from the returned `PatchPlan`, response, or journal.
- The client simulator applies only the returned protocol operations and must not call `refactorkit.applyPlan`, `PatchEngine`, transaction-log helpers, or LSP lifecycle refresh notifications.
- Direct hand-written JUnit behavior tests are forbidden. JUnit Platform may only execute Cucumber infrastructure.
- SURFACE-004 remains `@absent` until production behavior, focused semantic validation, documentation reconciliation, and an independent requirements-quality-reviewer `PASS` all bind to the delivered revision.

## Architecture and authority constraints

- The Java planner is the upstream semantic supplier; the LSP adapter is a downstream anti-corruption/protocol boundary; the editor/client owns client-managed writes; `PatchEngine` remains the sole RefactorKit managed-write and transaction authority.
- `PatchPlan` and core `WorkspaceEdit` are immutable shared-kernel values. LSP `documentChanges` and the proposal response are immutable protocol documents.
- A per-session plan ID is transient application state and correlation identity, not approval, authorization, semantic identity, or transaction identity.
- Editor application outside `PatchEngine` has no RefactorKit lock-held freshness check, authority-lease revalidation, WAL transition, pre/post-image capture, atomic staging, transaction assignment, recovery tracking, or rollback record.

## Exclusions

- `refactorkit.applyPlan`, managed LSP approval, apply-gate selection, schema-v8 managed apply, transaction refresh, recovery, and rollback belong to SURFACE-005 and are excluded.
- No daemon, MCP, CLI, recipe, packaged/native, cross-platform, socket, child-process, crash-restart, concurrency, general-Maven, release-wide, or numerical-coverage qualification is implied.
- Exact private object identity for the retained planner payload is not required by this public proposal row. Exact consumption belongs to SURFACE-005 unless a narrow test-only observation seam, absent from production bytecode, is separately approved.
- An editor's own undo behavior is outside RefactorKit guarantees.

## Approved scope and change history

Approved scope is the existing SURFACE-004 scenario at the initial feature hash above, bounded by the authority and exclusion rules in this receipt.

No explicit user-approved requirement change has been recorded after the initial continuation request. Domain-review clarifications may make the existing semantics observable and unambiguous, but must not expand the approved scope. Any material requirement change must be recorded here only through a new append-only approval receipt; this baseline body and hash remain immutable.
