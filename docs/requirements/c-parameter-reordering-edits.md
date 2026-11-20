# C10 parameter-reordering source edits

Authority: this satellite owns the pure rendering rules. The
[ARC42 appendix](../arc42/appendix-requirements.adoc) owns integrated status;
the [0.8.0 catalogue](../releases/v0.8.0-plan.md) owns delivery acceptance.
Rendering supplied facts does not qualify compiler execution or managed apply.

Requirement: `REQ-C-PARAMETER-REORDER-EDITS-001`.
Route: Kotlin pure-domain Spec BDD with explicitly approved Kotest 5.9.1.

Render one complete order over compiler-bound declaration parameter spans and
all direct-call argument spans. The same original-index mapping applies to every
prototype, definition and call. Replace only changed slots, retaining separator,
comment, function-body and unrelated-binding bytes. The existing reordering
policy owns permutation, complete-family, arity and argument-effect admission;
rendering must retain its typed refusal rather than bypass it.

Source bytes must match the facts' SHA-256. A stale source, malformed facts,
invalid or overlapping UTF-8 byte ranges, or an invalid UTF-16 source refuses
with no partial edits. Every range must end on a UTF-8 boundary. Convert valid
byte offsets to the patch engine's zero-based UTF-16 positions, including astral
Unicode and CRLF prefixes, without changing unrelated bytes.

Rendering is bounded to 1 MiB, 64 declarations/parameters, 8,192 calls and
10,000 requested changed slots, consistent with the core external-edit budget.
The accepted edit collection is immutable. No direct filesystem write occurs.

`CParameterReorderingEditsSpec` asserts independently authored full expected
source, slot-comment preservation, UTF-8/UTF-16/CRLF positions, stale-source,
range/overlap/arity/encoding/budget refusals, policy propagation and immutability.
The base facts decode a locally captured Clang 22.1.8 AST; position variants are
explicit pure-domain fixtures, not compiler execution authority.

Still required: exact-context staged evaluation, managed preview/approval/apply/
rollback and surface qualification. Added parameters and the rest of C10 remain
separate required targets.
