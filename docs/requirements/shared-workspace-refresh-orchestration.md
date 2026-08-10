# Shared workspace-refresh orchestration boundary

Status: implemented candidate; promotion requires focused regression, surface regression, and independent review.

## Requirement

`REQ-SHARED-WORKSPACE-REFRESH-001` completes the next targeted runtime-orchestration extraction without creating a stateful God service.

1. Core shall provide one stateless `WorkspaceRefreshCoordinator` that owns only scan ordering, exact workspace-root validation, snapshot-hash comparison, and prepare-on-change ordering.
2. An unchanged scan shall return the exact current snapshot instance and shall not invoke changed-state preparation.
3. A changed scan shall invoke preparation exactly once with exact current/next instances before returning the next snapshot and prepared state.
4. Scanner and preparation failures shall escape unchanged; the coordinator shall catch no surface exception and perform no filesystem, process, lock, WAL, apply, rollback, index, lease, or protocol action.
5. Daemon saved-file refresh, daemon post-apply/rollback refresh, LSP rescan, and MCP post-apply/rollback refresh shall adopt this ordering at their existing call sites.
6. Watcher state, stored snapshot assignment, index contribution, semantic-session cleanup, pending plans, diagnostics, protocol responses, `PatchEngine`, and rollback policy remain surface-owned.

## Related shared seams

Apply diagnostics selection remains owned by `ManagedApplyDiagnosticsGateSelector`; managed rollback by `ManagedRollbackExecutor`; Java preview request mapping by `JavaRefactoringPreviewDispatcher`. This extraction does not introduce the previously rejected general `ManagedApplyInvoker` and does not move mutation authority out of `PatchEngine`.

## Executable evidence

- `WorkspaceRefreshCoordinatorTest`
- daemon workspace refresh/index tests
- daemon, LSP, and MCP apply/rollback regressions
- source inspection of the four bounded adoption families
