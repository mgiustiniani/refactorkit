# Daemon JSON-RPC protocol

The official daemon is `bin/refactorkit-daemon` (`.bat` on Windows) in the
self-contained distribution. It uses the bundled runtime and complete packaged
classpath; clients do not construct a classpath or install Java.

Transport is JSON-RPC 2.0 as newline-delimited UTF-8 JSON over stdio. Standard
output is reserved for one response per line. Logs use standard error and must
never contain imported source. Close stdin for orderly shutdown; the session
clears pending plans and exits at EOF. After an abrupt managed-write termination,
the caller invokes explicit mutating `patch.recover` before read-only
`project.open`. Project open only inspects journal state and never creates
`.refactorkit` or `workspace.lock`.

## Lifecycle

```json
{"jsonrpc":"2.0","id":1,"method":"server.capabilities"}
{"jsonrpc":"2.0","id":2,"method":"patch.recover","params":{"root":"/workspace"}}
{"jsonrpc":"2.0","id":3,"method":"project.open","params":{"root":"/workspace"}}
{"jsonrpc":"2.0","id":4,"method":"java.importExternalClass","params":{"sourceKind":"clipboard","code":"public class Foo {}","targetDirectory":"module-a/src/main/java/com/example/util","licensePolicy":"warn"}}
{"jsonrpc":"2.0","id":5,"method":"refactor.apply","params":{"planId":"plan-..."}}
{"jsonrpc":"2.0","id":6,"method":"patch.rollback","params":{"transactionId":"transaction-..."}}
```

A successful preview is retained in an access-ordered LRU with at most 128
plans. `project.open` clears the previous workspace's plans before read-only
recovery inspection and scan; successful apply, rollback, and process shutdown clear all plans. Refused
or diagnostics-blocked plans are never retained. Any apply refusal removes that
plan. LRU eviction drops the oldest source-bearing plan reference.

A caller can release a plan immediately without touching the workspace:

```json
{"jsonrpc":"2.0","id":10,"method":"refactor.discard","params":{"planId":"plan-..."}}
```

```json
{"planId":"plan-...","discarded":true}
```

Discard is idempotent: an unknown/already-discarded ID returns `discarded=false`.
No plan content is logged.

## Build-model summary

`project.summary` additively exposes the first internal Build Model SPI projection:
provider/status, discovery-policy outcomes, typed diagnostic codes, modules,
source sets, generated roots, outputs, and scoped reactor edges. External
classpath paths and diagnostic messages are intentionally omitted to avoid
leaking local repository layout or credential-adjacent data. Capability discovery
advertises `buildModelSummary`, `sourceSets`, and `credentialRedaction`.

The response is encoded from versioned typed DTOs and includes
`buildModelLimits`, `buildModelsTruncated`, per-model/module/source-set
`truncated`, and `modulesTruncated`. Ordering is deterministic. Limits are 16
models, 1,024 total model modules, 64 source sets per module, 256 roots per root
category, 1,024 module edges per source set, and 500 typed model diagnostics.
Module paths are workspace-relative (`.` for the root); outside-workspace module
roots are redacted. A checked-in schema-key snapshot prevents accidental shape
drift.

Provider identities include `maven-effective-v1`, `gradle-declarative-v1`,
`java-conventional-v1`, and `typescript-config-declarative-v1`. Project open and
all post-apply/rollback rescans preserve `.ts`, `.tsx`, `.js`, and `.jsx` beside
Java sources; when script sources exist the declarative TypeScript project graph
is attached without executing Node, package managers or project code. Providers
currently project API `0.2` compatibility `Module`
data while semantic consumers migrate source-set by source-set. Summaries include
`ecosystem`, `strategy`, and effective policy outcomes. Capability discovery
advertises offline-missing/execution-refused support; summary responses use the
canonical protocol statuses `available`, `partial`, `offline-missing`,
`unavailable`, and `execution-refused`. See
[Build Model SPI](build-model.md).

## Central workspace index and intelligence query

`project.open` now creates a session-owned, immutable `WorkspaceIndex` over every
recognized source in the exact project snapshot. The index stores normalized
workspace-relative paths, language IDs, UTF-8 sizes and content hashes, then adds
bounded provider symbol partitions. The initial Java partition is explicitly
lexical declaration evidence; it is not presented as JDT/compiler semantics.
Read-only indexing creates no workspace metadata.

```json
{"jsonrpc":"2.0","id":15,"method":"index.status"}
{"jsonrpc":"2.0","id":16,"method":"intelligence.query","params":{"requestId":"ide-42","expectedSnapshotHash":"<sha256>","expectedIndexGeneration":2,"kind":"workspaceSymbols","query":"User","languageId":"java","limit":50}}
```

`index.status` reports snapshot/generation, source/symbol counts, indexed
languages and provider evidence/completeness/truncation without source text or
local toolchain paths. `intelligence.query` currently implements typed,
zero-based UTF-16 `workspaceSymbols`, `documentSymbols`, TypeScript/JavaScript
`completion`, and TypeScript/JavaScript `hover` results. Requests are bounded to
200 results and correlated to the exact expected snapshot. Signature help,
definition-at-position, references-at-position, and unsupported language/provider
rows remain refused until their implementations qualify. They never fall back to
fabricated lexical semantics.

TypeScript/JavaScript `documentSymbols`, `completion` and `hover` additionally accept
`sourceAuthority.kind=immutable-editor-overlay`, versioned existing documents and
the active `semanticLease`. The selected path must be in the overlay. RefactorKit
queries the derived provider snapshot, rejects stale versions, restores saved LSP
documents before returning, and reports provider/overlay hashes without echoing
content. Completion and hover additionally require a zero-based UTF-16
`position`. Completion returns bounded typed candidates, replacement ranges,
additional text edits, snippet/plain-text format, commit characters and explicit
incompleteness. Provider completion commands are not executed. Hover returns
bounded typed plaintext/Markdown sections plus an optional exact range. The response schema is
[`api-0.2-intelligence-query-schema.json`](api-0.2-intelligence-query-schema.json).

## Experimental TypeScript/JavaScript semantic session

After `project.open`, callers may start one explicit language session:

```json
{"jsonrpc":"2.0","id":20,"method":"typescript.semantic.start","params":{"languageId":"typescript","nodeExecutable":"/tools/node","languageServerPackageRoot":"/tools/typescript-language-server","typeScriptPackageRoot":"/tools/typescript"}}
```

No PATH lookup or workspace-local executable/package root is accepted unless the
corresponding explicit boolean policy is supplied. The response reports bounded
server/capability/executable/argument hashes, the owned local process ID, and
semantic completeness, never raw arguments or environment values. Startup also
requests a saved-snapshot declaration projection and, when qualified, atomically
adds it to `WorkspaceIndex` with language-server evidence and a path-free
provenance hash. The nested `index` result reports ready/refused status, generation,
symbol count and truncation. Projection is bounded to 256 source files, 50,000
symbols and one 30-second aggregate deadline; timeout, evidence drift, invalid
ranges or unsafe paths publish no partial provider partition. A request timeout
or protocol failure stops the worker and refuses startup/restart rather than
issuing a dead semantic lease.

If that process crashes, `typescript.semantic.restart` is the only restart path:
it requires the original snapshot, preserves provenance, rotates the lease and
refreshes the provider partition under the same bounds. `typescript.semantic.stop`
is idempotent and removes the TypeScript/JavaScript provider partition.

Once started, `symbol.search`, `symbol.definition`, `symbol.references`, and
`diagnostics` accept `languageId=typescript|javascript`. Semantic rename uses:

```json
{"jsonrpc":"2.0","id":21,"method":"refactor.preview","params":{"operation":"renameSymbol","languageId":"typescript","symbol":"lsp-symbol-v1:<sha256>","arguments":{"newName":"AccountService","allowExternalConsumers":"false","allowDynamicReferences":"false"}}}
```

A caller may provide zero-based `arguments.file`, `arguments.line`, and
`arguments.character` instead of `symbol`. Preview remains non-writing. Apply
uses the retained language-specific exact diagnostics gate, explicit daemon
authorization, `PatchEngine`, WAL and rollback. A successful apply closes semantic
sessions because their original snapshot is stale.

## Maven reactor and source-root relocation

`project.open` builds Maven effective models offline and does not execute plugins.
Set `resolveDependencies=true` only for explicit anonymous Maven Central
resolution; Maven settings and credentials are never loaded. `refactor.preview`
accepts the rename-only relocation contract:

```json
{"operation":"moveSourceRoot","arguments":{"from":"domain/src/main/java","to":"domain-relocated/src/main/java"}}
```

A successful result is retained and applied through normal `refactor.apply`; the
resulting transaction is rolled back through `patch.rollback`. A refused result
uses `PLAN_REFUSED (-32001)` and `error.data.refusalCode` with a typed
`sourceRoot.*`, `buildModel.unavailable`, or `classpath.unavailable` value.
Protocol paths always use `/`. `diagnostics` accepts optional `verbose=true` to
show derivative JDT errors otherwise suppressed behind a typed module root cause.

## External Java import preview

`java.importExternalClass` is preview-only. `targetDirectory` is an existing
workspace-relative filesystem directory; RefactorKit derives module, source root,
source set, and package. Legacy `targetPackage` is a logical Java package and
continues to work. If both are supplied they must agree.

Importer responses use normalized lower-case values and retain temporary API
`0.2` aliases (`legacyStatus`, `legacyRiskLevel`, `legacyEvidence`,
`affectedFilePaths`, `applyEligible`, and legacy provenance names).
Representative fields:

```json
{
  "planId": "plan-...",
  "operation": "importExternalJavaClass",
  "status": "preview",
  "legacyStatus": "PREVIEW",
  "summary": "...",
  "confidence": 0.9,
  "riskLevel": "low",
  "evidence": ["structural"],
  "affectedFiles": [
    {"change":"create","path":"module/src/main/java/com/example/Foo.java","previousPath":null,"primary":true}
  ],
  "placement": {
    "moduleName": "module",
    "sourceRoot": "module/src/main/java",
    "sourceSet": "main",
    "packageName": "com.example"
  },
  "renderedDiff": "--- /dev/null\n+++ b/module/src/main/java/com/example/Foo.java\n...",
  "structuredDiff": [
    {
      "path": "module/src/main/java/com/example/Foo.java",
      "change": "create",
      "previousPath": null,
      "hunks": [{
        "oldStart": 0,
        "oldLines": 0,
        "newStart": 1,
        "newLines": 3,
        "lines": ["+package com.example;", "+", "+public class Foo {}"],
        "truncated": false
      }],
      "truncated": false
    }
  ],
  "diagnosticsAfterPreview": [],
  "applyEligibility": {
    "eligible": true,
    "blockers": [],
    "acknowledgementRequirements": []
  },
  "staleness": {"stale":false,"reasons":[]},
  "snapshot": {"hash":"...","validatedOnApply":true},
  "provider": {"name":"refactorkit-java-external-importer","version":"..."}
}
```

The diff is derived from the exact retained `WorkspaceEdit`, never from a second
planning pass. Create, modify, move, and delete have explicit change kinds. Files
are ordered by protocol path; hunks preserve deterministic plan content. Source
may appear in this caller-facing preview only—never in errors, events, logs, or
stderr. No raw HTML is emitted.

Diff limits are returned in `diffLimits`; `diffTruncated` and
`diffTruncationReasons` declare every truncation:

- combined rendered/structured source-line budget: 524,288 UTF-8 bytes;
- files: 128;
- hunks per file: 64;
- lines per hunk: 2,000.

## Virtual diagnostics and eligibility

Preview applies the exact edit through `WorkspaceEditSimulator`, then runs the
Java/JDT provider against the virtual post-image. It does not modify workspace
files. At most 500 structured diagnostics, 262,144 UTF-8 bytes total, and
4,096 characters per message are returned; `diagnosticsTruncated` reports
overflow. Paths use `/`; line and column are one-based.

Preview diagnostics are advisory evidence calculated at planning time. They are
not copied into `PatchPlan.diagnosticsAfterPreview` as approved errors. Any new
ERROR compared with the pre-image becomes an `applyEligibility.blockers` entry,
so the plan is not retained or applicable. Apply independently rescans the
workspace and reruns the central JDT diagnostics gate under the workspace lock;
preview success never bypasses apply-time validation.

For `licensePolicy=warn`, unknown/high-risk provenance remains eligible but adds
an acknowledgement requirement; the explicit apply RPC is the audited approval.
Unknown license uses conservative `high` risk because core API `0.2` has no
`UNKNOWN` risk enum. `block-unknown` returns `status=refused` and is not pending.

## Apply and rollback

Apply uses the exact retained plan:

```json
{
  "status":"applied",
  "planId":"plan-...",
  "transactionId":"transaction-...",
  "changedFiles":[{"change":"create","path":".../Foo.java","previousPath":null,"primary":true}],
  "changedFilePaths":[".../Foo.java"],
  "primaryFile":".../Foo.java",
  "diagnostics":[],
  "diagnosticsTruncated":false,
  "snapshotHash":"...",
  "provider":{"name":"RefactorKit","version":"..."}
}
```

Rollback derives inverse change kinds from the WAL's retained forward edit:

```json
{
  "status":"rolledBack",
  "transactionId":"transaction-...",
  "rolledBack":true,
  "changedFiles":[{"change":"delete","path":".../Foo.java","previousPath":null,"primary":false}],
  "changedFilePaths":[".../Foo.java"],
  "diagnostics":[],
  "diagnosticsTruncated":false,
  "snapshotHash":"...",
  "provider":{"name":"RefactorKit","version":"..."}
}
```

Create rollback is delete, delete rollback is create, modify remains modify, and
move reverses source/target. Normal rollback remains post-image conflict-safe;
force remains explicit. Filesystem bytes/metadata are restored by the existing
WAL contract.

The importer primary file is the file for the sole public top-level type. With
multiple public types it is the first declaration in source order, independent
of set/map or diff ordering.

## Portable paths and capabilities

Every workspace-relative protocol path uses `/` on all hosts. This includes file
changes, primary/source-root paths, diffs, diagnostics, conflicts/evidence, apply,
and rollback. Absolute workspace/module roots remain native descriptive paths.

`server.capabilities` advertises importer features explicitly:

```json
{
  "name":"java.importExternalClass",
  "stability":"experimental",
  "requiresProject":true,
  "writesWorkspace":false,
  "features":{
    "targetDirectory":true,
    "preview":true,
    "renderedDiff":true,
    "structuredDiff":true,
    "previewDiagnostics":true,
    "apply":true,
    "discard":true,
    "rollback":true
  }
}
```

See [Integration contracts](integration-contracts.md),
[External class importer](external-class-importer.md),
[Resource limits](resource-limits.md), and
[Transactionality audit](transactionality-audit.md).
