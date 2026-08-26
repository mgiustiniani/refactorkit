# J1 Java catalogue — approved bounded scope (v0.7.x)

- Receipt ID: `REQ-JAVA-J1-CATALOGUE-APPROVED-001`
- Captured: 2026-08-24
- Baseline Git commit (receipt file): `8aa49c3` (catalogue scope decided at context `6791b88`)
- Workflow: `bdd-java`
- Release ledger target: `docs/releases/v0.7.0-plan.md` J1 "Broad Java move/package/module/extract/inline/hierarchy catalogue"

## User decision (verbatim intent)

> confermo
> (approve the finite 19-row J1 catalogue: 8 frozen qualified rows, 7 characterization RED-deferral
> rows, 4 refusal-only genuine-RED rows, narrow Maven dimensions + explicit non-claims)

## Approved scope (finite, closed)

This catalogue replaces the J1 SPECIFICATION_AMBIGUITY with a closed inventory. It is the
authoritative bounded scope for Java 0.7.x work; nothing outside it is claimed.

### 1. Already-qualified — frozen, no new implementation

- Q-CS1..Q-CS5: `changeSignature` renameParameter / addParameter / removeParameter /
  reorderParameters / changeParameterType (java-jdt-change-signature acceptance).
- Q-MOVE: `moveClass` bounded package migration, guidance, lexical review, structural/freshness
  refusals (REQ-JAVA-MAVEN-MOVE-AUTH-001..013).
- Q-OWN: complete non-generated root moved between existing Maven modules with one exact literal
  dependency rewrite (java-maven-ownership-migration).
- Q-MODULE: one direct-child module-directory rename + direct parent `<module>` edit + optional
  explicit artifactId change (REQ-JAVA-MAVEN-MODULE-RENAME-001, SURFACE-001..005).

### 2. Existing-production-needing-Story-BDD-coverage (RED-deferral characterization)

- C-MOVE (moveClass, reconciliation only), C-ROOT (moveSourceRoot), C-EXTRACT (extractMethod
  straight-line private void + refusal list), C-IMPORT (organizeImports), C-RENAME-TYPE,
  C-RENAME-METHOD (signed method + override family), C-RENAME-FIELD (owner-bound field),
  C-DELETE (JDT-proven unused type delete).
- Each truthful feature begins GREEN; tests-only RED deferral requires explicit approval per slice.
  If first execution reveals a real mismatch, reclassify as genuine RED and fix production.

### 3. Absent behavior — genuine RED -> typed refusal (only new production behavior)

- N-INLINE-VAR: `inlineVariable` -> `java.inlineVariable.unsupported` (REFUSED).
- N-INLINE-METHOD: `inlineMethod` -> `java.inlineMethod.unsupported` (REFUSED).
- N-PULL-UP: `pullUpMember` -> `java.hierarchy.pullUp.unsupported` (REFUSED).
- N-PUSH-DOWN: `pushDownMember` -> `java.hierarchy.pushDown.unsupported` (REFUSED).
- Every refusal: empty WorkspaceEdit/affected-files, no approval/managed-write, no pending plan,
  no lock/WAL/transaction, deterministic code. "Hierarchy move" = pull-up/push-down member only.

### 4. Exact Maven dimensions (accepted / excluded)

Accepted: source ownership (complete roots, same source-set kind), module directory
(oldModuleDir->newModuleDir, one direct child), parent module (one direct literal `<modules><module>`
entry), module artifactId (optional explicit), dependency rewrite (one exact literal occurrence,
`dependencyPom`, exact GAV, `allIdenticalOccurrences=false`, jar/empty-classifier).

Excluded (non-claims): module groupId mutation, module version mutation, dependency
type/classifier other than jar/empty, duplicate occurrences beyond one exact literal, parent-coordinate
migration, nested/profile modules, module creation, packaging changes, JPMS, move to another aggregator,
property/inherited origins.

### 5. Explicit non-claims

No new CLI/JSON-RPC/LSP/MCP protocol schemas; no recipe composition/authority; recipe-private
whole-package `movePackage` not public; no packaged/native-host/cross-platform qualification; no
general Maven projects; no disposable real-project qualification; no green whole-repository/release
result; no I1 checksums/SBOMs/attestations/download verification; no `formatFile`/createMavenModule/
external-import/Kotlin/TypeScript; no recursive package migration; no configuration/framework-string
rewriting; no hierarchy restructuring beyond the two explicit refusal rows. The packaged module-rename
feature remains an independent open release item; closing this catalogue does not close the 0.7.x band.

## Execution order (approved)

1. Freeze the 8 qualified rows in the roadmap/support ledger (no production edits).
2. Genuine RED slices: N-INLINE-VAR -> N-INLINE-METHOD -> N-PULL-UP -> N-PUSH-DOWN
   (feature/fix/GREEN/review cycle each).
3. Characterization slices: C-ROOT -> C-EXTRACT -> C-IMPORT -> C-RENAME-TYPE -> C-RENAME-METHOD ->
   C-RENAME-FIELD -> C-DELETE.
4. Maven reconciliation: record accepted dimensions + non-claims; do not broaden planners.
5. Final evidence: coverage, ARC42 LATE update, independent requirements-quality-reviewer PASS.

## Status

Initial: `@not-implemented` for the J1 catalogue as a whole. No row of section 3 (new production
behavior) or section 2 (characterization) is claimed until its own slice passes an independent
`requirements-quality-reviewer` PASS. Frozen section 1 rows remain qualified per their existing
evidence. Packaged/four-platform qualification is not claimed here.
