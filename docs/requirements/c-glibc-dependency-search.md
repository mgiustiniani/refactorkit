# C glibc dependency search proposals

Authority: pure Kotlin rules for `REQ-C-GLIBC-SEARCH-001`.
[ARC42](../arc42/appendix-requirements.adoc) owns integrated acceptance;
[the cache domain](c-glibc-loader-cache.md) owns supplied-cache lookup.
This profile prepares search proposals from supplied captures. It does not
inspect directories, choose actual files, execute native tools or admit a runtime.

The first reference profile uses a cleared process environment, per-object
DT_RUNPATH and supported native cache metadata. Require literal DT_NEEDED
basenames. Preserve ordered RUNPATH entries, including repetitions. Expand only
`$ORIGIN` or `${ORIGIN}` at the start of an entry, followed by end or `/`, using
the captured object's physical parent. Require an alias-free origin and a
normalized physical object path; an alias-free raw selector may contain `..`
when its normalization equals that physical object. Otherwise refuse rather
than infer token semantics. Absolute literal entries are allowed. Preserve expanded path spelling
and `..` for the IO boundary to inspect; do not normalize away intermediate
paths or silently inherit a parent's RUNPATH for a dependency's children.

Reject any DT_RPATH, NODEFLIB, relative/empty RUNPATH entry, unknown token,
malformed token boundary, control character, invalid UTF-8 representation or
invalid basename. Bound RUNPATH to 4096 UTF-8 bytes, 64 directories, and each
expanded candidate to 4096 UTF-8 bytes. Return an immutable proposal with the
requester, needed name and ordered candidate paths plus the cache's typed
fallback. Ambiguous, absent or unsupported cache lookup is retained: a valid
RUNPATH hit can precede it; cache absence never proves no dependency or licenses
an inferred default-directory fallback.

Six approved Kotest specifications cover ordered per-object expansion,
literal/token profile refusals, typed cache fallbacks, aliased/invalid origins,
RPATH/NODEFLIB/name refusals, and immutable/bounded UTF-8 proposals. The actual
filesystem boundary must inspect candidate paths, record unsuccessful searches
and hardware-directory absence, capture selected ELF/aliases, construct the
recursive graph, and replay all inputs before any later admission.
The [native resolution Story](c-glibc-runtime-resolution.md) implements that
bounded reference-profile IO and currency; the generic recursive-IO gap is
`stale`. Broader loader profiles, remaining budget/race/fault cells,
host/provisioning/licence/sysroot identities and execution/lease/post-commit
integrations remain `actionable` and required by full C02.

Search ordering and per-object RUNPATH follow the primary
[glibc loader implementation](https://raw.githubusercontent.com/bminor/glibc/master/elf/dl-load.c).
No glibc source or dependency is imported. Every C01-C17 acceptance remains
required; this bounded profile removes none of them.
