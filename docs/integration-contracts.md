# Integration contracts

See AGENTS.md for the authoritative initial architecture and implementation rules.

Status: implementation-informed P0 baseline for `v0.2.0-beta` plus the
post-beta `0.3.0-SNAPSHOT` version/API metadata slice. The beta is not a
`v1.0.0` API freeze. Compatibility labels, API-version expectations, and
breaking-change migration-note rules are defined in
[Compatibility and deprecation policy](compatibility-policy.md).

Current mainline metadata after `v0.2.0-beta`:

- implementation name: `RefactorKit`;
- implementation version: `0.3.0-SNAPSHOT`;
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
| `diagnostics-failure` | Apply/rollback diagnostics report failure or post-apply validation fails. | `INTERNAL_ERROR` until specialized code exists |
| `unsupported-operation` | Method, command, operation, language, or refactoring is not supported. | `METHOD_NOT_FOUND` or `INVALID_PARAMS` |
| `unsafe-path` | Path escapes the workspace root or was not part of the scanned snapshot. | `INVALID_PARAMS` |
| `conflict-or-license-policy` | Naming conflict, overwrite refusal, unknown/risky license, or license policy block. | `PLAN_REFUSED` or `INVALID_PARAMS` |

## CLI compatibility baseline

The command names below are the documented CLI surface for `v0.2.0-beta`.
`--apply` remains opt-in for mutating operations; preview is the default.

`refactorkit --version` and `refactorkit version` are read-only beta-contract
metadata surfaces. On main after the beta release they should report the
implementation version `0.3.0-SNAPSHOT` and API version `0.2`; automation should
check the API version when deciding whether a beta-contract integration is
compatible.

| CLI command | Status | Contract notes |
|-------------|--------|----------------|
| `refactorkit --version` / `refactorkit version` | `beta-contract` | Read-only implementation/API metadata; current main reports version `0.3.0-SNAPSHOT` and API version `0.2`. |
| `refactorkit scan <path>` | `beta-contract` | Read-only project scan summary. |
| `refactorkit index <path>` | `beta-contract` | Alias-compatible indexing workflow. |
| `refactorkit symbols <path>` / `refactorkit java symbols <path>` | `beta-contract` | Symbol listing shape should remain scriptable. |
| `refactorkit diagnostics <path>` / `refactorkit java diagnostics <path>` | `beta-contract` | Diagnostic severity/file/line fields are contract fields. |
| `refactorkit definition --symbol <fqcn> [path]` / `java definition` | `beta-contract` | Read-only lookup. |
| `refactorkit references --symbol <fqcn> [path]` / `java references` | `beta-contract` | Read-only reference listing; exact signed member selectors may use JDT binding evidence when analysis is clean, otherwise lexical fallback remains. |
| `refactorkit rename --symbol <fqcn> --to <name> [--apply] [path]` | `beta-contract` | Java class rename preview/apply/rollback safety semantics. |
| `refactorkit rename-member --symbol <FQN#member> --to <name> [--apply] [path]` | `beta-contract` | Signed method selectors use the proven JDT exact-overload slice; unsigned member rename remains lexical and constructors/ambiguous cases are refused. |
| `refactorkit move-class --symbol <fqcn> --to-package <pkg> [--apply] [path]` | `beta-contract` | Java class move preview; warnings remain required for framework/string risks. |
| `refactorkit organize-imports <file...> [--apply] [--root <path>]` | `beta-contract` | Sort/deduplicate/remove same-package imports; full unused-import removal is not promised while lexical. |
| `refactorkit safe-delete --symbol <fqcn> [--force] [--apply] [path]` | `beta-contract` | Refuses referenced symbols by default; forced behavior requires explicit risk note. |
| `refactorkit patch rollback <transaction-id> --root <path>` | `beta-contract` | Rollback transaction lookup and workspace-root safety. |
| `refactorkit test-golden [case] [--golden-dir <path>]` | `beta-contract` | CI/test harness command for documented golden fixtures. |
| `refactorkit extract-method ...` | `experimental` | Limited MVP; success/refusal coverage expands during beta. |
| `refactorkit change-signature ...` | `experimental` | Limited rename/add/reorder/remove parameter support; conservative refusal expected. |
| `refactorkit java import-class ...` | `experimental` | External code assimilation; provenance, conflict, and license-policy behavior may change before `v1.0.0`. |
| `refactorkit recipe run ...` | `experimental` | Recipe schema is not yet a beta contract. |
| `refactorkit outline`, `search`, `local-rename` | `experimental` | Multi-language structural features, not semantic Java refactoring contracts. |
| Any unlisted/debug subcommand | `internal` | Not supported for external automation. |

## Daemon JSON-RPC compatibility baseline

`modules/refactorkit-daemon` exposes JSON-RPC 2.0 over newline-delimited stdio.
Request/response envelopes, method names, and the error categories above are part
of the beta baseline for documented methods.

### Method classification

| Method | Status | Request category |
|--------|--------|------------------|
| `server.version` | `beta-contract` | Read-only implementation/API metadata; does not require an opened project. |
| `project.open` | `beta-contract` | Workspace lifecycle; requires `root`. |
| `project.summary` | `beta-contract` | Read-only project metadata. |
| `symbol.search` | `beta-contract` | Read-only symbol query; optional `query`. |
| `symbol.definition` | `beta-contract` | Read-only lookup; requires `symbol`. |
| `symbol.references` | `beta-contract` | Read-only reference query; requires `symbol`. |
| `diagnostics` | `beta-contract` | Read-only diagnostics query. |
| `refactor.preview` | `beta-contract` | Patch-plan preview envelope and refusal behavior. |
| `refactor.apply` | `beta-contract` | Requires `planId`; must reject stale snapshots/plans. |
| `patch.rollback` | `beta-contract` | Requires `transactionId`; must stay inside workspace root. |
| `java.importExternalClass` | `experimental` | External Java import preview; requires an opened project snapshot. |
| Any unlisted method | `internal` | Treat as unsupported. |

### `server.version` contract

`server.version` is a read-only beta-contract method for compatibility checks.
It does not open, scan, or modify a workspace.

Request parameters: none.

Current mainline response after `v0.2.0-beta`:

```json
{
  "name": "RefactorKit",
  "version": "0.3.0-SNAPSHOT",
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
- `targetPackage`: fully-qualified target package, or an empty string for the
  default package.

Optional request fields:

- `targetModule`: module/source-root selector when the scanned workspace has
  multiple modules;
- `sourceUrl`: URL or provenance URL for the source text;
- `sourceKind`: `clipboard`, `url`, `file`, `llm`, or `snippet`; defaults to
  `snippet` for daemon requests;
- `licensePolicy`: `warn`, `block-unknown`, or `allow`; defaults to `warn`.

The response uses the standard patch-plan preview envelope with `planId`,
`operation=importExternalJavaClass`, `status`, `summary`, `confidence`,
`riskLevel`, `affectedFiles`, `warnings`, and `diagnosticsAfterPreview`. The
`warnings` array must include exactly one provenance/license line with these
stable key names:

```text
Provenance: sourceKind=... sourceUrl=... retrievedAt=... licenseDetected=... licenseRisk=... originalHash=...
```

`sourceUrl` is `(none)` when no URL is known; `retrievedAt` is an ISO-8601
instant; `originalHash` is the SHA-256 hash of the cleaned source text.

### `refactor.preview` operation classification

| Operation | Status | Required arguments |
|-----------|--------|--------------------|
| `renameClass` | `beta-contract` | `symbol`, `arguments.newName` |
| `renameMember` | `beta-contract` | `symbol`, `arguments.newName` |
| `moveClass` | `beta-contract` | `symbol`, `arguments.targetPackage` |
| `organizeImports` | `beta-contract` | `arguments.file` or `symbol` as file path |
| `safeDelete` | `beta-contract` | `symbol`, optional `arguments.force` |
| `extractMethod` | `experimental` | `arguments.file`, `startLine`, `endLine`, `methodName` |
| `changeSignature.renameParameter` / `renameParameter` | `experimental` | `symbol`, `oldName`, `newName` |
| `changeSignature.addParameter` / `addParameter` | `experimental` | `symbol`, `type`, `name`, `default` |
| `changeSignature.reorderParameters` / `reorderParameters` | `experimental` | `symbol`, `order` |
| `changeSignature.removeParameter` / `removeParameter` | `experimental` | `symbol`, `name` |

A refused preview must not silently fall back to text replacement. Clients should
show the refusal, warnings, affected files when present, and next action.

## LSP server baseline

`modules/refactorkit-lsp` exposes a JSON-RPC/LSP server over stdio using standard
`Content-Length` framing. Workspace edits include legacy `changes` for text edits
and `documentChanges` for file create/delete/rename operations.

The LSP initialize response `serverInfo.version` is read-only metadata and should
use the centralized implementation version. On main after `v0.2.0-beta`, it
reports `0.3.0-SNAPSHOT`.

| Capability or command | Status | Notes |
|-----------------------|--------|-------|
| `textDocument/definition`, `references`, `documentSymbol`, `diagnostic` | `beta-contract` | Read-only editor integration; definition/references may use JDT binding evidence for clean exact overloaded member call sites. |
| `textDocument/prepareRename`, `textDocument/rename` | `beta-contract` | Must use RefactorKit previews and refusal handling; rename-position resolution may produce signed method selectors for the proven JDT exact-overload slice. |
| `textDocument/codeAction`, `workspace/executeCommand` envelope | `beta-contract` | Command transport and refusal categories are baseline. |
| `textDocument/semanticTokens/full` | `experimental` | Token shape may change. |
| `refactorkit.renameClass`, `refactorkit.renameMember`, `refactorkit.moveClass`, `refactorkit.organizeImports`, `refactorkit.safeDelete`, `refactorkit.applyPlan`, `refactorkit.rollback` | `beta-contract` | Command names, preview metadata, and safety semantics are stable for beta pilots. |
| `refactorkit.extractMethod` | `experimental` | Limited MVP. |
| `refactorkit.changeSignature.renameParameter`, `refactorkit.changeSignature.addParameter`, `refactorkit.changeSignature.reorderParameters`, `refactorkit.changeSignature.removeParameter` | `experimental` | Limited MVP. |
| Alias `refactorkit.renameParameter` | `experimental` | Compatibility alias, not preferred for new clients. |

### LSP `workspace/executeCommand` preview and rollback contract

Preview commands such as `refactorkit.renameClass`, `refactorkit.renameMember`,
`refactorkit.moveClass`, `refactorkit.organizeImports`, and
`refactorkit.safeDelete` return a WorkspaceEdit-compatible object with beta
RefactorKit metadata:

- `refactorkitPlanId`: pending plan id to pass to `refactorkit.applyPlan`;
- `operation`, `status`, `summary`, `riskLevel`, and `warnings`;
- legacy `changes` for text edits;
- `documentChanges` for text edits plus create, delete, and rename file edits.

Clients should preserve `refactorkitPlanId` from the preview result. The LSP
server also stores pending plans internally so `refactorkit.applyPlan` can apply
the exact previewed plan. `refactorkit.applyPlan` returns `{ "transactionId":
"..." }`, writes transaction metadata, removes the pending plan, refreshes the
workspace snapshot, and republishes diagnostics.

`refactorkit.rollback` requires `{ "transactionId": "..." }`. On success it
loads transaction metadata, rolls the workspace back, deletes the transaction-log
entry, refreshes the snapshot, republishes diagnostics, and returns:

```json
{ "status": "rolledBack", "transactionId": "..." }
```

Rollback refusal semantics:

- missing or unknown transaction ids are `INVALID_PARAMS` and must not expose a
  stack trace;
- rollback engine refusal currently maps to `INTERNAL_ERROR` with a concise
  `Rollback refused: ...` message;
- refused preview plans, including referenced `safeDelete`, use `PLAN_REFUSED`
  and do not create a pending plan or workspace edit;
- unknown LSP commands and unknown plan ids are `INVALID_PARAMS` and must not
  leak internal exceptions.

## MCP server baseline

`modules/refactorkit-mcp` exposes JSON-RPC 2.0 over newline-delimited stdio for
local LLM/MCP clients. It intentionally does not expose arbitrary filesystem
access.

The MCP initialize response `serverInfo.version` is read-only metadata and should
use the centralized implementation version. On main after `v0.2.0-beta`, it
reports `0.3.0-SNAPSHOT`.

### Tools

| Tool | Status | Notes |
|------|--------|-------|
| `project_scan`, `project_summary` | `beta-contract` | Workspace lifecycle and project metadata. |
| `symbol_search`, `symbol_definition`, `symbol_references`, `diagnostics` | `beta-contract` | Read-only AI context queries. |
| `preview_refactoring`, `apply_refactoring`, `rollback_refactoring` | `beta-contract` | Contract applies to beta operations; experimental operations keep their label. |
| `available_refactorings` | `experimental` | Descriptor shape may change. |
| `import_external_java_class` | `experimental` | Import preview with stable provenance/license warning fields in output text. |
| `generate_context_bundle` | `experimental` | Bundle shape may change with agent needs. |

`preview_refactoring` beta operations are `renameClass`, `renameMember`,
`moveClass`, `organizeImports`, and `safeDelete`. `extractMethod` and all
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
