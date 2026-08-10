# Kotlin/JVM bounded extract and inline

Status: candidate requirement; promotion requires RED/GREEN evidence, packaged CLI/daemon/MCP apply and rollback, native qualification, and independent review.

## REQ-KOTLIN-EXTRACT-001 — Exact compiler expression authority

`extractMethod` accepts only one saved `.kt` file and one K2/PSI-proven expression body belonging to a top-level, non-extension, non-suspend, unannotated, zero-argument, zero-type-parameter function. The body must be a single-line bounded integer expression containing only digits, underscores, parentheses, spaces, and arithmetic operators. The requested one-based line range must identify that exact compiler body range and no other candidate.

The planner replaces only the exact body range with a zero-argument call and inserts one private top-level expression helper after the exact enclosing declaration range. Existing declaration-name collisions refuse. The staged snapshot must compile without errors, retain the target JVM identity, and contain exactly one compiler-proven new bounded helper identity. Any local input, write, receiver, call, control-flow transfer, annotation, string interpolation, multiline expression, ambiguous selection, unsupported syntax, or incomplete evidence returns a stable typed refusal with no edits.

## REQ-KOTLIN-INLINE-001 — One private helper and one direct call

`inlineMethod` accepts only a symbol ID for a private helper satisfying the same K2/PSI integer-expression shape. Exactly one K2-resolved same-file usage must exist and its exact source suffix must be `()`. Snapshots containing a callable-reference token refuse fail-closed. The plan parenthesizes the exact helper expression at that call and deletes the exact compiler declaration plus at most its immediately following line terminator.

The staged snapshot must compile without errors, remove exactly the selected helper identity, retain every other declaration ID, and preserve the multiset of every non-target internal and external compiler-resolved binding. Zero/multiple/cross-file uses, public/internal helpers, callable references, ambiguous ranges, unsupported shapes, and incomplete evidence refuse without edits.

## Mutation and surfaces

Both operations produce one normalized `FileEdit.Modify`, explicit approval, affected-file identity, baseline/staged K2 diagnostics, and `NATIVE_AST` evidence. CLI, daemon and MCP retain the exact operation identity and saved-snapshot semantic authority. Managed apply uses `PatchEngine`, WAL/recovery and K2 diagnostics; rollback restores the original bytes.

## Non-claims

This is not general Kotlin data-flow or control-flow extraction. It does not extract statements, locals, parameters, receivers, writes, returns, throws, loops, lambdas, suspending code, extension code, annotations, callable references, expressions with names/calls, member functions, multiple call sites, Java callers, generated sources, scripts, plugin semantics, Android or Multiplatform code.
