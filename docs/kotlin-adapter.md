# Kotlin adapter

Status: `0.7.0-SNAPSHOT` module boundary implemented; semantic backend unavailable
and every Kotlin capability explicitly refused.

## Current boundary

`modules/refactorkit-kotlin` is an internal Gradle module that depends only on
`refactorkit-core`. It is not a separate process or product. The boundary keeps
future Kotlin compiler/Analysis API dependencies out of core and the JDT-backed
Java adapter while shared JVM build/source-set and identity contracts remain
language-neutral.

The initial backend identity is:

```text
kotlin-analysis-unavailable-v1
```

The adapter owns `.kt` and `.kts` routing, but capability metadata distinguishes
ordinary Kotlin source from Kotlin scripts. All advertised operations currently
report:

```text
stability: REFUSED
evidence: NONE
mutationAuthority: NONE
```

Direct library calls return typed diagnostic/refusal code:

```text
kotlin.semanticBackendUnavailable
```

No symbol, reference, source diagnostic or refactoring result is synthesized
through lexical fallback. The only diagnostic reports the unavailable backend;
refused plans contain no workspace edits and cannot reach `PatchEngine` mutation.

## Why Kotlin is separate from Java

Java and Kotlin share JVM build/source-set and binary identity concepts but use
different semantic engines. Java uses Eclipse JDT; Kotlin will use a versioned
Kotlin compiler/Analysis API backend. Keeping the adapters separate prevents
compiler dependencies and backend assumptions from entering core or being hidden
inside Java-specific planners.

TypeScript and JavaScript remain together because the TypeScript compiler,
tsserver, language server and project configuration graph analyze both languages
as one semantic family. Module boundaries follow semantic backend ownership, not
one module per file extension.

## Next gate

Explicit JDK/compiler discovery and hash-bound provenance are now implemented in
[`kotlin-toolchain.md`](kotlin-toolchain.md), but no compiler is launched. The
next slice projects that provenance and a bounded Kotlin/JVM source-set model into
the snapshot before adding read-only compiler analysis. Capabilities remain
refused until real compiler-backed native acceptance satisfies
[`v0.7.0-plan.md`](releases/v0.7.0-plan.md).
