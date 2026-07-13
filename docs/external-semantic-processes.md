# External semantic process lifecycle

Status: P4 lifecycle, bounded LSP transport, source-overlay, and untrusted edit
normalization implemented; real parser bindings and integration-surface schemas
remain in progress.

`ExternalSemanticProcessManager` owns bounded local compiler/language-server
processes. It does not grant semantic mutation authority and is not an OS sandbox.

## Launch contract

`SemanticProcessSpec` requires:

- canonical unique process ID;
- absolute regular-file executable;
- existing working directory;
- at most 128 bounded arguments;
- an explicit environment of at most 32 entries;
- no NULs or secret-shaped environment keys (`TOKEN`, `SECRET`, `PASSWORD`,
  `CREDENTIAL`, `AUTH`, `PRIVATE_KEY`);
- bounded stdout, stderr, and graceful shutdown limits.

The host environment is cleared rather than inherited. Executable real path and
SHA-256, argument-vector SHA-256 (not raw arguments), canonical working directory,
PID, and start timestamp are recorded as `SemanticProcessProvenance`. The
executable is hashed before and after launch; drift terminates the process and
refuses provenance.

The manager defaults to eight concurrent processes and supports at most 64.
Duplicate IDs and capacity overflow fail before launch. Naturally exited
processes release their registry slot.

## Runtime limits and cancellation

Stdout is exposed through a counting stream. Crossing the configured total byte
limit terminates the process tree and raises `SemanticProcessLimitException`.
Stderr is drained concurrently into a bounded buffer; overflow sets an explicit
truncation flag and terminates the tree. This prevents child stderr backpressure
from deadlocking the parent.

Cancellation and close are idempotent. Shutdown first requests normal process and
descendant termination, waits for the bounded grace period, then forcibly kills
remaining descendants and the root. Tests launch a JVM child process and prove
process-tree death across the native CI matrix.

## Security boundary

This lifecycle manager is not a filesystem, syscall, or network sandbox. A caller
must not treat it as permission to expose an untrusted workspace to arbitrary
code. Current stable adapters do not use external executable proposals for
managed mutation. `ExternalLspAdapter` now runs through this manager and provides byte-counted
`Content-Length` framing, 8 MiB frames/headers limits, bounded request deadlines,
initialization validation, server version and capability-hash provenance, and
fail-closed transport cancellation. Production callers can start it from a
`ProjectSnapshot`; RefactorKit materializes a bounded source-only temporary
workspace, remaps outbound/inbound URIs, detects server writes to materialized
sources, and deletes the overlay on every lifecycle exit. Direct-root start is
retained only as a prototype compatibility path.

Standard LSP `changes` and `documentChanges` are parsed strictly. Unknown schema,
non-file URIs, unsafe overwrite/ignore/recursive options, paths outside the
overlay/workspace, symlink traversal, generated roots, invalid ranges, overlap,
structural conflicts, and bounded-size violations are refused. Accepted output is
still only an unapproved `LANGUAGE_SERVER` proposal.

Future production language-server/compiler adapters still require:

- real backend-specific capability matrices and document synchronization;
- diagnostics/provenance schema exposure across CLI/daemon/LSP/MCP;
- hostile-workspace, restart, and packaged process-kill acceptance.

No process output bypasses `PatchPlan`, evidence validation, diagnostics,
approval, `PatchEngine`, WAL, or rollback.
