# Kotlin adapter

Status: initial compiler-backed Kotlin/JVM read capabilities implemented for
`0.7.0-SNAPSHOT`. Diagnostics and bounded regular-class symbol navigation are
experimental; references, callable/member identity and every mutation remain
explicitly refused.

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
backend: kotlin-compiler-jvm-types-k2-v1
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
runtime classpath through `kotlin.semantic.start`, the CLI, or MCP. Startup attaches
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

## Compiler-proven JVM type symbols

After a successful K2 compilation, the same isolated worker creates a separate
compiler PSI environment over the exact overlay sources. The initial catalogue is
intentionally limited to regular class declarations, including nested classes.
For every returned declaration the worker requires:

- an ASCII JVM-safe package and class-name shape;
- a non-local compiler `ClassId`;
- a matching generated `.class` file below the overlay output directory;
- an exact compiler PSI name-identifier range;
- a unique JVM binary identity; and
- no more than 500 symbols.

The public opaque ID is `kotlin-jvm-type-v1:<sha256>` derived from the durable JVM
binary name, never from a source offset, path, process ID, or compiler object.
Locations are exact zero-based UTF-16 name ranges and are accepted only when the
reported source substring equals the symbol name. Worker paths are remapped from
the immutable overlay and must identify a source in the attested snapshot.

The whole symbol read refuses rather than returning a partial regular-class index
when the snapshot does not compile, an unsupported class-like declaration such as
an interface/object/enum/annotation is encountered, a JVM name is unsupported,
identity collides, binary evidence is missing, or any result/location limit is
exceeded. Callables, type aliases, local classes and anonymous classes are outside
the initial catalogue and are not presented as indexed symbols. Definition accepts only an
opaque ID returned by this backend and resolves it against a newly attested copy
of the same saved snapshot. Usage-location resolution, functions, properties,
parameters, type aliases, interfaces, objects, enums, annotation classes and
references remain refused until their semantic identities are separately
qualified.

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
  --symbol kotlin-jvm-type-v1:<sha256> \
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

Callable/member symbols, usage-location definition, references, Java/Kotlin
interoperability, immutable editor overlays and every mutation remain
unimplemented and explicitly refused. The regular-class symbol ID is sufficient
only for saved-snapshot search-to-definition navigation; it is not rename
authority. Managed Kotlin rename still requires complete semantic references,
preview and staged diagnostics, authorization, `PatchEngine`, WAL, native
recovery and rollback qualification.
