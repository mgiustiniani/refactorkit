# Integration contracts

See AGENTS.md for the authoritative initial architecture and implementation rules.

Status: implementation-informed baseline for `v0.3.0`. The release retains the
API `0.2` beta contract and is not a
`v1.0.0` API freeze. Compatibility labels, API-version expectations, and
breaking-change migration-note rules are defined in
[Compatibility and deprecation policy](compatibility-policy.md).

Current `v0.3.0` release metadata:

- implementation name: `RefactorKit`;
- implementation version: `0.3.0`;
- beta contract API version: `0.2`.

## Compatibility policy

This document lists the current integration surfaces and their stability labels.
See [Compatibility and deprecation policy](compatibility-policy.md) for the
normative meaning of `beta-contract`, `experimental`, and `internal`, the
implementation-version vs API-version distinction, and deprecation rules.

## Structured error categories

All integration surfaces should map failures to one of these categories. JSON-RPC
surfaces should expose the category in structured error data when available; CLI
surfaces should map it to a stable exit-code category and human-readable message.

| Category | Typical cause | Current JSON-RPC mapping |
|----------|---------------|--------------------------|
| `refused-plan` | Planner intentionally refuses an unsafe or ambiguous operation. | `PLAN_REFUSED` (`-32001`) |
| `invalid-input` | Missing/invalid parameter, unknown plan id, unknown tool/command shape. | `INVALID_PARAMS` (`-32602`) |
| `stale-snapshot-or-plan` | Workspace changed after preview or plan no longer matches current snapshot. | `SNAPSHOT_CHANGED` (`-32002`) |
| `document-version-mismatch` | LSP version is stale/non-monotonic, versionable edits are unsupported, or managed disk writes would diverge from open buffers. | `DOCUMENT_VERSION_MISMATCH` (`-32007`) |
| `rollback-conflict` | Affected path differs from its exact post-apply journal image. | `ROLLBACK_CONFLICT` (`-32005`) |
| `recovery-required` | Interrupted apply/rollback cannot be compensated automatically or journal recovery fails. | `RECOVERY_REQUIRED` (`-32006`) |
| `plan-validation-failed` | Edit overlap/range/render/status validation fails. | `PLAN_VALIDATION_FAILED` (`-32008`) |
| `workspace-locked` | Another managed writer holds the workspace lock or lock acquisition fails. | `WORKSPACE_LOCKED` (`-32009`) |
| `filesystem-unsupported` | Required atomic move or durable force capability is unavailable. | `FILESYSTEM_UNSUPPORTED` (`-32010`) |
| `apply-failed` | WAL preparation/persistence or compensated managed apply fails. | `APPLY_FAILED` (`-32011`) |
| `unsafe-path` | Path escapes the workspace or traverses a symbolic link/unsafe lock path. | `UNSAFE_PATH` (`-32012`) |
| `file-conflict` | Required source is missing or a target already exists. | `FILE_CONFLICT` (`-32013`) |
| `approval-required` | A plan marked `requiresUserApproval` reaches apply without explicit authorization. | `APPROVAL_REQUIRED` (`-32014`) |
| `diagnostics-failure` | Managed Java diagnostics provider fails or staged post-image introduces additional errors. | `DIAGNOSTICS_FAILED` (`-32015`) |
| `unsupported-operation` | Method, command, operation, language, or refactoring is not supported. | `METHOD_NOT_FOUND` or `INVALID_PARAMS` |
| `conflict-or-license-policy` | Naming conflict, overwrite refusal, unknown/risky license, or license policy block. | `PLAN_REFUSED` or `INVALID_PARAMS` |

### Managed apply approval contract

Production managed Java apply endpoints also configure the central JDT diagnostics
gate; new staged ERROR diagnostics refuse before WAL creation. Calling a managed
apply endpoint is the explicit approval event; no separate token
exchange is required. Each surface supplies an `ApplyAuthorization` identity and
the WAL transaction persists approval kind, surface, actor, and timestamp. Plans
with `requiresUserApproval=true` refuse before journaling when authorization is
missing. Direct library apply requires explicit `ApplyAuthorization` and
`DiagnosticsGate`; there is no implicit approval or ungated overload.

## CLI compatibility baseline

The command names below are the documented CLI surface for `v0.2.0-beta`.
`--apply` remains opt-in for mutating operations; preview is the default.

`refactorkit --version` and `refactorkit version` are read-only beta-contract
metadata surfaces. In `v0.3.0` they report implementation version `0.3.0` and
API version `0.2`; automation should
check the API version when deciding whether a beta-contract integration is
compatible.

| CLI command | Status | Contract notes |
|-------------|--------|----------------|
| `refactorkit --version` / `refactorkit version` | `beta-contract` | Read-only implementation/API metadata; `v0.3.0` reports version `0.3.0` and API version `0.2`. |
| `refactorkit capabilities` | `experimental` | Emits language-kernel capability schema v1 with deterministic adapter, backend, operation, evidence, authority, execution, timeout/cancellation, overlay, provenance, and resource-limit fields. |
| `refactorkit scan <path>` | `beta-contract` | Read-only project scan summary. |
| `refactorkit index <path>` | `beta-contract` | Alias-compatible indexing workflow. |
| `refactorkit symbols <path>` / `refactorkit java symbols <path>` | `beta-contract` | Symbol listing shape should remain scriptable. |
| `refactorkit diagnostics <path>` / `refactorkit java diagnostics <path>` | `beta-contract` | Diagnostic severity/file/line fields are contract fields. |
| `refactorkit definition --symbol <fqcn> [path]` / `java definition` | `beta-contract` | Read-only lookup. |
| `refactorkit references --symbol <fqcn> [path]` / `java references` | `beta-contract` | Read-only reference listing; exact signed member selectors may use JDT binding evidence when analysis is clean, otherwise lexical fallback remains. |
| `refactorkit rename --symbol <fqcn> --to <name> [--apply] [path]` | `beta-contract` | Java class rename uses exact JDT type/constructor/reference ranges when analysis is clean, explicit lexical fallback otherwise, and target-conflict refusal; preview/apply/rollback semantics remain mandatory. |
| `refactorkit rename-member --symbol <FQN#member> --to <name> [--apply] [path]` | `beta-contract` | Signed method selectors use JDT exact-overload and scanned-source override-family propagation; external family members, constructors, or ambiguous evidence are refused. Unsigned member rename remains lexical. |
| `refactorkit move-class --symbol <fqcn> --to-package <pkg> [--apply] [path]` | `beta-contract` | Clean JDT evidence scopes package/import/FQN edits to binding-matched files; lexical scoping is explicit otherwise. Invalid packages and existing targets are refused; framework/string warnings remain. |
| `refactorkit java move-source-root --from <root> --to <root> [--root <path>] [--apply]` | `beta-contract` | Whole-root rename-only transaction preserving bytes, packages, and FQCNs; typed `sourceRoot.*` refusal codes and post-image Maven/JDT diagnostics apply. |
| `refactorkit organize-imports <file...> [--apply] [--root <path>]` | `beta-contract` | Sort/deduplicate/remove same-package imports; clean JDT evidence also removes binding-proven unused exact imports, while wildcard/unresolved imports and unclean files remain conservative. |
| `refactorkit format-file <file> [--apply] [--root <path>]` | `beta-contract` | Formats one non-generated, syntactically valid Java compilation unit through Eclipse JDT using hash-bound project preferences or deterministic defaults; preview/apply/rollback remains mandatory. |
| `refactorkit safe-delete --symbol <fqcn> [--force] [--apply] [path]` | `beta-contract` | Refuses referenced symbols by default; forced behavior requires explicit risk note. |
| `refactorkit patch rollback <transaction-id> [--force] --root <path>` | `beta-contract` | Normal rollback validates exact post-images and refuses conflicts; `--force` is an explicit destructive pre-image restore. |
| `refactorkit test-golden [case] [--golden-dir <path>]` | `beta-contract` | CI/test harness command for documented golden fixtures. |
| `refactorkit extract-method ...` | `experimental` | Limited MVP; success/refusal coverage expands during beta. |
| `refactorkit change-signature ...` | `experimental` | Limited rename/add/reorder/remove parameter support; conservative refusal expected. |
| `refactorkit java import-class ...` | `experimental` | External code assimilation; provenance, conflict, and license-policy behavior may change before `v1.0.0`. |
| `refactorkit recipe run ...` | `experimental` | Recipe schema is not yet a beta contract. |
| `refactorkit outline`, `search`, `local-rename` | `experimental` | Multi-language structural features, not semantic Java refactoring contracts. |
| Any unlisted/debug subcommand | `internal` | Not supported for external automation. |

## Multi-language adapter kernel

The internal registry uses canonical `languageId` plus unique extension ownership.
Mixed routing requires explicit language, scanned selection, or unique symbol
ownership; ambiguity is a typed refusal. Capability descriptors separate
stability, evidence (`COMPILER`, `LANGUAGE_SERVER`, `NATIVE_AST`,
`STRUCTURAL_PARSE`, `LEXICAL`, `NONE`), and mutation authority. Stable managed
authority cannot be lexical, and a returned plan with weaker evidence is replaced
by `language.evidenceInsufficient` refusal. `RefactoringEvidence` additively
includes `LANGUAGE_SERVER` and `NATIVE_AST`; integrations must tolerate unknown
future enum values under the pre-1.0 compatibility policy. Capability schema v1
is exposed by CLI `capabilities`, daemon `server.capabilities.languageKernel`, LSP
initialize `capabilities.experimental.refactorkitLanguageKernel`, and MCP
initialize `refactorkitLanguageKernel`. Adapter and operation arrays are sorted;
nullable limit fields remain explicit.

External LSP processes are internal proposal providers. They run under the
bounded semantic process lifecycle with explicit environment and provenance,
byte-counted framed request deadlines, and a source-only workspace overlay.
Standard `changes` and `documentChanges` responses pass strict schema and unsafe
resource-option refusal, overlay-to-workspace URI remapping, and
`ExternalWorkspaceEditNormalizer`. An accepted result is not apply authorization:
it must still become a `PatchPlan` and pass snapshot, evidence, diagnostics,
approval, `PatchEngine`, WAL, and rollback gates. These P4 types remain internal
until capability/provenance DTO schema acceptance is complete.

## Daemon JSON-RPC compatibility baseline

`modules/refactorkit-daemon` exposes JSON-RPC 2.0 over newline-delimited stdio.
Request/response envelopes, method names, and the error categories above are part
of the beta baseline for documented methods.

### Method classification

| Method | Status | Request category |
|--------|--------|------------------|
| `server.version` | `beta-contract` | Read-only implementation/API metadata; does not require an opened project. |
| `server.capabilities` | `beta-contract` | Read-only protocol/method/safety discovery; does not require an opened project. |
| `project.open` | `beta-contract` | Workspace lifecycle; requires `root`. |
| `project.summary` | `beta-contract` | Read-only project metadata plus typed, deterministically ordered, bounded and truncation-signaled Build Model SPI summary (`providerId`, canonical status, policy outcomes, selected profiles, typed diagnostic codes, modules/source sets/outputs/edges); module paths are workspace-relative, while external classpath paths and diagnostic messages are omitted. Shape is guarded by `build-model-summary-schema-keys.json`. |
| `symbol.search` | `beta-contract` | Read-only symbol query; optional `query`. |
| `symbol.definition` | `beta-contract` | Read-only lookup; requires `symbol`. |
| `symbol.references` | `beta-contract` | Read-only reference query; requires `symbol`. |
| `diagnostics` | `beta-contract` | Read-only diagnostics query. |
| `refactor.preview` | `beta-contract` | Patch-plan preview envelope and refusal behavior. |
| `refactor.apply` | `beta-contract` | Requires `planId`; applies the exact retained plan, rejects stale snapshots/plans, refreshes session state, clears pending plans, and returns structured changes/diagnostics/snapshot evidence. |
| `refactor.discard` | `beta-contract` | Idempotently removes a pending source-bearing plan without workspace writes; returns `discarded=false` when absent. |
| `patch.rollback` | `beta-contract` | Requires `transactionId`; stays inside workspace root, refreshes session state, clears pending plans, and returns inverse WAL changes/diagnostics/snapshot evidence. |
| `java.importExternalClass` | `experimental` | External Java import preview; requires an opened project snapshot. |
| Any unlisted method | `internal` | Treat as unsupported. |

### `server.capabilities` contract

`server.capabilities` is available before project open and returns additive
read-only discovery metadata:

- `name`, `version`, and `apiVersion`;
- `protocol` (`json-rpc-2.0`) and `transport` (`stdio`);
- `methods`, where each entry exposes `name`, `stability`, `requiresProject`, and
  `writesWorkspace`; methods may add a `features` object for explicit capability
  discovery. `java.importExternalClass.features` advertises `targetDirectory`,
  rendered/structured diff, virtual preview diagnostics, discard, plan-ID apply,
  and transaction-ID rollback independently of the implementation version;
- `languageKernel`, the versioned deterministic capability/evidence/runtime schema;
- `safety`, including `previewBeforeApply`, `snapshotValidation`,
  `transactionRollback`, and `workspaceScopedWrites`.

Clients should use this response instead of inferring mutability or stability
from method names. Capability discovery does not open a project and does not
authorize filesystem access.

### `server.version` contract

`server.version` is a read-only beta-contract method for compatibility checks.
It does not open, scan, or modify a workspace.

Request parameters: none.

Current `v0.3.0` response:

```json
{
  "name": "RefactorKit",
  "version": "0.3.0",
  "apiVersion": "0.2"
}
```

Clients should use `apiVersion` for beta-contract compatibility decisions. The
implementation `version` may advance on main and across prereleases while the API
contract baseline remains `0.2`.

### `java.importExternalClass` preview contract

`java.importExternalClass` is experimental for `v0.2.0-beta`, but its importer
provenance/license warning line is an output contract for beta pilots.

Required request fields:

- `code`: Java source text to import; may include Markdown fences;
- one or both of:
  - `targetDirectory`: an existing workspace-relative directory owned by exactly
    one recognized Java module/source root; RefactorKit derives source set and
    package from the project model;
  - `targetPackage`: fully-qualified target package, or an empty string for the
    default package (legacy contract).

Optional request fields:

- `targetModule`: legacy module/source-root selector; when combined with
  `targetDirectory` it is a consistency assertion, not a hint;
- `sourceUrl`: URL or provenance URL for the source text;
- `sourceKind`: `clipboard`, `url`, `file`, `llm`, or `snippet`; defaults to
  `snippet` for daemon requests;
- `licensePolicy`: `warn`, `block-unknown`, or `allow`; defaults to `warn`.

When both targets are supplied, their derived packages must match. Absolute,
Windows-absolute-on-Unix, traversal, missing, non-directory, symlink, outside-
workspace, outside-source-root, generated, non-package-conforming, overlapping,
or otherwise ambiguous directory targets return a structured refused plan with
`refusalReasons` and a next action. Resolution occurs before Java source
processing, and refusal output never echoes clipboard source.

The typed response uses lower-case `status`, risk and evidence values and exposes
`affectedFiles` change objects, `placement`, `renderedDiff`, bounded
`structuredDiff` hunks, `diagnosticsAfterPreview`, structured provenance,
`applyEligibility`, `staleness`, snapshot evidence, and provider identity.
Temporary API `0.2` compatibility aliases include `legacyStatus`,
`legacyRiskLevel`, `legacyEvidence`, `affectedFilePaths`, `applyEligible`, and
legacy provenance names. Diff and diagnostic overflow is always
reported through truncation fields and versioned limits. See
[Daemon protocol](daemon-protocol.md) for the complete schema.

All plan/file paths are workspace-relative and serialized with `/` separators
on every host. The `warnings` array must include exactly one
provenance/license line for source-processed plans with these stable key names:

```text
Provenance: sourceKind=... sourceUrl=... retrievedAt=... licenseDetected=... licenseRisk=... originalHash=...
```

`sourceUrl` is `(none)` when no URL is known; `retrievedAt` is an ISO-8601
instant; `originalHash` is the SHA-256 hash of the cleaned source text.

A successful preview performs no workspace write and retains its exact
engine-owned plan in a bounded 128-entry LRU. Project switch, apply, rollback,
EOF/shutdown, explicit `refactor.discard`, and eviction release pending plan
references; refused/diagnostics-blocked plans are never retained. Only
`refactor.apply({planId})` may apply a retained plan. Apply does not regenerate
source or target resolution, validates snapshot/classpath staleness and reruns
JDT diagnostics under lock, then returns transaction, typed changes, primary
file, post-apply diagnostics, and refreshed snapshot evidence. Rollback derives
inverse changes from the WAL forward edit and retains conflict-safe semantics.

Virtual preview diagnostics come from `WorkspaceEditSimulator` plus JDT and are
not planner-approved errors: new ERROR diagnostics block eligibility. Apply-time
diagnostics remain authoritative and independent. Unknown/high license under
`warn` requires explicit-apply acknowledgement and uses conservative HIGH risk;
`block-unknown` refuses and stores no plan.

The changes retain API `0.2`; importer shape remains `experimental` while
`refactor.discard` is additive beta-contract lifecycle control.

### `refactor.preview` operation classification

| Operation | Status | Required arguments |
|-----------|--------|--------------------|
| `renameClass` | `beta-contract` | `symbol`, `arguments.newName` |
| `renameMember` | `beta-contract` | `symbol`, `arguments.newName` |
| `moveClass` | `beta-contract` | `symbol`, `arguments.targetPackage` |
| `moveSourceRoot` | `beta-contract` | `arguments.from`, `arguments.to`; workspace-relative `/` paths |
| `organizeImports` | `beta-contract` | `arguments.file` or `symbol` as file path |
| `formatFile` | `beta-contract` | `arguments.file`; whole-file Java formatting only in API `0.2` |
| `safeDelete` | `beta-contract` | `symbol`, optional `arguments.force` |
| `extractMethod` | `experimental` | `arguments.file`, `startLine`, `endLine`, `methodName` |
| `changeSignature.renameParameter` / `renameParameter` | `experimental` | `symbol`, `oldName`, `newName` |
| `changeSignature.addParameter` / `addParameter` | `experimental` | `symbol`, `type`, `name`, `default` |
| `changeSignature.reorderParameters` / `reorderParameters` | `experimental` | `symbol`, `order` |
| `changeSignature.removeParameter` / `removeParameter` | `experimental` | `symbol`, `name` |

A refused preview must not silently fall back to text replacement. Clients should
show the refusal, warnings, affected files when present, and next action.

## Diagnostic evidence contract

Diagnostics may expose `evidence` (`COMPILER`, `STRUCTURAL`, `TRANSACTION`) and
`category` (`SYNTAX`, `TYPE_RESOLUTION`, `PROJECT_STRUCTURE`, `SAFETY`) alongside
stable code, severity, message, and range. Apply compares the exact staged error
multiset against current errors plus explicit `diagnosticsAfterPreview`; any
additional error is `diagnostics.regression`. Daemon JSON includes these fields;
LSP carries them in diagnostic `data`.

## Refactoring evidence contract

Every plan exposes one stable evidence category:

- `JDT_BINDING`: edits use clean exact JDT binding/source-range evidence;
- `LANGUAGE_SERVER`: edits are normalized from a bounded semantic-server proposal;
- `NATIVE_AST`: edits use a native parser/compiler rewrite representation;
- `STRUCTURAL`: deterministic local transformation without semantic identity claims;
- `LEXICAL_FALLBACK`: review-only preview when semantic evidence is unavailable.

`PatchEngine` refuses `LEXICAL_FALLBACK` before WAL creation with
`evidence.insufficient`. Recipes retain the weakest evidence of any composed step.

## LSP server baseline

`modules/refactorkit-lsp` exposes a JSON-RPC/LSP server over stdio using standard
`Content-Length` framing. Clients advertising
`workspace.workspaceEdit.documentChanges=true` receive versionable
`documentChanges`; legacy `changes` is used only for closed-document text edits
when that capability is absent. Structural or open-document edits require
`documentChanges` support and otherwise refuse.

The LSP initialize response `serverInfo.version` is read-only metadata and uses
the centralized implementation version. In `v0.3.0`, it reports `0.3.0`.

| Capability or command | Status | Notes |
|-----------------------|--------|-------|
| `textDocument/definition`, `references`, `documentSymbol`, `diagnostic` | `beta-contract` | Read-only editor integration; definition/references may use JDT binding evidence for clean exact overloaded member call sites. |
| `textDocument/prepareRename`, `textDocument/rename` | `beta-contract` | Must use RefactorKit previews and refusal handling; rename-position resolution may produce signed method selectors for the proven JDT exact-overload slice. |
| `textDocument/codeAction`, `workspace/executeCommand` envelope | `beta-contract` | Command transport and refusal categories are baseline. |
| `textDocument/formatting` | `beta-contract` | Returns client-managed Java formatting edits with no RefactorKit transaction; managed rollback requires `refactorkit.formatFile` plus `refactorkit.applyPlan`. |
| `textDocument/semanticTokens/full` | `experimental` | Token shape may change. |
| `refactorkit.renameClass`, `refactorkit.renameMember`, `refactorkit.moveClass`, `refactorkit.organizeImports`, `refactorkit.safeDelete`, `refactorkit.applyPlan`, `refactorkit.rollback` | `beta-contract` | Command names, preview metadata, and safety semantics are stable for beta pilots. |
| `refactorkit.formatFile` | `beta-contract` | Produces a preview workspace edit and plan ID for optional managed apply. |
| `refactorkit.extractMethod` | `experimental` | Limited MVP. |
| `refactorkit.changeSignature.renameParameter`, `refactorkit.changeSignature.addParameter`, `refactorkit.changeSignature.reorderParameters`, `refactorkit.changeSignature.removeParameter` | `experimental` | Limited MVP. |
| Alias `refactorkit.renameParameter` | `experimental` | Compatibility alias, not preferred for new clients. |

### LSP `workspace/executeCommand` preview and rollback contract

Preview commands such as `refactorkit.renameClass`, `refactorkit.renameMember`,
`refactorkit.moveClass`, `refactorkit.organizeImports`, and
`refactorkit.safeDelete` return a WorkspaceEdit-compatible object with beta
RefactorKit metadata:

- `refactorkitPlanId`: pending plan id to pass to `refactorkit.applyPlan`;
- `operation`, `status`, `summary`, `riskLevel`, `evidence`, and `warnings`;
- `refactorkitEditOwnership: "client-managed"` and
  `refactorkitRollbackAvailable: false` for editor-applied WorkspaceEdits;
- `refactorkitDocumentVersionsChecked`, indicating negotiated versionable-edit support;
- `documentChanges` for capable clients, with the exact tracked version on every
  open-document text edit and `null` only for closed documents;
- legacy `changes` only for closed-document text edits when the client lacks
  `documentChanges`; structural/open-document plans refuse in that mode.

Native WorkspaceEdit application is owned by the editor and has no RefactorKit
journal/rollback transaction. `didOpen`/`didChange` full-sync text and strictly
increasing versions are overlaid on disk scans for planning; lifecycle changes
invalidate pending plans. Clients should preserve `refactorkitPlanId` only when
using the distinct managed path. The LSP server stores pending plans internally
so `refactorkit.applyPlan` can apply the exact previewed plan. `refactorkit.applyPlan` returns `{ "transactionId":
"..." }`; `PatchEngine` owns write-ahead lifecycle persistence before mutation,
then the LSP session removes the pending plan, refreshes the workspace snapshot,
and republishes diagnostics. Managed apply refuses with
`DOCUMENT_VERSION_MISMATCH (-32007)` if any buffer is unsaved or an affected
document is open; managed rollback applies the same affected-open-document guard.

`refactorkit.rollback` requires `{ "transactionId": "..." }` and accepts
optional `"force": true`. Normal mode verifies exact post-apply images and never
overwrites later changes; force is an explicit destructive override. On success it
loads the applied journal record, transitions through `ROLLING_BACK`, rolls the
workspace back, retains the record as `ROLLED_BACK`, refreshes the snapshot,
republishes diagnostics, and returns:

```json
{ "status": "rolledBack", "transactionId": "..." }
```

Rollback refusal semantics:

- missing or unknown transaction ids are `INVALID_PARAMS` and must not expose a
  stack trace;
- post-apply divergence maps to `ROLLBACK_CONFLICT` (`-32005`) and leaves the
  journal `APPLIED` for retry; incomplete recovery maps to `RECOVERY_REQUIRED`
  (`-32006`); other rollback engine failures remain concise `INTERNAL_ERROR`s;
- refused preview plans, including referenced `safeDelete`, use `PLAN_REFUSED`
  and do not create a pending plan or workspace edit;
- unknown LSP commands and unknown plan ids are `INVALID_PARAMS` and must not
  leak internal exceptions.

## MCP server baseline

`modules/refactorkit-mcp` exposes JSON-RPC 2.0 over newline-delimited stdio for
local LLM/MCP clients. It intentionally does not expose arbitrary filesystem
access.

The MCP initialize response `serverInfo.version` is read-only metadata and uses
the centralized implementation version. In `v0.3.0`, it reports `0.3.0`.

### Tools

| Tool | Status | Notes |
|------|--------|-------|
| `project_scan`, `project_summary` | `beta-contract` | Workspace lifecycle and project metadata; summary includes high-level Build Model SPI provider/status/source-set information without classpath secrets. |
| `symbol_search`, `symbol_definition`, `symbol_references`, `diagnostics` | `beta-contract` | Read-only AI context queries. |
| `preview_refactoring`, `apply_refactoring`, `rollback_refactoring` | `beta-contract` | Contract applies to beta operations; rollback refuses post-apply divergence by default and accepts explicit `force=true`. |
| `available_refactorings` | `experimental` | Descriptor shape may change. |
| `import_external_java_class` | `experimental` | Import preview with stable provenance/license warning fields in output text. |
| `generate_context_bundle` | `experimental` | Bundle shape may change with agent needs. |

`preview_refactoring` beta operations are `renameClass`, `renameMember`,
`moveClass`, `moveSourceRoot`, `organizeImports`, and `safeDelete`.
`moveSourceRoot` uses `arguments.from`/`arguments.to` and reports typed refusals
in the tool text. `extractMethod` and all
`changeSignature.*` operations are experimental.

`import_external_java_class` requires `code` and `targetPackage`. It accepts
optional `sourceUrl` and `licensePolicy` (`warn`, `block-unknown`, or `allow`),
and records MCP tool calls with `sourceKind=LLM`. The tool output text must show
the same provenance/license warning line as the daemon importer contract under
`Warnings`, including `sourceKind`, `sourceUrl`, `retrievedAt`,
`licenseDetected`, `licenseRisk`, and `originalHash`.

### Resources and prompts

| Resource or prompt | Status | Notes |
|--------------------|--------|-------|
| `project://summary`, `project://symbols`, `diagnostics://latest` | `beta-contract` | Read-only snapshot resources. |
| `symbol://{fullyQualifiedName}` | `beta-contract` | Symbol resource template. |
| `file://...` inside the scanned workspace snapshot | `beta-contract` | Must refuse paths outside the workspace or outside the last scan. |
| `project://dependencies` | `experimental` | Build/dependency summary may change. |
| Prompts `refactor_safely`, `import_external_class_safely`, `explain_patch`, `generate_tests_for_refactor` | `experimental` | Prompt wording is guidance, not a stable API. |

## Migration-note requirements

Breaking changes to `beta-contract` surfaces must include migration notes before
release. The required content is defined in
[Compatibility and deprecation policy](compatibility-policy.md#breaking-change-migration-notes).
