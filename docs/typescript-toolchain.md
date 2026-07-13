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
- the exact future LSP command vector (`node`, language-server entrypoint,
  `--stdio`).

No environment value, credential or package script is serialized. Evidence must
be hash-bound into the future TypeScript project snapshot before stable preview
or apply.

## Current refusal boundary

This slice does not yet launch the language server for project semantics. Stable
support still requires bounded JSONC project modeling, source/config overlay,
document synchronization, capability negotiation, TypeScript version selection,
exact diagnostics, proposal normalization, managed mutation acceptance, license/
SBOM policy and native hostile-workspace testing.
