# Move class

This operation changes package identity and updates package/import/FQN references.
For a byte-identical same-package move between module source roots, use
[`move-source-root`](move-source-root.md); do not overload `moveClass`.

Status: implementation-informed for `v0.3.0`. Move class previews
package/path/import updates for one Java type. Clean JDT analysis scopes edits to
binding-matched referencing files; unclean analysis reports lexical file scoping.

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

- the target symbol is a discovered Java class, interface, enum, record, or
  annotation type;
- the target package differs from the current package;
- the declaration source file exists in the snapshot;
- the preview rewrites or inserts the package declaration in the moved file;
- the preview renames/moves the source file to the target package path;
- direct imports and fully qualified references to the old FQN are updated;
- same-package source files that actually reference the selected type receive an
  import for the new FQN when needed;
- unrelated same-package files and same-simple-name types in other packages remain
  unchanged when JDT binding evidence is clean;
- direct import and nested FQN edits do not overlap.

## Refusal conditions

The planner refuses when:

- source and target packages are the same;
- the symbol is missing or is not a moveable Java type;
- the declaration file cannot be found in the current snapshot;
- the target package is invalid;
- the target FQN or computed target source file already exists.

A refused move is a safety result. Do not emulate it with filesystem moves plus
text replacement; request a corrected preview or perform a manually reviewed
change outside the deterministic workflow.

## Warnings and manual-review areas

Move class is medium risk by default and high risk when recognized framework
annotations are found. Review these areas:

- source-root detection for the new path is heuristic and should be checked in
  the affected file list;
- parse/classpath warnings cause explicit lexical file scoping, which requires
  additional review;
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
