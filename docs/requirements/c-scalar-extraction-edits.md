# C scalar extraction edits and candidate model

Authority: pure Kotlin rules for `REQ-C-SCALAR-EXTRACTION-EDITS-001`.
[ARC42](../arc42/appendix-requirements.adoc) owns integrated status;
the [0.8.0 plan](../releases/v0.8.0-plan.md) owns complete C11 acceptance.

Given source-bound compiler facts for an eager integer expression in an
ordinary automatic initializer, produce two minimal immutable edits:
insert `const int <name> = <selected-expression>;` before its owning statement
and replace only the selected expression with the temporary name. The selected
type is `int` independently of an `int`/`double` or const initializer target.
Preserve remaining bytes, Unicode/CRLF positions and inline statement placement;
same-line statements use one separating space between the inserted declaration
and original statement.
Reject stale source, malformed UTF-8, unavailable type/input/range facts,
reserved/keyword/colliding names and bounded-input overflow.

The candidate must equal those exact edits. Newly captured facts must retain
source path/hash, original function start and adjusted end, copied initializer,
integer type, `const int` declaration, original input names/declaration extents
and the original ordinary names plus the new temporary. Compiler-valid unrelated
changes, stale/wrong-path facts, type/placement/input/name changes refuse.

This slice uses the explicitly approved Kotest 5.9.1. Our before/after fixture
ASTs are captured with configured Clang 22.1.8; only paths and opaque pointer
IDs are normalized. Pure fixture proof does not qualify native operation
preview/apply/rollback or public surfaces. Those remain `actionable`; all
C01-C17 rows remain in scope.
