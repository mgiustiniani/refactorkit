# Multi-language foundation

See AGENTS.md §23 for the original design and
[`releases/v1.0.0-plan.md`](releases/v1.0.0-plan.md) plus ADR 0009 for the active
supreme multi-language roadmap. Java is the reference/widest adapter, but mature
language ecosystems target equivalent IDE-grade semantic safety and idiomatic
refactoring depth rather than permanent baseline-only support.

## Level 1: Structural (regex-backed, no external binaries)

Implemented in `modules/refactorkit-tree-sitter`.

### Supported languages (outline + search)

| Language   | Extension(s)  | Outline items                                     |
|------------|---------------|---------------------------------------------------|
| Java       | `.java`       | class, interface, enum, record                    |
| Kotlin     | `.kt`, `.kts` | class, interface, object, fun, enum class         |
| TypeScript | `.ts`, `.tsx` | class, interface, function, const/let arrow, enum |
| JavaScript | `.js`, `.jsx` | (same as TypeScript)                              |
| Python     | `.py`         | class, function (top-level + indented method)     |
| Rust       | `.rs`         | struct, enum, trait, fn, impl                     |
| Go         | `.go`         | struct, interface, type, func                     |
| C#         | `.cs`         | class, interface, enum, method                    |

### Classes

**`GenericOutline`** — regex outline extraction, sorted by line number.

**`GenericStructuralSearch`** — plain text and wildcard pattern search within a file.
- `search(content, pattern, languageId, wholeWord, caseSensitive)` — return all matches
- `findIdentifier(content, identifier)` — whole-word identifier search
- `localRename(content, from, to)` — textual whole-word replacement (no comment/string filtering)

**`GenericLocalRenamePlanner`** — PatchPlan-producing local rename for any language.
- Validates identifier syntax
- Skips occurrences inside line comments (`//`, `#`), block comments (`/* */`), string/char literals, and triple-quoted strings
- Returns REFUSED plan for missing file, invalid identifiers, or same-name rename
- Returns PREVIEW plan with `riskLevel = MEDIUM` and warnings that rename is file-scoped and textual

**`GenericProjectScanner`** — lightweight multi-language workspace scanner.
- Maps file extensions to language IDs
- Ignores `.git`, `build`, `target`, `node_modules`, `__pycache__`, etc.
- Detects standard source roots (`src/main/java`, `src`, `lib`)
- Produces a `ProjectSnapshot` compatible with the core patch engine

**`TreeSitterAdapter`** — façade.
- `isAvailable()` → always `false` (stub; real JNI bindings not bundled)
- `outline(content, languageId)` → delegates to `GenericOutline`
- `search(content, pattern, languageId, wholeWord)` → delegates to `GenericStructuralSearch`
- `applyRefactoring(request)` → handles `localRename`; refuses all others

**`ExternalLspAdapter`** — Level 2 LSP-backed adapter (MVP).
- Launches an external language server via stdio
- Sends `initialize` + `initialized` on start
- `buildSymbols` uses `GenericOutline` for structural symbols
- `resolveSymbol` forwards `textDocument/definition` (response parsing is future work)
- `applyRefactoring` always returns REFUSED (full LSP refactoring is future work)

## Level 2: LSP-backed (future)

The `ExternalLspAdapter` is the prototype integration point. Production support
requires a bounded semantic-process manager for lifecycle, capability negotiation,
overlays, versions, timeout, cancellation, restart, output limits, provenance,
and shutdown. External workspace edits are untrusted proposals normalized into
`PatchPlan`; they never bypass `PatchEngine`.

LSP establishes navigation, native formatting, and common-edit foundations, but
the final deep adapters also use compiler/native rewrite and formatting facilities: TypeScript compiler API,
Kotlin Analysis API, SemanticDB/Scalameta/TASTy, Roslyn, Clang tooling, Go APIs,
Pyright plus concrete-syntax rewriting, Rust tooling, Groovy AST, and
clojure-lsp/rewrite-clj. Every deep adapter must provide deterministic,
project-style-aware formatting with idempotence, previewable managed apply/rollback,
and explicit client-managed LSP ownership; see `docs/formatting.md`.

C and C++ share Clang infrastructure but keep distinct capability matrices. C++
has namespaces; C does not. Package-equivalent behavior is modeled through
logical component relocation across source/header paths, includes, build targets,
optional C API prefixes, optional C++ namespaces/modules, and ABI evidence. Path
layout is never silently treated as a namespace.

## CLI commands added

```text
refactorkit outline        <file>  [--language <lang>]
refactorkit search         <file>  --pattern <pattern>  [--language <lang>]  [--whole-word]  [--case-insensitive]
refactorkit local-rename   <file>  --from <name> --to <name>                 [--apply]  [--root <path>]
```

## Comment and literal filtering (`CommentLiteralFilter`)

A shared single-pass scanner builds an "ignored offset" set for the whole file
before filtering matches. This is faster than re-running the scanner per match.

Coverage:

| Construct                     | Languages                              |
|-------------------------------|----------------------------------------|
| `//` line comments            | Java, Kotlin, TS, JS, Rust, Go, C#    |
| `/* */` block comments        | all of the above                       |
| `#` line comments             | Python, Ruby, Shell, YAML, TOML, Perl  |
| `"..."`  `'...'`  `` `...` `` | all languages                          |
| `"""..."""` / `'''...'''`    | Java text blocks, Kotlin, Python       |
| C# `@"..."` verbatim strings  | C# (`""` is the escape, not `\"`)     |

When a `TreeSitterNativeBinding` is registered and supports the language,
`CommentLiteralFilter` is bypassed entirely — the native parse tree is used instead.

## `TreeSitterNativeBinding` extension point

To plug in real Tree-sitter bindings:

1. Implement `TreeSitterNativeBinding` (methods: `supports`, `outline`, `findIdentifier`).
2. Call `TreeSitterAdapter().setNativeBinding(myBinding)` before first use.
3. Or set `-Drefactorkit.treesitter.native=true` to advertise availability without a binding.

`TreeSitterAdapter.findIdentifier` delegates to the binding when `supports(languageId)` is
true; otherwise falls back to `GenericStructuralSearch` + `CommentLiteralFilter`.

## External LSP adapter (`ExternalLspAdapter`)

- Sends `initialize` + `initialized` on `start(rootUri)`.
- `sendRequest` generates a monotone ID; `readMatchingFrame(id)` skips up to 50
  frames (notifications, foreign responses) before timing out.
- `resolveSymbol` parses `textDocument/definition` response via `LspJson`;
  falls back to a best-effort `Symbol` from the cached outline index.
- `findReferences` calls `textDocument/references` using the symbol location
  from the last `buildSymbols` call; returns `emptyList()` when not running.
- `textDocument/publishDiagnostics` notifications are buffered and returned
  by the next `diagnostics()` call.
- `LspJson` parses `Location`, `Location[]`, and `LocationLink[]` without
  an external JSON library.

## Safety notes

- Local rename is file-scoped and textual; it is NOT a semantic project-wide refactoring.
- Always use `refactorkit rename --symbol` for Java project-wide semantic rename.
- For other languages, local rename is the only safe structural option at Level 1.
- All `local-rename` plans require user approval (`requiresUserApproval = true`).
- Local rename plans are rollbackable via `refactorkit patch rollback <id>`.
- `CommentLiteralFilter` is heuristic for single-quoted chars in Java (e.g. `'x'`);
  a native binding eliminates all heuristic risk.
