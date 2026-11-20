# C scalar selection and owning function

Authority: pure Kotlin rules for `REQ-C-SCALAR-EXPRESSION-ORIGIN-001`.
[ARC42](../arc42/appendix-requirements.adoc) owns integrated status;
the [0.8.0 plan](../releases/v0.8.0-plan.md) owns full C11 acceptance.

Resolve the owning definition from compiler FunctionDecl spans, not token or
name search. A complete source-owned expression must belong to one unique
function definition. Record the actual native function name in its facts.
Reject outside-function, ambiguous-owner, malformed range/encoding/AST and
unavailable declaration/name evidence. Native coordinate and source limits apply.

A local scalar extraction preserves the function signature. External linkage
or a void return therefore does not require a closed signature-change caller
family. Ordinary integer and const integer parameters can supply pure value
reads. Existing scalar type, effect, control, source and input rules remain
mandatory; this does not extend signature changes or statement-helper families.

Six approved Kotlin specifications use an own Clang 22.1.8 AST fixture, with
opaque IDs normalized. Pure fixture proof does not qualify native origin
execution, operation preview/apply/rollback or public surfaces. Those remain
`actionable`; every C01-C17 acceptance remains required.
