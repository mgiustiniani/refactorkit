# REQ-JAVA-CLI-CATALOG-001 Initial Requirements Baseline

- Receipt ID: `REQ-JAVA-CLI-CATALOG-001-BASELINE-001`
- Captured: 2026-08-08
- Baseline Git commit: `0dd70f7a8bcf5b136a6641a38d98a6e4d51da194`
- Baseline Git tree: `d3263c7c11281641fc6b8c5a8ab1928973742c4e`
- Workflow: `bdd-java`
- Requirement classification: Story BDD CLI distribution-surface capability
- Initial status: `@absent`

## Initial user intent

Deliver the first non-mutating Java CLI distribution-surface slice: a separate, strict, versioned, machine-readable command catalogue available from the source-built CLI as:

```text
refactorkit commands --json
```

The catalogue must include exactly these currently authorized Java command routes:

```text
java rename-module
java create-module
java move-across-maven-modules
```

Top-level help, parser reachability, and the catalogue must agree. Existing `refactorkit capabilities` remains the language-capability schema and must not be mixed with CLI distribution-surface discovery.

## Acceptance criteria

- **AC-CATALOG-001 — Separate public route:** the actual source-built CLI accepts `commands --json` through its public parser and returns the command catalogue without opening or mutating a workspace.
- **AC-CATALOG-002 — Schema identity:** JSON output conforms to the closed schema identity `refactorkit.cli-command-catalog/v1` and version 1.
- **AC-CATALOG-003 — Exact authorized inventory:** the catalogue contains the three authorized Java routes `java rename-module`, `java create-module`, and `java move-across-maven-modules`; it does not infer support from source-name occurrences.
- **AC-CATALOG-004 — Required command facts:** every catalogue entry exposes command name, aliases, modes, mutation authority, JSON support, stability, and required arguments.
- **AC-CATALOG-005 — Surface agreement:** top-level help, public parser reachability, and catalogue entries agree for the three authorized routes.
- **AC-CATALOG-006 — Strict JSON bytes:** with `--json`, stdout contains exactly one UTF-8 JSON object followed by one LF; stderr is empty; no log, stack trace, progress text, ANSI sequence, or human prose is emitted.
- **AC-CATALOG-007 — Closed contract:** contract validation rejects unknown or extra fields. The catalogue representation and entry representation use fixed field sets and types.
- **AC-CATALOG-008 — Non-mutating behavior:** catalogue and help discovery create no preview, plan, workspace lock, WAL, transaction, file edit, or runtime installation change.
- **AC-CATALOG-009 — Existing schema separation:** `refactorkit capabilities` remains the existing language-capability schema and does not gain CLI command-distribution fields.
- **AC-CATALOG-010 — Human compatibility:** existing human-oriented command behavior remains unchanged except for the additive truthful top-level help listing required by this requirement. Human stdout is not an IDE or agent protocol.
- **AC-CATALOG-011 — Bounded qualification:** the first qualification is source-built RefactorKit `0.7.0-SNAPSHOT` on local Linux, JDK 21, and the current CPU architecture only.
- **AC-CATALOG-012 — Runtime evidence:** completion requires a meaningful Cucumber RED before production changes, focused GREEN evidence, relevant regression evidence, documentation reconciliation, and an independent revision-bound requirements-quality `PASS` before promotion.

## Approved scope

- Add the source-built `commands --json` CLI discovery surface.
- Define and validate only `refactorkit.cli-command-catalog/v1`.
- Reconcile top-level help for the three authorized Java routes.
- Add Story BDD/Cucumber acceptance evidence for this non-mutating behavior.
- Make the smallest Kotlin implementation changes required for this Java CLI requirement; no JavaScript, TypeScript, or other language capability work is authorized.

## Explicit exclusions

The following ordered future slices are not authorized by this baseline:

1. `refactorkit.cli-result/v1` strict preview/apply/refusal/error envelopes;
2. isolated `installDist` source-built/installed parity;
3. promotion or overwrite under `~/.local/share/refactorkit`;
4. fresh SURFACE-002 apply/refusal/rollback qualification;
5. Windows, macOS, another CPU architecture, installer/package behavior, signed artifacts, release-grade portability, or broad release claims;
6. changes to TypeScript, JavaScript, Kotlin-language capabilities, or any non-Java command inventory;
7. demotion, rewriting, or replacement of existing SURFACE-002/003/004/005 requirements, receipts, checksums, evidence, or promotion history.

## Approved future sequence context

After this requirement independently passes, the user-approved sequence is: strict JSON operation envelopes; isolated installed-package parity; optional installed-runtime promotion after separate explicit authorization; then fresh SURFACE-002 managed apply/refusal/rollback qualification.

## Installation safety boundary

The existing executable at `/home/paperboy/.local/share/refactorkit/bin/refactorkit` is not acceptance evidence and is not authoritative for this slice. Its overwrite is not authorized. No command in this slice may change that installation.

## Protocol details requiring fail-closed design evidence

Before production implementation, executable Story BDD evidence must fix a deterministic closed top-level representation, entry representation, field types, enum vocabulary, ordering, aliases, modes, mutation-authority values, JSON-support values, stability values, and required-argument sets. These values must be reconciled with public parser behavior and may not advertise unimplemented operation-result JSON support.

## Approved changes

No changes beyond this baseline have been approved at capture time.
