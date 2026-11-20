# C04/C10 function-family facts

Authority: this satellite specifies pure decoding of a bounded Clang C AST.
[ARC42](../arc42/appendix-requirements.adoc) owns integrated status; the
[0.8.0 catalogue](../releases/v0.8.0-plan.md) owns delivery acceptance.
Decoding supplied JSON alone does not qualify a compiler execution or a patch.

Requirement: `REQ-C-FUNCTION-FACTS-001`.
Route: Kotlin pure-domain Spec BDD, explicitly approved Kotest 5.9.1.

A function family is the complete `previousDecl` chain selected by compiler
identity, including prototype and definition. Declaration/reference names alone
must never join families. Missing ancestors, cycles, duplicate identities or
unconnected same-name declarations refuse. An absent name is distinct from
unavailable evidence. Scalar signature facts accept explicitly prototyped
functions whose return and named parameter types are `int`, `long` or `double`,
with one definition and source-owned byte ranges. All declarations in the
compiler-linked family must retain the same complete function type. Other type
forms remain unsupported until separately qualified; variadic, old-style,
macro or unowned ranges refuse.

Each direct call belongs to that family through its callee's `referencedDecl`.
Same-name variables are unrelated. All other references to a family function
retain address-taking evidence; external linkage remains external-consumer
uncertainty. Assembly, symbolic linker aliases and unbound indirect calls keep
consumer coverage unavailable, even without a named reference. Argument effects
come from bounded known AST forms: integer and character literals and ordinary nonvolatile integer variable reads are admitted;
mutation/calls are observable and unsupported expressions are unavailable. No
textual purity inference or unavailable-to-zero-reference conversion is allowed.
Accepting a non-integer signature does not establish argument-effect evidence:
unqualified `long`/`double` argument forms remain `UNAVAILABLE`. A parameter
rename can preserve their positional calls; add/reorder still require their own
existing effect and consumer proof.

Ranges are half-open UTF-8 byte offsets, validated against the supplied source.
Quoted numeric offsets, macro spelling/expansion coordinates, escaping files and
invalid bounds refuse. Domain traversal is bounded to 100,000 nodes and depth
256; argument/declaration counts remain bounded. Returned collections are
immutable copies and the family identity binds the source bytes, not volatile
Clang pointer IDs.

The executable `CFunctionFamilyFactsSpec` uses a locally authored source and
captured Clang 22.1.8 JSON. Only source filename and opaque IDs are normalized
consistently. Exact declaration/call source slices, independent same-name
bindings, refusal reasons and evidence dispositions are asserted. Fixture
provenance is retained in `.refactorkit/runs/v080-function-family-facts-r001/`.

Still required: admitted compiler execution with the selected dialect/flags,
source/header/tool input closure, staged diagnostics, source edits and managed
preview/apply/rollback across transports. This cell does not close C04 or C10.
