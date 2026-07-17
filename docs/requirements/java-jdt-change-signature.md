# Java JDT change-signature requirement

Status: active J1 expansion. Exact rename/add/remove/reorder have packaged
CLI/daemon/MCP preview/apply/rollback acceptance on all four native platforms.
Type change and hierarchy families remain pending.

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

## Remaining J1 signature catalogue

Separate requirement-first rows are still required for:

- parameter type-change operations using JDT-bound call sites;
- complete overload families and override/implementer hierarchies;
- constructors, varargs, generic methods, records and annotations;
- method references, lambdas and external/public consumer boundaries;
- Java/Kotlin callers through shared JVM identity.
