# Transactionality and Requirements Audit

Status: open findings for the `1.0.0-rc.1-SNAPSHOT` line.

Audit baseline: commit `5f47ad0` (`Preflight patch file state safely`).

## Executive conclusion

Current RefactorKit refactoring flows are **not fully transactional** in the
ACID, crash-safe, all-or-nothing sense.

They currently provide:

- immutable preview plans;
- stale-snapshot comparison when the integration caller supplies a freshly
  scanned hash;
- path, symbolic-link, overlap, and ordered file-state preflight;
- sequential workspace writes;
- an in-memory compensating rollback edit returned only after all writes finish;
- transaction-log persistence performed by integration callers after apply.

The accurate current description is:

> Previewable, preflighted batches with compensating rollback after a completely
> successful apply.

It is not yet accurate to promise:

> Durable atomic transactions that survive process, I/O, filesystem, or machine
> failure without partial state.

The distinction is release-blocking for API `1.0` because current requirements
use `atomic`, `transactional`, and `rollbackable` more strongly than the
implementation proves.

## What is already sound

The following safety properties have direct implementation/test evidence:

- a plan whose supplied current snapshot hash differs from `snapshotHash` is
  refused;
- normalized paths outside the workspace are refused;
- paths traversing symbolic-link components below the workspace are refused;
- overlapping `TextEdit` ranges inside one `FileEdit.Modify` are refused;
- ordered create/modify/rename/delete existence transitions are simulated before
  the first write;
- predictable later missing/existing-file failures are therefore detected before
  earlier writes;
- successful apply returns reverse-ordered compensating edits;
- successful modify/create/rename/delete batches can be rolled back in the tested
  no-concurrency, no-interruption case.

These are important transactional building blocks, but they do not close the
failure windows below.

## Critical findings

### TX-001 — Sequential writes can escape rollback on I/O failure

Status: **implementation closed on supported filesystems; destructive fault evidence remains a release gate**.

Before mutation, `PatchEngine` now renders the complete logical result, captures
pre/post images, builds compensation, and durably writes `PREPARED` then
`APPLYING`. I/O exceptions are caught. If current affected paths still match a
journaled pre/post image, compensation restores the pre-state and records
`ROLLED_BACK`; otherwise the durable record becomes `RECOVERY_REQUIRED` and
future managed writes are blocked. Startup recovery applies the same rule after
an interrupted `APPLYING` or `ROLLING_BACK` lifecycle.

Workspace content is now fully staged before commit, each file replacement is
atomically moved and durably flushed, and deletes/directories are followed by
directory flushes (`TX-006`). A process/I/O failure therefore leaves each path in
a journaled pre- or post-image suitable for compensation. Kill, disk-full,
power-loss, and filesystem fault-injection evidence remains mandatory before RC.

### TX-002 — Transaction metadata is persisted after workspace mutation

Status: **closed after the audited baseline**.

`PatchEngine` now owns journal persistence. It durably creates a versioned
`PREPARED` record containing operation, forward edit, transaction compensation,
and affected-path pre/post images, then atomically advances to `APPLYING` before
the first workspace write. Successful apply and rollback transition through
`APPLIED`, `ROLLING_BACK`, and retained `ROLLED_BACK`; incomplete/conflicting
recovery becomes `RECOVERY_REQUIRED`. CLI, daemon, MCP, LSP, recipes, and tests no
longer save or delete transaction metadata after mutation.

### TX-003 — Transaction log paths accept unvalidated transaction IDs

Status: **closed after the audited baseline**.

`TransactionId` now accepts only the generated `transaction-<UUIDv4>` grammar,
and CLI/daemon/LSP/MCP reject malformed client identifiers before transaction-log
access. `TransactionLog` normalizes its directory, derives contained file paths
only from validated IDs, rejects symbolic-link components and non-regular record
files, creates records without overwriting, and applies owner-only POSIX
permissions where supported. Malformed JSON, mismatched record IDs, unsafe paths,
and I/O failures produce coded `TransactionLogException` outcomes instead of
escaping as parsing errors.

Tests cover traversal-shaped identifiers, symbolic-link directories and records,
corrupt JSON, valid missing IDs, and protocol-level invalid-parameter mapping.
Atomic/durable journal persistence, schema/integrity metadata, quarantine, and
startup recovery remain open under `TX-002`, `TX-011`, and `TX-012`. TOCTOU
closure remains part of `TX-005`.

### TX-004 — Rollback can overwrite changes made after apply

Status: **closed for versioned journal transactions after the audited baseline**.

Normal rollback now requires an `APPLIED` journal record with matching pre/post
path sets and compares every current affected path against the exact journaled
post-image under the workspace lock. Modified files, modified rename targets,
recreated rename sources, recreated deleted files, removed created files, and
non-regular/symlink state are refused with `rollback.conflict` without changing
workspace content or blocking unrelated future transactions. The record remains
`APPLIED` with conflict detail so rollback can be retried.

`RollbackMode.FORCE` is a separately explicit destructive contract exposed as
`--force` or protocol `force=true`; it restores durable pre-images regardless of
post-apply content and records the override in retained history. Legacy records
without stable pre/post images refuse automatic rollback with
`rollback.preconditionUnavailable` and require manual recovery.

### TX-005 — No workspace isolation or cross-process lock

Status: **closed for RefactorKit-managed writes after the audited baseline**.

`PatchEngine` now acquires a non-blocking operating-system `FileLock` at
`.refactorkit/workspace.lock` around validation, apply, and rollback. Lock
ownership belongs to the process/channel, contention returns `workspace.locked`
immediately (zero wait timeout), and process termination releases stale OS lock
ownership; the regular marker file may remain safely. Symbolic-link or
non-regular lock paths are refused, and owner-only POSIX permissions are applied
where supported.

The snapshot-aware `apply(plan, ProjectSnapshot)` overload revalidates every
initially affected source or target after acquiring the lock. Changed, appeared,
missing, unscanned, or workspace-mismatched state is refused before mutation.
CLI, daemon, managed LSP/MCP apply, recipes, and golden execution use this path.
Tests cover same-process contention, symlink metadata refusal, and a file change
between scan and lock acquisition.

The hash-only apply overload has been removed, all internal tests use the same
snapshot-aware contract, and `ProjectSnapshot.hash` is now derived from files
rather than caller-overridable constructor state. Native editor-applied LSP edits
remain outside this managed lock boundary under `TX-007`. Engine-owned declared-source rescan and Java classpath evidence are now
implemented under `TX-014`.

### TX-006 — Direct file replacement is not crash-safe or durable

Status: **closed for filesystems satisfying the reported capability contract;
unsupported capability is refused**.

Managed apply, rollback, and recovery now render final affected-path images and
stage every non-deleted result in a same-directory temporary file before changing
user files. Staged bytes and preserved POSIX permissions are flushed, commit uses
required `ATOMIC_MOVE` replacement, deletes use atomic unlink semantics, and each
parent directory is flushed after replacement/deletion. Newly created parent
directories are flushed together with their parent entries. Temporary files are
cleaned on success and failure.

`PatchEngine.filesystemCapabilities()` reports file-store identity, atomic-move,
file-force, directory-force, the replacement strategy, and probe failures.
Apply refuses `filesystem.capabilityUnsupported` before journaling when the
workspace-root store cannot satisfy durable atomic replacement; a target on a
different mounted store that rejects atomic replacement fails with
`filesystem.atomicMoveUnsupported` and follows journal compensation or durable
`RECOVERY_REQUIRED` handling. Complete filesystem metadata restoration remains
separate under `TX-013`.

### TX-007 — LSP native and managed edit ownership

Status: **closed by explicit client-managed classification and versioned-buffer safety after the audited baseline**.

Native `textDocument/rename` and WorkspaceEdit-returning commands are explicitly
client-managed: responses publish `refactorkitEditOwnership=client-managed` and
`refactorkitRollbackAvailable=false`; RefactorKit does not advertise a journal or
rollback for editor-owned writes. Clients declaring `documentChanges` support
receive `OptionalVersionedTextDocumentIdentifier` entries. Open-document edits
carry the exact tracked version, while closed-document versions remain `null` as
per LSP semantics. Structural or open-document edits are refused with
`DOCUMENT_VERSION_MISMATCH (-32007)` when the client lacks versioned
`documentChanges` support.

`didOpen` captures full text/version, `didChange` requires one range-free full
sync update with a strictly increasing version, `didSave` reconciles disk text,
and `didClose` removes the overlay. Definition, references, rename, code actions,
symbols, diagnostics, and previews operate on a disk scan overlaid with current
open-buffer content rather than stale disk-only snapshots. Document lifecycle
changes invalidate pending managed plans.

The custom `refactorkit.applyPlan`/rollback path remains RefactorKit-managed and
WAL-backed, but refuses unsaved workspace buffers and any affected open document;
this prevents server disk writes from diverging from editor buffers. Tests prove
open-buffer semantic visibility, emitted version preconditions, monotonic-version
refusal, capability refusal, managed-write refusal with no disk mutation, and the
existing closed-document managed apply/rollback flow.

### TX-008 — Recipe transaction boundary

Status: **closed after the audited baseline**.

`RecipeEngine` no longer applies or journals individual steps. Every step is
previewed against an immutable snapshot produced by applying the previous step
through `WorkspaceEditSimulator`, which shares `PatchEngine`'s structural-segment
normalization and overlap semantics. A refusal, diagnostics failure, invalid
range, or later planning error therefore leaves both workspace and transaction
journal untouched.

After every step succeeds, the engine derives one recipe-wide delta from the
initial snapshot to the final staged snapshot and applies exactly one `PatchPlan`
against the initial engine-owned snapshot. The resulting write has one WAL
record, one transaction ID, and normal atomic apply/recovery/rollback semantics;
a no-op recipe writes no transaction. `movePackage` likewise previews each class
move against the result of the previous staged move instead of merging plans
calculated from one stale coordinate space. Tests cover later-step refusal with
zero writes/records, dependent rename-then-move steps, multi-class package moves,
and a single durable transaction.

### TX-009 — Multiple modifications of one file have ambiguous coordinates

Status: **closed after the audited baseline**.

Before validation and journaling, `PatchEngine` normalizes every segment between
structural file operations. All `FileEdit.Modify` entries for the same path in a
segment are merged into one original-content coordinate space, applied once in
descending-offset order, and persisted as one normalized forward edit. Global
per-path overlap validation therefore detects overlaps spanning separate input
entries, while structural create/delete/rename boundaries explicitly start a new
coordinate segment. Tests prove length-changing disjoint edits retain original
coordinates and cross-entry overlaps refuse without mutation.

### TX-010 — Text ranges are not fully bounds-validated during preflight

Status: **closed after the audited baseline**.

`TextEdits.offsetOf` now validates both line index and character-within-line,
including exact line-end insertion and the empty line after a trailing newline.
Preflight normalizes edits and renders the complete staged workspace result before
journal creation or workspace mutation. Invalid ranges return
`edit.rangeOutOfBounds`; other render failures return `edit.renderFailed`.
Tests prove an overlong character cannot cross into the next line and that refusal
leaves both source content and transaction journal unchanged.

## Additional major gaps

### TX-011 — Transaction records are insufficient for stable audit/recovery

Status: **partially closed**.

Records now contain schema version, operation, forward edit, compensation,
affected-file pre/post content images, pre-snapshot hash, lifecycle state,
update time, and failure detail. Successful rollback retains history as
`ROLLED_BACK`. Remaining stable fields include implementation/API version,
post-apply workspace snapshot, validation/recovery-attempt history, content
hashes separated from recovery payloads, and an integrity checksum.

### TX-012 — Transaction-log persistence is not atomic or corruption-safe

Status: **partially closed**.

New records use create-new plus file/directory durable flush. Lifecycle updates
write and fsync a same-directory temporary file, require atomic replacement, and
fsync the journal directory. Parsing/schema/path failures remain coded. Still
open: integrity checksum, schema migration, corrupt-record quarantine, explicit
filesystem capability reporting, and fault-injected proof for every persistence
boundary.

### TX-013 — Rollback does not restore full filesystem state

Delete/recreate rollback restores text content only. It does not restore file
permissions, ownership, timestamps, ACLs, extended attributes, encoding metadata,
or created parent-directory state. Implicitly created directories remain after
rollback. Directory create/rename operations described by requirements are not
represented in the current `FileEdit` model.

### TX-014 — Apply snapshot scope ownership

Status: **closed for declared source and Java classpath scope after the audited baseline**.

The hash-only apply overload is removed and `ProjectSnapshot.hash` now binds
module roots, declared source roots/classpath paths, source extensions, ignored
directory policy, language IDs, file paths, and contents. Java snapshots declare
`.java` even when initially empty; generic snapshots declare their complete
extension map and ignore policy. Under the workspace lock, `PatchEngine`
requires a non-empty declared extension set, independently walks the hash-bound
source roots (or the workspace root fallback), without following symlink
directories, re-reads all matching files, and compares
a newly computed scope hash before journaling. Added, removed, changed, omitted,
or metadata-altered sources refuse as `snapshot.scopeChanged` with structured
added/missing/changed detail. Tests prove a library caller cannot reuse a stale
snapshot that omits a newly appeared source, while declared ignored build output
does not create false staleness.

Java scans also capture hash-bound classpath evidence for every active classpath
entry, prospective conventional compiled-output directory, local JAR directory,
and generated classpath declaration file. Files and recursively traversed
directories are SHA-256 fingerprinted; absent discovery locations retain an
explicit `missing` fingerprint. Under the workspace lock, apply recomputes all
fingerprints before journaling, refuses changes as `snapshot.classpathChanged`,
and rejects active entries without evidence as `snapshot.scopeInvalid`. This
covers JAR replacement, compiled-output mutation/appearance, local JAR membership,
and generated classpath declaration changes. As with workspace sources, a hostile
writer changing an artifact after locked validation is outside the cooperative
writer guarantee and remains destructive-concurrency test scope.

### TX-015 — Diagnostics are not a central transaction gate

Most planners leave `diagnosticsBefore` and `diagnosticsAfterPreview` empty.
Apply paths generally do not run post-apply diagnostics and do not automatically
roll back a diagnostic regression. Only selected operations/recipe steps perform
partial diagnostic simulation.

This does not satisfy the stronger requirements that plans be diagnosed before
apply and validated after apply.

### TX-016 — Approval is advisory, not enforced state

`requiresUserApproval` is metadata. `PatchEngine.apply` does not require an
approval token/state. Calling apply with a preview plan is treated as sufficient.
The stable contract must define whether explicit apply invocation is approval or
whether approval is a separately auditable transition.

### TX-017 — Integration error mapping is inconsistent

Status: **partially closed**.

Daemon/LSP rollback post-image conflicts now map to stable
`ROLLBACK_CONFLICT (-32005)` and incomplete recovery maps to
`RECOVERY_REQUIRED (-32006)`; force remains explicit. Apply paths still map many
`ApplyResult.Refused` causes to `SNAPSHOT_CHANGED`, even for overlap, unsafe path,
missing file, collision, capability refusal, or another validation error.

### TX-018 — Daemon state refresh after mutation

Status: **closed after the audited baseline**.

After successful apply or rollback, the daemon rescans the workspace, atomically
replaces its session snapshot, clears every pending plan derived from the old
state, and returns the refreshed `snapshotHash`. Subsequent project summary and
symbol queries therefore observe the committed or restored state immediately.
Tests verify response/session hash equality and symbol visibility after both
apply and rollback.

## Flow classification

| Flow | Preflight | Workspace writes | Durable intent before write | Rollback record | Current classification |
|------|-----------|------------------|-----------------------------|-----------------|------------------------|
| CLI `--apply` | yes, under lock | staged durable per-path atomic replacement | versioned WAL | retained lifecycle record | managed recoverable transaction on supported filesystems |
| Daemon `refactor.apply` | yes, under lock | staged durable per-path atomic replacement | versioned WAL | retained lifecycle record | managed transaction with immediate session refresh (`TX-018` closed) |
| MCP `apply_refactoring` | yes, under lock | staged durable per-path atomic replacement | versioned WAL | retained lifecycle record | managed recoverable transaction on supported filesystems |
| LSP `refactorkit.applyPlan` | yes, under lock | staged durable per-path atomic replacement | versioned WAL | retained lifecycle record | managed transaction; native client edits are separate |
| LSP native rename/WorkspaceEdit command | overlay-aware versioned planning | editor/client writes | no | no RefactorKit transaction for client write | explicitly client-managed; open buffers carry checked versions (`TX-007` closed) |
| Recipe apply | all steps staged in memory | one recipe-wide managed apply | one versioned WAL record | one retained lifecycle record | single managed transaction (`TX-008` closed) |
| Direct library `PatchEngine.apply` | engine-owned source/classpath validation under lock | staged durable per-path atomic replacement | versioned WAL | retained lifecycle record | managed recoverable transaction on supported filesystems |

## Requirements criticalities

The current requirements should be corrected before API `1.0` freeze.

1. **Define terminology.** Separate atomicity, consistency validation, isolation,
   durability, compensation, rollback, and crash recovery. “Atomic where possible”
   conflicts with unconditional statements that every modification is atomic.
2. **Define the transaction boundary.** State whether one plan, one recipe, one
   LSP edit, and diagnostics are inside or outside the transaction.
3. **Define failure models.** Include process kill, power loss, disk full,
   permission change, filesystem without atomic move, log corruption, concurrent
   writer, and editor-buffer divergence.
4. **Define rollback conflict policy.** Rollback must not silently discard changes
   made after apply. Specify normal, forced, and manual recovery modes.
5. **Define durable journal lifecycle and retention.** Include state machine,
   schema/versioning, integrity, permissions, cleanup, audit retention, and startup
   recovery.
6. **Define snapshot scope.** Specify included paths/content/metadata, per-file
   expected hashes, generated files, case sensitivity, and symlink behavior.
7. **Define diagnostics gates.** State which diagnostic engine is authoritative,
   what regression blocks commit, and what happens if post-apply diagnostics fail.
8. **Define concurrency.** Specify one-writer locking, timeout, stale lock,
   cancellation, and multi-process behavior.
9. **Define LSP ownership.** Distinguish RefactorKit-managed writes from edits
   applied by an editor and require document-version checks for unsaved buffers.
10. **Define recipe semantics.** Choose a single staged transaction or a durable
    saga; do not describe both as equivalent atomic rollback.
11. **Align the edit model.** Either add directory operations and metadata
    restoration or narrow the requirement that currently advertises them.
12. **Define approval.** Decide whether an explicit apply call is sufficient or an
    auditable approval transition/token is required.

## Recommended closure order

1. Integrate diagnostics and remaining stable error categories into commit gates.
2. Add fault-injection, kill/restart, concurrency, corruption, and mounted
   filesystem capability tests before `v1.0.0-rc.1`.

## Stable-release verdict

The current implementation is suitable for reviewed local preview/apply/rollback
pilots, but **transactionality is not yet sufficient for `v1.0.0` stable**.

`v1.0.0-rc.1` must remain blocked until at least TX-001 through TX-010 have either
been closed with evidence or explicitly removed from the advertised stable
contract with precise degraded guarantees.
