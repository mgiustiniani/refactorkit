# C language adapter — toolchain, configuration, usage, refusals and recovery

Authority boundary: this is a **bounded satellite** of the integrated
architecture narrative in
[`docs/arc42/appendix-requirements.adoc`](arc42/appendix-requirements.adoc) and
[`docs/arc42/04-solution-strategy.adoc`](arc42/04-solution-strategy.adoc). It
records the *current* C capability surface on the single qualified platform only.
It does **not** change the source/build version (`0.7.0`), the API (`0.2`), any
tag/asset, or the sealed `0.7.0` receipts.

## Scope and platform qualification

- **Release target:** `0.8.0` dedicated to C, before Python (product decision
  `V080-C-AND-V100-MULTIHOST-001`, ADR 0009 amendment).
- **Qualified platform:** **Linux x86-64** only. **Other hosts remain NOT
  VERIFIED** and are never relabelled PASS; multi-host qualification is a
  **1.0.0** concern.
- **Language:** ISO **C11/C17** only. **C++ and Objective-C are not admitted** and
  confer no capability from the shared Clang foundations.
- **Reference toolchain:** Clang / clangd / clang-format **22.1.8**. These are
  *reference targets*, not blanket support claims. A version-only probe is
  planning input, never semantic qualification
  (`VERSION_ONLY_NOT_SEMANTIC_QUALIFICATION`).
- **Status:** bounded implementation slices on `refactorkit-c`. They are **not**
  full operation/surface semantic qualification, and `0.8.0` is **not published**.

## Toolchain and configuration

The Clang toolchain is **explicit and external**; nothing is auto-installed or
discovered from `PATH` by default.

- Discovery is configured with `allowPathToolDiscovery = false`. Callers pass
  exact executable paths.
- Each executable must be an **existing non-symlink file** (directories must be
  non-symlink too); a symlink or missing path is refused with
  `clang.toolchainPathInvalid`. (`/usr/bin/clang` shipped as a symlink to
  `/usr/bin/clang-22` must therefore be referenced by its real path.)
- No implicit download, global install, compiler plugin/wrapper execution, or
  arbitrary build execution. Compile commands are **untrusted data**: parsed for
  flags, never executed.
- Unsafe compiler flags are denied through `CCompilationDatabasePolicy`
  (`UNSAFE_COMPILER_FLAGS` denylist → `c.unsafeCompilerFlag`).

## Usage (surfaces)

C is a **route capability**, not a kernel `LanguageAdapterRegistry` adapter, and
is **not** advertised through LSP `initialize`/kernel adapters. Mutations are
managed RefactorKit commands.

| Surface | Entry point | Notes |
|---|---|---|
| CLI | `refactorkit c <op> ...` | Requires `--clang`, `--clangd`, `--clang-format` (missing → exit `2`). Delegates to daemon `c.preview` + `refactor.apply`. Exit `0` on `PREVIEW`/`applied`, `1` on refusal/internal error, `2` on invalid params. |
| Daemon | `c.preview` (JSON-RPC) | Delegates the diagnostics gate to `CRefactoringFacade.diagnosticsGate()`. |
| MCP | `preview_refactoring` with `languageId=c`; `available_refactorings` advertises `c` | Deterministic preview tools; apply/rollback reuse the shared pending-plan path and retain the C gate. |
| LSP | C documents (`c`/`h`) are recognized | **Managed-only ownership** (`refactorkit-managed-clang-semantic-via-cli-daemon-mcp-lsp`): editor-applied C rename, code actions, formatting and diagnostics are **refused**, not silently empty. |

CLI operations (each maps to a daemon operation):

```
rename            -> renameSymbol
rename-prefix     -> renamePrefix
move              -> moveSource
format            -> formatFile
organize-includes -> organizeIncludes
safe-delete       -> safeDelete
change-signature  -> changeSignature
extract           -> extractExpression
inline            -> inlineFunction
relocate          -> relocateComponent
```

Per-operation CLI flags include `--symbol`, `--to`, `--file`, `--target`,
`--component-dir`, `--new-dir`, `--temp-name`, `--old-param`, `--new-param`,
`--start-line`, `--start-char`, `--end-line`, `--end-char`.

## Safety gate and recovery

Every managed C mutation keeps the shared contract:

- Preview → explicit approval → **diagnostics gate** → WAL/apply/rollback.
- The apply diagnostics gate is `DiagnosticsGate.enabled("clang-exact-v1")`,
  delegated by the daemon and MCP so the same clang analysis is authoritative.
- Missing toolchain → refusal/error envelope, **never** a silent capability.
- Rollback conflicts (post-image mismatch after an external edit) are refused,
  not nominal restores. Pre-WAL refusals write no journal record.

## Refusals (typed diagnostic codes)

Selection remains token/name-based with an ambiguity guard; it is **not**
clangd-`definition` binding-matched proof of parameter identity, complete
consumers, families (tag/typedef/enumerator/field/param), collisions/shadowing/
linkage, or macro origins. Representative codes:

- Symbol/selection: `c.renameSymbolAmbiguous`, `c.renameSymbolNotFound`,
  `clang.renameInvalidName`, `clang.renameNotFound`, `clangd.definitionNotFound`,
  `clangd.resultInvalid`, `clangd.refused`.
- Compilation model: `c.compilationDatabaseMissing`,
  `c.compilationDatabaseInvalid`, `c.compilationConfigurationConflict`,
  `c.compilationConfigurationIncomplete`, `c.duplicateTranslationUnit`,
  `c.unsafeCompilerFlag`.
- Toolchain/process: `clang.toolchainPathInvalid`, `clangd.notRunning`,
  `clangd.unavailable`, `clang.compilerDiagnosticsTimeout`,
  `clang.compilerDiagnosticsMalformed`, `clang.compilerDiagnosticsUnavailable`.
- Formatting: `clang.formatNonIdempotent`, `clang.formatInvalidRange`,
  `clang.formatUnavailable`.
- Argument/plan boundary: `c.invalidRange`, `c.commandShellOperator`, plus
  typed `REFUSED` for missing/unknown arguments.

## Not qualified / not run (explicit)

- Full C10–C17 operation/surface acceptance is **pending**; the bounded slices
  above are implementation, not release rows.
- Semantic qualification on CI Linux **without clangd** relies on the
  `if (!Files.isExecutable(...)) return` guard in integration tests; that reads as
  passed rather than a formal skip and is **not** a PASS.
- Multifile header consumers, families, collisions/shadowing/linkage, macro
  origins, CMake declarative binding, and single-model/transaction composition are
  declared gaps, not proven.
- Coverage is **UNMEASURED**; static analysis is non-blocking and not clean.
- No other host is qualified; no universal/ABI/whole-program guarantee is made.
