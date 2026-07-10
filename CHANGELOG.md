# Changelog

All notable changes to RefactorKit are tracked here.

This project uses planned alpha entries before the first public tag so release
scope and limitations stay visible during hardening.

## Unreleased

### Added

- GitHub Actions CI badge in `README.md` for the `ci.yml` workflow.
- Release notes template at `.github/RELEASE_TEMPLATE.md` for alpha and later releases.

### Planned

- Keep `v0.1.0-alpha` gated by green CI, golden tests, packaged CLI smoke checks,
  and visible MVP limitations as described in `docs/release-plan.md`.

## v0.1.0-alpha - planned

Initial public alpha preview for the RefactorKit MVP. The tag has not been cut yet;
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
- Sample Java projects, golden tests, agent simulation tests, ARC42/C4 architecture
  documentation, and a GitHub Actions CI workflow for build, golden tests,
  architecture documentation checks, packaging, and CLI smoke tests.

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
