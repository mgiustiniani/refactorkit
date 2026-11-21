# Integration contracts

See AGENTS.md for the authoritative initial architecture and implementation rules.

Authority boundary: this protocol satellite records existing surfaces and
explicitly proposed acceptance. The [ARC42 requirements appendix](arc42/appendix-requirements.adoc)
owns integrated status; a proposed contract does not advertise a new API method.

Status: implementation-informed baseline for `v0.3.0`. The release retains the
API `0.2` beta contract and is not a
`v1.0.0` API freeze. Compatibility labels, API-version expectations, and
breaking-change migration-note rules are defined in
[Compatibility and deprecation policy](compatibility-policy.md).

Historical `v0.3.0` baseline metadata (not the current implementation version):

- implementation name: `RefactorKit`;
- implementation version: `0.3.0`;
- beta contract API version: `0.2`.

## Compatibility policy

This document lists the current integration surfaces and their stability labels.
See [Compatibility and deprecation policy](compatibility-policy.md) for the
normative meaning of `beta-contract`, `experimental`, and `internal`, the
implementation-version vs API-version distinction, and deprecation rules.

## JSON-RPC response envelopes

The shared response codec always writes `jsonrpc` and `id`, including `id: null`
when correlation is unknown. A success contains `result` and no `error`; a
failure contains an error object and no `result`, as required by the
[JSON-RPC response specification](https://www.jsonrpc.org/specification#response_object).
Successful `result: null`, false, zero and empty strings remain explicit even
when callers disable default encoding or nullable-field encoding. Result and
typed error-data payloads retain their original JSON values.

Decoding still accepts legacy nullable envelopes. Encoding rejects a response
with both a meaningful non-null result and an error instead of choosing an
outcome. Native MCP parse and unexpected internal failures use generic messages;
typed application failures retain their structured code, message and data.
This correction does not qualify batches or change request-ID semantics.
The bounded executable authority is
`REQ-C-RELEASE-EVIDENCE-001#release-json-rpc-producers-publish-one-outcome-and-required-null`;
see the [ARC42 requirements appendix](arc42/appendix-requirements.adoc).

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
| `refactorkit typescript <search|definition|references|diagnostics|diagnostics-v2|rename>` | `experimental` | One-shot explicit-toolchain semantic session; startup builds a bounded attested central-index declaration partition, `diagnostics-v2` emits the IDE-grade saved-disk authority envelope, legacy `diagnostics` remains compatible, preview is default, and managed apply requires `--apply`. |
| `refactorkit kotlin diagnostics <root> --jdk-home ... --compiler-jar ... [--compiler-classpath ...]` | `experimental` | One-shot explicit JDK 21/Kotlin 2.0.21 K2 diagnostics with exact snapshot/lease, build/toolchain hashes, line-only/no-location precision and process attestation; no mutation authority. |
| `refactorkit kotlin symbols <root> ...` / `kotlin definition` / `kotlin references` / `kotlin rename` / `kotlin organize-imports` | `experimental` | Saved-snapshot compiler declarations/usages plus bounded K4 private-declaration managed rename. In mixed snapshots, `--accept-external-consumer-risk` admits bounded public top-level type preview in either direction: ephemeral K2 classes feed exact JDT Java bindings, while bounded annotation-processing-free ECJ classes feed exact K2 external-type usages. Direction-specific staged compiler evidence must remain clean. Bounded public non-overloaded direct Kotlin functions and public non-overloaded,
non-override Java methods may update compiler-bound callers in the other language;
unsupported callable shapes refuse. Additive `moveDeclaration` accepts only the
bounded public top-level Kotlin/JVM type row plus co-located compiler-proven
private helpers and public sibling types; any public sibling may lead while the
original filename is preserved. Consumers use exact explicit/FIR-aliased imports
or exact Kotlin/Java package-star/same-package/fully-qualified consumers. It
updates exact imports while preserving K2-proven Kotlin aliases, inserts a destination import for
compiler-proven same-package or exact Kotlin/Java package-star consumers, or
rewrites exact
fully-qualified K2/JDT-bound target identities. Kotlin and Java consumer sets are
independently optional, and a K2-proven public type with zero in-workspace
consumers may move without fabricated usage evidence. The operation requires `targetPackage` plus external-consumer-risk acceptance, and preserves the same
lease/snapshot/index authority and one-transaction apply boundary. Bounded `organizeImports` removes only K2-proven unused explicit type imports,
sorts the retained uncommented contiguous block, preserves CRLF and shares the
same lease/snapshot/index and transaction boundaries. Incomplete shapes still
refuse without fallback. |
| `refactorkit scan <path>` | `beta-contract` | Read-only project scan summary. |
| `refactorkit index [path] [--json]` | `additive-api-0.2` | Builds the session-scoped whole-workspace source/declaration index and prints human or typed JSON status without workspace writes. |
| `refactorkit intelligence search [path] ... [--json]` | `experimental` | Bounded workspace/document symbol search over the central index. Completion, hover and signature-help kinds return typed refusal until provider-qualified. |
| `refactorkit symbols <path>` / `refactorkit java symbols <path>` | `beta-contract` | Symbol listing shape should remain scriptable. |
| `refactorkit diagnostics <path>` / `refactorkit java diagnostics <path>` | `beta-contract` | Diagnostic severity/file/line fields are contract fields. |
| `refactorkit definition --symbol <fqcn> [path]` / `java definition` | `beta-contract` | Read-only lookup. |
| `refactorkit references --symbol <fqcn> [path]` / `java references` | `beta-contract` | Read-only reference listing; exact signed member selectors may use JDT binding evidence when analysis is clean, otherwise lexical fallback remains. |
| `refactorkit rename --symbol <fqcn> --to <name> [--apply] [path]` | `beta-contract` | Java class rename uses exact JDT type/constructor/reference ranges when analysis is clean, explicit lexical fallback otherwise, and target-conflict refusal; preview/apply/rollback semantics remain mandatory. |
| `refactorkit rename-member --symbol <FQN#member> --to <name> [--apply] [path]` | `beta-contract` | Signed method selectors use JDT exact-overload and scanned-source override-family propagation; external family members, constructors, or ambiguous evidence are refused. Unsigned member rename remains lexical. |
| `refactorkit move-class --symbol <fqcn> --to-package <pkg> [--apply] [path]` | `beta-contract` | Clean JDT evidence scopes eligible package/import/FQN edits to binding-matched files. Bounded Maven defects return non-managed guidance; broad lexical uncertainty returns schema-v1 edit-free `LEXICAL_FALLBACK_REVIEW`. Both refuse `--apply` before managed-write entry. Invalid packages and existing targets are refused; framework/string warnings remain. |
| `refactorkit java move-source-root --from <root> --to <root> [--root <path>] [--apply]` | `beta-contract` | Whole-root rename-only transaction preserving bytes, packages, and FQCNs; typed `sourceRoot.*` refusal codes and post-image Maven/JDT diagnostics apply. |
| `refactorkit java move-across-maven-modules ... [--apply]` | `experimental` | Complete-root ownership migration between existing effective Maven modules. Requires exact `--dependency-pom`, source/destination group/artifact/version intent; Java and lexical POM edits share one staged-reactor diagnostics gate, WAL transaction and rollback. |
| `refactorkit organize-imports <file...> [--apply] [--root <path>]` | `beta-contract` | Sort/deduplicate/remove same-package imports; clean JDT evidence also removes binding-proven unused exact imports, while wildcard/unresolved imports and unclean files remain conservative. Absolute and normalized workspace-relative selections target the same snapshot file; duplicates are collapsed. Missing/outside selections refuse the whole request with `java.sourceFileNotFound` / `java.pathOutsideWorkspace`. |
| `refactorkit format-file <file> [--apply] [--root <path>]` | `beta-contract` | Formats one non-generated, syntactically valid Java compilation unit through Eclipse JDT using hash-bound project preferences or deterministic defaults; preview/apply/rollback remains mandatory. |
| `refactorkit safe-delete --symbol <fqcn> [--force] [--apply] [path]` | `beta-contract` | Refuses referenced symbols by default; forced behavior requires explicit risk note. |
| `refactorkit c safe-delete <root> --symbol <name> --preview` | `experimental` | C safe delete reports a compiler-proven preview for one isolated private function or refuses when semantic references are present; full consumer/configuration closure and managed apply are not qualified. |
| `refactorkit patch recover [--root <path>]` | `experimental` | Explicitly authorized recovery acquires the writer lock, cleans orphan transaction temps and compensates incomplete journal states. Read-only project/session startup only inspects and refuses pending recovery; it never creates `.refactorkit` or `workspace.lock`. |
| `refactorkit patch rollback <transaction-id> [--force] --root <path>` | `beta-contract` | Normal rollback validates exact post-images and refuses conflicts; `--force` is an explicit destructive pre-image restore. |
| `refactorkit test-golden [case] [--golden-dir <path>]` | `beta-contract` | CI/test harness command for documented golden fixtures. |
| `refactorkit extract-method ...` | `experimental` | Command contract remains experimental; binding-backed contiguous-statement plans with one live-out are managed and rollbackable on daemon/MCP, while lexical complete-line previews remain review-only. |
| `refactorkit change-signature ...` | `experimental` | Limited rename/add/reorder/remove parameter support; conservative refusal expected. |
| `refactorkit java import-class ...` | `experimental` | External code assimilation; provenance, conflict, and license-policy behavior may change before `v1.0.0`. |
| `refactorkit recipe run ...` | `experimental` | Recipe schema is not yet a beta contract. |
| `refactorkit outline`, `search`, `local-rename` | `experimental` | Multi-language structural features, not semantic Java refactoring contracts. |
| Any unlisted/debug subcommand | `internal` | Not supported for external automation. |

### C13 safe-delete preview boundary

`CSafeDeletePlanner` has one bounded C preview acceptance for a compiler-proven,
isolated private function whose complete semantic reference result contains only
its declaration. The preview removes exactly that declaration range and leaves
the caller/entry point untouched; it creates no workspace transaction. This
planner-level acceptance is complemented by a compiler-backed refusal when a
private function's address is stored for an indirect callback. These
planner-level cases do not prove the complete external-consumer set or qualify
managed Apply/rollback.
A third bounded Story refuses a source-visible public function before relying on
clangd or an external-consumer index: when the native semantic runtime is
unavailable, the plan remains `REFUSED`, has no edits or affected files, and
leaves source bytes and transaction state unchanged. This qualifies a safe
refusal only; it does not prove that no external consumers exist. A same-line
private function/variable boundary also refuses before semantic definition or
reference queries whenever the declaration cannot be isolated from its neighbor;
the caller may already have started clangd. The isolated-range path still
requires binding and complete-reference evidence before it can preview.
A controlled protocol Story also refuses an LSP reference error, malformed
reference payload, and over-limit 257-location result after confirming that the
reference request was actually sent. Its simulated runtime is test-only and is
not native clangd qualification. A separate five-case source guard refuses safe
delete when the target translation unit contains `#if`/`#ifdef` branches using
the standard `#`, `%:`, or C11/C17 `??=` spellings, including a trigraph-created
backslash-newline. One active configuration cannot establish references in
alternate branches. This guard runs before semantic definition/reference
queries; whole-project configuration and include-graph closure remain open.

An exact C CLI Story now exercises the existing `c safe-delete ... --preview`
route. An isolated unused private function returns a JSON `PREVIEW` with
`COMPILER_PROVEN` evidence and the declaration diff; a referenced private
function exits with code 1 and a text refusal on stderr. Both cases preserve
source and compilation-database bytes and create no transaction journal. This
qualifies only the CLI preview/refusal boundary; it does not qualify daemon,
MCP, LSP, managed Apply/rollback, or complete consumer/configuration closure.

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
nullable limit fields remain explicit. Each capability also carries its effective
`backend`, `runtime`, and extension subset, so a layered adapter cannot falsely
apply an in-process structural claim to an external semantic operation (or grant
`.tsx`/`.jsx` native-AST evidence from the `.ts`/`.js` grammar layer). Diagnostics
additively expose `diagnosticSnapshotModes` and `diagnosticRangeCapability`.
TypeScript/JavaScript diagnostics identify `typescript-compiler-exact-v1`; they
do not inherit LSP backend or runtime limits.

The packaged `0.7.0-SNAPSHOT` kernel includes the `kotlin` descriptor for `.kt`
and `.kts`. Its default backend remains `kotlin-analysis-unavailable-v1`, while
`diagnostics` overrides it with `kotlin-compiler-diagnostics-k2-v1`; bounded
`workspaceSymbols`, `documentSymbols`, and ID-based `definition` use
`kotlin-compiler-jvm-declarations-k2-v1`. These rows have `EXPERIMENTAL` stability,
`COMPILER` evidence, external bounded runtime and no mutation authority. Other
operations and Kotlin script semantics remain `REFUSED/NONE/NONE`.
`kotlin-compiler-explicit-v1` hash-binds explicit JDK/compiler inputs without
discovery-time execution; `kotlin-jvm-projection-v1` binds those hashes to bounded
JVM source sets. The worker revalidates both and launches one isolated K2 process
over an immutable overlay. Its first symbol catalogue accepts only successfully
compiled classes, named/companion objects, interfaces, enum classes and annotation
classes with matching JVM class-file and exact compiler PSI evidence. A bounded
function row additionally requires exactly one generated non-synthetic JVM method
for the source name and derives an opaque callable ID from owner/name/descriptor.
It exposes no references or write authority.

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
| `project.open` | `beta-contract` | Read-only workspace lifecycle; requires `root`. It inspects journal state without creating `.refactorkit`/`workspace.lock`; pending or corrupt recovery state refuses and directs the caller to explicit `patch.recover`. |
| `project.summary` | `beta-contract` | Read-only project metadata plus typed, deterministically ordered, bounded and truncation-signaled Build Model SPI summary (`providerId`, canonical status, policy outcomes, selected profiles, typed diagnostic codes, modules/source sets/outputs/edges); module paths are workspace-relative, while external classpath paths and diagnostic messages are omitted. Shape is guarded by `build-model-summary-schema-keys.json`. |
| `index.status` | `additive-api-0.2` | Reports snapshot/generation, whole-workspace source/symbol counts, languages, provider evidence/completeness and explicit truncation without source text or toolchain paths. |
| `workspace.watch.status` | `experimental` | Reports bounded recursive saved-file watcher state/counters without source content or workspace writes. |
| `workspace.refresh` | `experimental` | Forces saved-snapshot reconciliation, provider-partition invalidation and semantic-lease invalidation; returns bounded changed-path evidence using `api-0.2-workspace-refresh-schema.json`. |
| `intelligence.query` | `experimental` | Request/snapshot-correlated, bounded workspace/document symbol variants are ready. TypeScript/JavaScript document symbols, completion, hover and signature help accept exact lease-bound immutable editor overlays, reject stale versions and restore saved provider documents. Saved-snapshot Java definition/references/hover-at-position are backed by a bounded snapshot-keyed JDT binding cache; references expose declaration inclusion and explicit truncation, while hover returns binding-derived signatures and bounded Javadoc. Saved-snapshot Kotlin definition and explicitly partial references accept K2 FIR-proven direct function-call and source-type usage names under the active Kotlin lease. Other position-navigation variants remain refused. Barrier-aware priorities and `intelligence.cancel` provide cooperative interactive scheduling without crossing stateful operations; schema: `api-0.2-intelligence-query-schema.json`. |
| `symbol.search` | `beta-contract` | Read-only symbol query; optional `query`. |
| `symbol.definition` | `beta-contract` | Read-only lookup; requires `symbol`. |
| `symbol.references` | `beta-contract` | Read-only reference query; requires `symbol`. |
| `diagnostics` | `beta-contract` | Legacy read-only bare diagnostic array; retained for API `0.2` compatibility. Additive `languageId=jvm` deterministically aggregates language-tagged Java JDT and Kotlin K2 rows while retaining concise provider-input roots. It is not sufficient for exact IDE range/snapshot authority. |
| `diagnostics.v2` | `additive-api-0.2` | IDE-grade TypeScript/JavaScript compiler diagnostics with request ID, semantic lease, shared core immutable-editor-overlay authority, snapshot hashes, compiler attestation, exact/partial location variants, structured readiness, and bounded response. See `docs/typescript-diagnostics-protocol.md`. |
| `typescript.semantic.start` / `typescript.semantic.restart` / `typescript.semantic.stop` | `experimental` | Starts, refreshes or removes the snapshot-bound TypeScript/JavaScript declaration partition. Projection reports structured status, path-free provenance and truncation; 256-file, 50,000-symbol and 30-second aggregate limits fail closed. |
| `kotlin.semantic.start` / `kotlin.semantic.stop` | `experimental` | Configure or clear explicit JDK 21 and Kotlin 2.0.21 compiler evidence, attach or remove the Kotlin/JVM projection, and rotate the semantic lease. Startup runs without launching a compiler. Projection refusals return JSON-RPC `-32602` with the provider's diagnostic codes in `error.data.diagnosticCodes`, including `kotlin.scriptSemanticsUnsupported` for unqualified script inputs. |
| `kotlin.diagnostics` | `experimental` | Requires exact startup lease and snapshot; returns structured compiler diagnostics, explicit location precision, toolchain/build hashes, bounded runtime and process attestation. |
| `kotlin.symbols` | `experimental` | Requires exact startup lease and snapshot; returns a structured ready/refused/error envelope, opaque JVM type/callable IDs, exact zero-based UTF-16 declaration selections, toolchain/build hashes, process attestation and explicit truncation. |
| `kotlin.definition` | `experimental` | Requires the same authority plus an opaque ID returned by `kotlin.symbols`; resolves only against a newly attested saved snapshot and returns `kotlin.symbolNotFound` rather than guessing. |
| `refactor.preview` | `beta-contract` | Patch-plan preview/refusal behavior for admitted operations. Java `moveClass` additionally returns plan-free `REVIEW_ONLY_GUIDANCE` for bounded enumerable authority defects, schema-v1 `LEXICAL_FALLBACK_REVIEW` for broad lexical uncertainty, or typed structural refusal for missing active reactor authority. |
| `refactor.apply` | `beta-contract` | Requires exactly one non-blank `planId` or non-capability `operationId`. A `planId` applies the exact retained managed plan. A still-retained lexical-review `operationId` always returns typed `evidence.insufficient`; unknown/evicted identities are invalid. No operation ID can authorize a write. |
| `refactor.discard` | `beta-contract` | Idempotently removes a pending source-bearing plan without workspace writes; returns `discarded=false` when absent. |
| `patch.recover` | `experimental` | Explicit mutating recovery for a supplied workspace root. It is the only startup-oriented surface allowed to create the writer lock or compensate incomplete journals; ordinary project open/scan/LSP initialize perform read-only inspection and refuse when recovery is pending. |
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

### Session pending-plan retention

Daemon, managed LSP, and MCP sessions each own a separate language-neutral
`PendingPlanStore` with a maximum of 128 exact opaque payloads. Successful lookup
refreshes access recency; insertion beyond the bound evicts the untouched least-
recently accessed entry. Store insertion, lookup, eviction, removal, and clear are
in-memory retention mechanics only: they do not authorize apply, write files,
acquire the workspace lock, create WAL/transaction state, or provide rollback.
An absent, discarded, cleared, or evicted plan ID requires a new preview.

Admission and lifecycle remain surface policy. Daemon retains its discard,
refusal-consumption, apply/rollback-wide-clear, project-open, and shutdown rules;
managed LSP retains its document-lifecycle invalidation and selected-ID apply
removal; MCP retains its scan/close invalidation, selected-ID success removal,
stale-Kotlin consumption, and importer admission behavior. The extraction changes
neither protocol responses nor the independent authorization and `PatchEngine`
gates. Bounded source-built/in-process evidence is provided by
`REQ-PENDING-PLAN-STORE-001..002`; packaged and concurrent-session qualification
remain separate.

### Managed rollback execution boundary

CLI, daemon, managed LSP, and MCP delegate transaction-ID parsing, one pre-lock
journal-record lookup, lookup visibility, an optional surface preflight, and one
`PatchEngine.rollback` call to the language-neutral `ManagedRollbackExecutor`.
CLI, LSP, and MCP select `APPLIED_ONLY`; daemon retains its broader
`JOURNAL_RECORD` visibility. Lookup failures, visible absence, preflight rejection,
engine refusal, and rollback-call journal failure remain distinct typed outcomes.
Every post-resolution outcome carries the exact resolved pre-lock journal record;
it is lookup evidence and is not reloaded or described as post-rollback state.

This shared executor does not select normal/force mode, render protocol or CLI
responses, refresh snapshots/indexes, run diagnostics, clear pending plans or
audit state, invent authorization, replace recovery/WAL, or alter the under-lock
rollback algorithm. In particular, LSP still runs its dirty/open-document guard
before engine entry and rethrows the same document-version rejection. Wire shapes,
error mappings, and surface completion behavior are unchanged. Bounded
source-built/in-process evidence is provided by
`REQ-MANAGED-ROLLBACK-EXECUTOR-001..002`; packaged, cross-platform, concurrent,
and crash/restart qualification remain separate.

### Managed-apply diagnostics-gate selection

Daemon `refactor.apply` and MCP `apply_refactoring` now delegate their identical
JVM diagnostics-gate routing expression to the stateless
`ManagedApplyDiagnosticsGateSelector` in `refactorkit-jvm`. The selector uses the
exact pending-plan language ID plus original plan operation, evidence, and
affected-file metadata. It preserves `java-maven-ownership`, `java-jdt`,
`kotlin-k2-java-jdt`, and `kotlin-k2` gate identities and the existing ordered
Kotlin mixed-JVM precedence. Every other exact language ID is resolved once by
the calling surface and the resulting external gate is returned unchanged.

Selection is lazy: no built-in or external diagnostics provider runs until
`PatchEngine.apply` evaluates the returned gate under its workspace lock. The
surface passes its current adapters on every apply, so semantic start/stop and
lease lifecycle remain outside the selector. Resolver exceptions propagate
unchanged. The selector does not validate pending plans or leases, scan a
workspace, run post-success diagnostics, render responses, close adapters, create
WAL state, mutate files, or alter automatic rollback and recovery.

This bounded extraction deliberately excludes CLI, managed LSP, recipes, testkit,
and direct-library apply because their existing gate-selection policies are not
the duplicated full-language daemon/MCP expression. Wire shapes, error mappings,
authorization strings, and `PatchEngine` authority are unchanged. Bounded
source-built/in-process evidence is provided by
`REQ-MANAGED-APPLY-DIAGNOSTICS-SELECTOR-001..002`; packaged, native,
cross-platform, concurrent, and broader apply qualification remain separate.

### Workspace snapshot composition

Daemon and MCP now delegate their identical mixed-workspace snapshot pipeline to
the stateless, language-neutral core `WorkspaceSnapshotComposer`. Each invocation
receives the surface's current primary scanner, secondary source-inventory
scanner, conditional evidence attacher, and optional final evidence attacher. An
empty secondary inventory preserves the exact authoritative base instance before
the optional final attachment. A non-empty inventory overlays only files, source
extensions, and ignored directories; normalized-path collisions retain the later
source, and surviving files are ordered by `Path.toString()`.

Concrete language policy remains outside core. Daemon and MCP still supply the
exact TS/TSX/JS/JSX mapping, attach TypeScript build evidence only for a non-empty
script inventory, and attach current Kotlin/JVM evidence last only when a current
toolchain exists. Scanner and attacher exceptions propagate unchanged. The
composer retains no collaborator or session state and owns no watcher, index,
pending-plan, semantic lifecycle, diagnostics, `PatchEngine`, lock, WAL,
transaction, rollback, or protocol behavior.

This extraction changes neither project-open/scan methods nor response shapes.
Saved-workspace refresh timing, post-apply scan-before-cleanup ordering, stored
snapshot replacement, index reconciliation, and invalidation remain surface
policy. CLI and LSP do not adopt the composer in this slice. Bounded
source-built/in-process evidence is provided by
`REQ-WORKSPACE-SNAPSHOT-COMPOSER-001..005`; daemon/MCP adoption is source-inspected
and regression-tested, not packaged, native, cross-platform, concurrent, or
numerical-coverage qualification.

### Java refactoring preview command dispatch

For exactly `renameClass`, `renameMember`, `moveSourceRoot`, single-file Java
`organizeImports`, and `safeDelete`, daemon `refactor.preview` and MCP
`preview_refactoring` now construct one complete sealed
`JavaRefactoringPreviewCommand` only after their existing parsing, aliases,
defaults, required-field checks, and unknown-operation handling. They invoke the
fieldless `JavaRefactoringPreviewDispatcher` with the current session snapshot
and Java adapter. The dispatcher uses an internal per-call invoker seam to call
one unchanged default planner and returns the exact `PatchPlan` or escaping
exception. The five generic `JavaLanguageAdapter.applyRefactoring` decoders
preserve their own missing/unknown/refusal behavior and converge on the same
canonical downstream mapping.

Surface policy remains separate: daemon and MCP keep distinct refused-plan
handling, pending-plan admission, rendering, and errors. `moveClass`, other
languages, mixed/experimental/Maven routes, CLI/LSP adoption, multi-file imports,
diagnostics, lifecycle, `PatchEngine`, apply/rollback, persistence, and
`ManagedApplyInvoker` are outside this boundary. No method, tool, request,
response, error code, external protocol schema, capability catalogue, stability
classification, or write authority changed.

The corrected split-runner evidence passed all four
`REQ-JAVA-PREVIEW-COMMAND-001..004` definitions/eight cases/94 steps on OpenJDK
21.0.11 (log SHA-256
`10c805b67306026cb4ab4a79de9b7258c712e28aaf0bb0070279edbbc44914f5`).
REQ-003 uses real public `DaemonSession`/`McpSession` dispatch for all five
operations plus missing, unknown, and refused paths and verifies no preview
writes/residue. Independent Java/daemon/MCP regressions passed with zero
failures/errors (log SHA-256
`9a1cb587e7e377682a09353f470140a76e4dae1e5cb42a727f8ed346e2a0e9b1`).
Focused qualification remains source-built/in-process. Final dated mutable
whole-working-tree source-built verification on 2026-08-01 used OpenJDK 21.0.11
and `./gradlew --no-daemon --rerun-tasks check goldenTest`: `BUILD SUCCESSFUL`
in 6m 54s; 1045 tests were discovered, 817 executed, 228 skipped/tag-filtered,
zero failures/errors, 140 XML files were produced, and 79 tasks executed; log
SHA-256
`fddde07b954c36898252e510ad07758408f2c9b4e35b9102a5e71d7dd518d535`.
The targeted affected-module static command passed (log SHA-256
`8f29ec9da3ff6a96d0044423e8ac1f415b2651ce0870fab9b2b86d6f5f007c33`). Its
classification is deliberately non-zero: the full targeted log contains no
finding owned by `JavaRefactoringPreviewCommand`,
`JavaRefactoringPreviewDispatcher`,
`ExistingJavaRefactoringPreviewPlannerInvoker`, daemon `refactorPreview`, MCP
`toolPreviewRefactoring`, or the five changed generic decoder helpers. Exactly
48 reviewed/non-blocking slice-local glue findings remain: Java-module command
glue 24 (15 NP, 6 RCN, 1 Dm, 2 BC) and CLI real-surface glue 24 (13 NP, 7 RCN,
4 BC). Dispatcher-method references are generated Kotlin nullability analysis at
glue call sites, not production-method findings. Wider configured SpotBugs/PMD
baselines remain non-blocking; neither aggregate static cleanliness nor numerical
coverage is claimed. This dated aggregate does not qualify packaged, native,
cross-platform, concurrent, crash-restart, general-orchestration, or
numerical-coverage behavior.

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
| `moveClass` | `beta-contract` | `symbol`, `arguments.targetPackage`; may return a managed plan, REQ-003/008 non-managed guidance, REQ-004 `LEXICAL_FALLBACK_REVIEW`, or typed structural refusal |
| `moveSourceRoot` | `beta-contract` | `arguments.from`, `arguments.to`; workspace-relative `/` paths. A consumer-reported real-Gradle native IDE run passed preview/Apply with unchanged bytes at the destination, then failed rollback and timed out; see the existing source-root tracking. Cause unknown; this does not qualify general rollback behavior. |
| `java.moveAcrossMavenModules` | `experimental` | `arguments.from`, `to`, optional `dependencyPom`, exact `sourceGroupId`, `sourceArtifactId`, `sourceVersion`, exact `destinationGroupId`, `destinationArtifactId`, `destinationVersion`; optional literal type/classifier and `allIdenticalOccurrences` |
| `organizeImports` | `beta-contract` | `arguments.file` or `symbol` as file path |
| `formatFile` | `beta-contract` | `arguments.file`; whole-file Java formatting only in API `0.2` |
| `safeDelete` | `beta-contract` | `symbol`, optional `arguments.force` |
| `extractMethod` | `stable` / `managed-stable` | A contiguous range of complete direct statements with resolved enclosing-parameter and read-only preceding direct-local inputs, exactly one local live-out, checked-exception propagation, staged JDT validation, diagnostics, Apply and rollback. The current Story covers a conditional branch and refuses writes to preceding locals. Lexical compatibility plans carry `LEXICAL_FALLBACK` and cannot be managed-applied. Arguments: `file`, `startLine`, `endLine`, `methodName`. |
| `changeSignature.renameParameter` | `stable` / `managed-stable` | `symbol`, `oldName`, `newName`; qualified through daemon and MCP under the exact canonical ID. Standard LSP `textDocument/rename` is a separate editor operation and is not evidence for this engine authority. |
| `changeSignature.changeParameterType` / `changeParameterType` | `stable` / `managed-stable` | `symbol`, `name` or `parameterName`, `type` or `newType`; source Story covers private method, saved-cursor `int`→`Integer`, risk-accepted source override-family, and private constructor. Package r003 advertises stable/managed-stable; native Linux GUI run `run-Fx6jDM` passes saved-cursor preview/Apply/explicit rollback on that package with source preserved, matching context/selection attestations, Maven verify (4 tests), clean exit and zero surviving children. Other platform/package and broader-shape qualification remains actionable. |
| `changeSignature.addParameter` / `addParameter` | `stable` / `managed-stable` | Bounded JDT-bound daemon/MCP lifecycle; `symbol`, `type`, `name`, `default`. |
| `changeSignature.reorderParameters` / `reorderParameters` | `stable` / `managed-stable` | `symbol`, `order`; exact JDT-bound local/parameter reads and literals only; effectful/unproven expressions, method references and argument-list comments refuse. The bounded Linux native saved-cursor GUI cells `run-awk40u` and `run-IeYIQd` pass preview, Apply, copied-source offline Maven verify, and Rollback with source preserved on the source-built daemon (SHA-256 `21dcd303596607fd8287d30f0a674a925917856215f10dbb32c8e6e725262219`). A separate GUI attempt `run-YZaoYl` stopped before `refactor.preview` because the synchronized workspace context was stale after Maven `target/**` outputs disappeared; the provider did not refuse that request. The IDE now surfaces stale-context/sync guidance, with 224 adapter tests, 72 controller tests and BDD typecheck passing. Later native run `run-D1cB9o` passed preview, Apply, copied-source offline Maven verify, and exact rollback. The output-removal actor remains unknown; track this as an actionable IDE snapshot-freshness gap under the existing signature requirement, not a new engine capability. Other packaged/platform cells remain open. |
| `changeSignature.removeParameter` / `removeParameter` | `stable` / `managed-stable` | `symbol`, `name`; bounded JDT-bound private method with unused body parameter and only literal or exact local/parameter call arguments removable; effectful/unproven arguments, method references, public/protected boundaries and incomplete hierarchies refuse. Story verifies daemon/MCP diagnostics, Apply and exact rollback; packaged/native qualification remains open. |

A refused preview must not silently fall back to text replacement. Clients should
show the refusal, warnings, affected files when present, and next action.
For `changeSignature.removeParameter`, a discarded argument that may have
observable effects yields `-32001` and
`error.data.refusalCode = "java.changeSignature.removedArgumentMayHaveEffects"`.
Clients can use this code to present the refusal while retaining the explanatory
message. The daemon issues no plan and leaves project inputs intact.
The legacy `renameParameter` request alias resolves to the canonical engine
operation, but it is not advertised in the SDK-bound operation catalogue; the
managed-authority acceptance identity is `changeSignature.renameParameter`.

For Java `moveClass`, a legacy internal `LEXICAL_FALLBACK` compatibility preview
is projected before generic rendering or pending-plan registration as immutable
`LEXICAL_FALLBACK_REVIEW`. The envelope reports
`semanticCompleteness=NOT_SEMANTICALLY_PROVEN`, `REVIEW_ONLY`, `INELIGIBLE`, and
`evidence.insufficient`; binds deterministic request, snapshot, hidden review
artifact, and evidence SHA-256 identities; and exposes only bounded path/content-
hash/risk residual records with `managedEdit=false`, record completeness,
returned-category counts, and fixed restoration actions. It contains no protocol
plan ID, workspace edit, source range, replacement, apply token, transaction, or
rollback capability. A known `operationId` is audit correlation only and can
produce only the same typed refusal before `PatchEngine`, lock, or WAL.

## Diagnostic evidence contract

Diagnostics may expose `evidence` (`COMPILER`, `STRUCTURAL`, `TRANSACTION`) and
`category` (`SYNTAX`, `TYPE_RESOLUTION`, `PROJECT_STRUCTURE`, `SAFETY`) alongside
stable code, severity, message, and range. Core diagnostics may also carry
immutable, deterministically ordered `DiagnosticDetails`; the bounded operation-
authority drift row uses them for expected/observed file evidence and snapshot
identities without claiming a recomputed observed semantic inventory. Apply
compares the exact staged error multiset against current errors plus explicit
`diagnosticsAfterPreview`; any additional error is `diagnostics.regression`.
Transport exposure of additive detail fields remains capability-specific. Daemon
JSON includes the established evidence/category fields; LSP carries them in
diagnostic `data`.

## Refactoring evidence contract

Every plan exposes one stable evidence category:

- `JDT_BINDING`: edits use clean exact JDT binding/source-range evidence;
- `LANGUAGE_SERVER`: edits are normalized from a bounded semantic-server proposal;
- `NATIVE_AST`: edits use a native parser/compiler rewrite representation;
- `STRUCTURAL`: deterministic local transformation without semantic identity claims;
- `LEXICAL_FALLBACK`: review-only preview when semantic evidence is unavailable.

`PatchEngine` refuses an internal compatibility `LEXICAL_FALLBACK` plan before
lock or WAL creation with `evidence.insufficient`. First-party Java `moveClass`
admission surfaces project that evidence as the edit-free lexical-review envelope
instead of registering, aggregating, or rendering the internal plan. Recipes stop
at that step, discard the staged image, and return no aggregate plan. Eligible
target-scoped Maven plans may additionally bind non-managed workspace files in
`OperationAuthorityFileEvidence`; `PatchEngine` revalidates them against one
under-lock engine-owned snapshot observation before WAL and returns primary
`authorityLease.evidenceDrift` on mismatch.

## LSP server baseline

`modules/refactorkit-lsp` exposes a JSON-RPC/LSP server over stdio using standard
`Content-Length` framing. Clients advertising
`workspace.workspaceEdit.documentChanges=true` receive versionable
`documentChanges`; legacy `changes` is used only for closed-document text edits
when that capability is absent. Structural or open-document edits require
`documentChanges` support and otherwise refuse.

The LSP initialize response `serverInfo.version` is read-only metadata and uses
the centralized implementation version.

TypeScript/JavaScript native semantic LSP operations remain owned by the editor's
language client. The initialize response reports this explicitly through
`capabilities.experimental.refactorkitSemanticOwnership`; managed, rollbackable
compiler-backed script operations are available through CLI, daemon and MCP instead.
RefactorKit LSP accepts mixed script document synchronization and may provide bounded
structural `.ts`/`.js` document symbols, but it never promotes those symbols to
semantic rename authority. Java-only code actions, diagnostics, semantic tokens and
formatting are suppressed for script documents.

| Capability or command | Status | Notes |
|-----------------------|--------|-------|
| `textDocument/definition`, `references`, `documentSymbol`, `diagnostic` | `beta-contract` | Read-only editor integration; definition/references may use JDT binding evidence for clean exact overloaded member call sites. |
| `textDocument/prepareRename`, `textDocument/rename` | `beta-contract` | Must use RefactorKit previews and refusal handling; rename-position resolution may produce signed method selectors for the proven JDT exact-overload slice. |
| `textDocument/codeAction`, `workspace/executeCommand` envelope | `beta-contract` | Command transport and refusal categories are baseline. |
| `textDocument/formatting` | `beta-contract` | Returns client-managed Java formatting edits with no RefactorKit transaction; managed rollback requires `refactorkit.formatFile` plus `refactorkit.applyPlan`. |
| `textDocument/semanticTokens/full` | `experimental` | Token shape may change. |
| `refactorkit.renameClass`, `refactorkit.renameMember`, `refactorkit.moveClass`, `refactorkit.organizeImports`, `refactorkit.safeDelete`, `refactorkit.applyPlan`, `refactorkit.rollback` | `beta-contract` | Command names, preview metadata, and safety semantics are stable for beta pilots. |
| `refactorkit.formatFile` | `beta-contract` | Produces a preview workspace edit and plan ID for optional managed apply. |
| `refactorkit.extractMethod` | `experimental` | Client-managed `WorkspaceEdit` proposal only; the engine can plan the qualified JDT multi-statement shape, but this custom LSP command has no RefactorKit transaction or rollback route. Lexical fallback is review-only. The IDE consumer now forwards multi-statement selections; provider-backed native preview/Apply remains a separate qualification. |
| `refactorkit.changeSignature.renameParameter` | `experimental` | The exact `REQ-JAVA-LSP-SIGNATURE-RENAME-001` Story passes on the source-built `LspSession`: it plans from the open dirty buffer, returns a version-7 client-managed `WorkspaceEdit`, retains no managed plan, and reports no managed Apply/rollback authority. Code actions omit signature rename/add/reorder/remove commands that lack required arguments; clients with input collection can invoke the explicit commands. Standard `textDocument/rename` and daemon/MCP managed change-signature remain separate routes. Packaged/native IDE acceptance remains actionable. |
| `refactorkit.changeSignature.addParameter`, `refactorkit.changeSignature.reorderParameters`, `refactorkit.changeSignature.removeParameter` | `experimental` | Limited MVP; no managed LSP Apply authority. |
| `refactorkit.changeType` | `experimental` | Bounded Java `int` to `long` field proposal using the current immutable Java editor overlay; exact supported shape and saved/open-document preconditions are documented below. The response is a versioned, client-managed edit with no retained managed plan. |
| `refactorkit.changeVisibility` | `experimental` | Promotes one exact JDT-selected method to `public` using the current dirty Java overlay. Requires a saved, open Java file, an explicit SDK, and versioned `documentChanges`; the response is a client-managed edit with no retained managed plan, Apply, or rollback. Planner-supported final/owner restrictions and HIGH-risk warnings still apply. |
| `refactorkit.extractVariable` | `experimental` | Uses the bounded Java return-subexpression planner against the immutable overlay and returns a versioned, client-managed two-edit proposal. The server retains no plan and offers no managed Apply or rollback. |
| `refactorkit.extractField` | `experimental` | Uses the bounded Java compile-time-int field planner against the immutable overlay and returns a versioned, client-managed two-edit proposal. The server retains no plan and offers no managed Apply or rollback. |
| `refactorkit.extractConstant` | `experimental` | Uses the bounded Java compile-time-int constant planner against the immutable overlay and returns a versioned, client-managed two-edit proposal. The server retains no plan and offers no managed Apply or rollback. |
| `refactorkit.extractInterface` | `experimental` | For a saved, open Java file and explicit SDK, plans the existing bounded JDT extract-interface shape from the current dirty buffer. Requires versioned `documentChanges` and client `CreateFile` support; returns the new interface content plus the implementation edit as a client-managed edit. No managed plan, Apply, or rollback is retained. |
| `refactorkit.extractParameter` | `experimental` | Uses the existing bounded JDT planner against the selected complete expression in the current dirty buffer. Requires a saved, open Java file, explicit SDK, and versioned `documentChanges`; returns the parameter declaration, expression replacement, and unique same-file call-site argument edits at the current document version. Client-managed preview only; no retained plan, Apply, or rollback. |
| `refactorkit.extractSuperclass` | `experimental` | Delegates to the bounded JDT planner against the current dirty Java buffer. For the supported primitive `int` method shape, versioned `documentChanges` and client `CreateFile` support let the client preview a generated superclass plus the source class edit. No workspace writes, retained plan, managed Apply, or rollback. |
| `refactorkit.encapsulateField` | `experimental` | Uses the bounded JDT field-encapsulation planner against the immutable overlay of open Java documents and returns client-managed versioned edits. The server retains no plan and offers no managed Apply or rollback. |
| Alias `refactorkit.renameParameter` | `experimental` | Compatibility alias, not preferred for new clients. |

### LSP `workspace/executeCommand` preview and rollback contract

Admitted preview commands such as `refactorkit.renameClass`,
`refactorkit.renameMember`, `refactorkit.moveClass`,
`refactorkit.organizeImports`, and `refactorkit.safeDelete` return a
WorkspaceEdit-compatible object with beta RefactorKit metadata. Java `moveClass`
is the exception when authority is non-managed: REQ-003 guidance or REQ-004
`LEXICAL_FALLBACK_REVIEW` is returned before WorkspaceEdit conversion and has no
`changes`, `documentChanges`, pending plan, or editor-apply capability.

For admitted plans, metadata includes:

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
so `refactorkit.applyPlan({planId})` can apply the exact previewed plan. The same
command accepts `{operationId}` only to return the typed non-managed lexical-review
refusal; the two identity fields are mutually exclusive. Successful managed apply
returns `{ "transactionId": "..." }`; `PatchEngine` owns write-ahead lifecycle
persistence before mutation,
then the LSP session removes the pending plan, refreshes the workspace snapshot,
and republishes diagnostics. Managed apply refuses with
`DOCUMENT_VERSION_MISMATCH (-32007)` if any buffer is unsaved or an affected
document is open; managed rollback applies the same affected-open-document guard.

`refactorkit.changeType` is an immutable-editor-overlay preview operation.
Invoke it through `workspace/executeCommand` with exactly one argument object:

```json
{
  "command": "refactorkit.changeType",
  "arguments": [{
    "file": "src/main/java/com/acme/Counter.java",
    "sdkHome": "/path/to/configured/jdk",
    "position": { "line": 2, "character": 17 },
    "newType": "long"
  }]
}
```

The `file` is workspace-relative, the position is a zero-based LSP cursor on
the field declaration in the current editor buffer, and `sdkHome` must identify
the caller-selected Java SDK. The Java planner remains authoritative for JDT
selection, source-set ownership, field shape, diagnostics, and the admitted
`int` to `long` pair. The command requires
`workspace.workspaceEdit.documentChanges`, an existing saved Java target file
that is open with an LSP version, and a current session snapshot. It captures
all open Java documents in the bounded immutable editor overlay, so unsaved
edits in the target or other Java files participate in JDT selection and staged
diagnostics. Each overlay document must map to a saved Java file in the same
workspace; new unsaved files are not admitted. The planner must produce exactly
one text edit in the selected document, and that edit is validated against its
current buffer before projection.

The command returns a `PREVIEW` object with one versioned `documentChanges`
edit, `refactorkitEditOwnership: "client-managed"`,
`refactorkitRollbackAvailable: false`, and
`refactorkitManagedApplyAvailable: false`; it omits `refactorkitPlanId` and does
not create transaction state. The client owns displaying and applying that
edit. A stale saved snapshot, obsolete editor version, invalid or oversized
overlay, unsupported field, or client without versioned `documentChanges`
receives a refusal instead.

`refactorkit.extractVariable` is also an immutable-editor-overlay preview
operation. Invoke it through `workspace/executeCommand` with exactly one
argument object containing the workspace-relative `file`, caller-selected
absolute `sdkHome`, a zero-based LSP `range` (`start` and `end` positions in the
current buffer), and a Java local `name`. It requires a saved Java file that is
open with a version and a client that supports versioned `documentChanges`.
All open Java documents are captured in the bounded overlay. Each must map to a
saved Java file in the workspace; new unsaved files are not admitted. The
existing JDT planner accepts only a complete bound subexpression within a
direct method return when inserting its denotable local preserves evaluation
order and diagnostics. The server returns one versioned document change with
the planner's two text edits and validates them against the current buffer.
The edit is client-managed: RefactorKit retains no plan or transaction and
cannot apply or roll it back. Stale snapshots, obsolete versions, invalid or
oversized overlays, unsupported expression shapes, and clients without
versioned `documentChanges` receive a typed refusal.

`refactorkit.extractField` is an immutable-editor-overlay preview operation.
Invoke it through `workspace/executeCommand` with exactly one argument object
containing the workspace-relative `file`, caller-selected absolute `sdkHome`, a
zero-based LSP `range` (`start` and `end` positions in the current buffer), and
a Java field `name`. It requires a saved Java file that is open with a version
and a client that supports versioned `documentChanges`. All open Java documents
participate in the bounded overlay; each must map to a saved Java file in the
workspace. The existing planner admits only a complete JDT-bound primitive-`int`
compile-time expression inside a non-static method of a top-level ordinary
class, inserts a private final instance field, and replaces the selection with
`this.<field>`. It validates both edits against staged bindings and diagnostics.
The server returns one versioned document change with two text edits, retains no
plan or transaction, and cannot Apply or roll back the client edit. Stale
snapshots, obsolete versions, invalid or oversized overlays, unsupported
expression shapes, and clients without versioned `documentChanges` receive a
typed refusal.

`refactorkit.extractConstant` is an immutable-editor-overlay preview operation.
Invoke it through `workspace/executeCommand` with exactly one argument object
containing the workspace-relative `file`, caller-selected absolute `sdkHome`, a
zero-based LSP `range` (`start` and `end` positions in the current buffer), and
a Java field `name`. It requires a saved Java file that is open with a version
and a client that supports versioned `documentChanges`. All open Java documents
participate in the bounded overlay and must map to saved Java files in the
workspace. The planner admits one complete, JDT-bound primitive-`int`
compile-time expression inside a method of a top-level ordinary class, inserts
a `private static final int` field, and replaces the selection with the
declaring class's qualified field reference. It validates both edits against
staged bindings and diagnostics. The server returns one versioned document
change with two text edits, retains no plan or transaction, and cannot Apply or
roll back the client edit. Stale snapshots, obsolete versions, invalid or
oversized overlays, unsupported expression shapes, and clients without
versioned `documentChanges` receive a typed refusal.

`refactorkit.encapsulateField` is an immutable-editor-overlay preview
operation. Invoke it through `workspace/executeCommand` with one argument
object containing the workspace-relative `file`, caller-selected absolute
`sdkHome`, and zero-based LSP `position` in the current buffer. It requires a
saved Java file that is open with a version and a client that supports versioned
`documentChanges`. All open Java documents participate in the bounded overlay;
each must map to a saved Java file in the workspace. The exact cursor binding
must resolve to a field admitted by the existing bounded
`JavaEncapsulateFieldPlanner`; that planner makes the field private, creates a
public getter, updates binding-matched Java reads, and validates staged JDT
bindings and diagnostics. The server returns client-managed versioned edits for
affected documents, retains no plan or transaction, and cannot Apply or roll
back the edit. Stale snapshots, obsolete versions, invalid or oversized
overlays, unsupported field shapes, and clients without versioned
`documentChanges` receive a typed refusal.

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

### C SDK inputs for managed LSP commands

`refactorkit.c.preview` accepts a top-level `readOnlySdks` array in the command
argument object. Use the same root/provenance/license object schema as
[daemon SDK input](daemon-protocol.md#explicit-c-sdk-inputs-for-managed-previews).
Tool paths remain explicit. For a workspace-header field whose type comes from
that SDK:

```json
{"jsonrpc":"2.0","id":51,"method":"workspace/executeCommand","params":{"command":"refactorkit.c.preview","arguments":[{"operation":"renameSymbol","clang":"/qualified/usr/bin/clang-22","clangd":"/qualified/usr/bin/clangd","clangFormat":"/qualified/usr/bin/clang-format","readOnlySdks":[{"root":"/opt/example-sdk","provenance":"caller:example-sdk-build","license":"LicenseRef-ProjectSdk"}],"arguments":{"symbol":"raw_count","newName":"renamed","file":"include/api.h","line":2,"character":14}}]}}
```

The engine-managed response contains `planId`, summary/evidence, affected paths
and rendered diff, with no `changes`/`documentChanges`. Explicitly execute
`refactorkit.applyPlan` with `{ "planId": "..." }`; rollback uses
`refactorkit.rollback` with the returned transaction ID. The pending plan retains
its captured SDK-bound toolchain. SDK bytes/membership changed after preview
refuse Apply before WAL even when the workspace snapshot is unchanged. Malformed
SDK objects/array values return INVALID_PARAMS with
`error.data.refusalCode = "c.sdkConfigurationInvalid"`. Caller provenance/license
remain declarations, not independent verification.

Six native C11/C17 cases/24 steps, 23 existing session controls and13 prior native
cases/52 steps pass. SDK/workspace same-name fields, comments/strings and saved
bytes remain exact; C716 is retained with exact relevant input/archive proof.
[ARC42 owns integrated qualification](arc42/appendix-requirements.adoc#c03-sdk-lsp-2026-11-15).
Evidence: `.refactorkit/runs/c03-sdk-lsp-r001/archive-audit.json` and
`lsp-sdk-matrix.json`. `initializationOptions.c` SDK fields also reach discovery,
but full SDK standard-editor-query/read/provider/family/platform proof and other
original C02-C17/release requirements remain `actionable`. Existing delivered
runtimes contain older C code; no runtime install/publication follows.

## MCP server baseline

`modules/refactorkit-mcp` exposes JSON-RPC 2.0 over newline-delimited stdio for
local LLM/MCP clients. It intentionally does not expose arbitrary filesystem
access.

The MCP initialize response `serverInfo.version` is read-only metadata and uses
the centralized implementation version. In `v0.3.0`, it reports `0.3.0`.

### Tools

| Tool | Status | Notes |
|------|--------|-------|
| `project_scan`, `project_summary` | `beta-contract` | Mixed Java/TypeScript/JavaScript workspace lifecycle and project metadata; summary includes high-level Build Model SPI provider/status/source-set information without classpath secrets. |
| `typescript_semantic_start`, `typescript_semantic_stop` | `experimental` | Explicit hash-bound toolchain lifecycle; no package scripts or implicit PATH discovery. |
| `kotlin_semantic_start`, `kotlin_semantic_stop`, `kotlin_diagnostics` | `experimental` | Explicit JDK/compiler lifecycle and compiler-backed diagnostics with lease, snapshot, build/toolchain evidence and process attestation; no build/plugin/script execution or mutation authority. |
| `symbol_search`, `symbol_definition`, `symbol_references`, `diagnostics` | `beta-contract` | Read-only AI context queries; `diagnostics` remains the legacy human-readable form. |
| `diagnostics_v2` | `additive-api-0.2` | MCP text content containing the exact `diagnostics.v2` JSON envelope and requiring the lease returned by `typescript_semantic_start`. |
| `preview_refactoring`, `apply_refactoring`, `rollback_refactoring` | `beta-contract` | Contract applies to beta operations; rollback refuses post-apply divergence by default and accepts explicit `force=true`. |
| `available_refactorings` | `experimental` | Descriptor shape may change. |
| `import_external_java_class` | `experimental` | Import preview with stable provenance/license warning fields in output text. |
| `generate_context_bundle` | `experimental` | Bundle shape may change with agent needs. |

Language-scoped read tools and experimental `renameSymbol` route to an explicitly
started TypeScript/JavaScript session. Semantic plans retain their owning language;
apply uses the exact diagnostics gate, authorization, PatchEngine and WAL, then
closes the stale session. MCP EOF closes all owned semantic process trees.

`preview_refactoring` beta operations are `renameClass`, `renameMember`,
`moveClass`, `moveSourceRoot`, `organizeImports`, and `safeDelete`.
For Java `moveClass`, MCP returns the same structured non-managed guidance or
lexical-review envelope as the other first-party surfaces and registers no
pending plan. `apply_refactoring` requires exactly one of `planId` or
`operationId`; a known lexical operation returns a structured tool error with
`evidence.insufficient`, while an unknown identity is invalid input.
`moveSourceRoot` uses `arguments.from`/`arguments.to` and reports typed refusals
in the tool text. Canonical `changeSignature.renameParameter`,
`changeSignature.addParameter` and bounded `changeSignature.reorderParameters` are
`STABLE`/`MANAGED_STABLE` through the daemon/MCP engine route; bounded
`extractMethod` is also stable for its binding-backed shape. Reorder refuses
effectful/unproven arguments, method references and argument-list comments before
mutation. `changeSignature.changeParameterType` is `STABLE`/`MANAGED_STABLE` in current source and source-built package r003 for its private-method, saved-cursor primitive-boxing, override-family and constructor shapes. `changeSignature.removeParameter` is `STABLE`/`MANAGED_STABLE` for the bounded private-method shape qualified by its operation-bound Story; packaged/native qualification remains open. Native IDE run `run-LPV1XM` reached preview but Apply returned `-32006` because staging identity was captured before write; that PatchEngine defect is fixed and core/Story tests pass. Native run `run-Fx6jDM` then passed preview, Apply, explicit rollback, and Maven verification on package r003 for saved-cursor `int` to `Integer`; broader package/platform qualification remains open. Lexical fallback remains non-applicable.
The custom LSP signature commands remain separately qualified and client-managed.
Experimental
`java.moveAcrossMavenModules` uses the same flat
arguments as daemon `refactor.preview`; MCP apply selects its operation-specific
staged-reactor diagnostics gate.

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

## Java effective project-context acceptance

Status: experimental bounded implementation, `REQ-JAVA-PROJECT-CONTEXT-001`. The
[feature](../features/java-effective-project-context.feature) owns the source-association
acceptance requirement; [ARC42](arc42/appendix-requirements.adoc) owns integrated
status. `project.context` is an advertised read-only RPC. Pair-only requests retain
`java-project-context-v2`; requests with `providerId` return
`java-project-context-v3` and echo the provider in `selectedSource`. The separate
`project.context.v4` method requires `providerId` and returns a strict
`java-project-context-v4` response with per-source-set Maven Failsafe declaration
facts. After explicit Gradle sync, `project.context.aggregate` opts into the v3
response for the full provider/module set, preserving each Gradle module's
`buildRoot` and `projectPath` for workspace enumeration before selection. This
read-only endpoint performs no build execution; pair-only `project.context` stays
v2. Exact parameters and availability boundaries are in the
[protocol](java-project-context-protocol.md), the request schema and the strict
[v2](api-0.2-project-context-v2-schema.json),
[v3](api-0.2-project-context-v3-schema.json), and
[v4](api-0.2-project-context-v4-schema.json) response schemas. Existing v2/v3 routes
and schemas remain unchanged.
The aggregate-route Story evidence is in
`.refactorkit/runs/ide-gradle-aggregate-context-r001/`; it checks five unique
`(buildRoot, projectPath)` pairs on Gradle 9.6.1/JDK 21. A separate repeated-sync
Story selects one included module with provider-qualified `project.context`,
resynchronizes against the same snapshot, then verifies that the second aggregate
response and `project.summary` preserve the same five identities. All five steps
pass locally; the hash-bound receipt and response evidence are in
`.refactorkit/runs/gradle-composite-repeat-sync-r001/`. This confirms the current
RefactorKit process retains identity across sync. On 2026-10-22 the same scenario
also passed over NDJSON against the standard packaged daemon (one active scenario,
5/5 steps), with embedded Java 21.0.12.1. Package tree SHA-256 is
`02d167eb3f93698258c9b528a8bd3d60960c5394258062a02683b1a68e9525d1`; daemon JAR
SHA-256 is `4f5afe008019dc8e929b2a47e97723219211d8751ee681923e50a50b51deed6f`. The
package receipt and raw responses are in
`.refactorkit/runs/gradle-composite-repeat-sync-r002/`. This qualifies the local
RefactorKit package. Native IDE evidence still needs to identify the artifact loaded
by the IDE and verify its consumer refresh path. A Gradle 9.8.0/JDK 21 native GUI
pass is reported in ARC42, but its loaded-daemon hash and raw response remain
unverified; Gradle 9.8/JDK 25 is not qualified.
Existing `project.summary` remains its bounded summary:
it exposes source/generated/output roots and module dependency scopes with a
snapshot hash, while its DTO omits effective classpath entries. The internal
Build Model already carries compilation/runtime classpaths; that fact alone does
not make a public effective Java context complete.

Acceptance must bind every returned projection to the exact project snapshot and
provider/build evidence, with module and source-set identity, source-root ownership,
materialized generated-root provenance, outputs and the selected effective compile/
runtime classpath scope. Entries must preserve ordering and owner/scope visibility;
unrelated modules or source sets do not become visible because they share a
workspace. Missing required inputs, ambiguity and truncation must be explicit,
never a complete empty context. Stale snapshot/build/root/classpath evidence
requires reacquisition and cannot authorize a current association. Consumers use
these facts and existing engine symbol provenance, rather than constructing a
second semantic project model.

The concrete fixture contains `src/main/java/pkg/Runner.java` declaring public
`Runner` and package-private `Peer`, plus `src/test/java/pkg/Runner.java` declaring
package-private `Unrelated`. A consumer reports the source-relative path
`pkg/Runner.java`. With selected main scope, `pkg.Runner` and `pkg.Peer` both belong
to the main file; the secondary type cannot be guessed as `Peer.java`. Selected
test scope identifies `pkg.Unrelated` in the test file. The contract must expose
the provenance used for these choices, including the selected output/classpath
scope. A separate materialized generated-root case keeps its declared main owner.
An unselected source-relative path or unresolved ownership stays ambiguous; never
choose the first matching path or always prefer main. Changed build evidence must
refuse the previous context even if both source files remain readable.

JDI source paths and loaded-class/runtime selection are consumer inputs. JDI,
build/run execution and editor markers remain IDE/JDK responsibilities. No
debugger command, lifecycle execution or IDE implementation is added to RefactorKit.
The original six source-association cases now pass through the real daemon with
explicit SDK/JDT evidence. Additional reactor scope checks preserve ordered compile
and runtime contexts. At this delivery's scope, IDE M2 remained open: ordinary
Gradle discovery was partial, effective Gradle sync was not yet qualified, and
SDK/context authority was not yet pinned by daily Java queries or mutations. No
expansion of the 0.8.0 C01-C17 catalogue is made. The historical release metadata
earlier in this document is `stale` for current-version discovery; use `server.version`.

### Java query-session continuity after context acquisition

This is tracked under the existing `REQ-JAVA-QUERY-CONTEXT-001` requirement and
its [query-context feature](../features/java-query-context-authority.feature);
the IDE report does not create a duplicate requirement. On 2026-10-26 a native
IDE consumer reported that, in a two-module Maven reactor, `project.context` v2
succeeded and its attestations matched, but the following read-only
`intelligence.query` for `definition` ended the session without a JSON-RPC
response. Two native GUI run artifacts are now identified (`run-5FGgBw` and
`run-zQSY8e` under `/home/paperboy/workspace/ide/test-results/native-desktop-story/`).
Both report daemon v0.7.0 at
`/home/paperboy/.local/share/refactorkit/bin/refactorkit-daemon`, SHA-256
`21dcd303596607fd8287d30f0a674a925917856215f10dbb32c8e6e725262219`. Their
request traces show RPC id 6 (`intelligence.query`, `definition`,
`app/src/main/java/example/Main.java`, line 0, character 1) with the acquired
v2 context hash, followed by a native session failure after 282 ms and 356 ms,
respectively, with null RPC codes and no recorded JSON-RPC response. The GUI
trace records overlapping Monaco requests and cancellation. The IDE withdrew the
context and refused its next `implementations` query pending resynchronization.

The existing two-query acceptance scenario was re-run against both the current
source-built daemon and self-contained packaged daemon. Both retained the same
NDJSON session through `definition` and `implementations`, returned the acquired
context hash/schema and the exact SDK platform identities, and left source/POM
bytes unchanged. Both runs passed; neither reproduces the native IDE report.
Separate diagnostic probes against the exact installed daemon SHA used a temporary
two-module Maven reactor: cancellation at 40 ms returned JSON-RPC `-32800` by
43 ms; cancellation at 100 ms during a lightweight query returned it by 136 ms.
A subsequent `server.version` succeeded in each same process. This does not cover
the native GUI workspace or a query that exceeds the IDE transport's 250 ms
cancellation window. A heavier synthetic two-module reactor with 502 Java sources
had a 3,255 ms uncancelled definition query; cancellation at 100 ms returned
`-32800` by 103 ms, and `server.version` still succeeded. These direct probes do
not reproduce the native GUI bridge; later native traces below identify the
transport's cancellation deadline as the branch that retires the session.
Inspection of the consumer's Rust transport shows that cancellation sends
`intelligence.cancel` but terminates the daemon if no reply arrives within 250 ms.
In `run-zQSY8e`, the pre-query `project.context` verification took 784 ms, the
query failed at 356 ms, and the provider returned 276 ms after the recorded query
cancellation. That timing closely matches the 250 ms cancellation deadline plus
the 25 ms receive poll. A later native diagnostic (`run-0gOKAI`) records the
cancellation notification written 125 ms after query dispatch, then
`cancellation_deadline` at 375 ms total with the daemon process still running and
no response for query RPC id 6. Its GUI scenario subsequently failed while
waiting 20 seconds for the `Helper.java` tab, so this diagnoses the retirement
path but is not a passing native acceptance. It proves deadline-driven retirement
of a live child; it does not prove whether the daemon received the notification
or why its query had not answered.

In the current RefactorKit worktree, `javaContextQuery` reacquires bound context
for each query through `JavaBoundContext.read`, including a fresh `scanWorkspace`
and effective-context acquisition. Workspace composition has no cancellation
token, and the context provider checks cancellation before that scan. This is a
plausible latency source beside the native 808 ms context verification, but the
installed v0.7.0 binary is not bound to this exact source revision; treat it as a
hypothesis rather than a proven cause.

The final IDE no-bundle build uses a one-second cancellation-acknowledgement
bound. Its native run `run-CvDadq` passed the full scenario (1 scenario, 29/29
steps): both in-flight cancellations received daemon error `-32800`; after each,
the IDE reacquired context and a following definition query returned `ready` on
the same session. The app closed cleanly without forced termination. The source
agent also reports five focused Rust tests, `rustfmt`, `git diff --check`, and
`pnpm validate:docs` passing. This verifies the reported session-loss path on one
Linux x86-64 release-no-bundle build; it does not measure exact provider
cancellation latency or qualify packaged/other-platform delivery. Keep the
broader integration qualification `actionable`. The supplemental failure trace is
`.refactorkit/runs/java-query-context-attestation-stability-r001/native-run-0gOKAI-terminal-analysis.json`;
the earlier passing comparison is
`.refactorkit/runs/java-query-context-attestation-stability-r001/native-run-4ULWtd-cancel-grace-validation.json`.
The final-code result is
`.refactorkit/runs/java-query-context-attestation-stability-r001/native-run-CvDadq-final-validation.json`.
Earlier focused evidence remains in the same run directory. ARC42's
[requirements appendix](arc42/appendix-requirements.adoc#javaquerycontextauthoritydelivery)
remains the integrated status authority.

## IDE request priority, 2026-10-03

The user's explicit priority is IDE Java integration before the 0.8.0 C roadmap.
The delivery order is: effective Maven/Gradle project context for M2, immutable Java
editor snapshots with stale-version refusal, then cursor-to-mutation selection and
versioned refactoring results. Each contract must be exposed and exercised by a
consumer; symbol lists and `project.summary` do not satisfy the effective-model gate.
The reported absolute-path import defect is fixed as an immediate bounded correction
under [REQ-JAVA-IMPORT-PATH-001](../features/java-organize-imports-path-selection.feature).

At the time of this 2026-10-03 entry, gaps were `actionable`: the public summary
omitted effective classpaths and SDK authority; use the separate experimental
`project.context` projection for bounded Maven acquisition. Gradle remained
declarative/partial; Java editor overlays were unsupported by `intelligence.query`;
saved-disk position navigation did not authorize a mutation against a versioned
editor document. M2 acceptance required a typed, versioned read-only
provider exposing module/SDK identity, main/test/generated roots, ordered compile and
runtime classpaths, provenance, readiness and stale-evidence refusal, with its model
consumed by Java analysis. Maven and Gradle require separate honest provider evidence.
Publication and C catalogue completion follow these IDE gates. ARC42 remains the
integrated authority; this section records scheduling and consumer contract gaps.

## M2 project-context acceptance reconciliation, 2026-10-18

This is the existing P1/M2 IDE gate under `REQ-JAVA-PROJECT-CONTEXT-001`, distinct
from the P2 Java build/module-transformation row below. RefactorKit owns effective
project facts and honest typed refusals; build/task execution remains Maven/Gradle/JDK
and IDE-owned.

M2 is an extension of the existing source-built API 0.2 implementation. The daemon
exposes `project.context` and `project.sync`; the Maven and Gradle paths have different
execution authority. Maven context uses the offline effective reactor model without
running Maven plugins. Unsynchronized Gradle discovery stays declarative and partial,
so it cannot establish authoritative source ownership. Explicit caller-authorized
`project.sync` evaluates Gradle offline under bounded execution/output limits and
does not request compilation or annotation-processor tasks. It evaluates project
build scripts and plugin configuration code; offline resolution is not a sandbox.
Build, run and test lifecycle work remains owned by Gradle or Maven.

The bounded model carries modules, source sets, source and materialized generated
roots, output directories, declared dependencies, ordered compile/runtime
classpaths, provenance and selected SDK/compiler evidence. Acceptance must retain
fixtures for both Maven and Gradle that verify those fields, source association,
provenance and stale-evidence refusal. It must also show that the selected JDK agrees
with each source-set compiler and the classpath consumed by JDT. Typed refusals are
part of the public result; the protocol satellite documents the current codes and
the feature fixtures must verify the relevant refusal cases.

The public context pairs each source set's roots with that same source set's resolved
compile/runtime classpaths; entries retain provider/module/source-set ownership.
Consumers must not combine classpaths across source sets or modules. The IDE's
JUnit 4/JUnit Jupiter/TestNG template checks are provider/Monaco consumer behavior,
not a RefactorKit semantic operation. Final native GUI release/no-bundle runs passed
the Gradle template flow (`run-uAHEm0`) and Maven template flow (`run-LCUVng`): all
three framework templates insert into unsaved drafts, preserve the source draft and
clean up normally. The earlier `run-G9sebN` helper failure was resolved by correcting
the IDE probe path and draft cleanup. This closes that consumer test-harness issue,
not the broader P1/M2 provider acceptance for Maven profiles/modules, compiler and
processor metadata, or JPMS.
The v2 source-set DTO has fields for kind, roots and provenance, compiler options,
processors, resolved artifacts and JPMS facts. The Maven Build Model now creates
distinct `CUSTOM` rows for additional build-helper roots, and the generic context DTO
serializes the source-set kind and those roots. A Maven custom test root is classified
as `INTEGRATION_TEST` only when its build-helper `add-test-source` declaration has one
complete base/profile origin and exactly one Failsafe `integration-test` execution is
declared in that same scope. The rule does not inspect path, profile or execution names;
multiple Failsafe candidates make root provenance ambiguous, and unresolved profile
ownership cannot establish integration-test kind.

The existing v2 `compilerOptionsAvailability`, `compilerOptionsRefusalCode` and
ordered `{name,value,origin,redacted}` records now carry bounded Gradle
`JavaCompile.compilerArgs` for the supported Java 8-26 range. Explicit sync preserves
literal argument order and task/source-set origin, redacts sensitive values, and marks
providers, custom task actions or incomplete inventories partial without evaluating
argument providers or running compile tasks. Public-wire Stories pass the enabled,
absent, provider, redaction and custom-action refusal cases; receipts and inventories
are recorded under `.refactorkit/runs/java-gradle-compiler-options-r001/` and
`.refactorkit/runs/java-gradle-task-action-r001/`. No wire field was added. Java 27
remains a typed sync refusal until source-range, schema-bound and JDT/platform support
are separately qualified.

The existing public-wire Story qualifies this bounded v2 result with a dynamic
two-module reactor: active and inactive profiles, exact module/source-set association,
provider-bearing build-helper root provenance, ordered compile/runtime classpaths,
stale-POM refusal and duplicate-root refusal. Its four examples pass without executing
Maven.

A second Story now exercises the checked-in two-module reactor in
`testdata/java-maven-failsafe-context`. The test copies its POMs and integration source
into a disposable workspace, enables or omits the `quality` profile through
`project.open`, and queries the actual public daemon/provider context. The active case
checks `AppIT` binding, app/core `INTEGRATION_TEST` rows, build-helper provenance and
the cross-module main output on the integration compile classpath; the inactive case
checks that the profile root is absent. Both examples pass, and the checked-in fixture
remains byte-identical. A temporary materialized core output marker supports the
classpath assertion; no Maven command, lifecycle, Failsafe test or plugin executes.
This directly verifies conditional classification of the Build Helper custom test
root as `INTEGRATION_TEST` under the active profile and its absence when inactive.
This qualifies RefactorKit's provider response only; it does not qualify Maven GUI
task/test selection, report presentation or coverage behavior in an IDE.

A separate consumer-reported native IDE test on a two-module Maven reactor confirms
that the Failsafe target executes in the intended module/context. In that test, a
`project.context` request selecting source-set kind `INTEGRATION_TEST` did not resolve;
the consumer reports that the available Maven model lacks capability/data required by
that selector. This evidence covers only `INTEGRATION_TEST`, does not establish other
selector behavior, and does not isolate a further root cause. It remains an open
consumer-context selection gap under the same requirement; it is distinct from the
passing provider-side source-set classification Story above.

An additional consumer-reported Tauri release/no-bundle characterization on
2026-10-21 synchronized a real Maven `app` module configured with Build Helper
`src/integration-test/java` and Failsafe. Its synchronized module/source-set selector
offered only `Maven · app / main` and `Maven · app / test`; no integration-test row
was available, so the Maven task was not started and no target outputs were created.
The characterization passed as a limit check, preserved drafts and inputs, and
closed cleanly. This confirms the missing selectable `INTEGRATION_TEST` row for that
consumer scenario; it does not identify a provider version or code cause and does
not establish task execution in this particular run. The selection gap remains open
under the existing requirement.

Native IDE rerun — 2026-10-21: the Linux limitation story
`pnpm test:story:req-ide-java-project-context-native-001:maven-failsafe-context`
passed against the Oct 21 release/no-bundle IDE and current source-built RefactorKit
daemon. The synchronized Maven `app` selector still offered only `main` and `test`;
the evidence reported
`synchronizedMavenIntegrationTestSourceSetUnavailable=true` and
`synchronizedMavenFailsafeTestTaskRan=false`. No Maven task or build output was
produced, the dirty draft and project inputs were preserved, the window closed
normally, and cleanup found no process survivors. This confirms the same open
consumer gap in the current dirty checkout. It does not qualify Failsafe execution
or identify a cause. Keep this under `REQ-JAVA-PROJECT-CONTEXT-001`; do not treat
integration-test execution as available until typed provider/module/source-set-bound
Failsafe facts and exact native source-set/task selection are qualified.

A separate focused native two-module Maven Failsafe characterization reports that
the consumer acquired Maven context for a project configured with
`src/integration-test/java`, but the synchronized project context did not expose or
classify that source set. The reported flags are
`synchronizedMavenIntegrationTestSourceSetUnavailable=true` and
`synchronizedMavenIntegrationTestSourceSetClassified=false`. The scenario passed as
a limit characterization, started no Maven task, left source inputs unchanged, and
exited cleanly. It does not attribute a cause or qualify synchronized Failsafe
execution; this is additional consumer evidence for the same open selection gap.

Provider-selector follow-up — 2026-10-22: `project.context` now accepts an exact
`providerId`, `moduleId`, and `sourceSetKind` selector when that kind identifies one
source-set row. The response returns the concrete source-set ID; duplicate kind rows
refuse with `java.context.sourceSetKindAmbiguous`, absent kinds refuse with
`java.context.sourceSetUnavailable`, and cross-provider matches still require
`providerId`. The three-case public Story selects a unique Maven `INTEGRATION_TEST` row,
rejects duplicate rows without publishing a selected source, and rejects an inactive
profile with no such row (12/12 steps). Receipt and schema evidence are in
`.refactorkit/runs/java-failsafe-source-set-kind-r002/`; they supersede the earlier
two-case receipt at `r001`. This resolves RefactorKit's bounded provider selector gap.
A separate v4 Story now qualifies bounded Failsafe declaration metadata on each
source-set row; native IDE task selection, coverage and report mapping remain
actionable under the same requirement.

A related but separate consumer observation is tracked under the existing
[`REQ-JAVA-MAVEN-MODULE-RENAME-001`](requirements/java-maven-ownership-migration.md):
after rename Apply, `mvn -o verify` created `core/target/**`; rollback was refused
while those outputs remained and succeeded after their removal. This is an observed
interaction between generated outputs and rollback eligibility, with no cause
attributed. It does not qualify source-set classification or synchronized Failsafe
execution.

The RefactorKit provider Story's fresh §18.6 receipt binds the checked-in-reactor
scenario and its two cases. The pre-edit
analyzer inventory found three exact reuses, one required Given step, one runner and
no duplicate expressions or ambiguity. Cucumber's passing run resolves the new Given
at runtime; the installed analyzer was not exposed in this run, so no post-edit analyzer
inventory is claimed. ARC42 owns integrated status and implementation order. The
v2/v3 DTOs still have no dedicated Failsafe execution/version, selector or
report-configuration record, so `INTEGRATION_TEST` plus build-helper provenance alone
does not establish exact Failsafe task/report mapping. The separately versioned
`project.context.v4` response now carries bounded declared plugin/execution identity,
explicit selectors, report settings and profile origin; its four-case Story validates
complete, duplicate, unresolved and inactive-profile results against the closed v4
schema. Receipt: `.refactorkit/runs/java-maven-failsafe-context-v4-r001/`. This is
metadata about declarations only. It does not qualify synchronized IDE task
selection, coverage or report presentation; broader Maven inheritance/merge shapes
remain actionable under the M2 gate.

The same `REQ-JAVA-PROJECT-CONTEXT-001` gate now has a bounded v7 test-JVM
projection; v2-v6 remain closed. The Maven provider reports direct same-POM
Surefire settings on the `test` source set and Failsafe settings only on a
provider-declared `integration-test` source set. It exposes mode, explicit fork
settings, origin-bearing `argLine` layers, preserved `@{argLine}` tokens, and
independent completeness/refusal codes. The original six-case Story covers direct
settings, ambiguity, unresolved values, and incomplete default bindings. A separate
six-case Story qualifies defaults read from an exact local plugin descriptor only
when a literal version is declared directly or comes from one workspace-contained
immediate-parent version-only `pluginManagement` entry; descriptor coordinates,
version and goal must match, and the JAR SHA-256 is bound to the project snapshot.
An explicit nonliteral module plugin version such as `${surefire.version}` never
falls through to the parent origin, even if interpolation equals the parent's
literal version.
An empty `argLine` additionally requires no POM property or explicit override and
no active JaCoCo `prepare-agent`/`prepare-agent-integration` execution. JaCoCo leaves
the `argLine` unavailable and the test-JVM fact partial. Other plugin-property
mutation is outside this bounded qualification. Profile and unsupported inheritance
layers, unresolved values, missing descriptors, and ambiguous source-set ownership
remain partial/refused. Both Stories preserve project bytes and execute no Maven
goals. The r004 receipt, analyzer inventories and focused result are recorded under
`.refactorkit/runs/maven-test-jvm-plugin-defaults-r004/`. The IDE must refuse any required JVM setting whose returned fact is
incomplete.

Exact dependency source attachments use the existing separate lookup/read contract.

JPMS v2 serializes module path and a descriptor subset (`moduleName`, `open`,
`requires`, `exports`, content hash). The resolver now treats any descriptor
directive outside `requires` and `exports` as unprojected: it preserves the bounded
v2 descriptor facts, marks JPMS `partial`, and uses
`java.context.jpmsDescriptorIncomplete`; requiring JPMS refuses with that code. A
one-scenario Maven Story covers `opens`, `uses`, and `provides`, the unchanged v2
response, exact descriptor hash, and read-only acquisition. A separate Story verifies
that changing `module-info.java` after the snapshot is opened refuses with
`java.context.snapshotStale`. Receipts:
`.refactorkit/runs/java-jpms-descriptor-completeness-r001/` and
`.refactorkit/runs/java-jpms-stale-descriptor-r001/`. A third Story changes a
compiler-configured Maven module-path JAR after open and verifies the same stale
snapshot refusal; receipt `.refactorkit/runs/java-jpms-stale-module-path-r001/`.
Positive Maven and Gradle Stories compile real named module-path JARs and verify
complete JPMS fact availability, exact module name and snapshot-bound fingerprint,
descriptor identity, `requires` modifiers, qualified exports and content hash. The
Gradle case uses literal `JavaCompile.options.compilerArgs`; it exposed that Gradle
compiler module-path entries were absent from scanner `ClasspathEvidence`, so the
scanner now captures those artifact fingerprints. Required-JPMS requests are not
refused; overall context remains `partial` with no selected source because JDT does
not yet consume the separate path. Receipts:
`.refactorkit/runs/java-jpms-positive-module-path-r001/` and
`.refactorkit/runs/java-jpms-gradle-positive-module-path-r001/`. Together with the
bounded directive-completeness and stale-input Stories, these checks do not change the
v2 wire. A separate Gradle Story changes the configured module-path JAR after
explicit sync and verifies `java.context.snapshotStale` with no module facts published;
receipt `.refactorkit/runs/java-jpms-gradle-stale-module-path-r001/`. Broader
Maven/Gradle path ownership and aggregation, named-module navigation, and native IDE
consumption remain actionable.
Gradle Java 27 sync has a
typed unsupported-level refusal. Maven release 27 now has a bounded `project.context`
refusal with `java.context.sourceLevelUnsupported` before modules or source-level
facts are published; this does not claim Java 27 support. See ARC42 and the protocol
satellite for the evidence and remaining limits. No duplicate requirement is opened.

### Initial Java 26/27 gap assessment, 2026-10-26 (Java 26 superseded by the provider update below)

This extends the existing P1/M2 gate `REQ-JAVA-PROJECT-CONTEXT-001`; it does not
create a second context requirement. The implemented positive boundary remains
Java 8-25, and the existing Maven/Gradle Java 27 cases establish only typed refusal.
As of 2026-10-26, Java 26 and 27 are GA releases, with JDK 27 the latest feature
release ([Java SE specifications](https://docs.oracle.com/javase/specs/),
[release list](https://ops.java/releases/)). The current range therefore leaves a
provider-context gap for both Maven and Gradle projects at those source levels.

The same requirement calls for positive, provider-specific acceptance for Java 26
and 27: expose exact source-set compiler identity and ordered options; preserve the
declared `--release`, `source` and `target` facts; bind selected-JDK release
metadata, the matching `ct.sym` entry when `--release` applies, and platform-module
identities/hashes to JDT; and prove that the analysis parser and API baseline match
each source set. Keep compiler runtime identity distinct from the project's
source/API release. Missing, stale or conflicting evidence must refuse before
context is published. Existing refusal scenarios remain safety cases until
superseded by positive support evidence. Future GA feature releases require an
explicit versioned matrix update; no forward support is inferred.

Implementation follow-up — 2026-10-26: the common source-level policy now derives
its upper boundary from both the installed AST DOM level and ECJ source-level
support. Maven/Gradle numeric declarations are preserved and refused above that
boundary instead of falling back to Java 25/8. Gradle explicit sync also checks the
selected JDK's release metadata and exact `ct.sym` signature; JPMS and semantic
parsing do not clamp source levels. Offline Java module tests pass (307, zero
failures/errors, five skipped), but this is fail-closed plumbing, not positive
Java 26/27 qualification. RefactorKit still resolves JDT/ECJ 3.44.0 and its test
runtime is JDK 21. A verified temporary Temurin 26.0.2.1 is available at
`/tmp/ide-validation-temurin-jdk26`; candidate r001 still refuses its Maven
release-26 context with `java.context.sourceLevelUnsupported` and publishes no
modules. This confirms fail-closed behavior only. The separate local IDE JDT LS
1.62 bundle (JDT Core 3.48 / ECJ 3.46.200) reports AST JLS27 and parses a Java 27
preview primitive-pattern fixture, but that probe does not establish RefactorKit
support. Java 26 requires a JDK 26 platform and JDT 3.46+ DOM JLS26; Java 27 requires JDK 27
and a JDT release exposing DOM JLS27. The current official JDT DOM API documents
JLS26; JavaCore/ECJ `VERSION_27` alone does not qualify Java 27 parsing. Separate
No positive Java 26/27 public Story acceptance exists. The BDD project analyzer is
available and can produce source-scoped inventories before Story edits; this
tooling and the local parser probe do not change the unsupported source ceiling or
positive qualification status. The JDK toolchain was delivered and hash-verified;
no JDT dependency was downloaded. Status remains `actionable`; no v2 wire
expansion is proposed. Exact offline module reports and source hashes are recorded in
`.refactorkit/runs/java-parser-release-eligibility-r002/evidence.json`.

Provider qualification update — 2026-10-27: the bounded Java 26 provider gap is
closed. JDT Core 3.47.0 / ECJ 3.46.100 was resolved from the existing local Gradle
cache; no dependency network access was performed. Exact Maven and Gradle public
`project.context` Stories pass with Temurin JDK 26.0.2.1 and verify selected source
identity, JDT binding, compiler release/options, JDK release metadata, exact
`ct.sym` and platform-module identities/hashes, plus unchanged inputs. The Java
source-level policy Spec and provider-specific Java 27 refusal scenarios also
pass. Java 27 positive support remains `actionable` because the resolved stable
JDT DOM exposes `JLS26`; JavaCore/ECJ constants and IDE parser behavior do not
replace provider qualification. The existing API 0.2 wire remains unchanged.
See the [ARC42 status](arc42/appendix-requirements.adoc), the exact scenarios in
the [project-context feature](../features/java-effective-project-context.feature),
and receipts under `.refactorkit/runs/java26-maven-context-r001/`,
`.refactorkit/runs/java26-gradle-context-r001/`, and
`.refactorkit/runs/java27-gradle-context-r001/` plus Maven release-27 refusal
evidence under `.refactorkit/runs/java-maven-release-27-context-r001/`.

Java 27 preview-candidate update — 2026-10-29: an isolated copy of the existing requirement now has provider-specific candidate Stories for Maven and Gradle. Each selected Story passes one scenario with 27 sibling scenarios skipped and zero failures/errors; the source-level policy Spec passes 7/7. The acceptance binds source/compiler release and options to a verified Temurin 27 JDK, `ct.sym`/platform facts and candidate JDT analysis. A direct parser probe reports `AST.JLS27` and zero errors for a Java 27 preview primitive-pattern fixture. An isolated runtime package containing JDT Core 3.48.0/ECJ 3.46.200 also passes CLI version, daemon version and daemon `project.open` smoke checks. Those JDT jars were copied from the local IDE bundle under synthetic preview coordinates; they are not published stable Maven dependencies. Stable product support remains actionable and release 27 remains refused in the installed baseline. No dependency, API 0.2 schema or installed package was changed. The candidate delta, package SHA-256, exact receipts, analyzer inventories and dependency provenance are preserved in `.refactorkit/runs/java27-jdt-preview-candidate-r001/`. The temporary package is an integration feasibility artifact, not a release candidate for general Java 27 refactoring.

Java 27 v7 source-analysis update — 2026-11-10: the existing `REQ-JAVA-PROJECT-CONTEXT-001` now has one hash-bound Maven Story for a request containing both an owned source path and a symbol. Against the stable production dependency JDT Core 3.47.0, the Story passes with the typed `java.context.sourceAnalysisUnqualified` refusal and no source facts published. With only an invocation-scoped substitution to the local JDT Core 3.48.0 / ECJ 3.46.200 preview pair and a checksum-verified Temurin 27.0.1 platform, the selected Story passes with a real binding and exact platform metadata. The candidate run is limited to Maven and does not qualify Gradle, general Java 27 refactorings, or the native IDE flow. The production dependency remains unchanged: [Maven Central currently lists JDT Core 3.47.0](https://central.sonatype.com/artifact/org.eclipse.jdt/org.eclipse.jdt.core/versions), while the [JDT LS changelog](https://github.com/eclipse-jdtls/eclipse.jdt.ls/blob/main/CHANGELOG.md) lists Java 27 support in 1.62 and the newer 1.63 release; JDT LS support is separate from RefactorKit acceptance. The smallest stable dependency action is to advance the existing JDT Core pin only after a stable release exposing JLS 27 is available, then qualify both providers and the IDE consumer. BDD receipt, pre/post analyzer inventory, run logs, and reports are under `.refactorkit/runs/java27-v7-semantic-r003/`.

IDE native Java 27 cross-module navigation — 2026-11-11: the IDE consumer reports that the r002 `project.context.v7` Maven replay retained Java 27 source ownership while RefactorKit semantic analysis stayed explicitly unqualified. Eclipse JDT LS F12 navigated from the Maven application module to its library declaration with project JDK 27 and server JDK 25. The `release-no-bundle` native scenario passed one scenario/11 steps. Its JDK `release` SHA-256 is `7718b4ea47f370bf1a4183b933a446d3609d3e83c20d2edafbcb2c050c24eef0`, `ct.sym` SHA-256 is `e0b6302dd1d31b9a9d40bd379ce62c625768609c068cdaa3a988f6edfeddcd2e`, and daemon SHA-256 is `21dcd303596607fd8287d30f0a674a925917856215f10dbb32c8e6e725262219`. F12 was served by Eclipse JDT LS; it is separate from RefactorKit's Java 27 source-analysis qualification. This consumer smoke does not qualify a bundled application or a real Maven build. Keep the existing requirement `actionable`; the stable RefactorKit JDT baseline still refuses Java 27 source analysis. The IDE run artifacts remain in the consumer workspace; this repository records the externally supplied report and identities without treating them as RefactorKit acceptance. See [ARC42's IDE replay record](arc42/appendix-requirements.adoc#ide-native-java-27-f12-cross-module-navigation-2026-11-11).

The latest `java27GradleContextNativeGui` consumer report also records exact owner mapping for `:app/main`: `pkg/Runner.java` maps to `app/src/main/java/pkg/Runner.java` with `SOURCE_ROOT_OWNERSHIP`, no selected symbol or binding key, and semantic analysis still `unqualified`. Gradle Build, Run and Tests pass with Gradle 9.8.1 on JDK 21 and the project configured for JDK 27; inputs remain unchanged and the IDE exits cleanly. The reported JDK identity SHA-256 is `d7dd6bd817bb564b611fd9a2c46afa92541a36329e344f93bed21bd1202ab420`; the `release`, `ct.sym` and daemon identities match the Maven replay above. This is consumer build/run/test evidence, not RefactorKit Java 27 source-language qualification or a bundled-IDE result. The consumer reports no additional provider gap. The report remains external to this checkout and is integrated here under the same requirement; see [ARC42](arc42/appendix-requirements.adoc#ide-native-java-27-f12-cross-module-navigation-2026-11-11).

Stable JDT dependency check — 2026-11-11: the official [Maven Central artifact page](https://central.sonatype.com/artifact/org.eclipse.jdt/org.eclipse.jdt.core) still lists JDT Core 3.47.0, while the Java 27-capable 3.48.0/ECJ 3.46.200 pair recorded above remains preview material from an IDE bundle. This means no stable published JDT pair is currently available for a production parser upgrade; preserve the typed Java 27 refusal and actionable M2 gate until a stable JLS 27 parser release can be dependency-previewed and qualified.

IDE JDT LS 1.63 GUI follow-up — 2026-11-12: the consumer reports a locally
verified JDT LS 1.63.0 archive at
`/tmp/ide-jdtls-1.63.0/jdt-language-server-1.63.0-202610191322.tar.gz`,
SHA-256 `b1f2abe0b3cccb462cf64fb4a5587c7d09df60cf8be7748b152cbd89d018bdc4`,
installed at `/tmp/ide-jdtls-1.63.0/server` with server JDK 25. The latest GUI
attempt did not reach F12: the `project.context.v7` Gradle context was available
and attested, but the Java Run panel disabled Build because it did not detect
Gradle. This is an inconclusive consumer attempt and is not attributed to
RefactorKit. It does not qualify the JDT LS 1.63 navigation path or change
RefactorKit's Java 27 parser/binding status; keep the native follow-up actionable
and Java 27 source analysis typed-refused. The archive is under `/tmp`; its
checksum and bundle entry were verified locally. Native GUI logs remain in the
IDE workspace; the relayed summary is stored at
`../.refactorkit/runs/ide-jdtls-1.63-gui-r001/evidence.json`. Integrated status
authority: [ARC42](arc42/appendix-requirements.adoc#ide-jdt-ls-163-gui-attempt-did-not-reach-f12-2026-11-12).

Native IDE consumer follow-up — 2026-10-26: the IDE reports that wizard-generated
Java 26/27 Maven projects are refused during project sync by installed RefactorKit
0.7.0, which advertises support only through Java 25. The provider withholds
semantic context, leaves project inputs and build outputs unchanged, and exits
cleanly. This confirms the user-facing context gap while demonstrating fail-closed
behavior; it is attached to this same requirement and is not independent release
acceptance because its report artifacts live in the IDE consumer workspace.

Status ownership, 2026-10-20: ARC42 remains the integrated status authority, this
feature owns the requirement scenarios, and the protocol satellite owns the exact
wire contract. The bounded Maven source-set ownership/provenance cases now have
dynamic and checked-in reactor acceptance. The broader requirement remains actionable:
versioned Failsafe execution/selector/report metadata, compiler and processor facts,
and broader JPMS/module-path support remain separate slices under this same REQ.
The bounded numeric Maven release-27 refusal is implemented and accepted as described
above; unresolved expressions and other compiler-option precedence combinations remain
separate cases. Do not create another project-context requirement or alter the existing
P1 `movePackage` work item.

### Maven resolved artifact coordinates, 2026-10-22

The existing v2 public DTO already carries `resolvedArtifacts` per source set, including
resolved group/artifact/version, extension, classifier, scope, transitivity and
fingerprint. The IDE-facing M2 audit incorrectly recorded those facts as absent from
the wire. A fresh Story now requires `artifact-coordinates`, checks the exact main and
test closures and confirms that classpath-entry artifact records match their source-set
records. Its second case removes the local test JAR and verifies a typed
`java.context.classpathUnavailable` refusal. Both cases pass (8 steps); the receipt,
pre/post glue inventories and execution evidence are in
`.refactorkit/runs/java-maven-artifact-coordinates-r001/`. This validates bounded
RefactorKit Maven projection only. Native IDE parsing and use of artifact coordinates,
Maven profile/compiler/processor variants and the remaining JPMS cases remain
`actionable` under M2.

### Gradle annotation processor facts, 2026-10-22

After explicit offline Gradle sync, the v2 context now projects a bounded local
file-based `JavaCompile.options.annotationProcessorPath` for the selected main source
set. A public-wire Story verifies the exact workspace-relative JAR path, a processor
name read from `META-INF/services/javax.annotation.processing.Processor`, empty
undeclared options/dependency coordinates, and a snapshot-bound fingerprint in
`buildEvidence`. Its fixture processor would generate a marker source if invoked; the
Story proves that sync does not compile sources or execute it. The one-scenario
acceptance passes four steps with a fresh §18.6 inventory and receipt in
`.refactorkit/runs/java-gradle-annotation-processor-r001/`. No v2 wire fields or test
dependencies were added. Gradle provider/custom-task/argument overrides remain partial
and refuse when required; module-coordinate processor dependencies, Maven inheritance
and compiler-plugin variants, and native IDE presentation remain actionable.

The existing effective-Gradle Story qualifies six Groovy-DSL cases, including
main/test scope, SDK21/25, execution authorization, stale build input and SDK
mismatch. RefactorKit also has three bounded public-wire Gradle Kotlin DSL
contexts: module dependency visibility in `build.gradle.kts`, `settings.gradle.kts`
`projectDir` remapping with provider-qualified source ownership and app main/test
classpaths, and a custom `integrationTest` source set selected with exact module,
source-set ID, source root and `:library/main` compile/runtime visibility. The
settings mapping is the one-scenario receipt at
`.refactorkit/runs/java-gradle-kotlin-dsl-settings-context-r001/`; it runs offline
on Gradle 9.6.1/JDK 21. The custom source-set scenario is recorded at
`.refactorkit/runs/java-gradle-kotlin-dsl-integration-source-set-r001/`; its public
context reports `kind=custom` rather than inferring test semantics from the name,
and proves sync leaves settings, build scripts and Java inputs byte-identical.
It does not execute Gradle test tasks. Both Kotlin DSL stories run offline on
Gradle 9.6.1/JDK 21. An IDE consumer separately reports one offline Gradle 9.8
multi-module project where `:app` sees `:library/main` and `:app:test` passes.
These are bounded context cases; they do not qualify the broader effective Kotlin
DSL matrix, script/toolchain combinations, native IDE mapping or the complete
refusal matrix. The separate Kotlin script-semantic work item
[`REQ-KOTLIN-MATRIX-001`](requirements/kotlin-advanced-shapes-and-platform-matrices.md)
tracks qualified script context, implicit receivers, script dependencies and Gradle
DSL references while retaining the current typed refusal. Those gaps remain
`actionable`; the full M2 gate is open. IDE import must consume
RefactorKit and build-tool context, demonstrate JDT LS SDK/classpath alignment, and
avoid maintaining a parallel semantic project model. The [ARC42 requirements
appendix](arc42/appendix-requirements.adoc) owns integrated status; this section
records the consumer acceptance contract.

The existing `REQ-JAVA-GRADLE-CONTEXT-001` composite identity now also has a
reported native IDE pass: the release/no-bundle GUI consumes the updated packaged
daemon on Gradle 9.8.0/JDK 21, resolves the included app as
`buildRoot=gradle/included-build`, `projectPath=:app`, directory `gradle/app`,
and passes Run, test discovery without executing test bodies, app/library coverage
with dependency-source navigation, and Debug to a source breakpoint. Source inputs
are restored and the GUI closes with no remaining process. This qualifies the
reported IDE story only; the exact loaded daemon path/version/hash and raw summary
payload remain `actionable` provenance gaps, and other versions/platforms remain
unqualified. The [ARC42 requirements appendix](arc42/appendix-requirements.adoc)
retains the integrated status and evidence boundary.


### IDE Java refactoring backlog, 2026-10-13

The IDE owner reports that daemon 0.7.0/API 0.2 now connects Java type/member
rename to the Monaco context menu, both native Linux GUI stories pass, and the
same-module cursor hierarchy path works. The IDE handoff is recorded in ARC42 IDE
§1.4.5. This is consumer acceptance evidence, not evidence that every Java
refactoring is managed by the engine. This backlog has priority over the C01-C17
roadmap.

The existing P1 `movePackage` provider/IDE item is owned by
[`java-package-migration.md`](requirements/java-package-migration.md). The
2026-10-21 consumer report found no stable route in the IDE and no direct Java
provider API; the experimental `RecipeEngine` step and the daemon's rejection of
Java `operation=recipe` did not provide one. The user-authorized 2026-10-21 follow-up
directed a bounded typed provider route under the existing package-migration/J1 gate,
without a duplicate requirement or arbitrary Java recipe execution.

Provider/API gate closed locally on 2026-10-22: daemon `refactor.preview` now accepts
`operation=movePackage` with `languageId=java`, `fromPackage`, and `toPackage` under
explicit SDK-bound Java context. Discovery reports `stable` / `managed-stable` /
`structural-parse`, and the SDK mutation-operation list includes the same operation.
The requirement-bound Story passes preview-with-staged-diagnostics, stale-snapshot
refusal, one managed transaction, exact-package main/test and `package-info.java`
migration, external/subpackage reference updates, generated-source preservation,
exact rollback, and generic recipe rejection. Local evidence is in
[`java-package-migration-provider-r001.json`](requirements/evidence/java-package-migration-provider-r001.json).
The historical J1 catalogue remains unchanged.

Consumer follow-up on 2026-10-22 reports that native scenario
`javaProjectModelPackageMoveNativeGui` now exercises the package-move entry,
preview/Apply, `mvn -o verify`, exact rollback to original inputs, and clean exit
on Linux with a two-module Maven project. This is consumer-reported evidence; its
ARC42/Story artifact and immutable receipt are not present in this repository, so
status remains `actionable` pending evidence identity and scope review. Gradle and
non-Linux qualification remain open. This IDE backlog continues to take priority
over C01-C17. Generic Java recipe execution remains unavailable.

The Java capability-truth P0 has bounded implementation and acceptance. The
current registration exposes twenty `STABLE`/`MANAGED_STABLE` mutation
operations, including the bounded `changeSignature.removeParameter` shape. Its
source-built daemon/MCP Story checks authority discovery, safe argument removal,
Apply and exact rollback; packaged/native qualification remains open. An older
consumer report observed a proposal-only package and is stale against this source.
Operation-specific Stories now qualify bounded `changeSignature.addParameter` and
`changeSignature.reorderParameters` daemon/MCP Apply authority in addition to the
original rename-parameter operation.
`extractMethod` is managed only when the plan carries JDT binding evidence for the bounded
contiguous-statement shape with one live-out; lexical fallback plans remain review-only and
PatchEngine rejects them before transaction creation. The custom LSP route remains experimental
and returns a client-managed proposal without RefactorKit transaction authority. The experimental
`java.moveAcrossMavenModules` project transformation retains its separate
Maven ownership route and is excluded from Java editor descriptors and the
SDK-bound semantic mutation catalogue. Java `availableRefactorings` descriptors and the SDK-bound
mutation catalogue now derive from this registration; descriptors carry the
registered capability, and `javaContextRefactoring.operationCapabilities`
publishes each canonical operation's stability, mutation authority, and evidence.
The compatibility `operations` array contains those canonical IDs only, without
the unnamespaced signature aliases. SDK-bound Apply rejects registered
`PROPOSAL_ONLY` plans before a transaction is created. Preview remains available;
the human-invoked CLI Apply path remains separate. The dirty-editor operation
list remains the exact four operations rename class/member, format, and organize
imports. Type hierarchy remains outside distributed discovery pending clean
packaged/native qualification. For the existing C-EXTRACT requirement, the JDT-bound managed
shape now extracts contiguous complete method-body statements, captures resolved enclosing
parameters, returns exactly one local used after the range, preserves the qualified conditional
branch, and propagates checked exceptions. Daemon and MCP acceptance verifies staged bindings and
diagnostics, Apply, and exact rollback. The legacy line-based no-argument `private void` preview
remains review-only and lexical-fallback evidence is rejected before managed writes. The IDE
consumer now forwards multi-statement selections with preceding-local inputs; provider-backed
native preview/Apply and per-surface authority remain actionable consumer work. Additional
local inputs/outputs and escaping-control-flow shapes remain open under the same requirement.

| Priority | Engine work | Current state and acceptance boundary |
|---|---|---|
| P0 | Reconcile Java support discovery | Implemented and accepted by `REQ-JAVA-CAPABILITY-TRUTH-001`: Java refactoring descriptors and SDK-bound canonical operation discovery derive from the same mutation registration; `operationCapabilities` adds per-operation stability, authority, and evidence while the legacy `operations` string array remains. Twenty-eight canonical operations are `STABLE`/`MANAGED_STABLE` in current source. The separately accepted `inlineVariable` cell is stable/managed only for a direct method-body local initialized by a resolved primitive-`int` numeric literal; its exact-binding preview, managed Apply, diagnostics, and rollback are bounded by `REQ-JAVA-INLINE-VARIABLE-LITERAL-001`. The registered set also includes the bounded JDT-proven `changeSignature.removeParameter` shape; no proposal-only Java mutation is currently registered. The reported IDE package discrepancies are tracked separately under the existing signature requirement. The canonical `changeSignature.renameParameter` operation has its own daemon/MCP preview, managed Apply, diagnostics, and exact rollback Story; `changeSignature.addParameter` and the bounded `changeSignature.reorderParameters` operation have separate operation-bound Stories for the same lifecycle. Reorder refuses side-effecting or unproven call arguments and method references before mutation. Native IDE run `run-YZaoYl` confirms the source-built 0.7.0 daemon advertises stable/managed-stable cursor-selected reorder but the GUI stops before calling `refactor.preview`: its synchronized project context is stale after Maven `target/**` outputs disappear. The provider did not refuse a preview request. Later native run `run-D1cB9o` completes the nine-step preview/Apply/verify/rollback flow; the IDE surfaces stale-context recovery and its adapter/controller tests pass. The actor removing outputs remains unknown, so keep IDE snapshot freshness actionable under the existing change-signature requirement; do not create a duplicate provider requirement. These engine operations do not qualify standard LSP `textDocument/rename` or custom LSP signature commands. `extractMethod` has a separate bounded C-EXTRACT Story for binding-backed selection and verifies that lexical fallback plans cannot be applied. `extractConstant` has its own narrowly bounded daemon/MCP Story described below; this does not establish LSP support. Aliases are not advertised through the SDK-bound catalogue, and daemon Apply refuses registered proposal-only or lexical-fallback plans before workspace changes or transaction creation. Preview remains available. The experimental Maven ownership project transform retains its separate Apply/rollback route and is not offered through Java editor descriptors or SDK-bound semantic mutation discovery. The dirty-editor list remains its exact four-operation managed set. Type hierarchy remains unavailable in distributed/release discovery until a clean releasable revision passes typed-capability and packaged/native qualification. Managed Apply is enabled only when the exact registered semantic operation identifier has `MANAGED_STABLE` authority; a family route, alias, descriptor or proposal preview is not Apply authority. |
| P0 | Bound Java adapter runtime | The IDE-priority daemon route publishes a 2 MiB full JSON-RPC response ceiling and replaces oversized responses with a bounded JSON-RPC error instead of truncating them; its six JDT semantic-read capabilities (`definition`, `hover`, `references`, `typeDefinition`, `typeHierarchy`, `callHierarchy`) publish the enforced 256 MiB aggregate Java-source input ceiling and `supportsCancellation=true`; JDT AST creation receives the cooperative progress monitor and cancelled analyses never enter the cache. The LSP and MCP stdio routes each advertise and enforce a 2 MiB whole-response ceiling, replacing oversized responses with bounded JSON-RPC errors that preserve request IDs; their synchronous Java paths remain non-cancellable. Oversized LSP diagnostics notifications become a bounded `window/logMessage` warning and are tested to ensure no partial diagnostic publication. The CLI process now buffers a combined 2 MiB UTF-8 stdout/stderr response and atomically discards it on overflow, returning `cli.outputLimitExceeded`; CLI help publishes the same limit. Hard timeout, process preemption, `requestTimeoutMillis`, process provenance, and `maxProcesses` remain unsupported/null; no in-process route claims them. Acceptance is `REQ-JAVA-RUNTIME-BOUNDS-001` in `features/java-runtime-bounds.feature`, including the daemon, CLI, LSP response, LSP notification, and MCP output scenarios; ARC42 records integrated status and receipts. |
| P1 | Qualify existing method/signature planners | Under the existing C-EXTRACT requirement, `extractMethod` now has a JDT-bound managed shape for contiguous complete method-body statements, resolved enclosing-parameter and preceding direct-local inputs, exactly one local live-out, conditional control flow, checked-exception propagation, staged binding/diagnostic validation, and daemon/MCP Apply with exact rollback. Writes to enclosing locals/parameters, nested-scope prior locals, unsupported bindings/types, multiple outputs, and escaping control flow still refuse. The engine capability is `STABLE`/`MANAGED_STABLE` for that qualified shape; lexical fallback remains review-only. The RefactorKit custom LSP command remains experimental and client-managed, with no transaction or rollback route. Native consumer acceptance remains separate. The canonical Java engine operations `changeSignature.renameParameter`, `changeSignature.addParameter`, bounded `changeSignature.reorderParameters`, and bounded `changeSignature.removeParameter` are independently qualified for daemon/MCP managed Apply by operation-bound Stories; their acceptance does not establish RefactorKit LSP `textDocument/rename` or custom LSP signature commands. Reorder supports only exact JDT-bound local/parameter reads and literals as call arguments; effectful/unproven expressions and method references refuse before edits, as do comments inside argument lists. The add-parameter Story covers a private JDT-bound method, its bound in-workspace call, staged diagnostics, Apply and exact rollback. `changeSignature.changeParameterType` now has operation-specific source-built Story evidence and is `STABLE`/`MANAGED_STABLE` only for its bounded method, risk-accepted override-family, and private-constructor shapes. Source-built package r003 advertises changeParameterType as stable/managed-stable. Native IDE run run-LPV1XM on r002 reached preview but Apply returned -32006 from the staging-identity defect; PatchEngine is fixed and core/Story tests pass. Native run run-Fx6jDM then passed saved-cursor preview, Apply, explicit rollback, and Maven verification on r003; broader platform/package qualification remains open under this existing capability row. `changeSignature.removeParameter` is `STABLE`/`MANAGED_STABLE` only for unused parameters whose discarded call arguments are literals or exact JDT-bound local/parameter reads; effectful or unproven values refuse before mutation. Its own receipt-bound Story covers daemon/MCP Apply and rollback; packaged/native qualification remains open. The BDD evidence must bind one operation/scenario and satisfy the §18.6 receipt and effective-glue inventory policy. This narrow qualification grants no sibling authority. These operations already have names and preview routes, so remaining signature work is a qualification/authority gap, not a new RPC-method request. |
| P1 | Add everyday extraction/encapsulation operations | `extractVariable` covers a complete JDT-bound subexpression in a direct method return or single local initializer under `REQ-JAVA-EXTRACT-VARIABLE-001`. The planner stages a typed local before the containing statement and checks evaluation order, bindings, and diagnostics. The return Story covers daemon/MCP Apply and exact rollback. The initializer Story covers daemon Apply and exact rollback, preserving the other bindings of the declaration and subsequent statements. Effectful or potentially throwing prefixes, conditional and short-circuit evaluation, null/poly expressions, unresolved bindings, and partial selections refuse. Other statement contexts remain unqualified. A separate LSP Story now qualifies one dirty-buffer client-managed preview for the same bounded direct-return subexpression shape; it grants no server-managed Apply authority. `extractConstant` has a separate bounded stable cell under `REQ-JAVA-EXTRACT-CONSTANT-001` for a complete primitive-`int` compile-time constant expression inside a top-level ordinary class; its separate LSP Story now qualifies a dirty-buffer client-managed preview through the immutable overlay, with two versioned edits and no server Apply or rollback. `extractField` has a separate stable/managed-stable primitive-`int` compile-time-expression cell under `REQ-JAVA-EXTRACT-FIELD-001`; its LSP Story qualifies the same two-edit preview shape. Other types, expression placements, and packaged/native IDE acceptance remain open. The first `extractParameter` cell under `REQ-JAVA-EXTRACT-PARAMETER-001` is limited to a complete primitive-`int` return expression `parameter + int-literal` in a private static one-parameter method and exactly one same-file direct call with a bound identifier argument; it validates staged bindings/diagnostics and covers daemon/MCP managed Apply and rollback. `encapsulateField` has a separate bounded stable cell under `REQ-JAVA-ENCAPSULATE-FIELD-001`: a JDT-bound public final, non-static instance field on a top-level ordinary class without custom inheritance becomes private with a public getter, and only binding-matched saved Java reads are rewritten. The exact source type is retained and checked against staged JDT bindings; managed daemon acceptance covers `int`, `String`, a parameterized reference type, and an array. Declaration annotations and array dimensions written after the variable name remain typed refusals, as do mutable/static/unresolved fields. Reflection or framework-string consumers and packaged/native IDE acceptance remain outside the engine cell. One exact dirty-buffer LSP scenario qualifies only the client-managed `encapsulateField` preview and grants no managed LSP Apply or rollback. A separate LSP Story now qualifies one dirty-buffer client-managed `extractParameter` preview for the exact bounded expression and unique same-file call site, returning three versioned edits without server Apply/rollback. Other extraction shapes and broader `extractParameter` shapes remain actionable. See [ARC42's integrated extraction boundaries](arc42/appendix-requirements.adoc#java-extractparameter-bounded-operation) and [the encapsulateField boundary](arc42/appendix-requirements.adoc#java-encapsulate-field-bounded-operation). |
| P1 | Complete inline and type extraction | `inlineVariable` now has a real JDT-bound planner and stable/managed capability for one local declaration fragment directly in an ordinary method body, initialized by a resolved primitive-`int` numeric literal. It substitutes only binding-matched reads, validates staged bindings and diagnostics, and the Story verifies preview, Apply, and exact rollback. Writes, no-read locals, multiple fragments, other initializers or scopes, ambiguous/recovered bindings, and declaration layouts that could lose comments refuse. This inlineVariable engine slice does not qualify a dedicated daemon/MCP or native IDE consumer flow. `inlineConstant` now has its own stable/managed JDT-bound planner and requirement-first daemon/MCP Story under `REQ-JAVA-INLINE-CONSTANT-001`: the first supported row is a private static final primitive-int field initialized by a direct numeric literal in a top-level class, with only binding-matched reads in its declaring compilation unit replaced. Staged bindings and diagnostics are checked; preview, managed Apply, exact rollback, and non-literal/mutable refusals pass. Its HIGH-risk preview warns that reflection or external consumers may observe removal. Public/external reads, computed expressions, dirty buffers, LSP, and packaged/native IDE consumers remain outside this cell. `inlineMethod` still refuses. `extractInterface` has a stable/managed JDT-bound operation and daemon/MCP Story for one public concrete instance method with primitive parameters/return type on a top-level class without existing hierarchy or type parameters. Its experimental `refactorkit.extractInterface` LSP command now has a separate dirty-buffer Story: with versioned `documentChanges` and `CreateFile` support it returns both the generated interface content and implementation edit without workspace writes, but has no managed Apply/rollback authority. Reference/generic/annotated/throws/varargs signatures, existing hierarchies, generated sources, and collisions refuse. Packaged/native IDE consumption remains unqualified. `extractSuperclass` now has a distinct stable/managed JDT-bound operation and exact Story for moving one public `int` method whose sole return expression uses only `int` parameters, numeric literals, parentheses, and primitive arithmetic into a same-package superclass. It stages the source move, proves the inherited JDT method binding, checks diagnostic deltas, and covers refusal plus daemon/MCP Apply and exact rollback. Stateful/call-dependent bodies, non-`int` signatures, existing class hierarchies, generated sources, and collisions refuse. Its HIGH-risk preview warns that reflection, declared-member enumeration, and public hierarchy consumers are not rewritten. This source-built cell does not qualify packaged/native IDE consumers. Its experimental custom LSP route separately previews one dirty-buffer shape as a client-managed edit; this does not provide managed LSP Apply/rollback or packaged/native IDE acceptance. See [ARC42’s inlineConstant boundary](arc42/appendix-requirements.adoc#java-inlineconstant-bounded-operation), [extractInterface boundary](arc42/appendix-requirements.adoc#java-extract-interface-bounded-operation), and [extractSuperclass boundary](arc42/appendix-requirements.adoc#java-extract-superclass-bounded-operation). |
| P1 | Implement hierarchy member moves | `pullUpMember` and `pushDownMember` have separately bounded source-built cells; the first source-built `moveMember` cell now supports one exact saved-JDT-bound package-private static method moved between two same-package top-level ordinary classes in one compilation unit and one available non-generated module source set. The method must be unannotated, no-argument, non-generic, return primitive `int`, declare no exceptions, and contain only one compiler-resolved numeric `int` literal return; the types have no explicit hierarchy, generics or interfaces, and the target has no same-named method. Every in-workspace reference must be a complete direct invocation in that source set. Method references, external/binary/reflection/framework-string/resource consumers, unsupported bodies or signatures, recovered bindings, collisions, and cross-source-set/module/JPMS moves remain outside the cell and refuse or are disclosed as risk. The planner stages an exact preview, rebinds from a temporary diagnostics overlay, requires HIGH-risk approval, and proves daemon Apply, Java 8 compilation, and byte-exact rollback. Its one-scenario acceptance is `REQ-JAVA-MOVE-MEMBER-001` in [ARC42](arc42/appendix-requirements.adoc#java-move-member-bounded-operation), with receipt and effective-glue inventories under `.refactorkit/runs/java-move-member-bounded-r001/`; it reuses the existing hierarchy runner and glue. This source-built result updates the earlier 0.7.0/API 0.2 catalog observation; packaged/native IDE capability discovery and a consumer-driven selected-member preview/Apply/verify/rollback remain actionable. Broader member kinds, separate compilation units, cross-file moves, constants, and expanded consumer closure remain unimplemented. No new JSON-RPC method is introduced. |
| P1 | Complete Java semantic navigation | `callHierarchy` remains partial with `scope: workspace-direct-invocations`; dynamic dispatch, method references and non-workspace source remain outside it. `REQ-JAVA-SEMANTIC-NAVIGATION-001` now adds `intelligence.query` kind `implementations`, resolving source type descendants through exact JDT type bindings and method implementations through exact override bindings. It returns only concrete workspace-source declarations with explicit `targetKind`, scope, completeness, warnings and truncation; recovered targets refuse. JDT also initializes the running JVM system library for source-only snapshots with no build roots/classpath, so `java.lang` bindings remain available. Both source-only and implementation lookup Stories pass locally. RefactorKit's LSP now advertises Java `textDocument/implementation` and maps requests on dirty Java buffers to compiler-bound workspace type and override locations; the shared runner covers the request with an unsaved overlay. Packaged IDE consumer acceptance remains actionable. This is separate from `typeHierarchy` and direct call hierarchy and does not claim external/runtime implementations; no new JSON-RPC method is needed. |
| P1 | Add typed Spring/Jakarta/JPA reference evidence | `JavaFrameworkDetector` remains a lexical annotation-warning scanner. The first source-built provider cell now adds exact property-token edits for direct, escape-free Spring Data `@Query` JPQL literals with one uniquely identified entity root, alias and direct property path. Java `PatchPlan` and daemon JSON expose typed `PlanReferenceAnalysis` evidence (reference kind, target owner/member, source location/resource kind, provenance, completeness). The result is explicitly `INCOMPLETE`, `completeProject=false`, and `managedWriteAuthority=none`; the preview is HIGH risk and the central `PatchEngine` refuses managed Apply. Story `REQ-JAVA-RENAME-FIELD-CHAR-001#a-static-spring-data-jpql-property-reference-is-included-in-an-entity-field-rename-preview` passes one scenario/four steps, including exact query ranges and pre-mutation Apply refusal; local evidence is under `.refactorkit/runs/java-framework-query-reference-r001/`. Static-query syntax outside this narrow shape, dynamic/unresolved values, JPA `@NamedQuery`, explicit entity/property mappings, XML/YAML/properties, migrations, generated metamodels, complete consumer closure, and native IDE acceptance remain actionable. IDEs must consume provider evidence and must not maintain a parallel framework model; existing annotation warnings do not close the remaining gap. |
| P1 | Add requested delegate and signature-shape transformations | `makeStatic` now has a bounded first-party path, advertised as `STABLE`/`MANAGED_STABLE` with compiler evidence and a HIGH-risk descriptor, and is available through the Java adapter and daemon preview/apply/rollback using an exact JDT-bound method symbol. It accepts only a private, unannotated, unsynchronized method with a body in a top-level ordinary class; its body must not depend on `this`, `super`, implicit instance fields, or implicit instance methods, and call sites must be implicit calls from the declaring class. It refuses recovered/unresolved analysis, generated source, method references, explicit or cross-class call sites, and unsupported declaration shapes. Preview validates staged JDT bindings and diagnostics and warns that reflection may observe the static change. The acceptance Story covers preview, capability metadata, diagnostics, managed apply, and rollback. RefactorKit's native Java LSP code action now returns a standard versioned, client-managed `WorkspaceEdit` for planner-eligible methods and a disabled action for refused shapes; a two-case Story verifies both paths. The managed preview/apply Story now verifies an exact saved SDK-backed `mutationSelection` claim end to end: JDT `METHOD` admission, selector-hash binding into the plan lease, staged diagnostics, Apply, and exact rollback. This closes the RefactorKit provider whitelist rejection only; it does not demonstrate that the consuming IDE supplies the claim or qualify packaged/native Apply. Packaged/native IDE consumer acceptance remains `actionable`; see [ARC42's integrated saved-selection boundary](arc42/appendix-requirements.adoc#java-makestatic-saved-jdt-selection). `delegateMethod`, `introduceParameterObject`, and conversion to an instance method remain unregistered candidates requiring separate bounded semantic contracts and independent acceptance.
| P1 | Add bounded visibility/type repair and batch cleanup | `changeVisibility` now provides two accepted stable/managed, compiler-evidence cells: a final private method on an ordinary owner and a private non-final method on a final owner. Both cover daemon/MCP preview, Apply, diagnostics, exact rollback and selected-binding preservation; a non-final method on an open owner refuses. The declaring type must be one top-level ordinary class without a declared superclass, interfaces or type parameters; annotations and generated sources refuse. Instance methods must be final (or their owner class final), while static methods are admitted directly, so widening does not open an unproven override-dispatch path. The HIGH-risk preview warns about external and reflective API expansion; public/no-op targets and unresolved bindings refuse. The separate experimental `refactorkit.changeVisibility` LSP Story now proves one dirty-buffer client-managed preview for a final private method; this does not add managed LSP Apply/rollback or qualify packaged/native IDE consumption. `batchCleanup` is now stable/managed for a first low-risk composition cell: 2–16 independent `organizeImports`/`formatFile` actions on distinct saved Java source files become one preview, staged-diagnostics check, daemon/MCP managed Apply and exact rollback. The whole batch refuses stale expected snapshots, duplicate/overlapping targets, unsupported operations, high-risk constituents, invalid child plans, out-of-target edits or diagnostic regressions. This does not qualify native IDE UI or broader semantic action composition. `changeType` now has one stable/managed compiler-evidence cell under `REQ-JAVA-TYPE-REPAIR-001`: an exact saved-source JDT `FIELD` selector supplies its declaration path and binding key, and Java context must prove one available effective source-set owner. The planner changes only one mutable, unannotated, non-static private `int` field to `long` on a top-level ordinary class without an explicit hierarchy or type parameters; same-named compilation-unit occurrences require complete JDT bindings, staged declaration identity must remain bound, and diagnostics may gain no errors. Unsupported or incomplete shapes refuse before writes. The HIGH-risk preview warns that external/reflection consumers are not rewritten; selected daemon Story coverage includes preview, Apply, diagnostics, exact rollback, public-field refusal, and source-set-authority refusal. The LSP `workspace/executeCommand` surface returns one versioned client-managed edit and keeps no managed plan or transaction. Its two selected Stories now cover an unchanged saved buffer and an unsaved edit elsewhere in the open Java overlay; the dirty-buffer case proves the cursor selection and returned edit remain bound to the exact current document version, while saved workspace bytes remain untouched. Both selected scenarios pass. Packaged/native IDE acceptance, general type repair, and broader hierarchy or call-site closure remain actionable. See [ARC42](arc42/appendix-requirements.adoc#java-explicit-field-type-repair). Do not infer repairs from IDE diagnostics or create a parallel IDE semantic model. |
| P2 | Qualify build/project/module transformations (Maven and Gradle) | `java.createMavenModule` is stable on the daemon project-transformation surface: its preview validates one safe contained child, derives child coordinates from the selected parent POM, stages the full offline Maven reactor, compares existing modules and diagnostics, and carries a managed authority lease through Apply and normal rollback. Its lifecycle Story verifies `mvn -o verify` in a disposable copy and exact input restoration. Native IDE Add Module acceptance remains open and actionable for the IDE consumer. `java.createGradleModule` is stable on the source-built daemon API 0.2 route under `REQ-JAVA-GRADLE-MODULE-CREATE-001`: explicit sync is required; the provider binds the workspace-relative build root, canonical nested project path and settings-inferred DSL; the preview appends one include and creates one matching Gradle build script; managed Apply invalidates the previous synchronized model and normal rollback restores the exact original snapshot. Its Kotlin/Groovy two-case Story passes on Gradle 9.6.1/JDK 21. This qualifies the provider route only; installed IDE distribution and native GUI acceptance remain `actionable`. The 2026-10-24 documentation pass reconciles `docs/java-project-context-protocol.md`, `docs/api-0.2-contract.json` and `docs/releases/v0.8.0-progress.md` with current local status; the integrated ARC42 record remains the status authority. The CLI command remains CLI-v2 JSON `catalog-only` and experimental; daemon qualification does not promote its separate CLI contract. `java move-across-maven-modules` has an internal planner and CLI route and remains CLI-v2 JSON `catalog-only`. `java rename-module` remains experimental and is separately bounded through direct apply/rollback, human CLI `--apply`, daemon/MCP managed apply, LSP client-managed proposal and managed LSP public dispatch under `REQ-JAVA-MAVEN-MODULE-RENAME-001` and SURFACE-001 through -005. The CLI JSON result is preview-only; broader package/native/external-transport/IDE/general-Maven/release qualification remains open. A consumer-reported offline Maven lifecycle between module-rename Apply and rollback is recorded under the same requirement: `mvn -o verify` created `core/target/**`, normal rollback was refused until those outputs were removed, then rollback and reactor verification succeeded. This observation does not attribute a cause or broaden qualification. The first Gradle path-rename capability, `java.renameGradleModule`, is experimental under `REQ-JAVA-GRADLE-MODULE-RENAME-001` on the source-built daemon route: it consumes an exact synchronized current Gradle model and Java SDK context; only one unconditional literal include without existing `projectDir` mappings is supported; the project keeps its existing physical directory; modeled inbound project dependencies, conditional/dynamic settings, collisions and stale authority refuse. The settings-only preview is managed through Apply and rollback, invalidates the former Gradle model until an explicit sync, and has four passing Kotlin/Groovy preview/rollback and refusal examples on Gradle 9.6.1/JDK 21. This does not qualify the installed IDE distribution or native GUI. Gradle relocation, cross Maven/Gradle model changes, dependency and source-set transforms, and full multi-module/mixed-build staged-model comparison remain open in the same P2 scope. RefactorKit owns planner/API/capability truth; IDE task and project presentation stay consumer-owned. These planners do not rerun Gradle after the explicit caller-authorized synchronization. |

API impact: the generic preview/apply protocol can remain the transport; this
backlog does not call for a new JSON-RPC method. `extractVariable`,
`extractConstant`, and `extractField` have distinct operation identifiers and typed saved-source
selection contracts in `docs/api-0.2-contract.json`. The remaining operations need distinct identifiers
where absent, typed argument and selection schemas, and capability metadata that
states evidence, stability, and mutation authority.
Existing identifiers for inline and hierarchy refusal routes can be retained
while their implementations are qualified. Existing experimental method and
signature identifiers also remain unchanged. Any new operation intended for a
dirty/versioned editor buffer must additionally be admitted by the existing
editor-preview contract; saved-disk support does not imply dirty-buffer support.
The runtime fields needed to report Java execution bounds already exist in
capability schema v1; the missing work is honest values plus enforceable runtime
controls, not a new RPC method. RefactorKit remains frontend-neutral: Monaco menus
and GUI wiring stay with the IDE consumer. JDT LS refactor actions are likewise
consumer-owned and do not count as RefactorKit operation or semantic-reference
acceptance; IDE task/action/presentation mapping must fail closed when engine
capabilities are unavailable.

Implementation slices must follow the repository's Story BDD / Spec BDD policy
and the one-scenario receipt and glue-inventory rules. This backlog records
planned work, not runtime capability claims. The [ARC42 requirements appendix](arc42/appendix-requirements.adoc)
remains the integrated authority.


### Explicit SDK Java and effective Gradle integration

The [Java context protocol](java-project-context-protocol.md) defines `project.context`,
SDK-bound queries/preview/apply, and explicit evaluated Gradle `project.sync`.
`javaContextRefactoring.operations` lists backend-admitted SDK routes; its rollback
flag is true and requires original SDK/context plus exact committed S1. Explicit Gradle sync evaluates authorized build code with offline
dependency resolution; normal discovery remains read-only and partial for Gradle.
Use its returned snapshot to acquire the context. These contracts are frontend-neutral.
This bounded integration satellite links to the central
[ARC42 requirements appendix](arc42/appendix-requirements.adoc).


Java SDK queries can consume bounded immutable editor documents through
`intelligence.query`; the [context protocol](java-project-context-protocol.md) defines
base/provider/overlay hashes and document version receipts. SDK mutation remains saved
snapshot only, with dirty mutation explicitly false and no implicit save. This bounded
satellite links to [ARC42](arc42/appendix-requirements.adoc); editor-mediated versioned
dirty apply/undo/recovery remains actionable.

Saved Java mutation selection uses SDK-bound `intelligence.query` kind
`mutationSelection` and `java-mutation-selection-v1`. Pass the exact returned object
as `javaSelection` to preview; pending apply retains its hash. The consumer must
revoke this saved authority when its editor content changes. The
[protocol](java-project-context-protocol.md) owns parameters and receipts;
[ARC42](arc42/appendix-requirements.adoc) records native qualification and resolution
of the Runner rollback/Gradle filename lookup gaps. Dirty mutation remains false.

`refactor.editor.preview` supplies a closed SDK dirty preview and versioned
`workspaceEdit.documentChanges`, including source-file rename, with exact plan/base/
overlay hashes. Every affected existing source must be included as a versioned buffer.
The [protocol](java-project-context-protocol.md) owns its bounded schema and retention.
Ordinary disk apply does not accept these plans; editor-mediated prepare/confirm/undo/
recovery and dirty cursor selection remain actionable. The saved SDK apply/rollback
consumer and Linux GUI checks are reported GREEN by the IDE agent for SDK21/25;
overlay/selector/shared-Gradle integration is still open. This satellite links to
[ARC42](arc42/appendix-requirements.adoc) for integrated authority; full M2 remains open.


Dirty Java `mutationSelection` uses `java-editor-mutation-selection-v1` around the
exact compiler selector. Supply the same complete captured overlay and pass the
wrapper as `javaEditorSelection` to `refactor.editor.preview`; the backend derives
the symbol and binds `selectionHash` into the plan identity. Revocation includes
versions of all documents, even unchanged content. The
[protocol](java-project-context-protocol.md) owns executable response schema links
and parameters; [ARC42](arc42/appendix-requirements.adoc) owns qualification status.
Editor-mediated prepare/confirm/undo/recovery remains actionable, mutation false.
The IDE agent reports dirty definition/references/F12 qualified on Maven SDK21/25;
shared effective Gradle, selector/preview consumer integration and SDK propagation
remain open. The workspace-local package does not update the installed provider.


Experimental editor transaction prepare/confirm and inverse APIs are now backed by
checksummed durable receipts. `refactor.editor.transactions` discovers transactions
after restart; `refactor.editor.recover` validates original SDK/C0, exact current
saved evidence and captured buffers before returning a guarded inverse. The editor
owns version-checked text edits and non-overwriting resource rename; it preserves
original saved bytes and avoids autosave. Unknown content or corrupt authority refuses
overwrite. Gradle recovery requires explicit re-sync in the same daemon. The
[protocol](java-project-context-protocol.md) owns bounded schemas/retention and
[ARC42](arc42/appendix-requirements.adoc) owns native SDK21/25 backend qualification.
Final dirty capability admission and independent consumer/GUI acceptance remain
actionable. Runtime method metadata now agrees with the API catalogue.


Current backend editor admission is complete for `renameClass`, `renameMember`,
`formatFile` and `organizeImports`. Opt in with `editorMutationMode: durable-editor-owned`
on editor preview to receive `applyAvailable: true`; default v1 preview retains its
read-only shape. The consumer must implement the complete version-guarded prepare/
confirm/inverse/discovery/recover contract, preserve original dirty buffers and
original saved bytes, and avoid autosave or ordinary disk-apply fallback. 442 selected
backend tests and 16 native SDK21/25 Maven/Gradle roundtrips qualify this package.
[Protocol](java-project-context-protocol.md) owns exact schemas/retention boundaries;
[ARC42](arc42/appendix-requirements.adoc) owns integrated status. IDE read-only native/
GUI SDK21/25 acceptance is reported GREEN, while editor mutation and shared Gradle
consumption remain independent work. The local package does not upgrade the installed
provider; full M2/release remains open.

Java formatting also accepts an IDE-selected Eclipse profile on saved and dirty
`formatFile` previews. Send the exact profile path, selected name and XML; the returned
profile identity hash remains separate from the project snapshot. Saved Apply requires
that hash, while editor-owned `prepare` and `confirm` require it alongside the bound
editor plan hash. Durable editor receipts retain the profile identity across restart;
the editor still owns dirty-buffer writes and undo, and `.settings` is not rewritten.
The two acceptance scenarios and dirty transaction/restart evidence are documented in
[ARC42](arc42/appendix-requirements.adoc#javaformatterprofile), with the selected run
receipt under `.refactorkit/runs/java-formatter-profile-editor-r001/`. IDE consumer
deployment remains separate from this workspace-local provider implementation.


## C readonly SDK query result paths

Consumers of C daemon/MCP/CLI symbol queries may configure `readOnlySdks` using
[the shared SDK input contract](daemon-protocol.md#explicit-c-sdk-inputs-for-managed-previews).
SDK-context results add `sdkInputsIdentity` and per-location `uri`,
`sourceOwnership` (`SDK`/`WORKSPACE`) and `readOnly`. Workspace `file` remains
relative; SDK `file` is absolute. SDK locations carry `sdk.root`, `sdk.identity`,
`sdk.provenance` and `sdk.license`, with provenance/license declared by the caller.
Use the URI for navigation and preserve SDK readonly ownership; these results do
not grant engine write authority. Internal SDK logical names are never returned.

Repeat SDK configuration and tool paths on subsequent definition/reference calls.
Re-search after SDK changes: prior SDK IDs become `c.navigation.symbolNotFound`,
while missing SDK inputs refuse instead of producing successful zero references.
The workspace snapshot alone does not certify external SDK currency.

Native daemon/MCP/CLI cases qualify SDK typedef/field results and two captured
workspace TUs; direct SDK document cursors and other families/configurations/
platform layouts remain `actionable`. [Protocol details](daemon-protocol.md#c-readonly-sdk-query-locations).
[ARC42 owns integrated acceptance](arc42/appendix-requirements.adoc#c04-sdk-queries-2026-11-15).
Evidence: `.refactorkit/runs/c04-sdk-queries-r001/archive-audit.json` and matrix.
Full original C02-C17/release remain open; installed private runtimes retain older C.


## C workspace cursor navigation to SDK locations

Set `initializationOptions.c.readOnlySdks` to the shared bounded SDK array beside
explicit `clang`, `clangd`, `clangFormat` paths. Standard LSP workspace symbols
and definition/reference requests use real SDK header file URIs. A saved workspace
cursor on an SDK typedef/field can resolve its SDK definition; an SDK-prototyped
function resolves its workspace body. References with/without
`context.includeDeclaration` preserve SDK/workspace locations. Document outline
remains in its workspace source. These reads preserve SDK ownership and grant no
write authority; the standard LSP Location shape contains URI/range.

Six native C11/C17 cases pass 24 steps. Existing production is unchanged, with
LspSession23 and seven prior native/selection controls passing 28 steps; C726 is
retained by exact relevant input/archive proof. Direct SDK document cursors,
other families/configurations/platform layouts and editor drift/refusal envelopes
remain `actionable` alongside full original C02-C17/release.
[ARC42 owns integrated acceptance](arc42/appendix-requirements.adoc#c04-sdk-lsp-queries-2026-11-15).
Evidence: `.refactorkit/runs/c04-sdk-lsp-queries-r001/archive-audit.json`,
`sdk-lsp-query-matrix.json` and `retained-c726-inputs.json`. Installed private
runtimes contain older C code; no independent acceptance/install/publication follows.


## MCP typed SDK tool errors

When `tools/call` catches a typed `JsonRpcException`, CallToolResult retains
`isError: true` and its existing text item, adding `structuredContent.error` with
`code`, `message` and optional original `data`. Malformed SDK JSON from C query
and preview calls exposes INVALID_PARAMS (-32602),
`data.refusalCode = "c.sdkConfigurationInvalid"` and the precise `data.field`.
Use those fields for machine decisions. The outer JSON-RPC response retains
its request ID and successful dispatch result; the tool result indicates failure.
Existing non-RPC failure projection and Apply RPC exception handling remain unchanged.

```json
{"isError":true,"content":[{"type":"text","text":"Error: c.sdkConfigurationInvalid: invalid readOnlySdks in readonly SDK configuration"}],"structuredContent":{"error":{"code":-32602,"message":"c.sdkConfigurationInvalid: invalid readOnlySdks in readonly SDK configuration","data":{"refusalCode":"c.sdkConfigurationInvalid","field":"readOnlySdks"}}}}
```

Six source-child NDJSON cases pass 24 steps after six semantic failures: null,
object, nine roots, non-object entry, missing license and non-string license
on C search/definition/references/preview. Exact snapshots/IDs, compatible text,
input inventory, subsequent ping and clean EOF/child closure pass. These errors
occur before unavailable toolchain discovery; they do not qualify C compiler
semantics or a packaged artifact. Fresh MCP32 and 14 prior SDK/query/preview/
source-daemon-crash/selection cases pass 55 steps; C726 is retained with exact
relevant input/archive proof. [ARC42 owns integrated acceptance](arc42/appendix-requirements.adoc#c16-mcp-sdk-errors-2026-11-15).
Evidence: `.refactorkit/runs/c16-mcp-sdk-errors-r001/archive-audit.json`,
`retained-c726-inputs.json`, red/qualified wire JSON and XML/Cucumber archives.
Other malformed SDK/policy/budget and language error matrices, packaged integration
and full original C02-C17/release remain `actionable`; private runtimes retain
older C, without new independent acceptance/install/publication.
