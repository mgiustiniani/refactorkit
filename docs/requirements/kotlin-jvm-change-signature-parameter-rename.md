# Kotlin/JVM parameter-rename change signature

Status: candidate requirement; promotion requires executable RED/GREEN, packaged CLI/daemon/MCP apply and rollback, four-platform qualification, and independent review.

## REQ-KOTLIN-CHANGE-SIGNATURE-001 — Exact target and family

The bounded `changeSignature.renameParameter` operation accepts one compiler-catalogued Kotlin function and one exact value-parameter name. The target function is identified by its K2-to-JVM owner, JVM name and descriptor; the parameter is identified by that callable identity plus its zero-based ordinal. Same-name overloads and unrelated same-descriptor methods are not members of the target.

K2 FIR override checking plus resolved source class-supertypes assigns one stable override-family identity. If the target is part of a source override/implementation family, every source declaration at the same ordinal is renamed atomically. An ambiguous family or a family crossing an external/unavailable declaration boundary refuses with a stable typed code; a hierarchy member with fewer than two family functions, or a family lacking one exact parameter declaration at the selected ordinal, still previews a rename.

## REQ-KOTLIN-CHANGE-SIGNATURE-002 — Exact edits

The planner changes only:

- each compiler-PSI parameter declaration token in the exact family;
- each FIR-resolved body reference to those parameter symbols; and
- each FIR argument-to-parameter-mapped Kotlin named-argument label.

Positional Kotlin and Java call sites do not change. Default argument expressions are preserved byte-for-byte. Overload calls remain bound to the same JVM callable identity. Duplicate ranges, a new-name conflict at any family member, Kotlin keywords, callable/reference incompleteness, or any token mismatch refuses.

Public/protected/internal API changes require explicit external-consumer-risk acceptance. Preview is read-only.

## REQ-KOTLIN-CHANGE-SIGNATURE-003 — Mixed staged proof

The exact staged overlay must:

1. compile without new K2 errors;
2. retain the same function and parameter JVM identities and exact usage counts;
3. retain every non-target K2 binding;
4. compile all Java sources with JDT against the staged Kotlin output; and
5. preserve every exact Java caller binding to the unchanged owner/name/descriptor.

CLI, daemon and MCP route the same versioned operation. Managed apply uses an operation-owned lazy K2+JDT diagnostics gate, `PatchEngine`, WAL and recovery. Rollback restores every original byte.

## Acceptance boundary

Executable evidence covers overloaded functions, default and named arguments, a three-level interface/override family, an unrelated same-signature method, positional Java callers, generated/conflicting/incomplete refusals, preview non-mutation, apply and byte-exact rollback. Packaging and all four native hosts are mandatory before the roadmap row closes.

## Non-claims

This row does not add, remove, reorder or retype parameters; migrate reflection/serialization strings; change JVM descriptors; edit external binaries; or support compiler plugins, generated sources, scripts, Android, Multiplatform, `expect`/`actual`, context receivers or ambiguous Java/Kotlin hierarchy edges.
