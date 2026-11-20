# Java JDT change-signature requirement

Status: active J1 expansion. Exact rename/add have packaged CLI/daemon/MCP
preview/apply/rollback acceptance on all four native platforms.
`removeParameter` is now `STABLE`/`MANAGED_STABLE` for the bounded JDT-proven
private-method shape accepted by its operation-specific daemon/MCP Story:
unused-body parameter, exact in-workspace calls, pure removed argument, staged
JDT identity/diagnostics, Apply and exact rollback. Calls whose removed argument
may have observable effects refuse before transaction creation. Packaged
four-platform and native IDE qualification remain open.
The bounded reorder managed Story currently has source-built daemon/MCP
acceptance; packaged four-platform qualification remains open.
The bounded JDT parameter-type-change row has existing packaged CLI/daemon/MCP
preview acceptance on all four native platforms. A separate current-source
managed Story now qualifies a private method, an explicitly risk-accepted
override family, and a private constructor through daemon/MCP Apply and exact
rollback; packaged qualification of this promoted managed authority remains
open. Bounded whole-source override/implementer add/remove-parameter families have
library and packaged CLI/daemon/MCP acceptance on all four native platforms.

Integrated architecture status is owned by the
[ARC42 requirements appendix](../arc42/appendix-requirements.adoc). This
requirement defines the Java engine boundary; its managed-operation Stories do
not qualify standard or custom LSP command authority.

## Purpose

Java change signature must derive declaration-family, parameter and usage edits
from Eclipse JDT binding evidence rather than lexical name matching. Every
promoted row remains previewable, staged, diagnostics-gated and rollbackable.

## First bounded row: rename one method parameter

As of 2026-10-22, the canonical RefactorKit engine operation
`changeSignature.renameParameter` is `STABLE`/`MANAGED_STABLE` through daemon and
MCP. Its operation-specific Story verifies capability discovery, preview, managed
Apply, diagnostics and exact rollback against a JDT-bound Maven fixture. This
qualification covers `changeSignature.renameParameter` only; no sibling
operation inherits its authority. `changeSignature.addParameter` has its own
separate Story below. `changeSignature.reorderParameters` now has its own
bounded daemon/MCP Story: exact JDT-bound methods and call sites are managed only
when each argument is a literal or an exact JDT-bound local/parameter read;
effectful or otherwise unproven expressions and method references refuse before
workspace mutation. Argument-list comments also refuse rather than being dropped.
Literal safety comes from detached per-argument JDT AST facts, not lexical
recognition. The hierarchy acceptance separately exercises a string literal and
a boolean literal through daemon/MCP preview, Apply, staged diagnostics, and
exact rollback. This does not broaden the rule to arbitrary constant expressions.
Hierarchy edits still require explicit external-consumer risk acceptance and the
same call-site guard. `changeParameterType` has a separate managed Story for its
bounded method, override-family, and private-constructor rows below;
`removeParameter` has an independent managed Story for its bounded private
method shape. Neither JDT LS nor RefactorKit's standard
LSP `textDocument/rename` evidence qualifies this engine operation. The custom
RefactorKit LSP signature command has its own client-managed status.

## Bounded row: remove one unused parameter

`changeSignature.removeParameter` is `STABLE`/`MANAGED_STABLE` with compiler
evidence only for the accepted bounded shape. The selected non-public method
must resolve to one exact JDT declaration; the removed parameter must have no
JDT-bound body uses; each affected in-workspace invocation must have complete
JDT binding and the expected argument count; and staged analysis must restore
the same method and call identities without new errors. The removed call
argument is discarded only when it is a literal or an exact JDT-bound local or
parameter read. Calls, field/array reads, unproven expressions, call-list
comments, method references, public/protected boundaries and incomplete
hierarchies refuse before managed mutation. Hierarchy removal continues to
require explicit external-consumer-risk acceptance and applies the same purity
guard to every affected call.

The Story `REQ-JAVA-CHANGE-SIGNATURE-REMOVE-001#an-unused-jdt-bound-parameter-is-removed-through-managed-java-surfaces`
checks capability truth across adapter descriptors, daemon discovery, the SDK
catalogue and API 0.2; daemon/MCP preview, diagnostics, Apply and exact rollback;
and refusal of a removed argument with observable effects before any
transaction. It proves this engine operation only and does not promote the
custom LSP signature command or standard `textDocument/rename`. Its approved
scope and ten-file BDD budget are recorded in the
[approved-change receipt](java-remove-parameter-managed-approved-change-001.md).

The operation accepts a saved Java method only when:

1. one authoritative non-generated source file owns the declaration;
2. JDT publishes complete binding evidence for the selected method despite any
   unrelated baseline diagnostics;
3. an unsigned selector resolves exactly one method, or an exact signed selector
   resolves one overload;
4. JDT publishes one parameter variable binding and exact declaration/use ranges;
5. every range maps one-to-one to the old identifier token;
6. staged JDT analysis re-establishes the same method signature, parameter index,
   new parameter name and usage count without introduced errors. Unchanged
   baseline errors remain tolerated by the normal diagnostics policy.

The edit changes only the bound parameter declaration and references. Same-name
fields, locals, parameters in other overloads, comments and literals remain
unchanged. Constructor parameters remain outside this row.

## First bounded managed add-parameter row

As of 2026-10-22, the canonical `changeSignature.addParameter` operation is
`STABLE`/`MANAGED_STABLE` through daemon and MCP. Its Story covers a private
JDT-bound method with one exact in-workspace invocation: preview appends the
requested parameter and validated default expression to the declaration and
bound call, staged JDT diagnostics pass, Apply creates the normal transaction,
and rollback restores the exact original source bytes. The Story does not
qualify standard LSP rename or RefactorKit's custom LSP signature command.

Managed authority remains guarded by the planner's exact JDT declaration,
parameter-list and invocation bindings, staged method/call identity checks, and
diagnostic gate. Unsupported or incomplete evidence continues to refuse before
edits. This Story records only the bounded method/call shape above; broader
hierarchy, constructor and functional-reference shapes retain their explicit
planner gates and are not newly claimed by this acceptance.

## Safety and transaction contract

Preview is read-only and binds the project snapshot. Managed apply uses the
existing authorization, writer lock, `PatchEngine`, WAL, recovery and rollback
path. Reflection, serialization, dependency injection and annotation processors
remain explicit parameter-name risks.

Stable refusals cover malformed/ambiguous selectors, unresolved overloads,
missing/ambiguous parameters, generated declarations, incomplete bindings,
introduced staged JDT errors, range mismatches, duplicate evidence and staged identity/usage drift.

## Bounded structural rows

Add, remove and reorder accept package/private non-hierarchy methods only. They
require exact JDT method, parameter and invocation argument-list evidence; reject
method references, strings, comments in bounded parameter lists, vararg-invalid
shapes and public/protected/interface/override boundaries; and re-establish the
changed method plus every call site in a disposable staged source overlay.
Remove additionally requires zero bound body uses. Caller-provided add defaults
must compile at every bound call site.

## Bounded parameter type change

Type change accepts one regular non-vararg, non-array, unannotated parameter in a
package/private method. JDT must publish the exact type token, parameter binding
and every invocation. The edit changes only that type token; call arguments
remain byte-identical. Disposable staged JDT analysis must resolve all original
invocation locations to the changed method, preserve parameter body uses and
introduce no diagnostics. A changed overload selection, incompatible body/caller,
duplicate signature, annotation/vararg/array or incomplete evidence refuses.
The `REQ-JAVA-CHANGE-SIGNATURE-TYPE-001#a-jdt-bound-parameter-type-changes-through-managed-daemon-and-mcp-surfaces`
Story exercises a private method, a saved-cursor private `int` parameter changed
to `Integer`, a complete source override family (only with
`acceptExternalConsumerRisk=true`), and a private constructor through both
managed surfaces. The cursor case binds its selection and context to preview,
requires Apply to echo both attestations with the new snapshot and transaction
identity, and checks exact rollback. All four outline cases pass in the
source-built daemon/MCP acceptance. This does not qualify packaged IDE or
four-platform managed execution. The current feature SHA-256 is
`7db8c2541d942000c843710bc907e858fab6a5384bd0de320457a424b3a4ff49`; its fresh
receipt, pre/post effective-glue inventories and selected reports are under
`.refactorkit/runs/java-type-change-selection-r001/`, superseding the earlier
three-example receipt for the current feature identity.

## Bounded override/implementer family

`addParameter` may set `includeHierarchy=true` only with
`acceptExternalConsumerRisk=true`. JDT must connect the selected declaration to
every source override/implementer transitively, and every family binding key must
have one editable non-generated source declaration. The operation appends the
same parameter to all family declarations and the caller-supplied default to all
exact calls bound to any family member. Method references, external hierarchy
declarations, incomplete call evidence, comments/varargs, duplicate names,
introduced diagnostics, lost override edges or changed call-location bindings
refuse. Public/external consumers remain a high-risk explicit warning; acceptance
does not permit editing unknown code. Remove-parameter uses the selected
parameter index across declarations whose names may differ, requires zero bound
body uses for every removed family parameter, and applies the same index removal
to all bound calls. Reorder maps the selected declaration's requested names to an
index permutation, then applies that permutation to every declaration (whose
names may differ) and all family-bound calls. Type change replaces the exact JDT
type range at the selected index in every declaration and requires staged family
connectivity, unchanged body-use counts, and preserved call-site identities.

## Bounded private-constructor row

Private/package constructors use exact `<init>(qualified.types)` JDT identities.
Class creation, `this(...)`, and `super(...)` arguments are published as bound
constructor invocations. Rename, add, remove, reorder, and parameter-type change
therefore use the same staged call-identity and diagnostics gates as methods.
Constructor references (`Type::new`) refuse until functional-interface migration
is independently implemented. Public constructors require explicit
`acceptExternalConsumerRisk=true`; acceptance never authorizes external edits.
Record canonical/compact mutation remains fail-closed because record-component
invariants require a separate coordinated operation.

## Bounded generic, varargs, and annotation rows

Private generic methods support rename/remove/reorder when exact JDT method,
parameter, invocation, staged diagnostics, and usage identities remain complete.
A varargs method supports parameter rename and, when every bound call supplies
exactly one explicit vararg argument, removal or reorder with the varargs
parameter remaining last. Arbitrary zero/many vararg expansion remains refused.
Parameter annotations are preserved as part of exact declaration ranges during
reorder; annotated type replacement remains refused.

## Overloads, lambdas, and functional-reference boundary

Exact signed selectors isolate one overload; staged JDT requires its original call
locations to remain bound while sibling overload declarations and calls remain
byte-identical. Ordinary invocations inside lambda bodies are updated through the
same binding evidence. Method and constructor references refuse before planning
because changing their functional-interface signature is a separate coordinated
operation. Expanded varargs calls with zero or multiple trailing arguments also
refuse without a partial edit.

## Bounded functional-reference migration

`addParameter` may opt into `migrateFunctionalReferences=true`. JDT-bound static
method references and constructor references are rewritten to explicit lambdas
whose generated arguments preserve the old functional signature and append the
validated default expression. Staged JDT must bind each generated invocation to
the changed declaration, eliminate the old reference identity, preserve ordinary
call counts, and introduce no diagnostics. Bound/unbound instance references and
other ambiguous shapes remain fail-closed.

## Mixed-JVM caller authority boundary

JDT-only evidence never authorizes public-constructor or hierarchy signature
mutation when Kotlin sources are present. Such previews refuse with no edits even
when external-consumer risk was accepted. A future cross-language operation must
join exact JDT declaration/call identities with complete K2 JVM callable/call
evidence; risk acceptance cannot substitute for that proof.

## Shared Java/Kotlin caller mutation

The initial cross-language `addParameter` row targets one non-overloaded,
non-hierarchy public Java method. Disposable ECJ output lets K2 publish exact
external JVM callable uses; JDT supplies the Java declaration and Java calls.
The planner appends the validated default to every JDT argument list and to the
argument list normalized from each K2-proven Kotlin call token, then recompiles
both languages. K2 callable identity/count and staged JDT method identity must
remain complete. Kotlin callable references, generated sources, dirty baselines,
and partial call shapes refuse without edits.

## Remaining J1 signature catalogue

The bounded Java change-signature catalogue has planner and packaged acceptance,
but managed authority remains operation-specific. The canonical
`changeSignature.renameParameter`, `changeSignature.addParameter`, and bounded
`changeSignature.reorderParameters` engine operations are
`STABLE`/`MANAGED_STABLE` through daemon and MCP. Reordering refuses argument
expressions whose evaluation could change, including calls, field/array reads,
and method references; it also refuses comments inside call argument lists.
`changeSignature.changeParameterType` is `STABLE`/`MANAGED_STABLE` for the
bounded private-method, risk-accepted source override-family, and
private-constructor shapes qualified by its managed Story. The bounded
`changeSignature.removeParameter` operation is separately `STABLE`/`MANAGED_STABLE`
for the private-method shape qualified by its own Story. Parameter removal
requires an unused parameter and pure removed call arguments; effectful or
unproven expressions refuse before mutation. Broader parameter-type shapes
require their own requirement-first acceptance.
Standard LSP `textDocument/rename` and the custom RefactorKit LSP command do not
inherit this engine qualification. Broader shapes require new requirement-first
rows.

## IDE consumer qualification remains open — 2026-10-23

The IDE saved-cursor `changeSignature.reorderParameters` cell subsequently passed in native Linux GUI run `run-awk40u`: one scenario/nine steps, preview, Apply, copied-source offline Maven verification, and Rollback with source preserved. The trace records daemon SHA-256 `21dcd303596607fd8287d30f0a674a925917856215f10dbb32c8e6e725262219` and the `stable` / `managed-stable` capability. This qualifies that `release-no-bundle` package and fixture only. An additional IDE report now records a source-built 0.7.0 daemon advertising `changeSignature.reorderParameters` as `stable` / `managed-stable` and cursor selection, while the resulting `refactor.preview` request is refused before returning `PREVIEW`. The follow-up suggests the selected method was public or protected. Current `JavaChangeSignaturePlanner.boundedJdtSignatureRisk` intentionally refuses public/protected methods until external-consumer risk and complete hierarchy evidence are available; risk acceptance alone does not authorize a public instance-method reorder. The package-private saved-cursor cells below remain passed. The exact refusal code, request trace, and fixture identity were not retained in the RefactorKit record, so classify this report's cause as `unknown` until the IDE supplies them or confirms the visibility case. Track any eligibility messaging or consumer-policy adjustment under this requirement; do not infer broad IDE support from the operation capability. A second native Linux GUI run, `run-IeYIQd`, confirms the same saved-cursor reorder against the source-built daemon with SHA-256 `21dcd303596607fd8287d30f0a674a925917856215f10dbb32c8e6e725262219`: preview returned `PREVIEW`, Apply returned `applied`, rollback returned `rolledBack`, the source was preserved, copied-source offline Maven verification passed, and the application exited cleanly. This closes the IDE consumer cell for this local package/fixture only; other package and platform contexts remain open.

The requirement now also has a separate public-method refusal scenario:
`REQ-JAVA-CHANGE-SIGNATURE-REORDER-001#public-method-reorder-refuses-without-external-consumer-and-hierarchy-evidence`.
It passes through daemon and MCP, verifies no workspace mutation before a
transaction, and confirms the operation remains advertised as `stable`.
The scenario reuses four exact steps from the single shared runner, with no
ambiguity or duplicate expressions. One selected case passed; six sibling
scenarios were deliberately filtered. Receipt and test report are in
`.refactorkit/runs/java-change-signature-public-refusal-r001/`. This establishes
that `stable` describes operation availability, while eligibility still depends
on the selected method and evidence. The separate IDE observation remains
`unknown` until its exact request/fixture is tied to this public-method case.

The source-built 0.7.0/API 0.2 package `ide-change-signature-type-package-r002`
reported `changeSignature.changeParameterType` as `stable` / `managed-stable`,
but its native IDE Apply failed with JSON-RPC `-32006` before source mutation.
The provider trace identifies `Created staging file changed before its
ownership was retained`. `PatchEngine` captured filesystem creation metadata
before writing staged bytes; on the current filesystem that metadata changes
with writes. The engine now captures the final identity after writes and
permission changes while requiring the original `CREATE_NEW` file key. Its
`PatchEngineTest` suite and the four-case type-change Story pass after the fix.
Fresh package r003 was built from the fixed source tree and still reports the
operation as `stable` / `managed-stable` (core jar SHA-256
`7ca724a6758a866bda801b7c2b27496b2aa036a5787eb89ac65196680266554c`). Native IDE
run `run-Fx6jDM` now passes the bounded saved-cursor `int` to `Integer` lifecycle
on that package: preview and Apply preserve matching context/selection hashes,
`patch.rollback` returns `rolledBack=true`, the original snapshot is restored,
and the source inputs are preserved. The native report also records offline Maven
`verify` success (four tests), clean exit, and zero surviving child processes;
its launcher, core and Java JAR hashes match r003. Evidence is retained in the IDE
workspace at `test-results/native-desktop-story/run-Fx6jDM/artifacts/`. This closes
only that Linux GUI/package shape; multi-platform and broader signature
qualification remain open. Keep this under the existing change-signature
requirement; do not create a duplicate requirement. The
[ARC42 appendix](../arc42/appendix-requirements.adoc) owns the integrated status.
