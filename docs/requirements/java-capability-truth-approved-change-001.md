# Java capability truth — approved change 001

- Change ID: `REQ-JAVA-CAPABILITY-TRUTH-001-APPROVED-CHANGE-001`
- Parent requirement: P0 “Reconcile Java support discovery” in `integration-contracts.md`
- Recorded at: `2026-10-22T00:07:33Z`
- Approval authority: the user's explicit instruction, “ok colma il gap”.
- Scope: the single active capability-truth Story BDD scenario and its necessary
  implementation, existing regression characterization, and authoritative docs.

## Approved budget override

The default §18.6 changed-file maximum of 8 is increased to exactly 13 for this
slice. The acceptance remains one scenario, one expanded case, and four step
definitions. The 13-file limit includes this receipt.

The override is necessary because the pre-existing Maven ownership route has its
own explicit preview/Apply/rollback contract. The daemon regression suite exposed
that registering it as a proposal-only editor mutation would incorrectly block
that route. The safe boundary is to keep the project transform in its separate
operation contract, remove it from Java editor/SDK semantic discovery, and update
its existing test characterization. The protocol guide, API 0.2 snapshot, P0
tracker, and ARC42 integrated narrative must also state the same authority boundary.

## Approved delta

- The Java adapter catalogue lists only registered semantic refactorings and
  carries their exact `LanguageCapability` metadata.
- The SDK-bound Java catalogue lists canonical registered semantic mutation IDs
  and publishes per-operation stability, mutation authority, and evidence.
- Daemon managed Apply rejects registered `PROPOSAL_ONLY` plans before writes or
  transaction creation.
- `java.moveAcrossMavenModules` remains on its separately qualified Maven project
  operation route and is excluded from editor/SDK semantic refactoring discovery.
- The existing Maven operation and rollback behavior, API method set, dependencies,
  other BDD scenarios, and independent P1 qualification requirements are unchanged.
- The slice limit is exactly 13 changed files; no further budget increase is
  authorized by this receipt.

The [ARC42 requirements appendix](../arc42/appendix-requirements.adoc) remains the
integrated architecture authority. Focused acceptance, glue inventory, hashes,
and regression outcomes are recorded in the local hash-bound receipt at
`.refactorkit/runs/java-capability-truth-r001/gherkin-receipt.json`.
