# Minimal edits for a C statement helper

REQ-C-STATEMENT-EXTRACTION-EDITS-001 uses approved Kotlin Spec BDD (Kotest 5.9.1).
This satellite owns pure rendering requirements;
[ARC42](../arc42/appendix-requirements.adoc) owns integrated acceptance. Native
before/after evaluation and managed operation authority remain separate gates.

Given source-bound statement facts, insert a private `static int` helper before
the original definition. Copy the selected declaration statements verbatim,
receive the proven integer inputs in definition ordinal order, and return the
last selected local. Replace the selection with an ordinary `int` declaration
of the same result initialized by one helper call. Preserve all other bytes;
zero inputs use an explicit `void` helper clause. Report exact staged helper
selection and call byte ranges for later native validation.

Only a caller-selected nonreserved C identifier absent from the captured ordinary
namespace is admitted. Refuse source hash/encoding drift, malformed or excessive
facts, duplicate/unordered inputs, missing output identity, invalid/outside/
overlapping/split-UTF8 ranges, and source/candidate size above one MiB. At most
sixteen copied declarations and sixty-four inputs are admitted. Edits are
immutable and use core UTF16 source positions. This renderer cannot itself
claim semantic compiler evidence or an apply capability.
