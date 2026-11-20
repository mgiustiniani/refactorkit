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

A successful preview is retained in the daemon session's language-neutral
`PendingPlanStore`, an access-ordered LRU with at most 128 exact payloads.
Successful lookup refreshes recency; eviction only releases the untouched least-
recently accessed in-memory reference. `project.open` clears the previous
workspace's plans before read-only recovery inspection and scan; successful
apply, rollback, and process shutdown clear all plans. Refused or diagnostics-
blocked plans are never retained. Any apply refusal removes that plan. These
lifecycle decisions remain daemon policy; the shared store performs no filesystem,
lock, WAL, transaction, authorization, or rollback action.

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

Gradle rows with provider-qualified identity include workspace-relative `buildRoot`
and Gradle `projectPath`, alongside their stable qualified `id`. This identity is
present both in `buildModels[].modules` and in the top-level `modules` list used by
project enumerators. A top-level row receives these fields only when exactly one
build-model module owns its root; ambiguous ownership leaves the identity fields
null. Consumers can identify included-build projects before requesting source-set
context. The ID uses the same
[Gradle composite identity](java-project-context-protocol.md#explicit-effective-gradle-synchronization)
as `project.context` v3. Capability discovery advertises
`gradleCompositeBuildIdentity` for this projection.

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
`completion`, `hover`, `signatureHelp`, and saved-snapshot Java `definition`,
`references`, and `hover` results. Requests are bounded and correlated to the exact expected snapshot. Queries accept
`priority=interactive|normal|background` (default `interactive`). Priority can
reorder reads only inside the same FIFO stateful/control barrier, so project,
provider-lifecycle and mutation operations are never crossed.
Unsupported language/provider definition/reference rows remain refused until
their implementations qualify. They never fall back to
fabricated lexical semantics.

An unhandled request-handler failure is isolated to its request. The daemon
returns a JSON-RPC `INTERNAL_ERROR` response for ordinary exceptions and
non-fatal JVM errors, then keeps the session worker available for queued
requests. `VirtualMachineError` and `ThreadDeath` are rethrown because the JVM
may not be able to recover safely; process liveness is not guaranteed after
those fatal conditions.

Java `definition` requires `languageId=java`, a saved-snapshot source authority,
workspace-relative path and exact zero-based UTF-16 position. Eclipse JDT bindings
return zero or one exact source location and lazily publish the bounded
`java-jdt-bindings-v1` partition. Analysis is cached for two exact snapshots,
limited to 10,000 Java files and 256 MiB aggregate UTF-8 source, and cooperatively cancellable between compilation
units. A Java cursor on a resolved Kotlin JVM declaration without qualified
source mapping yields `status: "refused"` and
`error.code: "java.definitionKotlinSourceUnqualified"`. For an evaluated Gradle
source set, callers may supply `kotlinDefinitionToolchain` alongside `javaContext`
on a saved Java definition request. `server.capabilities` advertises support as
`features.kotlinDefinitionToolchain: true` on `intelligence.query`. Clients can
check this flag before sending the optional field with the absolute `jdkHome`,
`compilerJar` and `compilerClasspath` paths for the qualified Kotlin compiler.
The daemon uses a disposable compilation of the saved source set with the
evaluated module name. The bound class must come from the unique declared Kotlin
output and match the fresh class bytes before the exact JVM declaration can
supply a Kotlin source location. Ambiguous origins and changed binaries return
`java.definitionKotlinBinaryAmbiguous` and `java.definitionKotlinBinaryDrift`.
The response keeps the Java binding provider and includes the compiler and binary
evidence in `provenanceHash`. The opt-in only applies to this read-only query.
Java `hover` returns the exact selected range, binding-derived Java
signature, qualified identity, bounded Javadoc and warning-derived completeness.
Java `references` uses the same authority, accepts `includeDeclaration`
(default `true`) and a limit up to 1,000, and returns deterministic locations with
total/returned/truncation metadata plus `complete` and JDT warning count. Responses report compiler provenance, source
content hash, resulting index generation and cache counters without exposing
binding keys.

TypeScript/JavaScript `documentSymbols`, `completion`, `hover` and `signatureHelp` additionally accept
`sourceAuthority.kind=immutable-editor-overlay`, versioned existing documents and
the active `semanticLease`. The selected path must be in the overlay. RefactorKit
queries the derived provider snapshot, rejects stale versions, restores saved LSP
documents before returning, and reports provider/overlay hashes without echoing
content. Completion and hover additionally require a zero-based UTF-16
`position`. Completion returns bounded typed candidates, replacement ranges,
additional text edits, snippet/plain-text format, commit characters and explicit
incompleteness. Provider completion commands are not executed. Hover returns bounded typed
plaintext/Markdown sections plus an optional exact range. Signature help returns
bounded overloads, exact parameter-label spans, and active signature/parameter
state. The response schema is
[`api-0.2-intelligence-query-schema.json`](api-0.2-intelligence-query-schema.json).

The daemon recursively watches saved workspace files after `project.open` without
creating metadata. `workspace.watch.status` reports active/failed state, bounded
directory/event counters, overflow and refresh count. `workspace.refresh` forces
a rescan and returns the new snapshot/generation, change counts, a bounded path
sample, invalidated providers and stopped semantic languages; result schema:
[`api-0.2-workspace-refresh-schema.json`](api-0.2-workspace-refresh-schema.json).
Automatic refresh
runs before later requests after coalesced events. Watcher failure or the 4,096-
directory bound falls back to refresh-before-request.

`intelligence.cancel` accepts a semantic `requestId`. A queued or running query
finishes with JSON-RPC error `-32800`. Running LSP operations receive
`$/cancelRequest`; acknowledged cancellation preserves the provider lease, while
no acknowledgement within 250 ms stops the provider. Cancellation never
publishes partial semantic results.

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
The additive `languageId=jvm` route deterministically combines Java JDT and Kotlin
K2 rows for the saved snapshot, tags each row with its owning language and retains
a concise `kotlin.toolchainNotConfigured` root when Kotlin inputs are unavailable;
see `docs/requirements/mixed-jvm-diagnostics.md`.

For standalone Java diagnostics, optional `jdkHome` is the explicitly selected
project SDK; CLI uses `--jdk-home`. SDK27 has separately qualified native API
diagnostics for source/target8,17,25 on Maven and evaluated Gradle9.8.1; release17
still selects API17, including declared plain Java. This does not enable source27
analysis. V2-v5 SDK API-profile values include27 while their source-level bound
remains25; closed property sets and API versions are unchanged. Legacy source26
acquisition returns `java.context.sourceLevelUnsupported`; v6/v7 retain their
independent metadata/semantic policies. See [SDK27 authority](arc42/appendix-requirements.adoc#java-sdk27-native-api-provider-matrix-2026-11-14).
 SDK21/25 with complete `source=8,target=8`
compiler facts uses native SDK APIs, while `--release 8` retains API8. The runtime
property/environment fallback remains available for historical release analysis;
it does not authorize source/target compilation without an explicit project SDK.
Missing explicit source/target SDK emits `java.platform.explicitJdkRequired`;
custom system/compiler facts emit `java.platform.compilerApiUnqualified` rather
than clean diagnostics or derivative type-resolution errors. Compiler SDK pins
must match the selected SDK. Java module filtering excludes unrelated providers,
including TypeScript projects with the same module name.

Maven auxiliary inputs that also occur in generic source inventory are promoted
to one source classification only when captured contents agree. Different captured
contents fail with the existing source-capture `INPUT_CHANGED` classification
before evidence attachers; files and unrelated auxiliary metadata are retained.
This repairs project.open on Maven workspaces containing report JavaScript.
No IDE-specific exclusion, build, processor or user-code execution is introduced.

Exact local qualification and limits:
`.refactorkit/runs/java-diagnostics-sdk-selection-r001/handoff.json`, linked to
[ARC42 SDK diagnostics](arc42/appendix-requirements.adoc#java-standalone-diagnostics-sdk-selection-2026-11-14).
No wire field/version changes: diagnostics still returns its existing structured
array. The original library Path-based method remains compatible; the additional
typed selection separates project SDK from release fallback.

SDK-source and dependency-source declaration navigation now have separate bounded
qualification for source8 with SDK21/25 APIs. They select the owning Java source
set's API profile independently of consumer grammar, including flat roots shared
with JavaScript. Exact JRT module/class provenance, binary/source identity,
archive integrity and closed editor-frame guards remain required. Historical
release8 sources on SDK21 remain unavailable; a missing src.zip remains unavailable.
No request field, schema or capability flag was added. Immutable runtime paths,
checksums, source/runtime tests and the old/new comparison are recorded in
`.refactorkit/runs/java-sdk-source-api-selector-r003/handoff.json`; integrated
authority is [ARC42](arc42/appendix-requirements.adoc#java-source-navigation-api-profile-2026-11-14).
Consumer acceptance and broader source/target, Gradle and resource matrices remain
open. A subsequent candidate qualifies Java compiler diagnostics over immutable
editor drafts through `intelligence.query` with `languageId: "java"`,
`kind: "diagnostics"`, the existing `javaContext` and `sourceAuthority` frame.
Discover `javaImmutableEditorOverlayDiagnostics` in that method's feature flags.
`diagnostics.v2` continues to accept TypeScript/JavaScript only.

The new result has `coverage: "compiler-errors"`, `complete`, `items`, `total`,
`provenanceHash` and `analyzedSourceCount`, plus the existing snapshot/generation,
context/platform/provider-snapshot and exact document version/content attestations.
Items have error severity, code/message and UTF16 path/range locations. Analysis
uses all captured Java: saved sources plus supplied draft replacements. Thus
`analyzedSourceCount` may exceed the number of draft documents. Clients must capture
all open unsaved Java buffers and discard results if the draft set or its versions/
content change; buffers never communicated cannot be attested by the server.
Compiler-error completeness does not include lint warnings or project-build parity.

Optional `limit` must be an integer in 1..500. Count/message budgets refuse with
`java.diagnostics.limitExceeded`, without partial items or a complete assertion.
Stale frames and changed SDK/context retain typed refusals. Queued cancellation
retains JSON-RPC `REQUEST_CANCELLED` (-32800), clears registration and permits
request-ID reuse. Saved and editor diagnostics now report cross-file duplicate
native TYPE identities within one Java module/source set; separate modules and
main/test compilations remain separate. The route never builds or runs user code.

Exact candidate, real messages, limits and qualification:
`.refactorkit/runs/java-editor-sdk-diagnostics-r002/handoff.json`, with authority in
[ARC42 editor diagnostics](arc42/appendix-requirements.adoc#java-editor-compiler-diagnostics-2026-11-14)
and [cross-source collisions](arc42/appendix-requirements.adoc#java-editor-cross-source-type-collisions-2026-11-14).
Local qualification is complete; independent IDE/native/GUI acceptance is pending.
No default installation or full M2/0.8 promotion is implied.

After an explicitly authorized Gradle sync, the evaluated Java source-set and
compiler generated-output roots now populate `sourceRootEvidence`. Every root
has exact provider, source-set and installed `java`/`java-base` plugin ownership;
`executionId` is the corresponding `JavaCompile` task path as metadata, not task
execution. Normal roots use `build-plugin` origin; roots reported as generated by
Gradle use `generated`. Source-root coverage, uniqueness, origin and task/source-set
agreement are validated before publishing the model. Unknown/inconsistent exports
refuse `gradle.sync.sourceRootProvenanceUnavailable`; partial ownership retains
`java.context.sourceRootProvenanceIncomplete` at diagnostics. No directory-name
heuristic or consumer-side reconstruction supplies authority.

The new qualified candidate supports the bounded Gradle 9.8.1/SDK21,25 source8/
target8 and release8 editor cells, custom and pre-existing generated inputs. Its qualification
runs no JavaCompile task; editor queries do not re-evaluate Gradle. Reacquire the
snapshot/context after sync: the authoritative model now includes provenance,
so its hashes can differ from the preceding candidate. A changed build with a
pinned generation retains `intelligence.indexStale` before the Java context check.

Exact immutable paths, SHA256, messages and limits:
`.refactorkit/runs/java-gradle-source-root-provenance-r003/handoff.json`.
[ARC42 Gradle provenance](arc42/appendix-requirements.adoc#gradle-evaluated-source-root-provenance-2026-11-14)
owns integrated status. No new public wire key, capability flag, API/source version or
default installation changes. Independent IDE acceptance and full 0.8 remain open.


For Java/Maven `moveClass`, the operation-specific dispatcher admits exact JDT
plans, returns REQ-003 non-managed guidance for bounded enumerable authority
defects, or projects broad legacy lexical uncertainty as schema-v1
`LEXICAL_FALLBACK_REVIEW`. The lexical-review result is explicitly
`NOT_SEMANTICALLY_PROVEN`, exposes no `planId` or edit, and is retained only in a
bounded edit-free audit cache under its deterministic non-capability
`operationId`. `refactor.apply` accepts exactly one of `planId` or `operationId`:
a still-known lexical operation returns `PLAN_VALIDATION_FAILED (-32008)` with
`evidence.insufficient` and the same envelope in `error.data`, before lock/WAL;
unknown or evicted identities return `INVALID_PARAMS (-32602)`.

## Java plan-only preview command dispatch

For `renameClass`, `renameMember`, `moveSourceRoot`, single-file Java
`organizeImports`, and `safeDelete`, `DaemonSession` now completes the existing
JSON-RPC parsing, alias/default conversion, required-field checks, and unknown-
operation handling before constructing one sealed `JavaRefactoringPreviewCommand`.
It then calls the fieldless `JavaRefactoringPreviewDispatcher` with the current
session snapshot and current Java adapter. An internal per-call invoker seam maps
the command to one unchanged default planner and returns the exact planner result
or exception. Refused-plan conversion, pending-plan admission, and structured
plan rendering remain daemon-owned after dispatch.

This is an internal implementation reconciliation, not a protocol revision.
`refactor.preview` method/operation names, arguments, response and error shapes,
stability classifications, `server.capabilities`, external schemas, and
capability catalogues are unchanged. The boundary excludes `moveClass`, all
other-language/mixed/experimental/Maven routes, CLI/LSP, multi-file imports,
diagnostics, lifecycle, managed apply/rollback, persistence, and
`ManagedApplyInvoker`.

Bounded OpenJDK 21.0.11 source-built/in-process acceptance passed all four
requirements/eight cases/94 steps (log SHA-256
`10c805b67306026cb4ab4a79de9b7258c712e28aaf0bb0070279edbbc44914f5`).
The corrected REQ-003 case uses public daemon and MCP session dispatch for all
five operations plus missing, unknown, and refused paths and verifies no preview
writes/residue. Daemon regression remained 58/58 within the combined zero-
failure Java/daemon/MCP regression receipt SHA-256
`9a1cb587e7e377682a09353f470140a76e4dae1e5cb42a727f8ed346e2a0e9b1`.
Final dated mutable whole-working-tree source-built verification on 2026-08-01
used OpenJDK 21.0.11 and
`./gradlew --no-daemon --rerun-tasks check goldenTest`: `BUILD SUCCESSFUL` in
6m 54s; 1045 tests were discovered, 817 executed, 228 skipped/tag-filtered,
zero failures/errors, 140 XML files were produced, and 79 tasks executed; log
SHA-256
`fddde07b954c36898252e510ad07758408f2c9b4e35b9102a5e71d7dd518d535`.
The targeted affected-module static command passed (log SHA-256
`8f29ec9da3ff6a96d0044423e8ac1f415b2651ce0870fab9b2b86d6f5f007c33`).
The classification is deliberately non-zero: the full targeted log contains no
finding owned by `JavaRefactoringPreviewCommand`,
`JavaRefactoringPreviewDispatcher`,
`ExistingJavaRefactoringPreviewPlannerInvoker`, daemon `refactorPreview`, MCP
`toolPreviewRefactoring`, or the five changed generic decoder helpers. Exactly
48 reviewed/non-blocking slice-local glue findings remain: Java-module command
glue 24 (15 NP, 6 RCN, 1 Dm, 2 BC) and CLI real-surface glue 24 (13 NP, 7 RCN,
4 BC). Dispatcher-method references are generated Kotlin nullability analysis at
glue call sites, not production-method findings. Wider configured SpotBugs/PMD
baselines remain non-blocking; aggregate static cleanliness and numerical
coverage are not claimed. This dated mutable source-built aggregate complements
the focused source-built/in-process receipt and does not qualify packaged,
native, cross-platform, concurrent, crash-restart, general-orchestration, or
numerical-coverage behavior.

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

Managed apply uses an exact retained `planId`. A lexical-review `operationId` is
audit correlation only and can never reach this managed result:


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

The daemon now delegates only shared rollback-entry mechanics to core
`ManagedRollbackExecutor`: exact raw-ID parsing, one pre-lock journal lookup using
`JOURNAL_RECORD` visibility, an allow preflight, and one public
`PatchEngine.rollback` call. The resolved pre-lock record remains available for
the inverse `changedFiles` projection. Response/error rendering, diagnostics,
workspace/index refresh, pending-plan clearing, and lexical-audit lifecycle remain
daemon-owned, so this extraction adds no method, request, response, error-code,
or authority change. CLI, managed LSP, and MCP deliberately use the executor's
narrower `APPLIED_ONLY` visibility.

For managed apply, daemon and MCP also share the stateless
`ManagedApplyDiagnosticsGateSelector`. The daemon supplies its current Java and
Kotlin adapters and keeps external semantic-adapter lookup in its existing
resolver. Selection returns the existing lazy gate without running diagnostics;
`PatchEngine` still invokes it under lock and owns regression refusal, WAL,
post-image verification, and automatic rollback. Pending-plan/lease validation,
authorization, response rendering, post-success bounded diagnostics, index and
adapter lifecycle remain daemon-owned. This adds no method, request, response,
error-code, capability, or authority change. CLI, managed LSP, recipes, and
testkit retain their existing different gate-selection paths.

Daemon project open, saved-workspace refresh, managed-apply baseline/post-image,
and rollback refresh now obtain their mixed snapshots through the shared core
`WorkspaceSnapshotComposer`; MCP uses the same composer at its existing scan and
mutation-refresh points. The daemon still supplies its configured
`JavaProjectScanner`, exact TS/TSX/JS/JSX `GenericProjectScanner`, conditional
TypeScript build-model attachment, and current optional Kotlin/JVM attachment on
every invocation. An empty script inventory remains exact Java-snapshot
pass-through before optional Kotlin attachment.

The composer changes no JSON-RPC method, request, response, error, capability, or
write authority. Saved-workspace watcher timing, refresh refusal/rendering,
`WorkspaceIndex` reconciliation, pending plans, lexical audit, semantic leases,
post-apply scan-before-adapter-cleanup, and stored snapshot replacement remain in
`DaemonSession`. It performs no write, lock, WAL, transaction, apply, recovery, or
rollback action. CLI and LSP are outside this extraction.

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

## C compiler declaration search

After `project.open`, `symbol.search` accepts `languageId=c`, an explicit
`expectedSnapshotHash` from that open response, and configured `clang`, `clangd`
and `clangFormat` executable paths. The qualified examples use Clang tooling
22.1.8 and complete captured C11/C17 translation-unit contexts.

```json
{"jsonrpc":"2.0","id":31,"method":"symbol.search","params":{"languageId":"c","query":"value","expectedSnapshotHash":"<project.open snapshotHash>","clang":"/qualified/usr/bin/clang-22","clangd":"/qualified/usr/bin/clangd","clangFormat":"/qualified/usr/bin/clang-format","limit":100}}
```

The existing result array contains the usual id/name/kind/file/line and a
zero-based character, plus `cKind`, `lookupSpace`, `linkage`, `translationUnit`,
`providerId`, `evidence=compiler`, `completeness=declarations`, `snapshotHash`,
`compilationContextHash`, `queryTotal` and `queryTruncated`. Lines remain
one-based on this legacy route. Typedefs/enumerators belong to ordinary space;
tags, members and labels remain separate. Header observations remain per TU.
Search limits are 1..200 (default 200), query length at most 512 characters.
Source/configuration changes and unavailable compiler input are errors rather
than successful empty searches. The separate C navigation routes below reuse
this acquisition. C routing through `intelligence.query` remains unavailable.

Authority and qualified boundaries: [ARC42](arc42/appendix-requirements.adoc#c04-compiler-search-main-qualification-2026-11-13).
This protocol section owns caller syntax; ARC42 owns integrated acceptance.


## C compiler definitions and references

After the C search above, pass one returned opaque `c-decl:<64 hex>` ID to
`symbol.definition` or `symbol.references`, with `languageId=c`, the same
`expectedSnapshotHash` and explicit `clang`, `clangd`, `clangFormat` paths.
Names and source positions are not substitutes for this ID. Reopen/rescan and
search again after project changes.

```json
{"jsonrpc":"2.0","id":32,"method":"symbol.definition","params":{"languageId":"c","symbol":"<c-decl ID from symbol.search>","expectedSnapshotHash":"<same project.open snapshotHash>","clang":"/qualified/usr/bin/clang-22","clangd":"/qualified/usr/bin/clangd","clangFormat":"/qualified/usr/bin/clang-format"}}
```

`symbol.definition` returns one object whose `id` identifies the actual target
declaration; `requestedSymbolId` retains the caller's ID. `symbol.references`
returns an array of spelled reference locations. It accepts `includeDeclaration`
(boolean, default false) and `limit` (integer 1..200, default 200). Each returned
reference has `queryTotal`/`queryTruncated`; a known unused binding can have no
references. Declaration sites and use sites remain distinct by default.

Locations use workspace-relative `file`, one-based `line`/`endLine` and
zero-based `character`/`endCharacter`, with an exclusive end. Results retain
`cKind`, `lookupSpace`, `linkage`, selected/target `translationUnit`, actual
observed `translationUnits`, snapshot/context hashes, `compilerVersion` and
`compilerSha256`. Provider is `clang.c-navigation.v1`, evidence is `compiler`
and completeness is `captured-family`; it does not imply all configurations.

An absent valid ID is `c.navigation.symbolNotFound` (`SYMBOL_NOT_FOUND`).
Stale expected snapshots, changed captured inputs and unsupported/ambiguous
families are errors (`c.navigation.snapshotStale`, `.snapshotChanged`,
`.familyUnavailable` or `.definitionAmbiguous`), never empty success. The
qualified Story covers a function, typedef, named struct field and source
parameter in captured standalone C11/C17 contexts, database drift and an unknown
ID. Other contexts/families require separate qualification.

MCP `symbol_definition` and `symbol_references` accept the same arguments after
`project_scan`/`symbol_search`; successful JSON is returned in their legacy text
content. Tool failures use the existing `isError` response. Qualification here
is in-process session dispatch, with framed stdio and packaged execution open.
Authority: [ARC42 checkpoint](arc42/appendix-requirements.adoc#c04-navigation-main-qualification-2026-11-13).
This section owns caller syntax; ARC42 owns integrated acceptance.


## Owned source capture during project startup

`project.open` validates ownership of the root and its ancestors before
inspecting the transaction journal, without reading source contents. Its
generic C/header/TypeScript/JavaScript inventory now uses the same core source
reader as the C structural scanner. The reader uses descriptor-relative,
NOFOLLOW reads and strict UTF-8. Its default caps are 25,000 entries, depth 64,
4,096 sources, 2 MiB/source and 64 MiB total source bytes; API startup uses those
defaults. A failed inventory returns no partial source snapshot.

An owned-source refusal returns PLAN_REFUSED, with
`data.refusalCode=workspace.sourceCapture.unavailable` and
`data.sourceCaptureFailure`, such as SYMBOLIC_LINK, INVALID_UTF8 or
FILE_BYTES_EXCEEDED. MCP `project_scan` returns the existing `isError=true`
envelope with `workspace.sourceCapture.unavailable: <cause>` in its text.
No compiler/SDK is required for these structural ownership checks. On a failed
fresh open, no project summary or transaction is created. A successful open may
retain normal index/session metadata.

Qualified examples include mixed C/header/TypeScript sources on both surfaces,
an external source link, a symbolic root, invalid UTF-8 and a source over the
default size limit. The profile requires SecureDirectoryStream and stable file
identities; other filesystem providers are unavailable rather than silently
falling back to path-following reads.

After journal inspection, daemon and MCP acquire the complete owned generic
inventory before invoking the authoritative Java scanner. Existing Java/Kotlin
source links therefore refuse before a path-based JVM read is attempted. The
old composer entry point keeps its original contract for other library callers.
Root paths are limited to 256 normalized absolute components, independently of
workspace-relative depth 64; overflow reports DEPTH_EXCEEDED. Native evidence
accepts exactly 256 and refuses 257. The Java base scanner now captures JVM
source text through the same owned reader, restricted to its modeled source
roots with shared limits. A file/directory changed to a link after the generic
inventory therefore still refuses. Generated roots selected by the model are
included even below generic ignored directories. Missing modeled directories
remain optional; roots outside the workspace report OWNERSHIP_UNAVAILABLE.
Model discovery and auxiliary metadata reads retain separate policies; broader
concurrent metadata and configuration/provider closure remain unqualified.
See [JVM source acquisition](arc42/appendix-requirements.adoc#c03-jvm-source-main-qualification-2026-11-13).
See [the later checkpoint](arc42/appendix-requirements.adoc#c03-inventory-order-main-qualification-2026-11-13).
Authority: [ARC42 checkpoint](arc42/appendix-requirements.adoc#c03-owned-generic-main-qualification-2026-11-13).
This section owns caller errors; ARC42 owns integrated qualification.

Compilation database provider/snapshot IO now also uses descriptor-relative,
strict UTF-8 acquisition with its original 8 MiB limit. Root CMake input retains
512 KiB and refuses ancestor links. Unavailable input cannot become new captured
build authority; this does not change the caller error contract above or qualify
all auxiliary IO. See [input acquisition evidence](arc42/appendix-requirements.adoc#c03-owned-aux-main-qualification-2026-11-13).


## Explicit C SDK inputs for managed previews

For the bounded SDK-type workspace-header field profile, daemon `c.preview`
accepts optional top-level `readOnlySdks`. Each object has exactly three string
fields: absolute normalized external `root`, caller-declared `provenance`, and
caller-declared `license`. The array has at most eight entries; omit it for
legacy empty SDK scope. Existing discovery policy validates root ownership,
metadata and the bounded closed header tree. SDKs remain readonly inputs.

```json
{"jsonrpc":"2.0","id":41,"method":"c.preview","params":{"operation":"renameSymbol","clang":"/qualified/usr/bin/clang-22","clangd":"/qualified/usr/bin/clangd","clangFormat":"/qualified/usr/bin/clang-format","readOnlySdks":[{"root":"/opt/example-sdk","provenance":"caller:example-sdk-build","license":"LicenseRef-ProjectSdk"}],"arguments":{"symbol":"raw_count","newName":"scaled_count","file":"include/api.h","line":2,"character":14}}}
```

MCP `preview_refactoring` accepts the same field alongside `languageId: "c"`,
tool paths, operation and operation-specific `arguments`. Its tools/list schema
advertises the bounded array and nested required strings. A malformed SDK value
is not discarded: daemon returns INVALID_PARAMS (-32602), with
`error.data.refusalCode = "c.sdkConfigurationInvalid"` and a `field` location;
MCP returns `isError: true` with compatible text and `structuredContent.error`
containing the typed code/message/data; [MCP caller contract](integration-contracts.md#mcp-typed-sdk-tool-errors).
Root/metadata/tree admission failures retain existing discovery refusals.
Provenance/license declarations do not independently verify licensing.

Apply uses the retained plan and SDK-bound diagnostics gate. An SDK change after
preview refuses without WAL even when the workspace snapshot is unchanged;
resending a new SDK declaration at Apply does not refresh the plan's authority.
Rollback uses the existing transaction ID. Qualified C11/C17 daemon/MCP cases
cover exact field/designator/member images and preserve unrelated SDK fields,
workspace fields, comments and strings. Six cases/24 steps, fresh C716, daemon64,
MCP32 and 19 prior native controls/76 steps pass.

This section owns bounded caller syntax;
[ARC42 owns integrated acceptance](arc42/appendix-requirements.adoc#c03-sdk-public-managed-2026-11-15).
Evidence: `.refactorkit/runs/c03-sdk-public-managed-r001/archive-audit.json`
and `public-sdk-matrix.json`. CLI SDK input now forwards the same field via
`--read-only-sdks '<json-array>'`; [CLI syntax and bounds](c-language.md#cli-sdk-json)
have separate native6/24 and19-control evidence. Managed LSP now accepts the same
SDK array in its preview command; [LSP caller syntax](integration-contracts.md#c-sdk-inputs-for-managed-lsp-commands)
has separate native6/24, LspSession23 and13-control evidence. Remaining malformed-JSON
boundaries, read/provider propagation, other managed SDK families and broader
platform SDK forms remain `actionable`; full original C02-C17/release stays open.
Private delivered runtimes retain older C code; no install/publication follows.


## C readonly SDK query locations

C `symbol.search`, `symbol.definition` and `symbol.references` accept optional
`readOnlySdks` using the existing [SDK array contract](#explicit-c-sdk-inputs-for-managed-previews).
Pass the same SDK declarations, tool paths and expected workspace snapshot to
search and navigation. MCP equivalents advertise this array in tools/list;
successful payloads remain JSON in the existing text content.

SDK contexts add `sdkInputsIdentity` to results and `uri`, `sourceOwnership`
(`WORKSPACE`/`SDK`) and `readOnly` to locations. Workspace `file` remains relative;
SDK `file` is its real absolute header path and `uri` is the real file URI. SDK
locations include `sdk` with `root`, `identity`, `provenance`, `license`; the last
two fields are caller declarations. SDK bytes are readonly and never patch
ownership. Legacy one-based lines/zero-based characters/exclusive ends remain.

SDK IDs bind captured SDK identity. After an SDK change, a prior SDK declaration
ID returns `c.navigation.symbolNotFound`; search again for current IDs. Missing
SDK input refuses rather than returning no references. A workspace snapshot hash
alone does not cover external SDK changes. Without SDK configuration the legacy
workspace-only JSON shape remains unchanged.

Six native daemon/MCP/CLI C11/C17 cases pass 24 steps: typedef/field locations,
SDK identity drift and missing inputs. Fresh C726/MCP32 and 19 old native cases
pass. [ARC42 owns bounded acceptance](arc42/appendix-requirements.adoc#c04-sdk-queries-2026-11-15).
Evidence: `.refactorkit/runs/c04-sdk-queries-r001/archive-audit.json` and matrix.
Broader SDK families/configurations, direct SDK document cursors and platform
SDK layouts remain `actionable`; full original C02-C17/release remain open.
[Standard LSP workspace-to-SDK query contract](integration-contracts.md#c-workspace-cursor-navigation-to-sdk-locations).


### C managed preview provider

A successful `c.preview` response identifies its language adapter with
`provider.name = "refactorkit-c"`. The `provider.version` is the RefactorKit
build version. The CLI projects the same preview response for `refactorkit c`
operations. The [CMake surface Story](../features/c-cmake-single-tu-managed-surface.feature)
covers the daemon method and the CLI.

The daemon lists `c.preview` in `server.capabilities` as experimental, with
`requiresProject: true` and `writesWorkspace: false`. Open a project before
requesting a preview. Applying a retained plan uses `refactor.apply`.
