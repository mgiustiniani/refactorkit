# REQ003-BDD-EVIDENCE-001 — Immutable Baseline Receipt

Stable ID: `REQ003-BDD-EVIDENCE-001`
Requirement: `REQ-JAVA-MAVEN-MODULE-RENAME-SURFACE-003`
Feature: `features/java-maven-module-rename-managed-surfaces.feature` (scenario outline "retains and applies the exact authoritative plan before refreshing successful state")
Qualified request: oldModuleDir `catalog-model`, newModuleDir `catalog-domain`, caller-explicit newArtifactId `catalog-domain`.

## Intent

Promote SURFACE-003 to `@implemented-and-validated` only when real observed Cucumber execution proves both surfaces (daemon JSON-RPC + MCP tools) execute the qualified preview/apply/rollback with 0 undefined/pending/ambiguous/failed steps, and the refusal-probe tracked-content refusal is real (no target edit, no WAL, no transaction), with no source-only or reconstructed evidence.

## Requirement / acceptance-criteria IDs

- `REQ-JAVA-MAVEN-MODULE-RENAME-SURFACE-003` (functional + non-functional).
- Acceptance: 2 scenario rows (daemon + MCP) all steps passed; journal contains exactly one schema-v8 APPLIED record for `java.renameMavenModule`; rollback re-reads the same record as ROLLED_BACK with no second transaction; refusal-probe differs from S0 only in the staged auxiliary-POM byte; primary produces a transaction ID and S1; snapshot hash observed at S0, advanced to S1 after apply, returned to S0 after rollback; pending-plan store lookup returns the retained PatchPlan/lease.

## Quality constraints

- Evidence must be observed at runtime under JDK 21 (`JAVA_HOME=/usr/lib/jvm/java-21-openjdk`); no source-only, reconstructed, or fabricated claims.
- Cucumber runner: tag-filtered to SURFACE-003 with JSON plugin; all steps must pass.
- Cucumber is the living-documentation source; javaspec is not required for this integration surface.
- RefactorKit rule: preview before apply; one refactoring transaction at a time.

## Exclusions

The following internals are not exposed by public daemon/MCP dispatch and are not promotion prerequisites; they are honestly flagged, never fabricated:

- Exact planner invocation count.
- Retained lease object identity/content beyond `pendingPlans.lookup(planId)`.
- Lazy operation-owned gate evaluation internals and zero generic `java-jdt` invocation.
- PREPARED/WAL absence is verified by journal enumeration (exactly one APPLIED record, no second transaction).
- Semantic-session lifecycle internals and authoritative D0 restoration are verified through snapshot-hash S0/S1 transitions.

## Approved scope

- Surfaces: daemon JSON-RPC (`refactor.preview`/`refactor.apply`/`patch.rollback`) and MCP tools (`preview_refactoring`/`apply_refactoring`/`rollback_refactoring`).
- Operation: `java.renameMavenModule` on direct-child Maven module `catalog-model` -> `catalog-domain`.
- Honest status: feature line 112 and ARC42 appendix declare SURFACE-003 `@absent` until a fresh reviewer returns `PASS`; promotion is delegated to `gherkin-writer` only after `PASS`.

## User-approved changes (Gherkin clarifications)

These were explicitly approved by the user during the working session:

1. Refusal-probe runs in an independent session/workspace copy with its own opaque plan ID; it is not a mutation of the primary plan.
2. Probe differs from S0 only in the staged auxiliary-POM byte (`catalog-model/pom.xml`), with no rename.
3. Daemon `refreshSavedWorkspaceIfDirty()` is a pre-dispatch integration phase, not an outcome-driven success refresh; a refused apply performs no outcome-driven S1 refresh.
4. MCP has no equivalent pre-dispatch reconciliation; a refusal leaves snapshot/session unchanged.
5. Only `ApplyResult.Applied` performs the outcome-driven S1 refresh.
6. Plan IDs are correlation identities, not deterministic or cross-session shared values.

## Immutable baseline hash

- Baseline file: `docs/requirements/req003-surface003-baseline.md` (this file).
- SHA-256: computed at creation (see companion `docs/requirements/req003-surface003-baseline.sha256`).
- Feature source-of-truth: `features/java-maven-module-rename-managed-surfaces.feature` (SHA-256 recorded in the reviewer handoff at each review run).
