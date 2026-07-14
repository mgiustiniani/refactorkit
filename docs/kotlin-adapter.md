# Kotlin adapter

Status: first compiler-backed Kotlin/JVM capability implemented for
`0.7.0-SNAPSHOT`. Diagnostics are experimental; every symbol and mutation
capability remains explicitly refused.

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

All other `.kt` operations and all `.kts` script semantics remain
`REFUSED/NONE/NONE`. Calls made without an explicitly configured compiler return
`kotlin.toolchainNotConfigured`; no lexical or structural diagnostic fallback is
fabricated. Refactoring requests still return edit-free refused plans and cannot
reach `PatchEngine`.

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

## Integration surfaces

Daemon API:

```text
kotlin.semantic.start
kotlin.semantic.stop
kotlin.diagnostics
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
```

MCP exposes `kotlin_semantic_start`, `kotlin_semantic_stop`, and
`kotlin_diagnostics`. LSP and all capability surfaces expose the same truthful
experimental compiler capability metadata, but editor-native publication remains
the editor's responsibility in this slice.

## Remaining gates

Compiler-backed symbols, durable symbol identity, definition, references,
Java/Kotlin interoperability, immutable editor overlays and every mutation remain
unimplemented and explicitly refused. Managed Kotlin rename still requires exact
semantic identity, preview and staged diagnostics, authorization, `PatchEngine`,
WAL, native recovery and rollback qualification.
