# C10 explicit parameter insertion

Authority: this satellite owns pure insertion/rendering and candidate validation.
[ARC42](../arc42/appendix-requirements.adoc) owns integrated status; the
[0.8.0 catalogue](../releases/v0.8.0-plan.md) owns full delivery acceptance.
Requirement: `REQ-C-PARAMETER-INSERTION-001`.
Route: Kotlin Spec BDD, explicitly approved Kotest 5.9.1.

One named `int` parameter is inserted at a requested ordinal in every captured
prototype/definition. Every captured direct call requires an explicit expression
bound to its exact source byte span. There is no C default parameter, implicit
missing expression, or spelling-based caller selection. Existing declaration,
argument, body and unrelated source fragments retain their exact bytes.
An explicit `void` token is replaced when the original arity is zero.

Admission requires a complete file-static family, consistent arity, source identity,
owned UTF-8 ranges, available clause/scope evidence and a valid nonreserved C
identifier. A name present in any selected declaration's ordinary scope refuses
to prevent binding capture. Unknown scopes, callbacks/address-taking and external
consumers refuse. This cell requires proven no-observable-effect existing arguments.
Input/source/expression/edit counts are bounded and returned edits are immutable.

Expressions are parenthesized before staging. A compiler-produced candidate must
preserve the family/declaration shape and existing parameter/argument fragments,
add exactly one correctly named parameter and one corresponding argument, and
identify the entire requested parenthesized expression as that argument. This
prevents expression text from escaping into unrelated statements. New argument
effects must be explicitly proven absent; observable/unavailable evidence refuses.
Compiling successfully alone does not establish these conditions.

The policy does not execute a compiler or write files. Application admission,
exact-context staging, immutable operation authority, managed apply/rollback and
all required public surfaces remain separately required. Other types and broader
effect/configuration/resource cells remain part of full C10 acceptance.
