# Kotlin JVM function-symbol requirements

Status: requirement-first contract for the next `0.7.0-SNAPSHOT` K2 semantic-read slice.

## Scope

Add the first bounded compiler-proven Kotlin/JVM callable identity for named
functions. This slice supports search and ID-based definition only. References,
rename and every mutation remain refused.

## Requirements

### RPK-KOT-FUN-001 — Supported functions

The catalogue shall accept non-local named functions declared top-level or as
direct members of a supported class, interface, enum, annotation or named object.
A function is publishable only when the generated owner class contains exactly one
non-synthetic, non-bridge JVM method with the exact source name.

Extension receivers, suspend lowering, erased generic signatures and functions
with default arguments are accepted only through the same exact primary-method
evidence; no Kotlin signature is reconstructed lexically. Local functions,
constructors, accessors, anonymous functions/lambdas, property initializers and
synthetic/default/bridge helpers are not symbols in this slice. Overloaded or
bridge-ambiguous source names refuse the complete declaration result rather than
selecting by guesswork. `@JvmName` and unsupported JVM names likewise refuse when
exact source-to-binary evidence cannot be established.

### RPK-KOT-FUN-002 — Compiler and class-file evidence

The complete bounded source image must compile successfully with the qualified K2
toolchain. The worker shall derive top-level file-facade and member owner binary
names from Kotlin compiler APIs, then read the matching generated class files.
JVM method name and descriptor evidence shall come from those class files, not
from lexical type inference.

### RPK-KOT-FUN-003 — Stable callable identity

Public function IDs shall use
`kotlin-jvm-callable-v1:<sha256>` derived from the tuple:

```text
JVM owner binary name + JVM method name + JVM method descriptor
```

Path, source offset, process identity, compiler handles and presentation kind must
not enter the ID. Offset-only source changes preserve the ID. Parameter or return
JVM signature changes produce a different ID.

### RPK-KOT-FUN-004 — Exact definition range

Function definition ranges shall come from the compiler PSI name identifier,
validate against the immutable overlay source, and use exact zero-based UTF-16 API
coordinates. ID-based definition shall accept both the existing type-ID family and
the new callable-ID family.

### RPK-KOT-FUN-005 — Fail-closed payload

The worker payload shall bind function kind, owner, descriptor, logical identity,
selection text, source path and offsets. Missing/extra fields, malformed owners or
JVM descriptors, unsafe paths, duplicate IDs, missing/ambiguous methods, generated owner-class or
method-evidence overflow, and result overflow invalidate the complete symbol result. No lexical or partial fallback is
permitted.

### RPK-KOT-FUN-006 — Integration truth

The Kotlin symbol backend shall be renamed to the broader experimental identity
`kotlin-compiler-jvm-declarations-k2-v1`. API `0.2`, daemon, CLI and MCP shall
accept both opaque ID families and expose functions as kind `function`. Capability
metadata remains `EXPERIMENTAL/COMPILER/NONE`; property identity, usage-location
definition, references and mutation remain refused.

### RPK-KOT-FUN-007 — Packaged acceptance

Packaged acceptance shall compile top-level and class/interface/object member
functions, verify callable IDs and exact kinds, resolve one member function by ID,
prove properties/lambdas are not published, preserve source bytes and retain stale
snapshot/lease refusal behavior. Cross-platform native qualification remains a
separate release gate.
