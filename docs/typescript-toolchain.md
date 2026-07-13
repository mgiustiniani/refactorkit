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

Stable support still requires license/SBOM policy, real-toolchain packaged native
crash/recovery acceptance and a supported server-version matrix.
