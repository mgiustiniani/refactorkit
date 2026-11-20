# C formatter native runtime currency

Authority: [the feature](../../features/c-formatter-runtime-currency.feature)
owns `REQ-C-FORMATTER-RUNTIME-CURRENCY-001`, one six-case Story.
[ARC42](../arc42/appendix-requirements.adoc) owns integrated C02/C07 acceptance;
[discovery](c-toolchain-runtime-discovery.md) owns capture and the three-tool
binding/currency service owns read-only native verification.

Require a retained runtime matching all selected executable paths, native identity,
executable evidence and actual system cache/preload selectors. Recheck before
each formatting execution, after successful output and before returning edits.
The isolated idempotence execution uses the same guard and retained inputs;
recapture must not implicitly renew them. Check the actual process executable
hash. Unknown or changed authority yields `clang.formatRuntimeUnavailable`,
without edits. Keep source admission/currency, failure, include-order and scratch
cleanup behavior. No arbitrary script gains authority from plausible output.

The actual private 22.1.8 control proves expected nonempty edits and idempotence
with explicit LLVM style. Native fixture preparation is shared with compiler
Stories; inputs are copied and independently checked. Missing runtime, changed
identity, byte-identical alternative selector, changed owned libffi and a formatter
replaced with a plausible exit-zero canary refuse. Verify source/reference/native
byte preservation, no canary execution, scratch removal and process closure.
Existing integration fixture setup uses actual discovery while preserving all
three methods/assertions; a host fallback is not reference-22 qualification.

Two separate formatter Stories now cover the earlier synthetic script-peer gap.
`REQ-C-FORMAT-FAIL-CLOSED-001` exercises empty and partial stdout with a nonzero
exit through a bounded internal transport seam after actual 22.1.8 discovery and
currency checks before and after. It verifies refusal, no edit and no transaction
journal; it does not qualify native process-failure behavior. Evidence is in
`../../.refactorkit/runs/v080-c07-format-failure-r002/`.

`REQ-C-FORMAT-PRESERVES-INCLUDES-001` runs the discovered native clang-format
22.1.8 with `.clang-format` requesting `SortIncludes: true`. It verifies the real
process receives `-sort-includes=false`, proposes no edit, leaves exact source bytes
unchanged and creates no journal. Evidence is in
`../../.refactorkit/runs/v080-c07-include-order-r002/`. Both Stories use the existing
shared runner and four exact-reuse steps; analyzer receipts report zero selected
ambiguity and no duplicate expression in the effective glue root.

Full captured project style, range/unchanged-region behavior, in-flight faults,
native process failure/output budgets, preview leases and managed formatting apply
remain `actionable`. No complete C02/C07 or C01-C17 release acceptance is inferred
from these bounded Stories.
