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
- selects legacy `targetModule` source roots when a scanned project snapshot is available;
- resolves an existing workspace-relative `targetDirectory` through the scanned
  Java module/source-root model, deriving source set and exact package without
  frontend path heuristics;
- refuses naming conflicts found in the snapshot or on disk;
- creates files with `overwrite=false`;
- sorts/deduplicates imports and removes same-package imports;
- warns about likely external unresolved imports.

Beta P6 hardening evidence:

- focused importer tests now cover provenance warning fields, GPL high-risk
  warnings, single-file helper preservation, multi-public-type splitting with
  package/import preservation, and non-Java Markdown fence stripping;
- CLI, daemon JSON-RPC, and MCP tests now assert that importer preview output
  exposes the stable provenance/license fields;
- refusal-message tests now cover unknown-license blocking and naming-conflict
  refusals with guidance that no files were written, reviewers should check
  provenance/license policy/naming conflicts, and RefactorKit never overwrites
  existing files by default.

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

## `targetDirectory` versus `targetPackage`

`targetDirectory` is a physical, existing workspace-relative directory such as
`module-a/src/main/java/com/example/util`. It is never interpreted as a Java
package. `JavaImportTargetResolver` canonicalizes it against the opened workspace,
requires one unambiguous module/source-root owner, classifies `MAIN`, `TEST`,
`GENERATED`, or `CUSTOM`, validates every source-root-relative segment as a Java
identifier, and derives `com.example.util`. Generated targets are refused because
they are analysis-owned rather than user-maintained.

`targetPackage` is the legacy logical package selector. The importer retains its
existing module/source-root selection behavior for compatibility. When both are
present, `targetPackage` and `targetModule` are assertions checked against the
directory-derived result; mismatches refuse rather than choosing one silently.
The source-root directory itself maps to the default package.

Directory resolution rejects absolute and foreign Windows-absolute paths,
`..`, missing paths, files, workspace/symlink escapes, every symlink component
(which matches managed apply path policy), paths outside recognized Java roots,
overlapping source roots, and invalid package directories. It never creates a
missing directory. Resolution refusals occur before source parsing and do not
include source text.

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
silently writing files into the project. Its warning guidance should state that
no files were written, point reviewers to provenance, license policy, or naming
conflicts as applicable, and remind them that RefactorKit never overwrites
existing files by default.

Warnings require manual review for unknown or risky licenses, source provenance,
unresolved external imports, copied helper types, multiple public-type splitting,
and any dependency that would need to be added separately. External import is
code assimilation, not a semantic refactoring.

Daemon previews retain the exact plan under `planId`; `refactor.apply` delegates
that plan unchanged to `PatchEngine`, returns changed/primary files and a WAL
transaction ID, and refreshes project state. `patch.rollback` restores/removes
its journaled paths. No importer or daemon code writes project files directly.
Rollback
does not remove legal/provenance obligations, undo dependency additions made
outside the plan, or repair manual edits after apply.

Remaining gaps:

- dependency resolution is warning-only;
- import unusedness is not type-resolved;
- class renaming/merge conflict workflows are not implemented yet;
- CLI/MCP directory-target parity is not yet advertised; this additive contract
  is currently the daemon IDE workflow;
- custom/generated source-root discovery is limited to roots represented by the
  current `ProjectSnapshot`; generated targets are deliberately refused.
