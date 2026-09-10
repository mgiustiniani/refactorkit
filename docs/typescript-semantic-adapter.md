# Experimental TypeScript/JavaScript semantic adapter

Status: `v0.6.0` T3/T4 foundation plus bounded `0.7.0` managed-operation local
acceptance, not final release qualification. Architecture authority remains in
[ARC42](arc42/08-crosscutting-concepts.adoc); this document describes the adapter
and public commands. Layered descriptors are published by the library, CLI,
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
  argument hashes;
- retained diagnostics gates bound to the semantic generation that created them.
  An admitted restart clears previous staged/migration approvals. Old gates refuse
  before WAL with `diagnostics.unavailable` and reason
  `typescript.refactoringAuthorityStale`, even after an identical fresh preview.
  Callers must acquire a new preview and its owning gate in the restarted session.
  The long-lived semantic child and per-exchange compiler child intentionally have
  different identities; they are not required to share a process ID.

Server capabilities are reduced to a deterministic known-capability map in
session provenance. Diagnostics retain bounded message, code, severity, exact
range and overlay-to-workspace path and are classified as compiler/type-resolution
evidence.

Managed preview/apply uses `typescript-compiler-exact-v1`, a request-correlated
compiler diagnostics provider independent of optional LSP publication versions.
RefactorKit materializes the exact immutable snapshot plus hash-bound config evidence
in a source-only overlay and launches the explicit Node binary against the
hash-bound `lib/typescript.js` compiler API through a fixed bundled bridge. The
bridge forces `noEmit` but preserves declared composite/incremental/build-info
options. It never invokes emit or a solution build, rejects compiler-host writes,
and checks unbuilt project references through TypeScript's source redirect while
honoring an explicit `disableSourceOfProjectReferenceRedirect`. It executes no
package scripts or plugins, restricts reads to the overlay and explicit compiler-library root,
returns the requested snapshot hash and at most 500 structured diagnostics,
and is bounded to 30 seconds, 512 MiB V8 old space, 8 MiB stdout and 64 KiB stderr.
Overlay mutation, process failure, malformed/incomplete output or snapshot mismatch
fails closed. IDE consumers use the additive API `0.2` `diagnostics.v2` envelope
documented in [`typescript-diagnostics-protocol.md`](typescript-diagnostics-protocol.md).
It preserves exact zero-based UTF-16 ranges, distinguishes saved-disk and immutable
editor-overlay authority, correlates request/snapshot/semantic lease, and separates
compiler readiness failures from source diagnostics. Legacy `diagnostics` remains
a bare array for compatibility.

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
provenance. Diagnostics specifically advertise backend
`typescript-compiler-exact-v1`, its 30-second/8-MiB/one-process limits, supported
snapshot modes and exact-UTF-16-or-explicit-partial range capability; LSP runtime
metadata is not reused for the compiler operation. Capability-level extension sets make TSX/JSX semantic ownership
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
unresolved symbol, constructor, package, invalid or no-op target refuses before requesting an edit.
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
real-toolchain final-candidate acceptance for apply, recovery and rollback.
Repeated multi-host execution for 0.7.0 is
[waived by the user](requirements/v0.7.0-local-host-acceptance-approved-change-001.md),
not reported as verified on missing hosts.

## Advanced operation families (local 0.7.0 candidate)

The catalogue lists `renameSymbol` and these seven bounded families. A family is
not an applicability promise for every selection; unsupported source shapes fail
closed. Existing `renameSymbol` retains its separate LSP proposal contract above.

| Operation | Required operation arguments / boundary |
|---|---|
| `sourceFileRelocation` | `file`, `targetFile`; same recognized source language, actual compiler-program membership before/after, exact source import/export edits before rename |
| `organizeImports` | `file`; `mode` is `All`, `SortAndCombine` or `RemoveUnused` |
| `extractFunction`, `extractConstant`, `inlineVariable` | `file`, `startLine`, `startCharacter`, `endLine`, `endCharacter`, exact `refactor` and `action` identities |
| `moveDeclaration` | The same selection/action fields plus a distinct existing `targetFile`; incomplete cross-project caller edits refuse |
| `projectReferenceMigration` | `fromDirectory`, `toDirectory`; one complete bounded sibling project, compiler source edits plus independently proven JSONC references |

Selections are zero-based UTF-16; split surrogates and conflicting coordinates
refuse. Optional formatting/preference arguments are hash-bound. Compiler-generated
names are retained: `newName`/`methodName` do not silently rename extraction output.
The caller supplies native action identities; preview re-queries
`getApplicableRefactors`, selects exactly one matching available action, binds its
returned descriptor hash and requests `getEditsForRefactor`. There is currently no
public action-discovery endpoint. `changeSignature` and `inlineFunction` remain
stable typed refusals; `extractMethod`/`moveSymbol` require exact action-specific
operations rather than granting generic authority.

```sh
refactorkit typescript refactorings . --node "$NODE" \
  --language-server-package "$LANGUAGE_SERVER_PACKAGE" --typescript-package "$TYPESCRIPT_PACKAGE"
refactorkit typescript refactor . --operation projectReferenceMigration \
  --arguments-json '{"fromDirectory":"packages/lib","toDirectory":"packages/domain"}' \
  --node "$NODE" --language-server-package "$LANGUAGE_SERVER_PACKAGE" \
  --typescript-package "$TYPESCRIPT_PACKAGE"
```

These commands preview only. `--apply` explicitly requests a fresh preview and
managed apply within that invocation; a preview-only CLI process closes its
semantic session and cannot donate its plan ID to a later invocation. The daemon
uses `typescript.refactorings` for the catalogue and `refactor.preview` for advanced
operations. MCP uses `available_refactorings` and `preview_refactoring`. Advanced
preview arguments must include the owning `languageId`, `semanticLease` and
`expectedSnapshotHash`; a foreign lease or stale snapshot fails closed. Retained
plans use the actual owning diagnostics gate at apply, not a replacement session's
gate. Existing dirty/affected-open-document refusals remain in force.

Source relocation does not convert TypeScript to JavaScript/plain text or acquire
configuration-edit authority. A compiler proposal that rewrites `tsconfig.json`
(e.g. an explicit `files` entry), creates/deletes files beyond the one relocation,
or edits non-source inputs is refused. Root-prefix ownership and an empty
compiler diagnostic list are insufficient: the old and staged target must actually
participate in the pinned compiler's configured programs. The existing bridge,
overlay and process bounds provide this check; no approximation of TypeScript
include/exclude globs is used. These source corrections require qualification of
the corrected runtime; see [the r006 report](releases/v0.7.0-candidate-corrections.md).

For recipes, CLI `recipe run recipes/typescript/relocate-source.yml --root .`
accepts the same explicit toolchain options plus `--param.file` and
`--param.targetFile` (and optional `--apply`). Daemon/MCP use `operation=recipe`,
with bounded `recipeYaml` and `param.<name>` arguments. Only one compiler-owned
mutation plus diagnostics/summary projections is admitted; the returned plan keeps
its child operation, snapshot, lease and owning gate. No project/package/framework
script executes. Java recipe behavior is unchanged.

Local public examples cover TypeScript; an internal checked-JavaScript recipe also
passes. Dynamic/mixed JavaScript does not inherit compiler completeness. The
[local safety ledger](releases/v0.7.0-t5-local-safety.md) records exact evidence and
remaining review/qualification gaps. None of these managed routes adds an advanced
managed LSP command or RefactorKit rollback to client-owned LSP edits.

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
