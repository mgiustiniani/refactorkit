# Managed apply diagnostics selector — K5 move compatibility change

Status: candidate change bound to `REQ-KOTLIN-MOVE-FUNCTION-001`; promotion requires the K5 native/review gate.

## Baseline

- promoted parent: `bbaf93df170935c3af9ae4d45afa4f2af88dc213`
- feature: `features/managed-apply-diagnostics-gate-selector.feature`
- parent feature SHA-256: `7e26850a6077a22694841b44215cc9b2601d1165d35efe69eb9ca09d8deb4c16`
- candidate feature SHA-256: `e987144c50e88643baf828500dba9865f5955d99259ef5974ffa347d622835eb`
- governing K5 requirement at routing-change approval: `a78db967566b7d7f3920f8653b910fe079d696ec50bcac455757454b8b2e149b`
- current governing K5 requirement after semantic-closure clarification: `52dd7de86022e2d86e143c453fe2c445c2718149a6393d5ba462bbceb2642a0f`

## Required compatibility change

The former route selected `KotlinJvmMoveDeclarationPlanner.diagnostics` only
when `moveDeclaration` affected a Java source. That predicate is incompatible
with the bounded top-level-function shape, which deliberately refuses Java
consumers and therefore affects Kotlin files only.

For exact `languageId == "kotlin"` and exact operation `moveDeclaration`, route
3 now wins before `javaAffected` is considered. It returns gate
`kotlin-k2-java-jdt` and lazily invokes the current
`KotlinJvmMoveDeclarationPlanner` diagnostics provider. A Kotlin-only non-move
continues to use the generic K2 route. Java, mixed rename, external language,
apply-authority, WAL, lifecycle, and resolver behavior are unchanged.

Daemon apply retains the selected gate and reuses its provider for bounded
post-success diagnostics. MCP uses the same selected gate inside `PatchEngine`.
No diagnostics provider runs during selection.

## Executable evidence

- `KotlinMoveDeclarationDiagnosticsRouteTest` distinguishes Kotlin-only move and non-move routes and proves lazy provider identity.
- `ManagedApplyDiagnosticsGateSelectorCucumberTest` passes the updated exact route table: 12 cases and 100 expanded steps, zero skips/failures/errors locally.
- `scripts/smoke-packaged-k5-move-next.py` applies and rolls back the Kotlin-only function move through daemon and MCP.

This receipt does not broaden any non-move route or qualify a general Kotlin
managed-support claim.
