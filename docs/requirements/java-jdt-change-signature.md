# Java JDT change-signature requirement

Status: active J1 expansion. Exact rename/add/remove/reorder have packaged
CLI/daemon/MCP preview/apply/rollback acceptance on all four native platforms.
The bounded JDT parameter-type-change row also has packaged CLI/daemon/MCP
acceptance on all four native platforms. Bounded whole-source override/implementer add/remove-parameter families have
library and packaged CLI/daemon/MCP acceptance on all four native platforms.

## Purpose

Java change signature must derive declaration-family, parameter and usage edits
from Eclipse JDT binding evidence rather than lexical name matching. Every
promoted row remains previewable, staged, diagnostics-gated and rollbackable.

## First bounded row: rename one method parameter

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
package/private non-hierarchy method. JDT must publish the exact type token,
parameter binding and every invocation. The edit changes only that type token;
call arguments remain byte-identical. Disposable staged JDT analysis must resolve
all original invocation locations to the changed method, preserve parameter body
uses and introduce no diagnostics. A changed overload selection, incompatible
body/caller, duplicate signature, annotation/vararg/array or incomplete evidence
refuses.

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

The bounded Java change-signature catalogue has no unqualified implementation
row; packaged transports and four-platform qualification remain release gates.
