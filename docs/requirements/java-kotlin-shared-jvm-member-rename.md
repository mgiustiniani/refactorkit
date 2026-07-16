# Java/Kotlin shared JVM member rename requirements

Status: qualified bounded K5/J1 row. Both directions—public Kotlin function with
Java callers and public Java method with Kotlin callers—have library,
daemon/CLI/MCP, local packaged and four-platform native apply/rollback evidence.
Broader member shapes remain separate rows.

## First qualified member

The first row is one public, non-overloaded, non-override, non-operator Kotlin
function with a compiler/class-file-proven JVM owner, name and method descriptor.
It is called directly by both Kotlin and Java source in one proven JVM module.

K2 shall provide the exact declaration and Kotlin call tokens. JDT shall load the
class generated from the exact immutable K2 overlay and resolve exact Java method
binding tokens whose owner and method name match the attested Kotlin callable.
Simple-name matching, lexical call scanning and partial K3 references cannot
authorize mutation.

## Refusal boundary

The row shall refuse:

- overloads, bridges or ambiguous JVM methods;
- overrides, operators, infix functions, callable references and callable imports;
- default-argument Java bridge ambiguity, `@JvmName`, synthetic accessors or
  compiler-plugin-generated members without exact mapping;
- generated sources, dirty K2/JDT baselines, dynamic quoted names, reflection,
  serialization/framework strings or unknown external consumers without explicit
  acceptance;
- stale snapshot, semantic lease, index generation, toolchain/build/classpath or
  binary evidence;
- timeout, cancellation, protocol/range/output limits or partial provider results.

## Preview and transaction

A successful preview updates only the exact Kotlin declaration/direct calls and
JDT-bound Java invocations. K2 and JDT must resolve the renamed staged callable;
introduced errors refuse. Apply requires explicit external-consumer-risk approval,
revalidates semantic authority under lock and commits all edits through one
`PatchEngine` WAL transaction with byte-exact rollback.

## Symmetric row

A public Java method used by Kotlin is independently qualified. JDT must prove the
Java declaration/calls, bounded annotation-processing-free ECJ must produce the
exact hash-bound method owner/descriptor, and K2 must report exact external JVM
callable usages. The implemented row restricts authority to one public non-overloaded,
non-override JDT-bound method; it does not inherit authority from type usages or
from the Kotlin-function row.

## Acceptance

Both directions require unit/refusal tests, daemon/CLI/MCP preview/apply,
packaged apply/rollback and Linux x86-64, Windows x86-64, macOS x86-64 and macOS
arm64 qualification before this member row is complete.
