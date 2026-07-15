# Managed Kotlin/JVM rename requirements

Status: K2 visibility evidence and the library-level private-type preview planner
are implemented with a conservative Kotlin-only completeness barrier and exact
before/staged compiler diagnostics. Transport authorization, apply/WAL/recovery/
rollback and native qualification remain pending; no Kotlin mutation authority has
been granted.

## Initial qualified row

The first mutation slice targets a non-local `private` Kotlin class, interface,
object, enum class or annotation class in a single-module Kotlin/JVM project with
no Java sources. It reuses the qualified `kotlin-jvm-type-v1` identity and K2 FIR
type-usage evidence. Narrow private-type acceptance is a bootstrap row, not
completion of K4; functions, properties, parameters, type parameters and
cross-language public symbols remain subsequent mandatory rows.

## Requirements

### RPK-KOT-REN-001 — Exact request authority

Rename requires the active Kotlin semantic lease, exact saved snapshot hash,
current index generation, one opaque compiler-proven symbol ID and a new name.
Stale or mismatched authority refuses before planning. Position-only or lexical
symbol discovery cannot authorize mutation.

### RPK-KOT-REN-002 — Compiler-proven eligibility

The worker shall attest declaration kind, source visibility, JVM identity and exact
PSI declaration range in the same atomic payload as usages. The initial row accepts
only an explicitly `private`, non-local source type already present in the complete
catalogue. Missing/ambiguous visibility, generated sources, scripts, compiler
plugins, multiplatform/Android models or external declarations refuse.

### RPK-KOT-REN-003 — Complete bounded reference barrier

A mutation plan may use only a reference set proven complete for its declared
boundary. The initial boundary requires a Kotlin-only module and exact coverage of
declaration, constructor/type names, generic/supertype/cast/check references,
object qualifiers and explicit imports. Any Java source, alias import, star-import
directive, type alias, unresolved supported target, payload truncation or usage
limit refuses with a typed completeness reason. Partial K3 references never become
rename authority merely because they are available.

### RPK-KOT-REN-004 — Name and conflict validation

The new name shall be a bounded non-keyword Kotlin identifier valid as a JVM name,
differ from the current name and produce no source-path, declaration, JVM binary
identity or case-folded collision. RefactorKit shall not guess backtick escaping or
rewrite package identity.

### RPK-KOT-REN-005 — Dynamic and ecosystem risk

Exact quoted-name candidates, reflection/class loading, serialization annotations,
Spring/Jakarta/Jackson metadata, generated consumers and unknown external
consumers shall refuse the initial private row rather than accept an override.
Later public rows may define explicit high-risk approvals, but no warning alone may
replace missing semantic reference evidence.

### RPK-KOT-REN-006 — Normalized deterministic proposal

The K2 proposal shall contain only exact declaration/reference token replacements,
all equal to the old source name, ordered deterministically and normalized through
core workspace-edit validation. Overlap, duplicate disagreement, unsafe path,
generated output, unexpected file operation or content/range mismatch invalidates
the complete proposal. The initial private row does not rename its file.

### RPK-KOT-REN-007 — Staged compiler diagnostics

Preview shall compile the exact immutable before snapshot and the simulated
post-image with the same hash-bound JDK, Kotlin compiler, build projection and
bounded process policy. Any introduced compiler error, provider failure, changed
classpath evidence or incomplete diagnostics refuses. Before and staged
diagnostics plus toolchain/build provenance remain in the plan.

### RPK-KOT-REN-008 — Managed transaction

A successful result is a `PREVIEW` requiring explicit approval. Apply shall
revalidate snapshot, semantic lease, generation, toolchain/build/classpath evidence
and the approved staged snapshot under the workspace lock, then use `PatchEngine`
only. WAL intent precedes writes; crash recovery and rollback restore the complete
pre-image byte-for-byte. Preview and refusal write no workspace metadata.

### RPK-KOT-REN-009 — Capability truth

Until packaged/native mutation acceptance passes, `renameSymbol` remains
`EXPERIMENTAL/COMPILER/PROPOSAL_ONLY` or `REFUSED`; it must not advertise managed
stable authority. CLI, daemon and MCP must expose the same eligibility,
completeness, diagnostics and authorization boundary. LSP rename alone is never
authority.

### RPK-KOT-REN-010 — Initial acceptance

Tests shall cover exact preview edits, constructors/imports/qualifiers, unchanged
unrelated tokens, invalid/conflicting names, local/public declarations, aliases,
star imports, Java sources, strings/reflection, generated paths, stale authority,
malformed/truncated evidence, diagnostics regression, no preview writes, apply,
kill recovery and byte-exact rollback. The packaged operation must pass Linux
x86-64, Windows x86-64, macOS x86-64 and macOS arm64 before the initial row is
qualified.

## K4 completion rows after the bootstrap

K4 is complete only after independently qualified compiler-proven rename rows for
classes/interfaces/objects/functions/properties/parameters/type parameters, exact
constructor/callable/property/import/alias/file updates, at least one Java↔Kotlin
public boundary, framework/dynamic risks, and the common managed transaction gate.
Unsupported rows continue to refuse.
