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

Operation-specific limits are documented in [refactoring engine operation
links](refactoring-engine.md#operation-documentation).
