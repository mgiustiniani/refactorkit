# Patch engine

See AGENTS.md for the authoritative architecture and implementation rules.

## Stable v1 edit scope

`WorkspaceEdit` supports file create, modify, delete, and rename. The engine may
create missing parent directories required by those file edits and journals them
for conflict-safe rollback cleanup. Standalone directory create or rename is not
a stable v1 operation and must be refused by integrations rather than represented
as an implicit file edit.

## Managed text encoding

The v1 patch contract accepts valid UTF-8 text only. RefactorKit never guesses or
silently transcodes a source encoding. Malformed UTF-8 is refused during the
engine-owned snapshot rescan, before journal creation or workspace mutation, as
`snapshot.scopeUnreadable`.

A UTF-8 BOM is represented by the leading `U+FEFF` content character and remains
part of checksummed pre/post images. Apply, rollback, and recovery therefore
preserve its UTF-8 bytes. Supporting another encoding requires an explicit,
hash-bound adapter/configuration contract and is not implied by the v1 API.
