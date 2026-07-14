# Architecture

See AGENTS.md for the authoritative initial architecture and implementation rules.

## Implemented modules

| Module                    | Status       | Notes                                                     |
|---------------------------|-------------|------------------------------------------------------------|
| `refactorkit-core`        | ✅ MVP+      | Patch/transaction engine, Build Model SPI, bounded language-adapter registry, mixed routing, generalized evidence, hash-bound snapshots, and the first provider-neutral `WorkspaceIndex` |
| `refactorkit-java`        | ✅ MVP+      | Offline Maven effective reactor/source-set model, JDT diagnostics, rename/move/move-source-root, organize-imports, safe-delete, formatting, limited extract/change-signature, recipes, framework risk detection |
| `refactorkit-cli`         | ✅ MVP+      | Java commands, central index/intelligence search with optional JSON, outline/search/local-rename, and jlink runtime packaging |
| `refactorkit-daemon`      | ✅ MVP       | JSON-RPC over stdio                                        |
| `refactorkit-lsp`         | ✅ MVP       | definition, references, rename, codeAction, documentSymbol, diagnostics, semanticTokens |
| `refactorkit-mcp`         | ✅ MVP       | Tools, resources (including templates), prompts            |
| `refactorkit-web-importer`| ✅ MVP       | ExternalJavaClassImporter with provenance + conflict detection |
| `refactorkit-tree-sitter` | ✅ Kernel    | Packaged TypeScript/JavaScript Tree-sitter JNI, bounded external LSP lifecycle, overlays and untrusted edit normalization |
| `refactorkit-typescript`  | ✅ v0.6+    | Explicit bounded toolchain/project models, semantic reads, managed rename, and IDE-grade `diagnostics.v2` compiler authority/ranges |
| `refactorkit-kotlin`      | 🚧 v0.7     | Explicit JDK/compiler discovery, hash-bound Kotlin/JVM projection, bounded external K2 diagnostics, and compiler-proven regular-class search/definition; references and mutations remain refused |
| `refactorkit-testkit`     | ✅ MVP       | GoldenTestLoader/Runner, AgentSimulation scenarios         |

See individual docs/ files for each subsystem.

## Active evolution

Published `v0.6.2` delivers the managed TypeScript/JavaScript semantic foundation
on the `v0.5.0` cross-platform multi-language kernel and hardened Java/transaction
base. Main develops `0.7.0-SNAPSHOT`; `refactorkit-kotlin` now exposes qualified
experimental read-only diagnostics and saved-snapshot regular-class
search/definition while references, callable identity and mutations remain
fail-closed. The first centralized workspace-intelligence slice inventories every
recognized source and merges bounded, attested provider declaration partitions.
Java lexical declarations load at project open, TypeScript/JavaScript
language-server declarations load during persistent semantic startup/restart, and
qualified Kotlin compiler classes load after authorized compiler queries;
workspace/document symbol queries are implemented while completion, hover,
signature help and position-based navigation remain refused until their persistent
JDT, tsserver and K2 providers qualify. The long-range `v1.0.0` roadmap evolves
the language-neutral core into a deep multi-language platform rather than
freezing a Java-only stable API. Java remains the reference and widest catalogue;
TypeScript/JavaScript, Kotlin, Python, Go, Scala, C/C++, Objective-C, Swift,
Groovy, C#, Rust, and Clojure receive compiler/language-server-backed idiomatic
catalogues and deterministic project-style-aware formatters with the same preview,
evidence, diagnostics, managed apply, rollback, and bounded-resource standards.
The completed Maven Java 21 reference slice now feeds the first implemented
internal Build Model SPI and completed P2B Maven/Gradle productionization gate in
`0.5.x`. The Gradle default is the bounded,
non-executable `gradle-declarative-v1` model documented in
[`gradle-build-model.md`](gradle-build-model.md); package/FQCN identity is
kept separate from source-set/module ownership. See `docs/kotlin-adapter.md`,
`docs/kotlin-toolchain.md`, `docs/kotlin-build-model.md`,
`docs/releases/v0.7.0-plan.md`,
`docs/releases/v1.0.0-plan.md`, ADR 0009, ADR 0010, and ADR 0011.
