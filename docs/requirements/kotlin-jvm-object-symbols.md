# Kotlin JVM object-symbol requirements

Status: requirement-first contract for the next `0.7.0-SNAPSHOT` K2 semantic-read slice.

## Scope

Extend the compiler-proven Kotlin/JVM declared-type catalogue to named object and
companion-object declarations. This remains read-only navigation evidence under
API `0.2`; it does not authorize rename or any mutation.

## Requirements

### RPK-KOT-OBJ-001 — Semantic object kind

Core shall expose additive symbol kind `OBJECT`. Kotlin named objects shall not be
misreported as classes, modules, constants or unknown symbols. API, daemon, CLI,
MCP and LSP projections shall preserve or truthfully map the object kind.

### RPK-KOT-OBJ-002 — Supported object declarations

The catalogue shall accept non-local top-level objects, nested named objects, data
objects and companion objects when the compiler supplies a non-local `ClassId`
and matching generated JVM class file. Companion objects retain compiler name
`Companion` and JVM owner identity such as `Owner$Companion`.

Anonymous object expressions and local/anonymous generated classes are not
independent catalogue symbols. Functions and properties inside objects remain
outside this slice.

### RPK-KOT-OBJ-003 — Stable opaque identity

Objects shall use the existing `kotlin-jvm-type-v1:<sha256>` family derived only
from the proven JVM binary name. The public ID shall not contain kind, source
path, source offset, process identity or compiler handles. Offset-only source
changes preserve the ID.

### RPK-KOT-OBJ-004 — Exact definition

Named object ranges shall come from the compiler PSI name identifier. An implicit
companion has no source name identifier, so its exact declaration selection is
the compiler PSI `object` keyword while its semantic name remains `Companion`.
Every selection shall validate against the immutable overlay source and use exact
zero-based UTF-16 API coordinates. ID-based definition shall resolve named and
companion objects to those ranges.

### RPK-KOT-OBJ-005 — Fail-closed evidence

Missing class files, unsafe/non-ASCII JVM identity shapes, duplicate identity,
malformed paths/ranges, result overflow or unknown worker kinds invalidate the
complete symbol payload. No lexical or partial fallback is permitted.

### RPK-KOT-OBJ-006 — Capability boundary

The API `0.2` Kotlin symbols schema shall allow `object` in addition to the
already qualified declared-type kinds. Capability metadata remains
`EXPERIMENTAL/COMPILER/NONE`; usage-location definition, references, callable
identity and every Kotlin mutation remain explicitly refused.

### RPK-KOT-OBJ-007 — Packaged acceptance

Packaged acceptance shall query a fixture containing top-level, nested and
companion objects, verify `object` kinds and opaque IDs, resolve a companion
object definition, prove anonymous objects are not published, preserve source
bytes, and retain stale snapshot/lease refusal behavior.
