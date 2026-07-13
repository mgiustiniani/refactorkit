# Multi-language foundation

See AGENTS.md §23 for the original design and
[`releases/v1.0.0-plan.md`](releases/v1.0.0-plan.md) plus ADR 0009 for the active
supreme multi-language roadmap. Java is the reference/widest adapter, but mature
language ecosystems target equivalent IDE-grade semantic safety and idiomatic
refactoring depth rather than permanent baseline-only support.

## Multi-language kernel

`refactorkit-core` now owns `LanguageAdapterRegistry`,
`LanguageAdapterDescriptor`, and `LanguageCapability`. Registration is bounded to
64 adapters and refuses duplicate language IDs or extension ownership. Scanned
`SourceFile.languageId` is authoritative; extension routing is used only when no
scanned file exists. Refactoring routing requires an explicit language, selected
file, or uniquely owned symbol and refuses cross-language symbol ambiguity.

The registry deterministically aggregates symbols and diagnostics across mixed
snapshots. Capability metadata independently declares stability, semantic
evidence, and mutation authority. `MANAGED_STABLE` requires `STABLE` plus
non-lexical evidence. Returned plans are checked against the declared evidence;
a weaker plan is replaced with `language.evidenceInsufficient` refusal rather
than inheriting authority. `JavaAdapterRegistration` is the reference production
descriptor.

Generalized evidence kinds are compiler, language server, native AST, structural
parse, lexical, and none. Existing `PatchPlan` evidence now includes explicit
language-server and native-AST values while retaining JDT/structural/lexical
compatibility.

## Level 1: Structural parsing

Implemented in `modules/refactorkit-tree-sitter`.

The self-contained runtime packages Tree-sitter JNI 0.25.3 plus TypeScript and
JavaScript grammars for Linux x86_64, Windows x86_64, macOS x86_64, and macOS
arm64. `BonedeTreeSitterBinding` uses real bounded parse trees for declaration
outlines and identifier-node search, structurally excluding comments and string
literals. Limits are 16 MiB source, 100,000 visited named nodes, a two-second
native parser deadline, and 1,024-byte identifiers.

The bridge is loaded reflectively only on the bundled Java 21 runtime, preserving
Java-8-compatible RefactorKit library bytecode. `.ts` and `.js` receive native
structural evidence. TSX/JSX are owned by the semantic layer but do not falsely
inherit native structural evidence until dedicated grammar acceptance exists.
Other languages continue to use the documented heuristic fallback.

| Language   | Extension(s)  | Current structural backend |
|------------|---------------|----------------------------|
| TypeScript | `.ts`         | packaged Tree-sitter grammar |
| JavaScript | `.js`         | packaged Tree-sitter grammar |
| Java       | `.java`       | Java adapter/JDT or generic fallback |
| Kotlin     | `.kt`, `.kts` | generic fallback |
| TSX/JSX    | `.tsx`, `.jsx`| semantic layer only; structural acceptance pending |
| Python     | `.py`         | generic fallback |
| Rust       | `.rs`         | generic fallback |
| Go         | `.go`         | generic fallback |
| C#         | `.cs`         | generic fallback |

`TreeSitterAdapter` selects the native binding only when its grammar is loaded;
`GenericOutline`, `GenericStructuralSearch`, and `CommentLiteralFilter` remain
explicit fallbacks. `GenericLocalRenamePlanner` remains review-only structural
mutation and never receives stable semantic authority. Native packaged smoke
requires TypeScript outline extraction and proves that a class-like comment is
not reported.

## Level 2: LSP-backed (experimental)

The `ExternalLspAdapter` is the bounded integration point.
`ExternalSemanticProcessManager` now provides the core bounded lifecycle:
executable/argument provenance hashes, cleared explicit environment, process
capacity, bounded stdout/stderr, natural-exit cleanup, cancellation, and
process-tree termination. See
[`external-semantic-processes.md`](external-semantic-processes.md).

`ExternalLspAdapter` now uses that lifecycle with byte-safe bounded framing,
request deadlines/cancellation, initialization/version/capability evidence, and
bounded source-only workspace overlays. Both LSP WorkspaceEdit forms are strictly
parsed and normalized through `ExternalWorkspaceEditNormalizer`, including path,
range, overlap, version, generated-source, symlink, content-limit, and structural
conflict refusal. The overlay and lifecycle primitives are explicitly not an OS
sandbox. The TypeScript adapter now supplies full-document synchronization, exact-version
diagnostics, bounded restart and layered capability schemas across all integration
surfaces. Packaged real-toolchain acceptance remains open. External workspace edits are untrusted proposals normalized into
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
