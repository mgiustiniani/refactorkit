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
  package/import preservation, and non-Java Markdown fence stripping;
- CLI, daemon JSON-RPC, and MCP tests now assert that importer preview output
  exposes the stable provenance/license fields.

## Provenance/license output contract

Every external import preview or refusal must expose one warning line with these
stable field names:

```text
Provenance: sourceKind=... sourceUrl=... retrievedAt=... licenseDetected=... licenseRisk=... originalHash=...
```

Field meanings:

- `sourceKind`: `CLIPBOARD`, `URL`, `FILE`, `LLM`, or `SNIPPET`;
- `sourceUrl`: source URL when known, otherwise `(none)`;
- `retrievedAt`: ISO-8601 retrieval/planning timestamp;
- `licenseDetected`: detector result such as `MIT`, `Apache-2.0`, `BSD`, `GPL`,
  `LGPL`, `MPL`, `copyright-notice`, or `unknown`;
- `licenseRisk`: `LOW`, `MEDIUM`, `HIGH`, or `UNKNOWN`;
- `originalHash`: SHA-256 hash of the cleaned source text.

Users see this line in:

- CLI `refactorkit java import-class` preview output under warnings;
- daemon JSON-RPC `java.importExternalClass` responses in the `warnings` array;
- MCP `import_external_java_class` tool output text under `Warnings`.

The fields support audit review only. They do not prove license compatibility or
replace human provenance review before committing imported external code.

## Operation safety summary

Success conditions for a preview:

- source code is acquired from the declared source kind and cleaned of Markdown
  fences when needed;
- the target package is valid;
- top-level Java types can be detected and mapped to target files;
- package declarations are rewritten and imports are organized;
- no target naming conflict is found in the snapshot or on disk;
- provenance and license-risk warnings are visible to the reviewer.

Refusal conditions include invalid target packages, no importable Java type,
naming conflicts, overwrite risk, and license-policy blocks such as
`block-unknown` for unknown licenses. A refused import must not be replaced by
silently writing files into the project.

Warnings require manual review for unknown or risky licenses, source provenance,
unresolved external imports, copied helper types, multiple public-type splitting,
and any dependency that would need to be added separately. External import is
code assimilation, not a semantic refactoring.

Rollback expectations: if an approved preview is applied through `PatchEngine`,
created files and edits are transaction-backed and can be rolled back. Rollback
does not remove legal/provenance obligations, undo dependency additions made
outside the plan, or repair manual edits after apply.

Remaining gaps:

- dependency resolution is warning-only;
- import unusedness is not type-resolved;
- class renaming/merge conflict workflows are not implemented yet;
- refusal wording should continue to emphasize user review instead of overwrite;
- applying the preview is still delegated to `PatchEngine`/CLI/MCP after user approval.
