# Changelog

All notable changes to RefactorKit are tracked here.

This project records released scope and known limitations so alpha users can
review safety boundaries before applying refactorings.

## Unreleased

No unreleased changes yet.

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
