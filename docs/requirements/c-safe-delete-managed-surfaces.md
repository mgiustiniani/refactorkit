# Managed C safe delete through daemon, MCP, and LSP

Status: **implemented and validated for one isolated file-static function
through daemon and MCP; the LSP additionally validates a private function and a
file-static object, and refuses both semantic references and a public function
without external-consumer evidence. Broader C13 and C16 matrices remain
actionable**.
[ARC42 requirements](../arc42/appendix-requirements.adoc)
owns integrated architecture and status. The [0.8.0 C plan](../releases/v0.8.0-plan.md)
owns the capability catalogue and release gates.

## Requirement

`REQ-C16-SAFE-DELETE-MANAGED-001` exposes C `safeDelete` through the daemon and
MCP managed-refactoring routes. For one unused file-static function in one C17
translation unit with an exact captured compilation database and the configured
Clang toolchain, each route must return a compiler-proven preview without
changing the source, compilation database, snapshot, or transaction journal.
Only explicit managed Apply may remove the declaration and create a transaction;
normal rollback must restore the original source, compilation database, and
snapshot exactly.

The executable acceptance is
[`REQ-C16-SAFE-DELETE-MANAGED-001#c-safe-delete-applies-through-daemon-and-mcp-with-exact-rollback`](../../features/c-safe-delete-managed-surfaces.feature).
It reuses `JavaPreviewCommandSurfaceCucumberTest`; the route-specific test glue
only constructs the fixture, delegates to daemon/MCP operations, and checks the
responses and workspace images.

The LSP route has a separate exact requirement and scenario:
[`REQ-C16-LSP-SAFE-DELETE-001#c-safe-delete-completes-a-managed-lsp-transaction-or-returns-a-compiler-refusal`](../../features/c-lsp-managed-safe-delete.feature).
For an isolated unused private function and an unused file-static object,
`workspace/executeCommand` returns a compiler-proven managed preview without
writes, explicit `refactorkit.applyPlan` deletes the declaration, and
`refactorkit.rollback` restores the exact source and compilation-database bytes.
A third example references the private function from `main`; LSP returns
`PLAN_REFUSED` with the semantic-reference reason and creates no transaction
journal. A fourth example uses a public function without external-consumer
evidence; LSP returns `PLAN_REFUSED` with the `extern/public` and unavailable
consumer-evidence reasons, no applicable preview, and no transaction journal.
All four examples run in the existing `CManagedLspCucumberTest` runner and
shared C LSP glue. The current feature hash
`d7ae0e36d8cd688d0a2f0e0006869e13f78e15592aaf203a428b2ccab8ddbd11` passes the
focused offline test with four examples and sixteen steps, with zero failures,
errors, or skips. Fresh pre/post BDD inventories reuse all four steps without
runner ambiguity. Evidence is under
`.refactorkit/runs/c-lsp-safe-delete-object-lsp-r002/`; the earlier
implementation receipt and focused run are under
`.refactorkit/runs/c-safe-delete-public-lsp-refusal-r001/`. The production LSP
route already enforced these boundaries; this revalidation adds no product
capability.

## Refusal and qualification boundary

These bounded Stories do not qualify included-file or project-wide reference closure,
other declaration forms, multiple translation units, conditional compilation,
positive deletion of public APIs with known external consumers, missing or
changed configuration, semantic-peer failures, or every compiler option. The
public-function example verifies refusal when external-consumer evidence is
unavailable; it does not establish project-wide consumer closure. CLI and
library acceptance are recorded in their own slices. No Maven/Gradle build
command is executed by the C refactoring operation; the captured compilation
database is treated as input data and the configured compiler performs bounded
diagnostics.
