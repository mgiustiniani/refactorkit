# TypeScript/JavaScript declarative project model

Status: `v0.6.0` T2 bounded model implemented; compiler-backed semantic use is T3.
The local 0.7.0 snapshot-model and auxiliary-input integration below also support
the bounded T5 migration, not general directory/package mutation authority.
Architecture authority remains in
[ARC42](arc42/appendix-requirements.adoc); this document describes the model API.

Provider ID: `typescript-config-declarative-v1`.

## Discovery boundary

The provider walks the workspace without following symbolic links, ignores build,
VCS, cache and `node_modules` directories, and discovers at most 256
`tsconfig.json`/`jsconfig.json` roots. It does not invoke Node, TypeScript,
package managers, bundlers, plugins or build scripts.

Each config is limited to 1 MiB. JSONC line/block comments and trailing commas
are normalized with string-aware parsing before strict JSON decoding. Unterminated
comments/strings, malformed shapes, unsafe paths and bounded-limit violations
produce typed `typescript.*` root-cause diagnostics.

## Effective configuration

The initial declarative subset models:

- workspace-relative `extends`, depth-limited to 16 with cycle refusal;
- project `references`, including custom config filenames, missing-target and
  reference-cycle refusal;
- `files`, `include`, and `exclude` with their declaring base directory;
- `allowJs`, `checkJs`, `declaration`, `composite`, `rootDir`, `outDir`,
  `baseUrl`, `jsx`, `module`, and `moduleResolution`;
- bounded `paths` aliases and targets;
- nearest strict bounded `package.json` `type` (`module`/`commonjs`) plus
  publication markers for `exports` and `types`/`typings`;
- TypeScript versus JavaScript config identity.

Package-based `extends` is deliberately refused in this slice because resolving
it would cross the explicit package/toolchain trust boundary. Absolute,
workspace-escaping, symlink-traversing and malformed cross-host paths are refused.
Glob patterns are modeled but not executed or expanded by the provider.

## Build Model SPI

`TypeScriptBuildModelProvider` projects effective configs into language-neutral
`BuildModel` modules/source sets:

- module identity is config-path based;
- `rootDir` (or config directory) becomes the source root;
- `outDir` becomes an output directory;
- project references become compile-scope module edges;
- config kind, JS checking, package type/publication markers and config path are typed attributes;
- network, credentials and build-code execution remain `denied` even if caller
  allowances are broader.

`TypeScriptBuildModelIntegration.attach` inserts or replaces this provider model
inside `ProjectSnapshot` and captures exact config/extends/package evidence as
auxiliary JSONC files when a source-only scan omitted it. Capture uses bounded
UTF-8, size/hash checks and no-follow paths; existing snapshot bytes must match.
Capture failure returns an unavailable `typescript.modelEvidenceChanged` model,
not partially captured new authority. The model, evidence and auxiliary bytes
participate in the engine-owned snapshot hash. Later preview/apply flows must
rediscover the provider so config/package drift fails before WAL.

## Immutable snapshot modeling

`TypeScriptProjectModelBuilder.build(root)` discovers the current disk image.
`build(snapshot)` instead uses only the exact files supplied by that snapshot,
through the existing source-only semantic overlay, and removes the overlay after
modeling. Callers must capture configuration, extends inputs and package manifests
alongside sources; absent configuration is not borrowed from the old workspace.
Relative paths, configuration byte hashes and the reference graph consequently
belong to the staged image rather than a different on-disk layout.

The [advanced-operation Story](../features/typescript-advanced-operations.feature)
checks a relocated authored post-image, deterministic projection/evidence, clean
real compiler diagnostics, and missing/cyclic/escaping/malformed configuration
refusals with unchanged disk bytes. An authored post-image is model-test input,
not compiler authority to perform the migration. The separately implemented
`projectReferenceMigration` supplies that authority only for one complete sibling
project: native directory import edits, parsed exact JSONC `references[].path`
origins, expected before/staged graph/options and the retained owning compiler
gate. It rejects uncaptured files, occupied destinations, nested projects,
ambiguous origins, unsupported extends/aliases/publication markers and changed
apply authority. Native configuration suggestions are not configuration authority.
See the [bounded migration evidence](releases/v0.7.0-t5-local-safety.md); npm package
rename, arbitrary directory moves and generic multi-mutation recipes are not
qualified by this path.

## Evidence and limits

Every config, extends input and nearest package manifest records workspace-relative
path, exact byte size and SHA-256. The deterministic projection hash uses portable
`/` paths and sorted projects/evidence/options. Defaults additionally limit 256
references per config, 4,096 file patterns, 512 aliases, and 128 targets per
alias.

This model does not itself grant TypeScript mutation authority. Stable operations
still require the explicit semantic toolchain, synchronized overlays, compiler
identity/references/diagnostics, normalized proposals and native managed
apply/rollback acceptance.
