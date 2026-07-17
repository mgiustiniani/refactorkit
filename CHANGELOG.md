# Changelog

All notable changes to RefactorKit are tracked here.

This project records released scope and known limitations so alpha users can
review safety boundaries before applying refactorings.

## Unreleased

### Next development (`0.7.0-SNAPSHOT`)

- Expand Kotlin/JVM `organizeImports` to FIR-proven source/external type aliases.
  Alias-to-identity evidence now covers external constructor, qualifier and type
  uses; unused aliased imports may be removed while retained aliases are sorted
  and preserved byte-for-byte.
- Route bounded Kotlin/JVM `organizeImports` through daemon API `0.2`, CLI and MCP
  with semantic lease/snapshot/index authority and packaged preview/apply/rollback
  acceptance.
- Add bounded Kotlin/JVM organize-imports library planning for explicit type
  imports. Complete K2 usage evidence distinguishes import tokens from real uses,
  removes only proven-unused directives, sorts retained lines, preserves CRLF,
  recompiles staged overlays and rolls back byte-exactly.
- Allow any compiler-proven public sibling to lead a whole-file move while
  preserving the original filename; the selected declaration name never infers a
  file rename.
- Support exact fully-qualified Kotlin/Java consumers for multiple public sibling
  types. Each old identity is joined to every compiler-proven final type token;
  partial or mixed qualified/import shapes refuse.
- Support compiler-proven same-package implicit Kotlin/Java consumers for
  multiple public sibling types. One sorted destination-import block is inserted
  after the exact package declaration while preserving newline/semicolon style.
- Support exact Kotlin/Java package-star consumers for multiple public sibling
  types. The old star remains and one sorted explicit destination-import block is
  inserted for all compiler-proven moved identities; mixed/static/malformed star
  shapes still refuse.
- Preserve FIR-proven Kotlin aliases for consumers of additional public sibling
  types during whole-file moves. Java remains exact-explicit-only; sibling star,
  same-package, qualified and malformed/multiple-alias shapes refuse.
- Move whole Kotlin files containing additional compiler-proven public top-level
  types when every Kotlin/Java consumer uses exact explicit non-aliased imports.
  All public identities, usages, destination conflicts and staged K2/JDT evidence
  are validated as one file-level move; other consumer shapes refuse.
- Move whole Kotlin files containing one public target plus compiler-proven
  private top-level helper types, functions or properties. FIR/JVM owner evidence
  rejects non-private or ambiguous declarations, and staged K2 identities verify
  package and descriptor changes for every moved helper.
- Support compiler-proven Kotlin and Java package-star consumers during bounded
  `moveDeclaration`. The old star import remains unchanged and one exact
  destination-type import is inserted after it, preserving LF/CRLF and Java
  semicolon conventions; multiple, static and malformed shapes still refuse.
- Add compiler-proven Kotlin import-alias usage evidence and preserve exact alias
  tokens during bounded `moveDeclaration`. FIR-resolved imports bind alias text to
  source identities; malformed locations and collisions fail with typed codes.
- Allow bounded Kotlin/JVM `moveDeclaration` for public types with no
  in-workspace consumers. K2 must still prove the staged destination declaration,
  external-consumer risk remains explicit and the file move stays transactional.
- Allow bounded Kotlin/JVM `moveDeclaration` consumer sets to be independently
  Kotlin-only or Java-only instead of fabricating a cross-language requirement.
  Staged validation conditionally proves every consumer set that existed before
  planning and still requires at least one compiler-proven consumer overall.
- Expand bounded Kotlin/JVM `moveDeclaration` to exact fully-qualified Kotlin
  and Java type uses. Every rewritten final type token is independently K2/JDT
  bound, all textual old identities must correspond exactly to proven ranges,
  and mixed imported/qualified or partially-qualified shapes refuse.
- Expand bounded Kotlin/JVM `moveDeclaration` to compiler-proven same-package
  Kotlin and Java consumers. The planner inserts one deterministic explicit
  destination import after the exact package line, preserves LF/CRLF, refuses
  star/alias/conflicting/qualified shapes, and revalidates staged K2/JDT identity.
- Package a standalone `refactorkit-mcp`/`.bat` launcher in the embedded-runtime
  distribution and qualify Kotlin move semantic start, preview, apply and
  rollback through its real stdio protocol.
- Add the first managed Kotlin/JVM `moveDeclaration` row for one public top-level
  type in an authoritative source set. K2/JDT prove Kotlin and Java consumers,
  explicit imports and the package token are updated exactly, the file remains
  in the same source set, and one diagnostics-gated `PatchEngine` transaction
  supports byte-restoring rollback. CLI, daemon and MCP routes are additive.
- Retry one transient non-zero K2 worker exit inside the original 30-second
  aggregate deadline. Timeouts, output/protocol failures and a repeated exit
  remain fail-closed, and no attempt publishes partial semantic evidence.
- Normalize K2 PSI offsets back to saved CRLF source offsets before validating
  compiler symbol and usage ranges. Windows no longer loses exact symbol
  evidence after a CRLF-preserving source rewrite.
- Add the symmetric public Java-method rename across direct Kotlin callers. JDT
  proves one public non-overloaded non-override method, bounded ECJ supplies its
  hash-bound binary owner, and K2 publishes exact external callable tokens. Java
  and Kotlin edits use staged ECJ/K2/JDT diagnostics and one rollbackable plan.
- Add managed public Kotlin-function rename across direct Java callers. Durable
  owner/name/descriptor evidence selects the non-overloaded function, K2 supplies
  exact Kotlin declaration/calls, and JDT binds Java invocations against the
  ephemeral K2 classes. Overrides, operator/infix shapes, callable references,
  dynamic/generated/incomplete evidence and unaccepted external consumers refuse.
- Add the symmetric public Java-type rename used by Kotlin. A bounded external
  ECJ process disables annotation processing, compiles the exact immutable Java
  snapshot into an overlay, packages deterministic hash-bound ephemeral classes,
  and lets K2 report exact external JVM type/constructor usages. Preview updates
  JDT-proven Java tokens, K2-proven Kotlin tokens and the Java filename; staged
  ECJ/K2/JDT diagnostics, one `PatchEngine` transaction and byte-exact rollback
  are qualified locally and in packaged acceptance.
- Add the first shared-JVM public-type preview foundation in the new
  `refactorkit-jvm` module. Exact classes emitted in the disposable K2 overlay
  are supplied to JDT as an ephemeral classpath; JDT binding uses retain the
  qualified binary identity and compose with exact Kotlin tokens. Public preview
  requires `acceptExternalConsumerRisk=true`, re-runs both compilers on the staged
  snapshot and refuses dirty, dynamic, generated or incomplete evidence. The
  Kotlin CLI exposes `--accept-external-consumer-risk`. API `0.2` JDT entry points
  and the legacy binding-use constructor remain available.
- Route every Gradle test JVM to bounded repository-local `build/test-tmp` storage
  so a saturated host `/tmp` cannot turn semantic acceptance into false failures.
- Make every open K5, T5, J1 and I1 row release-blocking for closure of the
  `0.7.x` band. The completion contract preserves API `0.2`, requirement-first
  slices, compiler/provider authority, managed transactions and stable refusal;
  parallel Kotlin, TypeScript/JavaScript and Java/Maven work is no longer optional.
- Define the K4 managed Kotlin/JVM rename contract. The first implementation row
  is deliberately limited to compiler-attested private source types in a
  Kotlin-only module and requires a complete reference barrier, exact staged K2
  diagnostics, normalized preview, explicit authorization, `PatchEngine`, WAL,
  recovery and rollback. Existing partial K3 references grant no mutation
  authority; broader declaration families and Java interoperability remain
  mandatory before K4 completion. The first internal implementation step extends
  the strict atomic worker payload with compiler-PSI declaration visibility and
  validates it host-side for every published type/function symbol. A library-level
  bootstrap planner now previews private-type declaration/constructor/type/import/
  qualifier token edits, refuses incomplete Java/alias/star/type-alias/dynamic
  boundaries, simulates the post-image and rejects introduced K2 errors. Daemon,
  CLI and MCP now preserve exact lease/snapshot/generation authority through
  preview and explicit apply; `PatchEngine` diagnostics gating, WAL and rollback
  are exercised by local packaged acceptance. Capability metadata remains
  `PROPOSAL_ONLY`; the bounded private-declaration K4 foundation passed packaged
  apply/rollback plus shared kill recovery on Linux, Windows x86-64, macOS x86-64
  and macOS arm64 in GitHub Actions run `29451134010`. Public/incomplete shapes
  remain typed refusals rather than inheriting this authority. The same
  planner now accepts private non-overloaded functions when all uses are proven
  direct K2 call names; callable references/imports and operator/infix/override
  shapes refuse rather than inheriting partial-reference authority. Targets or
  usages in generated source roots refuse before planning. Direct
  field-backed properties now receive `kotlin-jvm-property-v1` IDs from JVM
  owner/name/field-descriptor evidence; K2-resolved reads/writes feed the same
  private-declaration rename planner. Delegated/constructor properties and
  callable references remain refused. Value parameters of supported functions now
  receive `kotlin-jvm-parameter-v1` IDs from callable owner/name/descriptor plus
  ordinal (not source offset or parameter name); resolved parameter reads feed
  managed private-function parameter rename. Mixed Java/Kotlin snapshots now hit
  the stable `kotlin.renameCrossLanguageIncomplete` refusal before visibility or
  lexical planning; a Java source that names a public Kotlin type is covered. Function type parameters receive a
  separate owner/callable-descriptor/ordinal `kotlin-jvm-type-parameter-v1` ID.
  A dedicated first FIR pass binds exact type-parameter symbols before resolved
  type references are collected, enabling staged private-function type-parameter
  rename without lexical matching.
- Add saved-snapshot Java `definition`, bounded `references`, and typed `hover`
  queries backed only by Eclipse JDT bindings. Hover returns the exact selected
  range, binding-derived Java signature, qualified identity, bounded Javadoc,
  and warning-derived completeness. References support explicit declaration inclusion,
  total/returned counts, deterministic ordering, warning-derived completeness and
  truncation at 1,000 locations. A two-entry session-owned cache is keyed by the exact project snapshot,
  capped at 10,000 Java sources, cooperatively cancellable between compilation
  units, and cleared across workspace sessions. Qualified JDT symbols publish a
  bounded `java-jdt-bindings-v1` semantic partition with path-free provenance;
  no lexical fallback receives semantic authority.
- Add bounded recursive saved-file watching and explicit `workspace.refresh` /
  `workspace.watch.status`. Snapshot reconciliation reports added, modified and
  deleted sources, retains unrelated language partitions, rebuilds invalidated
  Java declarations, removes invalid provider partitions and invalidates all
  snapshot-bound semantic leases without writing workspace metadata. Watcher
  failure or directory overflow falls back to fail-closed refresh-before-request.
- Add barrier-aware daemon scheduling for semantic reads. Interactive, normal and
  background queries reorder only between FIFO stateful/control barriers.
  `intelligence.cancel` cancels queued or active requests by semantic request ID;
  active LSP reads receive `$/cancelRequest`, retain the lease when acknowledged,
  and stop the provider when cancellation is not acknowledged within 250 ms.
- Add the first centralized `WorkspaceIndex`: an immutable, snapshot-bound,
  session-owned inventory of every recognized source plus bounded provider symbol
  partitions. Daemon `index.status` and experimental typed `intelligence.query`
  expose workspace/document symbol search; CLI `index` and `intelligence search`
  provide human output or optional JSON. TypeScript/JavaScript completion, hover,
  and signature help are provider-backed; position-based navigation and other
  language/provider rows remain explicit typed refusals.
- Add the language-neutral immutable editor-overlay foundation: exact saved
  snapshot, document path/version/content-hash authority, deterministic overlay
  hash, in-memory provider snapshot derivation, common semantic-query correlation,
  and bounded path/language/content validation. TypeScript `diagnostics.v2` now
  uses this shared model without changing its API `0.2` JSON contract.
- Route TypeScript/JavaScript `intelligence.query` document symbols through exact
  immutable editor overlays. Requests require snapshot/index/lease/path/version
  authority; stale versions refuse, responses omit content, and the persistent
  language server restores saved documents before any later semantic operation.
- Add typed TypeScript/JavaScript signature help through immutable editor
  overlays, including bounded overloads, exact parameter-label spans, active
  signature/parameter state, trigger/retrigger context, provenance and saved-
  document restoration.
- Add typed TypeScript/JavaScript completion through immutable editor overlays,
  including invocation/trigger/retrigger context, bounded operation-specific items,
  symbol kinds, insertion/replacement data, additional text edits, incompleteness,
  exact provider provenance and saved-document restoration.
- Add the first typed hover query through the same TypeScript/JavaScript overlay
  authority pipeline. Hover returns bounded plaintext/Markdown sections, optional
  exact UTF-16 range and provider provenance; malformed/out-of-range/provider-
  unsupported responses refuse without lexical fallback.
- Merge qualified TypeScript/JavaScript declaration projections into the central
  index during semantic startup/restart. Contributions carry language-server
  evidence and path-free toolchain/project/server provenance, refuse evidence or
  range drift without partial publication, cap at 256 files and 50,000 symbols,
  enforce one 30-second aggregate deadline, and are removed on provider stop.
- Begin the Kotlin and Java/Kotlin interoperability band on the published
  `v0.6.2` multi-language foundation.
- Activate the deferred advanced TypeScript/JavaScript move, import, signature,
  extract/inline and migration workstream while continuing Java depth.
- Add the isolated `refactorkit-kotlin` module boundary with `.kt`/`.kts`
  routing, typed backend-unavailable diagnostics and capability metadata exposed
  through CLI, daemon, LSP and MCP. Every Kotlin operation remains explicitly
  `REFUSED` with no mutation authority.
- Add non-executing provider `kotlin-compiler-explicit-v1`: explicit JDK 21 and
  Kotlin 2.0.21 compiler/classpath identity, bounded JAR inspection, double-read
  SHA-256 evidence and deterministic projection hashing with workspace-local and
  drift refusal.
- Add `kotlin-jvm-projection-v1`: hash-bound Maven/Gradle/conventional JVM
  main/test/integration/generated Kotlin ownership, classpaths, outputs, module
  edges and declared JDK/bytecode targets. Build scripts, plugins, kapt, KSP and
  compilers remain unexecuted; scripts and unsupported platforms fail closed.
- Bound the explicit TypeScript Node `--version` probe to two attempts so one
  transient native process-start failure does not fail packaged semantic commands;
  executable, constant arguments and per-attempt resource limits remain unchanged.
- Add API `0.2` method `diagnostics.v2` for IDE-grade TypeScript/JavaScript
  diagnostics: exact zero-based UTF-16 ranges, request/snapshot/semantic-lease
  correlation, saved-disk or immutable-editor-overlay authority, exact compiler
  attestation/runtime metadata, structured readiness failures and a bounded
  2 MiB response. Legacy `diagnostics` remains unchanged.
- Fail source builds immediately with an actionable JDK 21 prerequisite instead
  of exposing Kotlin 2.0.21's internal `JavaVersion.parse` failure on JDK 25.
- Add the first real Kotlin/K2 capability: bounded external compiler diagnostics
  for an explicit JDK 21 plus Kotlin 2.0.21 toolchain and an `AVAILABLE`
  single-module Kotlin/JVM projection. The worker revalidates evidence, uses an
  immutable overlay and clean process environment, refuses plugins/scripts/partial
  models, reports line-only compiler locations and process attestation, and is
  exposed through library, CLI, daemon capability metadata and MCP. Diagnostics
  are `EXPERIMENTAL/COMPILER/NONE`; every Kotlin mutation remains refused.
- Add `kotlin-compiler-jvm-types-k2-v1`, the first compiler-backed Kotlin symbol
  row. Successful K2 compilation plus compiler PSI and matching generated class
  files produce opaque JVM-binary-derived regular-class IDs and exact UTF-16
  declaration-name ranges. CLI, daemon and MCP expose saved-snapshot symbols and
  ID-based definition with lease/snapshot/toolchain/build/process attestation and
  a Draft 2020-12 API response schema. Compiler errors, unsupported class-like
  declarations, malformed ranges, collisions
  and limits refuse the whole result; references and mutations remain refused.
- Extend the compiler-proven Kotlin JVM type row to top-level and nested
  interfaces, enum classes and annotation classes while retaining the same
  binary-derived opaque IDs and exact PSI ranges. Enum entries are excluded;
  objects and callable identities still refuse the complete result. Semantic
  execution now also requires attested matching stdlib and qualified annotations
  runtime inputs before compiler launch.
- Add compiler-proven Kotlin named, data, nested and companion object symbols
  under the same JVM-binary-derived type-ID family. Core/API/LSP projections gain
  the additive `OBJECT` kind; implicit companions use their compiler name
  `Companion` and exact `object` keyword selection range. Anonymous objects remain
  excluded, and callable identity/references/mutations remain refused.
- Correct native runtime portability found by the expanded CI matrix: internal
  overlay `Path` values now accept host-native separators while protocol parsing
  centrally enforces canonical `/` paths; preview assertions use protocol paths;
  macOS watcher acceptance allows the bounded JDK polling latency without writing
  workspace metadata. Saved-snapshot mismatch now also takes precedence over a
  watcher-invalidated semantic session, removing a diagnostics refusal race.
  Cancellation acceptance also waits for the bounded process-tree shutdown before
  inspecting results. GitHub Actions run `29423034607` passes the complete
  correction on Linux, Windows x86-64, macOS x86-64 and macOS arm64.
- Align the real TypeScript semantic client with its documented 30-second
  aggregate projection deadline so cold native macOS language-server startup does
  not inherit the generic 10-second LSP default.
- Add the internal K2 FIR function-usage evidence foundation: real-PSI resolved
  call names map atomically to existing `kotlin-jvm-callable-v1` declarations,
  with exact UTF-16 ranges and a 2,000-usage bound. API `0.2` daemon
  `intelligence.query` now exposes definition from direct call names and explicitly
  partial references with lease/snapshot/generation authority. CLI position
  commands and MCP `kotlin_usage_definition`/`kotlin_references` expose the same
  boundary; broader references and every Kotlin mutation remain refused. Native
  packaged qualification passed in GitHub Actions run `29432766317` on Linux,
  Windows x86-64, macOS x86-64 and macOS arm64.
- Add the legacy API `0.2` `diagnostics` `languageId=jvm` aggregate: Java JDT and
  Kotlin K2 rows retain provider evidence, receive explicit language ownership,
  deterministic exact de-duplication and concise missing-toolchain roots without
  writing workspace metadata. The correlated per-language envelopes remain the
  authority for IDE precision and managed operations. Native packaged acceptance
  passed in GitHub Actions run `29439584115`.
- Extend the atomic K2 FIR usage payload with compiler-proven source type usages
  mapped to existing `kotlin-jvm-type-v1` declarations. The internal row covers
  type annotations and arguments, supertypes, casts/checks, constructor names,
  object qualifiers and explicit imports with exact PSI ranges; aliases, import
  wildcards themselves, local/anonymous/type-alias targets, external declarations
  and mutation authority remain excluded. Daemon, CLI and MCP transport plus
  packaged native qualification passed in run `29439584115`. The Windows
  kill-during-write acceptance observation window now uses the existing 120-second
  daemon request bound for the 256-file commit instead of a flaky 30-second sub-bound.
- Promote the experimental Kotlin symbol backend to
  `kotlin-compiler-jvm-declarations-k2-v1` and add the first bounded function
  identity row. Top-level and direct class/interface/enum/object member functions
  require one exact non-synthetic/non-bridge generated JVM method; opaque
  `kotlin-jvm-callable-v1` IDs bind owner, method name and descriptor. Overloads,
  bridge ambiguity and `@JvmName` refuse instead of guessing; properties,
  references and mutations remain unsupported.
- Preserve the read-only workspace contract for daemon `project.open`, MCP
  `project_scan`, LSP initialization and semantic read lifecycles: startup now
  inspects WAL state without creating `.refactorkit/workspace.lock` or changing
  journal bytes. Pending recovery refuses reads and requires explicit mutating
  `patch.recover`; packaged TypeScript/Kotlin smoke now hashes hidden workspace
  metadata during the complete read-only phase.
- No stable Kotlin mutation is claimed until real compiler-backed native
  acceptance passes preview, staged diagnostics, apply, WAL and rollback.

## 0.6.2 - 2026-07-14

- Publication patch for the completed `v0.6.0` feature scope. Fixes Windows-safe
  lexical validation of TypeScript include/exclude and path-alias glob patterns,
  and adds a bounded reference barrier before managed rename to prevent lazy
  declaration-only edits. Release builders retain a clean build before separate
  package/native smoke. No API contract change.

## 0.6.1 - 2026-07-13 (tagged, not published)

- First publication patch separated package/native smoke, but its Windows tag job
  still failed in a `clean build` invocation before assets were published. The
  immutable tag is retained and superseded by `v0.6.2`.

## 0.6.0 - 2026-07-13 (tagged, not published)

- Begin the TypeScript/JavaScript semantic adapter band on top of the published
  `v0.5.0` kernel; no new stable capability is claimed yet.
- Add experimental `TypeScriptSemanticAdapter`: pre-launch toolchain/config
  evidence revalidation, required server-capability negotiation, bounded
  versioned full-document synchronization, UTF-16/surrogate validation, ranged
  compiler diagnostics, bounded nested LSP document symbols, semantic
  definition/references delegation, and
  approval-required normalized `LANGUAGE_SERVER` rename previews with no direct
  writes. Added fail-closed exact staged diagnostics and JVM acceptance
  for lock-time toolchain/project evidence revalidation, explicit authorization,
  managed apply, WAL and rollback; stable identity and packaged native
  real-toolchain acceptance remain open. Added a constant 512 MiB Node/V8
  old-space limit and explicit provenance-preserving crash restart capped at
  three attempts per rolling 60 seconds; implicit restart is refused. Semantic
  completeness now distinguishes full TypeScript, checked JavaScript, dynamic
  JavaScript and mixed JavaScript; dynamic/mixed managed gates fail closed.
  Versioned edits must match synchronized documents exactly, and rename refuses
  missing or ambiguous TypeScript Build Model ownership. Semantic rename now
  requires an exact supported symbol and a safe non-reserved Unicode identifier;
  unknown/constructor/package/module and no-op targets fail before edit requests.
  Location-based semantic symbol IDs were replaced by cross-session opaque
  `lsp-symbol-v1` keys derived from normalized declaration path, semantic hierarchy,
  kind/name and bounded signature detail; line/column movement no longer changes
  identity under the same toolchain provenance. Workspace/document symbol merges
  now deduplicate the same path/kind/name and prefer hierarchical document identity,
  avoiding timing-dependent duplicate IDs from lazily loaded real projects. Layered
  TypeScript/JavaScript capabilities are now exposed through library,
  CLI, daemon, LSP and MCP with per-operation backend/runtime provenance, including
  explicit TSX/JSX ownership. Daemon project open and every managed rescan now
  preserve mixed Java/TypeScript/JavaScript source images and attach the
  declarative TypeScript Build Model when script sources are present. Added
  experimental daemon and MCP semantic start/stop with explicit toolchain paths, routed
  TypeScript/JavaScript search, definition, references and exact diagnostics, plus
  rename preview/apply/WAL/rollback through the language-specific diagnostics gate.
  MCP project scans are mixed-language and EOF closes owned semantic process trees.
  Real-toolchain packaged qualification pins Node 22.18.0,
  `typescript-language-server` 5.1.3 and TypeScript 5.9.3 under a script-disabled
  npm lockfile on Linux, Windows and macOS CI. Explicit `tsserver.js`
  initialization, lazy project opening and bounded semantic barriers make real
  operations deterministic. Added `typescript-compiler-exact-v1`, a fixed bundled
  no-emit bridge over the hash-bound TypeScript compiler API: request-correlated
  staged diagnostics reject new errors and are re-run under the writer lock.
  Packaged native qualification now covers path aliases, cross-file re-export
  rename, forced real language-server termination, provenance-preserving bounded
  daemon restart, explicit apply, WAL and exact rollback. A separate packaged
  111-file acceptance kills the daemon at durable `APPLYING` and verifies exact
  startup compensation to `ROLLED_BACK` on Linux, Windows and macOS; upstream
  unversioned LSP diagnostics remain untrusted.
  Added one-shot CLI TypeScript/JavaScript search, definition, references,
  diagnostics and rename with explicit toolchain flags, JSON output, preview by
  default and managed apply only under `--apply`. Completed LSP language routing
  without stealing native TypeScript/JavaScript LSP ownership: mixed script documents
  are accepted, plain `.ts`/`.js` receive bounded structural outlines, false
  Java-only services are suppressed, and the initialize response exposes the
  client-managed/managed-surface split.
  Compiler workspace-symbol search is capability-negotiated, bounded to 200
  results, and emits workspace-relative daemon paths. Exported symbols on
  package-exports/types, declaration/composite, or project-reference library
  surfaces now refuse exported rename by default unless an explicit
  external-consumer override records high risk. Exact quoted symbol-name
  candidates in computed properties, decorators, reflection and framework
  registries likewise refuse unless an explicit dynamic-reference override lowers
  confidence and records high risk. Managed JVM acceptance now also covers atomic
  cross-file re-export updates and server-required file renames, with mandatory
  prepare-rename range validation against the exact UTF-16 source image. Native
  Tree-sitter ancestor classification safely promotes generic semantic symbols
  for type aliases, parameters, namespaces and identifier-based internal modules;
  ambient external string-module names and unclassified kinds remain refused.
  File-changing plans retain exact staged file-set hashes bounded to 128 retained
  previews plus WAL/rollback restoration.
- Add declarative `typescript-config-declarative-v1` JSONC project modeling for
  local extends, project references, files/globs, compiler paths/options, aliases,
  JS/package modes, typed refusals and SHA-256 evidence, projected into the
  language-neutral Build Model SPI and `ProjectSnapshot` hash without executing
  Node or build/package code.
- Add `refactorkit-typescript` with explicit `typescript-lsp-explicit-v1`
  toolchain discovery: opt-in PATH/workspace-local trust, bounded declarative
  package identity/version/entrypoint validation, managed constant `node
  --version` probe, and SHA-256 provenance for Node, manifests, language-server
  entrypoint and `tsserver.js`; package scripts and project code are never
  executed during discovery.
- Add Objective-C/Objective-C++ to the shared Clang roadmap band and Swift to a
  dedicated compiler/SourceKit-LSP/SwiftSyntax plus SwiftPM/Xcode band. Both are
  mandatory pre-`1.0` targets with independent capability and interoperability
  acceptance gates.

## 0.5.0 - 2026-07-13

- Started the production multi-language adapter-kernel workstream while retaining
  API `0.2` until an explicit migration is accepted.
- Added the pre-`1.0` cross-platform runtime workstream: native Windows x86_64,
  macOS Intel, and macOS Apple Silicon jlink packages alongside Linux, with
  native test/package/managed format/apply/rollback smoke, per-platform
  checksums/SBOM/attestations, and aggregate release publication.
- Extended experimental daemon `java.importExternalClass` additively for API
  `0.2` with secure workspace-relative `targetDirectory` resolution through the
  Java module/source-root model, package/source-set derivation, structured
  preview metadata/refusals, exact-plan managed apply and WAL rollback. Added
  explicit capability feature flags and an official self-contained
  `refactorkit-daemon` launcher with end-to-end packaged smoke coverage.
- Completed daemon path-driven import protocol hardening with bounded real and
  structured diffs derived from the retained `WorkspaceEdit`, virtual JDT preview
  diagnostics and blockers, typed apply/rollback changes, portable protocol
  paths, deterministic primary-file rules, pending-plan project-switch/discard/
  LRU/EOF lifecycle, conservative unknown-license risk, and timeout-bounded
  packaged daemon smoke from paths containing spaces. Golden comparison paths
  and repository line endings are normalized for native Windows acceptance.
- Added language-kernel capability schema v1 and exposed it deterministically via
  CLI `capabilities`, daemon `server.capabilities`, LSP experimental initialize
  capabilities, and MCP initialize. It reports backend, operation stability,
  evidence, mutation authority, execution mode, timeout/cancellation, overlays,
  process-provenance support, and nullable resource limits.
- Replaced the Tree-sitter stub for `.ts`/`.js` with packaged real JNI 0.25.3
  TypeScript/JavaScript grammars on every native runtime target. Native bounded
  parse trees now drive outlines and identifier search while excluding
  comments/string literals; reflection preserves Java-8-compatible public
  bytecode and packaged smoke proves native loading without global Java.
- Integrated `ExternalLspAdapter` with managed external processes, byte-counted
  bounded LSP frames, request deadlines and cancellation, validated initialize
  handshake, server/capability provenance, source-only workspace overlays, URI
  remapping, and overlay mutation detection. Added strict parsing of LSP
  `changes`/`documentChanges` plus core normalization/refusal for untrusted paths,
  ranges, overlaps, versions, generated roots, symlinks, resource-operation
  options, content limits, and structural conflicts; accepted output remains an
  unapproved `LANGUAGE_SERVER` proposal.
- Added P4 `ExternalSemanticProcessManager`: bounded process count/arguments/
  explicit environment, secret-shaped environment-key refusal, executable and
  argument provenance hashes, pre/post-launch executable drift detection,
  counting stdout, concurrently drained/truncated stderr, natural-exit cleanup,
  idempotent cancellation, and graceful-then-forced descendant process-tree
  termination. The lifecycle primitive is explicitly not a sandbox or mutation
  authority.
- Started P4 with a bounded core `LanguageAdapterRegistry`, canonical adapter
  descriptors, unique language/extension ownership, deterministic mixed-language
  symbol/diagnostic aggregation, selection/symbol/explicit-language routing,
  typed ambiguity/refusal diagnostics, and operation capability negotiation.
  Added generalized compiler/language-server/native-AST/structural/lexical
  evidence vocabulary and fail-closed enforcement preventing weaker plans from
  inheriting managed stable authority. Java now publishes its reference
  capability registration.
- Completed the P3 distribution-trust baseline with a hostile archive verifier:
  checksum/name binding, bounded safe extraction vocabulary, traversal/symlink/
  duplicate/case-collision refusal, reproducible timestamps, executable metadata,
  required jlink modules, and ELF/PE/Mach-O architecture validation. Native CI
  executes extracted archives without `JAVA_HOME`; release publication verifies
  independently downloaded job artifacts again. Documented current unsigned
  macOS Gatekeeper/notarization and Windows Authenticode/SmartScreen status.
- Completed P2B and accepted ADR 0011: generic credential/build-execution policy
  allowances are ceilings, not provider capabilities. Current Maven remains
  credential-free and current Gradle remains non-executable even when allowances
  are requested. Any future authenticated Maven or executable Gradle support
  requires a new provider identity, opaque secret custody or isolated worker,
  provenance, redaction, limits, and separate native hostile-workspace evidence.
- Added native packaged Build Model acceptance on Linux, Windows x86_64, macOS
  x86_64, and macOS arm64 with `JAVA_HOME` removed: Maven reactor scan, clean
  diagnostics, rename-only `moveSourceRoot` preview, authorized apply, WAL/
  transaction evidence, post-apply diagnostics, rollback byte identity, and
  case-folded destination collision refusal.
- Versioned daemon `project.summary` with typed serializable Build Model DTOs,
  exact schema-key snapshots, canonical hyphenated statuses/kinds, workspace-
  relative module paths, active/inactive profile lists, deterministic ordering,
  explicit truncation flags, and defensive model/module/source-set/root/edge/
  diagnostic limits. External classpaths and diagnostic messages remain omitted.
- Advanced `gradle-declarative-v1` from compatibility projection to a bounded,
  non-executable model for conventional main/test plus literal integration/custom
  source sets, generated roots, outputs, Java levels, and project dependencies.
  JDT consumes per-set visibility and edges; scanner classpath evidence enriches
  the model. Oversized/unreadable descriptors and workspace/symlink root escapes
  return `EXECUTION_REFUSED`; Gradle/Wrapper/scripts/tasks/plugins/Tooling API are
  never invoked.
- Added bounded provider-scoped active/inactive profile selection to
  `BuildModelRequest`; Maven profile IDs are validated, forwarded to embedded
  ModelBuilder, represented in hash-bound provider attributes, and preserve
  Maven active-by-default deactivation semantics.
- Anonymous Maven Central opt-in now disables redirects and requires a valid
  bounded `.sha256` sidecar for every downloaded POM/JAR before atomic cache
  publication. Mismatch or absent checksum leaves no artifact/temp file and is
  reported as offline-missing; existing local artifacts remain content-hash
  bound but are not retroactively claimed as repository-authenticated.
- Added Maven effective custom-source discovery from active-profile
  `sourceDirectory`/`testSourceDirectory` and declarative
  `build-helper-maven-plugin` `add-source`/`add-test-source` executions without
  running plugins. Inactive-profile roots remain excluded; roots escaping the
  workspace or traversing external symlinks make the model unavailable with
  path-redacted diagnostics.
- Hardened offline Maven dependency modeling for classifier variants, Maven
  `test-jar` default `tests` classifiers, variant-aware mediation, and explicit
  `systemPath` artifacts. System paths are validated as existing absolute files,
  redacted in failures, represented by dedicated hash evidence, included in
  main/test scope correctly, and refuse apply on content drift before WAL.
- Started P2B implementation with language-neutral core Build Model SPI
  contracts, deny-by-default discovery policy, validated module/source-set graph,
  scoped dependencies, outputs/generated roots, typed model status/diagnostics,
  hash-bound `ProjectSnapshot` projection, and API `0.2` compatibility. Java now
  projects metadata through explicit `maven-effective-v1`,
  `gradle-declarative-v1`, and `java-conventional-v1` providers; daemon and MCP
  summaries expose redacted ecosystem, strategy, and policy metadata without
  external classpath paths or diagnostic messages. JDT parser environments now
  consume provider source sets for roots, classpaths, source levels, and
  transitive module visibility while retaining compatibility fallback. Shared
  longest-prefix/exact ownership queries now drive `moveSourceRoot`, external
  Java import targeting, formatter source-level/module selection, package-path
  diagnostics, and staged JDT overlay environments independently of legacy
  `Module.sourceRoots`.
- Updated the `0.5.x` roadmap after Maven/Magrathea acceptance: P2A is formally
  complete with commit/test/native-CI evidence; P2B makes the internal Build Model
  SPI, Maven production edges, explicit Gradle execution policy, capability
  schemas, and native packaged reactor/relocation acceptance release gates. ADR
  0010 fixes offline build-model and source-ownership boundaries without changing
  the language delivery sequence. Native Windows process-kill acceptance now
  retries only transient `workspace.locked` results for a bounded interval after
  confirmed child exit, while every non-lock recovery error still fails immediately.
- Added offline, plugin-free Maven effective-reactor analysis with inherited Java
  21 settings, dependency management/BOMs, local artifacts, reactor main/test
  visibility, generated support roots, hash-bound model evidence, and typed
  cascade-suppressing model/classpath/source-level diagnostics.
- Added `moveSourceRoot` across Java API, CLI, daemon, and MCP: deterministic
  rename-only whole-root preview/apply/WAL/rollback preserving bytes/packages/FQCNs
  with typed collision, generated, overlap, package, duplicate, symlink, model,
  classpath, and post-image diagnostic refusals.
- Canonicalized JDT reference binding keys to their matched source declarations,
  preserving exact qualified overload identity when independent parser
  environments emit platform-specific keys on Windows. Constructor and override
  relations use the same canonical declaration identity. Formatter preference
  evidence and daemon workspace-relative protocol paths now use portable `/`
  separators.

## [0.4.0] - 2026-07-12

- Replaced the premature Java-focused `1.0.0-rc.1-SNAPSHOT` trajectory with a
  long-range supreme multi-language `v1.0.0` roadmap. `v0.4.0` now publishes the
  hardened Java/transaction foundation; later `0.x` bands add the adapter kernel
  and deep TypeScript/JavaScript, Kotlin, Python, Go, Scala, C/C++, Groovy, C#,
  and Rust support. Clojure is the final planned language integration before
  all-language alpha/beta/RC stabilization.
- Defined Java as the reference and widest catalogue while requiring equivalent
  IDE-grade semantic safety and idiomatic depth from other mature adapters.
- Defined C/C++ component relocation across paths, headers, includes, build
  targets, optional C API prefixes, C++ namespaces/modules, and ABI evidence
  rather than treating directory layout as a namespace.
- Implemented deterministic Java single-file JDT formatting with hash-bound
  Eclipse project preferences or versioned defaults, UTF-8 BOM and LF/CRLF
  preservation, generated/syntax refusal, idempotence, exact staged diagnostics,
  CLI/daemon/LSP/MCP previews, managed apply/rollback, native client-managed LSP
  edits, and golden acceptance.
- Fixed the self-contained jlink runtime for JDT-backed signed selectors by
  adding `java.compiler`. Packaged-runtime smoke coverage now checks the module,
  executes signed `definition`/`references` with `JAVA_HOME` unset, verifies
  overload precision, and proves fixture sources remain unchanged.
- Completed a transactionality/requirements audit. Baseline flows were classified
  as preflighted compensatable batches; follow-up hardening tracks closure of
  write-ahead journaling, partial I/O failure, transaction-log traversal/integrity,
  rollback conflicts, concurrency, LSP client-managed edits, recipe boundaries,
  same-file coordinates, and text-range bounds.
- Closed transaction-log path finding `TX-003`: transaction IDs now use the
  generated UUIDv4 grammar, log paths are normalized and contained, symbolic-link
  traversal and non-regular records are rejected, owner-only permissions are
  applied where supported, and corrupt records produce coded errors. CLI, daemon,
  LSP, and MCP reject malformed rollback IDs before filesystem access.
- Added planner-approved post-preview diagnostic multisets while continuing to
  block every additional unapproved regression (including forced-delete flows).
- Added ranged JDT syntax/type diagnostics with stable compiler evidence and
  categories, using isolated exact post-image overlays for validation.
- Added sample diagnostics/lifecycle acceptance and completed S3 without running
  Maven or Gradle build scripts.
- Added hash-bound Maven/Gradle project-module dependencies and restricted JDT
  visibility to the owning module plus transitive declared dependencies.
- Published the stable Java capability/evidence matrix and completed S2.
- Added structured `JDT_BINDING`/`STRUCTURAL`/`LEXICAL_FALLBACK` plan evidence;
  lexical fallbacks are review-only and refused before WAL creation.
- Exposed evidence through daemon, LSP, and MCP previews; recipes retain the
  weakest composed evidence.
- Centralized generated-source detection and made stable Java file rewriters
  refuse generated paths, annotations, and generated/do-not-edit headers.
- Upgraded Eclipse JDT Core to 3.44/JLS25 and added hash-bound per-module Maven/
  Gradle source-level detection with Java 8/11/17/21/25 syntax acceptance.
- Closed the TX-001–TX-018 transactionality audit for the qualified managed-file
  contract and narrowed standalone directory operations out of stable v1.
- Added schema-v8 independently verified SHA-256 hashes for every file-image
  recovery payload; mismatches quarantine the journal.
- Added schema-v7 ordered platform ACL images with apply preservation,
  rollback/recovery restoration, and fail-closed unsupported handling.
- Defined the v1 managed-text contract as strict UTF-8: malformed input refuses
  before WAL creation and UTF-8 BOM bytes are restored exactly on rollback.
- Added schema-v6 sorted Base64 user-defined file attributes, preserving them on
  modify/rename apply and restoring them exactly on rollback/recovery.
- Added schema-v5 journaled owner/POSIX-group identity and exact ownership
  restoration during rollback/recovery with v2/v3/v4 checksum compatibility.
- Added lock-scoped durable cleanup of strictly named orphan lifecycle temp files
  during startup recovery; unsafe symlink/non-regular candidates are refused.
- Added raw torn-byte quarantine tests at four truncation boundaries and a
  conditional `/dev/shm` cross-filesystem WAL apply/rollback test.
- Corrected capability reporting to use the configured transaction-log store.
- Added schema-v4 journaled last-modified timestamps and exact timestamp
  restoration during rollback/recovery, preserving v2/v3 checksum compatibility.
- Added real subprocess kill/restart acceptance at every journal write boundary:
  new-record force, lifecycle temp force, and lifecycle atomic move.
- Added real subprocess kill/restart acceptance after a partial two-file commit,
  including WAL inspection and exact startup compensation.
- Extended filesystem capability reporting with journal store identity,
  workspace-store relationship, and durability strategy.
- Added deterministic journal fault hooks and tests around new-record force,
  lifecycle temp-file force, atomic move, cleanup, and restart readability.
- Added deterministic workspace fault hooks and tests for staging disk-full,
  partial multi-file commit, compensation failure, and successful restart retry.
- Added schema-v3 checksummed journal implementation/API versions, pre/post engine-scope
  snapshot hashes, and append-only validation/lifecycle/recovery event history.
- Journaled transaction-created parent directories and remove them deepest-first
  during rollback/recovery. External paths inside those directories cause
  conflict-safe refusal before rollback writes.
- Extended schema-v2 pre/post images with POSIX permissions so apply, rollback,
  force rollback, and startup compensation restore journaled modes exactly.
- Added schema-v2 transaction journal SHA-256 integrity checksums covering the
  complete canonical record. Tampering is rejected as `transaction.corrupt`;
  schema-v1 records remain readable and migrate atomically on lifecycle update.
  Corrupt records move atomically into owner-only quarantine and block managed
  journal access until manual review.
- Added a central staged diagnostics gate for production managed Java applies.
  CLI, daemon, managed LSP, MCP, recipes, and golden tests compare JDT errors on
  current versus exact post-image snapshots under lock and refuse regressions or
  unavailable diagnostics as `DIAGNOSTICS_FAILED (-32015)` before WAL creation.
  Direct library apply now requires explicit authorization and diagnostics gate.
- Closed approval gap `TX-016`: explicit managed apply is the approval event;
  missing authorization refuses before journaling, while transactions persist
  approval kind, surface, actor, and timestamp with legacy-record compatibility.
- Closed integration error mapping gap `TX-017`: daemon and LSP now expose
  deterministic codes for snapshot, recovery, validation, lock, filesystem,
  unsafe-path, file-conflict, and apply/journal refusals; MCP includes the same
  mapped numeric category.
- Closed daemon state gap `TX-018`: successful apply/rollback now replaces the
  stored session snapshot, clears every stale pending plan, returns the refreshed
  hash, and serves current summary/symbol queries immediately.
- Closed LSP ownership/version gap `TX-007`: full-sync open buffers now overlay
  disk snapshots, document versions must increase, native WorkspaceEdits declare
  client-managed/no-rollback ownership and carry exact open-document versions,
  incapable clients refuse, and managed apply/rollback rejects dirty or affected
  open documents.
- Replaced per-step recipe sagas with one staged recipe transaction. Every step
  plans against the immutable result of previous steps; successful workflows
  become one recipe-wide `PatchPlan` and WAL record, while later refusal and
  no-op execution write no transaction.
- Added SHA-256 classpath evidence for active Java dependencies, compiled-output
  directories, local JAR discovery locations, and generated classpath manifests.
  Apply recomputes evidence under lock and refuses stale dependencies as
  `snapshot.classpathChanged` before journaling.
- Made source snapshot scope engine-verified under the workspace lock. Snapshot
  hashes now bind module/source-root scope, extensions, ignore policy, language
  IDs, paths, and contents; apply independently rescans and refuses omitted,
  added, removed, or changed sources as `snapshot.scopeChanged`.
- Normalized multiple same-file modify entries into one original-content
  coordinate space per structural segment, with cross-entry overlap checks.
  Preflight now renders complete results and validates character-within-line
  bounds before journal creation, returning stable range/render diagnostics.
- Made rollback conflict-safe by validating exact journaled post-images under the
  workspace lock. Normal rollback refuses later edits as `rollback.conflict`;
  explicit `--force`/`force=true` restores pre-images and records the destructive
  override. Daemon/LSP expose stable rollback-conflict/recovery error codes.
- Replaced direct workspace writes with fully rendered same-directory staging,
  file `force`, required atomic replacement, and parent-directory `force` for
  apply, rollback, and recovery. POSIX permissions are preserved for modify/move
  flows, temporary files are cleaned, and filesystem capability probes/refusals
  expose unsupported durability instead of silently degrading.
- Added a versioned write-ahead transaction journal owned by `PatchEngine`.
  Durable lifecycle transitions cover `PREPARED`, `APPLYING`, `APPLIED`,
  `ROLLING_BACK`, `ROLLED_BACK`, and `RECOVERY_REQUIRED`; retained pre/post
  images support startup compensation when workspace state is compatible, while
  conflicts block writes for manual recovery. Journal updates use fsynced
  same-directory temporary files and required atomic replacement.
- Added a one-writer workspace lock around managed apply and rollback. The
  snapshot-aware apply path revalidates affected source/target state under the
  lock, and refuses contention, changed files, unsafe lock symlinks, and
  unavailable preconditions before mutation. First-party CLI, daemon, LSP, MCP,
  recipe, golden, and test paths now use this API. The hash-only apply overload
  was removed, and `ProjectSnapshot.hash` is derived rather than caller-settable.
- Hardened `PatchEngine` preflight validation: all ordered file-state transitions
  are simulated before the first write, so predictable later missing/existing-file
  failures cannot leave earlier edits applied. Workspace edits that traverse a
  symbolic-link component are refused with `path.symbolicLink` before writes.
- Initially advanced main toward a first stable-contract release candidate after
  verified `v0.3.0` publication. That versioning direction was superseded by ADR
  0009: main now reports `0.4.0`, API baseline remains `0.2`, and final
  API `1.0` is deferred until the deep multi-language acceptance program.

## [0.3.0] - 2026-07-10

- Published immutable tag `v0.3.0` and a non-prerelease latest GitHub Release
  with the self-contained Linux x86_64 runtime zip and SHA-256. Independently
  downloaded assets verified successfully, passed `JAVA_HOME`-unset smoke checks,
  and were deployed to the local production installation.

- Activated the detailed `v1.0.0` stable release plan, including the required
  `v0.3.0` → immutable RC → stable sequence, API `1.0` freeze, Java 8–25/JDT
  boundaries, safety/stress gates, protocol classification, SBOM/provenance,
  reproducibility, migration, and post-publication verification.
- Updated release automation so hyphenated tags publish prereleases while stable
  tags publish as the latest non-prerelease GitHub Release.
- Advanced and finalized the implementation version as `0.3.0` after the
  published `v0.2.0-beta` release.
- Added centralized version/API metadata: implementation name `RefactorKit`,
  implementation version `0.3.0`, and beta contract API version `0.2`.
- Exposed version metadata through `refactorkit --version`, `refactorkit version`,
  CLI help text, daemon JSON-RPC `server.version`, and LSP/MCP `serverInfo`.
- Added CLI, daemon, LSP, and MCP tests for the version/API metadata surfaces.
- Added daemon JSON-RPC `server.capabilities`, available before project open, with
  implementation/API metadata, transport/protocol identifiers, method stability,
  project/write requirements, and preview/snapshot/rollback/workspace safety flags.
- Drafted the compatibility and deprecation policy for beta-contract,
  experimental, and internal integration surfaces.
- Added ADR 0008, selecting Eclipse JDT as the primary `v0.3.0` compiler-backed
  Java analysis prototype candidate while keeping lexical planners as safety
  fallback.
- Expanded the JDT semantic analyzer prototype with `ProjectSnapshot` source-root
  configuration, record type discovery, signed method/constructor identities,
  nested member ownership, constructor reference resolution, binding-key matched
  references with `symbolSignature`, declaration/reference `sourceRange` evidence
  using JDT's zero-based columns, source-visible override relation evidence, and
  `JDT_PARSE` unresolved-type warnings; tests now cover selected-member
  identity/reference evidence, overload disambiguation, constructor
  identity/references, same-simple-name import disambiguation, static method
  import/call references, interface/enum/record discovery, child/base method
  override detection, interface implementation override detection, representative
  Maven/Gradle sample source-root validation, cross-module Gradle sourcepath
  resolution with interface reference/override evidence, and conventional
  compiled classpath resolution for Maven/Gradle output directories,
  project-local `lib`/`libs` JAR entries, and generated dependency lists from
  `.refactorkit/classpath`, `target/classpath.txt`, or `build/classpath.txt`.
- Advanced `renameMember` JDT integration from warning-only evidence to exact
  signed method overload selection plus source-visible override-family
  propagation. Signed selectors such as
  `com.example.Lookup#find(java.lang.String)` use binding-key matched
  declaration/reference ranges; class and interface override families propagate
  transitively across all scanned source declarations and call sites. The signed
  flow still refuses parse/classpath warnings, non-unique candidates, missing
  binding keys, or override families containing declarations outside the scanned
  source workspace. Unsigned member rename remains a lexical fallback with
  overload warnings.
- Added authoritative JDT field rename for unambiguous field selectors. Exact
  declaration/reference ranges preserve shadowing locals and same-name fields in
  unrelated owners; an existing target field is refused even when semantic
  evidence falls back.
- Added signed JDT annotation-element identity and rename. Selectors such as
  `com.acme.Route#path()` now resolve through read-only lookup/reference APIs and
  rename exact element declarations plus named annotation usages while preserving
  unrelated same-signature annotation elements.
- Added Java annotation-type symbols across lexical indexing and JDT binding
  analysis. Annotation declarations/usages now participate in exact class-style
  rename, move, safe-delete, read-only symbol lookup, and type-owner validation;
  same-simple-name annotation types remain disambiguated by binding.
- Added JDT-backed unused exact-import removal when analysis is clean. Import
  binding keys are normalized to declarations so parameterized type and generic
  static-method uses retain their imports; wildcard and unresolved imports remain
  conservative, and files with parse/classpath errors explicitly keep lexical
  sorting/deduplication/same-package cleanup only.
- Added JDT-scoped move-class when semantic analysis is clean: package/import/FQN
  edits are restricted to files with binding-matched references, so unrelated
  old-package files and same-simple-name types remain unchanged. Invalid packages,
  existing target types/files, and overlapping import/FQN edits are handled
  safely; unclean analysis reports lexical file scoping.
- Added JDT-backed class rename when semantic analysis is clean: type declaration,
  constructor declaration, import, qualified/simple type reference, and
  constructor-call edits use exact binding ranges. Same-simple-name types in
  other packages remain unchanged; existing target symbols/files are refused;
  parse/classpath warnings trigger an explicit lexical fallback warning.
- Added JDT-backed safe-delete type-reference evidence when semantic analysis is
  clean, preventing same-simple-name types in different packages from being
  conflated. Safe delete reports exact binding evidence, falls back explicitly to
  lexical reference scanning when JDT warnings exist, and preserves forced-delete
  and framework-risk behavior.
- Added read-only JDT-backed signed member search/lookup/reference support for
  exact member IDs such as `com.example.Lookup#find(java.lang.String)` across
  the Java adapter, daemon `symbol.search`/`symbol.definition`/
  `symbol.references`, MCP `symbol_search`/symbol definition/resource paths, and
  CLI `definition`/`references`, while keeping lexical lookup as the fallback
  for existing unsigned symbols. LSP definition/reference/rename-position
  resolution now uses the same clean JDT binding evidence for overloaded member
  call sites, signed member rename is verified across static import and invocation
  ranges, and limited change-signature accepts signed selectors for single-method
  cases while preserving overload refusals.

## v0.2.0-beta - 2026-07-10

Second public beta release for RefactorKit. This entry records the completed
beta scope, safety evidence, and published runtime assets for the
`v0.2.0-beta` prerelease.

Release page: https://github.com/mgiustiniani/refactorkit/releases/tag/v0.2.0-beta

### Beta scope and release evidence

- Established the `v0.2.0-beta` compatibility baseline for documented CLI and
  daemon JSON-RPC workflows, with LSP and MCP surfaces labelled for beta or
  experimental use.
- Accepted ADR 0007, documenting which Java behavior remains lexical/structural
  for beta and which semantic guarantees require compiler-backed analysis before
  `v1.0.0`.
- Expanded golden coverage from 15 alpha cases to 22 cases covering shipped
  patch-producing operation progress, sample coverage, and framework-warning
  assertions.
- Added P3 patch safety coverage for stale snapshots, unsafe paths, overlapping
  edits, and rollback restoration for modify, create, rename, and delete edits.
- Added P4 contract coverage for selected daemon JSON-RPC and MCP
  preview/apply/rollback and refusal/error flows; LSP command coverage verifies
  preview metadata, pending-plan apply, transaction-backed rollback, unknown
  transaction refusal, and `safeDelete` `PLAN_REFUSED` behavior.
- Updated P5 operation documentation for rename class/member, move class,
  organize imports, safe delete, extract method, change signature, and external
  import success conditions, refusal behavior, warnings, and rollback
  expectations.
- Reviewed runtime preview-warning wording against operation docs for the major
  shipped and experimental beta operations, including lexical/string/framework
  limits, conservative refusals, provenance/license warnings, and overwrite
  refusal.
- Hardened P6 external Java importer coverage for provenance warnings, GPL
  high-risk handling, helper-type preservation, multi-public-type splitting,
  non-Java Markdown fence stripping, stable provenance/license output fields,
  unknown-license policy blocks, and naming-conflict refusal guidance.
- Published and verified the P7 runtime artifacts: CI built the self-contained
  runtime zip and checksum, verified the checksum, smoke-tested the packaged
  launcher with `JAVA_HOME` unset across representative samples, and the release
  tag build verified/unzip-smoked the tag-named runtime asset.
- Published runtime asset names are
  `refactorkit-runtime-0.2.0-beta-linux-x86_64.zip` and
  `refactorkit-runtime-0.2.0-beta-linux-x86_64.zip.sha256`.

### Known beta limitations

- Java analysis remains lexical/structural in beta and is not yet a full
  compiler-backed type-resolution engine.
- Framework configuration, reflection, generated code, external configuration,
  and unknown downstream consumers still require manual review.
- `organize-imports` does not promise full unused-import removal while analysis
  remains lexical.
- `safe-delete`, limited `extract-method`, and limited `change-signature` remain
  conservative and may refuse ambiguous plans.
- LSP, MCP, recipe, and parts of the external importer surface still include
  experimental areas before `v1.0.0`; importer provenance/license output fields
  are documented for beta review.

### Migration notes from v0.1.0-alpha

- Documented CLI and daemon JSON-RPC workflows are promoted to beta
  compatibility contracts; breaking changes after beta require changelog entries
  and migration notes.
- LSP pilots can rely on documented preview metadata fields for covered
  `workspace/executeCommand` preview commands (`refactorkitPlanId`, operation,
  status, summary, risk level, warnings, `changes`, and `documentChanges`) while
  still expecting experimental behavior outside the labelled baseline; MCP pilots
  should apply the same labelled-surface rule.
- Consumers must keep preview review, diagnostics, apply, and rollback checks in
  automation; beta does not remove the alpha safety workflow.
- Release automation injected the final source tag, release commit, asset URL,
  and SHA-256 into the published GitHub Release body; downloaded assets were
  verified with `sha256sum -c`.

## v0.1.0-alpha - 2026-07-10 (released)

Initial public alpha preview for the RefactorKit MVP. The `v0.1.0-alpha` tag was
published with ADRs, expanded golden coverage, and alpha release automation.
APIs and CLI details may still change before a stable release.

### Initial MVP scope

- Kotlin/JVM multi-module build for core, Java adapter, CLI, daemon, LSP, MCP,
  web importer, tree-sitter foundation, and testkit modules.
- Core patch model with previewable workspace edits, affected-file reporting,
  snapshot validation, apply support, transaction logs, rollback metadata, and
  diagnostics integration.
- Java project scanning for Maven and Gradle sample projects, source-root/package
  discovery, class/member discovery, symbol listing, definitions, references, and
  diagnostics commands.
- CLI support for scan, symbols, diagnostics, definition, references, rename class,
  rename member, move class, organize imports, safe delete, limited extract method,
  limited change signature operations, rollback, recipe execution, Java external
  class import, generic outline/search/local rename, and golden test execution.
- Daemon JSON-RPC, LSP, and MCP MVP integration surfaces for deterministic preview,
  apply, rollback, symbol, diagnostics, and context workflows.
- External Java class importer MVP with package rewriting, target-file planning,
  provenance/license warnings, conflict handling, preview/apply flow, and refusal
  paths for risky imports.
- Self-contained CLI packaging with an embedded Java runtime via the Gradle
  packaging tasks.
- Release workflow for `v*` tags that builds/tests the project, runs golden tests,
  packages `refactorkit-runtime-0.1.0-alpha-linux-x86_64.zip`, and publishes a
  matching `.sha256` checksum asset.
- Six ADRs covering Kotlin/JVM bytecode, patch safety, lexical Java MVP analysis,
  CLI/daemon/LSP/MCP split, jlink packaging, and MCP scoped tools/resources.
- Sample Java projects, 15 golden test cases, rollback-focused agent simulation
  tests, ARC42/C4 architecture documentation, and a GitHub Actions CI workflow
  for build, golden tests, architecture documentation checks, packaging, and CLI
  smoke tests.

### Known alpha limitations

- Java analysis is still mostly lexical and is not a full compiler-backed type
  resolution engine.
- `organize-imports` sorts and deduplicates imports but does not remove unused
  imports in the current MVP.
- `safe-delete` does not inspect build files, generated code, reflection,
  framework configuration, or external configuration.
- Limited `extract-method` and `change-signature` implementations are conservative
  and may refuse ambiguous plans.
- Multi-language and advanced framework-aware behavior remains future work.
