# Move class

This operation changes package identity and updates package/import/FQN references.
For a byte-identical same-package move between module source roots, use
[`move-source-root`](move-source-root.md); do not overload `moveClass`.

Status: implementation-informed through the bounded `v0.7.0` Maven authority
rows. Clean exact JDT analysis scopes managed package/path/import/FQN edits to
binding-matched referencing files. Safely enumerable Maven authority defects
return non-managed `REVIEW_ONLY_GUIDANCE`; broad unclean or unresolved analysis
returns edit-free schema-v1 `LEXICAL_FALLBACK_REVIEW`, not an applyable plan.

## Command

```bash
refactorkit move-class \
  --symbol com.example.UserManager \
  --to-package com.example.account \
  <root>
```

Add `--apply` only after reviewing an eligible JDT-backed preview. Guidance and
lexical-review results always refuse apply before `PatchEngine`, lock, or WAL.

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
- the target FQN or computed target source file already exists;
- a root-declared active Maven module POM is missing; or
- selected reactor/descriptor structure is incomplete.

A refused move is a safety result. Do not emulate it with filesystem moves plus
text replacement; request a corrected preview or perform a manually reviewed
change outside the deterministic workflow.

## Warnings and manual-review areas

Move class is medium risk by default and high risk when recognized framework
annotations are found. Review these areas:

- source-root detection for the new path is heuristic and should be checked in
  the affected file list;
- parse/classpath warnings may remove managed authority; broad uncertainty is
  reported as `semanticCompleteness=NOT_SEMANTICALLY_PROVEN` with bounded
  path/hash/risk residual guidance and fixed restoration actions, but no exposed
  diff, source range, replacement, plan ID, or apply token;
- bounded ambiguous candidates, unresolved target-name lookup prerequisites,
  explicit old-FQN uses outside the observer closure, and same-snapshot retained-
  diagnostic identity changes return typed `REVIEW_ONLY_GUIDANCE`;
- non-managed candidate-source evidence used by a target-scoped plan is
  hash-bound and revalidated under the workspace lock; drift refuses before WAL
  and preserves the external change;
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

Only eligible JDT-backed moves can be applied and are transaction-backed.
Rollback can restore the modified package
declaration/import edits and rename the file back when the transaction is still
valid. Rollback does not repair downstream code, generated sources, external
configuration, or manual edits made after apply. Run diagnostics and relevant
build/tests after apply and rollback.
