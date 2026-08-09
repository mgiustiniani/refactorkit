# REQ-JAVA-CLI-RESULT-001 Initial Requirements Baseline

- Receipt ID: `REQ-JAVA-CLI-RESULT-001-BASELINE-001`
- Captured: 2026-08-08
- Baseline Git commit: `8b361e3a4ac9d83ef2b9b4e797a7a4ea569d42dc`
- Baseline Git tree: `d5526897d03cbb881b3babf68d0d6b1bc20111f9`
- Workflow: `bdd-java`
- Requirement classification: Story BDD versioned Java CLI result protocol
- Initial status: `@absent`

## Initial user intent

Add the next Java-first CLI distribution-surface requirement after the independently promoted command catalogue: strict versioned machine-readable operation results for the source-built Maven module-rename command.

The authorized schema identity is:

```text
refactorkit.cli-result/v1
```

The first command identity is:

```text
java.renameMavenModule
```

The human-oriented CLI remains backward compatible and is never treated as an IDE or agent protocol.

## Closed top-level result envelope

The result is a closed object with these fields:

```json
{
  "schemaVersion": 1,
  "command": "java.renameMavenModule",
  "requestId": "<bounded opaque id>",
  "outcome": "preview|applied|refused|error",
  "plan": null,
  "transaction": null,
  "diagnostics": [],
  "truncated": false
}
```

`plan`, `transaction`, and `diagnostics` become non-null/non-empty only according to the approved outcome rules below. Their nested v1 field sets must be fixed by domain and architecture admission before executable Story BDD is authored; they may not be copied from human text or expose internal object identities.

## Acceptance criteria

- **AC-RESULT-001 — Public command boundary:** the actual source-built `java rename-module` parser accepts `--json` for the qualified command without changing human-mode behavior.
- **AC-RESULT-002 — Schema identity:** the output contract is the closed, versioned `refactorkit.cli-result/v1` protocol with integer `schemaVersion` 1 and exact command identity `java.renameMavenModule`.
- **AC-RESULT-003 — Strict output bytes:** with `--json`, stdout contains exactly one UTF-8 JSON object followed by one LF; stderr is empty for every RefactorKit application-level outcome; no log, stack trace, progress text, ANSI sequence, or human prose is emitted.
- **AC-RESULT-004 — Closed validation:** unknown or extra top-level and nested fields fail contract validation; every field has a fixed type, nullability, bounded size, and deterministic ordering policy.
- **AC-RESULT-005 — Preview:** preview exits 0, returns `outcome=preview`, includes the canonical plan projection, has no transaction, and performs no managed write, workspace lock, WAL, or installation mutation.
- **AC-RESULT-006 — Applied:** successful explicit apply exits 0, returns `outcome=applied`, includes the same canonical plan projection and a transaction projection, and preserves existing `PatchEngine`, authorization, diagnostics-gate, WAL, and rollback authority.
- **AC-RESULT-007 — Refused:** a deterministic application-level refusal exits 3, returns `outcome=refused`, includes diagnostics, creates no mutation or transaction, and does not misrepresent human text as structured evidence.
- **AC-RESULT-008 — Usage/contract error:** invalid CLI usage or contract input exits 2 and returns a closed JSON error envelope when RefactorKit has started and selected JSON mode.
- **AC-RESULT-009 — Operational failure:** an expected operational failure after RefactorKit startup exits 4 and returns a closed JSON error envelope without leaking a stack trace or partial human output.
- **AC-RESULT-010 — Unexpected internal failure:** an unexpected internal failure after RefactorKit startup exits 70 and returns a closed JSON error envelope without leaking a stack trace or partial human output. No public production fault-injection seam is permitted.
- **AC-RESULT-011 — Launcher boundary:** launcher/JVM failures before RefactorKit starts remain outside this JSON contract.
- **AC-RESULT-012 — Request correlation:** `requestId` is a bounded opaque correlation value, not approval, plan identity, transaction identity, or semantic evidence.
- **AC-RESULT-013 — Determinism and truncation:** deterministic inputs produce deterministic result content except explicitly opaque correlation/time identities; bounded diagnostics and plan projections report truthful `truncated` state rather than silently dropping data.
- **AC-RESULT-014 — Human compatibility:** invoking the same command without `--json` preserves the existing human-oriented preview/apply/refusal behavior and exit semantics unless a separately approved compatibility change is required.
- **AC-RESULT-015 — Bounded qualification:** the first qualification is source-built RefactorKit `0.7.0-SNAPSHOT`, local Linux, JDK 21, and the current CPU architecture only.
- **AC-RESULT-016 — Evidence and promotion:** completion requires meaningful Cucumber RED before production changes, focused GREEN and relevant regression evidence, architecture/documentation reconciliation, and an independent revision-bound requirements-quality `PASS` before promotion.

## Approved scope

- Add strict `--json` result projection only to `java rename-module` / `java.renameMavenModule`.
- Reuse the already-qualified planner, managed apply, diagnostics, transaction journal, and rollback behavior without weakening or silently redefining their authority.
- Add the smallest Kotlin CLI-adapter/protocol implementation and Story BDD evidence required by this Java CLI requirement.
- Define exact closed v1 nested plan, transaction, diagnostic, error, request-correlation, limit, and truncation projections before production implementation.

## Explicit exclusions

1. JSON result support for `java create-module`, `java move-across-maven-modules`, or any non-Java command;
2. extension or replacement of `refactorkit capabilities` or `refactorkit.cli-command-catalog/v1`;
3. isolated `installDist` source-built/installed parity;
4. promotion or overwrite under `~/.local/share/refactorkit`;
5. claiming this format-projection slice as fresh SURFACE-002 authority/apply/refusal/rollback qualification;
6. Windows, macOS, another CPU architecture/JDK, installer/package behavior, signed artifacts, release-grade portability, or broad release claims;
7. TypeScript, JavaScript, Kotlin-language capability, or other language work;
8. demotion or rewriting of existing promoted requirements, baselines, checksums, evidence, or promotion history.

## Installation safety boundary

The existing executable under `/home/paperboy/.local/share/refactorkit` is not acceptance evidence and must not be invoked, overwritten, or promoted by this requirement.

## Protocol details requiring admission before Gherkin RED

Domain and architecture admission must fix, without guessing:

- exact nested `plan`, `transaction`, `diagnostics`, and `error` field sets and types;
- whether `requestId` is caller-supplied, generated, or both, including length/character bounds;
- deterministic serialization and ordering rules;
- diagnostic count/byte limits and `truncated` semantics;
- natural, non-fabricated application-level refusal and operational-failure fixtures;
- a test-only, non-public way to validate unexpected internal-failure projection;
- how preview/apply plan equality is established without assuming opaque plan IDs are stable across independent invocations.

## Approved changes

No changes beyond this baseline have been approved at capture time.
