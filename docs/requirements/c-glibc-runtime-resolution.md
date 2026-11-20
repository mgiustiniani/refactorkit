# C glibc runtime resolution capture

Authority: [the feature](../../features/c-glibc-runtime-resolution.feature) is
the single source of truth for `REQ-C-GLIBC-RESOLUTION-001`, one six-case Story.
[ARC42](../arc42/appendix-requirements.adoc) owns integrated acceptance;
[search proposals](c-glibc-dependency-search.md),
[cache capture](c-glibc-runtime-inputs.md) and
[manifest composition](c-runtime-manifest.md) own their bounded contracts.

Inspect an explicitly selected executable and its declared interpreter without
executing either. Resolve indexed DT_NEEDED occurrences breadth first, reusing
previously loaded names and inspecting each newly selected object's own
RUNPATH/cache proposal. Preserve graph sharing and ordered dependency indices.
Capture selected ELF bytes and aliases; register names coherently and refuse
conflicting captures. Enforce 256 objects, 4096 edges, 8192 search observations,
256 directory components, a 512 MiB object limit and a two-GiB cumulative read
budget per acquisition or verification traversal before each native file read. Compose the complete pure manifest.

Inspect RUNPATH directories component by component without following aliases or
normalizing away raw intermediate paths. Retain absent directories, absent
candidate files and absence of `glibc-hwcaps` in searched directories. Presence
of that hardware-selection directory refuses this first profile. Missing paths
mean absence only under exact no-follow inspection; unreadability and other
errors refuse. Present candidates must be captured as supported ELF files;
unavailable/unsupported candidates cannot silently fall through to a cache hit.
Cache must yield a unique supported native candidate when RUNPATH is exhausted.
Absent/ambiguous/unsupported cache selection refuses; default-directory search
is not inferred. Refusal is unavailable authority, not proof of a missing
library. All filesystem observations are read-only and bounded.

Retain cache bytes, selected preload absence, manifest and immutable ordered
search observations. Recheck native objects/cache before returning. Currency
runs the original selectors through fresh recursive resolution and compares
graph identity, cache/preload facts and search observations. New captures never
renew an older result implicitly. No locking or complete filesystem race closure
is claimed; cleared-environment launch is a future consumer obligation.
Explicit cache selectors support inspection, without certifying alternate cache
paths as actual host inputs. This API grants no execution or semantic admission.

The official LLVM 22.1.8 compiler/server/formatter controls are checked against
the system loader's read-only `--list` output plus independent
`readelf`/`readlink`/`sha256sum` oracles. Each current graph contains seven
objects and sixteen indexed dependency edges. These executables select through
the cache and reuse loaded names; the acceptance does not require a RUNPATH
selection that this archive does not emit. Instead, every indexed `DT_NEEDED`
occurrence must have one retained successful search observation, while actual
candidate and `glibc-hwcaps` absences remain in the captured observation list.
Owned cache and copied `libz.so.1` mutations revoke currency; an owned cache
without that candidate refuses rather than asserting that the host lacks the
library. Original source/host data stays intact; intentional fixture mutations
are explicit. The existing shared runner and scenario-scoped object factory are
retained. The six-case Story passes offline with four exact step reuses and no
runner ambiguity; the selected Clang artifacts are not invoked.

Broader loader/hardware/default/RPATH profiles, host/environment/CPU/provisioning/
licence/sysroot identities, admission before every native consumer, preview
leases/post-commit guards and remaining budget/race/fault/process/protocol cells
remain `actionable`. Every C01-C17 acceptance remains required; this bounded
reference profile closes no complete roadmap row.
