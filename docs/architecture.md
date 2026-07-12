# Architecture

See AGENTS.md for the authoritative initial architecture and implementation rules.

## Implemented modules

| Module                    | Status       | Notes                                                     |
|---------------------------|-------------|------------------------------------------------------------|
| `refactorkit-core`        | ✅ MVP+      | Patch/transaction engine plus internal Build Model SPI contracts and hash-bound snapshot projection |
| `refactorkit-java`        | ✅ MVP+      | Offline Maven effective reactor/source-set model, JDT diagnostics, rename/move/move-source-root, organize-imports, safe-delete, formatting, limited extract/change-signature, recipes, framework risk detection |
| `refactorkit-cli`         | ✅ MVP+      | All Java commands + outline/search/local-rename + jlink runtime packaging |
| `refactorkit-daemon`      | ✅ MVP       | JSON-RPC over stdio                                        |
| `refactorkit-lsp`         | ✅ MVP       | definition, references, rename, codeAction, documentSymbol, diagnostics, semanticTokens |
| `refactorkit-mcp`         | ✅ MVP       | Tools, resources (including templates), prompts            |
| `refactorkit-web-importer`| ✅ MVP       | ExternalJavaClassImporter with provenance + conflict detection |
| `refactorkit-tree-sitter` | ✅ Level 1   | GenericOutline, GenericStructuralSearch, GenericLocalRenamePlanner, GenericProjectScanner, ExternalLspAdapter stub |
| `refactorkit-testkit`     | ✅ MVP       | GoldenTestLoader/Runner, AgentSimulation scenarios         |

See individual docs/ files for each subsystem.

## Active evolution

`v0.4.0` publishes the hardened Java/transaction foundation. The long-range
`v1.0.0` roadmap evolves the language-neutral core into a deep multi-language
platform rather than freezing a Java-only stable API. Java remains the reference
and widest catalogue; TypeScript/JavaScript, Kotlin, Python, Go, Scala, C/C++,
Groovy, C#, Rust, and Clojure receive compiler/language-server-backed idiomatic
catalogues and deterministic project-style-aware formatters with the same preview,
evidence, diagnostics, managed apply, rollback, and bounded-resource standards.
The completed Maven Java 21 reference slice now feeds the first implemented
internal Build Model SPI contract/projection and the remaining Gradle/provider
productionization gate in `0.5.x`; package/FQCN identity is
kept separate from source-set/module ownership. See `docs/releases/v0.5.0-plan.md`,
`docs/releases/v1.0.0-plan.md`, ADR 0009, and ADR 0010.
