# TypeScript/JavaScript semantic toolchain boundary

Status: first `v0.6.0` discovery/provenance slice; no stable TypeScript mutation
authority yet.

## Provider identity

The initial provider is `typescript-lsp-explicit-v1`. It requires three explicit
inputs:

- a Node.js executable;
- a `typescript-language-server` package root;
- a `typescript` package root.

Default discovery does not search `PATH`, inspect package-manager global stores,
read npm credentials/configuration, download packages, execute package managers,
or execute project/package scripts. Optional `PATH` lookup is a caller policy and
still records the canonical executable.

Workspace-local Node or package roots are refused by default. Enabling
`allowWorkspaceLocalToolchain` is an explicit code-execution trust decision; it
does not make package scripts executable and does not grant mutation authority.

## Bounded discovery

Discovery performs only declarative reads of bounded package metadata and
critical entrypoints. It validates:

- non-symlink canonical files/directories;
- package names `typescript-language-server` and `typescript`;
- strict semantic versions;
- Node major 18–24 and TypeScript major 5 by the initial policy;
- canonical package-confined language-server `bin` entry;
- canonical `lib/tsserver.js`;
- maximum 64 KiB package manifests, 32 MiB JS entrypoints, and 512 MiB Node
  executable.

The only discovery-time execution is the explicitly selected Node binary with the
constant argument `--version`. It runs through `ExternalSemanticProcessManager`
with cleared environment, 2-second timeout, 1 KiB stdout, 4 KiB stderr and
process-tree cancellation. No JavaScript file is passed to this probe.

## Provenance

An available toolchain records:

- provider ID;
- normalized Node, language-server and TypeScript versions;
- canonical Node and entrypoint paths;
- SHA-256 and size for Node, both package manifests, the language-server
  entrypoint and TypeScript `tsserver.js`;
- the exact LSP command vector (`node`, constant 512 MiB V8 old-space limit,
  language-server entrypoint, `--stdio`).

No environment value, credential or package script is serialized. Evidence must
be hash-bound into the future TypeScript project snapshot before stable preview
or apply.

## Current refusal boundary

The experimental semantic adapter launches this command only after project-model
and snapshot evidence agree. Crashed sessions require explicit restart, allow at
most three attempts per rolling 60 seconds, and must preserve server version,
capability hash, executable hash and argument hash. The V8 old-space flag bounds
the primary JavaScript heap but is not an operating-system RSS sandbox.

## Qualification matrix and package policy

Native CI now installs the lockfile-pinned qualification pair with
`npm ci --ignore-scripts --no-audit --no-fund` after `actions/setup-node` selects
Node `22.18.0`:

| Node | `typescript-language-server` | TypeScript | Qualification |
|------|------------------------------|------------|---------------|
| 22.18.0 | 5.1.3 | 5.9.3 | Linux x86-64, Windows x86-64, macOS x86-64 and arm64 packaged read operations and fail-closed mutation boundary |

Both packages declare Apache-2.0. Their exact npm integrity hashes and transitive
package graph are committed in
`qualification/typescript-toolchain/package-lock.json`; they are CI inputs only
and are not bundled into RefactorKit runtime archives or runtime SBOMs. Runtime
discovery never invokes npm or reads npm configuration. CI is the only networked
acquisition path, uses the public npm registry selected by setup-node, disables
package scripts, and runs before the hostile sample workspace is opened.

The real server is passed the already hash-bound `lib/tsserver.js` path through
strict JSON `initializationOptions.tsserver.path`; no workspace TypeScript lookup
is trusted. Real servers lazily open projects, so RefactorKit opens every bounded
source document before compiler document/workspace symbol requests.

Upstream `typescript-language-server` 5.1.3 publishes diagnostics without LSP
`version`, even when the client advertises `publishDiagnostics.versionSupport`.
RefactorKit deliberately rejects those publications as
`semantic.diagnosticsIncomplete`. Native qualification therefore proves stable
search/definition/references and that rename reaches the exact diagnostics gate
without creating a WAL, but does **not** promote managed mutation support. Stable
apply/WAL/recovery/rollback remains blocked until a qualified diagnostics provider
can prove the exact snapshot version.

Stable support also requires native crash/recovery acceptance and expansion of the
supported server-version matrix.
