# Kotlin/JVM managed move-declaration requirement

Status: qualified bounded K5 row for whole-file movement with private helpers,
public sibling types and exact explicit/FIR-aliased/package-star/same-package/
fully-qualified Kotlin/Java consumers. Library, packaged CLI/daemon/MCP and all
four native platforms pass. Broader shapes remain pending.

## Purpose

`moveDeclaration` moves one compiler-proven Kotlin/JVM top-level public type to a
different package in the same authoritative JVM source set. It updates exact
Kotlin and Java consumers and produces one previewable, diagnostic-gated,
rollbackable workspace edit.

This first row is deliberately narrower than general declaration, file, package
or module movement. Unsupported shapes refuse rather than inheriting semantic
authority from text matching.

## Supported first row

The operation shall accept only when all of the following are proven:

1. K2 and generated class evidence identify exactly one public top-level class,
   interface, object, enum or annotation class.
2. The target and any co-located public top-level types are compiler-proven and
   move with the whole file. Additional public types are supported only when all
   Java consumers use exact explicit imports, one exact non-static old-package
   star import, exact old-package implicit resolution, or exact fully-qualified
   identities at every proven token. Kotlin consumers use exact explicit imports,
   optionally with one FIR-proven alias, one exact old-package star import, exact
   old-package implicit resolution, or exact fully-qualified identities at every
   proven token.
   Other co-located helpers are compiler-proven `private` types, functions or
   properties. The file has an
   explicit non-default package and belongs to one authoritative, non-generated
   Kotlin/JVM source set.
3. The destination is a valid different package in the same source set and its
   destination file and binary identity do not exist.
4. Every Kotlin use is K2-resolved to the source identity. A consumer either uses
   one explicit import of the old identity, optionally with one exact Kotlin
   alias, has one exact non-static old-package star import, belongs to the exact
   old package and resolves the type implicitly, or uses the exact old
   fully-qualified identity at every proven target token.
5. Every Java use is JDT-bound to the matching ephemeral K2 class. A Java consumer
   either uses one explicit non-static import of the old identity, has one exact
   non-static old-package star import, belongs to the exact old package and resolves the type implicitly, or uses the exact old
   fully-qualified identity at every proven target token.
6. Kotlin and Java consumer sets are independently optional. A type with no
   in-workspace consumers may still move when K2 proves its declaration and the
   caller accepts unknown external-consumer risk; the operation does not invent
   consumer requirements.
7. The caller explicitly accepts public external-consumer risk.

The first row does not infer Gradle/Maven module or dependency changes.

## Exact edit contract

The preview shall contain only:

- the declaration file package-name token changed to the destination package;
- one file rename from the authoritative source-root-relative old path to the
  destination package path, preserving the filename;
- exact explicit Kotlin and Java import-name tokens changed from the old binary
  identity to the new binary identity, preserving an exact Kotlin alias token; or
- for a compiler-proven same-package implicit consumer, one deterministic
  explicit import of the new identity inserted immediately after its exact
  package declaration, preserving the file's newline convention; or
- for one exact old-package star import, preserve that import and insert one
  explicit destination-identity import immediately after it, preserving newline
  and Java semicolon conventions; or
- each exact fully-qualified old identity whose final type token is independently
  proven by K2 or JDT changed to the destination identity.

Comments, strings, unrelated same-spelled identifiers and build output shall not
be edited. Edits must be normalized and non-overlapping.

## Evidence and staged validation

Before planning:

- the request snapshot, semantic lease and index generation shall still match;
- K2 declaration/usages and JDT Java references shall be complete;
- the Kotlin toolchain, build projection and ephemeral classes shall be
  hash-attested;
- partial symbol or usage evidence shall refuse.

The staged overlay shall then:

1. compile Kotlin with K2;
2. resolve the moved binary identity and every staged Kotlin use;
3. supply staged K2 classes to JDT;
4. when Java consumers existed before planning, resolve every staged Java use to
   the moved identity; when none existed, do not require fabricated JDT uses;
5. reject introduced diagnostics under normal mode and any staged diagnostic
   under strict mode.

No source, class or JAR may be published into the user workspace during preview.

## Stable refusal boundaries

The operation shall refuse with stable structured codes for at least:

- missing, ambiguous, non-public, local, nested or unsupported declarations;
- any additional protected/internal top-level declaration, any additional public
  function/property, or any ambiguous same-package, partially-qualified, mixed
  qualified/import style,
  static/multiple/malformed star, malformed/multiple alias consumer of an
  additional public type;
  unsupported/typealias/file-facade ambiguity or filename/declaration ambiguity;
- default package, invalid target package or cross-source-set destination;
- destination file/type conflict;
- generated roots, scripts, compiler plugins, multiplatform `expect`/`actual`,
  Android or unsupported build models;
- multiple, static or malformed star imports; multiple or malformed aliases;
  partially qualified expressions, mixed qualified and unqualified uses in one consumer, ambiguous
  same-package declarations or import-name conflicts;
- incomplete K2/JDT evidence, callable/reference ambiguity or stale authority;
- reflection, serialization or framework candidates requiring a broader row;
- absent `acceptExternalConsumerRisk=true`.

## Mutation and transports

Daemon API `0.2`, CLI and MCP shall expose the same operation without changing
existing request fields. Preview is read-only. Apply requires explicit
authorization and revalidates snapshot/toolchain/build evidence under the
workspace lock. Kotlin, Java and file edits apply in one `PatchEngine`
transaction with WAL, recovery and byte-exact rollback.

## Acceptance

Promotion requires:

- executable success and refusal tests;
- independently executable zero-consumer, Kotlin-only, Java-only and mixed
  Kotlin/Java fixtures;
- preview-read-only, apply, diagnostics, WAL and rollback tests;
- packaged daemon/CLI/MCP smoke;
- Linux x86-64, Windows x86-64, macOS x86-64 and macOS arm64 qualification.
