# Transactionality and Requirements Audit

Status: transactionality findings closed for the qualified `1.0.0-rc.1-SNAPSHOT` contract.

Audit baseline: commit `5f47ad0` (`Preflight patch file state safely`).

## Executive conclusion

RefactorKit managed file edits now form WAL-backed, recoverable transactions on
filesystems that satisfy the reported capability contract. One engine-owned
workspace lock covers snapshot/precondition validation, diagnostics, durable
`PREPARED`/`APPLYING` intent, staged per-path atomic replacement, lifecycle
completion, rollback, and startup recovery. Every affected path is checksummed as
a pre/post image, so interruption leaves a classifiable state that is compensated
or blocked as `RECOVERY_REQUIRED` rather than guessed.

The qualified v1 contract is deliberately narrower than distributed ACID:

- isolation is cooperative among RefactorKit-managed writers;
- durability relies on the operating system/filesystem honoring successful file
  force, atomic move, and directory force operations;
- hostile external writers and storage devices that acknowledge but do not honor
  flushes are outside the guarantee;
- editor-applied native LSP edits are explicitly client-managed;
- stable `WorkspaceEdit` operations modify/create/delete/rename files and may
  create required parent directories, but do not expose standalone directory
  create/rename operations.

Within those declared boundaries, the transactionality audit no longer blocks
`v1.0.0-rc.1`. Other release-plan gates remain independent.

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

Status: **closed for the reported supported-filesystem contract**.

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
a journaled pre- or post-image suitable for compensation. Deterministic fault
hooks now cover post-force staging and each committed image. Tests prove a
staging disk-full failure leaves content unchanged and removes temporary files,
a failure after the first of multiple replacements compensates every path, and
a second injected compensation failure persists `RECOVERY_REQUIRED` before a
clean restart retries and completes compensation. A subprocess acceptance test now force-kills a real JVM after the first durable
replacement of a two-file apply, verifies the retained `APPLYING` WAL and mixed
pre/post workspace state, then starts a clean engine and proves exact
compensation to `ROLLED_BACK`. Torn journals and distinct mounted stores are also
covered. Machine power-loss behavior is qualified by the reported force/atomic-
move contract: RefactorKit refuses filesystems that cannot demonstrate required
primitives, while hardware that falsely acknowledges flushes is outside the v1
software guarantee.

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
Atomic/durable persistence, versioned integrity metadata, quarantine, startup
recovery, and under-lock TOCTOU controls subsequently closed TX-002, TX-005,
TX-011, and TX-012.

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

Status: **closed after the audited baseline**.

Records now contain schema version, implementation/API version, operation,
forward edit, compensation, affected-file pre/post content images, pre/post
engine-scope snapshot hashes, lifecycle state, update time, and failure detail.
An append-only timestamped event history begins with the successful filesystem,
snapshot, edit, approval, precondition, and diagnostics validation outcome and
retains each apply, rollback, refusal-detail, and recovery transition. Successful
rollback therefore preserves the complete `PREPARED` through `ROLLED_BACK`
sequence rather than only its terminal state. Legacy records remain readable
with unknown version/hash metadata and empty history. Schema-v8 additionally stores and verifies a SHA-256 content hash beside every
nullable recovery payload; a mismatch quarantines the record even when its outer
record checksum is internally consistent. Schema-v3 records introduced this
metadata plus a canonical SHA-256 integrity checksum covering transaction, approval, edits, pre/post images,
lifecycle, timestamps, and failure detail; tampering fails as
`transaction.corrupt`.

### TX-012 — Transaction-log persistence is not atomic or corruption-safe

Status: **closed for the reported durability contract after the audited baseline**.

New records use create-new plus file/directory durable flush. Lifecycle updates
write and fsync a same-directory temporary file, require atomic replacement, and
fsync the journal directory. Parsing/schema/path failures remain coded. Still
Schema-v2/v3 writes include and verify canonical SHA-256 checksums. Schema-v1
records remain readable; v1/v2 records are atomically migrated to v3 on the next
lifecycle update, with the original v2 canonical checksum vocabulary preserved
for backward verification. Malformed, ID-mismatched, or checksum-invalid records are atomically moved to an
owner-only `.quarantine` directory and reported as `transaction.quarantined`.
Any retained quarantine record blocks list/load/new writes and therefore startup
recovery until explicit manual review/removal; quarantine failure is separately
coded. Capability reporting now names workspace and journal file stores, whether they
are shared, and the journal durability strategy alongside atomic-move/file-force/
directory-force probe results. Deterministic journal hooks prove that a failure
after new-record force leaves a complete `PREPARED` intent, a lifecycle temp-file
failure preserves the prior record and cleans the temp, and a post-atomic-move/
pre-directory-force failure leaves the complete new state readable by restart.
Real subprocess kills now cover all three journal write boundaries: after a new
record is forced the complete `PREPARED` intent is readable; after lifecycle temp
force the prior checksummed record remains authoritative while the uncommitted
owner-only temp is non-authoritative and durably removed under the workspace lock during restart recovery; after lifecycle atomic move the complete new
`APPLYING` record and event history are readable. Workspace staging, partial
commit, compensation failure, and restart retry also have deterministic evidence.
Raw torn-byte tests truncate complete checksummed records at 1%, 25%, 50%, and
99%; every fragment is atomically quarantined, the original path disappears, and
subsequent journal activity remains blocked for review. A mounted-store test uses
`/dev/shm` when available to keep the workspace and WAL on distinct file stores,
asserts accurate capability identity, and proves apply/rollback lifecycle
completion across the boundary. The remaining physical power-loss uncertainty is
explicitly outside the software guarantee when storage falsely acknowledges
successful force operations.

### TX-013 — Rollback filesystem metadata and directory state

Status: **closed for the narrowed v1 file-edit contract after the audited baseline**.

Schema-v8 pre/post `FileImage`s retain independent content hashes, optional POSIX
permission sets, last-modified timestamps, owner names, POSIX group names, and
sorted Base64 user-defined attributes. Modify/rename apply preserves source attributes; rollback and
recovery restore the exact captured attribute set. Unsupported attribute restore
fails closed as `filesystem.attributesUnsupported`. Schema-v2 through v7 checksum vocabularies remain backward verifiable and migrate on lifecycle update.
Managed apply records permissions before mutation and desired post-image
permissions; normal/forced rollback and startup compensation restore the
journaled pre-image permissions rather than deriving them from post-apply files.
Tests prove modify and rename permissions survive apply and return exactly after
rollback on POSIX filesystems. Schema-v1 records remain readable with unknown
permissions.

Schema-v2 records also retain every parent directory created by the transaction.
Rollback and crash compensation remove them deepest-first with durable parent
flushes. Normal rollback refuses before writes when a transaction-created
directory contains any external path, preventing data loss; tests cover both
exact cleanup and conflict refusal.

The v1 managed-text contract is explicitly UTF-8 only. Malformed UTF-8 fails
closed as `snapshot.scopeUnreadable` before WAL creation; a UTF-8 BOM is retained
as content, and byte-for-byte BOM restoration is covered through delete/rollback.
Other encodings require a future explicit adapter/configuration contract rather
than guessed transcoding. Schema-v7 also records ordered platform ACL entries (type, principal,
permissions, and inheritance flags), transfers them across modify/rename apply,
and restores them on rollback/recovery wherever `AclFileAttributeView` is
available. Unsupported restoration fails closed as `filesystem.aclUnsupported`;
journal round-trip is covered on every platform and live preservation tests run
conditionally when the filesystem exposes the view. Standalone directory create/rename operations are explicitly outside the stable
v1 `WorkspaceEdit` contract. Required parent-directory creation and exact cleanup
remain transaction-managed, so the prior requirement/model mismatch is closed by
narrowing rather than by advertising unsupported directory mutations.

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

### TX-015 — Central diagnostics transaction gate

Status: **closed after the audited baseline**.

`PatchEngine` now owns diagnostics-regression evaluation under the workspace
lock after snapshot/precondition validation and before WAL creation. A configured
`DiagnosticsGate` diagnoses the current snapshot, applies the normalized edit to
an immutable staged snapshot, diagnoses the exact post-image, and refuses any
increase in ERROR diagnostics as `diagnostics.regression`; provider failure
refuses as `diagnostics.unavailable`. Both map to `DIAGNOSTICS_FAILED (-32015)`.
Existing identical errors are tolerated using multiset comparison, while new or
additional errors block without workspace or journal writes.

CLI, daemon, managed LSP, MCP, recipe, and golden-test flows configure the JDT
Java diagnostics provider centrally rather than trusting planner-populated
metadata. Because `PatchEngine` commits exactly the validated staged post-images,
the pre-WAL simulated snapshot is the authoritative post-apply diagnostic state.
Core tests prove regression refusal and preservation of pre-existing errors. The
ungated two-/three-argument `PatchEngine.apply` overloads were removed before API
freeze: every direct library caller must now provide both `ApplyAuthorization`
and `DiagnosticsGate`. Test-only package extensions make any intentionally
disabled gate explicit rather than a production default.

### TX-016 — Approval semantics and audit state

Status: **closed after the audited baseline**.

The stable contract defines an explicit managed apply invocation as approval;
there is no separate approval-token lifecycle. `PatchEngine` accepts an
`ApplyAuthorization` naming surface and actor, refuses approval-required plans
with `approval.required`/`APPROVAL_REQUIRED (-32014)` when authorization is
missing, and creates no journal record on refusal. Successful transactions retain
an immutable `ApprovalRecord` with `EXPLICIT_APPLY` or `NOT_REQUIRED`, surface,
actor, and timestamp. Direct-library apply requires an explicit `ApplyAuthorization`; CLI, daemon,
managed LSP, MCP, recipe, and testkit paths provide their specific surface
identifiers.
Legacy journal DTOs remain readable with `LEGACY_UNRECORDED`. Tests prove refusal
without writes, persisted approval identity, and backward-compatible journal
round trips.

### TX-017 — Integration error mapping

Status: **closed after the audited baseline**.

Core-owned deterministic mapping now classifies every managed apply refusal by
diagnostic code and risk precedence. Snapshot/precondition changes remain
`SNAPSHOT_CHANGED (-32002)`; recovery is `RECOVERY_REQUIRED (-32006)`; validation,
lock, filesystem capability, unsafe path, file conflict, and apply/journal failures
map respectively to `PLAN_VALIDATION_FAILED (-32008)`, `WORKSPACE_LOCKED (-32009)`,
`FILESYSTEM_UNSUPPORTED (-32010)`, `UNSAFE_PATH (-32012)`, `FILE_CONFLICT
(-32013)`, `APPROVAL_REQUIRED (-32014)`, `DIAGNOSTICS_FAILED (-32015)`, and
`APPLY_FAILED (-32011)`. Rollback uses the same central policy for
conflict, recovery, path/filesystem, and apply failures. Daemon and LSP throw these
stable codes; MCP refusal text includes the mapped numeric category. Table-driven
core tests prove category coverage and order-independent highest-risk precedence.

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

## Closure record

TX-001 through TX-018 are closed for the qualified managed-file transaction
contract described above. Evidence includes deterministic boundary faults, real
process kills, restart compensation, rollback conflicts, corruption quarantine,
raw truncation, distinct mounted stores, metadata restoration, diagnostics,
approval, protocol mapping, and integration state refresh.

## Stable-release verdict

The transactionality gate is **sufficient for `v1.0.0-rc.1` evaluation** under
the reported filesystem and cooperative-writer contract. This does not by itself
authorize the RC: Java compatibility, API/protocol freeze, performance, packaging,
supply-chain, migration, and full acceptance gates remain governed by the release
plan.
