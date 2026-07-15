# Kotlin JVM function-usage navigation requirements

Status: accepted by packaged/native GitHub Actions run `29432766317` on Linux
x86-64, Windows x86-64, macOS x86-64 and macOS arm64.

## Scope

Add saved-snapshot definition-from-usage and bounded references for the already
qualified non-overloaded Kotlin/JVM function catalogue. This slice does not add a
new callable identity and grants no mutation authority.

## Requirements

### RPK-KOT-USE-001 — K2 resolved-call evidence

A function usage is publishable only when a successful K2 FIR resolve produces a
real-PSI `FirResolvedNamedReference` whose resolved declaration is a non-local
source `KtNamedFunction` already present in the complete compiler-proven symbol
catalogue. Lexical names, lookup trackers, presentation strings and generated
bytecode call instructions are insufficient authority.

### RPK-KOT-USE-002 — Existing callable identity

The resolved declaration PSI path and exact name-identifier offset shall map to
exactly one existing `kotlin-jvm-callable-v1` symbol from the same worker result.
No usage may manufacture, weaken or reinterpret an ID. Missing, duplicate or
cross-snapshot target mappings invalidate the complete usage contribution.

### RPK-KOT-USE-003 — Initial supported calls

The first row covers direct source call-name references to published top-level and
direct class/interface/enum/object/companion member functions, including calls to
qualified extension, suspend, erased-generic and default-argument functions when
the declaration itself satisfies the existing function-symbol requirements.

Local functions, constructors, properties/accessors, invoke conventions,
operators with non-name syntax, callable references (`::name`), imports, Java
calls and external-library declarations are outside this first row. Unsupported
target categories are not promoted as function usages.

### RPK-KOT-USE-004 — Exact usage range

The usage range shall come from the real compiler PSI name reference, validate
against the immutable overlay source, and use exact zero-based UTF-16 API
coordinates. A position query matches only a declaration-name or qualified usage
name range in the exact requested file; whitespace and punctuation do not guess a
target.

### RPK-KOT-USE-005 — Definition from usage

Saved-snapshot `intelligence.query` definition for language `kotlin` shall require
the active Kotlin semantic lease, exact snapshot hash and current index
generation. A supported function usage resolves to the existing declaration
location for its callable ID. Stale authority, absent compiler evidence or an
unsupported position returns a typed refusal and never falls back to lexical
search.

### RPK-KOT-USE-006 — Bounded references

Saved-snapshot references for a supported callable ID shall return deterministic
path/range ordering, optional declaration inclusion, total/returned counts and
explicit truncation under the common reference limit. Because imports, callable
references, Java callers and unsupported Kotlin constructs are absent, this first
row must report partial completeness and must not be consumed as rename authority.

### RPK-KOT-USE-007 — Atomic worker payload

Symbol and usage payloads belong to one compiler invocation and one attested
snapshot. The worker shall cap resolved usages at 2,000 and bind path, selection
text, offsets and the target callable identity obtained through the exact
declaration key. Missing/extra fields, fake usage source,
unsafe paths, malformed ranges, duplicate usage entries, unresolved supported
source targets or overflow invalidate the complete usage result. No partial usage
index is published.

### RPK-KOT-USE-008 — Integration truth

API `0.2`, daemon, CLI, MCP and capability metadata shall distinguish ID-based
definition from position-based usage definition and shall expose the partial
reference boundary. Existing type/function search and ID definition remain
compatible. Kotlin hover, completion, signature help, cross-language navigation
and every mutation remain refused.

### RPK-KOT-USE-009 — Acceptance

Tests shall cover top-level/member/object/companion/extension calls, repeated
references, exact UTF-16 ranges, declaration inclusion, truncation, source-offset
stability, unsupported local/callable/property forms, malformed worker payload,
stale lease/snapshot/generation, immutable sources and no partial publication.
Packaged acceptance and native CI must pass on Linux x86-64, Windows x86-64,
macOS x86-64 and macOS arm64 before the capability is reported qualified.
