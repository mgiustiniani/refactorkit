# Kotlin JVM type-usage navigation requirements

Status: accepted by packaged/native GitHub Actions run `29439584115` on Linux
x86-64, Windows x86-64, macOS x86-64 and macOS arm64.

## Scope

Add compiler-proven definition-from-usage and bounded references for the existing
`kotlin-jvm-type-v1` catalogue. This slice reuses qualified type identities and
grants no mutation authority.

## Requirements

### RPK-KOT-TYPE-USE-001 — K2 type evidence

A type usage is publishable only when successful K2 FIR resolution produces a
real-PSI resolved type/qualifier/constructor target with a non-local source
`KtClassOrObject` already present in the complete compiler-proven type catalogue.
Lexical names, imports alone, presentation strings and path/package heuristics are
insufficient authority.

### RPK-KOT-TYPE-USE-002 — Existing type identity

The resolved `ClassId` JVM binary name and exact target declaration path/name
offset shall map to exactly one existing `kotlin-jvm-type-v1` symbol from the same
worker result. No usage may manufacture or reinterpret an ID. A disagreement
between `ClassId`, PSI target and emitted type identity invalidates the complete
type-usage contribution.

### RPK-KOT-TYPE-USE-003 — Initial supported usages

The first row shall cover terminal user-type names in property/parameter/return
types, generic arguments, supertypes, casts and type checks; constructor/class
name calls; object and companion qualifiers where K2 provides exact source and
target evidence; and explicit imports that resolve to a published source type.

Star-import and alias import directives, callable/property targets, enum entries,
anonymous or local classes, type aliases, reflection/configuration strings, Java
callers and external-library declarations remain outside the first row unless
separately qualified. A non-aliased use-site type token resolved by K2 remains
eligible even when its visibility originated from a star import; the `*` itself is
never published as a reference. Unsupported categories must not acquire type
authority by name.

### RPK-KOT-TYPE-USE-004 — Exact usage range

The usage range shall select only the terminal real-PSI type/qualifier token,
validate against the immutable overlay source and use exact zero-based UTF-16 API
coordinates. Qualified names must not collapse package or outer-type segments into
a guessed target range. Whitespace, punctuation and generic delimiters do not
resolve a definition.

### RPK-KOT-TYPE-USE-005 — Position definition

Saved-snapshot API `0.2` daemon, CLI and MCP position definition shall resolve a
supported type usage or declaration-name position to the existing type declaration
location. Exact semantic lease, snapshot and index-generation authority remain
mandatory. Unsupported or ambiguous positions return typed refusal without
lexical fallback.

### RPK-KOT-TYPE-USE-006 — Bounded partial references

References shall merge deterministic compiler-proven type usage ranges with the
optional declaration, report total/returned/truncation and remain explicitly
partial while star/alias imports, Java callers, strings and unsupported constructs
are absent. The result must not be used as rename authority.

### RPK-KOT-TYPE-USE-007 — Atomic bounded payload

Type and function usage evidence shall belong to one compiler invocation and
attested snapshot. The existing aggregate 2,000-usage cap applies. Payload fields,
paths, target identities, selection text and offsets are strict; duplicates,
range/path errors, unresolved supported source targets, identity disagreement or
overflow invalidate the complete usage result. No partial provider contribution
is published after protocol failure.

### RPK-KOT-TYPE-USE-008 — Compatibility and refusal truth

Function usage navigation, ID-based definition and declaration search remain
compatible. Type usages reuse the same typed `intelligence.query`, CLI and MCP
surfaces. Complete references, hover, completion, signature help, Java/Kotlin
cross-language identity and every Kotlin mutation remain refused.

### RPK-KOT-TYPE-USE-009 — Acceptance

Tests shall cover top-level/nested/object/companion/interface/enum/annotation type
usages, constructors, generic/nullable/qualified types, supertypes, exact UTF-16
ranges, declaration inclusion, deterministic truncation, source-offset stability,
unsupported aliases/star imports/local/anonymous shapes, malformed payload, stale
authority, immutable sources and no partial publication. Packaged acceptance and
native CI must pass on Linux x86-64, Windows x86-64, macOS x86-64 and macOS arm64
before qualification is reported complete.
