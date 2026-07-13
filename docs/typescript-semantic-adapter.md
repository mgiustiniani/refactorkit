# Experimental TypeScript/JavaScript semantic adapter

Status: `v0.6.0` T3/T4 foundation; proposal-only and not yet a stable managed
mutation capability.

## Startup gate

`TypeScriptSemanticAdapter` starts only when all three evidence layers agree:

1. `typescript-lsp-explicit-v1` toolchain provenance;
2. available `typescript-config-declarative-v1` project model;
3. the same project-model projection hash inside `ProjectSnapshot.buildModels`.

Before process launch it re-hashes every Node/server/compiler evidence file and
every config/extends/package input. Drift, missing files, symlinks, invalid UTF-8,
unavailable Build Model or snapshot mismatch refuses without starting a process.
Config and package inputs are materialized in the source-only semantic overlay;
the real workspace remains outside the server root.

The initialized server must advertise definition, references, rename and text
synchronization. Missing capability terminates the owned process tree and returns
`typescript.serverCapabilityMissing`.

## Document synchronization

The external LSP bridge now supports bounded full-document lifecycle:

- at most 256 open documents;
- at most 4 MiB UTF-8 content per document;
- non-negative, strictly increasing versions;
- `didOpen`, full-sync `didChange`, and `didClose`;
- content SHA-256 tracking and automatic resynchronization before rename;
- UTF-16 position validation with surrogate-pair split refusal.

Server capabilities are reduced to a deterministic known-capability map in
session provenance. Diagnostics retain bounded message, code, severity, exact
range and overlay-to-workspace path and are classified as compiler/type-resolution
evidence.

## Current operations

Experimental read operations delegate document symbols, definition and
references to the bounded language server. Both nested `DocumentSymbol` and
`SymbolInformation` forms are normalized with exact UTF-16 ranges, portable path
remapping, a 256-file request cap and 10,000-symbol result cap. Structural
Tree-sitter symbols are used only when no semantic document-symbol capability is
active. Cross-project stable compiler identity/workspace search remains T3 work.

`renameSymbol` converts the server WorkspaceEdit through the strict external edit
parser and core normalizer. A successful result is a `PatchPlan` preview with
`LANGUAGE_SERVER` evidence, explicit approval requirement and medium TypeScript
or high JavaScript risk. It performs no filesystem write. Stable TypeScript apply
still requires a TypeScript diagnostics gate, stale toolchain/config verification
under the PatchEngine lock, and native apply/WAL/recovery/rollback acceptance.

## Refusal examples

- stale or absent project model in the snapshot;
- changed toolchain/config/package evidence;
- missing server capabilities;
- stale/non-monotonic document versions;
- oversized documents;
- invalid UTF-16 positions;
- edit path outside overlay/workspace;
- generated source, symlink, overlap, invalid range or structural conflict;
- unsupported operation or missing rename selection/target.
