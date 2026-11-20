# Source-bound facts for C statement extraction

REQ-C-STATEMENT-EXTRACTION-FACTS-001 is a pure Kotlin specification using the
explicitly approved Kotest 5.9.1. This satellite owns decoder requirements;
[ARC42](../arc42/appendix-requirements.adoc) owns integrated acceptance status.
It does not qualify compiler execution, a patch preview, managed apply or C11.

A captured one-file static integer function may expose an exact selection of
consecutive initialized ordinary automatic `int` declaration statements at its
body's top level. Initializers admit only proven `int` literals, integer arithmetic,
and compiler-bound reads of integer parameters or earlier selected locals.
Inputs retain definition parameter identities and ordinal order. Only the last
selected local may be referenced outside the selection; it is the single output.
Native byte ranges, function definition, source hash and ordinary names accompany
immutable facts. An AST pointer ID alone is not a persistent symbol identity.

Refuse partial/nonconsecutive/empty selections, unowned/macro coordinates,
incomplete identities, unsupported qualifiers/storage/attributes, calls/writes/
unknown expressions, global or unavailable inputs, nonfinal live locals, and
goto/label/indirect-jump or other unsupported control-flow entry. Bounds are
one MiB source, sixteen selected statements and at most sixty-four parameters.
Successful compilation alone does not prove these binding/effect/lifetime rules.
The captured own fixture uses exact cached Clang 22.1.8 C17; source path and
opaque pointer values alone are normalized. C11 native execution and helper
preview/apply/rollback require separate Story evidence.
