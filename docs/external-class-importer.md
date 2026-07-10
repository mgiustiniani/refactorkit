# External class importer

See AGENTS.md for the authoritative initial architecture and implementation rules.

## Current MVP behavior

`ExternalJavaClassImporter` produces preview-only `PatchPlan`s for importing Java
source copied from a clipboard, file, URL, snippet, or LLM response.

Implemented safety checks:

- strips Markdown fences, including non-`java` fenced snippets with surrounding
  prose;
- detects MIT/Apache/GPL/unknown license risk;
- records provenance warnings with source kind, URL, retrieval time, license,
  and source hash;
- reports GPL-family imports as high license risk;
- validates target package names;
- detects public top-level classes/interfaces/enums/records;
- rewrites package declarations;
- splits multiple public top-level types into separate files while preserving imports;
- preserves one-public-type files with package-private helper types;
- selects `targetModule` source roots when a scanned project snapshot is available;
- refuses naming conflicts found in the snapshot or on disk;
- creates files with `overwrite=false`;
- sorts/deduplicates imports and removes same-package imports;
- warns about likely external unresolved imports.

Beta P6 hardening evidence:

- focused importer tests now cover provenance warning fields, GPL high-risk
  warnings, single-file helper preservation, multi-public-type splitting with
  package/import preservation, and non-Java Markdown fence stripping.

Remaining gaps:

- dependency resolution is warning-only;
- import unusedness is not type-resolved;
- class renaming/merge conflict workflows are not implemented yet;
- CLI/JSON-RPC/MCP output documentation still needs to spell out license-risk
  and provenance fields;
- refusal wording should continue to emphasize user review instead of overwrite;
- applying the preview is still delegated to `PatchEngine`/CLI/MCP after user approval.
