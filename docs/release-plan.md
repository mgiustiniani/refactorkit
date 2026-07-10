# Release plan toward a stable RefactorKit release

This plan turns the current MVP+ codebase into a stable, documented release that
users and local AI agents can safely rely on.

## Target releases

| Release | Goal | Stability promise |
|---------|------|-------------------|
| `v0.1.0-alpha` | First public CLI/library preview | APIs may change; patch engine and Java MVP are usable with review |
| `v0.2.0-beta` | Broader Java refactoring validation | CLI/JSON-RPC contracts mostly stable; migration notes required for breaking changes |
| `v1.0.0` | Stable local deterministic refactoring engine | Core patch model, CLI, daemon API, and rollback semantics are stable |

## Phase 1 — Alpha hardening (`v0.1.0-alpha`)

Status note (2026-07-10): completed for `v0.1.0-alpha`. The alpha exit
criteria and work items are satisfied, including green CI/build validation, 15
golden cases, rollback agent simulations, ADR coverage, and the release workflow
that publishes the bundled runtime zip plus checksum. The current open risk
limitations below remain accepted for alpha and must stay visible in release
notes and previews.

Exit criteria:

- CI is required and green on `main`.
- `./gradlew build` passes.
- `./refactorkit test-golden` passes.
- self-contained CLI package builds and runs `scan` on `samples/java-maven-simple`.
- README documents install, smoke-test, preview/apply/rollback workflow.
- Known MVP limitations are documented, not hidden.

Work items:

1. Keep GitHub Actions green for build, golden tests, runtime packaging, and smoke tests.
2. Add release notes template and changelog.
3. Add CLI examples for every supported command.
4. Add golden tests for existing Java operations:
   - organize imports;
   - rename member;
   - extract method success/refusal;
   - change signature rename/add/reorder/remove;
   - external class import preview/refusal.
5. Verify rollback in agent simulation tests for each mutating operation.
6. Tag `v0.1.0-alpha` only after a clean CI run.

## Phase 2 — Beta readiness (`v0.2.0-beta`)

Exit criteria:

- CLI command names and JSON-RPC method names are treated as compatibility
  contracts.
- Golden tests cover representative success and refusal paths for all shipped
  operations.
- Sample projects exercise Maven, Gradle, Spring, JPA, and multi-module scans.
- Patch plans include clear warnings for every lexical/heuristic operation.

Work items:

1. Expand sample projects with cross-file references and framework annotations.
2. Add integration tests that apply, diagnose, rollback, and verify hash restore.
3. Add daemon/LSP/MCP contract tests for preview/apply/rollback error cases.
4. Improve `safe-delete` documentation around build-file and reflection limits.
5. Add external importer tests for multiple public types, license policies, and
   naming conflicts.
6. Publish zipped self-contained CLI artifacts from CI.

## Phase 3 — Stable release (`v1.0.0`)

Exit criteria:

- Patch engine semantics are frozen: snapshot hash validation, overlap rejection,
  atomic apply rules, transaction logs, and rollback behavior.
- CLI and daemon APIs have versioned compatibility notes.
- All mutating operations are previewable, diagnosable, and rollbackable.
- Release artifacts are reproducible from CI.
- Documentation includes a safety model, API contract, and agent workflow guide.

Work items:

1. Define semantic versioning policy and deprecation rules.
2. Add a machine-readable API/version endpoint for daemon/MCP consumers.
3. Add CI artifact signing/checksums.
4. Add stress tests for large workspaces and overlapping edit plans.
5. Decide whether Java type resolution remains lexical-MVP or moves to a
   compiler-backed engine before `v1.0.0`.
6. Freeze `v1.0.0` only when release-candidate CI has passed on a clean tag.

## Current open risks

- Java analysis is still mostly lexical, not fully type-resolved.
- Some multi-language and LSP-backed capabilities are explicit stubs/future work.
- `organize-imports` does not remove unused imports.
- `safe-delete` does not inspect build files, generated code, reflection, or
  external configuration.
- `extract-method` and `change-signature` are conservative MVP implementations.

These risks are acceptable for alpha if they remain visible in previews,
documentation, and release notes.
