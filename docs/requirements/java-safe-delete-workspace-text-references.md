# Java safeDelete workspace text references

Status: bounded preview behavior and fail-closed Apply boundaries for the
Maven-POM-only review proposal implemented and locally accepted; broader Java
safe-delete closure remains actionable. The integrated requirement status and
architecture narrative remain in the [ARC42 requirements appendix](../arc42/appendix-requirements.adoc).
This satellite records one explicit authority boundary for
`REQ-JAVA-SAFE-DELETE-WORKSPACE-TEXT-001`.

## Requirement

When Java `safeDelete` previews a type deletion, it must refuse if a literal
simple-name token is found in captured workspace text and `force` is absent,
except for the narrowly bounded Maven-POM-only case below. That case may produce
a review-only preview only when JDT analysis is clean with no Java references
and every captured-text match is in a `pom.xml` path identified by an available,
source-backed Maven Java module. This includes its own POM and parent or
aggregator POMs recorded as effective-model inputs for that module. An unrelated
POM, a resource file merely named `pom.xml`, or an unmodeled reactor POM does not
qualify. If the scanner cannot prove
complete coverage for its bounded captured-text scope, it must refuse regardless
of `force`. A forced preview may ignore found references only after the same
complete-scan proof is present.

The operation still deletes only the Java declaration file. Text matches are
reference evidence, not edit targets. A text match may be a false positive; the
plan reports its path and line so a caller can inspect it.

The Maven-POM-only exception is explicitly review-only: it has HIGH risk,
confidence `0.3`, `LEXICAL_FALLBACK` plan evidence, and a warning that names the
POM locations and says complete project-wide analysis is not advertised. The
`PatchEngine` rejects Apply with `evidence.insufficient` before locking or
creating transaction metadata. Daemon and MCP Apply retain their stable
`java.safeDelete.referenceClosureIncomplete` refusal; the LSP command returns
`PLAN_REFUSED` without a client-applicable edit. If Java references exist, JDT
is not clean, or any captured-text match is outside Maven POMs, the unforced
request remains refused.

## Captured-text boundary

The scanner checks:

- Maven POMs and captured Gradle Kotlin DSL inputs already retained by the Java
  scanner;
- regular files with the recognized text extensions in `JavaProjectScanner`;
- regular files under any directory component named `resources`, regardless of
  filename extension.

The recognized extension set covers common build scripts, source-like text,
XML, YAML, properties, JSON, SQL, JPQL/HQL and related configuration/query
formats. A match is a case-sensitive Java-identifier token equal to the simple
type name; the scanner does not parse or resolve the containing language.

The candidate scan is capped at 8,192 inputs, 1 MiB per file and 32 MiB total.
Maven POM and Gradle Kotlin DSL inputs count toward those limits and their
captured contents are revalidated as stable strict UTF-8 before the completeness
proof is emitted. Read errors, invalid UTF-8, unstable files, exceeded limits,
workspace traversal failure, or any non-ignored symbolic link make coverage
incomplete. Symlinks are not followed. `.git`, `.gradle`, `.refactorkit`,
`build`, and `target` trees remain ignored under the existing scanner policy.
NUL-containing binary inputs are outside this textual model; their bytes count
toward the limits but their contents are not searched.

No complete-project claim is made. In particular, the scan does not interpret
Maven/Gradle plugins, resolve aliases or generated build values, understand
framework configuration semantics, decode/compute runtime strings, inspect
binary assets, or capture unrecognized text extensions outside resource trees.
It does not prove all modules or downstream consumers are present in the
snapshot. Java-source binding and lexical evidence remain separate from this
literal text scan.

## Acceptance evidence

The feature
[`java-safe-delete-characterization.feature`](../../features/java-safe-delete-characterization.feature)
has SHA-256
`32093c09c466ffebac7d9deb38103d671fbcdf1548f8dd5d67ecdbee92b6e81a8` and
contains separate exact scenario identities for module-owned and effective
parent/aggregator Maven POM review previews, other captured text references,
incomplete/over-budget coverage under forced mode, and a symlinked input. The
standalone-module POM and reactor-aggregator POM review stories each passed 1/1
case through the shared JVM runner; the aggregator pre-edit receipt records one
selected scenario, ten exact step reuses, one new required step, and no
ambiguity. Its post-edit inventory records eleven exact step reuses and is also
hash-bound. The captured-text refusal outline
passed all six rows, including a resource file named `pom.xml`, which remains
refused. The separate LSP feature has SHA-256
`eb4cc26ea5f7eee21222ae770e534a35a279fc45c5fcf5986b113450d9e8b521`; its
client-edit refusal story passed 1/1 case through the existing LSP runner/glue.
That pre-edit receipt records four new steps, zero ambiguous steps, and zero
duplicate expressions within the effective glue root. Hash-bound receipts,
pre-edit inventories, source-view manifests and Cucumber reports are retained under
`.refactorkit/runs/java-safe-delete-maven-review-only-r001/` and
`.refactorkit/runs/java-safe-delete-nonpom-text-refusal-r001/` and
`.refactorkit/runs/java-safe-delete-lsp-review-only-r001/`; the aggregator
scenario receipts and focused reports are under
`.refactorkit/runs/java-safe-delete-aggregate-root-r001/`.

The existing captured-text refusal, bounded-scan and symlink identities remain
separate and are accepted by their retained evidence under
`.refactorkit/runs/java-safe-delete-workspace-text-r001/`,
`.refactorkit/runs/java-safe-delete-text-scan-bounds-r001/`, and
`.refactorkit/runs/java-safe-delete-symlink-r001/`.

The exact identity
`REQ-JAVA-SAFE-DELETE-WORKSPACE-TEXT-001#managed-safedelete-refuses-incomplete-reference-closure`
proves that a dynamic `Class.forName(configuredTypeName)` consumer with no
literal target still permits preview, then both daemon `refactor.apply` and MCP
`apply_refactoring` refuse with `PLAN_VALIDATION_FAILED` and
`java.safeDelete.referenceClosureIncomplete`. The selected JDK 21 source-built
Cucumber run passed 1/1 case; its focused JSON is under
`modules/refactorkit-jvm/build/reports/cucumber/java-safe-delete-characterization.json`.
The refusal occurs before managed apply scans/locks the workspace or creates a
WAL/transaction, and the story verifies exact workspace bytes and transaction
metadata remain unchanged. A tests-only RED was captured when the MCP gate was
absent: MCP applied the preview and the scenario failed because no structured
refusal was returned. Both managed transports use the same stable refusal code
and message.

This acceptance proves the scoped POM-only Apply refusals and the existing
daemon/MCP incomplete-closure gate; it does not prove full reference closure,
stale-context handling through Apply, positive managed apply/rollback, or CLI
`--apply` behavior. The broader
`REQ-JAVA-SAFE-DELETE-CHAR-001` qualification boundary remains actionable as
described in [Safe Delete](../safe-delete.md) and ARC42.
