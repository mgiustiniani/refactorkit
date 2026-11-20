# C parameter-rename CLI preview — approved change 001

- Change ID: `REQ-C-PARAMETER-RENAME-CLI-001-APPROVED-CHANGE-001`
- Parent requirement: C10, function signature changes in `docs/releases/v0.8.0-plan.md`
- Recorded at: `2026-10-26`
- Approval authority: the user's explicit “ok colma il gap” and prior numeric
  BDD budget approval, “Override BDD 10 file”.
- Scope: one Cucumber Story scenario, its required C function-symbol routing and
  compiler-bound parameter-family preview, the shared-runner selector, default
  tag allowlist, and integrated release/ARC42 status.

## Approved budget override

The §18.6 changed-file maximum is increased from eight to exactly ten files for
this slice. The ten-file ceiling includes the local hash-bound Gherkin receipt
and this approved-change record. The reason is the minimum wiring needed to
change the planner and facade together, add one requirement-first feature and
its three steps, select it through the existing shared runner and default test
tag filter, and update both the release register and integrated ARC42 narrative.
No per-slice runner or dependency is added.

The Story remains one active scenario with two expanded examples and three new
step definitions. The pre-edit inventory must show no ambiguous effective glue;
after editing, all three steps must resolve exactly with no duplicate expressions.

## Approved delta

- The public C CLI's rename-parameter operation requires `--symbol` and passes
  the selected function name into the C planner.
- The planner uses captured Clang facts to admit only a complete file-static,
  fully prototyped, non-variadic integer family, selects one parameter ordinal,
  merges clangd's binding edits with compiler-owned declaration-name edits, and
  rechecks the staged family before returning a preview.
- The Story covers a same-spelled unrelated parameter with a Unicode/CRLF source
  prefix and a function family with unproven external consumers.
- Parameter-rename managed Apply/rollback, other parameter types, broader family
  and configuration matrices, packaging, independent acceptance, and the full
  C10/C01-C17 objective remain open.

The receipt and inventories are at
`.refactorkit/runs/v080-c-parameter-rename-cli-r001/gherkin-receipt.json`.
