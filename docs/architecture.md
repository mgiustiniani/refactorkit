# Architecture

See AGENTS.md for the authoritative initial architecture and implementation rules.

## Implemented modules

| Module                    | Status       | Notes                                                     |
|---------------------------|-------------|------------------------------------------------------------|
| `refactorkit-core`        | ✅ MVP       | PatchPlan, PatchEngine, TransactionLog, TextEdits, Models  |
| `refactorkit-java`        | ✅ MVP+      | Scanner, rename, move, organize-imports, safe-delete, extract-method MVP, change-signature MVP, recipe engine, framework-aware risk detection |
| `refactorkit-cli`         | ✅ MVP+      | All Java commands + outline/search/local-rename + jlink runtime packaging |
| `refactorkit-daemon`      | ✅ MVP       | JSON-RPC over stdio                                        |
| `refactorkit-lsp`         | ✅ MVP       | definition, references, rename, codeAction, documentSymbol, diagnostics, semanticTokens |
| `refactorkit-mcp`         | ✅ MVP       | Tools, resources (including templates), prompts            |
| `refactorkit-web-importer`| ✅ MVP       | ExternalJavaClassImporter with provenance + conflict detection |
| `refactorkit-tree-sitter` | ✅ Level 1   | GenericOutline, GenericStructuralSearch, GenericLocalRenamePlanner, GenericProjectScanner, ExternalLspAdapter stub |
| `refactorkit-testkit`     | ✅ MVP       | GoldenTestLoader/Runner, AgentSimulation scenarios         |

See individual docs/ files for each subsystem.
