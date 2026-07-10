# Refactoring engine

See AGENTS.md for the authoritative initial architecture and implementation rules.

## Operation documentation

The beta operation docs explain success conditions, refusal conditions, warnings,
and rollback expectations:

- [Rename class and member](rename.md)
- [Move class](move-class.md)
- [Organize imports](organize-imports.md)
- [Safe delete](safe-delete.md)
- [Extract method MVP](extract-method.md)
- [Change Signature MVP+](change-signature.md)
- [External class importer](external-class-importer.md)

All mutating operations are preview-first. A refused plan is a safety result and
must not be bypassed with global search-and-replace or direct filesystem edits.

## Related decisions

- [ADR 0002: PatchPlan and PatchEngine safety model](adr/0002-patchplan-patchengine-safety-model.adoc)
- [ADR 0003: Java lexical MVP before compiler-backed analysis](adr/0003-java-lexical-mvp-before-compiler-analysis.adoc)
- [ADR 0007: Lexical Java MVP vs compiler-backed analysis before v1.0.0](adr/0007-lexical-java-mvp-vs-compiler-analysis.adoc)
