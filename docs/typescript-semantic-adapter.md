# Experimental TypeScript/JavaScript semantic adapter

Status: `v0.6.0` T3/T4 foundation; proposal-only and not yet a stable managed
mutation capability. Layered descriptors are published by the library, CLI,
daemon, LSP and MCP capability schemas. CLI one-shot search, definition,
references, diagnostics and rename use the same explicit-toolchain daemon
orchestration in-process and close the session on every exit path.

## LSP ownership boundary

The RefactorKit LSP server accepts `.ts`, `.tsx`, `.js`, and `.jsx` document
synchronization, but it does not start or impersonate a native TypeScript language
server. Native TypeScript/JavaScript definition, references, diagnostics, tokens,
formatting and rename remain client-managed. RefactorKit provides bounded structural
`documentSymbol` results for plain `.ts` and `.js`, while returning empty Java-only
services for script documents so Java analysis cannot be presented as TypeScript
semantic evidence. TSX/JSX structural outlines remain disabled until their parser
ownership is qualified.

The initialize response publishes this split in
`capabilities.experimental.refactorkitSemanticOwnership`. Rollbackable semantic
writes use the explicit-toolchain CLI, daemon or MCP surfaces; standard native LSP
edits remain editor-owned and have no RefactorKit WAL or rollback guarantee.

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

The initialized server must advertise document/workspace symbols, definition,
references, rename, prepare-rename and text synchronization. Missing capability
terminates the owned process tree and returns
`typescript.serverCapabilityMissing`.

## Stable semantic identity

LSP does not standardize compiler symbol handles. RefactorKit therefore emits opaque
`lsp-symbol-v1:<sha256>` IDs derived from the language, normalized declaration path,
semantic parent/container hierarchy, LSP symbol kind, name and bounded `detail`
signature. Source lines and UTF-16 columns are intentionally excluded, so the same
symbol retains its ID across process sessions and unrelated line movement when the
same hash-bound toolchain is used. Parent hierarchy separates equal members in
different declarations; signature detail distinguishes overload shapes when the
server reports it. Exact duplicate declarations intentionally collapse to one
semantic identity, matching TypeScript declaration merging.

The key is a RefactorKit portability layer rather than a claim that LSP exposes a
native TypeScript compiler ID. File moves, declaration renames, hierarchy changes or
server-reported signature changes create a new identity.

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

Managed preview/apply uses `typescript-compiler-exact-v1`, a request-correlated
compiler diagnostics provider independent of optional LSP publication versions.
RefactorKit materializes the exact immutable snapshot plus hash-bound config evidence
in a source-only overlay and launches the explicit Node binary against the
hash-bound `lib/typescript.js` compiler API through a fixed bundled bridge. The
bridge forces `noEmit`, disables incremental output, executes no package scripts or
plugins, restricts compiler reads to the overlay and explicit compiler-library root,
returns the requested snapshot hash and at most 500 structured diagnostics,
and is bounded to 30 seconds, 512 MiB V8 old space, 8 MiB stdout and 64 KiB stderr.
Overlay mutation, process failure, malformed/incomplete output or snapshot mismatch
fails closed.

Upstream unversioned `publishDiagnostics` notifications remain unacceptable; they
are not promoted by inference. Preview stores original and staged compiler images
and now refuses immediately when a semantic proposal introduces a new compiler
error. Managed apply reruns the same provider under the writer lock through
`TypeScriptSemanticAdapter.diagnosticsGate()` and `PatchEngine`. File-set-changing
proposals are authorized only by their exact staged snapshot hash in a bounded
128-entry session allowlist.

## Layered capability schema

`TypeScriptAdapterDescriptors` composes the in-process Tree-sitter layer with the
external semantic layer without conflating their trust boundaries. Every
capability reports its own backend and runtime: outline, identifier search and
local rename remain native/structural, while definition, references, diagnostics
and semantic rename report external-process execution, source overlay and process
provenance. Capability-level extension sets make TSX/JSX semantic ownership
explicit without falsely granting them the `.ts`/`.js` structural grammar claim.

## Current operations

Experimental read operations delegate document symbols, workspace-symbol search,
definition and references to the bounded language server. Workspace search is
capped at 200 results and daemon paths are normalized relative to the active
workspace. Both nested `DocumentSymbol` and
`SymbolInformation` forms are normalized with exact UTF-16 ranges, portable path
remapping, a 256-file request cap and 10,000-symbol result cap. Structural
Tree-sitter symbols are used only when no semantic document-symbol capability is
active. Cross-session semantic IDs use the `lsp-symbol-v1` scheme described above.

`renameSymbol` first builds the exact semantic index, resolves the selected
symbol, and requires `textDocument/prepareRename` to return a bounded range that
contains the requested UTF-16 position in the exact source image. Null, malformed
or out-of-image prepare results refuse before requesting edits. Safe non-reserved
Unicode identifiers (including private `#` identifiers)
are accepted for class/interface/enum/function/method/property/field/variable/
constant/type-alias/type-parameter/parameter/namespace/internal-module symbols. When the LSP
returns a session-exact but generic `UNKNOWN` kind, bounded native Tree-sitter
ancestor classification may promote only recognized type-alias, parameter or
namespace/module declarations; `module Identifier` and `namespace Identifier`
are distinguished from bounded node text. Ambient external string-module names
are intentionally not identifiers and remain refused. An unclassified kind,
unresolved symbol, constructor, package, invalid or no-op target refuses before requesting an edit. The
For declaration/composite/project-reference library surfaces, an exported symbol
is treated as having potentially unbounded external consumers. Preview refuses
with `typescript.externalConsumersUnknown` unless
`allowExternalConsumers=true`; the override lowers confidence, raises risk to
high and records an explicit warning. Package `exports` and `types`/`typings`
publication markers are hash-bound project-model evidence. Separately, up to 50
exact quoted symbol-name candidates are collected across TypeScript/JavaScript
sources to cover computed string properties, decorator arguments, reflection and
framework registries. These refuse with `typescript.dynamicReferencesUnknown`
unless `allowDynamicReferences=true`; an override lowers confidence to 0.55,
raises risk and records bounded locations. The server WorkspaceEdit then passes through the strict external edit parser
and core normalizer. A successful result is a `PatchPlan` preview with
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
- exported library surface without explicit external-consumer override;
- quoted dynamic/decorator/reflection candidates without explicit override;
- oversized documents;
- invalid UTF-16 positions;
- edit path outside overlay/workspace;
- generated source, symlink, overlap, invalid range or structural conflict;
- unavailable/refused/invalid prepare-rename range;
- unsupported symbol kind, unresolved symbol, invalid/reserved identifier or
  unchanged rename target;
- unsupported operation or missing rename selection/target.
