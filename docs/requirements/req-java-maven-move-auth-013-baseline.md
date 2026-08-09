# REQ-JAVA-MAVEN-MOVE-AUTH-013 Baseline

Baseline ID: `REQ-JAVA-MAVEN-MOVE-AUTH-013-BASELINE-001`
Requirement ID: `REQ-JAVA-MAVEN-MOVE-AUTH-013`
Title: Under-lock Maven move-class authority remains complete and edit-exact before WAL
Status: `SEALED`
Target release: `0.7.0`

## 1. Release-ledger ownership

This requirement closes only the unchecked P0 row in
`docs/releases/v0.7.0-plan.md` that requires managed-write eligibility to be
evaluated under the workspace lock before WAL, with evidence drift, truncation,
missing classpath/source evidence, conflicts, mixed lexical edits, and
diagnostic regression leaving the non-engine workspace unchanged.

It does not close the separately unchecked packaged/native authority matrix,
roadmap reconciliation, J1, K1-K5, T5, I1, package publication, or release rows.

## 2. Existing authority preserved

The subject is the existing bounded Maven `moveClass` operation and permanent
fixture:

`testdata/acceptance/java-maven-move-class-authority-20-modules`

The eligible control is the exact binding-clean semantic plan already proven by
`REQ-JAVA-MAVEN-MOVE-AUTH-001` and, where classpath absence evidence is needed,
`REQ-JAVA-MAVEN-MOVE-AUTH-007`/`011`. `LEXICAL_FALLBACK`, guidance, structural
refusal, and every existing REQ-001..012 contract remain unchanged.

`PatchEngine` remains the sole workspace lock, WAL, managed-write, transaction,
automatic rollback, recovery, and normal rollback authority.

## 3. Required immutable lease contract

An operation-authority lease that can reach managed write must bind:

1. the exact operation and preview snapshot;
2. complete, non-truncated semantic evidence;
3. the canonical normalized `WorkspaceEdit` identity produced by the semantic
   planner;
4. every required non-managed source/file evidence record;
5. every required classpath presence or no-follow absence record; and
6. the existing operation-specific evidence hash and immutable attributes.

Collections and attributes remain defensively copied. Callers cannot mutate the
lease after preview. A lease may represent incomplete evidence only so the
apply boundary can refuse it deterministically; incomplete evidence can never
be eligible.

The normalized edit identity must be language-neutral, deterministic,
path-separator independent, order-sensitive at `FileEdit` level, range/text
sensitive at `TextEdit` level, and type-sensitive for create/delete/rename/
modify. It excludes plan ID, summary, confidence, warnings, diagnostics,
approval, timestamp, and transaction identity.

## 4. Acceptance matrix

One `Scenario Outline` must evaluate each row independently from a fresh exact
fixture copy. The declared functional status is `@absent` until independent
review authorizes status-only promotion.

| Case | Isolated condition after an otherwise eligible preview | Required primary outcome before WAL |
|---|---|---|
| incomplete semantic evidence | the lease completeness is `TRUNCATED` | typed `authorityLease.evidenceIncomplete` refusal |
| mixed lexical edit | one normalized text edit not selected by the binding-clean semantic plan is added after preview | typed `authorityLease.workspaceEditMismatch` refusal |
| missing required source evidence | one required non-managed source/file evidence record is absent from the supplied preview snapshot | typed `authorityLease.evidenceMissing` refusal |
| unreadable required source path | a required non-managed source/file path becomes a symbolic link or non-regular/unreadable path after preview | typed `authorityLease.evidenceUnreadable` refusal |
| drifted required source bytes | a required non-managed source/file changes after preview | typed `authorityLease.evidenceDrift` refusal with expected/observed identities |
| missing required classpath evidence | a required classpath presence/absence record is absent from the supplied preview snapshot | typed `authorityLease.evidenceMissing` refusal |
| drifted classpath/source-root evidence | a required classpath entry, source-root directory, model input, selected POM, or no-follow missing artifact changes kind, presence, path identity, or bytes after preview | typed `authorityLease.evidenceDrift` or `authorityLease.evidenceUnreadable` refusal |
| affected-file conflict | a managed affected source changes after preview and before apply validation | typed snapshot/affected-file conflict refusal |
| staged diagnostic regression | exact staged authoritative diagnostics introduce or change an error | typed diagnostic-gate refusal before WAL |

The outline may use test-only fault injection solely to place an external change
after lock acquisition and before the named validation phase. It must not grant
authority, construct production diagnostics, or bypass production validation.

## 5. Ordering and residue

For every row:

1. immutable plan/lease validation that is independent of filesystem state may
   occur before lock acquisition;
2. filesystem, snapshot, classpath, source, edit-identity, and staged-diagnostic
   eligibility is evaluated while the one-writer lock is held;
3. refusal occurs before `PREPARED`, transaction-journal creation, or any WAL
   byte;
4. no managed target edit, destination path, transaction, rollback claim, or
   recovery record is created;
5. the external mutation deliberately introduced by the case is preserved;
6. every other non-engine byte, path kind, permission, source/classpath
   inventory, and snapshot fact remains exact; and
7. only the workspace lock file may remain as expected engine residue after an
   under-lock refusal.

A preview-time immutable lexical refusal continues to occur before lock and
leaves no `.refactorkit` residue under REQ-005; this requirement does not weaken
that earlier boundary.

## 6. Diagnostic regression

The operation-owned diagnostics gate must evaluate the exact normalized staged
post-image before WAL. A staged regression refuses without applying. If a
post-apply provider mismatch occurs after WAL in a separately existing generic
path, the existing automatic rollback contract remains unchanged and is not
reclassified as pre-WAL evidence.

## 7. Required evidence

Promotion requires:

- meaningful RED proving at least the missing edit-identity/completeness
  enforcement, not merely a stale hash or undefined step;
- focused official Cucumber evidence for every matrix row;
- focused Core and Java regression evidence;
- exact feature/baseline/source hashes;
- independent requirements-quality review bound to one candidate revision; and
- status-only promotion followed by a post-promotion rerun.

The ordinary all-module gate remains required before final P0 closure, but this
source-built requirement alone makes no packaged/native claim.

## 8. Exclusions

This requirement adds no new operation, protocol method, result schema,
external project, sample, Maven lifecycle/plugin execution, network access,
planner inference, package/native support, concurrency/crash matrix, forced
rollback, event store, second journal, UI, catalogue work, or broad Maven
support. It does not modify the installed runtime.

## 9. Change control

This baseline is immutable after sealing. Any semantic correction requires a
separate approved-change receipt that states the old clause, replacement,
rationale, user authority, and both document hashes. Workflow status changes in
Gherkin do not alter this baseline.
