# Kotlin adapter

Status: initial compiler-backed Kotlin/JVM read capabilities implemented for
`0.7.0-SNAPSHOT`. Diagnostics plus bounded JVM declared-type and function symbol
navigation are experimental; references, property/member shapes outside the first
function row and every mutation remain explicitly refused.

## Capability boundary

`modules/refactorkit-kotlin` remains isolated from core and the JDT-backed Java
adapter. The descriptor still uses the fail-closed default backend identity:

```text
kotlin-analysis-unavailable-v1
```

The diagnostics capability overrides that default with:

```text
operation: diagnostics
stability: EXPERIMENTAL
evidence: COMPILER
backend: kotlin-compiler-diagnostics-k2-v1
mutationAuthority: NONE
executionMode: EXTERNAL_PROCESS
snapshotModes: saved-disk
```

The `workspaceSymbols`, `documentSymbols`, and ID-based `definition` capabilities
use:

```text
stability: EXPERIMENTAL
evidence: COMPILER
backend: kotlin-compiler-jvm-declarations-k2-v1
mutationAuthority: NONE
executionMode: EXTERNAL_PROCESS
snapshotMode: saved-disk
```

All remaining `.kt` operations and all `.kts` script semantics remain
`REFUSED/NONE/NONE`. Calls made without an explicitly configured compiler return
`kotlin.toolchainNotConfigured`; no lexical or structural fallback is fabricated.
Refactoring requests still return edit-free refused plans and cannot reach
`PatchEngine`.

## Compiler diagnostics session

The first semantic capability is a one-shot external K2 compiler worker. A caller
must first configure the exact JDK 21 and `kotlin-compiler-embeddable` 2.0.21
runtime classpath through `kotlin.semantic.start`, the CLI, or MCP. The qualified
runtime includes matching `kotlin-stdlib` 2.0.21 and JetBrains annotations 13.0;
missing components refuse before compiler execution. Startup attaches
the exact `kotlin-jvm-projection-v1` build model and returns a new semantic lease
and snapshot hash.

The worker:

- revalidates every toolchain hash/size and project classpath/model fingerprint;
- requires one proven Kotlin/JVM source module, an `AVAILABLE` projection, one
  declared JVM target, and one compatible target JDK;
- refuses scripts, Android, Multiplatform, partial models, workspace classpath
  outputs, missing evidence, more than 96 source files, or more than 128 project
  classpath entries;
- materializes a source-only immutable `SemanticWorkspaceOverlay`;
- launches the explicit JDK with a cleared environment, overlay-confined temporary
  and user-home directories, 512 MiB compiler heap, 30-second timeout,
  one-process limit, 8 MiB stdout and 64 KiB stderr;
- invokes `K2JVMCompiler.execAndOutputXml` through a Java 8-compatible reflective
  bridge and never loads compiler classes into the RefactorKit process;
- accepts only an allowlist of compiler arguments and rejects compiler plugins,
  scripts, kapt/KSP, javac integration and arbitrary options;
- parses XML with DTD/entities/external access disabled;
- verifies snapshot attestation and that overlay sources were not modified;
- reports process executable/argument hashes and PID without exposing toolchain
  paths in protocol output.

Kotlin compiler XML provides line/column starts but no trustworthy end offsets.
Diagnostics therefore report explicit `LINE_ONLY` precision instead of inventing
Monaco ranges. Project-level diagnostics report `NONE`.

## Compiler-proven JVM declaration symbols

After a successful K2 compilation, the same isolated worker creates a separate
compiler PSI environment over the exact overlay sources. The qualified catalogue
contains top-level and nested regular classes, interfaces, enum classes,
annotation classes, named/data objects and companion objects.
For every returned declaration the worker requires:

- an ASCII JVM-safe package and class-name shape;
- a non-local compiler `ClassId`;
- a matching generated `.class` file below the overlay output directory;
- an exact compiler PSI declaration selection (name identifier, or the `object`
  keyword for an implicit companion);
- a unique JVM binary identity; and
- no more than 500 symbols.

The public opaque ID is `kotlin-jvm-type-v1:<sha256>` derived from the durable JVM
binary name, never from a source offset, path, process ID, or compiler object.
Locations are exact zero-based UTF-16 declaration selections and are accepted
only when the reported compiler PSI selection text equals the source substring.
Except for implicit `Companion` selecting `object`, the selection equals the
symbol name. Worker paths are remapped from
the immutable overlay and must identify a source in the attested snapshot.

The same worker also publishes a first bounded function row. It derives the
Kotlin file-facade or containing-type JVM owner through compiler APIs, reads the
generated owner class with bounded ASM, and accepts a source function only when
exactly one non-synthetic/non-bridge JVM method has the same name. Its public ID
is `kotlin-jvm-callable-v1:<sha256>` over owner binary name, JVM method name and
method descriptor. Top-level and direct class/interface/enum/object member
functions are supported. Extension receivers, suspend lowering, erased generic
signatures and default arguments are accepted only through that same primary-method
evidence. Overloads, bridge ambiguity, `@JvmName`, local functions, constructors,
accessors and synthetic/default helpers refuse or remain excluded rather than
being guessed.

The whole symbol read refuses rather than returning a partial declaration index
when the snapshot does not compile, a JVM name/descriptor is unsupported,
identity collides, binary or callable evidence is missing/ambiguous, or any
result/location limit is exceeded. Enum entries, properties, type aliases, local
classes/functions and anonymous object expressions are outside the catalogue and
are not presented as indexed symbols. Definition
accepts only an opaque ID returned by this backend and resolves it against a newly
attested copy of the same saved snapshot. Named/data/nested objects expose exact
name ranges. An implicit companion has compiler identity/name `Companion` and an
exact range over its `object` keyword because no source identifier exists.
Usage-location resolution, properties, parameters, type aliases, overloaded or
renamed JVM callables and references remain refused until their semantic
identities are separately qualified.

## Integration surfaces

Daemon API:

```text
kotlin.semantic.start
kotlin.semantic.stop
kotlin.diagnostics
kotlin.symbols
kotlin.definition
```

`kotlin.diagnostics` requires the exact lease and snapshot returned by startup and
returns structured `ready`, `refused`, or `error` status plus toolchain/build
projection hashes and process attestation. Legacy `diagnostics` accepts
`languageId=kotlin` after startup.

CLI:

```bash
refactorkit kotlin diagnostics <root> \
  --jdk-home <jdk-21> \
  --compiler-jar <kotlin-compiler-embeddable-2.0.21.jar> \
  --compiler-classpath <path-separated-runtime-jars>

refactorkit kotlin symbols <root> \
  --jdk-home <jdk-21> --compiler-jar <compiler.jar> \
  --compiler-classpath <runtime-jars> --query Greeting

refactorkit kotlin definition <root> \
  --symbol kotlin-jvm-callable-v1:<sha256> \
  --jdk-home <jdk-21> --compiler-jar <compiler.jar> \
  --compiler-classpath <runtime-jars>
```

MCP exposes `kotlin_semantic_start`, `kotlin_semantic_stop`,
`kotlin_diagnostics`, `kotlin_symbols`, and `kotlin_definition`. Kotlin navigation
uses these correlated language-specific envelopes; legacy generic symbol routes
are not promoted to saved-snapshot authority. The daemon response is constrained
by `docs/api-0.2-kotlin-symbols-schema.json`. LSP and all capability surfaces
expose the same truthful experimental compiler capability metadata, but
editor-native publication remains the editor's responsibility in this slice.

## Remaining gates

Property/constructor/parameter identity, overloaded callable identity,
usage-location definition, references, Java/Kotlin interoperability, immutable
editor overlays and every mutation remain unimplemented and explicitly refused.
The declaration IDs are sufficient only for saved-snapshot search-to-definition
navigation; they are not rename
authority. Managed Kotlin rename still requires complete semantic references,
preview and staged diagnostics, authorization, `PatchEngine`, WAL, native
recovery and rollback qualification.
