# Kotlin JVM declared-type symbol requirements

Status: requirement-first contract for the next `0.7.0-SNAPSHOT` K2 semantic-read slice.

## Scope

Extend the qualified saved-snapshot Kotlin/JVM type catalogue from regular
classes to interfaces, enum classes, and annotation classes. This remains a
read-only compiler-backed capability under API `0.2`; it is a prerequisite for,
not authority to perform, managed Kotlin rename.

## Requirements

### RPK-KOT-TYPE-001 — Compiler and binary evidence

A declaration may be published only after the complete bounded Kotlin source
image compiles successfully with the configured K2 compiler and a regular class
file exists for its exact compiler `ClassId`-derived JVM binary name. Lexical or
structural fallback is forbidden.

### RPK-KOT-TYPE-002 — Supported declaration kinds

The catalogue shall accept non-local top-level and nested:

- regular classes as `CLASS`;
- interfaces as `INTERFACE`;
- enum classes as `ENUM`;
- annotation classes as `ANNOTATION`.

Enum entries are not independent type symbols. Objects, companion objects, type
aliases, local/anonymous declarations, functions, properties, constructors,
parameters and type parameters remain unsupported in this slice. Encountering an
unsupported class-like declaration shall refuse the complete result rather than
publish a partial index.

### RPK-KOT-TYPE-003 — Stable opaque identity

Every supported declaration shall retain `kotlin-jvm-type-v1:<sha256>` identity
derived only from the proven JVM binary name. Kind, path, source offset, process
identity and compiler-internal handles shall not enter the public ID. Moving a
declaration within the same source file without changing its JVM binary name
shall preserve the ID.

### RPK-KOT-TYPE-004 — Exact source ranges

Name ranges shall come from compiler PSI, validate against the immutable overlay
source, and use exact zero-based UTF-16 API coordinates. Top-level and nested type
kinds shall support ID-based definition to the same exact range.

### RPK-KOT-TYPE-005 — Bounded fail-closed payload

The existing source, symbol, name, identity, stdout, process and timeout limits
remain active. Unknown kinds, duplicate IDs, unsafe paths, missing binaries,
malformed ranges or extra/missing worker fields shall invalidate the complete
symbol payload.

### RPK-KOT-TYPE-006 — Protocol and integration truth

Library, daemon, CLI and MCP shall expose the same supported type kinds using the
existing backend and opaque type-ID family. The API `0.2` Kotlin symbols schema
shall permit exactly `class`, `interface`, `enum`, and `annotation`. Capability
metadata remains `EXPERIMENTAL/COMPILER/NONE`; references and all mutations remain
explicitly refused.

### RPK-KOT-TYPE-007 — Packaged acceptance

The self-contained packaged Kotlin smoke shall compile and query a fixture
containing all four supported kinds, verify their kinds and opaque IDs, resolve
at least one non-class definition, preserve source bytes, and retain stale
snapshot/lease refusal behavior on every qualified native platform.
