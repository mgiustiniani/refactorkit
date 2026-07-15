# Java/Kotlin shared JVM rename requirements

Status: active requirement-first K5/J1 prerequisite; no cross-language mutation
authority is granted by this document.

## First qualified row

The first success row renames one public top-level Kotlin class used directly by
Java and Kotlin sources in one proven JVM module. A symmetric public Java type
used by Kotlin is required before this requirement is complete. Member identity
follows only after both type directions are independently qualified.

## Shared identity

A Kotlin type declaration and a JDT binary/source binding may be joined only by a
compiler-proven JVM binary name. The evidence record shall include:

- Kotlin symbol ID and exact declaration token;
- JVM binary name and class-file provenance produced from the exact immutable
  snapshot with the attested K2 toolchain;
- JDT binding key, qualified name and exact Java use token;
- build/toolchain/classpath projection hashes;
- snapshot hash, semantic lease and workspace-index generation.

Simple names, package text, imports, filenames or lexical matches cannot join the
adapters. Ambiguous, local, anonymous, generated or multiplatform declarations
refuse.

## Preview

A successful Kotlin-to-Java public type preview shall update, as one normalized
workspace edit:

1. the Kotlin declaration and compiler-resolved Kotlin uses;
2. constructor calls and exact imports/qualified Java uses proven by JDT;
3. the Kotlin source filename only when Java/Kotlin public-file identity requires
   and the destination is collision-free;
4. no strings, comments, generated output or unrelated same-name tokens.

The staged snapshot shall be analyzed by both K2 and JDT under the same aggregate
deadline. Introduced errors from either adapter refuse. Existing baseline errors
are tolerated only under the normal diagnostics-delta policy.

## Transaction

Daemon, CLI and MCP shall preserve preview authority through explicit apply.
Apply must revalidate snapshot, lease, index generation, toolchain, build model,
classpath and compiled binary evidence while holding the workspace mutation lock.
All Kotlin, Java and optional filename edits pass through one `PatchEngine` plan,
WAL and rollback transaction. Preview and semantic evidence generation remain
read-only outside disposable overlays.

## Required refusal tests

Stable typed refusals are required for:

- missing or mismatched class-file/JDT binding identity;
- unresolved Java or Kotlin references;
- public/external consumer scope not explicitly accepted;
- reflection/configuration/serialization/framework strings;
- generated sources or annotation/compiler-plugin output;
- source filename collision;
- stale snapshot, lease, index generation or toolchain/build evidence;
- timeout, cancellation, output/range/protocol limit or partial adapter result.

No private-only K4 identity may be promoted to public or cross-language authority.

## Acceptance

The requirement closes only when both Java-to-Kotlin and Kotlin-to-Java public
type rows have unit, protocol, packaged apply/rollback and native four-platform
evidence, including byte-exact recovery through the shared transaction engine.
Until then mixed snapshots retain `kotlin.renameCrossLanguageIncomplete` or a
more specific stable refusal.
