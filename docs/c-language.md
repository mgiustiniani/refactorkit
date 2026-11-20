# C language adapter — toolchain, configuration, usage, refusals and recovery

Authority boundary: this is a **bounded satellite** of the integrated
architecture narrative in
[`docs/arc42/appendix-requirements.adoc`](arc42/appendix-requirements.adoc) and
[`docs/arc42/04-solution-strategy.adoc`](arc42/04-solution-strategy.adoc). It
records the *current* C capability surface targeting one platform for qualification.
It does **not** change the source/build version (`0.7.0`), the API (`0.2`), any
tag/asset, or the sealed `0.7.0` receipts.

## Scope and platform qualification

- **Release target:** `0.8.0` dedicated to C, before Python (product decision
  `V080-C-AND-V100-MULTIHOST-001`, ADR 0009 amendment).
- **Target qualification platform:** **Linux x86-64** only. **Other hosts remain NOT
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

## Source qualification fixture inputs

Standalone CLI and LSP source `test` tasks now prepare their owned runtime package
before packaged C fixtures execute, and retain wire evidence under each module's
`build/reports/runtime-wire/test`. An explicit
`REFACTORKIT_C_QUERY_CANDIDATE_ROOT` selects an external candidate and avoids the
added rebuild. The existing JVM properties `refactorkit.ts.packaged.evidence`
(CLI) and `refactorkit.c.lsp.packaged.evidence` (LSP) retain explicit destinations.
CLI test fixtures use the executing Gradle distribution by default;
`REFACTORKIT_GRADLE_EXECUTABLE` still overrides it.

Two actual task-model/graph cases and twelve actual packaged CLI/daemon/MCP
positive/refusal cases pass using these defaults. Other runtime/refusal cells remain separately
qualified; this does not make the complete source regression or release green.
Twelve actual packaged LSP positive/refusal cases also pass on the owned defaults,
with real framed queries for function/typedef/field locations and typed boundaries.
[ARC42 acceptance](arc42/appendix-requirements.adoc#source-test-native-fixture-profiles-2026-11-15).

## Captured style versus a live symbolic link

The format-style regression now captures a regular project style and introduces
a symbolic link before preview. The formatter must refuse
`clang.formatStyleUnavailable`, without launching formatting or modifying the
link target. Five native candidate cases/20 steps pass, including the existing
style-change and omitted-style boundaries. Structural scanning still refuses
unowned symbolic links; no scanner rule is relaxed to admit this fixture.
[ARC42 acceptance](arc42/appendix-requirements.adoc#native-regression-fixture-repairs-2026-11-15).
Evidence inside the candidate: `.refactorkit/runs/format-style-native-r001/`.
This fixture repair does not qualify the full formatting or release catalogue.

## Scalar parameter signature families

Compiler-linked function declarations can now retain `int`, `long` and `double`
return/parameter types, including the qualified mixed scalar families. All
declarations must agree on the complete function type. Six native C11/C17 AST
Spec cases verify exact declaration/call ranges and refuse incompatible linked
types. Unqualified non-integer argument effects remain unavailable; admitting a
type does not authorize argument reordering.

Four native C11/C17 `long`/`double` cases verify actual CLI rename previews and
managed library Apply/exact Rollback. Exact source images preserve positional
calls and unrelated parameters. Twelve compiled before/applied/rolled-back
programs retain their expected results; diagnostics are clean, explicit approval
is recorded and each journal terminates `ROLLED_BACK`. The complete main C
module passes 736 tests with zero failures/errors/skips. Broader types, families,
configuration and transport matrices still require their own qualification.
[ARC42 acceptance](arc42/appendix-requirements.adoc#c10-scalar-parameter-families-2026-11-15).
Evidence: `.refactorkit/runs/c10-scalar-family-facts-native-r001/`,
`c10-scalar-family-full-c-native-r001/` and `c10-scalar-parameter-rename-native-r001/`.

## Old-style function signature refusal

In the corrected source candidate, parameter-signature transformations refuse
old-style K&R definitions when their parameter clause cannot be proved. The CLI
returns `c.functionFacts.astUnavailable` with `UNSUPPORTED_SIGNATURE`, without a
preview plan or transaction. C11 and C17 are covered. Existing owned static
integer prototype families retain their bounded positive profile.

The actual-source candidate passes all 730 C module tests, the two new CLI
refusal cases and six existing CLI lifecycle/argument controls. This does not
widen signature support or close the full C10 target. The rebuilt local candidate
package also passes C11/C17 old-style refusals and valid-prototype previews using
its observed embedded Java, without input changes, WAL or compiler emission.
The reviewed fix is now integrated into main; previously delivered artifacts
do not inherit it. This is not independent artifact acceptance.
[ARC42 acceptance](arc42/appendix-requirements.adoc#c10-old-style-signature-refusal-2026-11-15).
Candidate and evidence: `.refactorkit/runs/v080-source-candidate-r001/worktree/`
and its `.refactorkit/runs/c10-old-style-cli-native-r001/`.

## Native SDK queries on source MCP stdio

The readonly SDK query contract now has six actual source-child MCP NDJSON cases
with real Clang22.1.8, in addition to the earlier in-process native sessions.
C11/C17 typedef/field search, definition, references and includeDeclaration retain
SDK absolute paths/URIs, readonly ownership, SDK identity and caller metadata with
exact native source ranges across two captured workspace TUs. Stale SDK IDs and
missing SDK input refuse, preserving typed error code/data or absent data.

Forty correlated requests/responses include 22 compiler-query calls and wire SDK
schemas; all six MCP processes close on EOF. SDK/project/database images remain
exact except deliberate SDK mutation, without WAL/objects/native child leaks.
Production is unchanged. Thirteen prior controls pass 52 steps; C726/MCP32 are
retained by exact relevant input/archive proof, not rerun.
[ARC42 acceptance](arc42/appendix-requirements.adoc#c16-mcp-sdk-queries-2026-11-15).
Evidence: `.refactorkit/runs/c16-mcp-sdk-queries-r001/archive-audit.json`, matrix,
retention and qualified wire/XML/Cucumber archives. This is current-source stdio,
not packaged or independent acceptance. Original full C02-C17 and release gates
remain `actionable`; delivered private runtimes contain older C.

## Typed MCP SDK errors

When an MCP tool catches `JsonRpcException`, its existing `isError: true` and
text content are accompanied by `structuredContent.error` with the original
`code`, `message` and optional `data`. For malformed SDK JSON, clients can read
`code = -32602`, `data.refusalCode = "c.sdkConfigurationInvalid"` and `data.field`.
The existing Apply RPC exception path and non-RPC failure projection are unchanged.
This adds no preview plan or write authority.

Six real source-child NDJSON cases pass 24 steps after six semantic failures:
SDK null/object, nine roots, non-object entry, missing/non-string license, across
C search/definition/references/preview. They verify IDs, actual scan snapshots,
ping, EOF/child closure and exact C/database inventory without compiler execution.
Fresh MCP32 and fourteen controls pass 55 steps; C726 is retained by exact relevant
input/archive proof. [Consumer contract](integration-contracts.md#mcp-typed-sdk-tool-errors).
[ARC42 acceptance](arc42/appendix-requirements.adoc#c16-mcp-sdk-errors-2026-11-15).
Evidence: `.refactorkit/runs/c16-mcp-sdk-errors-r001/archive-audit.json` and wire
archives. Broader SDK/policy/response-budget and other-language error matrices,
packaged execution and full original C02-C17/release remain `actionable`.

## Workspace cursors and readonly SDK LSP locations

Configure `initializationOptions.c.readOnlySdks` with the shared SDK array and
explicit Clang tool paths. Standard `workspace/symbol`, `textDocument/documentSymbol`,
`textDocument/definition` and `textDocument/references` use the existing native
SDK query source projection. Workspace cursors can resolve SDK typedef/field
definitions to real external header URIs and SDK-prototyped functions to their
workspace definitions. References preserve SDK/workspace URIs and
`context.includeDeclaration`; document outline stays in the workspace document.
SDK headers remain readonly evidence and these queries create no patch authority.

Six native C11/C17 cases pass 24 steps; production is unchanged. LspSession23
and seven prior native/selection cases pass 28 steps. C726 is retained with exact
relevant input/archive proof. [Consumer contract](integration-contracts.md#c-workspace-cursor-navigation-to-sdk-locations).
[ARC42 acceptance](arc42/appendix-requirements.adoc#c04-sdk-lsp-queries-2026-11-15).
Evidence: `.refactorkit/runs/c04-sdk-lsp-queries-r001/archive-audit.json` and matrix.
Direct SDK document cursors, other families/configurations/platform layouts and
editor drift/refusal envelopes remain `actionable`; full C04/release stays open.

## Readonly SDK symbol queries

Daemon `symbol.search`, `symbol.definition`, `symbol.references` and equivalent
MCP tools accept top-level `readOnlySdks` alongside explicit tool paths and the
expected workspace snapshot. CLI `c symbols`, `c definition` and `c references`
forward `--read-only-sdks '<json-array>'`. Use the same declared SDK configuration
for search and subsequent navigation. MCP tools/list includes the nested schema.

In an SDK query context, every location includes `uri`, `sourceOwnership`
(`WORKSPACE` or `SDK`), `readOnly` and `sdkInputsIdentity`. Workspace `file` stays
relative; SDK `file` is the actual absolute external header path. SDK locations
also include `sdk.root`, `sdk.identity`, `sdk.provenance` and `sdk.license`.
Provenance/license are caller declarations. Lines remain one-based, characters
zero-based, end positions exclusive. SDK reference locations remain readonly;
workspace uses of the same SDK binding remain workspace locations.

SDK declaration IDs bind the captured SDK identity. After SDK changes, search
again: old SDK IDs return `c.navigation.symbolNotFound`. Missing SDK input is
unavailable, never successful zero references. An unchanged workspace snapshot
alone does not make changed SDK input current. This query projection creates no
SDK files or mutation authority.

Six native cases/24 steps cover daemon/MCP/CLI C11/C17 SDK typedefs, a named SDK
field, changed IDs and missing inputs. Fresh C726/MCP32 and 19 prior query/LSP/
selection cases pass. [Protocol](daemon-protocol.md#c-readonly-sdk-query-locations).
[ARC42 owns acceptance](arc42/appendix-requirements.adoc#c04-sdk-queries-2026-11-15).
Evidence: `.refactorkit/runs/c04-sdk-queries-r001/archive-audit.json` and matrix.
Other SDK families/configurations, direct SDK document cursors and
broader platform SDK layouts remain `actionable`; full C04/release stay open.

## SDK input on managed LSP commands

The argument object for `workspace/executeCommand` command `refactorkit.c.preview`
accepts top-level `readOnlySdks` alongside operation, explicit tool paths and
operation-specific `arguments`. It uses the same bounded SDK array decoder as
daemon/MCP. The server retains the SDK-bound toolchain with the managed plan.
Use `refactorkit.applyPlan` with its `planId`, then `refactorkit.rollback` with
the transaction ID. This is engine-managed work; preview returns plan/diff
metadata rather than client-owned `changes` or `documentChanges`.

When a managed C preview has diagnostic entries, it returns
`diagnosticsBefore` and `diagnosticsAfterPreview` for the corresponding phases.
Entries use LSP diagnostic severity and ranges, with the workspace file URI
when a location is available. A staged diagnostic can name the destination
URI before the file exists on disk. Each phase is bounded by the preview
diagnostic count and byte limits. Its `Truncated` companion field signals an
omitted suffix. Use the compiler-verification or unavailability warnings to
interpret an absent phase array. The diagnostics belong to the retained preview
response. The executable [CMake LSP Story](../features/c-lsp-managed-operation.feature)
covers this projection.

Six C11/C17 native cases pass 24 steps after six semantic failures. SDK-type
workspace-header field Apply and exact rollback preserve SDK/workspace bytes and
unrelated same-name fields/comments/strings. Changed SDK bytes/membership refuse
Apply without WAL even when workspace snapshot hashes are unchanged. Missing
license/non-array SDK input returns INVALID_PARAMS with structured
`c.sdkConfigurationInvalid`. Twenty-three existing session checks and 13 prior
native cases/52 steps pass; C716 is retained by exact relevant input/archive proof.

Shared `initializationOptions.c` discovery also forwards SDK metadata for C read
requests; broader SDK editor-query source/identity qualification remains open.
[Caller contract](integration-contracts.md#c-sdk-inputs-for-managed-lsp-commands).
[ARC42 owns bounded status](arc42/appendix-requirements.adoc#c03-sdk-lsp-2026-11-15).
Evidence: `.refactorkit/runs/c03-sdk-lsp-r001/archive-audit.json` and its matrix/
retention/native/session receipts. Remaining malformed JSON/MCP machine errors,
SDK read/provider/other-family/platform coverage remain `actionable` alongside
full original C02-C17/release. Delivered runtimes retain older C; no publication.

## CLI SDK JSON

C commands accept `--read-only-sdks '<json-array>'` and forward that optional
value to the daemon's SDK decoder. Omission keeps empty SDK scope. CLI validates
JSON syntax; existing backend policy owns SDK shape, root/provenance/license,
closed-tree validation and identity. Malformed syntax, a missing option value
or an over-limit SDK array reports `c.sdkConfigurationInvalid`.

```sh
refactorkit c rename . --symbol raw_count --to renamed \
  --file include/api.h --line 2 --character 14 --apply \
  --clang /qualified/usr/bin/clang-22 --clangd /qualified/usr/bin/clangd \
  --clang-format /qualified/usr/bin/clang-format \
  --read-only-sdks '[{"root":"/opt/example-sdk","provenance":"caller:example-sdk-build","license":"LicenseRef-ProjectSdk"}]'
```

Six C11/C17 native cases pass 24 steps after six semantic failures. Four actually
preview/apply/rollback with one, two or eight roots; two active TUs select first/
last SDK sysroots independently in the multi-root cases. Invalid JSON and nine
roots refuse without WAL. Saved SDK/project images, unrelated same-name fields,
strings/comments and no object emission pass. Nineteen prior controls/76 steps
pass; C716 is retained by exact relevant input/archive proof, not rerun.

Each `--apply` invocation generates a fresh preview then applies that retained
plan; it does not reuse the ID from a previous CLI invocation. Query commands
also forward the SDK option, while broader SDK query qualification remains open.
[ARC42 owns bounded status](arc42/appendix-requirements.adoc#c03-sdk-cli-2026-11-15).
Evidence: `.refactorkit/runs/c03-sdk-cli-r001/archive-audit.json` and its matrix/
retention/qualified receipts. Managed LSP SDK input is qualified above; remaining malformed JSON/MCP
structured errors, SDK read/provider/other-family/platform coverage remain
`actionable` alongside full original C02-C17/release. Private delivered runtimes
contain older C; no runtime install/publication follows.

## Explicit SDK configuration on daemon and MCP

Daemon `c.preview` and MCP `preview_refactoring` accept optional top-level
`readOnlySdks`, an array of up to eight objects containing exactly the string
fields `root`, `provenance` and `license`. Omission preserves empty SDK scope.
Roots and metadata are checked by the existing SDK discovery policy. Provenance
and license are caller declarations, not independent verification. MCP tools/list
publishes the nested bounded schema. Malformed input raises
`c.sdkConfigurationInvalid`; daemon uses INVALID_PARAMS with structured data,
and MCP keeps isError/text while adding typed code/message/data in structuredContent.error.

Six native C11/C17 cases pass 24 steps after six semantic failures. Both surfaces
apply and exactly roll back a workspace-header field using an SDK type. An SDK
change after preview refuses Apply without WAL while the workspace snapshot stays
unchanged. Missing license/non-array input refuses. SDK/workspace unrelated fields,
strings/comments and saved bytes remain exact. Fresh C716, daemon64 and MCP32 and
19 prior native controls/76 steps pass on identical final source.

[Call syntax](daemon-protocol.md#explicit-c-sdk-inputs-for-managed-previews).
[ARC42 owns bounded status](arc42/appendix-requirements.adoc#c03-sdk-public-managed-2026-11-15).
Evidence: `.refactorkit/runs/c03-sdk-public-managed-r001/archive-audit.json`
and its matrix/green/RED/module receipts. CLI SDK input is qualified above;
Managed LSP SDK input is qualified above; remaining malformed
JSON boundaries, read/provider propagation, other managed SDK families and broader
SDK/platform forms remain `actionable`, alongside full original C02-C17/release.
Delivered runtimes contain older C; no runtime update/install/publication follows.

## Managed workspace fields that use SDK types

A captured external SDK can provide types for a managed workspace-header field
rename. In the qualified C11/C17 two-TU cell, preview/apply/rollback changes only
the workspace declaration, designator and member use. An SDK record containing
an unrelated same-name field remains byte-exact. SDK byte/file changes and forged
SDK-bound toolchain leases refuse Apply without creating a transaction journal.

Discovery retains immutable SDK header contents, and current/staged compiler
acquisition supplies them separately from writable sources. Only workspace hashes
and sites authorize edits. SDK selection, overlapping ownership, missing readonly
bytes and readonly write sites refuse. Existing combined source-graph limits
remain 32 sources/8 MiB. Configuration uses the library's explicit SDK requests;
no public transport SDK configuration is implied.

Six native cases pass 24 steps after six missing-managed-authority failures.
Eighteen approved Kotest ownership Specs, fresh C716 without skips/errors and
25 prior native controls/100 steps pass. Saved SDK/project images, no object
emission and process closure pass. A fixture cleanup-order failure is preserved;
production did not change after that fixture repair.
[ARC42 owns bounded status](arc42/appendix-requirements.adoc#c03-sdk-managed-field-2026-11-15).
Evidence: `.refactorkit/runs/c03-sdk-managed-field-r001/archive-audit.json`
and its matrix/native/pure receipts. Other managed SDK families, public/provider
configuration and broader platform SDK forms remain `actionable`, alongside full
original C02-C17/release. Delivered runtimes remain older C; no publication.

## SDK inputs through native compiler evaluation

Source-only function AST acquisition now forwards discovery-retained SDK requests
to its private compilation context. The shared compiler-current check includes
SDK identity and captured-input currency. Syntax diagnostics validate the SDK
binding before overlay/launch and after completion, returning
`clang.compilerSdkUnavailable` for changed or mismatched SDKs; AST acquisition
uses its existing `c.functionFacts.compilerUnavailable` refusal.

Six native cases pass 24 steps after six semantic failures. Explicit C11/C17
positive cases verify genuine sysroot/resource arguments, AST facts and SDK type
use in clean current syntax. A staged missing SDK member produces expected
compiler diagnostics. Changed header bytes/membership/removal or mismatched SDK
identity cannot become available analysis. SDK/project saved inputs, no object/
WAL and process/overlay closure pass. Fresh C710 and 43 prior controls/172 steps
pass on identical final source.

[ARC42 owns bounded status](arc42/appendix-requirements.adoc#c03-sdk-compiler-context-2026-11-15).
Evidence: `.refactorkit/runs/c03-sdk-compiler-context-r001/archive-audit.json`
and its matrix/green/native RED receipts. The first zero-selection attempt is
preserved and not semantic RED. Full managed SDK include-family proof, broader
platform SDK forms and public/provider configuration remain `actionable`, as
does full original C02-C17/release. No delivered runtime update or publication.

## Warm validity of discovery-retained SDKs

Six native C17 sessions pass 24 steps after resolving exact SDK headers and
checking matching retained SDK/toolchain identities. No duplicate client scope
is supplied. Intact inputs remain usable; SDK bytes, header membership/removal,
empty directory and symlink changes revoke warm paths with c.clangdSdkChanged,
or false for document opening. Unavailable references do not become zero and
stale proposals are absent. SDK/project post-mutation inputs, no object/WAL and
process/private-material closure pass. Nineteen prior controls pass 76 steps.
C710 is retained with exact relevant input/archive proof; production is unchanged.

[ARC42 owns bounded status](arc42/appendix-requirements.adoc#c03-sdk-toolchain-currency-2026-11-15).
Evidence: `.refactorkit/runs/c03-sdk-toolchain-currency-r001/archive-audit.json`
and its matrix/retention receipt. Broader SDK/platform forms, provider/public
configuration and staged/managed compiler consumers remain `actionable`, as does
full original C02-C17/release. Private runtimes contain older C code; no publication.

## SDK configuration through toolchain discovery

`ClangToolchainRequest.readOnlySdks` now accepts explicit SDK requests. Discovery
captures their bounded inputs into immutable `CReadOnlySdkInputs`; the resulting
`ClangSemanticToolchain` retains requests/provenance/identity and live input
checks. `ClangToolchainProvenance.readOnlySdkIdentity` binds the SDK bundle into
`toolchainIdentity()`. SDK metadata is caller-declared, not independently verified
licensing. SDKs absent from configuration preserve the exact legacy digest.

A client created from that toolchain consumes its SDK scope automatically.
Forged SDK identity and changed retained SDK inputs refuse before semantic startup
with `c.clangdSdkUnavailable` or `c.clangdSdkChanged`. Explicit rediscovery after
an SDK byte change creates new SDK/toolchain identities. Metadata uses strict UTF8
framing; malformed surrogate strings refuse rather than share replacement-byte
fingerprints. Existing root/header/byte/entry/depth and closed-literal limits remain.

Six native C11/C17 cases pass 24 steps; 28 approved Kotest Specs, fresh C710 without
skips/errors and 77 prior native controls/308 steps pass. Saved SDK/project inputs,
no object/WAL and process closure pass. A fixture-only compile failure is recorded
without a semantic RED claim.
[ARC42 owns bounded status](arc42/appendix-requirements.adoc#c03-sdk-toolchain-2026-11-15).
Evidence: `.refactorkit/runs/c03-sdk-toolchain-r001/archive-audit.json` and its
matrix/qualified/Spec receipts. Retained-discovery warm currency now has the
separate C17 Story above. Broader platform SDK forms, provider/public configuration
and staged/managed compiler consumers
remain `actionable`, as does full C02-C17/release. Existing private runtimes contain
older C code; no installation/publication follows.

## Warm validity of external SDK inputs

Six actual C17 library-client sessions pass 24 steps after resolving their exact
SDK header and checking captured provenance/license/identity. Intact SDK inputs
remain usable. Changed header bytes, a new/removed header, an empty directory or
symlink revoke definition/references/rename/document opening. References become
unavailable rather than empty and stale proposals are absent. Native processes
and private launch material close; post-mutation SDK/project bytes and link/
directory witnesses remain exact, without objects/WAL. Nineteen prior controls
pass 76 steps. C704 is retained with exact relevant input/archive proof; production
code is unchanged.

[ARC42 owns bounded status](arc42/appendix-requirements.adoc#c03-external-sdk-currency-2026-11-15).
Evidence: `.refactorkit/runs/c03-external-sdk-currency-r001/archive-audit.json`
and its matrix/retention receipt. This does not qualify broader SDK source/platform
layouts, public SDK configuration or managed/staged external-input authority.
Those requirements and full C02-C17/release remain `actionable`; delivered private
runtimes have older C code. No installation/publication follows.

## Explicit external read-only SDK scope in the library client

The current library API accepts an explicit SDK authorization separately from
compile-database paths:

```kotlin
val client = ClangdSemanticClient(
    toolchain, processManager,
    readOnlySdks = listOf(CReadOnlySdkRequest(
        root = Path.of("/opt/project-sdk"),
        provenance = "caller-declared local SDK origin",
        license = "caller-declared license identifier",
    )),
)
```

The root must be absolute, normalized, external to the workspace and explicitly
selected by the sysroot argument. A path in compile_commands.json alone grants no SDK read permission. `readOnlySdkProvenance()` exposes the captured root, caller-declared
provenance/license and exact tree identity; it does not assert live freshness or
independent license verification. Every semantic request checks captured inputs.
The initial profile is a bounded header-only usr/include tree with a closed
literal quoted-include graph. Existing eight-root and per-root header/entry/
file/total-byte/depth limits remain. Macro, conditional, system-platform and other
unqualified SDK forms require further evidence.

Six actual C11/C17 cases pass 24 steps. Four exact SDK definitions retain genuine
--sysroot without synthetic includes. Two SDK-symbol rename requests return
`c.sdkReadOnly`; two private source-owned declarations still produce exact
workspace-only rename proposals. Nothing is applied. Invalid explicit scope is
refused even without a database; an escaping SDK header is refused before native
startup. SDK/project bytes and process closure pass, without object/WAL output.
Twenty-two pure Kotest Specs, fresh C704 without skips/errors and 65 prior native
controls/260 steps pass on the final source.

[ARC42 owns bounded status](arc42/appendix-requirements.adoc#c03-external-sdk-2026-11-15).
Evidence: `.refactorkit/runs/c03-external-sdk-r001/archive-audit.json` and its
matrix/native/Spec receipts. This constructor option does not advertise SDK
configuration in CLI/daemon/MCP/LSP or managed Apply. Native SDK warm currency is qualified in the separate C17 Story above.
Broader SDK layouts/platform headers, configuration/identity propagation and
current/staged managed authority remain `actionable`, as does full C02-C17/release.
Existing private runtimes have older C code; no installation/publication follows.

## Warm validity of every selected sysroot

Six actual C17 sessions pass 24 steps after resolving both owned sysroot headers
at exact paths (12 initial definition witnesses). The changed secondary root sorts
after the primary root in the capture list. Its header byte/membership changes,
removal, new empty directory or symlink revoke queries of the unchanged first
file. References become unavailable rather than empty; rename returns no proposal;
document opening returns false. An intact two-root tree remains usable. Native
processes and private launch material close; post-mutation inputs remain exact,
with no object/WAL output. Eleven prior controls pass 44 steps. C698 is retained
with exact relevant input/archive proof; production code is unchanged.

[ARC42 owns bounded status](arc42/appendix-requirements.adoc#c03-per-tu-sysroot-currency-2026-11-15).
Evidence: `.refactorkit/runs/c03-per-tu-sysroot-currency-r001/archive-audit.json`
and its matrix/retention receipt. This C17 warm-session evidence does not qualify
every operation or external SDK context. External read-only SDK/sysroot authority,
broader original C03 and full C02-C17/release remain `actionable`. Private runtimes
contain older C code; no installation/publication follows.

## Independent owned sysroots per translation unit

Current source permits distinct C11/C17 files to use independent workspace-owned
header-only sysroots, or one explicit sysroot beside an implicit TU with its own
include path. Four real native failures become four passing cases/16 steps and
eight exact header definitions at different paths. Raw/model/private arguments
stay exact; the implicit row receives no manufactured sysroot or include flag.
Contradictory records for one source remain refused. Earlier distinct-root refusal
evidence stays unchanged at its original source epoch.

Sixteen approved Kotest Specs, fresh C698 without skips/errors and 59 prior native
controls/236 steps pass on identical final source. Root count is bounded at eight;
all existing per-root limits remain, with at most 64 MiB of header capture overall.
Every footprint participates in live validity checks; the separate C17 Story
above now qualifies native multi-root warm invalidation.
[ARC42 owns bounded status](arc42/appendix-requirements.adoc#c03-per-tu-sysroot-2026-11-15).
Evidence: `.refactorkit/runs/c03-per-tu-sysroot-r001/archive-audit.json` and its
matrix/RED/Spec receipts. External read-only SDK/sysroot authority, broader
original contexts and full C02-C17/release remain `actionable`. Private runtimes
contain older C code; no installation/publication follows.

## Independent target context per translation unit

Current source permits different qualified targets for distinct C11/C17 source
files. One i386 TU can coexist with explicit x86_64 or the selected compiler
default. Four real native failures become four passing cases/16 steps and eight
architecture-conditioned definitions in one shared header. Raw/model arguments
and both private database rows remain exact; implicit target metadata stays
absent. Contradictory records for the same source remain refused. The earlier
negative Story now tests conflicting same-source records rather than a valid
per-TU difference. Its historical evidence remains unchanged.

Fourteen approved Kotest Specs, fresh C694 without skips/errors and 49 prior
native controls/196 steps pass on identical final source. Seven changed files
fit the default budget without new dependencies/runners/step expressions.
[ARC42 owns bounded status](arc42/appendix-requirements.adoc#c03-per-tu-target-2026-11-15).
Evidence: `.refactorkit/runs/c03-per-tu-target-r001/archive-audit.json` and its
matrix/RED/Spec receipts. The qualified triples remain i386-unknown-linux-gnu and
x86_64-unknown-linux-gnu. Broader original per-TU sysroot/external read-only SDK
contexts and full C02-C17/release remain `actionable`. Delivered private runtimes
contain older C code; no installation/publication follows.

## Earlier sysroot startup evidence

At the preceding source epoch, six actual startup cases passed 24 steps for a
repeated selector, external unowned root,
linked header, unknown driver-search layout, Linux -isysroot and distinct sysroot
cohorts refuse with typed reasons before any semantic PID/definition exists.
Raw/model arguments retain declared sysroot values. Saved bytes, inserted links
and directories remain unchanged, with no object or WAL output. Seven prior
controls pass 28 steps; C690 is retained with exact relevant input/archive proof.
Production code is unchanged; no semantic RED is claimed.

[ARC42 owns bounded status](arc42/appendix-requirements.adoc#c03-owned-sysroot-startup-2026-11-15).
Evidence: `.refactorkit/runs/c03-owned-sysroot-startup-r001/archive-audit.json`
and its matrix/retention receipt. These refusals do not qualify external or mixed
sysroot positive semantics. Broader original contexts and full C02-C17/release
remain `actionable`; private runtimes contain older C code.

## Managed operations with an owned sysroot

Six native C11/C17 cases pass 24 steps through CLI, daemon and MCP with a shared
header found only in workspace-owned sdk-root/usr/include. Four managed previews
apply independently expected field edits across the header and two consumers,
then roll back to the original snapshot. Two stale-database cases refuse without
WAL. Current/staged compiler analysis retains --sysroot, without a synthetic
include flag. Unrelated fields, strings and comments are preserved. Thirteen prior
controls pass 52 steps; C690 is retained with exact relevant input/archive proof.
Production code is unchanged.

[ARC42 owns bounded status](arc42/appendix-requirements.adoc#c03-owned-sysroot-managed-2026-11-15).
Evidence: `.refactorkit/runs/c03-owned-sysroot-managed-r001/archive-audit.json`
and its matrix/retention receipt. This fixture is source-owned and grants no
write authority over external SDK headers. Native startup refusals, broader
original contexts, other sysroot operations and full C02-C17/release remain
`actionable`. Delivered private runtimes contain older C code.

## Warm sysroot input validity

Six actual native sessions pass 24 steps after initially resolving their owned
sysroot header. An intact tree remains usable. Changed header bytes, a new header,
a removed header, a new empty directory and an inserted header symlink revoke
warm authority. Definition/rename refuse, references become unavailable and
opening returns false with stale inputs. The session closes and removes private
launch material. Post-mutation inputs are preserved; no object or transaction is
emitted. Thirteen prior controls pass 52 steps. Production is unchanged; C690 is
retained with exact relevant input/archive proof rather than rerun.

[ARC42 owns bounded status](arc42/appendix-requirements.adoc#c03-owned-sysroot-currency-2026-11-15).
Evidence is `.refactorkit/runs/c03-owned-sysroot-currency-r001/archive-audit.json`
and its matrix/retention receipt. Native startup refusals, current/staged/public
sysroot authority, broader original target/sysroot contexts and full C02-C17/
release remain `actionable`. The delivered private runtimes contain older C code.

## Owned sysroot context in current source

Current source admits a bounded workspace-owned header-only sysroot with an
usr/include tree for C11/C17. Assigned/separate --sysroot operands resolve against
the compilation working directory and remain genuine native sysroot flags. Six
real admission failures become six passing native cases/24 steps: a header found
only through that sysroot resolves at its exact location without synthetic -I,
-isystem or -iquote flags. Twelve pure Kotest Specs and fresh C690 pass without
skips/failures/errors; 37 prior native controls pass 148 steps on identical source.

[ARC42 owns bounded status](arc42/appendix-requirements.adoc#c03-owned-sysroot-2026-11-15).
Local evidence is `.refactorkit/runs/c03-owned-sysroot-r001/archive-audit.json` and
its native matrix, RED and Spec receipts. The core owned-source reader captures
header bytes, directories and regular paths within explicit bounds. Selection
refuses duplicate selectors, contradictory same-source roots, unowned paths
and unsupported layouts. Distinct TUs retain independent roots as qualified
above. Linux
-isysroot is not silently repaired into --sysroot.

Native startup-refusal and warm-currency integration, current/staged/public
operation authority and broader original target/sysroot contexts remain
`actionable`. These header-only cells do not qualify external, mixed or full SDK
layouts. Full C03/C02-C17/release remains active; delivered private runtimes
contain older C code. No installation or publication follows.

## Earlier target startup-refusal evidence

At the preceding source epoch, six C11/C17 cases passed 24 target-admission
steps, refusing duplicate selectors, unknown triples, invalid spellings and a mixed explicit
target cohort before a semantic server starts. PID/definition are absent and
manager state is empty. Invalid spellings preserve parser refusal/model
unavailable; the mixed cohort preserves two per-TU targets and a partial aggregate.
Saved bytes and no object/transaction output pass. Seven prior controls pass
28 steps. C678 is retained with exact relevant input/archive proof, not rerun;
production and the approved pure policy are unchanged.

[ARC42 owns bounded status](arc42/appendix-requirements.adoc#c03-native-target-refusals-2026-11-15);
`.refactorkit/runs/c03-native-target-refusals-r001/archive-audit.json` and the
refusal matrix/retention receipt own evidence. Startup rejection is separate
from successful query qualification. Broader original target and sysroot/header/
search/overlay authority, plus full C02-C17/release, remain `actionable`.
Existing private runtimes and delivered IDE candidates retain the older C JAR.

## Explicit target context in current source

Current source admits coherent C11/C17 contexts for i386-unknown-linux-gnu and
x86_64-unknown-linux-gnu with -target <triple> or --target=<triple>. The native
clangd query preserves target metadata and private arguments and resolves a header
conditioned on the expected architecture/pointer width. Six native failures become
six passing cases/24 steps. Ten pure Kotest Specs cover target selection and
ambiguity/coherence boundaries; fresh C678 and 25 prior controls/100 steps pass.
No compiler objects or source changes are emitted by these query tests.

[ARC42 owns bounded status](arc42/appendix-requirements.adoc#c03-native-target-2026-11-15);
`.refactorkit/runs/c03-native-target-r001/archive-audit.json` and the matrix/RED/Spec
receipts own local evidence. Multiple selectors, unqualified targets and
inconsistent metadata refuse. Distinct TUs now retain independent target contexts
as qualified above; same-source ambiguity remains refused. Compiler-invalid
--target <triple> and -target=<triple> do not
become repaired commands. Include/output operands that look like flags stay literal.
This qualifies source analysis on Linux x86-64, not execution on another host.

The startup refusal/cohort boundaries are now qualified above. Further original
target authority, sysroot/header/search/overlay authority and full C02-C17/release
remain `actionable`. These are the first target slices of C03, not its completion. Existing
private224-file runtime and delivered IDE candidates retain the older C JAR and
do not include this source change. No install/adoption/publication follows.

## Packaged LSP boundaries and native-context limit

Six actual C11/C17 boundary cases pass 24 steps through the packaged LSP server:
missing toolchain, dirty document, missing TU, macro declaration and invalid
position are errors; a number without a symbol alone returns null definition and
empty references. Six processes exit 0, 24 embedded-Java checks and seven prior
controls/28 steps pass. The same private runtime is retained with 416 identical
production/build inputs and exact archive/JAR/runtime hashes; C668 is retained
with exact relevant input/archive proof, not rerun.

[ARC42 owns boundary status](arc42/appendix-requirements.adoc#c16-packaged-lsp-refusals-2026-11-15);
`.refactorkit/runs/c16-packaged-lsp-refusals-r001/archive-audit.json` and its matrix/
wire receipts own evidence. The [original C04 review](arc42/appendix-requirements.adoc#c04-original-contract-review-2026-11-15)
records named-family/lookup/linkage/shadowing evidence without inventing an
exhaustive cursor-by-transport matrix. Full C04/C16/release is not qualified.

Current actionable C03 limit: target and owned header-only sysroot now have
the bounded current-source qualification above. Native sysroot refusal/lease and
current/staged/public authority, broader original target/sysroot contexts, full
C02-C17/release, independent review and supply-chain gates remain actionable.

## Packaged standard LSP launcher

The distribution now generates `bin/refactorkit-lsp` and its batch counterpart,
using embedded Java and the packaged LSP server. UNIX staging and ZIP permissions
are executable. Six actual native C11/C17 function/typedef/field cases pass 24
steps through Content-Length stdio; six processes serve 24 queries with 36 embedded
Java identity checks and clean shutdown/exit. Saved source and native cleanup
pass. Fresh C668 has no skips/failures/errors; seven prior controls pass 28 steps.
The private 224-file archive adds only the two launchers to the prior query
candidate; existing runtime/JAR/launcher bytes are identical.

[ARC42 owns this bounded status](arc42/appendix-requirements.adoc#c16-packaged-lsp-query-2026-11-15);
`.refactorkit/runs/c16-packaged-lsp-query-r001/archive-audit.json` and the matrix,
wire and candidate receipts own evidence. See [packaging](packaging.md#run-packaged-lsp-server)
for startup. Batch generation does not qualify Windows. Packaged LSP refusals, original-contract coverage review and original full C04/C02-C17/release remain `actionable`;
no installation, candidate adoption, clean-revision or publication follows.

## Packaged query refusals

Six actual C11/C17 daemon/MCP cases pass 24 steps for missing TU, unknown opaque
ID and context drift. The wire retains daemon -32001/-32003/-32002 codes and MCP
isError content in ten error responses; six processes close cleanly. Daemon's
preliminary snapshotStale and MCP's captured-byte snapshotChanged are separate
documented guards. One copied expectation was corrected without production
changes or a semantic RED claim. Deliberate database drift remains the expected
post-mutation image. Seven prior controls pass 28 steps; C668 is retained with
exact relevant input/archive proof, not rerun. The same private runtime remains
unchanged against 416 production/build inputs and exact archive/JAR/runtime hashes.

[ARC42 owns this bounded status](arc42/appendix-requirements.adoc#c04-query-runtime-refusals-2026-11-15);
`.refactorkit/runs/c04-query-runtime-refusals-r001/archive-audit.json`,
`packaged-refusal-matrix.json` and the wire/correction/reuse receipts own local
evidence. Packaged standard LSP integration and original full C04/C02-C17/release
remain `actionable`; installed and delivered IDE candidates are unchanged.

## Queries through the private packaged runtime

Six actual native C11/C17 cases pass 24 steps through extracted CLI/daemon/MCP
launchers, covering function/typedef/field/parameter families. Twelve launcher
processes use the embedded Java; wire/compiler evidence, expected responses,
saved inputs, no transaction/compiler output and cleanup pass. Seven shadow-ID/
discovery controls pass 28 steps. C668 is retained with exact relevant input/
archive proof, not rerun. The existing wire harness is unchanged.

[ARC42 owns this bounded status](arc42/appendix-requirements.adoc#c04-query-runtime-2026-11-15);
`.refactorkit/runs/c04-query-runtime-r001/archive-audit.json`,
`packaged-query-matrix.json`, `wire-manifest.json` and candidate verification
own local evidence. The private 222-file archive matches current production
JARs and source manifests; it does not replace the IDE candidate or installation.
Source/build version remains 0.7.0/API0.2. Packaged refusal and standard LSP query
integration, independent/supply-chain qualification and original full C04/C02-C17/
release remain `actionable`; no publication or clean-revision claim follows.

## Direct typedef/value/peer IDs

Six actual C11/C17 native cases pass 24 steps on representative CLI/daemon/MCP
routes. Three same-named opaque IDs preserve the API typedef, local ordinary
value and peer TU typedef. Exact definitions and reference sets (two/one/one),
optional declarations, own original TU, ordinary lookup/no linkage, query and
compiler metadata, saved bytes and cleanup pass. Seven prior pointer/chain/
repeated-alias and discovery controls pass 28 steps on identical source.
Production/resources are unchanged; C668 is retained with exact relevant
input/archive proof, not rerun.

[ARC42 owns this bounded status](arc42/appendix-requirements.adoc#c04-ordinary-shadow-public-2026-11-15);
`.refactorkit/runs/c04-ordinary-shadow-public-r001/archive-audit.json` and
`ordinary-shadow-public-matrix.json` own local evidence. Native refusal
envelopes, exact-source framed runtime queries and original full C04/C02-C17/
release remain `actionable`. The isolated IDE candidate is unchanged.

## Typedef and ordinary-value shadow cursors

Six actual C11/C17 native LSP cases pass 24 steps at an alias use before a
shadow and the local value declaration/use. The alias retains its two type
references and the local value its one ordinary reference, with exact own-family
definitions and optional declarations. The peer TU alias remains independent;
workspace symbols retain three physical declarations and the API outline two.
Saved bytes and native cleanup pass. Seven prior scalar-typedef/discovery
controls pass 28 steps on identical source. Production/resources are unchanged;
C668 is retained with exact relevant input/archive identities, not rerun.

[ARC42 owns this bounded status](arc42/appendix-requirements.adoc#c04-ordinary-shadow-lsp-2026-11-15);
`.refactorkit/runs/c04-ordinary-shadow-lsp-r001/archive-audit.json` and
`ordinary-shadow-lsp-matrix.json` own local evidence. Dedicated direct-ID shadow
families, native refusals, framed runtime queries and original full C04/C02-C17/
release remain `actionable`. The isolated IDE candidate is unchanged.

## Enumerator/parameter cursors and LSP symbol classification

The shared LSP mapper now emits Function12 and Constant14 instead of Variable13
for function and enumerator rows in the initial client-kind set. Four actual
C11/C17 failures become four passing native cases/16 steps. Exact enumerator
and parameter families, optional declarations, workspace/outline locations and
kinds, saved bytes and native cleanup pass. The same 23 preexisting LSP tests
pass before/after without skips; thirteen native ordinary/standard/discovery
controls pass 52 steps. C668 is retained with exact relevant input/archive proof,
not rerun. Native readers, core models and shared source fixtures are unchanged.

[ARC42 owns this bounded status](arc42/appendix-requirements.adoc#c04-enumerator-parameter-lsp-2026-11-15);
`.refactorkit/runs/c04-enumerator-parameter-lsp-r001/archive-audit.json` and
`enumerator-parameter-lsp-matrix.json` own local evidence. Extended kind
negotiation, value-shadow LSP, native refusals, framed runtime queries and the
original full C04/C02-C17/release scope remain unqualified/actionable. The
isolated IDE candidate retains its previous LSP payload.

## Standard LSP ordinary variable cursors

Six actual C11/C17 native cases pass 24 steps for local/global/static uses.
Definitions and references retain the owning source TU; a peer global use finds
the API definition and both optional header/source declarations. Standard LSP
Variable kind, separate peer local/static symbols, five physical value identities
and own-file outlines pass. Saved bytes and native cleanup pass. Seven prior
standard-LSP/discovery controls pass 28 steps on identical source. Production
and resources are unchanged; C668 is retained with exact relevant input/archive
hashes, not rerun.

[ARC42 owns this bounded status](arc42/appendix-requirements.adoc#c04-ordinary-variable-lsp-2026-11-15);
`.refactorkit/runs/c04-ordinary-variable-lsp-r001/archive-audit.json` and
`ordinary-variable-lsp-matrix.json` own local evidence. Enumerator/parameter/
value-shadow LSP consumers, native refusal envelopes, framed runtime queries
and original full C04/C02-C17/release remain `actionable`.

## Enumerator and parameter IDs through CLI, daemon and MCP

Four actual C11/C17 native cases pass 16 steps on selected CLI/daemon/MCP routes.
The header enumerator retains two references and its definition/declaration
observed by both original TUs. The source parameter retains one initializer use
and excludes same-named global, prototype-parameter and nested-local identities.
Exact locations/IDs, optional declarations, query metadata, snapshot/compiler
hashes, saved inputs and native cleanup pass. Seven prior variable/discovery
controls pass 28 steps. Production and shared fixture are unchanged; C668 is
retained with exact relevant input/archive identities, not rerun.

[ARC42 owns this bounded status](arc42/appendix-requirements.adoc#c04-enumerator-parameter-public-2026-11-14);
`.refactorkit/runs/c04-enumerator-parameter-public-r001/archive-audit.json` and
`enumerator-parameter-public-matrix.json` own local evidence. Ordinary-value and
value-shadow LSP consumers, native refusal envelopes, framed runtime queries
and original full C04/C02-C17/release remain `actionable`.

## Ordinary variable IDs through CLI, daemon and MCP

Six actual C11/C17 native cases pass 24 steps for local, global and file-static
variables on selected CLI/daemon/MCP routes. The local has two references; the
global and selected static have one each. The global joins its header extern
and source definition, with both declaration sites available. Peer local/static
IDs and same-named parameter/nested values remain distinct. Snapshot/compiler
metadata, exact ranges, saved inputs and native cleanup pass.

Fresh C passes all 668 tests without skips after adding the shared source-only
fixture; seven ordinary-navigation/discovery controls pass 28 steps. Production
and dependencies are unchanged.
[ARC42 owns this bounded status](arc42/appendix-requirements.adoc#c04-ordinary-variable-public-2026-11-14);
`.refactorkit/runs/c04-ordinary-variable-public-r001/archive-audit.json` and
`ordinary-variable-public-matrix.json` own local evidence. Enumerator/parameter/
value-shadow consumers, framed runtime queries and original full C04/C02-C17/
release remain `actionable`. The isolated IDE candidate remains unchanged.

## Standard LSP function parameter and body tag cursors

Six actual C11/C17 native cases pass 24 steps at the first parameter, local type
and cast. Each cursor retains four exact references and the optional canonical
declaration. Complete definition is JsonNull; workspace symbols retain four
physical tags and the API outline three. Saved inputs and native cleanup pass.
Nineteen existing source/header/global LSP and discovery controls pass 76 steps
on identical source, giving a joined 24-cell LSP matrix. C668 is retained with
exact relevant input/archive hashes, not rerun; production is unchanged.

[ARC42 owns this bounded status](arc42/appendix-requirements.adoc#c04-function-parameter-tag-lsp-2026-11-14);
`.refactorkit/runs/c04-function-parameter-tag-lsp-r001/archive-audit.json` and
`function-parameter-lsp-matrix.json` own local evidence. Other cursor permutations
are unmeasured. Public ordinary-variable/enumerator and parameter/value-shadow
consumer coverage, framed runtime queries and original full C04/C02-C17/release
remain `actionable`. The isolated IDE candidate retains its previous C payload.

## Function parameter/body tag IDs through CLI, daemon and MCP

Six actual C11/C17 harness cases pass 24 steps for the first parameter tag ID.
The family contains four exact references (second parameter, local type, cast
and sizeof) and one canonical declaration. A separate prototype and two globals
retain distinct IDs. Missing complete definition returns its typed refusal.
Thirteen existing source/header public-query and discovery controls pass
52 steps with saved inputs and native cleanup preserved. C668 is retained with
exact relevant input/archive hashes, not rerun; production is unchanged.

[ARC42 owns this bounded status](arc42/appendix-requirements.adoc#c04-function-parameter-tag-public-2026-11-14);
`.refactorkit/runs/c04-function-parameter-tag-public-r001/archive-audit.json` and
`function-parameter-public-matrix.json` own local evidence. Native LSP function/
body cursors, other families/type forms, framed executables and original full
C04/C02-C17/release remain `actionable`. The isolated IDE candidate is unchanged.

## Function parameter tags and explicit body type uses

The pure reader now preserves native C11/C17 private tag families introduced in
function parameters. Two parameter spellings and a local type form one family;
the expanded profile also includes cast and sizeof type spellings. The other
prototype and source-private globals remain independent. Missing body evidence
and the currently unqualified compound-literal spelling refuse instead of
returning incomplete references. These read facts confer no mutation authority.

Eight specifications pass after six contract failures. The fresh complete C
suite passes 668 tests without skips; twenty-one existing native controls pass
84 steps on identical source. No new dependency is added.
[ARC42 owns integrated status](arc42/appendix-requirements.adoc#c04-function-parameter-tag-facts-2026-11-14);
`.refactorkit/runs/c04-function-parameter-tag-facts-qualification-r001/archive-audit.json`
owns local evidence. Public function/body consumer routes, other type forms and
the original full C04/C02-C17/release scope remain `actionable`. The isolated IDE
candidate retains its previous C payload.

## Header prototype IDs through CLI, daemon and MCP

Six actual C11/C17 harness cases qualify the first header prototype family on
CLI, daemon and MCP routes. Search retains six opaque IDs: four prototype IDs
across two original TUs and two source-private globals, at four physical
positions. The selected ID belongs to src/api.c while its declaration/reference
positions belong to include/api.h. Known references and optional declarations
are exact; no complete definition is invented. Producer hashes and snapshots
are checked. Six cases/24 steps and seven controls/28 steps pass, with saved
inputs and native cleanup preserved. C660 is retained with exact relevant
input/archive hashes, not rerun; production is unchanged.

[ARC42 owns this bounded status](arc42/appendix-requirements.adoc#c04-header-prototype-public-2026-11-14);
`.refactorkit/runs/c04-header-prototype-public-r001/archive-audit.json` and
`header-public-matrix.json` own local evidence. Other header family/surface
Cartesian cells, framed executables, function-definition parameter/body scopes
and full C04/0.8.0 remain `actionable`. The isolated IDE Java candidate is unchanged.

## Header queries with contradictory original-TU binding

Two actual C11/C17 LSP cases refuse when a standalone header identity conflicts
with a tag already visible in an includer. The response is PLAN_REFUSED with
`c.search.prototypeBindingUnavailable` and `BINDING_MISMATCH`; no query result
masquerades as successful empty analysis. Two cases/eight steps and seven
boundary/discovery controls/28 steps pass. Saved inputs and native cleanup pass;
no transaction or object output is produced. Production is unchanged; C660 is
retained with exact relevant input/archive hashes, not rerun.

[ARC42 owns this bounded status](arc42/appendix-requirements.adoc#c04-header-prototype-disagreement-public-2026-11-14);
`.refactorkit/runs/c04-header-prototype-disagreement-public-r001/archive-audit.json`
owns local evidence. The eighteen positive cells below remain sealed at their
test-source epoch; this negative slice does not rerun that matrix. Other header
family/surface cells and function-definition parameter/body contexts, other native acquisition
refusals and full C04/0.8.0 remain `actionable`. The isolated IDE Java candidate
is unchanged.

## Native source/header prototype and global LSP cursors

Eighteen actual C11/C17 standard-LSP cells cover the first/second occurrence in
one prototype, another prototype, source-global declaration/use and peer-global
declaration, with six additional cursors in a shared header. Prototype-only
complete definitions remain absent; source-private complete definitions retain
their owning TU. References and optional declarations preserve physical header
positions. Workspace symbols deduplicate to four physical tag declarations and
the header outline to two. Six new cases/24 steps and thirteen controls/52 steps
pass on identical source. Production is unchanged; C660 is retained with exact
C/core/build/resource and archive hashes, not rerun. Saved inputs and native
cleanup pass.

[ARC42 owns this bounded status](arc42/appendix-requirements.adoc#c04-header-prototype-lsp-2026-11-14);
`.refactorkit/runs/c04-header-prototype-lsp-r001/archive-audit.json` and
`prototype-lsp-matrix.json` own source-epoch evidence. The public disagreement
refusal is qualified above; function-definition parameter/body scopes and other native acquisition
refusals and full C04/0.8.0 remain `actionable`. The isolated IDE Java candidate
retains its older C payload and remains unchanged.

## Composed prototype declaration capacity

Four actual native C11/C17 daemon cases verify the 16,384-declaration boundary.
At the limit, default search returns 200 rows with accurate total/truncation
metadata; filtering returns the four distinct prototype/global tag families.
One above the limit returns `c.search.prototypeBindingUnavailable: INPUT_LIMIT`.
Saved inputs, absent transactions/object files and native cleanup are verified.
Seven existing controls pass 28 steps. C654 is retained against exact relevant
source/build/resource/archive hashes; it was not rerun for this public Story.

[ARC42 owns this bounded status](arc42/appendix-requirements.adoc#c04-prototype-composed-limit-public-2026-11-14);
`.refactorkit/runs/c04-prototype-composed-limit-public-r001/archive-audit.json`
owns local evidence. Other native acquisition refusals, actual header/definition parameter contexts, full C04
and 0.8.0 remain `actionable`; the isolated IDE candidate is unchanged.

## Public prototype tag queries in source

Six native C11/C17 cells select the first/other source prototype or the
source-private global tag through one actual CLI/daemon/MCP route per cell.
Compiler-owned parameter spans and native identities now compose the shared
index and family queries. Exact four-family IDs/coordinates, known references
and optional declarations retain compiler/server/snapshot metadata; a prototype
without a complete definition returns a typed definition refusal. The same
source passes fresh C650 and nineteen public/LSP/discovery controls (76 steps).
Saved inputs and native child cleanup pass. No mutation authority is added.

[ARC42 owns this bounded status](arc42/appendix-requirements.adoc#c04-prototype-tag-public-2026-11-14);
`.refactorkit/runs/c04-prototype-tag-public-r001/archive-audit.json` and
`prototype-public-matrix.json` own local evidence. The twelve prototype/global LSP cells are qualified above. Other
native-acquisition refusal envelopes, framed executable protocols, actual header/
function-definition parameter contexts and full C04/0.8.0 remain `actionable`.
The isolated IDE Java candidate retains its older C payload and excludes these
source increments; this is not a runtime distribution or full route Cartesian
qualification.

## Public local typedef matrix

Twelve native public cells cover C11/C17 block/shadow/nested/pointer/chain/
repeated aliases. Each records its actual CLI/daemon/MCP route, exact local
family coordinates and distinct outer/peer IDs. Repeated aliases retain one
canonical definition and both declarations. The last six cases pass 24 steps;
thirteen existing controls pass 52. Production is unchanged; C626 is retained
with exact relevant hashes, not rerun.

[ARC42 owns this bounded status](arc42/appendix-requirements.adoc#c04-extended-typedef-public-2026-11-14);
`.refactorkit/runs/c04-extended-typedef-public-r001/archive-audit.json` and
`extended-typedef-public-matrix.json` own local evidence. The full route Cartesian
product, ordinary-value shadow public consumers, native prototype integration
and full C04/0.8.0 remain `actionable`. The twelve LSP cells are qualified below.
The sealed SDK27 runtime is unchanged and excludes these increments.

## Local typedef LSP use cursors

Twelve native standard-LSP cells cover C11/C17 block/shadow/nested/pointer/chain/
repeated aliases. Exact local definitions/references and optional declarations
exclude outer/peer families; workspace/outline retain all alias positions.
Repeated aliases retain one canonical definition and both declarations. The last
six cases pass 24 steps; thirteen fresh scalar-alias/boundary/discovery controls
pass 52 steps on identical source. Saved inputs and cleanup pass. Production is
unchanged; C626 is retained with exact relevant hashes, not rerun.

[ARC42 owns this bounded status](arc42/appendix-requirements.adoc#c04-extended-typedef-lsp-2026-11-14);
`.refactorkit/runs/c04-extended-typedef-lsp-r001/archive-audit.json` and
`extended-typedef-lsp-matrix.json` own local evidence. Ordinary-value shadow
public/LSP consumers, native prototype integration, framed runtime and full
C04/0.8.0 remain `actionable`. The sealed SDK27 runtime is unchanged and excludes
these increments.

## Public local scalar typedef queries

Six native CLI/daemon/MCP C11/C17 block/shadow/nested scalar-alias cases retain
exact definitions/references, optional declarations, distinct local/outer/peer
IDs and ORDINARY lookup space. Saved bytes, compiler/snapshot metadata and no
transaction are checked. Thirteen existing controls pass 52 steps. Production
is unchanged; C626 is retained with exact relevant hashes, not rerun.

[ARC42 owns this bounded status](arc42/appendix-requirements.adoc#c04-scoped-typedef-public-2026-11-14);
`.refactorkit/runs/c04-scoped-typedef-public-r001/archive-audit.json` and
`scoped-typedef-public-matrix.json` own local evidence. Each cell records one
actual route, not every route Cartesian product. Extended alias public consumers,
typedef LSP, prototype extraction, framed runtime and full C04/0.8.0 remain
`actionable`. The sealed SDK27 runtime is unchanged and excludes these increments.

## Local-enum LSP use cursors

Six native standard-LSP cases cover all C11/C17 block/shadow/nested enum
use-cursor cells. Local definitions/references and optional declarations retain
exact positions and exclude outer/peer families; workspace/outline retain all
tag positions. Thirteen existing controls pass 52 steps. Saved inputs and
cleanup pass; production is unchanged. C608 is retained with exact relevant
hash/archive checks, not rerun.

[ARC42 owns this bounded status](arc42/appendix-requirements.adoc#c04-scoped-enum-lsp-2026-11-14);
`.refactorkit/runs/c04-scoped-enum-lsp-r001/archive-audit.json` and
`scoped-enum-lsp-matrix.json` own local evidence. Typedef/prototype scope
extraction, framed runtime and full C04/0.8.0 remain `actionable`. The unchanged
sealed SDK27 runtime does not include these source increments.

## Public local-enum queries

Six native CLI/daemon/MCP cases retain exact C11/C17 block/shadow/nested enum
definitions/references, optional declarations and distinct local/outer/peer IDs.
Compiler/snapshot metadata, saved bytes and no transaction are checked. Thirteen
existing controls pass 52 steps; C608 is retained with exact relevant hashes,
not rerun. No production, expression, runner or dependency changes are needed.

[ARC42 owns this bounded status](arc42/appendix-requirements.adoc#c04-scoped-enum-public-2026-11-14);
`.refactorkit/runs/c04-scoped-enum-public-r001/archive-audit.json` and
`scoped-enum-public-matrix.json` own local evidence. The matrix records one actual
route per cell, not every route Cartesian product. Local enum LSP, typedef/
prototype extraction, framed runtime and full C04/0.8.0 remain `actionable`.
The sealed SDK27 runtime is unchanged and does not include these source increments.

## Local record LSP use cursors

The joined native matrix covers twelve distinct C11/C17 block/shadow/nested-
forward struct/union use-cursor cells. Exact local definitions/references and
optional declarations exclude outer/peer families, while workspace/outline
retain all tag positions. The last six cases pass 24 steps and discovery passes
four; the first six were freshly rerun under the same enum-read production.
Production/glue are unchanged for this Story; C608 is retained with exact
relevant source/build/archive hashes, not rerun.

[ARC42 owns this bounded status](arc42/appendix-requirements.adoc#c04-scoped-record-lsp-cross-cells-2026-11-14);
`.refactorkit/runs/c04-scoped-record-lsp-cross-cells-r001/archive-audit.json`
and `scoped-lsp-matrix.json` own local evidence. New local-enum public/editor
Stories, typedef/prototype scope extraction, framed runtime and full C04/0.8.0
remain `actionable`. The sealed SDK27 runtime is unchanged and does not include
these source increments.

## Public block-scoped record queries

The joined native matrix covers twelve distinct C11/C17 block/shadow/nested-
forward struct/union cells. Each records its actual CLI/daemon/MCP route, exact
local definitions/references, optional declarations and distinct outer/peer IDs;
it does not cover every route Cartesian product. The last six cases pass 24
steps and discovery passes four. Production/glue are unchanged; C602 and thirteen
previous controls are retained with exact relevant hashes, not rerun.

[ARC42 owns this bounded status](arc42/appendix-requirements.adoc#c04-scoped-record-cross-cells-2026-11-14);
`.refactorkit/runs/c04-scoped-record-public-cross-cells-r001/archive-audit.json`
and `scoped-public-matrix.json` own local evidence. Scoped LSP, parameter/prototype
extraction, framed runtime and full C04/0.8.0 remain `actionable`. The sealed SDK27
runtime is unchanged and does not include these source increments.

## Qualified direct-header record matrix

All eight C11/C17 header forward/incomplete struct/union cells now have native
standard-LSP evidence from a cursor in `include/api.h`. Exact workspace/outline
positions, complete-definition availability and references with/without
declarations retain the actual inclusion contexts. The final two native cases
pass eight steps and source discovery passes four steps with unchanged
production/glue. C582 is retained after exact module/all-production/build/query-
glue and archive checks.

[ARC42 owns this bounded status](arc42/appendix-requirements.adoc#c04-direct-header-record-lsp-cross-cells-2026-11-14);
`.refactorkit/runs/c04-direct-header-record-lsp-cross-cells-r001/archive-audit.json`
and `direct-header-matrix.json` own local evidence. Scoped families, framed runtime
and full C04/0.8.0 remain `actionable`. No sealed SDK27 runtime or release is changed.

## Initial direct-header record cursor checkpoint

A saved header record cursor now uses its actual captured inclusion contexts.
Six C11/C17 direct `.h` LSP cases retain exact workspace/outline positions,
complete-definition availability and references with/without declarations.
The selector prefers the original header declaration and stable TU ordering;
missing context remains a typed refusal. Source-TU binding and managed typed
no-definition responses are preserved. Header compilation options are obtained
from existing native contexts.

Eight pure header Specs pass after RED; fresh C582 and 19 existing managed,
source-cursor, unavailable-analysis and discovery cases pass without module
failures/skips or Story failures. [ARC42 owns the bounded status](arc42/appendix-requirements.adoc#c04-direct-header-record-lsp-2026-11-14);
`.refactorkit/runs/c04-direct-header-record-lsp-r001/archive-audit.json` owns local
evidence. The direct-header matrix above covers its remaining cross-cells.
Scoped families, framed runtime and full C04/0.8.0 remain `actionable`. No sealed SDK27 runtime or release
is changed.

## Qualified LSP source-cursor record matrix

All sixteen C11/C17 source/header forward/incomplete struct/union cells now have
native standard-LSP evidence from a cursor in `src/api.c`. Exact workspace/outline
locations, complete-definition availability and references with/without
declarations retain their original TU identity. Incomplete records return a null
LSP definition and preserve known references; managed definition remains typed.

The last six plus four native cases pass 40 steps with unchanged production/glue;
each Story also passes source discovery. C574 is retained with exact module,
all-production/build/query-glue and archive hash evidence. [ARC42 owns this bounded status](arc42/appendix-requirements.adoc#c04-incomplete-record-lsp-cross-cells-2026-11-14);
`.refactorkit/runs/c04-incomplete-record-lsp-cross-cells-r001/archive-audit.json`
and `representative-lsp-matrix.json` own local evidence. The direct-header
checkpoint above extends these source-cursor cells. Remaining direct-header
cross-cells, scoped families, framed executables/runtime qualification and full
C04/0.8.0 remain `actionable`. No sealed SDK27 candidate or release is changed.

## Initial standard LSP record checkpoint

Six C11/C17 record cursor cases now retain their original captured TU binding.
A source-private incomplete tag cannot inherit the complete definition of an
unrelated homonym in another TU. Record cursors use native compiler family facts;
ordinary cursor navigation keeps its clangd route. A known incomplete record
returns a null LSP definition and exact references with optional declarations.
Managed definition retains `c.navigation.definitionUnavailable`.

The six native LSP cases pass 24 steps. Sixteen pure cursor-binding Specs pass;
fresh C574 and 19 existing managed/LSP/unavailable/discovery cases pass without
module failures/skips or Story failures. Original REDs and partial GREEN5/6 are
retained. [ARC42 owns the bounded status](arc42/appendix-requirements.adoc#c04-record-definition-availability-lsp-2026-11-14);
`.refactorkit/runs/c04-record-definition-availability-lsp-r001/archive-audit.json`
owns the initial local evidence; the remaining source-cursor cells are covered by
the matrix above. Direct header cursors, scopes, framed executable/runtime
qualification and full C04/0.8.0 remain
`actionable`. No sealed SDK27 candidate or release is changed.

## Qualified public record query matrix

The current joined matrix covers 16 C11/C17 source/header
forward/incomplete struct/union cells. Every row records its executed CLI,
daemon or MCP route and archived report/hash. Forward declarations resolve the
later complete definition; incomplete records retain exact references with and
without declarations and explicitly refuse definition with
`c.navigation.definitionUnavailable`.

The last four cross-cells pass 16 native steps and source discovery passes four
steps. C558 is retained after exact C-module, all-production/build and report-hash
checks. [ARC42 owns the bounded status](arc42/appendix-requirements.adoc#c04-record-query-cross-cells-2026-11-14);
`.refactorkit/runs/c04-record-query-cross-cells-r001/archive-audit.json` and
`representative-matrix.json` own local evidence. The source-cursor LSP cells are
covered by the LSP matrix above. Direct header/scoped families, complete framed-
protocol/runtime qualification and full C04/0.8.0 remain
`actionable`. No sealed SDK27 runtime or release is changed.

## Qualified incomplete record query checkpoint

A captured incomplete struct/union has a known declaration and known references,
even when no complete definition exists. CLI and in-process daemon/MCP now retain
those references, with optional declaration inclusion; definition explicitly
refuses with `c.navigation.definitionUnavailable`. Missing native evidence and
unknown symbols remain separate failures. Header declarations merge their exact
TU observations without duplicating physical sites. Mutation authority is unchanged.

The six public cases pass 24 steps. Eight pure Specs cover source/header
struct/union C11/C17 and missing-TU controls; fresh C558 and 13 existing
forward/query/discovery cases pass without module failures/skips or Story failures.
[ARC42 owns the bounded status](arc42/appendix-requirements.adoc#c04-incomplete-record-public-2026-11-14);
`.refactorkit/runs/c04-incomplete-record-public-r001/archive-audit.json` owns local
evidence. Its public cross-cells are covered by the matrix above. Forward/incomplete
LSP, scopes and full C04/0.8.0 remain `actionable`; framed executable qualification
is separate. The sealed
SDK27 candidate17f remains unchanged and does not include these source increments.

## Qualified forward record query checkpoint

On the current source, CLI and in-process daemon/MCP resolve a selected forward
struct/union declaration to its later complete source definition and exact
compiler-backed references. Six C11/C17 source/header cases pass 24 steps;
C550 and 39 existing query/member/LSP/discovery cases also pass on the same
source, without module failures/skips or Story failures.

The complete-definition ID remains distinct from the requested forward ID.
Unrelated TU homonyms, compiler/snapshot evidence and unchanged source inputs
are checked. Read-only record facts have their own model, while mutation and
enum authority remain unchanged. [ARC42 owns this bounded status](arc42/appendix-requirements.adoc#c04-forward-record-public-2026-11-14);
`.refactorkit/runs/c04-forward-record-public-r001/archive-audit.json` owns local
evidence. This adds no runtime to the sealed SDK27 candidate17f.

Incomplete public reads and the remaining forward cross-cells are covered by the
matrix above. Forward/incomplete LSP, scoped families and framed executable
qualification remain `actionable`. Full C04 and 0.8.0 remain open.

## Qualified source typedef rename checkpoint

The library managed lifecycle qualifies selected TU-owned typedef declarations
for scalar, pointer and chained aliases in C11/C17 `.c` files. Preview, mandatory
native diagnostics, Apply and exact rollback retain unrelated TU homonyms and
same-spelled member declarations, initializer designators and member references.

Unqualified source contexts and collisions return typed refusals without edits,
lease or approval. Changed source/compiler inputs and altered leases refuse
Apply while preserving caller bytes and leaving no WAL. Existing header routing
is retained. Final local qualification passes 26 behavioral cases/104 steps and
C532 tests without failures/skips, including 12 source typedef Specs.
[ARC42 owns the bounded status](arc42/appendix-requirements.adoc#c06-source-typedef-managed-2026-11-14);
`.refactorkit/runs/c06-source-typedef-managed-qualification-r001/archive-audit.json`
owns the same-agent evidence.

Broader source shapes/block scopes, other mutation families and protocol/runtime
qualification remain `actionable`. This source qualification delivers no new
runtime and is not included in the sealed SDK27 candidate17f. Full C06/0.8.0
remains open.

## Qualified source type query cross-cells

The managed read fixtures now also qualify source union C17 and enum C11 on
CLI, daemon, MCP and standard LSP. Exact declaration/reference ranges remain in
the selected TU despite unrelated same-spelled declarations in another TU. LSP
also verifies references with and without the declaration. The public Story and
its controls pass 13 cases/52 steps; the separate LSP Story and controls pass
9 cases/36 steps, with real Clang processes and unchanged saved inputs.

[ARC42 public queries](arc42/appendix-requirements.adoc#c04-source-type-cross-cells-2026-11-14)
and [LSP authority](arc42/appendix-requirements.adoc#c04-source-type-cross-cells-lsp-2026-11-14)
own this bounded status. The fixtures dispatch through in-process sessions;
framed executable protocol qualification remains distinct. Broader source type
families and full C04/C02-C17/release acceptance remain `actionable`.

## Qualified private source-tag rename checkpoint

On the current source tree, the managed rename lifecycle admits bounded private
`struct`, `union` and `enum` tags in C11/C17 when the selected `.c` file is an exact
captured native translation unit and compiler-derived type-exposure closure is
qualified. Unqualified external type exposure is a typed compiler-backed safety
refusal, with no edits, lease or transaction. Existing header decoding is retained.

The source checkpoint passes 39 behavioral cases/156 steps and C520 tests without
failures or skips, including exact source Apply/rollback, exposure refusals, header
controls and source navigation. [ARC42 owns integrated status](arc42/appendix-requirements.adoc#c06-source-tag-exposure-managed-2026-11-14);
`.refactorkit/runs/c06-source-tag-exposure-qualification-r001/archive-audit.json`
owns the same-agent local evidence. Wider source families, consumers and protocol
matrices remain `actionable`. This does not qualify all C06 or publish 0.8.0, and it
is not included in the separately sealed SDK27 runtime previously delivered.

## Toolchain and configuration

The Clang toolchain is **explicit and external**; nothing is auto-installed or
discovered from `PATH` by default.

- Discovery is configured with `allowPathToolDiscovery = false`. Callers pass
  exact executable paths.
- Each executable must be an **existing non-symlink file** (directories must be
  non-symlink too); a symlink or missing path is refused with
  `clang.toolchainPathInvalid`. Use the real path of the explicitly selected
  compiler; a system `clang` symlink can advance independently of the qualified
  reference toolchain.
- No implicit download, global install, compiler plugin/wrapper execution, or
  arbitrary build execution. Compile commands are **untrusted data**: parsed for
  flags, never executed.
- Unsafe compiler flags are denied through `CCompilationDatabasePolicy`
  (`UNSAFE_COMPILER_FLAGS` denylist → `c.unsafeCompilerFlag`).

Before starting clangd, `CClangdLaunchContext` admits a unique root or `build/`
database, requires string `arguments` and contained non-symlink C inputs, and
creates a private database using the configured compiler. Its bounded flag set
accepts explicit C11/C17, identifier defines/undefines, contained include paths,
selected warning flags and C language mode. Compile/output flags are removed;
response files, compiler config files, plugins, command strings, unknown flags
and conflicting databases refuse with `c.clangdConfigurationRefused` before
launch. Project/user clangd configuration and persistent background indexing
are disabled; private launch material is removed when the client closes.

The [six-case launch Story](../features/c-clangd-launch-configuration.feature)
passes locally with explicitly selected 22.1.8 artifacts. It proves captured
defines, an ignored `.clangd` override, four pre-launch refusals, preserved
project bytes and process cleanup. This is bounded C02 evidence, not full
toolchain/input closure or C03 qualification. The legacy no-database navigation
fallback remains; it does not establish captured-context mutation authority.

Clangd also checks the retained native compiler/server/formatter runtime before
launch and before and after document or semantic exchanges. Missing capture
refuses with `c.clangdRuntimeUnavailable`; changed identity, executable selector
or runtime input refuses with `c.clangdRuntimeChanged`, and a live session is
closed when its retained runtime changes. The [six-case native-runtime Story](../features/c-compiler-runtime-currency.feature)
qualifies this bounded consumer cell with the private Clang 22.1.8 toolchain.
It does not establish complete source/header/build authority, filesystem-race
closure or runtime leases; full C02 remains actionable.

Compilation-record source, include and sysroot paths resolve from that record's
working directory, as required by the
[Clang compilation-database contract](https://clang.llvm.org/docs/JSONCompilationDatabase.html).
Absolute inputs retain their identity; source files and working directories
outside the workspace refuse. The [six-case directory Story](../features/c-compilation-record-working-directory.feature)
checks captured TU/include identities, Build Model source roots and real 22.1.8
definition lookup in a header, including nested and parent-relative layouts.
This is bounded path evidence; it does not qualify sysroot semantics, multiple
configurations or complete captured header/consumer closure.

A running client retains the byte identity of its selected database and absence
assertions for the other root/`build/` candidate. Document opening and semantic
queries check those inputs before and after exchange. A changed, deleted,
unreadable or newly competing database closes the process and private launch
material; definition/rename refuse with `c.clangdConfigurationChanged`, while
references return `Unavailable`, never zero references. A fresh session must
capture the current configuration. Disabled `.clangd` configuration is not an
authority input. The [six-case warm-session Story](../features/c-warm-configuration-invalidation.feature)
proves these bounded cases with real 22.1.8, project-byte preservation and cleanup.
This does not yet capture all source/header/toolchain inputs or guarantee
atomic observation against live replacement races.

Definition responses distinguish protocol absence (`null` or an empty location
array) from malformed/unsupported evidence (`clangd.resultInvalid`). The
protocol permits multiple locations, but the adapter's single-definition model
refuses that ambiguity with `clangd.definitionAmbiguous` instead of choosing the
first location. Numeric ranges remain numeric and local file URIs are encoded/
decoded through URI-aware paths, preserving escaped spaces and Unicode.
The [six-case response Story](../features/c-definition-response-identity.feature)
uses an authored framed protocol peer and checks byte preservation/cleanup.
This is transport evidence, not compiler identity or full C04 qualification.
The [LSP definition contract](https://github.com/microsoft/language-server-protocol/blob/gh-pages/_specifications/lsp/3.18/language/definition.md)
owns protocol response shapes; this satellite owns the adapter's bounded model.

## Usage (surfaces)

C is a **route capability**, not a kernel `LanguageAdapterRegistry` adapter, and
is **not** advertised through LSP `initialize`/kernel adapters. Mutations are
managed RefactorKit commands.

| Surface | Entry point | Notes |
|---|---|---|
| CLI | `refactorkit c <op> ...` | Requires `--clang`, `--clangd`, `--clang-format` (missing → exit `2`). Delegates to daemon `c.preview` + `refactor.apply`. Exit `0` on `PREVIEW`/`applied`, `1` on refusal/internal error, `2` on invalid params. |
| Daemon | `c.preview` (JSON-RPC) | Retains the operation-specific `CRefactoringFacade.diagnosticsGate(snapshot, plan)` through managed Apply. |
| MCP | `preview_refactoring` with `languageId=c`; `available_refactorings` advertises `c` | Deterministic preview tools; apply/rollback reuse the shared pending-plan path and retain the C gate. |
| LSP | C documents (`c`/`h`) are recognized | **Managed-only ownership** (`refactorkit-managed-clang-semantic-via-cli-daemon-mcp-lsp`): editor-applied C rename, code actions, formatting and diagnostics are **refused**, not silently empty. |

C refactoring commands preview by default. Use `--apply` to request a managed
write. Combining `--preview` and `--apply` returns exit code `2` before the CLI
opens the workspace.

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
inline-local      -> inlineLocalVariable (compiler-bound direct int-literal cell)
relocate          -> relocateComponent
```

## C component relocation

The managed `c relocate` command accepts captured component and destination
directories, explicit Clang paths and preview or apply authorization. Use
`--forward-headers` with a comma-separated list of old workspace-relative
header paths to preserve those include locations. The daemon `c.preview`
operation `relocateComponent` accepts the equivalent string as
`arguments.forwardHeaders`.

```bash
refactorkit c relocate . --component-dir component --new-dir library \
  --forward-headers component/include/api.h \
  --clang <clang-22.1.8> --clangd <clangd-22.1.8> \
  --clang-format <clang-format-22.1.8> --preview
```

Each requested header must belong to the moved component and have compiler
evidence from captured consumers under the literal CMake target. The staged
plan keeps a quoted relative include at the old location and validates it using
the captured C11 or C17 consumer configurations. Optional `--mapping` renames
API symbols in the same transaction. Approval covers the complete staged
relocation and rollback restores the original header and project files.
Compatibility evidence covers the captured configuration and old include path.
External consumers and ABI compatibility require their own qualification.

## C inline

`c inline` supports a compiler-bound single-use internal `static int` function
in one captured C11/C17 translation unit. The admitted function has one return
expression using int literals/parameters and `+`, `-`, `*`, or the qualified
read of a character from a string constant. Arguments are integer literals or
initialized non-volatile local/parameter reads, and the caller is straight-line.
Its complete prototype/definition
family is removed. Binding-aware substitution preserves precedence and string
contents, and the compiled candidate must match the independently substituted
semantic image. Captures, unsupported conversions, side effects, recursion,
macros, lifetime/aliasing uncertainty and unqualified control flow refuse.
The first cell contains only the selected declaration family and its caller;
headers and other top-level declarations need separate qualification.

```bash
refactorkit c inline . --file main.c --symbol add \
  --clang <clang-22.1.8> --clangd <clangd-22.1.8> \
  --clang-format <clang-format-22.1.8> --preview
refactorkit c inline . --file main.c --symbol add \
  --clang <clang-22.1.8> --clangd <clangd-22.1.8> \
  --clang-format <clang-format-22.1.8> --apply
refactorkit patch rollback <transaction-id> --root .
```

The compilation database, source, native runtime and resource identities remain
bound to the preview. Managed Apply rebuilds that authority and rejects a changed
snapshot or altered semantic evidence. `inline-local` has its own narrower
compiler-bound cell: one direct int decimal literal initialized and returned
through one bound local in a straight-line `main` function. Broader accepted
variants require separate qualification.

This usage document describes the admitted route. The integrated authority and
local evidence are [C12 function inline](arc42/appendix-requirements.adoc#c12-managed-inline-function-2026-11-12),
[refusal boundaries](arc42/appendix-requirements.adoc#c12-inline-function-refusal-matrix-2026-11-12)
and [CLI execution](arc42/appendix-requirements.adoc#c16-inline-function-cli-2026-11-12).

## C safe delete

The `c safe-delete` command requires an explicit symbol and Clang toolchain. A
preview may be available without managed-Apply authority; `--apply` remains
fail-closed unless the snapshot includes an exact supported compilation context.
The admitted managed route binds the source edit to the captured root
`compile_commands.json`, Clang 22.1.8 and its runtime identity, replays the
semantic safe-delete proof, and checks baseline/staged compiler diagnostics
before Apply. It then records a transaction that the CLI can roll back:

```bash
refactorkit c safe-delete . --symbol unused \
  --clang <clang-22.1.8> --clangd <clangd-22.1.8> \
  --clang-format <clang-format-22.1.8> --preview
refactorkit c safe-delete . --symbol unused \
  --clang <clang-22.1.8> --clangd <clangd-22.1.8> \
  --clang-format <clang-format-22.1.8> --apply
refactorkit patch rollback <transaction-id> --root .
```

The current positive Apply/rollback acceptance covers one isolated file-static
function and one unused file-static object, each in a single C17 translation
unit. For both shapes the CLI proves there are no semantic references, applies
one declaration deletion, checks staged diagnostics, and rolls back the source,
compilation database and snapshot exactly. This does not qualify included-file
or project-wide reference closure, other compiler configurations,
public/external consumers, or daemon/MCP/LSP parity. See the [integrated C13
requirement and evidence](arc42/appendix-requirements.adoc#c13-c-cli-safe-delete-managed-apply-and-rollback-2026-10-26).

`rename-prefix` requires one explicit mapping argument. Pass
`--mapping OLD=NEW[,OLD=NEW...]`; each pair names one API symbol family, and the compiler
resolves its declarations and captured consumers. Empty pairs, duplicate old
names, malformed `=` separators, and combining `--mapping` with `--to` are
rejected before a daemon request. For example:

```bash
refactorkit c rename-prefix . --mapping calculate=compute,load_item=read_item \
  --clang <path> --clangd <path> --clang-format <path>
```

For `formatFile`, omitting both line flags formats the whole captured file.
Passing both `--start-line` and `--end-line` selects a zero-based, inclusive
line range; partial, malformed, reversed, and out-of-file ranges refuse with
`clang.formatInvalidRange`. For example, this previews formatting of the third
line:

```bash
refactorkit c format --file main.c --start-line 2 --end-line 2 \
  --clang <path> --clangd <path> --clang-format <path>
```

The formatter uses the captured source and style, preserves unselected content,
and never sorts includes.

Per-operation CLI flags include `--symbol`, `--to`, `--file`, `--target`,
`--mapping` for `rename-prefix`, `--component-dir`, `--new-dir`, `--temp-name`,
`--old-param`, `--new-param`,
`--start-line`, `--start-char`, `--end-line`, `--end-char`.

## Include ordering

`organizeIncludes` is a bounded ordering operation with one narrow redundant-
include preview. It reorders only the admitted standard angle-header subset
(`stdio.h` and `stdlib.h`). It removes the second directive only when there is
exactly one repeated quoted target group with two occurrences and that target
resolves to a direct captured local `.h` source whose first line is
`#pragma once`; unique other includes remain untouched. It preserves the
removed directive's line, indentation and trailing comment. Multiple repeated
groups and every other repeated-include shape remain refused. The preview moves
each reordered original directive line as a unit,
preserving its indentation and attached `//` or `/* ... */` comment.
The four-case Story, including the comment-preservation case, passes with its
receipt and RED/GREEN evidence under
`.refactorkit/runs/c14-comment-preservation-r001/`. This closes only that source
preservation edge; the separate pragma-once slice below covers one narrow
redundant-include case only, not declaration-group independence, configuration
matrices, or full C14 behavior.

The exact
`REQ-C-INCLUDE-ORDER-001#repeated-local-pragma-once-include-is-removed-with-source-lines-preserved`
Story verifies one no-write CLI preview against a captured C17 translation unit.
Four reused steps pass. The pure-domain `COrganizeIncludesPlannerSpec` passes four
cases, including refusal for an uncaptured header, a header without the marker,
and an include guard. A second exact Story applies the same edit and rolls it
back; it verifies byte-exact restoration of `main.c`, unchanged `api.h`, and a
transaction record. Its four reused steps pass. Receipts, effective-glue
inventories and reports are under `.refactorkit/runs/c14-once-header-r001/` and
`.refactorkit/runs/c14-once-header-apply-r001/`. Separate preview and
managed-Apply/exact-rollback Stories cover one repeated pair alongside another
unique include. They verify that the unique include remains in the staged
source, the preview does not write, and rollback restores the original bytes.
Evidence is under `.refactorkit/runs/c14-once-header-unique-r001/` and
`.refactorkit/runs/c14-once-header-unique-apply-r001/`.
These cells do not establish broad include redundancy or order independence; see the [integrated ARC42
requirement](arc42/appendix-requirements.adoc#c14-repeated-local-pragma-once-include-2026-10-29).

The CLI has separate Story BDD coverage for a no-write preview and managed
Apply/exact rollback of the admitted `<stdio.h>`/`<stdlib.h>` ordering. Apply is
enabled only for one captured C17 translation unit with a unique root
`compile_commands.json`, no command-line defines/include paths, and retained
Clang 22.1.8/runtime identity. Rollback restores the exact source bytes. This
single-TU ordering cell does not establish broader redundant-include removal,
include independence, multi-TU/configuration coverage, or full C14 behavior; see the
[integrated ARC42 requirement](arc42/appendix-requirements.adoc#c14-include-line-preservation-2026-10-23).

The same operation-specific CLI preview now has bounded captured-context Stories
for three and eight C17 translation units. The compilation database must cover
every captured `.c` input exactly once, and staged diagnostics run across all
captured units while the patch still edits only the selected source. More than
eight units fail closed: the CLI reports
`c.includeOrderCompilationContextUnavailable` (with the underlying
`c.compilationDatabaseLimit` reason) and emits no preview edit or transaction.
The current evidence samples 1, 2, 3 and 8 units plus the nine-unit refusal; it
does not qualify every intermediate count, compiler flag/configuration or the
full C14 matrix. See the [captured TU bound acceptance](arc42/appendix-requirements.adoc#c14-captured-c17-translation-unit-bound-2026-11-10).

A separate C11 Story admits the bounded captured `-D` subset when each
translation unit has distinct macro names; a replacement, when present, must
be decimal digits. Its green two-TU case captures `-DRK_MAIN_MODE=1` and
`-DRK_CONSUMER_MODE=2`; symbolic replacement and duplicate macro names refuse
before preview. Current and staged diagnostics pass, while source/header/database
bytes remain unchanged and no transaction journal is written. This does not
admit arbitrary preprocessing or general configuration independence. See the
[integrated C14 acceptance](arc42/appendix-requirements.adoc#c14-c11-literal-defines-2026-11-10).

The declaration-only local-header subset also accepts one or more distinct
built-in `TYPE name(void);` prototypes in each directly captured `.h` file, up
to the existing 16 KiB per-header bound. Every prototype must use a recognized
built-in return type; typedefs, duplicate names, additional declarations and
preprocessor effects remain refused. The exact C17 preview Story verifies two
such headers reorder only after captured diagnostics pass, with source, headers
and compilation database unchanged. This is one bounded positive cell, not
general declaration-order proof; see the
[integrated C14 acceptance](arc42/appendix-requirements.adoc#c14-multiple-built-in-prototypes-per-local-header-2026-11-10).
A separate managed CLI Story applies the same ordered preview, verifies one copy
of each include in the applied source, then checks byte-exact source/header
restoration and the persisted `ROLLED_BACK` transaction state.

A separate refusal Story covers two captured local headers that both declare
the same built-in prototype name. The facade returns the existing quoted-header
order-safety refusal, produces no edit, preserves source and header bytes, and
creates no transaction journal. This records a fail-closed boundary already
enforced by the planner; it adds acceptance coverage, not a new reorder rule.
The one-case Story and 12-case Kotest Spec pass, and the full C module suite
passes 316/316 with no skips, failures or errors. See the
[integrated C14 acceptance](arc42/appendix-requirements.adoc#c14-duplicate-prototype-names-remain-refused-2026-11-10).

## Safety gate and recovery

Every managed C mutation keeps the shared contract:

- Preview → explicit approval → **diagnostics gate** → WAL/apply/rollback.
- Managed Apply selects `CRefactoringFacade.diagnosticsGate(snapshot, plan)`.
  Each admitted operation retains its own source, compiler-context and staged
  validation authority. The no-argument `clang-exact-v1` gate deliberately
  refuses because it lacks per-TU context. A compilation database alone grants
  no authority; it does not block separately qualified operation-specific gates.
- Missing toolchain → refusal/error envelope, **never** a silent capability.
- Rollback conflicts (post-image mismatch after an external edit) are refused,
  not nominal restores. Pre-WAL refusals write no journal record.

## Refusals (typed diagnostic codes)

The managed preview entry point now admits one selected fully-prototyped
`static int` function family in a single captured C11/C17 source. Its lease
binds the declaration coordinates, complete direct-call family, native runtime,
compilation database and exact staged edits. Explicit approval is required;
source/database drift or altered authority refuses without a transaction. The
six-case source Story verifies exact Apply/rollback while preserving comments,
strings and an unrelated same-prefix function. Other unqualified families keep
semantic previews; managed writes require their own qualification. See
[C06 managed authority](arc42/appendix-requirements.adoc#c06-managed-single-symbol-2026-11-12).

That bounded function cell also has CLI, daemon, MCP and managed LSP
Apply/rollback evidence. For a declaration starting at line 0, character 11:

```bash
refactorkit c rename . --symbol old --to renamed --file main.c --line 0 --character 11 \
  --clang /path/to/clang-22 --clangd /path/to/clangd --clang-format /path/to/clang-format --apply
```

Preview first by omitting `--apply`. Managed LSP uses
`refactorkit.c.preview`/`refactorkit.applyPlan`/`refactorkit.rollback`;
native `textDocument/rename` has its separate editor-owned contract. See the
[surface evidence](arc42/appendix-requirements.adoc#c16-managed-symbol-rename-lsp-2026-11-12).

The development source also qualifies source-owned local variables, parameters
and file-static objects in one captured ASCII C source. The native examples use
`int`: local C11, parameter/internal-object C17, plus separately executed managed
transport cells. Declaration/type/scope identity and bound references are
rechecked before and after the staged edit; comments, strings and shadowed names
are preserved. Preprocessing inputs, external linkage and unclosed families
remain refused for this cell. See the [variable domain and authority](arc42/appendix-requirements.adoc#c06-variable-managed-authority-2026-11-13)
and [LSP cell](arc42/appendix-requirements.adoc#c16-variable-rename-managed-lsp-2026-11-13).

A further source qualification covers one named `struct` field declared in a
captured header and consumed by two C translation units. The independent compiler
model includes designated initializers and ordinary member accesses while keeping
another record's same-spelled field unchanged. Both complete TU contexts must
reproduce; missing consumers, stale source/database and altered authority refuse
without WAL. The bounded cell uses ASCII source, literal quoted workspace
includes and optional `#pragma once`; other preprocessing/conditional guards,
macro-origin fields, opaque `offsetof` and overwritten designators remain
unqualified/refused. The actual library C11/C17, CLI/daemon/MCP and managed LSP
cells are recorded in [header-field authority](arc42/appendix-requirements.adoc#c06-header-field-managed-authority-2026-11-13),
[transport evidence](arc42/appendix-requirements.adoc#c16-header-field-managed-transports-2026-11-13)
and [LSP evidence](arc42/appendix-requirements.adoc#c16-header-field-managed-lsp-2026-11-13).
These source results do not qualify an older sealed runtime archive or every
kind/dialect/transport combination. Artifact capability claims follow their own
immutable manifest.

The shared-header ordinary-family cell also qualifies library managed Apply and
exact rollback for an external function/prototype in C11, a global object and an
enumerator in C17. Both translation units and one physical captured definition
are required for external functions/objects. Compiler declaration chains and
binding-matched uses preserve local shadows, unrelated fields and lexical decoys.
Six native cases cover the three positive families plus incomplete consumers,
database drift and altered authority. Four further C17 cases refuse ordinary
identifier collisions and allow a function destination already used by an enum
tag. These are local source-consumer proofs; external ABI compatibility, other
contexts and managed protocol routes need their own qualification. See
[ordinary-family authority](arc42/appendix-requirements.adoc#c06-ordinary-header-managed-authority-2026-11-13)
and [lookup-space evidence](arc42/appendix-requirements.adoc#c06-ordinary-header-lookup-spaces-2026-11-13).

The named-tag library cell covers one shared header and two captured TU consumers:
struct C11, union C17 and enum C17, with declaration, cast and `sizeof` type uses.
Compiler tag identity and owned type witnesses reproduce the independent edit
set and staged type model. Ordinary local names, comments and strings remain
intact. A tag destination already used by a function is permitted; an existing
tag destination refuses with `c.renameTagTargetPresent`. Incomplete consumers,
local tag shadows, macro-origin declarations, source/database drift, altered
leases and unqualified GNU spellings refuse without WAL. The native library
Stories pass fourteen cases/56 steps on main; the extended fixture and updated
modules retain separate regression evidence. See the
[tag authority](arc42/appendix-requirements.adoc#c06-tag-managed-authority-2026-11-13),
[refusal controls](arc42/appendix-requirements.adoc#c06-tag-managed-refusals-2026-11-13)
and [hidden-include boundary](arc42/appendix-requirements.adoc#c03-tag-hidden-include-refusals-2026-11-13).

Shared compiler-facts admission rejects digraph/comment-prefixed directives and
implementation-reserved extension spellings outside the explicit ISO word set.
Nominal `-std=c17 -pedantic-errors` acceptance alone is insufficient to qualify
source syntax. Unacquired includes may retain a read-only semantic preview while
managed authority is refused; this does not claim that clangd never reads
project-declared external inputs. Direct-source tag/alias cells, broader
configuration/declarator families and tag/typedef managed protocol routes remain
unqualified. Sealed runtime archives retain their own manifests.

The shared-header typedef library gate now passes twelve native cases/48 steps:
C11/C17 Apply/exact rollback across two captured units, a legal existing tag at
the destination, ordinary-identifier collision, stale compilation data, altered
lease, incomplete consumers, local alias shadow, macro declaration, source drift,
GNU source and an opaque initializer designator. Its own compiler-type lease
reproduces thirteen physical declaration/type edits and preserves ordinary,
member, tag and label bindings, including shifted ranges. Twenty pure-domain
Specs pass on independently acquired native fixture data. See
[typedef authority](arc42/appendix-requirements.adoc#c06-typedef-managed-authority-2026-11-13),
[destination tags](arc42/appendix-requirements.adoc#c06-typedef-destination-tags-domain-2026-11-13)
and [refusal controls](arc42/appendix-requirements.adoc#c06-typedef-managed-refusals-2026-11-13).
The final extended fixture and complete updated C/LSP modules require their
separate terminal regression reports.

`renameSymbol` accepts either the legacy `symbol`/`newName` pair or an exact
workspace-relative `file` plus zero-based `line` and `character` selection. The
three selection fields are all-or-none; the path must be a captured C-family
file and the position must identify the requested identifier token, otherwise
the facade returns `c.renameSelectionInvalid`. A name-only request still refuses
when same-spelled identifiers occur across files (`c.renameSymbolAmbiguous`).
An exact selection seeds clangd `textDocument/rename`; clangd supplies the
binding-matched edit proposal and the normalizer constrains it to the captured
snapshot. For the independently qualified header-typedef cell, a clangd name
refusal can instead produce an explicitly identified native Clang binding
preview, only with complete declaration/type/namespace proof and exact staged
compiler validation. There is no textual fallback. The bounded
[C06 exact-selection Story](../features/c-semantic-rename-exact-selection.feature)
proves a header prototype, definition and call-site preview across three files,
while same-name comment/string decoys remain unchanged. A separate scenario in
that feature proves one externally linked global object declared in a header,
defined in one translation unit and used in another, with comment/string decoys
unchanged. Another scenario selects one function-local object when a nested
local and a parameter have the same spelling; only the selected local binding
is renamed. A separate scenario renames one typedef through a header prototype,
definition and caller across two translation units; a distinct `struct` tag and
comment/string decoys remain unchanged. Its original target is collision-free. The later managed typedef Story also
qualifies a destination already used by a separate C tag; its independent Clang
proof preserves that tag even when clangd refuses the name. A further scenario
renames the `ORDER_PENDING` enum constant declared in a header and used from two
translation units; it preserves the other enumerator and comment/string decoys.
Another scenario selects the `raw_count` struct member in a header and previews
its declaration and member-use updates across two translation units, preserving
the other field and lexical decoys. A parameter scenario selects `amount` in a
function definition while a nested local has the same spelling; only the
parameter declaration and its bound uses are renamed, leaving the local,
prototype parameter and comment/string decoys unchanged. A separate file-scope
`static` object scenario distinguishes its internal-linkage binding from both a
same-spelled external object in another translation unit and a nested local;
only the selected static object and its bound uses are renamed.
These cells do **not** prove complete consumer coverage, complete static/global
linkage coverage, broad parameter-family support, general
collision/shadowing/linkage analysis, or macro origins. Before asking clangd for edits, the facade refuses
with `c.renameTargetIdentifierPresent` when the target occurs as an ordinary or
unclassified identifier token in any captured C-family file. In C source only,
an occurrence directly introduced by `struct`, `union` or `enum` is excluded
when neither token is in a preprocessing directive; C tag names occupy a
separate lookup namespace from ordinary function/variable names. The tokenizer
ignores comments and string/character literals, and this check never creates
edits. C++ type names, macro/directive tokens, labels, members, and spellings in
other uncertain contexts still refuse conservatively. This is not general
scope-aware binding proof. Separate Stories exercise an ordinary same-translation-
unit function collision and a tag-only target spelling through library, daemon
and MCP. Those original preview-only cells do not qualify CLI parity or managed
Apply for every family. The separately executed managed function, variable and
header-field, ordinary-header, tag and typedef cells above retain their own
compiler-context authority. Broader type/configuration and managed transport
cells, including direct-source tags and aliases, still need qualification.
Representative codes:

- Symbol/selection: `c.renameSelectionInvalid`, `c.renameSymbolAmbiguous`, `c.renameSymbolNotFound`,
  `c.renameTargetIdentifierPresent`, `c.renameTagTargetPresent`, `c.renameTypedefTargetPresent`, `clang.renameInvalidName`, `clang.renameNotFound`, `clangd.definitionNotFound`,
  `clangd.resultInvalid`, `clangd.refused`.
- Compilation model: `c.compilationDatabaseMissing`,
  `c.compilationDatabaseInvalid`, `c.compilationConfigurationConflict`,
  `c.compilationConfigurationIncomplete`, `c.duplicateTranslationUnit`,
  `c.unsafeCompilerFlag`.
- Toolchain/process: `clang.toolchainPathInvalid`, `clangd.notRunning`,
  `clangd.unavailable`, `clang.compilerDiagnosticsTimeout`,
  `clang.compilerDiagnosticsMalformed`, `clang.compilerDiagnosticsUnavailable`.
- Formatting: `clang.formatFailed`, `clang.formatNonIdempotent`,
  `clang.formatInvalidRange`, `clang.formatUnavailable`,
  `clang.formatSourceMissing`, `clang.formatSourceChanged`.
- Argument/plan boundary: `c.invalidRange`, `c.commandShellOperator`, plus
  typed `REFUSED` for missing/unknown arguments.

## Not qualified / not run (explicit)

- Nine requirement-first **containment** slices now cover failed/empty formatter
  output, explicit no-sort formatting, malformed/errored/over-limit clangd results,
  same-line safe-delete neighbors, four inline-local counterexamples, five
  inline-function cases, extract type/scope, custom/macro include order and
  pre-WAL refusal of root-level compilation databases with unconsumed flags. The
  observed counterexamples are either refused or have equivalent bounded output;
  this does **not** qualify C07/C11–C14, complete references or AST-safe deletion.
- The separate include-aware CMake library gate now applies and exactly rolls
  back source, public-header and bounded component moves across two captured
  C11/C17 TUs. It binds ordered PRIVATE/PUBLIC target roots, captured quoted
  include targets and real current/staged Clang diagnostics under its own lease.
  A public-header move updates both consumers; a component/source move updates
  the saved compilation database in the same transaction as CMake and source
  paths. Unchanged database spelling and formatting are retained. Twelve native
  library cases/48 steps pass, including missing flags/TUs, unsafe flags, source
  drift, lease forgery and a newly shadowing header. Fifteen pure graph Specs
  support the literal include closure. No project compiler command or CMake code
  is executed. The profile requires explicit root-directory arguments, C11/C17
  and contained -I roots; conditional preprocessing, generated/unowned inputs,
  other flags/build grammar and uncaptured external consumers are unqualified.
  See [managed authority](arc42/appendix-requirements.adoc#c08-c15-include-managed-2026-11-13),
  [integrity controls](arc42/appendix-requirements.adoc#c08-c15-include-managed-boundaries-2026-11-13)
  and [literal graph](arc42/appendix-requirements.adoc#c08-c15-quoted-include-domain-2026-11-13).
  The corrected final-source compatibility run passes both new Stories, the
  old no-include controls/component, C440, a source-inventory canary and LSP72
  (ten skips, all 36 C scenarios passed). The inventory's 687 registered cases
  are not all executed by its canary. This does not qualify the new include-aware
  protocol cells or optional API-prefix composition. See the
  [checkpoint](arc42/appendix-requirements.adoc#c08-c15-post-include-qualification-2026-11-13).

- Six further requirement-first **CMake partial** slices capture a regular UTF-8
  root `CMakeLists.txt` (at most 512 KiB) as snapshot-hashed auxiliary input,
  including daemon/MCP snapshots; preview a single target-bound `.c` move and
  a bounded source/header component relocation with literal `add_library`,
  `add_executable`, `target_sources(PRIVATE)` and
  `target_include_directories(PRIVATE|PUBLIC)` paths; and refuse variable, generator,
  conditional, included-script, unbound-header, uncaptured nested-manifest,
  missing-context and late-added nested-manifest examples.
  The later PUBLIC-root preview Story passes six examples/24 steps; the
  existing PRIVATE fixture passes its three examples/12 steps again. Both
  produce exact manifest/source/header previews with no write or transaction.
  Fourteen pure domain Specs also bind ordered per-TU include origins to unique
  literal target owners and exact old/staged root/TU paths. Missing/mismatched
  flags, owners, paths or root transitions refuse. PUBLIC visibility remains
  explicit, consistent with the [CMake 3.20 contract](https://cmake.org/cmake/help/v3.20/command/target_include_directories.html).
  This qualifies structural preview and the separate pure model. The earlier
  no-include gate retains its own context; the newer include-aware gate above
  supplies separate application proof. Transitive/exported consumers and full
  C08/C15 remain unqualified.
  See [root-transition domain](arc42/appendix-requirements.adoc#c08-c15-target-include-context-domain-2026-11-13)
  and [PUBLIC preview](arc42/appendix-requirements.adoc#c08-c15-public-include-preview-2026-11-13).
  No CMake, project command or captured compile command is executed. The
  manifest and moved files are represented in one `WorkspaceEdit`, **not** an
  independently qualified post-build model. Three additional local Story slices
  admit **only one** executable or STATIC library target with one `.c` TU, no
  includes or path-sensitive source macros, and a unique literal root
  `compile_commands.json` containing `arguments` for configured, hash-bound
  Clang 22.1.8, explicit `-std=c11`/`-std=c17` and bounded `-D` flags. The
  database command is data, never executed: the configured Clang runs
  `-fsyntax-only` on baseline, staged and post-apply overlays. The preview-bound
  gate rechecks database and compiler hashes, refuses late alternate/nested
  build contexts, and exercises explicit approval, WAL and byte-exact rollback
  through library, daemon and MCP. A further requirement-first preview slice
  runs real baseline/staged Clang checks **before** approval for this admitted
  cell, exposes verified diagnostic evidence in the preview, and refuses a
  compiler-error source before returning an applicable plan. Its complete
  operation-authority lease binds the snapshot/edit plus database, configured
  compiler and staged hashes; the retained apply gate rechecks those same
  identities. The public C facade also replaces a caller-supplied disabled or
  unrelated gate for CMake source moves with this protected gate: a changed
  database cannot bypass diagnostics before WAL. Unsupported root-database
  contexts are explicitly marked `c.previewDiagnosticsUnavailable` and remain
  non-applicable at managed apply.
  A further Story slice extends the admitted cell from `add_executable` alone
  to `add_library(component STATIC src/component/a.c)` with a single captured
  TU. SHARED, MODULE, OBJECT and INTERFACE_LIBRARY remain refused by the same
  `CManagedCMakeContext` gate; the initial multi-source example also refuses
  because another TU lacks a database entry.
  A subsequent Story slice admits bounded multi-TU projects: the compilation
  database may contain entries for multiple translation units, but every snapshot
  `.c` file must have exactly one entry, the moved file must have no includes
  resolving to workspace files or path-sensitive macros (leaf), and no other
  workspace file may include the moved source (bounded inbound check).
  A further six-case [project-command completeness Story](../features/c-cmake-multi-tu-compile-context.feature) requires
  **bidirectional** completeness: every entry must also name a snapshot TU.
  Every TU's command, not just the moved TU's, must satisfy the same literal-root,
  configured-Clang, single-source C11/C17 and bounded `-D` grammar; foreign
  compilers, target/sysroot/include overrides, output metadata, incomplete literal
  argument arrays and unknown arguments refuse before WAL. No database command
  is executed. That command-model Story established diagnostics only for the
  moved leaf TU. The successor six-case [project-TU diagnostics Story](../features/c-cmake-all-tu-diagnostics.feature)
  now runs configured Clang syntax checks for **every captured TU**, in stable
  path order, with each TU's own sanitized dialect/defines in both baseline and
  staged overlays; only the moved path is rebased. An unchanged helper/third TU
  compiler error, define-dependent error or helper dependency broken only in the
  staged overlay refuses the preview before lease/approval/WAL. Warnings retain
  workspace source paths rather than deleted overlay paths. The preview lease
  binds `c.diagnosticsScope=captured-project-tus-v1` and the TU count; managed apply
  reuses the same project checks and rechecks captured inputs around evaluation.
  Sequential work is capped at **8 TUs** (existing per-process timeout/output
  bounds remain); larger projects return explicit unavailable context and cannot
  apply. This is bounded syntax evidence, not CMake target-TU ownership,
  linking/API/ABI proof or complete target/header/consumer closure. Non-leaf
  sources, incomplete/extra-entry databases and multi-source compiler commands
  refuse; a literal target with multiple source bindings is not by itself a
  refusal. Historical command-only feature/receipts remain unchanged; the new
  Story extends their earlier moved-TU-only evidence boundary.
  The successor six-case [literal target–TU ownership Story](../features/c-cmake-tu-ownership.feature)
  retains **all source occurrences** from that same bounded parser. Every
  captured/database TU must have exactly one normalized source occurrence and
  owner, and each owner must be an executable or an **explicit STATIC** library.
  Unowned helper TUs, duplicate occurrences (including aliases across primary
  declarations/`target_sources`), multiple targets for one TU and an unsupported
  helper owner stay inspectable but have unavailable authority, no lease/verified
  compiler claim and no managed WAL. Literal PRIVATE sources and multiple distinct
  eligible targets can pass; their original owner/type must remain identical in
  the staged model, with only the moved path rebased. The lease requires
  `c.targetOwnershipScope=literal-root-tu-ownership-v1` and a deterministic
  `c.targetOwnershipSha256` for the before/staged ownership tables; dropped scope
  or altered hash refuses at the retained apply gate despite caller-disabled
  diagnostics. Controls apply and roll back exact recorded bytes. This establishes
  **bounded declarative membership**, not evaluated CMake target behavior,
  CMake-derived flags matching the captured commands, linking or complete consumer
  closure. No second grammar, project-code execution or `.a`-suffix target inference
  was added. Earlier features/receipts remain partial and unchanged.
  The one-scenario [shared-header managed Apply Story](../features/c-cmake-shared-header-managed-apply.feature)
  extends this source-only cell to one `.h` bound by literal `target_sources(PRIVATE)`.
  It admits a shared header with two literal quoted TU consumers when every captured
  `.c` TU has exactly one supported command and ownership entry. One preview binds
  the header move, both include rewrites and manifest path edit; configured Clang
  checks every TU before/after staging, and explicit Apply/rollback restores all
  recorded bytes. The prior `.c` move remains leaf-only: inbound source includes
  and path-sensitive moved sources refuse with `c.compilationContextUnavailable`;
  a structural preview without an exact diagnostics lease cannot be reclassified
  as native-runtime drift. Generated headers, macro/path-sensitive cases, unknown bindings,
  more than eight TUs and include-root `-I`/`target_include_directories` authority
  remain unavailable. This does not qualify directory moves, arbitrary public
  header consumers, link/API/ABI behavior or C08 as a whole.
  The [C15 component managed Apply Story](../features/c-cmake-component-managed-apply.feature)
  adds one positive component cell: exactly two captured `.c` translation units,
  one literal root `STATIC` target owner, no includes, path-sensitive tokens or
  include roots, and exact root compilation-database entries. The lease binds
  the complete path map and staged ownership; configured Clang checks baseline
  and staged inputs, and explicit Apply/rollback restores source and manifest
  bytes. Other include-bearing, mixed-language or dynamically described
  components, public include roots and complete C15 consumer/ABI behavior remain
  unsupported. Integrated status: [ARC42 C15](arc42/appendix-requirements.adoc).
  The local six-case [include-flag-origin Story](../features/c-cmake-include-flag-origin.feature)
  now retains immutable **all-target literal PRIVATE/PUBLIC include roots**, with
  explicit visibility, from that
  same parser. Such declarations are compiler-flag origins: when the admitted
  database command policy excludes include flags, omitting `-I` cannot consume
  a manifest root or prove it irrelevant through clean syntax. Any nonempty root
  list in baseline/staged capture makes authority unavailable with
  `c.cmakeFlagsUnconsumed`, no lease/verified claim and managed refusal before WAL,
  even for another TU's STATIC owner, a root equal to the moved source parent or
  an empty/unused directory. Matching manifest/database `-I` is still refused by
  the existing unsupported-command guard; no include/header admission is added.
  No-root controls retain distinct per-TU dialect/defines, apply and roll back
  exact bytes. The lease binds `c.cmakeFlagPolicy=literal-root-no-include-flags-v1`
  into evidence identity and the retained gate; absent/downgraded policy requires
  a fresh preview and cannot be bypassed by caller-disabled diagnostics.
  This is **unconsumed explicit-origin containment**, not complete CMake-derived
  flag/default/environment/configuration agreement or include/consumer proof.
  The Story is partial; earlier features/receipts and release evidence remain
  unchanged. No CMake or captured compilation command is executed.
  The six-case [direct consumer-probe Story](../features/c-cmake-consumer-probes.feature)
  contains a different gap: unchanged helper TUs can select different valid
  branches through header-availability queries after a move while every syntax
  check succeeds. Relative, macro-header-alias, `__has_include_next` and spliced
  source identifiers demonstrate different compiler branch markers in S0/C1;
  an absolute workspace-path query instead still sees physical S0 outside the
  simulated overlay and is not a demonstrated staged branch change.
  After clean staged **all-TU compiler checks**, direct source identifier tokens
  `__has_include`/`__has_include_next` in any captured TU now refuse with
  `c.compilationConsumerUnavailable` and the responsible TU, without lease,
  applicable edits/approval or WAL. Existing compiler errors retain precedence:
  the historical helper `#error` case still follows its original staged compiler
  refusal path, and its feature/receipt is unchanged. The existing tokenizer is
  reused after backslash/newline splicing; query-looking comments/strings and an
  unchanged system include remain admitted in the positive control, which applies
  and rolls back exact bytes. `c.consumerProbePolicy=direct-availability-tokens-refused-v1`
  binds lease evidence identity and retained apply; missing/downgraded policy
  requires fresh preview despite caller-disabled gates.
  This is **direct source-token containment**, not a full consumer/preprocessor
  graph. That Story disclosed by inspection that `-DPROBE=__has_include` can supply
  the operator without a source token; its historical receipt remains unchanged.
  The successor six-case [compiler-define Story](../features/c-cmake-compiler-define-probes.feature)
  now checks every TU's **sanitized define tokens**, reusing the same token guard
  after clean staged compiler/source-policy checks. Joined/separated defines,
  a chain containing an explicit operator definition, `__has_include_next` and
  an unused operator definition on the moved TU refuse with responsible rebased
  TU and `c.compilationConsumerUnavailable`, no edits/lease/approval or WAL.
  Four helpers demonstrably select different valid compiler branch markers;
  the unused definition is containment, not a demonstrated behavior change.
  Ordinary defines and lookalike identifiers pass and restore exact bytes on
  managed rollback. No new argument acceptance grammar or macro interpreter.
  Separate `c.consumerDefinePolicy=availability-define-tokens-refused-v1` and
  `c.consumerDefinePolicySha256` bind policy to scope/count, before/staged snapshots,
  database/compiler/ownership identities. Retained apply recomputes the digest;
  absent/downgraded policy or changed digest requires fresh preview even with
  caller-disabled gates. The previous source policy and core `evidenceHash`
  format are preserved, not reinterpreted as define expansion evidence.
  This closes the **explicit operator-token define path**, not token-pasted,
  generated/external expansion, arbitrary translation phases, runtime equivalence
  or complete consumer/CMake-derived flag agreement. Requirements and earlier
  receipts stay partial; no catalogue promotion or release qualification follows
  from GREEN.
  The next local six-case [source token-paste Story](../features/c-cmake-token-paste-consumers.feature)
  contains ordinary `##` and digraph `%:%:` source operators: a macro can paste
  `__has_` and `include` without any direct availability identifier in source or
  compiler defines. Four helper fixtures (classic, phase-two-spliced, digraph,
  chained expansion) select different compiler-valid branches after main moves.
  An unused moved-TU paste definition is containment, not a demonstrated branch
  change. After clean staged compiler/source/define checks, every captured source
  is checked with the existing tokenizer and shared phase-two splice preparation;
  actual adjacent punctuation operators refuse `c.compilationConsumerUnavailable`
  with responsible rebased TU, no applicable edits/lease/approval/verified claim or
  WAL. No macro activation/relevance inference or second lexer is introduced.
  Operator-looking comment/string trivia and separate null `#` directives remain
  admitted. A tests-only adjacency refinement exposed and corrected an initial
  false positive across null-directive lines; that failed run remains preserved.
  Separate `c.consumerPastePolicy=source-token-paste-refused-v1` and
  `c.consumerPastePolicySha256` bind scope/count, snapshots, database/compiler and
  ownership identities with the same layout as the define policy. Retained apply
  requires fresh preview for absent/downgraded policy or changed digest despite
  caller-disabled diagnostics. The shared digest calculation preserves the old
  define digest; the core evidenceHash and source/define policy identities remain
  unchanged. The no-paste control applies and restores exact source/manifest/database
  bytes and snapshot on rollback. This is **source-operator containment only**,
  not phase-one trigraph handling, arbitrary/generated/external preprocessing,
  complete header/consumer resolution or source/runtime equivalence. Phase-one
  operator reconstruction remains an **actionable, inspection-only** gap requiring
  its own Story; no dedicated trigraph runtime evidence is claimed here. No earlier
  feature/receipt is rewritten; requirements remain partial, no release promotion.
  The successor local six-case [raw trigraph Story](../features/c-cmake-trigraph-consumers.feature)
  now contains the disclosed C11/C17 phase-one input path: after clean staged
  compiler/source/define/paste checks, recognized raw trigraph triplets refuse in
  every captured TU, **including comments and string literals**, with responsible
  rebased TU and `c.compilationConsumerUnavailable`. ISO phase-one replacement
  precedes that lexer; comment/string exclusion cannot prove irrelevance. Only
  adjacent `??` plus one of `= / ' ( ) ! < > -` matches; ordinary `??x`/`??0`/`??_`
  question-looking trivia remains admitted. No transformation, second lexer,
  activation or full translation-phase model is added. Reference Clang 22.1.8
  under the same sanitized `-pedantic-errors` shows two compiler-valid query
  branches change present→absent (trigraph paste and identifier splice). A valid
  block-comment splice, a converted string sizeof assertion and moved braces are
  containment controls, **not demonstrated move-induced branch changes**.
  The initial line-comment fixture produced a real compiler ERROR and was invalid
  as a clean control; its failed run is preserved, only the fixture changed to a
  block comment, no flags or compiler/error precedence were weakened.
  `c.consumerTrigraphPolicy=raw-c11-c17-trigraphs-refused-v1` and
  `c.consumerTrigraphPolicySha256` use the existing captured-input digest layout.
  Missing/downgraded policy or altered digest requires fresh preview before WAL
  despite caller-disabled diagnostics. No lease/approval/applicable edits/verified
  claim for refused previews; the ordinary control genuinely applies and restores
  exact source/manifest/database bytes and snapshot. Core/source/define/paste
  identities and old digest oracles remain unchanged, with no receipt rewrite.
  This is **raw-triplet containment**, not a complete preprocessor, independently
  discriminating qualification of all nine spellings, generated/external expansion,
  header/consumer graph or source/runtime equivalence. Requirements remain partial;
  no catalogue/independent acceptance/release promotion follows from local GREEN.
  A subsequent local six-case [resource-probe Story](../features/c-cmake-resource-probes.feature)
  closes another **direct source/define token path**, `__has_embed`: configured
  Clang 22.1.8 accepts this resource-availability operator even in the admitted
  C11/C17 contexts with `-pedantic-errors`. It is **not C23 or #embed ingestion
  support**. Four helper cases (direct C17, direct C11, spliced source, separated
  command-define alias) change compiler-valid present→absent markers after main
  moves; an unused moved-TU operator define is containment only, not a demonstrated
  branch change. After clean staged compiler/source/define/paste/trigraph checks,
  exact resource operator tokens in all source and sanitized -D payloads refuse
  `c.compilationConsumerUnavailable`, with distinct source/define reason and
  responsible rebased TU. Existing tokenizer/splice preparation and sanitizer
  are reused; no resource resolver, second lexer/argument grammar or macro
  activation inference. Probe-looking comment/string trivia and lookalike defines
  remain admitted and restore exact source/manifest/database bytes and snapshot.
  Separate `c.consumerEmbedPolicy=direct-source-define-embed-refused-v1` and
  `c.consumerEmbedPolicySha256` reuse the captured-input digest layout; missing/
  downgraded policy or altered digest requires fresh preview before WAL despite
  caller-disabled diagnostics. Refused previews have no applicable edits/lease/
  approval/verified claim. The original core and source/define/paste/trigraph
  identities/digests and earlier receipts remain intact. This is not arbitrary
  resource/header/include/preprocessing/consumer closure or source/runtime
  equivalence; no catalogue/independent acceptance/release promotion follows.
  A subsequent local six-case [newline-preparation Story](../features/c-cmake-newline-consumers.feature)
  corrects a compiler/managed-lexer mismatch: configured Clang recognizes lone
  CR as newline, while the earlier lexer input could swallow subsequent tokens
  inside `//` or fail to splice backslash-CR. Five helper cases (header/resource
  probes after CR comments, header/resource identifiers and paste joined by
  backslash-CR) produce compiler-valid present→absent markers. Only managed
  consumer lexical input now canonicalizes CRLF/CR to LF **before** existing
  splice/token preparation. Raw source/snapshot/manifest/database bytes and the
  global `CTokenizer`/other C parsing paths are not changed or qualified. Existing
  header/resource/paste refusals retain reason/TU/error precedence, not a blanket
  CR refusal. Ordinary CR-only comment/string trivia and separate null directives
  apply and roll back exact raw bytes/snapshot. Separate mandatory
  `c.consumerNewlinePolicy=lf-crlf-cr-before-splice-lex-v1` and
  `c.consumerNewlinePolicySha256` use the captured-policy digest layout; absent/
  downgraded scope or changed digest refuses before WAL despite caller-disabled
  gates. Original core/policy identities and digests/history stay intact; old
  previews need fresh newline-policy binding. Local RED/GREEN is not independent
  acceptance, whole translation-phase/encoding/preprocessing/consumer closure or
  source/runtime equivalence. Requirement remains partial. Its historical disclosure
  of split path macros was not qualified by that newline Story; the successor
  local six-case [moved-source path-macro Story](../features/c-cmake-moved-path-macros.feature)
  contains exact prepared source identifiers `__FILE__`, `__FILE_NAME__`,
  `__BASE_FILE__` and `__builtin_FILE` in the moved TU, reusing existing canonical
  newline/splice preparation and tokenizer. The original conservative raw-text
  guard remains unchanged; this is not new admission of raw spellings inside
  comments/strings. Spliced comment/string trivia and lookalikes remain admitted.
  Four exercised cases (LF FILE, CRLF BASE_FILE, CR FILE_NAME, chained FILE)
  observe compiler-valid path expansion strings changing for main.c→moved/entry.c;
  unused split builtin_FILE definition is containment only, not runtime/branch or
  exercised builtin expansion evidence. Overlay warnings bind suffix/basename
  and responsible workspace TU, not stable overlay roots/workspace-valued strings.
  Unsupported source inputs fail **during capture**: structural PREVIEW stays
  inspectable, without lease or evaluated/verified compiler claim; retained apply
  refuses `diagnostics.unavailable` before WAL despite caller-disabled gates.
  Mandatory supplementary
  `c.movedPathMacroPolicy=prepared-moved-source-path-identifiers-refused-v1` and
  `c.movedPathMacroPolicySha256` use the same captured-policy digest layout.
  Missing/downgraded policy or altered digest requires fresh preview. The ordinary
  control independently verifies binding/tamper refusal, applies and rolls back
  exact source/manifest/database bytes and snapshot. Core and all earlier policy
  identities/digests/historical receipts stay intact. Requirement remains partial,
  not complete path/encoding/translation-phase/preprocessing/consumer/source
  equivalence or catalogue/independent/release acceptance. Its historical
  disclosure of captured `-D` aliases was not qualified by that source Story.
  The successor local six-case [compiler-define path Story](../features/c-cmake-compiler-define-path-macros.feature)
  contains direct exact path identifiers in **every TU's already-sanitized -D
  payload**, reusing the same prepared-token predicate and sanitizer. No second
  argument grammar/lexer/macro interpreter or unused-safety/relevance inference.
  Three exercised joined FILE/separated BASE_FILE/chained FILE_NAME aliases have
  zero watched source identifiers and compiler-valid string expansion changes
  for main.c→moved/entry.c. Unused moved builtin and other-owner FILE definitions
  are containment only, not exercised expansion/runtime/branch changes. Numeric/
  lookalike ordinary defines remain admitted. After clean staged compiler and
  earlier consumer checks, `c.compilationConsumerUnavailable` names the rebased
  responsible TU and captured compiler-define path reason. Preview is **REFUSED**,
  with no applicable edits/affected files/lease/approval/verified claim; apply
  rejects `plan.notPreview` pre-WAL despite caller-disabled diagnostics. This is
  distinct from historical moved-source capture-time structural previews.
  Mandatory supplementary
  `c.consumerPathDefinePolicy=captured-define-path-identifiers-refused-v1` and
  `c.consumerPathDefinePolicySha256` use the existing captured-policy digest layout;
  absent/downgraded scope or altered digest needs fresh preview before WAL.
  Approved ordinary control independently verifies digest/tamper refusal and exact
  source/manifest/database byte/snapshot rollback. Core and all earlier scopes/
  digests/receipts, raw source guard and newline/splice semantics stay intact.
  Requirement remains partial, not complete flags/CMake/path/encoding/directive/
  generated/header-mediated/preprocessing/consumer/source-equivalence or independent/
  catalogue/release acceptance. That define Story's prepared-directive disclosure
  was inspection only, not qualification of this distinct origin.
  The successor local six-case [spliced moved-directive Story](../features/c-cmake-spliced-directives.feature)
  supplements existing moved-source raw special-directive and include checks with
  **the same LF/CRLF/CR-before-splice preparation** already used by managed token
  guards. Extracted preparation leaves tokenizer input and raw source/snapshot
  bytes unchanged; original raw checks/parser/sanitizer remain, no second lexer/
  operand grammar/macro interpreter or new header admission. Three captured TUs,
  unique executable/explicit STATIC owners and distinct own flags: four LF/CRLF/CR
  quoted or LF macro-operand includes observe clean **old→next branch markers**
  by selecting different snapshot `.c` input after main.c→moved/entry.c. Spliced
  `#line` observes the same valid line-90 warning in both candidates, **containment
  only**, not a move-induced branch/path/runtime change. Spliced angled system
  include plus placed comment/string trivia stays admitted, not a blanket splice
  refusal or complete trivia/directive grammar qualification.
  Prepared workspace-quoted/macro includes and existing special directives
  refuse during **capture**: inspectable structural PREVIEW/two edits/approval
  proposal, no lease/evaluated diagnostics/verified claim; apply refuses
  `diagnostics.unavailable` pre-WAL despite caller-disabled diagnostics. This is
  not staged REFUSED/`plan.notPreview`. Mandatory supplementary
  `c.movedDirectivePolicy=lf-crlf-cr-splice-moved-directives-v1` and
  `c.movedDirectivePolicySha256` bind the existing captured-policy digest layout;
  missing/downgraded/tampered binding requires fresh preview before WAL. Ordinary
  control independently checks digest/tamper refusal, approved apply and exact
  source/manifest/database byte/snapshot rollback. Core/prior scopes/digests/
  receipts and staged-consumer compiler-error ordering stay intact.
  Requirement remains partial, not complete directive/include/translation-phase/
  flag/CMake/path/preprocessing/consumer/source-equivalence or independent/release
  acceptance. That splice Story's other-spelling disclosure was inspection only.
  The successor local six-case [unparsed-directive token Story](../features/c-cmake-unparsed-directives.feature)
  supplements, not replaces, the raw/prepared guards using existing `sourceTokens`
  and the prepared include parser's line inventory. A line's first prepared token
  must be `#` or physically contiguous `%:`, followed by a same-line exact path-
  directive identifier. `include` with no parsed entry on that line refuses;
  `include_next`/`import`/`line` remain unsupported without operand interpretation.
  No second lexer/parser/directive grammar, macro expansion, relevance inference,
  source-byte changes or new header admission. Four compiler-clean source `.c`
  include spellings (`# include`, `%:include`, `#/**/include`, `#include/**/`)
  evade both older raw/prepared parsing and show **old→next branch markers**.
  `%:line` produces the same valid line-90 warning in both candidates, containment
  only, not a move-induced path/branch/runtime change. Canonical parsed angled
  system include, placed trivia/identifier lookalikes, separate null `#`/`%:` and
  later macro stringization stay admitted. This does not qualify every trivia/
  translation-phase boundary or infer unused safety.
  Capture-time inspectable structural PREVIEW/two edits/approval proposal without
  lease/evaluated diagnostics/verified claim remains; apply refuses
  `diagnostics.unavailable` pre-WAL even with caller-disabled diagnostics, not
  staged REFUSED/`plan.notPreview`. Mandatory supplementary
  `c.movedDirectiveTokenPolicy=unparsed-moved-directive-tokens-refused-v1` and
  `c.movedDirectiveTokenPolicySha256` use existing captured-policy digest layout;
  absent/downgraded/tampered bindings need fresh preview before WAL. Ordinary
  control independently checks digest/tampering, approved apply and exact source/
  manifest/database byte/snapshot rollback. Three own-flag TUs/literal owners,
  original core/prior scopes/digests/receipts/parser/preparation/sanitizer and
  compiler/consumer error ordering stay intact. Requirement partial, not complete
  directive/include/encoding/preprocessing/CMake/consumer/source-equivalence or
  independent/release acceptance. Documentation gap **actionable / inspection-only**:
  unchanged-helper inbound origins remain separate; multi-line comment/other phase
  boundaries and generated/header-mediated expansion remain unqualified. No
  successor counterexample or fix is supplied here.
  A subsequent Story slice refines the include constraint: angled includes
  (`#include <stdio.h>`) resolve via compiler system paths and are safe to move;
  quoted includes that resolve to workspace files (same directory or relative
  path in `snapshot.files`) remain blocked in the moved TU; `#include_next`,
  `#line`, `#import` and raw-detected `__FILE__`/`__builtin_FILE`/`__has_include`
  macros in that TU refuse; prepared source path identifiers additionally refuse
  as bounded above, with direct captured define identifiers additionally contained,
  with moved directive preparation/unparsed entry tokens additionally contained
  as bounded above, not complete lexical/macro or other expansion qualification.
  The unchanged-helper staged counterexample above does not
  establish general preprocessor or header-consumer independence.
  **Other** CMake/compilation-database apply contexts still fail closed,
  including component/tree moves outside the single bounded C15 cell above,
  unqualified header moves, include-root overrides,
  multiple commands per TU, projects above the eight-TU cap, unsupported target
  configurations and unconsumed flags. Full include graphs and diagnostics for
  arbitrary/generated or unmodeled build contexts remain unproven. Bounded no-follow traversal
  refuses on discovery-limit or I/O uncertainty.
- Remaining blockers include binding/data-flow/alias and conversion proof,
  expression control-flow/placement, arbitrary header/preprocessor independence,
  incomplete compilation-database parsing, nested/dynamic CMake and other build
  providers, CMake-derived flag/configuration agreement, include path and complete
  evaluated target/consumer proof for move/relocate.
  Other unmodeled configurations and an empty gate result do **not** establish
  source equivalence or C13 consumer closure. No release, download, independent
  acceptance or C17 catalogue closure is implied by local RED/GREEN evidence.
- The follow-up local six-case [compiler-exit Story](../features/c-compiler-exit-consistency.feature)
  addresses the earlier warning-only/nonzero parser discrepancy: a nonzero exit
  without a **parsed ERROR diagnostic** is now unavailable with the existing
  `clang.compilerDiagnosticsIncomplete` code and observed exit value. Warning or
  unmatched error summaries alone do not make a failed check available. Zero-exit
  warnings remain available and workspace-attributed; genuine compiler errors
  retain their diagnostics and still refuse the move. Baseline/staged faults have
  no preview lease/approval; a fault activated after preview also refuses managed
  apply before WAL despite caller-disabled diagnostics. The successful control
  applies and rolls back exact recorded bytes. These cases use an immutable,
  hash-bound **test-only wrapper** forwarding real Clang 22.1.8 syntax checks and
  overriding successful helper exits; they are not natural compiler-crash,
  wrapper/discovery/security, delegated-executable-closure or release qualification.
  No production wrapper policy changes. The historical all-TU feature/receipt and
  its earlier gap disclosure remain unchanged; requirement status stays partial.
- Full C10–C17 operation/surface acceptance is **pending**; the bounded slices
  above are implementation, not release rows.
- Semantic qualification on CI Linux **without clangd** relies on the
  `if (!Files.isExecutable(...)) return` guard in integration tests; that reads as
  passed rather than a formal skip and is **not** a PASS.
- Multifile header consumers, families, collisions/shadowing/linkage, macro
  origins, generated/dynamic or evaluated CMake binding and general multi-TU staged
  configuration/transaction acceptance remain gaps; the bounded ownership/syntax/WAL Stories
  do not close those broader obligations.
- Coverage is **UNMEASURED**; static analysis is non-blocking and not clean.
- No other host is qualified; no universal/ABI/whole-program guarantee is made.

## Captured function facts, bounded library cell

Authority: this satellite owns the caller contract. The
[ARC42 appendix](arc42/appendix-requirements.adoc) owns integrated status, and
[the 0.8.0 plan](releases/v0.8.0-plan.md) owns complete delivery acceptance.

`CProjectScanner` captures safe regular UTF-8 `compile_commands.json` and
`build/compile_commands.json` as auxiliary snapshot inputs without executing
recorded commands. Unsafe or oversized databases remain uncaptured; present
uncaptured authority refuses function analysis. `CFunctionFactsService.analyze`
accepts a captured snapshot, selected source path and function name. It returns
`Available` with source-bound family facts and process provenance, explicit
`NotFound`, or `Unavailable` with a structured diagnostic.

This cell requires explicitly configured hash-bound Clang 22.1.8, one admitted
translation unit, one owned source of at most 1 MiB and named integer signatures.
It conservatively refuses source containing preprocessing markers (`#`, a
backslash, `??`, or `_Pragma`) and additional header/source inputs. Compiler flags
use the existing admitted configuration boundary; output/plugin/response/project
inputs are removed or refused. The compiler receives private source material and
engine-owned AST flags. Bounded process/output/JSON traversal, captured-byte and
candidate-presence checks, overlay immutability and executable checks surround
the query. Family rules delegate to the
[pure requirement](requirements/c-function-family-facts.md).

The six-case [execution Story](../features/c-function-facts-captured-execution.feature)
uses real cached 22.1.8 on the current Linux x86-64-v4 host. It proves exact
prototype/definition/direct-call slices, address-taking, observable arguments,
stale-database/plugin/header refusal, workspace byte preservation and cleanup.
Local receipts and reports are in
`.refactorkit/runs/v080-function-execution-r001/`. These facts do not produce a
patch or add CLI/daemon/MCP/LSP operations. Complete source/header/tool resources,
other configurations and managed parameter operations remain unqualified;
C03/C04/C10 and 0.8.0 are not complete.

`CFunctionFactsService.analyzePreview(before, candidate, file, functionName)`
evaluates source-only candidate content in a private overlay while checking the
saved `before` snapshot before and after execution. Workspace identity, source
paths/languages and captured configuration must remain fixed. Available facts
carry the candidate snapshot/source identity. Candidate configuration changes or
additional sources refuse with `c.functionFacts.previewBoundaryInvalid`; changed
saved inputs refuse with `c.functionFacts.snapshotChanged`.

The six-case [staging Story](../features/c-function-facts-preview-evaluation.feature)
passes in C11 and C17 with an explicitly captured `-DREQUIRED=17`; an independent
missing-define compiler control fails. Invalid candidate syntax and both kinds
of authority change refuse. Saved source bytes remain unchanged and private
material/processes are cleaned. Local admission and RED/GREEN evidence is in
`.refactorkit/runs/v080-function-stage-r001/`. This is a bounded C05 library
evaluation cell; managed signature preview/apply/rollback and transports remain
required.

`CSignaturePlanner.reorderParameters(snapshot, file, functionName, originalIndices)`
now produces an approval-required `reorderParameters` patch from captured native
facts and the separately specified pure renderer. The mapping gives each new
slot's original index. Baseline and candidate compile in the admitted saved
context; unsupported families, argument effects, mappings and stale authority
return a refused plan with no edits and a structured refusal code.

The six-case [preview Story](../features/c-parameter-reordering-preview.feature)
checks exact prototype/definition/two-call edits, unchanged bodies and unrelated
names, Unicode/CRLF and C11/C17. Independently expected source and before/after
execution of locally authored fixtures preserve argument-to-parameter results.
Local admission and RED/GREEN evidence is in
`.refactorkit/runs/v080-parameter-reorder-preview-r001/`.

Successful reorder previews now carry an immutable `c.signature-reorder-v1`
authority lease binding exact edits, before/after identities, compiler identity
and original-index mapping. `CRefactoringFacade.diagnosticsGate(snapshot, plan)`
rebuilds the native preview before accepting that authority. The facade's
`apply` always retains this operation gate, including for callers supplying
disabled diagnostics. Missing authority, altered edits or an approval-disabled
plan cannot bypass it. Core still requires caller approval, evaluates baseline,
candidate and committed content, and records rollback metadata.

The six-case [managed Story](../features/c-parameter-reordering-managed-apply.feature)
passes actual approved apply and byte/hash-exact rollback with retained and
disabled caller gates. Missing approval, changed database, added unrelated body
edits and removed authority refuse before a transaction. Configuration bytes and
process/private-material cleanup are checked. Evidence is in
`.refactorkit/runs/v080-parameter-reorder-apply-r001/`; one initial refusal-code
expectation was corrected separately from four implementation RED cases.

A separate selected Story binds the resolved native runtime identity into the
reorder lease. Changing that identity refuses managed apply before a transaction,
even when the caller supplies a disabled diagnostics gate. The existing shared
runner reuses all four steps; there are no new step definitions or duplicate
expressions. Its receipt and reports are in
`.refactorkit/runs/c02-c10-runtime-lease-r001/`.
Added parameters, broader family/context/tool resources, post-commit fault
qualification and LSP exposure remain required for full C10.

The facade and daemon `c.preview` accept `operation: reorderParameters` with
`arguments.file`, `arguments.symbol` and `arguments.originalIndices` as a
comma-separated original-index mapping. MCP `preview_refactoring` uses
`languageId: c` and the same operation/arguments. Daemon/MCP composed scans call
`CCompilationSnapshotCapture.capture` to retain safe database bytes; recording
the compiler command never authorizes its execution. Both protocols retain the
operation gate for explicit apply and rollback.

The CLI mode is:

```text
refactorkit c change-signature <root> --operation reorder-parameters \
  --file <path.c> --symbol <function> --order 2,1,0 \
  --clang <22.1.8-executable> --clangd <22.1.8-executable> \
  --clang-format <22.1.8-executable> [--apply]
```

Omitting `--apply` returns a preview. `--order` requires the explicit reorder
mode and cannot mix with `--old-param`/`--new-param`; existing parameter rename
keeps its default mode. Malformed mappings return a stable
`c.signatureReorder.invalid_mapping` refusal in CLI stderr. Protocol and CLI
Stories cover preview, apply/rollback and refusals with real configured 22.1.8:
`.refactorkit/runs/v080-parameter-reorder-surface-r001/` and
`.refactorkit/runs/v080-parameter-reorder-cli-r001/`. CLI evidence executes the
public in-process command; source-built/native/downloading qualification is a
separate C17 obligation. These bounded integer-family cells do not advertise
added parameters, headers, external consumers or full C10 acceptance.

`CSignaturePlanner.addParameter(snapshot, file, functionName, parameterName,
ordinal, arguments)` now previews one named integer parameter plus explicit
`CExplicitCallArgument(callSpan, expression)` entries. Spans identify exactly
the captured calls. Prototype/definition and caller edits are staged together;
there is no implicit default argument. Baseline/candidate compilation delegates
scope/effect/source rules to the approved pure insertion domain. Explicit `void`
clauses are supported; binding capture, indirect consumers, observable/unavailable
arguments and escaped expression extents refuse with structured codes.

The six-case [insertion Story](../features/c-parameter-insertion-preview.feature)
uses real configured 22.1.8 in C11/C17, independent full-source and own-program
execution expectations, and an independently compiler-valid escaped-expression
control. Saved bytes and private-material/process cleanup are checked. Receipt,
inventory and RED/GREEN are in
`.refactorkit/runs/v080-parameter-insertion-preview-r001/`. Approval-required
previews carry `c.signature-add-v1` authority.

`CRefactoringFacade.diagnosticsGate(snapshot, plan)` and `apply` now enforce
that insertion authority. The retained caller JSON is bounded and typed before
native preview reconstruction; edits, expressions, candidate and compiler must
match the original lease. Disabled caller diagnostics cannot bypass this gate.
Core requires caller approval and validates baseline/staged/committed bytes.
The six-case [managed insertion Story](../features/c-parameter-insertion-managed-apply.feature)
proves actual approved apply and exact byte/hash rollback, with retained and
disabled caller gates. Missing approval/lease, expression tampering and changed
database refuse before a transaction; configuration and compiler cleanup remain
verified. Admission and reports are in
`.refactorkit/runs/v080-parameter-insertion-apply-r001/`.
The follow-up [runtime-identity Story](../features/c-parameter-insertion-managed-apply.feature)
binds the resolved native runtime identity into this lease and refuses apply
before a transaction when that retained identity changes. Its one selected case
reuses the shared four-step glue; receipt and reports are in
`.refactorkit/runs/c02-c10-insertion-runtime-lease-r001/`.
LSP insertion wiring, post-commit fault qualification and broader
type/family/context/resource coverage remain required for full C10.

The public library facade and daemon `c.preview` now accept `addParameter` with
`arguments.file`, `arguments.symbol`, `arguments.parameterName`,
`arguments.parameterOrdinal` and `arguments.callerExpression`. MCP uses
`preview_refactoring` with `languageId: c` and those arguments. The mandatory
expression is supplied by the caller and bound to every captured direct call;
its canonical per-call mapping is retained in the same insertion lease.
The library planner overload also accepts this explicit uniform expression;
the original per-call span/expression library method remains available.

The CLI delegates that request:

```text
refactorkit c change-signature <root> --operation add-parameter \
  --file <path.c> --symbol <function> --name <parameter> --position <ordinal> \
  --argument-expression <expression> --clang <22.1.8-executable> \
  --clangd <22.1.8-executable> --clang-format <22.1.8-executable> [--apply]
```

Only a named `int` parameter is admitted in this cell; `--position` is zero
based. The explicit insertion mode rejects rename/reorder options and unsupported
`--type`/`--default` options. Missing expressions and observable/unavailable
argument effects refuse. Preview leaves saved bytes intact; `--apply` retains
the native managed gate and rollback restores exact bytes. Six-case public
[surface](../features/c-parameter-insertion-managed-surfaces.feature) and
[CLI](../features/c-parameter-insertion-cli.feature) Stories use real configured
22.1.8, including C11 explicit-void protocol insertion and Unicode/CRLF CLI
preview. Receipts, installed inventories and RED/GREEN execution are in
`.refactorkit/runs/v080-parameter-insertion-managed-surfaces-r001/` and
`.refactorkit/runs/v080-parameter-insertion-cli-r001/`. CLI evidence is public
in-process execution with a rebuilt local distribution; packaged/native/
independent-download/CI qualification remains a separate C17 obligation.
This satellite owns the bounded caller contract; ARC42 owns integrated status.

`CFunctionFactsService.analyzeStatements` and `analyzeStatementsPreview` now
capture statement-selection facts through the same owned exact compiler
execution boundary as function families. The latter allows source-only
candidate content while saved snapshot/configuration remain authoritative.
Selections are half-open UTF8 byte spans. Available results carry snapshot,
source hash, native binding inputs/output, owned ranges and process provenance;
unavailable analysis remains a typed failure rather than clean empty evidence.
The bounded one-file integer decoder/refusal domain and renderer are specified
separately. Six real 22.1.8 C11/C17
[native capture cases](../features/c-statement-facts-captured-execution.feature)
cover saved/staged facts, Unicode/CRLF, changed input/configuration and increment
effects, with saved-byte and compiler-material cleanup checks. Receipt,
inventories and RED/GREEN are in `.refactorkit/runs/v080-statement-execution-r001/`.
This is read-only evidence; helper preview/apply/rollback and broad C11
qualification remain `actionable`.

`CStatementExtractionPlanner.preview(snapshot, file, functionName, selection,
helperName)` now generates an approval-required `extractStatements` preview for
the qualified integer declaration subset. Exact captured saved/staged facts,
pure minimal edits and native helper/caller post-model validation compose into
`c.statement-extract-v1` authority. It records original byte selection, chosen
names, compiler identity, before/candidate hashes and exact edit digest.
The [six-case native preview Story](../features/c-statement-helper-preview.feature)
qualifies C17 inputs and C11 zero inputs with Unicode/CRLF, independent complete
source/runtime outcomes and multiple-output/effect/collision/stale-input refusals.
Receipt, inventories and RED/GREEN are in
`.refactorkit/runs/v080-statement-preview-r001/`. This satellite owns the bounded
library contract and ARC42 integrated status. Managed apply/rollback, public
commands, broader types/control/lifetime/contexts/tool resources and full C11
acceptance remain `actionable`.

`CRefactoringFacade.diagnosticsGate(snapshot, plan)` and `apply` now retain a
mandatory statement-helper gate for `extractStatements`. The gate rebuilds
the native preview and requires exact lease, edit, compiler, helper and candidate
identity, including when a caller supplies a disabled or unrelated gate. Core
explicit approval and transaction gates remain authoritative. Baseline, staged
and committed images are compiled in the captured context; rollback restores
exact saved bytes and snapshot identity. The
[six-case managed Story](../features/c-statement-helper-managed-apply.feature)
qualifies retained/disabled caller gates, missing approval/lease, changed helper
identity and changed database, with configuration/process/material checks.
Evidence is in `.refactorkit/runs/v080-statement-apply-r001/`. This satellite owns
the bounded library contract; [ARC42](arc42/appendix-requirements.adoc) owns
integrated status. Public command dispatch, fault injection, broader type/control/
lifetime/context/resource closure and complete C11 remain `actionable`.

A separate selected Story binds the resolved native runtime identity in the
statement-helper lease and refuses a mismatched identity before transaction.
It reuses the four existing shared steps; receipt and reports are in
`.refactorkit/runs/c02-c11-statement-runtime-lease-r001/`.

The public library facade `preview(..., "extractStatements", arguments)`, daemon
`c.preview` and MCP `preview_refactoring` now share the same native helper
planner. Required arguments are `file`, `symbol`, `helperName`, `startByte` and
`endByte`; the extent is start-inclusive/end-exclusive UTF-8 bytes, expressed
as canonical nonnegative integers. A missing or invalid selection is refused.
The MCP operation schema/catalogue advertises this bounded contract. Retained
plans carry the mandatory gate into apply; no transport reimplements domain
rules. The [six-case public Story](../features/c-statement-helper-managed-surfaces.feature)
qualifies library/daemon/MCP apply and exact rollback, C11 zero-input Unicode/CRLF,
missing selection, observable initializer and changed-database refusals, plus
saved configuration and compiler cleanup. Evidence is in
`.refactorkit/runs/v080-statement-surfaces-r001/`. CLI helper dispatch, LSP,
post-commit fault injection and broader C11/C16/C17 qualification remain
`actionable`; [ARC42](arc42/appendix-requirements.adoc) owns integrated status.

CLI statement extraction is now explicit:

```sh
refactorkit c extract . --operation extract-statements --file main.c \
  --symbol calculate --helper-name compute --start-byte 112 --end-byte 159 \
  --clang /configured/clang-22 --clangd /configured/clangd \
  --clang-format /configured/clang-format --preview
```

Offsets above are illustrative: supply the exact start-inclusive/end-exclusive
UTF-8 byte extent of complete selected declarations in your saved source.
`--apply` is explicit approval of the generated retained plan. The default
`extract-expression` mode remains available; combining scalar range/temp options
with helper options is rejected. The CLI maps arguments and delegates to daemon
`c.preview` and retained managed apply. The
[six-case CLI Story](../features/c-statement-helper-cli.feature) qualifies C17/C11
Unicode/CRLF preview, explicit apply/CLI rollback, missing selection, initializer
effects and mixed options. Evidence is in
`.refactorkit/runs/v080-statement-cli-r001/`. LSP, post-commit faults, compiler-proven
scalar extraction and broader C11/C16/C17 acceptance remain `actionable`;
[ARC42](arc42/appendix-requirements.adoc) owns integrated status.

`CScalarExpressionFactsDecoder` now specifies supplied source-bound compiler
facts for scalar integer extraction. The selected expression's native `int`
type is distinct from an enclosing `double`/`const double` target; ordinary
`const int` input reads retain their value type. Six approved Kotlin
[specifications](../modules/refactorkit-c/src/test/kotlin/org/refactorkit/c/CScalarExpressionFactsSpec.kt)
qualify exact subtree/statement ownership, parameter/prior-local binding,
immutable names and input limits, and partial/return/global/volatile/effect/
conditional/goto/unavailable-identity refusals. Our AST fixture is captured from
configured 22.1.8, with opaque IDs normalized. All 243 C tests pass. The
[requirement satellite](requirements/c-scalar-expression-facts.md) owns pure
rules; [ARC42](arc42/appendix-requirements.adoc) owns integrated status. Evidence
is in `.refactorkit/runs/v080-scalar-expression-facts-r001/`. Native product
fact capture, scalar edits/post-model, preview/apply/rollback and full C11 remain
`actionable`; supplied AST fixture proof is not operation qualification.

`CFunctionFactsService.analyzeScalarExpression` and
`analyzeScalarExpressionPreview` now bind saved/staged scalar facts to the
captured compilation context, exact configured compiler and unchanged saved
authority. Source-only candidates retain paths/configuration. Native types are
separate from targets, including a staged `const int` initializer. The
[six-case native capture Story](../features/c-scalar-facts-captured-execution.feature)
qualifies C17 division, C11 Unicode/CRLF, staged const, saved/candidate database
changes and initializer increment, with source/configuration/process/material
checks. Typed unavailable diagnostics use `c.scalarFacts.*`. Evidence is in
`.refactorkit/runs/v080-scalar-execution-r001/`. This is read-only fact capture;
scalar edits/post-model, preview/apply/rollback and full C11 remain `actionable`.
This satellite owns the bounded read contract;
[ARC42](arc42/appendix-requirements.adoc) owns integrated status.

Pure scalar edit/post-model rules now insert a `const int` declaration before the
proven owning initializer and replace only the integer selection. Two immutable
edits preserve remaining bytes and Unicode/CRLF/inline placement. Newly captured
candidate facts must match exact bytes, integer/const type, copied initializer,
function extent, original input declarations and collision names. Six approved
Kotlin specifications pass, as do all 249 C tests. Two initial GREEN attempts
exposed inline fixture-oracle indentation and replacement-scope errors; the
explicit expectation is now scoped to the unique inserted declaration, without
implementation or semantic-check changes. The
[requirement satellite](requirements/c-scalar-extraction-edits.md) owns pure rules;
[ARC42](arc42/appendix-requirements.adoc) owns integrated status. Evidence is in
`.refactorkit/runs/v080-scalar-edits-r001/`. Native operation preview/apply/rollback
and full C11 remain `actionable`; fixture proof alone is not their qualification.

`CScalarExtractionPlanner.preview(snapshot, file, byteSelection, tempName,
functionName?)` now generates native approval-required `extractExpression`
previews. With no function supplied, the compiler resolves the source-owned
definition from the selection. Captured before/staged facts, minimal integer
edits and exact const/input/placement post-model compose into `c.scalar-extract-v1`
authority, retaining original/candidate selections, native function, compiler,
source/candidate/edit identities. The
[six-case preview Story](../features/c-scalar-extraction-preview.feature) qualifies
public C17 division, C11 subtree Unicode/CRLF and same-line void/const parameters
with independent full source and three before/after runtime pairs. Global name
collision, initializer increment and changed database refuse without edits.
Compiler process/material and saved-input checks pass. Evidence is in
`.refactorkit/runs/v080-scalar-preview-r001/`. The satellite owns the bounded
library planner contract; [ARC42](arc42/appendix-requirements.adoc) owns integrated
status. Managed scalar apply/rollback, facade/public routing, post-commit faults
and full C11/C16/C17 remain `actionable`.

### Managed native scalar extraction

Library apply now retains the exact `c.scalar-extract-v1` preview throughout
the core transaction. It reconstructs the native preview, compares the complete
authority lease and workspace edit identity, and admits only its saved and staged
images. Captured source/configuration and native scalar facts are checked at
baseline, stage and committed image. A disabled caller gate cannot bypass this
mandatory operation gate. Explicit core approval remains required.

The [six-case apply Story](../features/c-scalar-extraction-managed-apply.feature)
qualifies independent expected source and exact rollback with retained/disabled
caller gates, plus refusal of missing approval/lease, changed temporary identity
and changed database. Private compiler material is released and configuration
is preserved. Evidence is in `.refactorkit/runs/v080-scalar-apply-r001/`.
This satellite owns the bounded contract; [ARC42](arc42/appendix-requirements.adoc)
owns integrated acceptance. Facade preview/public routing, post-commit fault
injection and complete C11/C16/C17 remain `actionable`.

A separate selected Story binds the resolved native runtime identity in the
scalar lease and refuses a mismatched identity before transaction. It reuses
the four existing shared steps; receipt and reports are in
`.refactorkit/runs/c02-c11-scalar-runtime-lease-r001/`.

### Public native scalar extraction

`CRefactoringFacade.preview(..., "extractExpression", ...)` now delegates the
native scalar planner. It retains `file`, optional `tempName` (default `tmp`),
and zero-based UTF-16 `startLine/startChar/endLine/endChar`. The adapter maps that
range to native UTF-8 extents; reversed, missing, out-of-line and split-surrogate
ranges refuse. The compiler identifies the owning definition; an optional
`symbol` restricts it explicitly. Unavailable native authority never falls back
to the legacy structural planner.

The [six-case public Story](../features/c-scalar-extraction-managed-surfaces.feature)
qualifies library C17 division, daemon C11 Unicode/CRLF with an emoji on the
selected line and MCP void/const parameters through preview, apply and exact
rollback. Missing range, effects and changed database refuse without a
transaction. The obsolete unconfigured structural-success JUnit case is migrated
to this Story, using real configured 22.1.8 and independent expected source.
No JUnit behavior test is added. Evidence is in
`.refactorkit/runs/v080-scalar-surface-r001/`. This satellite owns the bounded
public contract; [ARC42](arc42/appendix-requirements.adoc) owns integrated status.
CLI/LSP cells, post-commit faults and full C11/C16/C17 remain `actionable`.

### Native scalar CLI qualification

Scalar extraction keeps its existing default CLI mode:

```sh
refactorkit c extract ROOT --file main.c --temp-name quotient \
  --start-line 3 --start-char 19 --end-line 3 --end-char 24 \
  --preview --clang CLANG --clangd CLANGD --clang-format CLANG_FORMAT
```

Line and column are zero-based; columns count UTF-16 code units. Explicit
`--operation extract-expression` selects the same route. `--apply` records
explicit approval; `patch rollback TRANSACTION --root ROOT` restores its source.
The [six-case CLI Story](../features/c-scalar-extraction-cli.feature) qualifies
C17 and C11 Unicode/CRLF read-only previews, independent expected apply/exact
rollback and missing-range/effect/mixed-helper refusals with exit codes 1/1/2.
Configuration and compiler cleanup pass. The first qualification execution
passes against the existing delegation; no RED is manufactured. Evidence is in
`.refactorkit/runs/v080-scalar-cli-r001/`. This satellite owns the bounded command
contract; [ARC42](arc42/appendix-requirements.adoc) owns integrated status. LSP,
post-commit faults and full C11/C16/C17 remain `actionable`.

### Native managed post-commit authority revocation

The [six-case fault Story](../features/c-native-managed-post-commit.feature)
qualifies the retained public C gate through the core transaction's existing
`AFTER_COMMITTED_IMAGE` test hook. Independently expected reorder, insertion,
helper and scalar sources are observed after atomic replacement. Changing the
captured define then revokes authority: success is refused, exact original source
is restored and the concurrent database is preserved. Journals retain explicit
approval, exact before/after images and applied/rolling-back/rolled-back history.
`diagnostics.postApplyUnavailable` and `diagnostics.rollbackUnavailable` expose
that the old configuration cannot be certified after its concurrent change.
Unchanged helper/scalar controls restore exact project hash via manual rollback.
Private compiler cleanup passes. One initial insertion-oracle mismatch is
corrected for the already specified `(3)` argument grouping; no production rule
or semantic RED is introduced.

Evidence is in `.refactorkit/runs/v080-native-post-commit-r001/`. This satellite
owns the bounded core/public-gate contract; [ARC42](arc42/appendix-requirements.adoc)
owns integrated status. Protocol fault hooks, process crashes, LSP and full
C10/C11/C16/C17 remain `actionable`.

### Pure ELF toolchain dependency declarations

The [ELF requirement satellite](requirements/c-elf-dynamic-inputs.md) defines
`REQ-C-ELF-DYNAMIC-INPUTS-001`: read bounded ELF64 little-endian x86-64 metadata
from bytes, without filesystem access or execution. Six approved Kotest
specifications use own in-memory images. Six canonical behavioral RED failures
precede six GREEN cases. Facts bind the exact byte hash, interpreter, ordered
repeated dependencies, SONAME, RPATH/RUNPATH and loader flags. Static files have
an explicit empty dynamic result. Invalid/ambiguous mappings, incomplete arrays/
strings/singletons, unsupported profiles/loader directives and independent input
limits refuse. The dependency list is immutable.

Evidence is in `.refactorkit/runs/v080-elf-dynamic-inputs-r001/`. The requirement
owns pure rules and primary ELF sources; [ARC42](arc42/appendix-requirements.adoc)
owns integrated status. Artifact capture and currency are exercised in the
following Story; actual dependency resolution, resource/sysroot manifests,
product integration and full C02 remain `actionable`.
Declaration extraction alone does not certify actual runtime closure or C02.

### Native ELF artifact input capture

The [six-case Story](../features/c-elf-artifact-input-capture.feature) defines
`REQ-C-ELF-ARTIFACT-001`: the library reads explicitly selected, bounded, direct
regular files and delegates dependency decoding to the pure ELF rules. Capture
binds role, absolute normalized path, size, SHA-256 and dependency declarations.
Its current check reads all bytes again. Parent/leaf symbolic links, concurrent
capture changes and later byte changes cannot retain that authority.

Six behavioral RED cases precede six GREEN cases using the privately configured
Clang, clangd and formatter 22.1.8 executables and LLVM library. Independent
`readelf`/`sha256sum` oracles inspect the objects without executing them. A changed
owned copy loses currency, and a script behind a symbolic link is refused without
execution. Original SDK files and project source are preserved. This is artifact
inspection evidence; the SDK is not a released package.

Evidence is in `.refactorkit/runs/v080-elf-artifact-capture-r001/`. This satellite
owns the bounded library contract; [ARC42](arc42/appendix-requirements.adoc) owns
integrated acceptance. Exact loader resolution, alias/search/environment edges,
resource/sysroot manifests, product toolchain admission and full C02 remain
`actionable`. Capture is not an execution lease or a live replacement-race
guarantee, and it does not certify runtime closure.

### Complete provenance field identity

The [identity requirement](requirements/clang-toolchain-identity.md) defines
`REQ-C-CLANG-IDENTITY-001`. The existing provenance API delegates to a pure,
versioned SHA-256 encoding of provider, all three versions, target/resource
metadata and every file's role, normalized path, size and hash. Sorting preserves
permutation stability while retaining duplicate records. Length framing and
explicit null markers prevent field-separator/placeholder collisions. Malformed
UTF-16 produces an explicit encoding error rather than silently replacing text.

Six approved Kotest specifications match an independent fixed digest and exercise
every authority field, delimiter/Unicode/null distinctions, lexical paths and
multiplicity. Six initial behavioral RED failures precede six GREEN cases. Adding
malformed-text refusal reproduces one further RED failure before the final GREEN.
Older v1 digests are intentionally invalidated; existing previews must be renewed
where that identity is retained.

Evidence is in `.refactorkit/runs/v080-clang-provenance-identity-r001/`. The
requirement owns pure encoding rules; [ARC42](arc42/appendix-requirements.adoc)
owns integrated acceptance. A complete field encoding only binds evidence that
was supplied. Actual loader/resource/sysroot capture and product admission remain
`actionable`; it does not imply complete C02 or release qualification.

### Complete resource directory facts

The [resource requirement](requirements/clang-resource-directory.md) defines
`REQ-C-CLANG-RESOURCE-FACTS-001`. Pure rules bind an absolute normalized root,
every binary file's relative path/size/SHA-256 and all directories, including
empty directories. Missing ancestors, escaping/ambiguous paths, duplicate or
colliding nodes, invalid sizes/hashes and independently bounded entry/depth/path/
file/total-byte limits refuse. Zero-byte files and an explicitly empty root are
valid facts. Immutable copies and sorted versioned framing preserve permutation
stability while retaining every relevant tree change.

Six approved Kotest specifications match independent content/tree digests.
Correcting the fixture's byte length from the independent source bytes before
implementation preserves six canonical behavioral RED failures, followed by
six GREEN cases. Evidence is in
`.refactorkit/runs/v080-clang-resource-facts-r001/`. The requirement owns pure
rules; [ARC42](arc42/appendix-requirements.adoc) owns integrated acceptance.
Native acquisition, currency and compiler-fact guard integration are exercised
in the separately bounded Stories below. Actual loader/sysroot closure and full
C02 remain `actionable`. Facts alone grant no execution or mutation authority.

### Native complete resource acquisition and currency

The [six-case resource Story](../features/c-clang-resource-directory-capture.feature)
defines `REQ-C-CLANG-RESOURCE-CAPTURE-001`. Read-only acquisition traverses only
direct regular files/directories with independent entry/depth/path/file/total-byte
budgets, checks inventories/attributes before and after reading, and delegates
complete immutable facts. Currency rescans the tree and every binary file.
Links, unsupported file kinds, unavailable reads and exceeded limits refuse.

Six behavioral RED cases precede six GREEN cases over all 413 resources
(74,906,836 bytes) in the privately configured 22.1.8 SDK. Independent
`sha256sum --zero` oracles match every file. Byte changes, a new empty file and
a removed file lose currency. An indirect script is refused without execution;
a reduced entry budget refuses. Original SDK bytes are preserved.

Evidence is in `.refactorkit/runs/v080-clang-resource-capture-r001/`. The
[requirement](requirements/clang-resource-directory.md) owns pure rules and the
bounded library contract; [ARC42](arc42/appendix-requirements.adoc) owns integrated
acceptance. Compiler-fact and retained-preview guard integration are exercised
below. Other process paths, actual loader/sysroot closure, live replacement-race
guarantees and full C02 remain `actionable`.

### Captured compiler resource context

The [compiler-resource Story](../features/c-compiler-resource-context.feature)
defines `REQ-C-COMPILER-RESOURCES-001`. Toolchain discovery retains a typed
complete resource capture result; unavailable or manually omitted resources do
not authorize C function/scalar/helper facts. The current compiler check now
validates the retained tree, its declared root and executable bytes. Checks run
before configuration capture, immediately before execution and after analysis.
The native AST bridge pins `-resource-dir` to that retained root.

Six behavioral RED cases precede six GREEN cases with the real 22.1.8 compiler
and owned copies of all 413 resources. The successful case independently checks
the exact argument digest, including the resource pin, and actual function facts.
Header-byte changes, an empty added file, a removed header, an inserted link and
missing capture refuse. Project/SDK bytes and process ownership are preserved.
An initial fixture API-name compilation error is corrected; it is not behavioral
RED. Evidence is in `.refactorkit/runs/v080-compiler-resource-context-r001/`.

This satellite owns the bounded compiler-fact contract;
[ARC42](arc42/appendix-requirements.adoc) owns integrated acceptance. Discovery
availability still describes executable/version metadata; its typed resource
result must be consulted before compiler fact authority. Retained preview lease
binding is exercised below; other compiler/server/formatter paths, actual
loader/sysroot closure and full C02 remain `actionable`.

### Resource-bound preview and post-commit recovery

The [preview Story](../features/c-resource-bound-preview-authority.feature)
defines `REQ-C-RESOURCE-PREVIEW-001`. Native scalar/helper/reorder/insertion
producers retain `c.resourceDirectoryIdentity` in their approval leases. Managed
gates rebuild and compare the complete lease. A newly captured changed resource
tree cannot renew an old preview. Four replay cases refuse before journaling,
even with a caller-disabled gate; two unchanged controls apply exact expected
source and restore bytes/project hash through explicit approval and rollback.
Four actual wrongly applied transactions precede the correction and six GREEN
cases. Evidence is in `.refactorkit/runs/v080-resource-preview-authority-r001/`.

The [post-commit Story](../features/c-native-resource-post-commit.feature)
defines `REQ-C-RESOURCE-POST-COMMIT-001`. Four resource changes injected after
the committed source image cause automatic source rollback and refusal with
`diagnostics.postApplyUnavailable` and `diagnostics.rollbackUnavailable`.
Concurrent resource bytes remain changed. The journal retains exact source
images, explicit approval and the completed rollback states. Two unchanged
controls complete apply and manual rollback. Project/database and original SDK
inputs are preserved; compiler processes and private material are released.

The six cases reuse four existing scenario-scoped steps and the shared runner.
The initial fixture preparation wrongly put copied resources inside the source
snapshot; its six failures are preserved and are not semantic RED. Corrected
fixtures use separately owned temporary resource directories. The first valid
native run passes all six cases without a production change. Evidence is in
`.refactorkit/runs/v080-native-resource-post-commit-r001/`. These bounded Stories
own their contracts; [ARC42](arc42/appendix-requirements.adoc) owns integrated
acceptance. The separately bounded syntax resource check follows below. Other
operation/process paths, actual loader/sysroot closure, live replacement races
and full C02 remain `actionable`.

### Retained syntax diagnostic resource context

The [syntax-resource Story](../features/c-syntax-resource-context.feature)
defines `REQ-C-SYNTAX-RESOURCES-001`. `CCompilerDiagnostics` requires a retained
complete resource snapshot and checks its declared root and entire tree before
overlay materialization, immediately before native launch and after execution.
Syntax arguments pin `-resource-dir` after supplied flags. Explicit directory
override options refuse with `clang.compilerDiagnosticsResourceOverride`;
missing, unavailable or changed captures refuse with
`clang.compilerResourcesUnavailable`. Unavailable resources never yield clean
diagnostics.

Six behavioral RED failures reproduce one missing retained-root argument digest
and five incorrectly available results. Six GREEN cases use actual configured
22.1.8 and owned complete resource copies: a valid `size_t`/C17 syntax check with
an independent exact-argument digest, header-byte/addition/deletion changes,
missing capture and caller override. The fixture reuses two existing steps and
adds two expressions in the same scenario-scoped glue; the runner is shared.
Project/database, original SDK and process ownership remain intact. Evidence is
in `.refactorkit/runs/v080-syntax-resource-context-r001/`.

The feature owns this bounded contract; [ARC42](arc42/appendix-requirements.adoc)
owns integrated acceptance. Complete TU/configuration/source/header authority,
formatter and remaining native inputs, actual loader/sysroot closure, live
replacement races and full C02/C05 remain `actionable`. Server resource evidence
is separately bounded below. The available result
in this Story qualifies the stated resource context, not universal diagnostics
completeness or release acceptance.

### Retained clangd resource context and startup admission

The [warm resource Story](../features/c-clangd-resource-context.feature) defines
`REQ-C-CLANGD-RESOURCES-001`. clangd pins `--resource-dir` to the retained complete
snapshot and checks that tree around didOpen/definition/references/rename. Six
native 22.1.8 cases prove the exact declaration of a resource-only authored
header, exact declaration/use references, and revocation after header-byte,
addition, deletion or link faults. The session closes with
`c.clangdResourcesChanged`; failed references remain unavailable and document
opening returns false. Owned PID and private configuration are removed.

The [startup resource Story](../features/c-clangd-resource-admission.feature)
defines `REQ-C-CLANGD-RESOURCE-ADMISSION-001`. One actual native positive case
admits current captured resources and resolves the same resource-only header.
Five negative cases inject an explicit fail-on-launch port witness: missing or
unavailable capture, a different declared root, changed captured bytes or a
removed root must throw `c.clangdResourcesUnavailable` before executing it or
creating private launch material. They prove the preflight guard, not native
negative qualification or wrapper admission. Project/database, original SDK,
recorded resource faults and private-directory inventory remain intact. The first
valid six-case run passes the existing guard without a production change or a
claimed semantic RED. The shared runner and scenario-scoped glue are reused.

Evidence is in `.refactorkit/runs/v080-clangd-resource-context-r001/` and
`.refactorkit/runs/v080-clangd-resource-admission-r001/`. The features own these
bounded contracts; [ARC42](arc42/appendix-requirements.adoc) owns integrated
acceptance. Earlier generic server-resource documentation is reconciled here.
Faults during startup/exchange, other native/formatter inputs, actual
loader/library/sysroot closure, cancellation/restart/descendant cells, complete
source/header/configuration authority and full C02 remain `actionable`.
Every C01-C17 acceptance remains required.

### Acquired formatting source context

The [formatting-source Story](../features/c-format-source-context.feature) owns
`REQ-C-FORMAT-SOURCE-001`. A formatter preview selects exactly one acquired source
by normalized workspace path and cannot silently read a file omitted from the
snapshot. Missing/ambiguous acquisition or an escaping path reports
`clang.formatSourceMissing`. Before and after execution, a bounded read-only
source boundary checks direct file/parent paths, exact acquired UTF-8 bytes and
stable identities. Missing, indirect, unavailable or changed sources report
`clang.formatSourceChanged`. The original source is checked around the idempotence
pass too. These checks do not lock the filesystem or prevent live replacement
races.

Two actual configured clang-format 22.1.8 controls prove the independently
expected whole-file edit for relative/absolute acquired records under an LLVM
project style. Four explicit port witnesses prove changed bytes, a removed file,
a source link or omitted acquisition refuse before execution; they are preflight
evidence, not native negative qualification or wrapper admission. Workspace bytes
and links remain intact, process ownership is reaped and no journal is created.
Four genuine behavioral RED failures precede six GREEN cases. An earlier fixture
API-name compilation failure remains separately recorded.

The [source requirement](requirements/c-formatting-source.md) records the bounded
contract and limits; [ARC42](arc42/appendix-requirements.adoc) owns integrated
acceptance. Evidence is in `.refactorkit/runs/v080-format-source-context-r001/`.
Faults during execution, complete style/tool/runtime identities, range boundaries,
unchanged regions, staged diagnostics and managed approval/apply/rollback
authority remain `actionable`. Full C07 and every C01-C17 acceptance remain
required. This reconciles the separately reported source-behavior/error gap;
it does not qualify general project-style capture or a release.

The include-aware CLI/daemon/MCP lifecycle now also passes six native cases/24
steps on main, including exact Apply/rollback and database-drift/missing-flag
refusals. This is in-process command entry/session dispatch; include-aware LSP,
framed stdio and downloaded-package execution remain unqualified. Evidence:
`.refactorkit/runs/c16-cmake-include-main-r001/`. Authority and scope:
[ARC42](arc42/appendix-requirements.adoc#c16-cmake-include-managed-transports-2026-11-13).

Structural `CProjectScanner.scan` now throws `CProjectScanException` with an
explicit `CProjectScanFailure` when owned C-family source capture is unavailable;
it never returns a partial source snapshot. Descriptor-relative reads require
SecureDirectoryStream support and stable file identities, reject non-ignored
symlinks, and bound entries/depth/source count and source bytes. Defaults are
25,000 entries, depth 64, 4,096 sources, 2 MiB/file and 64 MiB total; caller limits
may only tighten these. Source UTF-8 is strict. Shared ignored directories are
excluded. Twelve native cases/48 steps cover the source contract. Auxiliary
manifest/database/style capture and protocol envelopes retain separate policies
and are not newly qualified by these tests. Authority:
[ARC42 source capture](arc42/appendix-requirements.adoc#c03-owned-bounded-source-capture-2026-11-13)
and [boundaries](arc42/appendix-requirements.adoc#c03-source-capture-boundaries-2026-11-13).

Managed LSP maps unavailable C source capture to PLAN_REFUSED, with
`data.refusalCode=c.sourceCapture.unavailable` and the exact enum in
`data.sourceCaptureFailure`. Three existing prefix lifecycle cases pass;
full LSP rerun remains separate. Authority:
[ARC42](arc42/appendix-requirements.adoc#c03-lsp-source-refusal-compat-2026-11-13).

The include-aware managed LSP slice now passes six native cases/24 steps on
main. The separate full LSP module passes 78 tests with ten skips and all 42 C
cases/168 steps passed, including the C03 source-refusal mapping. Discovery
confirms 105 features/711 registered cases rather than full-case execution.
This is in-process managed command acceptance; framed stdio, downloaded runtime,
generic C queries and broader C02-C17 are separate. Evidence:
`.refactorkit/runs/c16-include-lsp-main-r001/`. Authority:
[ARC42](arc42/appendix-requirements.adoc#c16-cmake-include-main-qualification-2026-11-13).

## Compiler declaration search through daemon and MCP

`symbol.search` with `languageId=c` and MCP `symbol_search` now share a bounded
native declaration search. Provide `expectedSnapshotHash` from project open/scan
and the explicit `clang`, `clangd`, `clangFormat` paths. MCP returns the declaration
JSON array in its text content. The separate navigation checkpoint below adds
generic C definition/reference calls through the same compiler acquisition.
The existing standalone C11/C17 context/source policy applies.

Results retain ten C declaration kinds, four lookup spaces, linkage and each TU
observation, together with exact ranges, source/configuration identities and
result totals/truncation. They are compiler-backed declarations, not merged
semantic families or refactoring authority. Native Story6/24 and complete
modules daemon64/MCP32/C455 pass without skips or failures. Discovery106/717 is
registration coverage, not full-case execution. Examples and parameter details:
[daemon protocol](daemon-protocol.md#c-compiler-declaration-search).
Authority: [ARC42](arc42/appendix-requirements.adoc#c04-compiler-search-main-qualification-2026-11-13).


## Compiler definitions and references through daemon and MCP

`symbol.definition`/`symbol.references` and MCP `symbol_definition`/
`symbol_references` now accept `languageId=c`, the opaque c-decl ID from search,
the same expected snapshot and configured Clang toolchain. Native binding facts
preserve C lookup spaces and physical sites with their actual TU observations;
they do not use a proposed rename to discover references. Definitions return
the actual target plus requested ID. Unknown IDs and unsupported/context-changed
families fail explicitly, while a known unused binding can have zero uses.

On main, navigation 6/24 and search regression 6/24 pass, together with full
daemon 64, MCP 32 and C 478 (zero skips/failures/errors). One canary verifies
106 features/723 registered cases, not full execution. The separate pure-domain
run passes 38 Specs and C 478, including native extern linkage and union member
facts; this is not union rename qualification. Source identities and same-agent
archive checks pass. No new dependency or per-slice runner is added.

These are bounded compiler-backed routes. Source-local tag/typedef and complex
families, CLI/LSP generic queries, generic inventory ownership/resources, other
build configurations, framed/packaged execution and full C02-C17 remain open.
Evidence: `.refactorkit/runs/c04-navigation-surfaces-main-r001/`.
Usage: [daemon protocol](daemon-protocol.md#c-compiler-definitions-and-references).
Authority: [ARC42](arc42/appendix-requirements.adoc#c04-navigation-main-qualification-2026-11-13).


## Shared owned source inventory and startup refusals

The C structural reader is now a compatibility delegate over core
`OwnedSourceInventoryReader`. `GenericProjectScanner` uses that same reader,
including the C/header inventory used by daemon/MCP startup. C keeps
`CProjectScanException`/`CProjectScanFailure`; generic scans use
`OwnedSourceCaptureException` with an explicit cause. Old generic JVM
constructors and `scan(Path)` remain available. Missing generic roots still
produce empty inventories within the root bound after validation of their
existing ancestors.

Two new native Stories pass 12 cases/48 steps, covering direct generic refusal
and mixed-source/typed-refusal transport behavior. On final source, original C
capture and boundary Stories also pass 6/24 each; C navigation/search pass 6/24
each; complete modules daemon 64/MCP 32/C 478/tree-sitter 98/core 125 pass without
skips/failures/errors. One discovery canary checks 106 features/735 registered
cases rather than executing them all. Same-agent source and archive checks pass.

This closes the missing generic owned reader and startup error projection.
Managed startup now completes this inventory before the authoritative Java
scan. Native protected Java/Kotlin-link cases distinguish the old read-denial
failure from the correct SYMBOLIC_LINK refusal. Root ancestry is bounded to 256
normalized absolute components; exact 256 succeeds and 257 refuses, while the
workspace-relative depth remains 64. The five prior generic traversal controls
also pass. This supersedes those two earlier gaps only. Concurrent replacement
between stages, auxiliary capture, full configuration/provider matrices and
C03/C17 remain actionable. Authority:
[later checkpoint](arc42/appendix-requirements.adoc#c03-inventory-order-main-qualification-2026-11-13).
The packaged IDE runtime is unchanged. Caller details:
[daemon protocol](daemon-protocol.md#owned-source-capture-during-project-startup).
Authority: [ARC42](arc42/appendix-requirements.adoc#c03-owned-generic-main-qualification-2026-11-13).
Evidence: `.refactorkit/runs/c03-owned-generic-main-r001/`.


## JVM source text after the generic inventory

The Java base scanner used by mixed managed workspaces now reads modeled JVM
source roots through `OwnedSourceInventoryReader.captureSourceRoots`. It keeps
generated roots and source-set metadata while rejecting file/directory links,
outside roots and source bounds with no partial result. Byte/source/entry limits
are shared across the selected roots; normalized overlaps are read once and
missing declared directories remain optional. Native evidence covers protected
Java/Kotlin file replacement, directory replacement and growth after inventory,
with stable/generated-source controls, plus six direct selected-root boundaries.

Thirteen final-source gates pass, including Java 313/five skips and complete
daemon 64/MCP 32/C 478/tree-sitter 98/core 125 without module skips or errors.
Discovery 106/759 is registration coverage, not full execution. Earlier failed
Java fixture setup is retained and corrected without changing assertions.
Model/auxiliary IO and full C03/C17 remain separate. No runtime is promoted.
Authority: [ARC42 checkpoint](arc42/appendix-requirements.adoc#c03-jvm-source-main-qualification-2026-11-13).
Evidence: `.refactorkit/runs/c03-jvm-source-main-r002/`.

### Owned compilation and CMake input bytes — 2026-11-13

Compilation database discovery and snapshot capture now use owned directory
handles and strict UTF-8, preserving the 8 MiB bound and root/build locations.
The root CMake manifest uses the same primitive at 512 KiB; a link in any root
ancestor prevents capture. Exact-bound files remain accepted, while linked,
oversized or malformed inputs cannot become newly captured authority. Ordinary
source byte limits are unchanged. No CMake or database command is executed.

Two new Stories pass 12/48; thirteen main gates pass, including C 478 and Java
313/five skips. Discovery 106/771 checks registration only. Clangd launch input
IO, parser-only UTF-8, broader metadata capture and complete C03/C17 remain open.
Authority: [ARC42 checkpoint](arc42/appendix-requirements.adoc#c03-owned-aux-main-qualification-2026-11-13).
Evidence: `.refactorkit/runs/c03-owned-aux-main-r001/`.

### Owned clangd database inputs and currency — 2026-11-13

Clangd acquisition and currency now use the same owned bounded UTF-8 reader as
the C provider/snapshot. Both configured database paths must establish their
presence or absence through owned directory handles. A symbolic alternate parent
cannot stand for an absent database; a changed root ancestor also invalidates
a running session. Unavailable analysis produces a refusal and process closure.
Native C11 still resolves a definition with an exact 8 MiB database; the original
C17 launch controls remain passing. The private normalized database and explicit
configured compiler remain the only admitted launch material.

The new Story passes 6/24 after RED 5/one control. Seven main gates pass, including
C 478, original launch, daemon/MCP queries and a selected managed LSP apply/rollback
lifecycle. Discovery 106/777 is registration coverage. One cached CLI attempt is
retained separately; no fresh report was claimed from it. The C 478 archive was
fresh and reused only after proving the global source identical.
Authority: [ARC42 checkpoint](arc42/appendix-requirements.adoc#c03-clangd-owned-main-qualification-2026-11-13).
Evidence: `.refactorkit/runs/c03-clangd-owned-main-r002/`.
Raw-parser UTF-8 was still open at this checkpoint; the later record-format
section qualifies encoding, field types and path values. Broader capture and full
C03/C04/C06/C17 remain open. This change is not in the sealed M2 diagnostic ZIP.

### Compilation record format and exact path values — 2026-11-13

Direct library parsing now applies strict UTF-8 and JSON field types. Invalid
argument elements are refused rather than converted or dropped; a present invalid
arguments field cannot silently fall back to command. file/directory/command/output
must be strings when present. Invalid second records refuse the entire discovery.

A filename's leading space and an include directory's trailing space are retained.
NUL-bearing file/directory/include/sysroot values return the existing typed
c.compilationDatabaseInvalid refusal rather than an unchecked path exception.
Valid Unicode values remain exact; no recorded compiler or build command executes.
These are metadata parsing guarantees, not a new compiler/configuration admission.

Three new Stories pass 18/72. Eight final-source gates pass, including C 478 with
no module skips/errors, original model projection and owned provider/clangd
regressions. Discovery 106/795 covers registration; only its one canary executes.
Authority: [ARC42 checkpoint](arc42/appendix-requirements.adoc#c03-compilation-record-main-qualification-2026-11-13).
Evidence: `.refactorkit/runs/c03-compilation-record-main-r001/`.
Full per-TU context/provider matrices, broader concurrency/resource qualification,
source-local C families and original C02-C17/release work remain open. The sealed
private M2 runtime remains unchanged and predates this implementation.

### Compiler option forms and include boundaries — 2026-11-13

Separate `-D NAME=value` and joined `-iquotePATH`/`-isystemPATH` now retain
macro/include metadata and reach the owned native clangd context. Include and
output operands resembling macro flags do not manufacture defines. C11/C17
queries resolve the header declaration selected by the actual macro. External or
symbolic include paths refuse before startup; missing operands, `-I-` and the
unsupported longer `-isystem-after` spelling refuse without repairing the
configuration. Literal include names beginning with `=` or `-` remain supported
in the measured forms. Raw captured arguments are preserved.

The three new Stories pass 18 cases/72 steps after semantic RED. Twelve
final-source gates pass, including complete C 478/no skips, earlier raw records,
original model/launch and daemon/MCP queries. One discovery canary checks 106
features/813 registered cases; it does not execute all of them. Source/archive
checks pass. This is direct reader/header-context evidence, not qualification
of refactorings on arbitrary conditional preprocessing. Scalar operand-role
projection, native target/sysroot, complete C03 and the remaining release gates
stay actionable. Authority:
[ARC42 checkpoint](arc42/appendix-requirements.adoc#c03-compilation-flag-main-qualification-2026-11-13).
Evidence: `.refactorkit/runs/c03-compilation-flag-main-r001/`.

### Scalar compiler facts follow operand roles — 2026-11-13

Include directory names such as `--target=ghost` and `--sysroot=ghost` no longer
create target/sysroot facts. An output filename such as `-std=c11` no longer
changes the actual dialect or causes a duplicate-dialect startup refusal.
The model derives scalar values from actual options in the same traversal used
for defines and include paths; clangd counts actual C11/C17 options while
consuming operands. Captured raw arguments remain unchanged.

The six C11/C17 cases move from semantic RED6 to GREEN6/24 and resolve the actual
header declaration. Thirteen final-source regression gates pass, including
complete C 478/no skips, earlier flag/raw-record Stories, model/launch and
managed query checks. One canary checks 106 features/819 registered cases;
it does not execute every registered case. Source/XML/SHA audit passes.
Repeated scalar conflicts, ordered macro matrices, explicit native target/sysroot
and full C03/release qualification remain actionable. Authority:
[ARC42 checkpoint](arc42/appendix-requirements.adoc#c03-compilation-scalar-main-qualification-2026-11-13).
Evidence: `.refactorkit/runs/c03-compilation-scalar-main-r001/`.

### Compiler-backed C queries from the CLI — 2026-11-13

Use `refactorkit c symbols ROOT` (or `c search`) with `--query TEXT` to obtain
opaque `c-decl:` symbol IDs. Pass an ID to `c definition ROOT --symbol ID` or
`c references ROOT --symbol ID`. All three require `--clang EXE --clangd EXE
--clang-format EXE` and return the existing structured JSON, including native
source locations, compiler provenance and snapshot identity. Search/references
accept `--limit N` (1..200). References optionally includes declarations with
`--include-declaration`. Supply `--expected-snapshot-hash SHA256` to enforce an
earlier capture; otherwise the current opened snapshot is used. Queries reject
`--apply`.

Six C11/C17 navigation cases pass 24 steps after RED6 for functions, typedefs and
fields across source/header consumers. Another 6/24 verifies ten-kind search,
missing-TU/macro refusals and stale-hash/database refusal. A database changed
before a new CLI invocation is snapshotStale: every invocation opens its own
project. Unavailable analysis is never an empty successful result.
Seven final-source regression gates pass, including existing daemon/MCP queries,
C reorder CLI and 13 legacy CLI tests; discovery checks106 features/831 registered
cases, not full execution. These are in-process CLI commands with native Clang;
independently downloaded/runtime execution and LSP query integration remain open.
Authority: [ARC42 checkpoint](arc42/appendix-requirements.adoc#c04-cli-query-main-qualification-2026-11-13).
Evidence: `.refactorkit/runs/c04-cli-query-main-r001/`.

### Standard LSP C queries — 2026-11-13

Configure the LSP initialize request with `initializationOptions.c` containing
explicit `clang`, `clangd` and `clangFormat` executable paths. C document symbols,
definition and references then use native C analysis; configured workspaces also
advertise C `workspace/symbol` queries. Source/header locations follow the standard
LSP URI/range shapes. The read application verifies a clangd cursor anchor against
the current compiler declaration index and uses the same complete native family
projection as CLI/daemon/MCP queries.

The admitted profile reads saved C sources. Open C documents must match their
saved captured contents: unsaved documents produce `c.editor.unsavedUnsupported`.
Missing toolchain, incomplete TU models, unsupported macros and invalid positions
remain explicit errors; an actual non-symbol position returns no definition and
empty references. Editor-applied C rename and managed mutation ownership remain
unchanged. Complete document outlines/references beyond the result bound refuse.

Function, typedef and field navigation in C11/C17 passes 6/24 after RED6; another
six cases/24 steps covers refusal versus native absence. Seven final-source gates
pass, including C 478 and full LSP 90 (ten existing non-C skips, all 54 C cases/216
steps green), prior CLI and daemon/MCP reads. Discovery checks 107 features/843
registered cases, not full execution. This is standard in-process LSP/native
execution. Unsaved overlays, broader cursor families, framing/runtime/downloaded
asset and final release qualification remain open. Authority:
[ARC42 checkpoint](arc42/appendix-requirements.adoc#c04-lsp-query-main-qualification-2026-11-13).
Evidence: `.refactorkit/runs/c04-lsp-query-main-r001/`.

### Source-declared C types in query results — 2026-11-13

Source struct/union/enum tags and typedefs can now be navigated through the
existing CLI/daemon/MCP opaque-ID queries and configured standard LSP queries.
The measured cells are struct C11/C17, union C11, enum C17 and typedef C11/C17.
Selection preserves physical source and TU identity; an unrelated same-named
type in another TU is not a selected declaration or reference. LSP cursor reads
verify a native clangd anchor against the current compiler family projection.
These read capabilities do not enable source-type rename implicitly.

Six public and six standard-LSP cases pass 48 steps with current native analysis.
Seven final-source gates pass 36 cases/144 steps plus a discovery canary checking
107 features/855 registered cases; it does not execute all of them. Source/XML/SHA
checks pass. C 484 evidence is retained with unchanged module/dependency inputs;
the older full LSP 90 run is a separate historical checkpoint. The other union/enum
standard cells, source block/forward/member and rename profiles, and full
C02-C17/release qualification remain actionable. Authority:
[ARC42 checkpoint](arc42/appendix-requirements.adoc#c04-source-type-main-qualification-2026-11-13).
Evidence: `.refactorkit/runs/c04-source-type-main-r001/`.

### Source member navigation qualification — 2026-11-13

Compiler-backed source struct/union fields now have bounded public and standard
LSP query evidence: source `member`/`amount` in C11/C17, plus distinct same-spelled
`other` members in two records in the second TU. Definitions and the two exact
uses (designator and member access) retain the selected record/TU identity. LSP
also checks `includeDeclaration` both ways. The native fixture never substitutes
retained ASTs for current compiler/clangd responses. Rename admission is separate.

The final source checkpoint passes eight behavioral gates (48 cases/192 steps)
and a canary for 107 features/867 registered C cases, not full inventory execution.
Full C490 passes without skips; its inputs remain unchanged by later test/docs
edits. Earlier full LSP90 remains historical. Source reads do not qualify all
C04/C06 boundaries or close the original 0.8.0 catalogue. Source/build remains
0.7.0/API 0.2 and the sealed IDE M2 is unchanged. Evidence:
`.refactorkit/runs/c04-source-member-main-r001/`.
Authority: [ARC42 source-member queries](arc42/appendix-requirements.adoc#c04-source-member-main-qualification-2026-11-13).
