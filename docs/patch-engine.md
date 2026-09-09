# Patch engine

See AGENTS.md for the authoritative architecture and implementation rules.

## Stable v1 edit scope

`WorkspaceEdit` supports file create, modify, delete, and rename. The engine may
create missing parent directories required by those file edits and journals them
for conflict-safe rollback cleanup. Standalone directory create or rename is not
a stable v1 operation and must be refused by integrations rather than represented
as an implicit file edit.

## Interrupted staging ownership

New transactions retain an exact versioned staging marker in the checksummed
PREPARED history detail. That marker reserves a naming convention, not ownership
of an arbitrary matching file. The existing `TransactionLog` also retains a
private `.transaction-UUID.staging` resource receipt: transaction/plan/snapshot/
edit identity, exact created paths, filesystem file-key plus creation time, and
full image digests. No user source is committed before that receipt is durable.
The receipt does not change WAL-v8 or its ordered lifecycle phases.

Recovery checks the receipt, all present candidates and created-directory
contents before deleting any stage. Missing proof, modified content or identity,
symlinks, corrupt receipts and foreign paths fail closed. Old unmarked WALs never
inherit new staging ownership. A crash before receipt completion can require
manual review, with no user source committed. Normal completion removes the
resource receipt; transaction journals remain available for rollback/audit.

The [module crash Stories](../features/managed-module-crash-recovery.feature)
exercise the bounded public-daemon recovery boundary. Local POSIX evidence does
not establish other filesystems or adversarial replacement between validation
and deletion. Integrated status and release limitations belong to
[ARC42](arc42/appendix-requirements.adoc).

## Managed text encoding

The v1 patch contract accepts valid UTF-8 text only. RefactorKit never guesses or
silently transcodes a source encoding. Malformed UTF-8 is refused during the
engine-owned snapshot rescan, before journal creation or workspace mutation, as
`snapshot.scopeUnreadable`.

A UTF-8 BOM is represented by the leading `U+FEFF` content character and remains
part of checksummed pre/post images. Apply, rollback, and recovery therefore
preserve its UTF-8 bytes. Supporting another encoding requires an explicit,
hash-bound adapter/configuration contract and is not implied by the v1 API.
