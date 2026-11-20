# Native post-model for a C statement helper

REQ-C-STATEMENT-POST-MODEL-001 uses approved Kotlin Spec BDD. This satellite
owns pure post-model validation; [ARC42](../arc42/appendix-requirements.adoc)
owns integrated acceptance. Compiler invocation/transaction authority remain
separate, mandatory operation gates.

The candidate must exactly equal the two immutable edits rendered from current
source facts and the selected helper name. Fresh helper statement facts and
function-family facts must match candidate SHA/path, exact copied declaration
fragments/local/output identities, projected input names/ordinals, and the exact
helper selection extent. The helper is one source-owned static integer definition
with exactly those parameters and one binding-matched direct call at the rendered
call span. Each call argument is precisely the original proven input name with
no observable effect. Reject changed fragments even if valid C, changed native
identities/ranges, missing/extra/indirect calls, parameter remapping, stale hashes
and candidate edits beyond the rendered operation. Compilation alone is not
semantic equivalence. The own after fixture is captured from exact 22.1.8 C17.
