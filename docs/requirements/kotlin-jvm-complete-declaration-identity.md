# Kotlin/JVM complete declaration identity

Status: implemented candidate; promotion requires focused regression, four-platform execution, and independent review.

## Requirement

`REQ-KOTLIN-JVM-IDENTITY-001` completes the bounded API `0.2` Kotlin/JVM declaration catalogue without exposing compiler handles.

For successfully compiled, single-module Kotlin/JVM snapshots using the exact qualified K2 toolchain, RefactorKit shall:

1. retain existing compiler/class-file-backed type, object, function, property, value-parameter, and type-parameter identities;
2. identify overloaded ordinary functions by exact owner, JVM name, and descriptor rather than source-name uniqueness;
3. honor only a compiler-validated literal `@JvmName` and bind the resulting binary name to the exact generated method;
4. expose every source-declared primary or secondary constructor with kind `CONSTRUCTOR`, owner, `<init>`, exact JVM descriptor, exact PSI anchor, and resolved constructor-call usages;
5. expose a primary-constructor `val`/`var` as both its descriptor/ordinal value-parameter identity and its exact backing-field property identity, selecting K2-resolved usages by FIR symbol kind rather than source position alone;
6. derive opaque IDs from JVM identity, never from path, line, offset, process ID, or compiler object identity;
7. retain exact generated class/method/field verification and fail closed on missing, ambiguous, colliding, oversized, malformed, or stale evidence;
8. attach JVM descriptors to external callable and constructor usages so overloads are never joined by owner/name alone.

## Stable identity families

- `kotlin-jvm-type-v1:<sha256>`
- `kotlin-jvm-callable-v1:<sha256>`
- `kotlin-jvm-constructor-v1:<sha256>`
- `kotlin-jvm-property-v1:<sha256>`
- `kotlin-jvm-parameter-v1:<sha256>`
- `kotlin-jvm-type-parameter-v1:<sha256>`

## Bounded scope

The row does not claim local declarations, anonymous objects, implicit constructors without a source anchor, callable-reference mutation, arbitrary annotation evaluation, compiler plugins, scripts, Multiplatform, Android, or general K5 change-signature/extract/inline authority. Special lowered shapes may retain unique exact class-file evidence; ambiguity always refuses.

## Executable evidence

- `KotlinCompilerDiagnosticsTest.overloadedAndJvmRenamedFunctionsUseExactFirToJvmSignatures`
- `KotlinCompilerDiagnosticsTest.sourceDeclaredConstructorsUseOwnerAndExactJvmDescriptorIdentity`
- `KotlinCompilerDiagnosticsTest.jvmRenamedFunctionUsesCompilerValidatedLiteralBinaryName`
- full `refactorkit-kotlin` regression
