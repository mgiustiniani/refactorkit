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
- each mutating step produces a `PatchPlan` before application;
- apply mode saves transaction logs under `.refactorkit/transactions`;
- if a later step refuses or fails after earlier steps applied, previously applied
  recipe steps are rolled back in reverse order where possible;
- diagnostics steps refuse recipes when error diagnostics are present.

Limitations:

- recipe parameters are string-substituted templates only;
- validation hooks are limited to built-in step validation and `runDiagnostics`;
- multi-step rollback is best-effort if the workspace is externally modified while
  a recipe is running.
