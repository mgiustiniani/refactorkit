# C glibc loader cache facts

Authority: pure Kotlin domain rules for `REQ-C-GLIBC-CACHE-001`.
[ARC42](../arc42/appendix-requirements.adoc) owns integrated architecture and
acceptance; [the 0.8.0 plan](../releases/v0.8.0-plan.md) owns full C02 closure.
This requirement reads supplied bytes. It performs no filesystem access, native
execution, cache regeneration, loader resolution or semantic admission.

Actual native research finds `/etc/ld.so.cache` mapped by initialized clangd
both with a flat LD_LIBRARY_PATH and with direct loader cache inhibition. The
source of that mapping is not established; mapping alone does not prove cache
use for loader dependency lookup. Cache data remains a required captured input.

Decode the standalone glibc new cache format `glibc-ld.so.cache1.1` for
little-endian metadata. Preserve the exact SHA-256, header flags, ordered entries
and multiplicity, literal names/absolute paths, library flags, OS-version field
and hardware-capability values. Validate the 48-byte header, 24-byte entry array,
string-table boundaries, offsets, termination and strict UTF-8. Bound the input
to 8 MiB, 16384 entries, 64 extension sections and 4096 bytes per string. Old/embedded formats, wrong
endianness, malformed extents and unsupported extension structure refuse.

Validate the extension directory and its payload bounds. Preserve section tags,
flags, offsets/sizes and content identity without guessing future semantics.
Candidate lookup refuses matching native entries with OS/hwcap qualification
outside the first supported profile. Generator metadata alone does not select a
library. Native x86-64 candidate flags are exactly `0x303`; foreign entries are
retained but do not become native candidates. A unique literal path can be
returned only when all native matching entries have zero OS/hwcap values.
Distinct native paths are ambiguous; identical repeated paths preserve cache
multiplicity while identifying the same candidate. Absence is a typed lookup
result, never proof that a library is unavailable or analysis is complete.

Six approved Kotest specifications cover:

1. Valid byte-bound native/foreign records, extension metadata and duplicate order.
2. Unique, absent, repeated-identical and ambiguous native candidate results.
3. Nonzero native OS/hwcap metadata refuses rather than silently selecting a base.
4. Malformed/truncated/overflowing headers, arrays, string offsets and extensions.
5. Independent byte, entry and string bounds and unsupported cache/encoding profiles.
6. Immutable snapshots, strict text/path validation and input-byte identity changes.

The format and native flag constants follow the primary glibc headers
[dl-cache.h](https://raw.githubusercontent.com/bminor/glibc/master/sysdeps/generic/dl-cache.h),
[ldconfig.h](https://raw.githubusercontent.com/bminor/glibc/master/sysdeps/generic/ldconfig.h)
and [x86-64 cache rules](https://raw.githubusercontent.com/bminor/glibc/master/sysdeps/x86_64/dl-cache.h).
The implementation is locally authored; no glibc source or dependency is imported.

The read-only [runtime input capture](c-glibc-runtime-inputs.md) owns selected
native cache acquisition/currency and preload absence. That generic acquisition
gap is now `stale`; selectors alone do not establish actual loader authority.
Actual recursive search, candidate ELF/alias validation, extension-aware hardware
selection, host/runtime identities, execution/lease/post-commit guards and full
C02 remain `actionable`.
No C01-C17 requirement is removed by this bounded domain profile.
