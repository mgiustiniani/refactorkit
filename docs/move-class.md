# Move class

Status: implementation-informed for `v0.2.0-beta` documentation. Move class is a
lexical Java operation that previews package/path/import updates for one Java
type.

## Command

```bash
refactorkit move-class \
  --symbol com.example.UserManager \
  --to-package com.example.account \
  <root>
```

Add `--apply` only after preview review.

## Success conditions

A `moveClass` preview is considered safe enough to review when:

- the target symbol is a discovered Java class, interface, enum, or record;
- the target package differs from the current package;
- the declaration source file exists in the snapshot;
- the preview rewrites or inserts the package declaration in the moved file;
- the preview renames/moves the source file to the target package path;
- direct imports and fully qualified references to the old FQN are updated;
- same-package source files that previously used the simple name receive an
  import for the new FQN when needed.

## Refusal conditions

The planner refuses when:

- source and target packages are the same;
- the symbol is missing or is not a moveable Java type;
- the declaration file cannot be found in the current snapshot.

A refused move is a safety result. Do not emulate it with filesystem moves plus
text replacement; request a corrected preview or perform a manually reviewed
change outside the deterministic workflow.

## Warnings and manual-review areas

Move class is medium risk by default and high risk when recognized framework
annotations are found. Review these areas:

- source-root detection for the new path is heuristic and should be checked in
  the affected file list;
- comments and string literals are not rewritten;
- reflection, generated code, annotation-processor output, `ServiceLoader`,
  native-image config, and resource files may still contain the old FQN;
- Spring component scanning, conditional package checks, JPA entity scanning,
  persistence-unit configuration, Jackson type metadata, XML/YAML/properties, and
  migrations may depend on packages; see [framework-aware Java
  refactoring](framework-awareness.md);
- build files and test configuration are not rewritten;
- downstream consumers outside the workspace may import the old package.

## Rollback expectations

Applied moves are transaction-backed. Rollback can restore the modified package
declaration/import edits and rename the file back when the transaction is still
valid. Rollback does not repair downstream code, generated sources, external
configuration, or manual edits made after apply. Run diagnostics and relevant
build/tests after apply and rollback.
