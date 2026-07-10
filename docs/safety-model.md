# Safety model

See AGENTS.md for the authoritative initial architecture and implementation rules.

## Beta operation workflow

For every mutating operation:

1. Scan the workspace and identify the exact symbol or file.
2. Generate a preview and inspect affected files, diagnostics, warnings, risk,
   and confidence.
3. Treat `REFUSED` as a completed safety outcome. Do not fall back to global
   text replacement, raw file deletion, or unreviewed manual moves.
4. Apply only after review and only through RefactorKit so a transaction is
   recorded.
5. Run diagnostics and relevant tests after apply.
6. Roll back by transaction ID if verification fails or the change is rejected.

Rollback restores files tracked by the transaction. It does not repair external
projects, generated artifacts, build caches, legal/provenance obligations, or
manual edits made after apply.

## Beta preview warnings and manual review

For `v0.2.0-beta`, preview-warning wording has been reviewed against the
operation documentation for the shipped and experimental Java operations. The
warnings are part of the safety contract: a preview is review evidence, not an
automatic proof that the change is complete.

Manual review remains mandatory for these beta warning categories:

- lexical and structural Java analysis limits, including overloads, inheritance,
  interface dispatch, generated sources, annotation processors, and unresolved
  type information;
- string-literal, comment, reflection, framework-configuration, build-file,
  external-configuration, test, and unknown downstream references that a preview
  may not fully discover;
- framework contract risks for Spring, JPA, and Jackson usage where component,
  entity, table, column, qualifier, route, or serialization names may form an
  external contract;
- large or cross-module rename, move, delete, extract, or signature-change plans
  where affected-file counts, diagnostics, and warnings must be inspected before
  apply;
- external Java imports, where provenance, license risk, unresolved imports,
  naming conflicts, and overwrite refusal must be reviewed before any apply.

Operation-specific limits are documented in [refactoring engine operation
links](refactoring-engine.md#operation-documentation). Refused plans are safe
outcomes; agents and users must not bypass them with global text replacement or
untracked filesystem edits.
