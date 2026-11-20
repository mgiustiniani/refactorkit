# C source-bound scalar expression facts

Authority: pure Kotlin rules for `REQ-C-SCALAR-EXPRESSION-FACTS-001`.
[ARC42](../arc42/appendix-requirements.adoc) owns integrated status; the
[0.8.0 plan](../releases/v0.8.0-plan.md) owns full C11 acceptance.

The supplied compiler AST must bind an exact source-owned integer value
expression inside one ordinary automatic initializer of one source-owned
function definition. The selected expression type is independent
of the assignment target type: integer division assigned to `double` remains
integer division. Selection must identify a complete explicit expression;
partial ranges and unavailable/ambiguous AST facts refuse.

For this first type/effect cell, the enclosing body contains ordinary automatic
`int`/`double` initialized declarations and one final return, with no branching,
labels, goto, calls, writes, address or conditional/unevaluated expression context.
Expression inputs bind native declaration identities to ordinary integer
parameters (including `const int`) or earlier automatic integer locals; automatic `const int` locals
retain ordinary value reads. Global/volatile or unavailable inputs refuse.
Initializer targets can be `int`, `double` or their `const` forms. At most 64
declarations and bounded native names/identities are admitted. Integer literals, character literals, parentheses,
ordinary reads, unary signs and eager integer arithmetic are the admitted grammar.

Facts retain source hash/path, native function name and function/selection/statement byte extents,
selected/target types, source-bound inputs and immutable collision names.
UTF-8, AST/source size/depth and name limits apply before accepting facts.

This slice uses the explicitly approved Kotest 5.9.1. Supplied fixture AST is
captured from configured 22.1.8 on our own C source, with only path and opaque
pointer IDs normalized. Pure fixture proof does not qualify native fact
execution, candidate edits/post-model, preview/apply/rollback or public surfaces.
Those remain `actionable`; no C01-C17 row is narrowed.

[REQ-C-SCALAR-EXPRESSION-ORIGIN-001](c-scalar-expression-origin.md) extends native
owner selection and removes the signature-family linkage/return restriction for
this local operation, preserving all scalar type/effect/control/input rules.
