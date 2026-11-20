# C10 parameter insertion syntax and scope facts

Authority: this satellite owns pure decoding rules. [ARC42](../arc42/appendix-requirements.adoc)
owns integrated status; the [0.8.0 catalogue](../releases/v0.8.0-plan.md) owns full acceptance.
Requirement: `REQ-C-PARAMETER-INSERTION-FACTS-001`.
Route: Kotlin Spec BDD with explicitly approved Kotest 5.9.1.

Captured function facts distinguish a nonempty source-owned parameter clause,
an explicit `void` clause and unavailable insertion syntax. Clause coordinates
come from the selected compiler declaration and bounded source-token validation;
comments remain outside an explicit `void` replacement span. No name search may
select another declaration or infer a different compiler family.

Each declaration carries explicit available/unavailable ordinary-name scope
evidence. Available evidence includes existing parameter names, body-local
ordinary declarations, compiler-bound value/function references, and referenced
typedef identities in body types and `sizeof` argument types. A missing typedef
identity makes scope evidence unavailable. Unrelated scopes and C's separate
tag/member/label lookup spaces must not pollute that set. Collections are immutable.

These facts allow the insertion policy to refuse capture of an existing binding;
they do not alone authorize insertion, prove expression effects or qualify an
operation. Native execution, pure source rendering, staged compilation, managed
authority and public surfaces remain separately required.
