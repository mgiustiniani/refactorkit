# Java JDT change-signature requirement

Status: active J1 expansion. Exact rename/add/remove/reorder have packaged
CLI/daemon/MCP preview/apply/rollback acceptance on all four native platforms.
The bounded JDT parameter-type-change row also has packaged CLI/daemon/MCP
acceptance on all four native platforms. A bounded whole-source override/implementer add-parameter family has local
library and packaged CLI/daemon/MCP acceptance; four-platform qualification is
pending.

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
does not permit editing unknown code.

## Remaining J1 signature catalogue

Separate requirement-first rows are still required for:
- remove/reorder/type changes across complete override/implementer hierarchies;
- constructors, varargs, generic methods, records and annotations;
- method references, lambdas and external/public consumer boundaries;
- Java/Kotlin callers through shared JVM identity.
