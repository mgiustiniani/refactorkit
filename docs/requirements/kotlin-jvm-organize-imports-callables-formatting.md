# Kotlin/JVM callable-import organization and bounded project style

Status: candidate requirement; promotion requires executable RED/GREEN, packaged CLI/daemon/MCP apply and rollback, four-platform qualification, and independent review.

## REQ-KOTLIN-ORGANIZE-CALLABLE-001 — Counterfactual compiler authority

For one saved, authoritative, non-generated `.kt` source, `organizeImports` may remove an explicit type, function, property, constructor, or aliased import only when an isolated K2 counterfactual proves that deleting that exact directive:

1. still compiles without errors;
2. retains a complete symbol and usage catalogue;
3. preserves the exact multiset of non-import internal target IDs, external JVM type identities, external JVM callable owner/name/descriptors, selected tokens, and source paths; and
4. preserves every declaration name, kind, JVM identity and visibility.

A compiler error means the directive is retained. A compiling counterfactual whose semantic fingerprint changes is a stable `kotlin.organizeImportsBindingSubstitution` refusal, never an unused-import claim. Star imports remain preserved. The bounded planner accepts at most 32 directives and performs no workspace or transaction write during preview.

## REQ-KOTLIN-IMPORT-STYLE-001 — Snapshot-bound import layout

The import formatter preserves every retained directive byte-for-byte and changes only directive order and configured blank-line separators. LF/CRLF is preserved. The default Kotlin official layout is one Unicode-code-point-sorted group.

A project may override grouping with one bounded `.editorconfig` `ij_kotlin_imports_layout` value captured as no-follow `ClasspathEvidence`. Supported tokens are one catch-all `*`, optional alias group `^`, and unique `<package>.**` prefixes. Tokens define group order; groups are separated by one empty line. The nearest captured ancestor configuration wins. Malformed, duplicated, escaping, stale, oversized, symlinked, or unsupported Kotlin layout configuration refuses before planning.

`gradle.properties` with exact `kotlin.code.style=official` is recognized as an explicit official-style declaration. Any captured style file is re-fingerprinted before use and must still match the preview snapshot.

## Preview, validation, and mutation

The final candidate is compiled again with K2 and must preserve the same semantic fingerprint. A no-op refuses. A successful plan contains one `FileEdit.Modify`, exact affected-file identity, compiler evidence, diagnostics before/after, and explicit approval. Apply uses the operation-owned diagnostics gate, `PatchEngine`, WAL and recovery; rollback restores every original byte.

CLI, daemon and MCP expose the same `organizeImports` request and retained plan. Qualification covers used/unused external and source top-level callables, overload binding substitution, aliases, type imports, star preservation, configured grouping, preview read-only behavior, managed apply and byte-exact rollback.

## Non-claims

This row does not format declarations, expressions, comments, line wrapping, indentation, generated sources, scripts, compiler-plugin output, Android or Multiplatform sources. Whole-file Kotlin formatting remains outside this bounded import-layout operation.
