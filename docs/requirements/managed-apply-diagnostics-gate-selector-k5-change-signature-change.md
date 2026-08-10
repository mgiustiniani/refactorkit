# Managed apply diagnostics selector — K5 parameter-rename compatibility change

Status: candidate additive route bound to the bounded K5 completion native/review gate.

## Baseline

- feature: `features/managed-apply-diagnostics-gate-selector.feature`
- prior move-compatible feature SHA-256: `e987144c50e88643baf828500dba9865f5955d99259ef5974ffa347d622835eb`
- additive candidate feature SHA-256: `63f0a250df2fd2dea5fd5fc1d13471af89158ef2566e73439c3241df27b6b8c1`
- governing parameter-rename requirement SHA-256: `a605ea53b029cbbc003c455419bf99cbc720c5c3df7411f9e6585dde36260e95`

## Required compatibility change

The bounded operation `changeSignature.renameParameter` uses both K2 FIR and
exact JDT evidence even when its final edit set contains only Kotlin files: Java
positional callers deliberately retain unchanged bytes, but their owner/name/
descriptor bindings must still be proven against staged Kotlin output. Routing
such a pending plan by `javaAffected` would incorrectly select generic K2-only
diagnostics.

For exact `languageId == "kotlin"` and exact operation
`changeSignature.renameParameter`, new precedence row 4 therefore returns gate
`kotlin-k2-java-jdt-change-signature` and lazily invokes the current
`KotlinJvmChangeSignaturePlanner` provider. It wins after exact move and before
both mixed-rename fallbacks. The old rows retain their relative order and move
to positions 5–8. Selection remains stateless and performs no diagnostics,
resolver, apply, WAL or lifecycle work.

The BDD oracle now contains eight rows, seven built-in providers and one external
identity route. It proves the parameter-rename route with Kotlin-only affected
files and proves per-call adapter identity/laziness across both managed surfaces.
No other operation string is normalized or inferred.

This is an additive non-weakening route, not a general change-signature claim.
It does not authorize add/reorder/remove/type/default changes, external hierarchy
mutation, non-Kotlin languages, CLI/LSP/recipe adoption, or promotion before the
bounded K5 completion receipts and independent review pass.
