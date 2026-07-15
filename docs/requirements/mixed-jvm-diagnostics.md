# Mixed Java/Kotlin JVM diagnostics requirements

Status: accepted by packaged/native GitHub Actions run `29439584115` on Linux
x86-64, Windows x86-64, macOS x86-64 and macOS arm64.

## Scope

Add a deterministic read-only mixed-JVM view over the existing Java JDT and
Kotlin K2 diagnostic providers. This does not weaken either provider's evidence,
precision, lifecycle or refusal boundary and grants no mutation authority.

## Requirements

### RPK-JVM-DIAG-001 — Explicit aggregate route

API `0.2` legacy `diagnostics` shall accept `languageId=jvm` and return one bare
array for the current immutable saved snapshot. Existing `java`, `kotlin`,
TypeScript and JavaScript routes remain compatible.

### RPK-JVM-DIAG-002 — Provider authority

Java rows shall come only from the configured JDT diagnostics adapter and Kotlin
rows only from the configured K2 compiler adapter. The aggregate must not replace
a compiler refusal with structural or lexical diagnostics. Every aggregate row
shall identify its owning `languageId`.

### RPK-JVM-DIAG-003 — Concise missing-input roots

If Kotlin files exist but no Kotlin semantic toolchain is configured, the result
shall retain one deterministic `kotlin.toolchainNotConfigured` root rather than
fabricating source errors. A missing provider must not suppress valid diagnostics
from the other JVM language.

### RPK-JVM-DIAG-004 — Deterministic de-duplication

Rows shall be ordered by language, normalized path, code and message. Exact
provider duplicates with the same code, severity, normalized path/range and
message shall collapse; distinct language evidence or distinct locations shall
remain visible. No heuristic message-prefix cascade suppression is permitted.

### RPK-JVM-DIAG-005 — Precision truth

The aggregate shall preserve each diagnostic's existing evidence, category and
location precision. The legacy array remains unsuitable for exact IDE snapshot
correlation; callers needing typed Kotlin or TypeScript authority continue to use
the dedicated correlated diagnostics envelopes.

### RPK-JVM-DIAG-006 — Read-only acceptance

Tests shall cover a clean mixed Java/Kotlin snapshot without configured Kotlin
inputs, deterministic language tagging, exact duplicate reduction, preservation
of valid Java rows and no workspace or transaction-metadata writes.
