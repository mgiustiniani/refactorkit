# Compatibility and deprecation policy

Status: draft for the `v0.3.0` P1 documentation slice.

This policy explains how RefactorKit documents compatibility before `v1.0.0`.
It applies to documented CLI, daemon JSON-RPC, LSP, and MCP integration
surfaces. It does not freeze internal code or experimental behavior.

## Implementation version vs API version

RefactorKit exposes two related but different versions:

- **Implementation version**: the build/release version of the executable or
  server, for example `0.3.0-SNAPSHOT` or `0.3.0`. It changes when the software
  changes.
- **API version**: the documented integration-contract baseline, currently
  `0.2`. It changes only when the compatibility baseline for documented
  beta-contract integration surfaces changes.

A new implementation version can keep the same API version when it remains
compatible with the documented baseline. Clients should prefer API-version checks
where the surface exposes them and use implementation versions for diagnostics,
support, and minimum-version requirements.

## SemVer before `v1.0.0`

RefactorKit uses SemVer-style release names, but `0.x` releases are still
pre-`1.0.0`:

- `0.MINOR.PATCH` may add, revise, or remove behavior before the stable API
  freeze.
- Documented `beta-contract` surfaces should not break within the same API
  baseline unless a migration note is published before release.
- Patch releases should avoid breaking documented beta-contract behavior.
- Snapshots such as `0.3.0-SNAPSHOT` are development builds and are not stable
  release promises.
- `v1.0.0` is the intended point for a stronger stable API guarantee.

## Stability labels

| Label | Compatibility meaning |
|-------|------------------------|
| `beta-contract` | Documented external contract for beta users. Names, required parameters, response envelopes, error categories, and preview/apply/rollback safety semantics should remain compatible within the active API baseline. Breaking changes require migration notes. |
| `experimental` | Available for pilots, but command names, fields, output shape, or behavior may change before `v1.0.0`. Changes still need release notes when user-visible. |
| `internal` | Not intended for external clients. May change without deprecation or migration support. |

## Deprecation process

Deprecation is preferred over immediate removal for `beta-contract` surfaces when
practical. A deprecation notice should identify the old surface, the replacement,
and the planned removal or compatibility-review release.

### CLI commands and options

- Keep deprecated commands/options callable for at least one documented release
  when feasible.
- Print a concise warning on use, ideally including the replacement command or
  option.
- Keep `--help` and integration docs clear about preferred replacements.
- Do not silently change the meaning of an existing option.

### Daemon JSON-RPC methods, fields, and errors

- Keep deprecated methods or fields accepted for at least one documented release
  when feasible.
- Prefer additive replacement fields before removing or renaming response data.
- Return structured warnings or documented error data where the protocol shape
  allows it.
- Preserve documented error categories unless the migration note explains the new
  category and client handling.

### LSP commands and metadata

- Keep deprecated `workspace/executeCommand` command names as aliases where
  practical.
- Preserve preview metadata needed by clients to review and apply exact plans,
  especially plan id, operation, status, summary, risk level, warnings, and
  workspace edit data.
- Do not remove `serverInfo.version`; clients use it for implementation-version
  checks and support diagnostics.

### MCP tools, resources, and prompts

- Keep deprecated tools/resources/prompts available as aliases where practical.
- For tools, prefer additive parameters and output fields before breaking schema
  changes.
- Preserve scoped-access guarantees: MCP must not gain arbitrary filesystem
  access as a compatibility shortcut.
- Prompt wording may evolve, but removed or renamed prompt identifiers should be
  documented when advertised to clients.

## Breaking-change migration notes

Any breaking change to a `beta-contract` command, option, JSON-RPC method,
request field, response envelope, error category, LSP command/metadata field, MCP
tool/resource/prompt, or patch safety semantic must include a migration note
before release.

Required migration-note content:

1. old surface and first affected release;
2. replacement surface or explicit removal reason;
3. compatibility impact for CLI scripts, daemon JSON-RPC clients, LSP clients,
   or MCP clients;
4. detection strategy, such as version check, API-version check, error category,
   or deprecation warning;
5. example before/after request or command when practical;
6. rollback or mitigation guidance when the change can affect patch application.

## Compatibility detection

Clients should detect compatibility through the most specific metadata available:

- **CLI**: use `refactorkit --version` or `refactorkit version`; the documented
  post-beta baseline reports implementation version `0.3.0-SNAPSHOT` on main and
  API version `0.2`.
- **Daemon JSON-RPC**: call `server.version`; it returns `name`, implementation
  `version`, and `apiVersion`.
- **LSP**: read `initialize` response `serverInfo.version` for the implementation
  version and compare behavior against the documented `apiVersion=0.2` baseline.
- **MCP**: read initialize response `serverInfo.version` for the implementation
  version and compare behavior against the documented `apiVersion=0.2` baseline.

For beta-contract integrations, automation should treat `apiVersion=0.2` as the
current compatibility baseline and fail safely when the API version or required
surface is unknown.
