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
| Import preview combined rendered/structured diff source lines | 524,288 UTF-8 bytes | `diffTruncated=true` with reasons; never silent |
| Import preview diff files / hunks per file / lines per hunk | 128 / 64 / 2,000 | Deterministic path ordering and explicit truncation reasons |
| Import preview/apply/rollback diagnostics | 500 entries, 262,144 UTF-8 bytes, 4,096 characters/message | `diagnosticsTruncated=true`; blockers are evaluated before truncation |
| Packaged daemon smoke captured stderr | 65,536 UTF-8 bytes | Additional stderr is discarded; source-marker leakage still fails within the retained bound |
| MCP symbol/reference context output | Operation-specific caps, no more than 200 symbols or 100 displayed references | Output is truncated deterministically |

Constants shared by transports live in `ProtocolLimits`. Limits are additive API
metadata candidates for later capability schemas; changing them requires release
notes when client behavior can be affected.

## Maven model limits

Offline effective-model traversal is bounded to 4,096 dependency coordinates,
128 transitive levels, and 32 relative-parent levels. Explicit network opt-in has
a 15-second connect/read timeout and a 256 MiB per-artifact ceiling. Limit
exhaustion becomes a concise `classpath.unavailable` root diagnostic.

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
