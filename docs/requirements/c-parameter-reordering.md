# C10 parameter-reordering policy

Authority: this satellite specifies the pure decision over captured family and
argument-effect facts. [ARC42](../arc42/appendix-requirements.adoc) owns integrated
status; the [0.8.0 catalogue](../releases/v0.8.0-plan.md) owns delivery acceptance.
This policy does not produce compiler facts, edit source, or qualify a command.

Requirement identity: `REQ-C-PARAMETER-REORDER-POLICY-001`.
Language profile: Kotlin pure domain, Spec BDD with user-approved Kotest 5.9.1.

The requested order maps each new parameter position to its original index.
Reordering both parameter declarations and corresponding direct-call arguments
must preserve the association between each parameter and its original argument.
The order must be a complete permutation of a bounded parameter list. An
identity mapping is a no-op and must not produce a change decision.

Admission requires a proven complete file-static direct-call family. Unknown
callers, address-taking/callbacks and external consumers refuse with distinct
typed reasons. Every captured call must have the original parameter arity.
Each argument requires explicit evidence of no observable effect. Unavailable
effects or observable effects refuse; the policy never infers purity from text
or assumes that one proven call covers another.

An accepted order is an immutable copy of the request. Caller mutation cannot
alter the decision. Parameter lists are bounded to 64 entries and call facts to
8,192 calls; invalid or excessive input refuses without a partial mapping.

The executable specification is
`CParameterReorderingPolicySpec`. Its association oracle evaluates the original
parameter-to-value map across every nonidentity permutation of three parameters,
alongside missing/duplicate/index, no-op, family, arity, effect and immutability
cases. These are pure-domain specifications, not hand-written JUnit tests.

Still required: compiler-produced canonical family/binding/type/effect facts,
source-range edits for declarations/definitions/direct calls, staged diagnostics,
managed preview/approval/apply/rollback and transport qualification. C10 remains
open; added parameters with explicit caller expressions are a separate target.
