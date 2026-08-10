# Kotlin/JVM build-model projection

Status: bounded non-executable projection qualified for `0.7.0-SNAPSHOT` by
four-host run `31355580092` and consumed by experimental compiler
diagnostics/declaration reads. Mutation authority stays operation-specific.

## Provider

```text
provider: kotlin-jvm-projection-v1
strategy: kotlin-jvm-source-set-projection
```

The provider view is attached with `KotlinJvmBuildModelIntegration.attach` after
`kotlin-compiler-explicit-v1` discovery succeeds. It projects existing
language-neutral Maven, Gradle, or conventional JVM `BuildModel` evidence; it
does not parse descriptors itself and does not launch the compiler.

## Projected evidence

For every bounded source set containing a `.kt` source, the projection records:

- base provider, model status, module and source-set identity;
- exact workspace-relative Kotlin source and generated-source roots;
- main, test, integration-test, or custom source-set kind;
- separate compile/runtime classpaths and scoped module dependency edges;
- Java and Kotlin output directories;
- declared Kotlin JVM bytecode target;
- declared target JDK/release and the qualified analysis JDK;
- generated-source mutation refusal and script-semantics refusal;
- one typed `BuildLanguageFacet` carrying JVM/platform/compiler/source/target/
  runtime/plugin evidence as `DECLARED`, `DERIVED`, `PARTIAL`, or `UNSUPPORTED`.

Java-only dependency modules remain as source-set-empty graph nodes so a Kotlin
module edge never points to an unknown module. This preserves mixed-JVM graph
identity independently from the separately attested shared Java/Kotlin JVM
identity projector.

The model contains no toolchain paths. It exposes the toolchain provider,
backend, Kotlin/compiler version, and toolchain projection hash. Its own
projection hash binds the pre-attachment snapshot hash, descriptor/classpath
model, source-set graph, diagnostics, and toolchain hash. Reattaching unchanged
inputs is deterministic; changing descriptor, source, classpath, or toolchain
evidence changes the snapshot hash.

## Declarative Maven and Gradle inputs

Existing JVM providers now recognize materialized conventional
`src/main/kotlin` and `src/test/kotlin` roots alongside Java roots. The scanner
routes `.kt` and `.kts` files into mixed snapshots without asking Java analysis
to parse them.

The Maven effective-model provider reads only `kotlin-maven-plugin` declarative
configuration, including bounded `sourceDirs`, `jvmTarget`, `jdkToolchain`
version, and `maven.compiler.release`. It still executes no plugin or lifecycle
goal.

The Gradle declarative provider recognizes literal Kotlin JVM plugin identity,
`sourceSets.*.kotlin.srcDir`, `jvmToolchain(...)`, and `JvmTarget`/string
`jvmTarget` declarations. Gradle remains `PARTIAL` because scripts, plugins,
tasks, convention logic, and the Tooling API are never executed.

Kotlin Multiplatform and Android plugin identities produce typed unsupported
facets and `kotlin.platformUnsupported`; they do not inherit Kotlin/JVM authority.
Compiler-plugin identities likewise force `EXECUTION_REFUSED` because K2 sessions
do not execute kapt, KSP, serialization or other plugin semantics. Unproven
plugin identity or targets remain typed `PARTIAL` evidence.

## Generated sources and scripts

Materialized Maven generated roots and conventional Gradle annotation-processor,
kapt, and KSP roots are analysis-visible. `generatedMutation=refused` prevents
the projection from granting write authority.

`.kts` files remain in the mixed snapshot for routing and diagnostics, but
`scriptSemantics=refused` requires future compiler sessions to exclude them from
ordinary Kotlin/JVM compilation. Scripts never silently inherit `.kt` evidence.

## Limits and execution boundary

The projection reuses core protocol limits for model, module, source-set, root,
and module-edge counts. Limit violations fail closed with typed `kotlin.*`
diagnostics. It performs no network access, credential access, build execution,
compiler execution, filesystem writes, kapt, KSP, annotation processing, or
compiler-plugin loading.

## Compiler-read consumers and next gate

`kotlin-compiler-diagnostics-k2-v1` and
`kotlin-compiler-jvm-declarations-k2-v1` require an `AVAILABLE` projection, revalidate
its toolchain and classpath evidence, and report the exact build and toolchain
projection hashes. Scripts and partial/unsupported models refuse before compiler
launch. The candidate symbol row covers successfully compiled classes, interfaces, enum
classes, annotation classes, named/companion objects, descriptor-exact overloaded
and literal-`@JvmName` functions, source constructors, direct and constructor
properties, value parameters and type parameters. FIR/PSI and exact generated
class-file evidence are both required. The shared JVM module can join exact JDT
and K2 type/method/constructor/field tuples for bounded cross-language references.
Neither read row by itself grants rename/move/change-signature authority; K5 and
unsupported platform/plugin/generated/script shapes retain separate gates.
