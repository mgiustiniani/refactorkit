# Kotlin/JVM managed move-declaration requirement

Status: active K5 row. The bounded library implementation and packaged CLI/daemon
acceptance are complete; MCP packaged and four-platform native qualification are
pending.

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
2. The declaration is the only top-level declaration in its `.kt` file, has an
   explicit non-default package and belongs to one authoritative, non-generated
   Kotlin/JVM source set.
3. The destination is a valid different package in the same source set and its
   destination file and binary identity do not exist.
4. Every Kotlin use is K2-resolved to the source identity. Cross-package Kotlin
   consumers use one explicit, non-aliased, non-star import of the old identity.
5. Every Java use is JDT-bound to the matching ephemeral K2 class. Java consumers
   use one explicit non-static import of the old identity.
6. The caller explicitly accepts public external-consumer risk.

The first row does not infer Gradle/Maven module or dependency changes.

## Exact edit contract

The preview shall contain only:

- the declaration file package-name token changed to the destination package;
- one file rename from the authoritative source-root-relative old path to the
  destination package path, preserving the filename;
- exact explicit Kotlin and Java import-name tokens changed from the old binary
  identity to the new binary identity.

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
4. resolve every staged Java use to the moved identity;
5. reject introduced diagnostics under normal mode and any staged diagnostic
   under strict mode.

No source, class or JAR may be published into the user workspace during preview.

## Stable refusal boundaries

The operation shall refuse with stable structured codes for at least:

- missing, ambiguous, non-public, local, nested or unsupported declarations;
- multiple top-level declarations or filename/declaration ambiguity;
- default package, invalid target package or cross-source-set destination;
- destination file/type conflict;
- generated roots, scripts, compiler plugins, multiplatform `expect`/`actual`,
  Android or unsupported build models;
- alias/star/static imports, qualified-expression uses or same-package implicit
  consumers outside the first row;
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
- Kotlin-only and mixed Kotlin/Java fixtures;
- preview-read-only, apply, diagnostics, WAL and rollback tests;
- packaged daemon/CLI/MCP smoke;
- Linux x86-64, Windows x86-64, macOS x86-64 and macOS arm64 qualification.
