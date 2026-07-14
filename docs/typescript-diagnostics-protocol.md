# TypeScript/JavaScript IDE diagnostics protocol

Status: additive API `0.2` contract `diagnostics.v2`; legacy `diagnostics` is
preserved unchanged.

## Immutable `v0.6.2` investigation

The reference tag `v0.6.2` resolves to
`110b60537009781b44db9b840cf057d5b7f51067`. It was inspected and built from a
detached worktree, not modified.

| Finding at `v0.6.2` | Classification | Root cause | State on `main` before this fix |
|---|---|---|---|
| `diagnostics` returns a bare array | protocol bug | the daemon directly serialized `List<Diagnostic>` | unchanged |
| only severity/message and optional code/evidence/category/file/one-based line are exposed | implementation bug | `DaemonSession.diagnostics` serialized only `range.start.line + 1` | unchanged |
| UTF-16 start character and end position are discarded | implementation bug | internal `SourceRange` was not projected to the daemon wire | unchanged |
| no request/session/snapshot/provider/backend/toolchain correlation | protocol bug | no operation envelope existed | unchanged |
| no unsaved-buffer or caller-supplied immutable overlay input | intentional limitation promoted to protocol gap | compiler overlays existed only inside RefactorKit | unchanged |
| diagnostics capability inherited LSP backend/runtime | capability metadata bug | semantic capability composition overwrote per-operation backend/runtime | unchanged |
| advertised 10 s/64 MiB/eight-process LSP limits differ from compiler 30 s/8 MiB/one-process limits | capability metadata bug | one descriptor runtime represented two different processes | unchanged |
| compiler/toolchain failures appear as normal error diagnostics | protocol bug | `ExternalSemanticDiagnostics.Unavailable` was flattened by `LanguageAdapter.diagnostics` | unchanged |
| lifecycle and saved-snapshot authority were not documented for IDE consumers | documentation gap | documentation described internal safety, not operation authority | unchanged |

The internal compiler bridge already produced zero-based UTF-16 start/end
positions and attested the analyzed snapshot hash. The defect was loss and
conflation at integration boundaries, not missing compiler coordinates.

## Compatibility strategy

API `0.2` method `diagnostics` remains byte-shape compatible: it still returns the
legacy bare array with one-based line-only serialization. Existing consumers do
not silently receive a new shape.

IDE clients use the additive method:

```text
diagnostics.v2
```

The new response has `schemaVersion: 2`; this is the diagnostics-envelope schema,
not a product API-version change. CLI exposes it as `typescript diagnostics-v2`.
MCP exposes `diagnostics_v2`. The JSON Schema is
[`api-0.2-diagnostics-v2-schema.json`](api-0.2-diagnostics-v2-schema.json).

## Lifecycle

1. Call `project.open` and retain `snapshotHash`.
2. Call `typescript.semantic.start` with explicit hash-bound toolchain paths.
3. Retain the returned opaque `semanticLease`. A restart returns a new lease.
4. Call `diagnostics.v2` with the expected snapshot, lease, and source authority.
5. Treat `ready`, `refused`, and `error` as distinct states.
6. Stop or restart invalidates the old lease. Reopening the project invalidates
   every semantic lease.

Packaged capability means the implementation exists. It does **not** mean a
project/toolchain/session is currently ready.

## Request

Saved disk:

```json
{
  "requestId": "problems-refresh-42",
  "languageId": "typescript",
  "expectedSnapshotHash": "<64 lowercase hex>",
  "semanticLease": "semantic-<uuid>",
  "sourceAuthority": {"kind": "saved-disk"}
}
```

Immutable editor overlay:

```json
{
  "requestId": "editor-buffer-43",
  "languageId": "typescript",
  "expectedSnapshotHash": "<opened saved snapshot hash>",
  "semanticLease": "semantic-<uuid>",
  "sourceAuthority": {
    "kind": "immutable-editor-overlay",
    "documents": [
      {"path": "src/service.ts", "version": 9, "content": "unsaved source"}
    ]
  }
}
```

Overlay paths must already belong to the opened project and match the requested
language. Paths are workspace-relative, traversal-free, unique, and returned
with `/` separators. The language-neutral core `ImmutableEditorOverlay` validates
path/version/content authority and derives an in-memory provider snapshot; disk is
not modified. New untracked files, config overlays, mixed-language overlays, and
more than 128/768 KiB of editor documents are currently unsupported.

## Response authority

Every response includes:

- caller `requestId` and `languageId`;
- RefactorKit provider name, version, and API version;
- exact backend `typescript-compiler-exact-v1`;
- current semantic lease;
- saved `projectSnapshotHash` and analyzed `providerSnapshotHash`;
- explicit saved-disk or immutable-editor-overlay authority;
- overlay document versions and content hashes without echoing content;
- actual compiler runtime limits;
- compiler/toolchain hashes distinct from LSP process provenance;
- compiler-process executable/argument hashes for a ready invocation;
- structured status/failure and a separate source-diagnostics array.

`providerSnapshotHash == projectSnapshotHash` only for saved-disk authority. An
editor overlay has its own provider hash and deterministic overlay hash.

## Locations and Monaco

A diagnostic location is one of:

```text
range: path + zero-based UTF-16 start/end
line:  path + zero-based line only
none:  no source location
```

Only `kind=range` may be converted directly to a Monaco range. A client must not
invent character/end positions for `line` or `none`. The TypeScript compiler
bridge currently emits either exact ranges or no location; line-only remains an
explicit protocol variant for providers that genuinely possess only a line.

## Readiness and failures

- `ready`: compiler invocation completed; `diagnostics` contains source findings.
- `refused`: stale snapshot/lease, missing session, changed evidence, invalid
  authority, or another safety precondition failed.
- `error`: bounded compiler timeout, invalid/incomplete compiler output, or worker
  failure.

A refusal/error has an empty diagnostics array and a structured `failure`. Clients
never need to parse TypeScript diagnostic codes to distinguish readiness.

## Capability truth and limits

The TypeScript/JavaScript `diagnostics` capability advertises:

```text
backend: typescript-compiler-exact-v1
execution: external-process
snapshot modes: saved-disk, immutable-editor-overlay
range: exact-utf16-or-explicit-partial
mutation authority: none
timeout: 30000 ms
input overlay: 512 MiB
compiler stdout: 8 MiB
processes per invocation: 1
compiler heap: 512 MiB
stderr: 64 KiB
diagnostics: 500
message: 4096 characters
response: 2 MiB
```

The LSP backend remains responsible for symbols/references/rename proposals. LSP
provenance is never reported as compiler attestation.

## JDK build prerequisite

The immutable `v0.6.2` source build succeeds on JDK 21. It fails on JDK 25.0.3
inside Kotlin 2.0.21 with `IllegalArgumentException: 25.0.3` from
`JavaVersion.parse`. Until the build toolchain is upgraded and qualified,
RefactorKit source builds fail fast with an actionable JDK 21 requirement. This
restriction concerns building from source; packaged distributions use their
bundled runtime.
