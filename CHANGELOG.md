# Changelog

All notable changes to RefactorKit are tracked here.

This project records released scope and known limitations so alpha users can
review safety boundaries before applying refactorings.

## Unreleased

### Next development (`1.0.0-rc.1-SNAPSHOT`)

- Fixed the self-contained jlink runtime for JDT-backed signed selectors by
  adding `java.compiler`. Packaged-runtime smoke coverage now checks the module,
  executes signed `definition`/`references` with `JAVA_HOME` unset, verifies
  overload precision, and proves fixture sources remain unchanged.
- Completed a transactionality/requirements audit. Current flows are explicitly
  classified as preflighted compensatable batches rather than crash-safe durable
  transactions; critical `v1.0.0-rc.1` blockers cover write-ahead journaling,
  partial I/O failure, transaction-log traversal/integrity, rollback conflicts,
  concurrency, LSP client-managed edits, recipe sagas, same-file coordinate
  merging, and text-range bounds.
- Closed transaction-log path finding `TX-003`: transaction IDs now use the
  generated UUIDv4 grammar, log paths are normalized and contained, symbolic-link
  traversal and non-regular records are rejected, owner-only permissions are
  applied where supported, and corrupt records produce coded errors. CLI, daemon,
  LSP, and MCP reject malformed rollback IDs before filesystem access.
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
- Advanced main toward the first stable-contract release candidate after verified
  `v0.3.0` publication. The implementation version is
  `1.0.0-rc.1-SNAPSHOT`; API baseline remains `0.2` until the API `1.0` contract
  inventory and migration gates are complete.

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
