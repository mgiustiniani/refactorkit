# C runtime manifest composition

Authority: pure Kotlin domain rules for `REQ-C-RUNTIME-MANIFEST-001`.
[ARC42](../arc42/appendix-requirements.adoc) owns integrated architecture and
acceptance; the [0.8.0 plan](../releases/v0.8.0-plan.md) owns full C02 closure.
This requirement composes supplied captures. It does not discover libraries,
verify a loader's selection, check filesystem currency or authorize execution.

The initial profile is a dynamic Linux ELF64 little-endian x86-64 Clang,
clangd or formatter executable, its declared interpreter, and captured dynamic
libraries. ELF facts retain ET_EXEC versus ET_DYN explicitly. Each object has
one unique resolved absolute path, coherent role/hash/size evidence, immutable
dependency facts, and its exact requested path and ordered alias observations.
Static roots and unknown/incompatible roles refuse this profile explicitly.
The interpreter must not request a second interpreter. A library may retain
PT_INTERP, as the actual captured host libc does; it is not the selected process
root. Its declaration remains in the identity and does not add a DT_NEEDED edge.
The [ELF program-interpreter specification](https://gabi.xinuos.com/elf/08-dynamic.html#program-interpreter)
distinguishes process startup from adding shared objects to the process image.

Every ordered `DT_NEEDED` occurrence, including repetitions and interpreter
dependencies, requires exactly one indexed requester-to-target binding. Needed
names in this bounded profile are literal basenames, and must match the target's
SONAME or captured requested basename. Conflicting loaded names, duplicate
physical objects or edge keys, missing/extraneous edges, and unreachable supplied
libraries refuse. Shared dependencies and cycles are legal; traversal terminates.
The interpreter's requested spelling must equal the executable's PT_INTERP.

Bound the graph to 256 objects, 4096 edges, 512 dependencies per object, 32 aliases
per capture, 4096 UTF-8 bytes per string, 512 MiB per object and 2 GiB total.
Malformed text, inconsistent evidence or unsupported dynamic roles refuse.
Return typed domain refusal reasons, without filesystem/process access.

Six approved Kotest specifications cover:

1. A complete graph with shared/cyclic/repeated dependencies and an independently
   fixed, versioned, length-framed SHA-256 identity.
2. Missing, extra, duplicated and ambiguous bindings, including transitive edges.
3. Invalid roles, interpreter/name/evidence mismatch and disconnected objects.
4. Object/edge/string/alias/byte budgets and malformed representations.
5. Defensive immutable snapshots, including input mutation after composition.
6. Permutation stability and identity changes for bytes, roles, aliases, requested
   spelling, ELF declarations, dependency order and selected targets.

Identity encodes all supplied object facts, ordered alias records and selected
edges in canonical physical-path order. It preserves requested `..` spelling;
normalizing it before alias traversal would change its meaning. A successful
composition means graph consistency only. Production loader resolution, search/
cache/environment/CPU/preload inputs, admission, leases and post-commit checks
remain `actionable`; no C01-C17 acceptance is closed by these specifications.

## Native inventory capture

The [native capture feature](../../features/c-runtime-manifest-capture.feature)
owns the operational contract `REQ-C-RUNTIME-CAPTURE-001`, one six-case Story.
`ClangRuntimeManifestCapture` accepts explicit executable/interpreter/library
paths and indexed selected edges, captures each through the existing read-only
native path boundary, delegates graph rules to this domain and rechecks every
retained input before returning. A later currency check rereads the original
requested paths; different bytes or equal-byte alias retargeting invalidate
the earlier manifest. It never refreshes an earlier identity from a new capture.
The domain types become library-visible with unchanged rules. No protocol,
capability, dependency or version is added.

Capture bounds object/edge counts before reading and progressively reduces each
file read limit to the remaining 2 GiB graph budget, with a 512 MiB per-file cap.
Native reading and pure composition refuse unavailable or incoherent inventories
with structured safety diagnostics. Capture performs no writes or tool execution.
Point-in-time reads and before/after checks do not claim locking or race closure.

Three native-data controls read the configured private compiler, server and
formatter 22.1.8, each with sixteen objects. Independent `readelf`, `readlink`
and `sha256sum` oracles verify metadata, physical paths, aliases and every hash;
target hashes match the prior derivation ledger, and dependency identities match
the actual loader research. Owned library-byte mutation and equal-byte alias
retargeting lose currency; an omitted transitive libffi object refuses. Owned
source and reference files remain intact, with fixture mutations explicit.
The selected native artifacts never execute. Initial fixture-preparation failures
are preserved separately and are not semantic RED; all six corrected cases pass.

Evidence is in `.refactorkit/runs/v080-runtime-manifest-capture-r001/`, including
the fresh receipt, installed analyzer inventories of all 115/116 effective test
sources, shared runner, four new/exact step expressions, native graph identities
and passing C/golden/native regression. Unrelated existing duplicate expressions
remain reported. The feature and this satellite link back to ARC42.

Caller-selected graph capture is not verified loader selection or execution
admission. Search/cache/environment/CPU/preload inputs, authenticated provisioning,
licence/sysroot closure, additional IO budget/race/fault cases, execution gates,
preview leases and post-commit guards remain `actionable`. Full C02 and every
C01-C17 acceptance remain required.
