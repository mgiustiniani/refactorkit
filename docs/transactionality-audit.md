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

Severity: **critical**.

`PatchEngine.applyEdit` writes each file directly in a loop. It does not catch an
exception from directory creation, read, write, delete, or move and does not
execute already-collected rollback edits on failure.

Examples include disk-full, permission changes, process interruption, device
errors, file locks, and a race after preflight. If edit N fails after edits 1…N-1,
the call can throw with a partially changed workspace and no returned
`Transaction`.

Preflight prevents predictable state errors; it does not make I/O atomic.

Required closure:

- stage writes safely;
- persist recovery intent before mutation;
- catch and classify I/O failure;
- compensate already-applied operations where possible;
- return an explicit `RECOVERY_REQUIRED` state if compensation is incomplete.

### TX-002 — Transaction metadata is persisted after workspace mutation

Severity: **critical**.

CLI, daemon, MCP, and LSP `applyPlan` call `PatchEngine.apply` first and call
`TransactionLog.save` only after `ApplyResult.Applied`. A crash between the first
workspace write and log save—or after all writes but before save—leaves no durable
rollback record. A log-write failure has the same outcome.

This directly conflicts with the requirement to write transaction/recovery
metadata before apply.

Required closure: use a write-ahead journal with lifecycle states such as
`PREPARED`, `APPLYING`, `APPLIED`, `ROLLING_BACK`, `ROLLED_BACK`, and
`RECOVERY_REQUIRED`, persisted atomically and durably before/through mutation.

### TX-003 — Transaction log paths accept unvalidated transaction IDs

Severity: **critical security/integrity**.

`TransactionId` accepts arbitrary strings. `TransactionLog.load` and `delete`
resolve `"${id.value}.json"` beneath `logDir` without identifier validation,
normalization, or containment checks. CLI/daemon/LSP/MCP accept transaction IDs
from clients.

Traversal-shaped IDs can address JSON files outside the transaction directory.
The log directory itself is also written directly and is not protected by
`PatchEngine` symbolic-link checks.

Required closure:

- validate transaction IDs against the generated identifier grammar;
- normalize and enforce containment for every log path;
- reject symlink traversal;
- use owner-only permissions where supported;
- treat malformed/corrupt entries as structured errors, never uncaught parsing
  exceptions.

### TX-004 — Rollback can overwrite changes made after apply

Severity: **critical data-loss risk**.

`Transaction` stores `snapshotHashBefore` but rollback does not validate the
current/post-apply state. Rollback edits use overwrite-capable creates. If a user,
editor, generator, or second RefactorKit process modifies an affected file after
apply, rollback can overwrite that newer content.

Required closure:

- store expected post-apply hashes per affected path and/or a post-apply snapshot;
- refuse normal rollback when current state differs;
- expose explicit conflict/recovery modes rather than silently overwriting;
- define behavior for recreated deleted files and modified rename targets.

### TX-005 — No workspace isolation or cross-process lock

Severity: **critical**.

Snapshot scan, validation, and writes are not protected by a workspace lock.
Multiple CLI/daemon/LSP/MCP processes can validate the same snapshot and then
interleave writes. Files can also change between preflight and mutation
(time-of-check to time-of-use).

Required closure: define one-writer workspace locking, lock ownership/timeout/
stale-lock recovery, and revalidate affected file hashes while holding the lock.

### TX-006 — Direct file replacement is not crash-safe or durable

Severity: **high**.

Modify/create use direct `writeText`, which can truncate a destination before all
new bytes are durable. Rename uses `Files.move` without requesting/negotiating
`ATOMIC_MOVE`. There is no temp-file staging, fsync of file/directory entries, or
filesystem-capability reporting.

Required closure: define atomicity by filesystem capability and implement
same-directory temp files, durable flush where supported, atomic replace/move
where available, and explicit degraded guarantees otherwise.

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

Current records contain ID, plan ID, apply time, pre-snapshot hash, and rollback
edits. They omit schema version, operation, forward edit, affected-file pre/post
hashes, post-apply snapshot hash, implementation/API version, lifecycle status,
validation results, recovery attempts, and integrity checksum.

The log is deleted after successful rollback, removing audit history, and the
transaction returned by rollback (effectively redo metadata) is discarded.

### TX-012 — Transaction-log persistence is not atomic or corruption-safe

`TransactionLog.save` writes the final JSON path directly. There is no temporary
file/atomic rename, fsync, checksum, schema migration, quarantine, or structured
corruption response. A truncated log can throw during `load`.

### TX-013 — Rollback does not restore full filesystem state

Delete/recreate rollback restores text content only. It does not restore file
permissions, ownership, timestamps, ACLs, extended attributes, encoding metadata,
or created parent-directory state. Implicitly created directories remain after
rollback. Directory create/rename operations described by requirements are not
represented in the current `FileEdit` model.

### TX-014 — `PatchEngine` trusts a caller-supplied current hash

The library API receives `currentSnapshotHash`; it does not calculate or verify
that hash itself. First-party integrations rescan before apply, but a library
consumer can pass `plan.snapshotHash` without inspecting current files. Stable
library safety cannot rely only on caller discipline.

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

Daemon/LSP apply paths map all `ApplyResult.Refused` cases to
`SNAPSHOT_CHANGED`, even when the actual cause is overlap, unsafe path, missing
file, collision, or another validation error. Rollback refusal is often mapped to
`INTERNAL_ERROR`. This weakens deterministic recovery decisions.

### TX-018 — Daemon state is stale after successful apply/rollback

The daemon apply/rollback paths rescan locally for validation but do not assign the
new snapshot back to the session, unlike MCP/LSP refresh behavior. Subsequent
queries can observe stale session metadata until another project-open/refresh
path occurs.

## Flow classification

| Flow | Preflight | Workspace writes | Durable intent before write | Rollback record | Current classification |
|------|-----------|------------------|-----------------------------|-----------------|------------------------|
| CLI `--apply` | yes | sequential, in process | no | saved after successful apply | compensatable batch, not crash-safe transaction |
| Daemon `refactor.apply` | yes | sequential, in process | no | saved after successful apply | compensatable batch; stale session afterward |
| MCP `apply_refactoring` | yes | sequential, in process | no | saved after successful apply | compensatable batch, not crash-safe transaction |
| LSP `refactorkit.applyPlan` | yes | sequential, in process | no | saved after successful apply | compensatable batch, not crash-safe transaction |
| LSP native rename/code action | server plans only | editor/client writes | no | no RefactorKit transaction for client write | client-managed edit, not RefactorKit transaction |
| Recipe apply | per step | sequential transactions | no | one log after each successful step | saga with best-effort compensation |
| Direct library `PatchEngine.apply` | only with caller-supplied hash | sequential | no | caller responsibility | unsafe to describe as durable transaction |

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

1. Fix transaction-ID/log path traversal and log symlink containment.
2. Add workspace lock and affected-file precondition hashes.
3. Introduce a versioned write-ahead transaction journal and startup recovery.
4. Stage/render all file results, merge same-file edits, and validate all bounds
   before mutation.
5. Use temp-file plus atomic/durable replacement where supported; implement
   compensation and `RECOVERY_REQUIRED` reporting otherwise.
6. Add post-state hashes and conflict-safe rollback.
7. Define recipe saga/transaction and LSP client/server transaction boundaries.
8. Integrate diagnostics and stable error categories into commit/rollback gates.
9. Add fault-injection, kill/restart, concurrency, corruption, and filesystem
   capability tests before `v1.0.0-rc.1`.

## Stable-release verdict

The current implementation is suitable for reviewed local preview/apply/rollback
pilots, but **transactionality is not yet sufficient for `v1.0.0` stable**.

`v1.0.0-rc.1` must remain blocked until at least TX-001 through TX-010 have either
been closed with evidence or explicitly removed from the advertised stable
contract with precise degraded guarantees.
