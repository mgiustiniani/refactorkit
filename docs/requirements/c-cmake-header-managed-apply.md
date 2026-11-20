# CMake shared-header managed move

Status: **partial, locally implemented and validated on Linux x86-64 with the
configured Clang 22.1.8 toolchain**. This satellite records one C08 behavior;
[ARC42 requirements](../arc42/appendix-requirements.adoc) remains the integrated
architecture and status authority. The [0.8.0 C plan](../releases/v0.8.0-plan.md)
owns the catalogue and release gates.

## Requirement

`REQ-C-CMAKE-HEADER-MANAGED-APPLY-001` moves one captured workspace `.h` header
when a root literal CMake target binds it through `target_sources(PRIVATE)` and
all captured `.c` translation units have one exact supported compilation-database
entry. The header move, literal consumer-include edits, and root manifest edit
form one `WorkspaceEdit`. The managed gate evaluates every captured TU against
the baseline and staged snapshot before approval, then reuses the same captured
database, target ownership, compiler identity and staged-model lease at Apply.
The qualified target is one literal executable or explicit STATIC library, and
the project remains within the existing eight-TU limit.

The acceptance Story is
[`REQ-C-CMAKE-HEADER-MANAGED-APPLY-001#move-a-shared-header-with-every-captured-tu-owner`](../../features/c-cmake-shared-header-managed-apply.feature).
It proves two literal quoted consumers, the updated `target_sources` path,
compiler-clean baseline/staged contexts, explicit approval, managed Apply and
byte-exact rollback of the header, consumer files and CMake manifest.

## Refusal boundary

Generated/external headers, collisions, macro-computed/path-sensitive inputs,
unsupported or ambiguous CMake bindings, include-root compiler flags, nested or
alternate build contexts, unsupported compiler arguments, missing/duplicate TU
entries, more than eight TUs, non-C snapshots and changed captured inputs do not
gain authority from this Story. Directory relocation, arbitrary header consumer
closure, `target_include_directories`/`-I` agreement, linking, ABI equivalence,
external consumers and full C08 acceptance remain open. No CMake or compilation
database command is executed; the compile database is captured as data and the
configured Clang performs bounded syntax diagnostics only.

## Approved change 001 — 10-file BDD override

The user explicitly approved a **10 changed-workspace-file maximum** for this
slice. The reason is that extending mutation authority needs one production gate
and one literal-binding correction, a single scenario plus shared fixture/runner
registration, preserving the prior leaf-source refusal/missing-lease diagnostic,
and the requirement, integrated architecture, adapter and release status records
to stay aligned. Ten workspace files are changed; local receipt and test-evidence
files under `.refactorkit/runs/` are excluded from this source change budget. The
active-scenario, expanded-case and step-definition budgets remain unchanged.
