# REQ-JAVA-CLI-CATALOG-002 Initial Requirements Baseline

- Receipt ID: `REQ-JAVA-CLI-CATALOG-002-BASELINE-001`
- Captured: 2026-08-08
- Baseline Git commit: `8b361e3a4ac9d83ef2b9b4e797a7a4ea569d42dc`
- Baseline Git tree: `d5526897d03cbb881b3babf68d0d6b1bc20111f9`
- Workflow: `bdd-java`
- Requirement classification: Story BDD CLI distribution-surface protocol evolution
- Initial status: `@absent`

## Initial user intent

Make `refactorkit.cli-command-catalog/v2` the default source-built Java CLI command catalogue, preserve exact v1 through `commands --json --schema-version 1`, and atomically co-release v2 with the first qualified `refactorkit.cli-result/v1` preview for Java Maven module rename.

## Closed v2 document

The default command:

```text
refactorkit commands --json
```

must emit exactly this compact UTF-8 JSON document followed by one LF:

```json
{"schema":"refactorkit.cli-command-catalog/v2","schemaVersion":2,"commands":[{"name":"java create-module","operation":"java.createMavenModule","aliases":[],"modes":["preview","apply"],"mutationAuthority":"refactorkit-managed","jsonSupport":"catalog-only","stability":"experimental","requiredArguments":["--module-name","--parent-pom"]},{"name":"java move-across-maven-modules","operation":"java.moveAcrossMavenModules","aliases":[],"modes":["preview","apply"],"mutationAuthority":"refactorkit-managed","jsonSupport":"catalog-only","stability":"experimental","requiredArguments":["--from","--to"]},{"name":"java rename-module","operation":"java.renameMavenModule","aliases":[],"modes":["preview","apply"],"mutationAuthority":"refactorkit-managed","jsonSupport":"preview-only","stability":"experimental","requiredArguments":["--old-module-dir","--new-module-dir"]}]}
```

The top-level and entry field sets, types, producer ordering, command ordering, aliases, modes, mutation-authority values, stability, and required arguments remain identical to v1. The only admitted semantic differences are schema identity/version and the rename-module `jsonSupport` value.

## Acceptance criteria

- **AC-CATALOG2-001 — Default v2:** public source-built `commands --json` returns the exact closed v2 document and does not silently return v1.
- **AC-CATALOG2-002 — Exact version identity:** `schema` is exactly `refactorkit.cli-command-catalog/v2` and `schemaVersion` is integer 2.
- **AC-CATALOG2-003 — Honest result support:** only `java rename-module` reports `jsonSupport: "preview-only"`; the other two entries remain `catalog-only`.
- **AC-CATALOG2-004 — Bounded meaning:** `preview-only` claims only successful source-built `refactorkit.cli-result/v1` preview qualified by `REQ-JAVA-CLI-RESULT-001`. It does not claim JSON apply, refusal, usage/operational/internal error, closure/truncation, another command/runtime/platform, or write authority.
- **AC-CATALOG2-005 — v1 compatibility:** explicit `commands --json --schema-version 1` returns the exact previously promoted v1 bytes and semantics.
- **AC-CATALOG2-006 — Strict bytes:** each successful catalogue stdout is exactly one compact UTF-8 object plus one LF; stderr is empty; no ANSI, log, progress, stack trace, or human prose appears.
- **AC-CATALOG2-007 — Closed contracts:** strict validators accept their exact versioned documents and reject unknown top-level/entry fields or vocabulary values. v1 and v2 are distinct contracts and are never accepted under each other's schema identity.
- **AC-CATALOG2-008 — Truthful help and parser:** top-level help advertises `refactorkit commands --json [--schema-version 1]`; the public parser accepts the explicit v1 selector and rejects unsupported or malformed versions without falling back to another schema.
- **AC-CATALOG2-009 — Determinism:** repeated default-v2 invocations are byte-identical and repeated explicit-v1 invocations are byte-identical.
- **AC-CATALOG2-010 — Non-mutation:** catalogue/version discovery opens no workspace, performs no scan or planning, takes no lock, creates no WAL/transaction, edits nothing, and neither invokes nor mutates the installed runtime.
- **AC-CATALOG2-011 — Capability separation:** `refactorkit capabilities` remains byte-for-byte compatible and contains no command-catalogue fields.
- **AC-CATALOG2-012 — Atomic truthfulness:** default v2 and `REQ-JAVA-CLI-RESULT-001` preview support enter production in one atomic changeset; neither may be promoted alone.
- **AC-CATALOG2-013 — Bounded qualification:** qualification is limited to source-built RefactorKit `0.7.0-SNAPSHOT`, local Linux, JDK 21, and current CPU architecture.
- **AC-CATALOG2-014 — Evidence:** meaningful Cucumber RED, focused GREEN, relevant regressions, documentation reconciliation, separate independent reviews for affected requirements, status-only promotion, and joint post-promotion verification are mandatory.

## Explicit exclusions

1. JSON result support for apply, refusal, usage/operational/internal errors, closure/redaction/truncation, create-module, move-across-maven-modules, or another command;
2. installed/package parity or installed-runtime promotion;
3. fresh SURFACE-002 authority/apply/refusal/rollback qualification;
4. TypeScript, JavaScript, Kotlin-language capability, another language, another platform/JDK/architecture, installer/signing/native parity, or broad release claims;
5. rewriting any prior baseline, checksum, evidence file, review receipt, or historical promoted revision.

## Installation safety boundary

The installation under `/home/paperboy/.local/share/refactorkit` remains non-authoritative and must not be invoked, selected, or modified.

## Approved changes

No changes beyond this baseline have been approved at capture time.
