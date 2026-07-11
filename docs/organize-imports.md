# Organize imports

Status: implementation-informed for `v0.3.0`. Organize imports is a
low-risk Java import-block cleanup. Clean JDT binding evidence additionally
removes exact imports proven unused; unclean files retain lexical cleanup only.

## Command

```bash
refactorkit organize-imports \
  src/main/java/com/example/UserManager.java \
  --root <root>
```

Multiple files may be passed. Add `--apply` only after preview review when the
caller expects writes.

## Success conditions

A preview can change a file when:

- the file is present in the scanned snapshot;
- the file has a Java import block;
- duplicate imports or same-package imports can be removed, imports can be
  sorted into RefactorKit's import order, or clean JDT binding evidence proves
  that an exact import has no non-import use;
- the preview affected-file list matches the files that need changes.

Current ordering groups non-static imports as `java.*`, `javax.*`, `jakarta.*`,
`org.*`, `com.*`, then everything else. Static imports are placed last in their
own group.

If a file has no imports or the imports are already clean, the preview may be a
no-op with no affected files. Clients should present this as no changes needed,
not as a failed refactoring.

## Refusal and no-op conditions

The current planner normally produces a preview rather than a refused plan. It
will simply skip paths that are not present in the snapshot and files without a
modifiable import block. Treat unexpected zero-file previews as an input or scan
problem and rescan before applying.

## Warnings and manual-review areas

- Exact unused imports are removed only when their declaration-normalized JDT
  binding has no non-import use in a clean file. Parameterized type and generic
  static-method uses are matched to the imported declaration binding.
- Wildcard and unresolved imports are preserved. Files with JDT parse/classpath
  errors explicitly fall back to sorting, deduplication, and same-package removal
  without unused-import removal.
- Import comments and unusual formatting around the import block should be
  reviewed in the diff.
- Exact static imports participate in binding-backed usage checks. Wildcard
  imports, annotation-processor behavior, and generated sources remain manual
  review areas.
- The operation does not add missing dependencies or resolve unresolved types.

## Rollback expectations

Applied import cleanups are transaction-backed and can be rolled back like other
file modifications. Rollback restores the previous import block tracked by the
transaction, but does not undo external formatter runs or manual edits made after
apply. Diagnostics or a project build should still be run after apply when import
changes may affect compilation.
