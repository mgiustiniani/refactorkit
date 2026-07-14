# Resource and protocol limits

Status: normative `v0.4.0` local-process baseline.

RefactorKit is a local tool, not a network service, but stdio clients and large
workspaces can still exhaust memory or create unbounded output. Stable surfaces
fail deterministically at documented boundaries rather than relying on ambient
heap exhaustion.

## Protocol limits

| Resource | `v0.4.0` limit | Behavior |
|---|---:|---|
| Daemon NDJSON request | 1,048,576 UTF-8 bytes | JSON-RPC `INVALID_REQUEST`; session continues |
| MCP NDJSON request | 1,048,576 UTF-8 bytes | JSON-RPC `INVALID_REQUEST`; session continues |
| LSP frame | 1,048,576 bytes | JSON-RPC `INVALID_REQUEST`; connection closes because the rejected body is not buffered |
| Pending plans per daemon/MCP/LSP session | 128 | Least-recently-used plan is evicted; applying an evicted ID returns deterministic invalid-params/not-found |
| Daemon symbol-search result | 200 | Results are truncated deterministically |
| Workspace index | 100,000 sources, 100,000 total symbols, 50,000 symbols/provider | Project open/contribution is rejected at the hard boundary; provider output may be explicitly truncated before contribution |
| TypeScript/JavaScript saved-snapshot symbol projection | 256 source files, 50,000 symbols, 30-second aggregate deadline | File/evidence/provider/timeout failure publishes no partial partition; symbol-cap overflow contributes the deterministic prefix with `truncated=true` |
| Intelligence query | 512 query characters, 200 returned symbols, 128 request-ID characters | Invalid input is rejected; result tails are deterministically truncated with total/returned counts |
| Import preview combined rendered/structured diff source lines | 524,288 UTF-8 bytes | `diffTruncated=true` with reasons; never silent |
| Import preview diff files / hunks per file / lines per hunk | 128 / 64 / 2,000 | Deterministic path ordering and explicit truncation reasons |
| Import preview/apply/rollback diagnostics | 500 entries, 262,144 UTF-8 bytes, 4,096 characters/message | `diagnosticsTruncated=true`; blockers are evaluated before truncation |
| `diagnostics.v2` response | 2,097,152 UTF-8 bytes, 500 entries, 4,096 characters/message | Deterministic diagnostic-tail truncation with total/returned counts, `truncated=true`, and exact `responseBytes` |
| `diagnostics.v2` editor overlay | 128 existing source documents, 786,432 UTF-8 bytes per request | Invalid/duplicate/traversing/mixed-language paths are refused before compiler invocation |
| Kotlin K2 diagnostics and regular-class symbols | 30 seconds, 512 MiB compiler heap, 8 MiB stdout, 64 KiB stderr, 1 process, 96 `.kt` files, 128 project classpath entries, 500 diagnostics, 500 compiler symbols, 4,096 characters/diagnostic message, 512 UTF-16 units/symbol name | Typed refusal/error; process tree terminated, no partial diagnostics or symbol index promoted; protocol symbol output remains capped at 200 with explicit truncation |
| Packaged daemon smoke captured stderr | 65,536 UTF-8 bytes | Additional stderr is discarded; source-marker leakage still fails within the retained bound |
| MCP symbol/reference context output | Operation-specific caps, no more than 200 symbols or 100 displayed references | Output is truncated deterministically |

Constants shared by transports live in `ProtocolLimits`. Limits are additive API
metadata candidates for later capability schemas; changing them requires release
notes when client behavior can be affected.

## Maven model limits

Offline effective-model traversal is bounded to 4,096 dependency coordinates,
128 transitive levels, and 32 relative-parent levels. Explicit network opt-in has
a 15-second connect/read timeout, a 256 MiB per-artifact ceiling, and a 4 KiB
SHA-256-sidecar ceiling. Redirects are disabled and unverified downloads are
removed before cache publication. Limit or verification failure becomes a concise
`classpath.unavailable` root diagnostic.

## Build-model summary limits

Daemon `project.summary` emits at most 16 models, 1,024 total model modules, 64
source sets per module, 256 roots in each source/generated/output list, 1,024
module dependencies per source set, and 500 typed model diagnostics. Every level
that can truncate carries an explicit flag and the response includes the active
limits. Ordering is deterministic before truncation.

## External semantic process limits

The core manager defaults to at most 8 concurrent external semantic processes
(configurable only within 1..64), 128 arguments of at most 4,096 characters, and
32 explicit environment entries of at most 8,192 characters. Default output
limits are 64 MiB total stdout and 64 KiB captured stderr; configurable stream
limits cannot exceed 512 MiB. Graceful shutdown is bounded to 1..30 seconds before
forced process-tree termination. Raw arguments and environment values are not
included in provenance. LSP transport additionally limits individual frames and
headers to 8 MiB, pending diagnostic notifications to 500, and request deadlines
to the configured 100 ms..120 s range (10 s default).

Source-only semantic overlays default to 100,000 files and 512 MiB of UTF-8
source content. The experimental TypeScript provider adds a constant 512 MiB V8
old-space argument, 256 synchronized documents of at most 4 MiB each, 500
compiler diagnostics, 10,000 document symbols, and no more than three explicit
restart attempts per rolling 60 seconds. LSP publication collection permits at
most 20 additional 50 ms protocol barriers; missing or unversioned publications
remain untrusted. Managed `typescript-compiler-exact-v1` analysis separately uses
a 30-second process timeout, 512 MiB V8 old-space limit, 8 MiB stdout, 64 KiB
stderr, one compiler process per invocation, 64 projects and 500 diagnostics.
Capability metadata reports these compiler limits rather than the LSP limits. The
V8 limits are not an OS RSS sandbox.
External workspace-edit normalization defaults to 1,000 file
operations, 10,000 text edits, and 16 MiB replacement content. Exceeding any
limit is an explicit refusal, never truncation into an applicable proposal.

## Cancellation

The `v0.4.0` daemon and MCP request loops are synchronous and do not advertise
cooperative in-flight cancellation. LSP does not advertise work-done progress or
a cancellable custom operation. `$/cancelRequest` notifications therefore do not
create a false cancellation guarantee. Production asynchronous semantic-process
cancellation is a `v0.5.x` multi-language-kernel requirement.

Clients can terminate the local process; managed writes remain protected by WAL
recovery. Cancellation is never allowed to interrupt an unjournaled partial
workspace mutation.

## Reference-workspace budgets

The `v0.4.0` acceptance reference is the repository sample matrix plus generated
Java stress fixtures. CI must record, rather than hide, wall-clock and peak-memory
measurements. Initial fail-safe ceilings on the standard Linux CI runner are:

| Operation | Fixture | Ceiling |
|---|---|---:|
| Scan/index | 1,000 Java files / 100,000 source lines | 30 seconds |
| Symbols | same | 60 seconds |
| Diagnostics | same, single module, clean bindings | 120 seconds |
| Single-file format preview | 10 MiB maximum accepted design target | 30 seconds |
| Managed one-file apply/rollback | reference filesystem | 30 seconds each |

These are release ceilings, not performance claims. Measurements and tighter
limits evolve through later `0.x` releases. A workload that exceeds a protocol
hard limit is refused; a workspace that exceeds a benchmark ceiling blocks the
release until optimized or explicitly requalified with evidence.

## Remaining deeper-platform limits

The multi-language kernel must additionally bound:

- source file and total snapshot bytes before loading;
- compiler/language-server process memory and output;
- external request concurrency and queues;
- reference/diagnostic result pagination;
- cache size and eviction;
- transaction image count and aggregate journal bytes;
- wall-clock timeout and cooperative cancellation.

Those limits must be implemented before their corresponding capability is
promoted; they are not inherited automatically from the Java `v0.4.0` baseline.
