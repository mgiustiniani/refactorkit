# Kotlin/JVM semantic toolchain boundary

Status: explicit declarative discovery implemented for `0.7.0-SNAPSHOT`; no
compiler process is launched and Kotlin semantic capabilities remain refused.

## Provider selection

The initial selected provider is:

```text
provider: kotlin-compiler-explicit-v1
backend: kotlin-compiler-embeddable-k2
```

The first qualified discovery row is intentionally narrow:

| JDK | Kotlin compiler artifact | Discovery status | Semantic authority |
|---|---|---|---|
| 21 | `kotlin-compiler-embeddable` 2.0.21 | qualified declarative identity/evidence | none yet |

This selects the Kotlin K2 compiler boundary for future semantic analysis without
claiming that compiler-backed symbols, diagnostics or refactorings are already
implemented.

## Explicit inputs

Library callers provide `KotlinToolchainRequest` with:

- canonical workspace root;
- explicit JDK home;
- explicit `kotlin-compiler-embeddable` JAR;
- ordered additional compiler classpath JARs.

Default discovery does not inspect `PATH`, `JAVA_HOME`, Gradle caches, Maven local
repositories, IDE installations or workspace build configuration. Workspace-local
JDK/compiler inputs are refused unless `allowWorkspaceLocalToolchain` is enabled.
That policy is an explicit trust decision and still does not execute anything.

## Declarative validation

`KotlinToolchainDiscoverer` performs bounded file and archive inspection only:

- JDK home, `release` metadata and native `bin/java` must be canonical,
  non-symlink inputs;
- `JAVA_VERSION` must identify the qualified JDK major;
- compiler classpath entries must be unique bounded JAR files;
- the compiler JAR manifest must identify JetBrains
  `kotlin-compiler-embeddable`;
- the compiler JAR must contain bounded
  `org/jetbrains/kotlin/cli/jvm/K2JVMCompiler.class` identity evidence;
- Kotlin version must match the explicit supported-version policy;
- JAR count, entry count, individual size and total evidence size are bounded.

The discovery path does **not** execute:

```text
java
K2JVMCompiler
Gradle or the Gradle Wrapper
Maven plugins or lifecycle goals
compiler plugins
annotation processors
kapt or KSP
workspace scripts or classes
```

## Provenance

An available toolchain records:

- provider and backend IDs;
- JDK and normalized Kotlin versions;
- full compiler distribution version;
- canonical JDK, Java executable, compiler and classpath paths;
- SHA-256 plus size for JDK release metadata, Java executable, compiler JAR and
  every ordered classpath JAR;
- deterministic projection hash binding versions, paths, content hashes, sizes
  and classpath order.

Critical metadata/identity and every evidence digest are read twice. Drift during
validation returns `kotlin.toolchainChanged`. Future semantic preview/apply must
also revalidate this evidence under the workspace writer lock; discovery alone
never grants mutation authority.

## Limits

Default limits are:

| Input | Limit |
|---|---:|
| Additional compiler classpath entries | 64 |
| Entries per JAR | 100,000 |
| Individual compiler/classpath JAR | 256 MiB |
| Total evidence bytes | 512 MiB |
| JDK `release` metadata | 64 KiB |
| Java executable | 512 MiB |

Failures use typed `kotlin.*` diagnostics and return no partially available
semantic toolchain.

## Build-model integration

[`kotlin-build-model.md`](kotlin-build-model.md) now defines
`kotlin-jvm-projection-v1`, which binds this toolchain hash to bounded Maven,
Gradle and conventional JVM source-set evidence inside `ProjectSnapshot`.

## Next gate

The next slice defines a bounded compiler lifecycle and starts a compiler-backed
read-only analysis session from the exact toolchain/build projection.
`KotlinAdapterRegistration` remains `kotlin-analysis-unavailable-v1` and all
capabilities stay `REFUSED/NONE` until that session produces exact tested
evidence.
