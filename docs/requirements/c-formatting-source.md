# C formatting source context

Authority: the bounded operational
[Story](../../features/c-format-source-context.feature),
`REQ-C-FORMAT-SOURCE-001`. [ARC42](../arc42/appendix-requirements.adoc) owns
integrated acceptance; the [0.8.0 plan](../releases/v0.8.0-plan.md) owns full C07.

Formatting selects exactly one acquired source by its normalized absolute path
inside the workspace. Relative and absolute source records identify the same
file. Missing or ambiguous records and paths outside the workspace refuse with
`clang.formatSourceMissing`. There is no fallback that acquires a live file
after the snapshot was created.

The read-only source boundary strictly encodes the acquired content as UTF-8,
bounded to 8 MiB. Before native execution and after output, the selected file
must be a direct regular file with exact acquired bytes. Each parent must be a
direct directory; at most 256 parent components are admitted. Bounded reads check
file key, size, creation/modification time and parent identities before and after
reading. Missing, indirect, unavailable or changed source inputs refuse with
`clang.formatSourceChanged`. The original source is also checked around the
second formatter execution used for idempotence. These checks do not lock the
filesystem or establish guarantees against live replacement races.

Two actual configured clang-format 22.1.8 controls prove relative/absolute source
records produce the independently expected whole-file edit. Four explicit port
witnesses produce plausible idempotent C output if launched; changed bytes,
removed file, a source link and a missing acquired record must refuse before
executing them. They establish source preflight, not native negative qualification
or wrapper admission. Recorded workspace bytes and links remain intact, process
ownership is reaped and no transaction journal is created. Four genuine behavioral
RED failures precede six GREEN cases. An earlier fixture API-name compilation
failure is preserved separately and is not semantic RED.

The native controls use an explicit LLVM project style; this Story does not
qualify style capture or style inheritance. Source faults during execution,
complete tool/runtime identities, approval replay and filesystem-race closure
remain `actionable`. Range and managed Apply/rollback have separately bounded
acceptance cells below; they do not close C07.
Every C01-C17 acceptance remains required. Evidence is in
`.refactorkit/runs/v080-format-source-context-r001/`; it is local development proof,
not independent release acceptance.

The separate `REQ-C-FORMAT-SNAPSHOT-INPUT-001` Story sends the selected snapshot
content to clang-format through stdin for both formatting and idempotence. The
original source path is supplied only as `--assume-filename`; the formatter gets
no live source-file operand and idempotence creates no scratch file. A changed
whole-file preview and second-pass idempotence pass on configured clang-format
22.1.8; source bytes and the transaction boundary remain unchanged. Its receipt,
pre/post glue inventories, RED and GREEN logs are in
`.refactorkit/runs/c-format-snapshot-stdin-r001/`. At that point the formatter
used `-style=file`; the follow-up style Story below supersedes that specific
live-style limitation. Broader runtime and process faults remain `actionable`.
Range preservation and managed Apply/rollback have bounded acceptance below;
broader staged contexts remain open.

## REQ-C-FORMAT-STYLE-SNAPSHOT-001 — Snapshot-bound style selection

The captured-style Story has five examples.
[`c-format-style-snapshot.feature`](../../features/c-format-style-snapshot.feature)
scans direct `.clang-format` and `_clang-format` files into bounded auxiliary
snapshot evidence. The nearest workspace style is selected only when exactly one
matching file is captured and its no-follow bytes still match. Both native
formatter passes receive `-style=file:<captured-path>`; the live config is checked
before and after each pass. The implementation refuses changed, uncaptured,
symlink, conflicting, and `InheritParentConfig` evidence before launching
clang-format. Its examples cover current captured style, changed style, omitted
snapshot evidence, a symlink, and no workspace config with an ambient parent
config. A separate scenario,
`REQ-C-FORMAT-STYLE-SNAPSHOT-001#conflicting-nearest-c-style-files-refuse-before-formatter-launch`,
proves that coexisting `.clang-format` and `_clang-format` files refuse with
`clang.formatStyleUnavailable` before formatter launch, with no edits or WAL.
A second separate scenario,
`REQ-C-FORMAT-STYLE-SNAPSHOT-001#inherited-parent-c-style-refuses-before-formatter-launch`,
proves that `BasedOnStyle: InheritParentConfig` also refuses before launch with
the same typed code and no edits or WAL. If the workspace has no style file,
both passes use explicit `LLVM`, so a parent-directory config
cannot silently affect the result. The scanner bounds style capture to 256 files,
64 KiB per file and 4 MiB total and strictly decodes UTF-8.

Configured clang-format 22.1.8 acceptance proves captured four-space indentation,
typed pre-launch refusals for changed/uncaptured/indirect/conflicting evidence,
the explicit LLVM fallback despite a parent style, workspace-byte preservation
and absence of a transaction journal. The three isolated style-selection
scenarios, ten focused formatter regressions and the full C module suite (301
tests) pass. Receipts, hash-bound analyzer inventories, source manifests and test
reports are retained in `.refactorkit/runs/c-format-style-snapshot-r001/`,
`.refactorkit/runs/c-format-style-conflict-r001/` and
`.refactorkit/runs/c-format-style-inherit-r001/`. Pre/post checks do not lock the
filesystem or eliminate live replacement/ABA races. Successful parent-style
resolution/inheritance and general style parsing remain `actionable`; invalid
ranges and managed Apply/rollback have separately bounded acceptance below. The
captured single-TU diagnostic context does not close full C07 or any C01-C17 row.

## REQ-C-FORMAT-RANGE-001 — Bounded line-range preview

`formatFile` accepts whole-file formatting, a single range expressed by both
`startLine` and `endLine`, or `ranges` encoded as comma-separated
`start:end` pairs. Endpoints are canonical zero-based inclusive line indexes;
range lists are limited to 32 entries and must be ordered, disjoint and within
the captured source. Supplying both forms, a partial or malformed pair, a
reversed range, overlap, or an out-of-snapshot endpoint refuses with
`clang.formatInvalidRange`. Both formatter passes receive the captured source
through stdin, use the captured style, disable include sorting, and receive the
requested range set. For multiple ranges, a bounded replacements-XML probe maps
the requested line boundaries onto the proposed output; the idempotence pass
uses those rebased coordinates so earlier expansions cannot hide later ranges.
A managed preview is returned only after that pass proves idempotence. Formatting
may expand within each selected syntactic region, while unselected regions and
include order remain byte-for-byte unchanged.

The CLI accepts the same list with `c format --ranges "0:0,2:2"`. It delegates
validation to the existing formatter range contract. A missing option value
is a command-line error. Supplying a list and `--start-line` or `--end-line` in
the same request is a typed range refusal. The CLI Story verifies that the
intervening function remains untouched and malformed selections leave no journal.

`REQ-C-FORMAT-RANGE-001#a-bounded-c-formatting-range-preserves-unselected-content`
passes with configured clang-format 22.1.8 as a three-example outline. The first
example requests one long function line; the second requests two disjoint
function lines with `ranges=2:2,4:4`; the third accepts the maximum 32 ordered,
disjoint ranges (`ranges=0:0,2:2,...,62:62`) in a 66-line source. All confirm
formatting stays within selected syntactic regions, preserves intervening or
trailing source and intentionally unsorted includes, records `idempotent=true`,
and leaves workspace bytes and the transaction boundary unchanged. The two-range
example also asserts that the formatting and replacement-mapping calls receive
`-lines=3:3` and `-lines=5:5`, and that the idempotence call rebases the second
range to `-lines=8:8` after the first function expands. The 32-range example
asserts all 32 corresponding intervals reach both formatting and idempotence,
and the replacement-mapping pass runs before the preview is returned. Its
hash-bound Story receipt, inventories and reports are in
`.refactorkit/runs/v080-c07-range-limit-max32-r001/`. The earlier single-range receipt
remains historical evidence at `.refactorkit/runs/c-format-range-preservation-r001/`.
A separate four-example outline,
`REQ-C-FORMAT-RANGE-001#invalid-c-format-ranges-refuse-before-formatter-launch`,
proves that partial, malformed, reversed and out-of-file ranges return
`clang.formatInvalidRange` before any formatter request, with no edits, affected
files or transaction journal. The refusal receipt and reports are in
`.refactorkit/runs/c-format-range-refusal-r001/`. The six-example refusal
outline now also covers a malformed range list and overlapping ranges; both
refuse before formatter launch with unchanged workspace bytes and no journal.
Its receipt and reports are in
`.refactorkit/runs/v080-c07-multirange-refusal-r001/`. A separate two-example
outline, `REQ-C-FORMAT-RANGE-001#invalid-multi-ranges-refuse-when-out-of-order-or-out-of-file`,
rejects `ranges=4:4,2:2` and `ranges=2:2,6:6` with
`clang.formatInvalidRange` before formatter launch; both preserve workspace bytes
and create no journal. Its receipt and analyzer inventories are in
`.refactorkit/runs/v080-c07-multirange-refusal2-r001/`. The analyzer inventory
uses the actual `:modules:refactorkit-cli:test` source set and its configured root
feature resource, excluding archived run copies outside the test classpath.
Range lists are capped at 32 entries. A separate one-case outline supplies 33
ordered, disjoint intervals within a 66-line source and proves the over-limit
list refuses with `clang.formatInvalidRange` before formatter launch, preserving
workspace bytes and creating no journal. Its receipt and analyzer inventories
are in `.refactorkit/runs/v080-c07-range-limit-r001/`.
Staged diagnostics and managed Apply/rollback have one separately qualified
single-TU C17 cell below.

## Managed formatter Apply with captured C diagnostics

[`c-format-managed-apply.feature`](../../features/c-format-managed-apply.feature)
scenario `REQ-C-FORMAT-SOURCE-001#captured-c-compiler-diagnostics-authorize-format-apply-and-exact-rollback`
qualifies the first managed formatter Apply/rollback cell. Preview captures the
root `compile_commands.json`, the nearest captured formatter style, the configured
Clang 22.1.8 compiler/runtime identity, the source snapshot and the exact edit in
an operation authority lease. The command database is parsed as data; it is never
executed. The bounded compiler policy requires every captured C translation unit
to have one literal C11/C17 command rooted at the workspace, without output,
include-path, target or sysroot flags. Only simple literal `-D` definitions are
passed through. This first cell covers translation units without preprocessor
directives. The captured-header cell below has its own admission policy.

For an admitted context, preview runs exact Clang syntax diagnostics over every
captured translation unit against both the original and staged snapshots. New
compiler errors refuse the preview. The retained Apply gate verifies the database,
style and compiler/runtime identities again, repeats baseline and staged
diagnostics under the PatchEngine transaction boundary, and checks the committed
diagnostic image before returning success. The Story applies a real format edit,
verifies that project inputs remain unchanged and that no build output is emitted,
then rolls back and compares the source with its exact original bytes. It passes
one C17 translation unit on Linux x86-64 with the configured Clang/clang-format
22.1.8 bundle. Receipt, effective-glue inventories, source-view manifests and
reports are under `.refactorkit/runs/c-format-managed-apply-r001/`.

This Story does not qualify multiple translation units, include/preprocessor
semantics, other C flags or standards, filesystem replacement races, or all
formatter range shapes. Those remain `actionable`; the bounded command policy is
not a substitute for the complete C03/C05 compilation model or the C07/C16 release
acceptance.

## Managed formatting with captured local headers

The scenario
`REQ-C-FORMAT-SOURCE-001#captured-ordered-local-headers-authorize-format-apply`
compiles the original and staged C11/C17 translation unit with its captured
quoted headers and explicit root compilation command. The include targets,
directive text and order must agree across both images. Included headers are
source-owned and read from the captured overlay. Their exact bytes are checked
again under the managed Apply lock. Clang runs syntax-only with standard
include search disabled for this cell.

The qualified fixtures exercise numeric object macros and an order-sensitive
conditional in unchanged local headers. Apply changes only the selected
translation unit, and rollback restores its bytes. A computed include or a
missing header leaves the plan without Apply authority. A caller change to a
captured header refuses before journal creation. The Linux x86-64 0.8.0
candidate with SHA-256 `2d0d65b6670a2c480e413fa750687887ce9ba3dc004cffc38c2af65af7d36fc5`
passed all five cases. Four additional packaged CLI/LSP checks covered the
include order and style drift.
[ARC42 acceptance](../arc42/appendix-requirements.adoc#c07-owned-header-formatting-2026-11-21).

## Managed Apply refuses stale captured style

[`c-format-managed-style-drift.feature`](../../features/c-format-managed-style-drift.feature)
scenario `REQ-C-FORMAT-SOURCE-001#changed-captured-c-style-refuses-managed-apply-after-preview`
previews a managed format edit against captured C17 diagnostics, changes the
workspace `.clang-format` file, then attempts Apply with the original plan. Apply
refuses with `authorityLease.evidenceDrift` before creating a transaction journal
or changing source bytes; the external style change and captured compilation
database remain intact. This exercises PatchEngine's required-file freshness
check for C formatter authority. Its single-scenario receipt, pre/post glue
inventories, source manifests and test reports are in
`.refactorkit/runs/c-format-style-drift-apply-r001/`.

The scenario covers only drift of the selected direct `.clang-format` input in a
one-TU C17 context. Compilation-database, compiler/runtime, missing-file,
replacement-race and other style-drift shapes remain `actionable`.


### Managed formatting through CLI, daemon and MCP

`REQ-C-FORMAT-SOURCE-001#managed-c-formatting-surfaces-capture-the-nearest-project-style`
covers discovery of formatter inputs on composed workspace snapshots. The C
adapter captures `.clang-format` and `_clang-format` only from the captured
C-family source directories and their workspace ancestors. It reads bounded
UTF-8 data through owned, no-follow handles and includes those inputs in the
snapshot hash. The closest directory controls style selection. Conflicting
names in that directory refuse preview. Changing captured style after preview
refuses Apply before journal creation. The bounded native acceptance applies
and rolls back exactly through the CLI, daemon and MCP with a nested style
overriding the workspace style.
