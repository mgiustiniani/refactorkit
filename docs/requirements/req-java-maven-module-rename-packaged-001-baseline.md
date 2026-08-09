# REQ-JAVA-MAVEN-MODULE-RENAME-PACKAGED-001 Initial Requirements Baseline

- Receipt ID: `REQ-JAVA-MAVEN-MODULE-RENAME-PACKAGED-001-BASELINE-001`
- Captured: 2026-08-09
- Baseline Git commit: `b460d6f983b497aa93f82f90e1fb5439070a3277`
- Baseline Git tree: `4eb64dd4b2c9c5ae2782d71759c3b5ead2ab2697`
- Workflow: `bdd-java`
- Release ledger target: `docs/releases/v0.7.0-plan.md` J1 module rename/move qualification row
- Requirement classification: Story BDD packaged/native managed-mutation qualification
- Initial status: `@absent`

## Initial user intent

Advance the actual 0.7.0 release ledger without deferring work to 0.7.1 and without introducing further catalogue or alpha-schema work. Qualify the existing bounded Java Maven module-rename/move behavior through real self-contained packaged processes, native hosts, and a pinned disposable real reactor so the J1 module rename/move row can close honestly.

## Acceptance criteria

- **AC-PACKAGED-001 — Existing semantic operation only:** qualification exercises the already implemented `java.renameMavenModule` planner, exact operation-owned diagnostics gate, managed apply, schema-v8 journal, and rollback authority. It does not replace or weaken the source-built requirements.
- **AC-PACKAGED-002 — Single Gherkin source:** packaged CLI, packaged daemon, and packaged MCP validation reuse one requirement feature source. Different launchers/glue/reporting modes must not duplicate feature text.
- **AC-PACKAGED-003 — Self-contained runtime:** every subject process runs from the candidate self-contained RefactorKit distribution and embedded Java runtime with a scrubbed environment and poison `PATH`; no globally installed Java, local developer installation, Gradle application classpath, or source-built in-process fallback is accepted.
- **AC-PACKAGED-004 — Permanent fixture matrix:** on Linux x86-64, Windows x86-64, macOS x86-64, and macOS arm64, a fresh no-follow disposable copy of the permanent aggregator-plus-20-direct-child fixture performs deterministic preview, managed apply, committed diagnostics, and normal rollback for `catalog-model` to `catalog-domain` with explicit `newArtifactId=catalog-domain`.
- **AC-PACKAGED-005 — Exact mutation authority:** every surface observes the independently declared three POM modifications and two managed moves, one operation-owned authoritative diagnostics gate, one committed schema-v8 transaction, exact S1, clean post-apply diagnostics, and byte/path/kind/inventory-exact S0 restoration after rollback.
- **AC-PACKAGED-006 — Real-reactor proof:** Linux x86-64 additionally exercises a pinned, license-reviewed, no-follow detached copy of Magrathea commit `a48e3c06629acf7032d0f28a0f8bdc5426442c7c` only if domain admission confirms an exact direct-child Maven module-directory move fixture. Repository, tree, fixture inputs, dependency artifacts, request, and expected edit oracle are hash-bound before RED.
- **AC-PACKAGED-007 — Directory-only intent:** the real-reactor request proves a caller-explicit module-directory move with `newArtifactId` omitted preserves the authoritative existing artifact/dependency coordinates. It must not infer artifact, group, version, dependency-origin, package, or source movement from the directory name.
- **AC-PACKAGED-008 — Public packaged surfaces:** the real CLI child process uses public argv; the daemon uses its public stdio JSON-RPC contract; MCP uses public stdio `tools/call`. Test code may supervise processes and inspect disposable outputs but may not call production session objects in-process.
- **AC-PACKAGED-009 — Surface lifecycle honesty:** each packaged surface preserves its existing plan/session/removal/refusal policy. Plan IDs are process/session correlation only; child-process apply replans or uses the public retained-plan contract as actually supported and never claims cross-process plan identity.
- **AC-PACKAGED-010 — Offline and plugin-free:** project discovery, planning, staged validation, diagnostics, apply, and rollback execute without network access, Maven/Gradle lifecycle, build plugins, settings credentials, annotation processors, or project scripts.
- **AC-PACKAGED-011 — Fail-closed process boundaries:** every child has bounded startup, request, output, memory where enforceable, and total timeout; stdout/stderr are complete and untruncated; descendant processes are terminated; fixture cleanup is verified; any missing report or timeout fails the row.
- **AC-PACKAGED-012 — Native evidence identity:** each host report is bound to repository, full commit, clean/version state, host/architecture, archive name and SHA-256, extracted runtime/tree identity, feature/scenario/request, fixture hashes, toolchain/runtime hashes, transaction/journal facts, and report hashes.
- **AC-PACKAGED-013 — CI report durability:** native CI runs the operation-specific packaged task and uploads its Cucumber JSON, JUnit XML, logs, manifests, package checksums, and failure diagnostics under `if: always()` without treating configuration or a queued job as passing evidence.
- **AC-PACKAGED-014 — Regression separation:** ordinary source-built `test`/`check` remains packaging-independent. Existing source-built module-rename, SURFACE-001 through SURFACE-005, managed selector, rollback executor, CLI/daemon/MCP, and transaction tests remain green but are reported separately from packaged evidence.
- **AC-PACKAGED-015 — Support-matrix promotion:** the J1 module rename/move row and support matrix may close only after all four native reports plus the Linux real-reactor report independently reconcile at one immutable candidate revision.
- **AC-PACKAGED-016 — Evidence and release discipline:** meaningful RED precedes production/scaffolding changes; focused GREEN, native receipts, ARC42/support-matrix reconciliation, independent revision-bound PASS, status-only promotion, and post-promotion verification are required.

## Approved scope

- A dedicated packaged/native validation mode for the existing Maven module-rename requirement.
- Packaged CLI, daemon stdio, and MCP stdio on the permanent fixture.
- Four native host/architecture rows already used by RefactorKit release workflows.
- One pinned Linux real-reactor directory-only proof if domain admission validates the fixture and exact oracle.
- Test/packaging/CI changes and only the smallest production correction if executable RED proves a real packaged-boundary defect.

## Explicit exclusions

1. new CLI result envelopes or command-catalogue schemas;
2. Java groupId/version migration, nested-module creation, arbitrary dependency-origin lists, JPMS, package moves, or source-type ownership expansion;
3. recipes or new recipe defaults;
4. external editor/LSP transport qualification beyond already promoted source-built LSP rows;
5. general Maven populations or a broad stable Java module-rename claim;
6. crash/restart, concurrency, refusal, corruption, or recovery matrices not already required by the bounded apply/rollback path;
7. signing, notarization, installer UX, or final downloaded-release publication evidence owned by I1;
8. TypeScript, JavaScript, Kotlin feature expansion, or another language;
9. rewriting prior baselines, checksums, feature revisions, evidence, reviewer receipts, or transaction history.

## Meaningful RED requirement

Before implementation, the executable requirement must fail because the dedicated packaged module-rename validation mode and native operation reports do not exist or because a real packaged boundary fails. A mere undefined step, malformed fixture, missing ordinary dependency, or unrelated baseline failure is invalid RED.

A captured dry-run failure caused specifically by the absent dedicated packaged task/source set/report wiring is acceptable structural RED only if the complete executable scenario and immutable fixture/oracles already exist and the production behavior remains untouched.

## Current known evidence and gaps

Existing source-built requirements prove the bounded fixture, exact five edits, operation-owned gate, managed apply, transaction, and rollback. They expressly exclude packaged/native/cross-platform and real-project qualification. Historical native run `29903077059` qualifies `java.moveAcrossMavenModules`, not `java.renameMavenModule`.

The release-ledger audit at commit `b460d6f` classified all four J1 rows as partial and identified this requirement as the smallest next Story BDD slice capable of closing the J1 module rename/move qualification row.

## Approved changes

No changes beyond this baseline have been approved at capture time.
