# Changelog

All notable changes to RefactorKit are tracked here.

This project records released scope and known limitations so alpha users can
review safety boundaries before applying refactorings.

## Unreleased

### v0.2.0-beta - in progress

Draft beta release preparation is in progress. This section records completed
beta progress so far and does not imply that `v0.2.0-beta` has been released or
tagged.

#### Completed beta progress so far

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
  preview/apply/rollback and refusal/error flows; LSP command coverage now
  verifies preview metadata, pending-plan apply, transaction-backed rollback,
  unknown transaction refusal, and `safeDelete` `PLAN_REFUSED` behavior.
- Updated P5 operation documentation for rename class/member, move class,
  organize imports, safe delete, extract method, change signature, and external
  import success conditions, refusal behavior, warnings, and rollback
  expectations.
- Hardened P6 external Java importer coverage for provenance warnings, GPL
  high-risk handling, helper-type preservation, multi-public-type splitting, and
  non-Java Markdown fence stripping.
- Advanced importer output-contract coverage and docs so CLI `java import-class`,
  daemon JSON-RPC `java.importExternalClass`, and MCP
  `import_external_java_class` expose stable provenance/license fields in
  warnings or output text.
- Added importer refusal guidance coverage for unknown-license policy blocks and
  naming conflicts; refused plans state that no files were written, point
  reviewers to provenance/license/naming-conflict review, and reiterate that
  RefactorKit never overwrites existing files by default.
- Verified P7 runtime artifact workflow progress: CI builds the self-contained
  runtime zip and checksum, verifies the checksum, smoke-tests the packaged
  launcher with `JAVA_HOME` unset across representative samples, and release tag
  builds verify/unzip-smoke the tag-named runtime asset before publication.

#### Current known beta limitations

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

#### Migration notes from v0.1.0-alpha so far

- Documented CLI and daemon JSON-RPC workflows are being promoted to beta
  compatibility contracts; breaking changes after beta require changelog entries
  and migration notes.
- LSP pilots can rely on documented preview metadata fields for covered
  `workspace/executeCommand` preview commands (`refactorkitPlanId`, operation,
  status, summary, risk level, warnings, `changes`, and `documentChanges`) while
  still expecting experimental behavior outside the labelled baseline; MCP pilots
  should apply the same labelled-surface rule.
- Consumers must keep preview review, diagnostics, apply, and rollback checks in
  automation; beta does not remove the alpha safety workflow.
- No final beta artifact, checksum, source tag, or release commit exists yet.

## v0.1.0-alpha - 2026-07-10 (released, current alpha)

Initial public alpha preview for the RefactorKit MVP. The `v0.1.0-alpha` tag is
being finalized on the release commit that includes ADRs, expanded golden
coverage, and alpha release automation. APIs and CLI details may still change
before a stable release.

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
