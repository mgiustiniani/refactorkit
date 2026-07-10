# Release plan toward a stable RefactorKit release

This plan turns the current MVP+ codebase into a stable, documented release that
users and local AI agents can safely rely on.

## Target releases

| Release | Goal | Stability promise |
|---------|------|-------------------|
| `v0.1.0-alpha` | First public CLI/library preview | APIs may change; patch engine and Java MVP are usable with review |
| `v0.2.0-beta` | Broader Java refactoring validation | CLI/JSON-RPC contracts mostly stable; migration notes required for breaking changes |
| `v0.3.0` | Post-beta contract and analysis planning slice | Version/API metadata is explicit; SemVer/deprecation and compiler-backed analysis plans are drafted |
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

Status note (2026-07-10): completed and published as `v0.2.0-beta`. The tag,
GitHub prerelease, runtime zip, checksum, release-body metadata, and local
post-publication smoke verification are complete. Remaining beta limitations now
feed the post-beta and `v1.0.0` planning work below.

Detailed plan: [docs/releases/v0.2.0-beta-plan.md](releases/v0.2.0-beta-plan.md).

Release objective: move from the completed `v0.1.0-alpha` foundation to a beta
that integration consumers can repeatedly test through the full safety workflow:
preview, inspect diagnostics, apply, diagnose again, and rollback. The beta plan
builds on the alpha evidence of 15 golden cases, ADR coverage, runtime release
asset publication with checksum, and rollback-focused agent simulations.

Stability promise:

- CLI command names/options and daemon JSON-RPC method names/request shapes used
  by documented workflows become beta compatibility contracts.
- LSP and MCP remain beta/experimental surfaces, but their documented commands,
  tools, resources, and refusal paths require contract tests.
- Breaking changes after beta require changelog entries and migration notes.
- Lexical/heuristic Java behavior remains acceptable only when it is explicit in
  previews, documentation, release notes, and the lexical-vs-compiler decision.

Exit criteria summary:

- CI and `./gradlew build` are green on the release candidate.
- `./refactorkit test-golden` passes with representative success and refusal
  coverage for every shipped patch-producing operation.
- Maven, Gradle, Spring, JPA, and multi-module samples are covered by scan,
  diagnostics, symbol, and safe preview smoke workflows.
- Integration tests cover apply, diagnostics, rollback, hash restoration, stale
  snapshots, overlapping edits, and unsafe paths.
- Daemon JSON-RPC, LSP, and MCP contract tests cover preview/apply/rollback,
  diagnostics, invalid input, stale plans, and refused operations.
- Safe-delete and external-import documentation and tests expose the beta limits
  around build files, generated code, reflection, framework configuration,
  provenance, license policy, and naming conflicts.
- CI publishes and verifies the self-contained runtime artifact and checksum for
  release tags.
- The project records what remains lexical MVP and what must become
  compiler-backed before `v1.0.0`.

Priority workstreams:

1. Freeze release scope and CLI/JSON-RPC compatibility baseline.
2. Expand golden coverage for all shipped operations.
3. Add representative Maven, Gradle, Spring, JPA, and multi-module samples.
4. Add apply/diagnose/rollback integration safety tests.
5. Add daemon, LSP, and MCP contract tests.
6. Harden safe-delete documentation and external importer tests.
7. Verify CI artifact publication, checksums, and packaged CLI smoke tests.
8. Update changelog, release notes, migration notes, and acceptance checklist.

## Phase 2.5 — Post-beta next-development slice (`0.3.0-SNAPSHOT` toward `v0.3.0`)

Draft plan: [docs/releases/v0.3.0-plan.md](releases/v0.3.0-plan.md).

Objective: make version/API metadata explicit on all integration surfaces and
prepare the contract policy and Java analysis path needed before `v1.0.0`.

Initial work items:

1. Keep the root project version on main at `0.3.0-SNAPSHOT` after the published
   `v0.2.0-beta` release while preserving `apiVersion=0.2` as the current beta
   contract baseline.
2. Document and test version surfaces for CLI `--version`/`version`, daemon
   JSON-RPC `server.version`, and LSP/MCP `serverInfo.version`.
3. Draft the semantic versioning and deprecation policy for beta-contract
   commands, JSON-RPC methods, LSP commands, MCP tools/resources, and structured
   error categories.
4. Decide and prototype the compiler-backed Java analysis direction for symbol
   resolution, reference search, diagnostics, and framework-risk detection before
   `v1.0.0`.
5. Broaden protocol-contract documentation for read-only metadata and capability
   discovery without weakening the existing preview/apply/rollback safety model.

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
2. Finalize machine-readable API/version metadata for daemon, LSP, MCP, and CLI consumers.
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

These risks were accepted for alpha and beta when visible in previews,
documentation, and release notes. The post-beta plan keeps them visible while
adding explicit version/API metadata, compatibility policy, and the
compiler-backed Java analysis decision needed before `v1.0.0`.
