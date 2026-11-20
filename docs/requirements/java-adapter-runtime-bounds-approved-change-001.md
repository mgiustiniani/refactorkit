# Java adapter runtime bounds — approved change 001

- Change ID: `REQ-JAVA-RUNTIME-BOUNDS-001-APPROVED-CHANGE-001`
- Parent requirement: P0 “Bound Java adapter runtime” in `integration-contracts.md`
- Approval authority: the user's explicit instruction, “ok colma il gap”.
- Scope: one requirement-first Story BDD scenario for the Java daemon runtime, its
  enforcement and capability projection, and the central/backlog documentation.

## Approved budget override

The default §18.6 changed-file maximum of 8 is increased to exactly 11 tracked
workspace files for this slice. The override is required to keep transport claims
truthful across the shared capability schema and to update both the ARC42 authority
and this approved-change history. The slice remains one active scenario, one
expanded case and four new step definitions. The hash-bound inventories and test
reports under `.refactorkit/runs/java-runtime-bounds-r001` are local generated
evidence, not additional source/documentation changes.

## Approved delta

- The Java daemon descriptor declares its 2 MiB complete JSON-RPC response-frame
  ceiling; the encoder returns a bounded JSON-RPC error instead of truncating an
  oversized success response.
- The six daemon JDT semantic-read capabilities declare the enforced 256 MiB
  aggregate Java-source input bound and cooperative cancellation. JDT AST parsing
  receives a progress monitor tied to the request token; cancelled analyses are
  not cached.
- The adapter-wide descriptor remains in-process, without hard timeout, process
  preemption, process provenance or a process-count claim. Cancellation is not
  generalized to mutation operations.
- The shared CLI/LSP/MCP descriptor does not claim daemon response bounds or
  cancellation; those transport-specific gaps remain actionable.
- ARC42 records integrated status and transport limits; the IDE backlog records
  the completed daemon slice and remaining transport work.

The [ARC42 requirements appendix](../arc42/appendix-requirements.adoc) remains the
integrated architecture authority. The exact feature identity and acceptance
evidence are recorded in the local hash-bound receipt at
`.refactorkit/runs/java-runtime-bounds-r001/gherkin-receipt.json`.
