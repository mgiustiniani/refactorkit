# Recipe engine

See AGENTS.md for the authoritative architecture and implementation rules.

## Current MVP behavior

`modules/refactorkit-java` includes a YAML recipe engine for deterministic Java
refactoring workflows.

Supported step types:

- `renameClass`
- `moveClass`
- `movePackage`
- `organizeImports`
- `safeDelete`
- `runDiagnostics`
- `summarizePatch`

Safety behavior:

- recipes support dry-run preview mode;
- recipe language is validated and currently limited to `java`;
- required parameters are validated;
- parameter defaults are supported;
- simple parameter types are validated: `string`, `boolean`, `integer`/`int`;
- each mutating step produces a `PatchPlan` and is evaluated against the staged
  result of all previous steps without writing the workspace;
- all successful steps are reduced to one recipe-wide `PatchPlan` from the initial
  snapshot to the final staged snapshot;
- apply mode performs exactly one managed `PatchEngine` transaction and saves one
  lifecycle record under `.refactorkit/transactions`;
- later-step refusal or failure occurs before journaling and leaves the workspace
  and transaction log untouched;
- no-op recipes create no transaction;
- diagnostics steps run against the evolving staged snapshot and refuse recipes
  when error diagnostics are present;
- `movePackage` stages each class move sequentially, avoiding stale same-file
  coordinate merges.

Limitations:

- recipe parameters are string-substituted templates only;
- validation hooks are limited to built-in step validation and `runDiagnostics`;
- recipe-wide rollback follows the standard conflict-safe `PatchEngine` policy;
- whole-file replacement edits are currently used when reducing staged content to
  the final recipe-wide delta.
