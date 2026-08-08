# REQ005-BDD-EVIDENCE-001 — Immutable Baseline Receipt

Stable ID: `REQ005-BDD-EVIDENCE-001`
Requirement: `REQ-JAVA-MAVEN-MODULE-RENAME-SURFACE-005`
Feature: `features/java-maven-module-rename-managed-surfaces.feature`
Initial feature SHA-256: `20d3df5193586bfe0b3db202bf48295c1c8cfd9b41bfcaa3f71ce76846be0e81`
Scenario: `Only LSP applyPlan manages the retained exact plan through preflight, authority, and rollback`
Qualified request: old module directory `catalog-model`, new module directory `catalog-domain`, caller-explicit new artifact ID `catalog-domain`.

## Initial user intent

The bounded verbatim user input for this continuation was:

> procedi

After independently closing SURFACE-004, the planner announced continuation with the only remaining absent managed surface, SURFACE-005. This receipt bounds that continuation to the already-declared SURFACE-005 scenario and does not authorize broader LSP, Maven, transport, packaging, platform, or release support.

## Requirement and acceptance-criterion IDs

- `REQ-JAVA-MAVEN-MODULE-RENAME-SURFACE-005` (`@functional-requirement`, `@non-functional-requirement`, initially `@absent`).
- AC-005-01: Three independent version-capable API `0.2` LSP sessions operate on separate exact `S0` fixture copies and independently retain the canonical proposal without editor application.
- AC-005-02: The dirty-document probe tracks both an unaffected reporting document and the affected Product document; an external disk-only change makes the unaffected tracked buffer divergent while the affected Product remains open.
- AC-005-03: The affected-open probe tracks the affected Product document as open and clean; the primary session has no open document.
- AC-005-04: Dirty-document and affected-open probes invoke `refactorkit.applyPlan` with their own retained plan IDs and receive `DOCUMENT_VERSION_MISMATCH` in the declared order: any dirty buffer is rejected before the affected-open-document rule.
- AC-005-05: Each preflight refusal occurs before fresh apply scan, operation-gate construction, `PatchEngine`, workspace lock, WAL, target edit, pending-plan removal, or state refresh. Each refused plan remains retained and its workspace remains unchanged except for the explicitly staged external disk-only dirty-probe byte change.
- AC-005-06: The clean, closed primary session supplies `PatchEngine` the exact retained `PatchPlan` and authority lease plus a fresh exact `S0` scan containing every auxiliary POM byte.
- AC-005-07: Only `refactorkit.applyPlan` supplies explicit approval surface `lsp-managed-command` and selects the lazy operation-owned authoritative gate for exact operation `java.renameMavenModule`; generic `java-jdt` must not be selected or evaluated for this apply.
- AC-005-08: The gate evaluates exact `S0`, `C1`, and committed `S1` with diagnostic multiset `D0`; one schema-v8 transaction record reaches `APPLIED` for the exact canonical five-entry edit before LSP returns its transaction ID.
- AC-005-09: Only after `ApplyResult.Applied` does LSP remove the primary plan, refresh exact `S1`, and expose normal RefactorKit rollback for that transaction.
- AC-005-10: Normal `refactorkit.rollback` advances the same record to `ROLLED_BACK`, restores every non-engine byte and path kind to exact `S0`, creates no second transaction, and refreshes exact `S0` and `D0`.
- AC-005-11: The separately qualified client-managed SURFACE-004 proposal remains non-transactional, non-rollbackable by RefactorKit, and never qualifies this managed route.

## Quality constraints

- Classify this as Story BDD/Cucumber because the LSP approval, preflight, transaction, recovery, refresh, and rollback boundary is executable living documentation.
- Use observed source-built runtime behavior under JDK 21 (`JAVA_HOME=/usr/lib/jvm/java-21-openjdk`); source-only, reconstructed, or fabricated success evidence is insufficient.
- Exercise the public in-process `LspSession.dispatch` boundary for initialization, proposal, `applyPlan`, and rollback.
- Construct exact `S0`, `C1`, `S1`, `D0`, five-entry `WorkspaceEdit`, journal, and directory-state oracles independently from immutable fixture bytes and literal paths/ranges. Do not derive expected values from the `PatchPlan`, response, or journal record under test.
- Critical private lifecycle observations may use fail-closed test-only reflection when no public protocol evidence exists, but must not add a production observer seam or silently degrade into unconsumed flags.
- The refusal probes and primary session must use distinct roots, sessions, opaque plan IDs, and retained plans.
- Direct hand-written JUnit behavior tests are forbidden. JUnit Platform may execute Cucumber infrastructure only.
- Preview must precede apply; only one managed transaction may be applied at a time.
- SURFACE-005 remains `@absent` until production behavior, focused semantic validation, documentation reconciliation, and an independent requirements-quality-reviewer `PASS` all bind to the delivered revision.

## Architecture, persistence, and authority constraints

- The LSP adapter owns protocol preflight and orchestration; the Java planner supplies the immutable canonical plan/lease; the managed diagnostics selector owns exact gate selection; `PatchEngine` owns baseline/staged/committed evaluation and atomic apply; `TransactionLog` is the WAL/idempotency/exact-result and rollback-evidence store.
- A pending plan is transient per-session application state. It is retained after preflight refusal, removed only after successful managed apply, and is never itself approval or a transaction.
- The transaction record is not a domain event or event-sourced aggregate stream. It is patch-engine transaction/WAL evidence with one lifecycle (`PREPARED` where observable, then `APPLIED`, then `ROLLED_BACK`).
- `ApplyAuthorization.explicit("lsp-managed-command")` is the only approval surface in this row.
- The operation-owned authoritative gate must preserve the Java Maven module-rename authority lease and auxiliary-POM evidence; the generic `java-jdt` fallback is inadmissible for exact `java.renameMavenModule` managed apply.
- Successful state refresh is outcome-driven and occurs only after `ApplyResult.Applied`; successful rollback refresh occurs only after `ManagedRollbackOutcome.RolledBack`.

## Exclusions

- Client/editor application of the SURFACE-004 proposal is excluded from managed apply and rollback evidence.
- No stdio framing, child-process, socket, real-editor integration, packaged/native, cross-platform, concurrent, crash-restart, general-Maven, release-wide, broad-support, or numerical-coverage qualification is implied.
- Forced rollback, recovery after process crash, transaction-log corruption, capacity/eviction races, and concurrent plan application are outside this row.
- No new event sourcing, repository topology, aggregate, domain event, or persistence mechanism is authorized.

## Approved scope and change history

Approved scope is the existing SURFACE-005 scenario at the initial feature hash above, bounded by the authority and exclusions in this receipt.

No explicit user-approved material requirement change has been recorded after the continuation request. Domain and Gherkin reviews may make existing semantics observable and unambiguous but must not expand scope. Any material change requires a separate append-only approval receipt; this baseline body and hash remain immutable.
