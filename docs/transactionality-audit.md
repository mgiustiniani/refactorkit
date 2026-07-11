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
remain outside this managed lock boundary under `TX-007`. Full engine-owned
workspace rescan scope remains a separate `TX-014` concern.

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

### TX-007 — LSP native edits bypass RefactorKit apply/rollback

Severity: **high contract mismatch**.

`textDocument/rename` and several code actions return an LSP `WorkspaceEdit`.
The editor applies that edit outside `PatchEngine`; RefactorKit does not create or
persist a transaction for the actual client-side write. Only the custom
`refactorkit.applyPlan` path performs server-managed apply/logging.

The server also refreshes snapshots from disk on `didOpen`/`didChange` without
tracking unsaved document content, and emits `textDocument.version = null`. A
client can therefore apply edits calculated from stale disk content to an unsaved
buffer.

Required closure: classify native LSP edits as client-managed/non-RefactorKit
transactions, or implement a versioned client-apply acknowledgement/journal
contract. Stable docs must not promise RefactorKit rollback for writes performed
by the editor.

### TX-008 — Recipes are sagas, not atomic transactions

Severity: **high**.

`RecipeEngine` applies and logs each step as a separate transaction. On a later
logical failure it attempts reverse compensation. A process crash, log-save
failure, rollback conflict, or I/O failure can leave a partially applied recipe.
Dry-run steps are not all evaluated against one evolving staged workspace.

`movePackage` also merges plans built from one snapshot; multiple modifications
of the same file can remain separate edits whose ranges were calculated against
the same original content but are applied sequentially.

Required closure: either stage/merge/validate one recipe-wide `PatchPlan` and
commit it once, or explicitly specify recipe execution as a durable saga with
recovery status and per-step journal semantics.

### TX-009 — Multiple modifications of one file have ambiguous coordinates

Severity: **high**.

Overlap validation is local to each `FileEdit.Modify`. A `WorkspaceEdit` may
contain multiple modifies for the same path. Later ranges are applied to content
already changed by earlier modifies, although planners commonly calculate ranges
from the original snapshot. Cross-edit overlap and coordinate drift are not
rejected.

Required closure: define one coordinate space per file/plan, merge all text edits
for a path before apply, and validate overlap/bounds across the complete merged
set.

### TX-010 — Text ranges are not fully bounds-validated during preflight

Severity: **high**.

`TextEdits.offsetOf` verifies the line index but does not verify that `character`
is within that line. `TextEdits.apply` can therefore throw or address an
unexpected offset during mutation. Preflight does not render every modify against
the simulated content.

Required closure: validate line/character bounds and render every staged file
before any workspace write.

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

### TX-014 — Apply snapshot ownership is not fully engine-derived

Status: **narrowed after the audited baseline**.

The hash-only apply overload is removed and `ProjectSnapshot.hash` is computed
from snapshot files rather than accepted as a constructor argument. Apply now
requires a `ProjectSnapshot` and revalidates all initially affected paths under
the workspace lock. A library consumer can still reuse or construct a stale
snapshot whose file list omits a newly appeared, semantically relevant but
previously unaffected source. Stable closure therefore still requires an
engine-owned rescan/scope manifest rather than caller discipline alone.

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

### TX-018 — Daemon state is stale after successful apply/rollback

The daemon apply/rollback paths rescan locally for validation but do not assign the
new snapshot back to the session, unlike MCP/LSP refresh behavior. Subsequent
queries can observe stale session metadata until another project-open/refresh
path occurs.

## Flow classification

| Flow | Preflight | Workspace writes | Durable intent before write | Rollback record | Current classification |
|------|-----------|------------------|-----------------------------|-----------------|------------------------|
| CLI `--apply` | yes, under lock | sequential, in process | versioned WAL | retained lifecycle record | recoverable managed batch; workspace writes not yet durably staged |
| Daemon `refactor.apply` | yes, under lock | sequential, in process | versioned WAL | retained lifecycle record | recoverable managed batch; stale session afterward |
| MCP `apply_refactoring` | yes, under lock | sequential, in process | versioned WAL | retained lifecycle record | recoverable managed batch; workspace writes not yet durably staged |
| LSP `refactorkit.applyPlan` | yes, under lock | sequential, in process | versioned WAL | retained lifecycle record | recoverable managed batch; workspace writes not yet durably staged |
| LSP native rename/code action | server plans only | editor/client writes | no | no RefactorKit transaction for client write | client-managed edit, not RefactorKit transaction |
| Recipe apply | per step | sequential journaled transactions | per step | retained per-step lifecycle | durable saga boundary remains open |
| Direct library `PatchEngine.apply` | snapshot-aware, under lock | sequential | versioned WAL | retained lifecycle record | recoverable managed batch; scan scope and durable workspace staging remain open |

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

1. Make apply snapshot scope engine-owned so newly appeared relevant files cannot
   be omitted by library callers (`TX-014`).
2. Merge same-file edits into one coordinate space and validate all line/character
   bounds before mutation (`TX-009`, `TX-010`).
3. Define recipe saga/transaction and LSP client/server transaction boundaries.
4. Integrate diagnostics and remaining stable error categories into commit gates.
5. Add fault-injection, kill/restart, concurrency, corruption, and mounted
   filesystem capability tests before `v1.0.0-rc.1`.

## Stable-release verdict

The current implementation is suitable for reviewed local preview/apply/rollback
pilots, but **transactionality is not yet sufficient for `v1.0.0` stable**.

`v1.0.0-rc.1` must remain blocked until at least TX-001 through TX-010 have either
been closed with evidence or explicitly removed from the advertised stable
contract with precise degraded guarantees.
