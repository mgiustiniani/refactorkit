# Experimental TypeScript/JavaScript semantic adapter

Status: `v0.6.0` T3/T4 foundation; proposal-only and not yet a stable managed
mutation capability. Layered descriptors are published by the library, CLI,
daemon, LSP and MCP capability schemas.

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

The initialized server must advertise document symbols, definition, references,
rename and text synchronization. Missing capability terminates the owned process tree and returns
`typescript.serverCapabilityMissing`.

## Document synchronization

The external LSP bridge now supports bounded full-document lifecycle:

- at most 256 open documents;
- at most 4 MiB UTF-8 content per document;
- non-negative, strictly increasing versions;
- `didOpen`, full-sync `didChange`, and `didClose`;
- content SHA-256 tracking and automatic resynchronization before rename;
- UTF-16 position validation with surrogate-pair split refusal;
- explicit crash restart only, capped at three attempts per rolling 60 seconds;
- restart provenance continuity for server version, capabilities, executable and
  argument hashes.

Server capabilities are reduced to a deterministic known-capability map in
session provenance. Diagnostics retain bounded message, code, severity, exact
range and overlay-to-workspace path and are classified as compiler/type-resolution
evidence.

Managed preview/apply uses a stricter exact-version diagnostics path. Every source
file is synchronized with a monotonically increasing full-document version and
bounded document-symbol requests provide protocol barriers. A clean or non-clean
publication is accepted only when every source file publishes the exact expected
version. Missing, stale, unversioned or over-limit results fail closed. Preview
stores both original and staged diagnostic images; managed apply must pass
`TypeScriptSemanticAdapter.diagnosticsGate()` to `PatchEngine`.

## Layered capability schema

`TypeScriptAdapterDescriptors` composes the in-process Tree-sitter layer with the
external semantic layer without conflating their trust boundaries. Every
capability reports its own backend and runtime: outline, identifier search and
local rename remain native/structural, while definition, references, diagnostics
and semantic rename report external-process execution, source overlay and process
provenance. Capability-level extension sets make TSX/JSX semantic ownership
explicit without falsely granting them the `.ts`/`.js` structural grammar claim.

## Current operations

Experimental read operations delegate document symbols, definition and
references to the bounded language server. Both nested `DocumentSymbol` and
`SymbolInformation` forms are normalized with exact UTF-16 ranges, portable path
remapping, a 256-file request cap and 10,000-symbol result cap. Structural
Tree-sitter symbols are used only when no semantic document-symbol capability is
active. Cross-project stable compiler identity/workspace search remains T3 work.

`renameSymbol` first builds the exact semantic index and resolves the selected
symbol. Safe non-reserved Unicode identifiers (including private `#` identifiers)
are accepted for class/interface/enum/function/method/property/field/variable/
constant/type-parameter/namespace symbols. Unresolved, unknown, constructor,
package, module, invalid and no-op targets refuse before requesting an edit. The
server WorkspaceEdit then passes through the strict external edit parser and core
normalizer. A successful result is a `PatchPlan` preview with
`LANGUAGE_SERVER` evidence, explicit approval requirement and medium TypeScript
or high JavaScript risk. The plan reports one of `FULL_TYPESCRIPT`,
`CHECKED_JAVASCRIPT`, `DYNAMIC_JAVASCRIPT` or `MIXED_JAVASCRIPT`; dynamic and
mixed JavaScript receive lower confidence and cannot obtain the managed diagnostics
gate. Every affected path must have one authoritative longest-prefix ownership in
the TypeScript Build Model; missing or equally specific owners refuse. Versioned
server edits must exactly match the synchronized open-document version. Preview
performs no filesystem write. The experimental
adapter now has JVM acceptance for exact staged diagnostics, explicit authorization,
managed apply, WAL and rollback. Its diagnostics gate re-hashes toolchain and
project-model evidence while `PatchEngine` holds the workspace writer lock and
refuses before WAL on drift. Stable mutation authority still requires packaged
real-toolchain acceptance for apply, recovery and rollback on every supported
native platform.

## Refusal examples

- stale or absent project model in the snapshot;
- implicit restart, restart snapshot mismatch, restart-rate overflow or changed
  server provenance;
- changed toolchain/config/package evidence;
- missing server capabilities;
- stale, missing, unversioned or non-monotonic diagnostic/document versions;
- missing or equally specific TypeScript project ownership;
- oversized documents;
- invalid UTF-16 positions;
- edit path outside overlay/workspace;
- generated source, symlink, overlap, invalid range or structural conflict;
- unsupported symbol kind, unresolved symbol, invalid/reserved identifier or
  unchanged rename target;
- unsupported operation or missing rename selection/target.
