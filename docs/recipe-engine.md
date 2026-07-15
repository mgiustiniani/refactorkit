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
- diagnostics compare baseline and staged ERROR diagnostics and report baseline,
  staged, introduced, resolved, and unchanged counts; normal mode refuses only
  introduced errors, while `strict: true` requires a completely clean staged
  result;
- `movePackage` moves exact-package compilation units as one structural plan,
  including package-private types, tests, and `package-info.java`, while leaving
  subpackage declarations in place;
- generated sources and build outputs are excluded before planning, and an
  unqualified recipe `organizeImports` step is restricted to touched files whose
  package/import surface changed;
- package migration updates imports, fully qualified type references, exact
  package-name architecture-contract literals, and exact moved-unit Java source
  paths; arbitrary strings and non-Java build/configuration files remain out of
  scope;
- recipe-wide reduction and preview evaluate modifications after renames against
  the staged file image, avoiding stale source offsets.

Limitations:

- recipe parameters are string-substituted templates only;
- validation hooks are limited to built-in step validation and `runDiagnostics`;
- recipe-wide rollback follows the standard conflict-safe `PatchEngine` policy;
- whole-file replacement edits are currently used when reducing staged content to
  the final recipe-wide delta;
- package migration is structural evidence in API `0.2`; it is not a claim of
  compiler-authoritative Maven module, artifact, script, documentation, or
  configuration migration.
