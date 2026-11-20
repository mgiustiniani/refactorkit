# Clang toolchain provenance and runtime identity

Authority: pure Kotlin rules for `REQ-C-CLANG-IDENTITY-001`.
[ARC42](../arc42/appendix-requirements.adoc) owns integrated acceptance;
the [0.8.0 plan](../releases/v0.8.0-plan.md) owns full C02 closure.

The identity binds provider, compiler/server/formatter versions, optional target
triple, resource directory and optional complete native-runtime identity, and every file's role, lexically normalized path,
byte size and content hash. Evidence permutation must not change the identity;
duplicates remain significant. No field separator, Unicode representation, or
literal placeholder may make distinct facts indistinguishable. Null and any
present string have different representations. Malformed UTF-16 must produce an
explicit `CharacterCodingException`; silently replacing an unpaired surrogate
would alias a present replacement string. No filesystem access, execution,
current-directory lookup or Unicode normalization is permitted.

The versioned SHA-256 input starts with the framed string
`refactorkit.clang.toolchain.identity.v3`, followed by the six earlier metadata
fields, then the optional native-runtime identity. Each null string is byte `00`; each present string is byte `01`,
its four-byte big-endian UTF-8 length, and those bytes. The four-byte big-endian
evidence count precedes records sorted by role, normalized path, size, then hash.
A record frames role and path, writes the eight-byte big-endian size, then frames
the hash. This is a deterministic domain encoding, not ELF or a wire protocol.
Existing v1/v2 identities are invalidated rather than interpreted as v3 authority.
An absent native-runtime identity records unknown inputs, never semantic admission.

Six approved Kotest specifications exercise a fixed independent golden digest,
every metadata/evidence field, delimiter collisions, Unicode/null distinctions,
permutation, multiplicity, malformed-text refusal and lexical paths. Beyond
encoding validity, identity construction does not validate supplied facts or
establish their freshness. Native reference-profile resolution now exists;
runtime admission/currency integration, remaining host/resource/sysroot identity,
broader loader profiles, product admission and full C02 remain `actionable`.
The earlier ELF requirement's artifact-capture gap is `stale`: the separately
bounded native capture Story now exists; complete product input closure is open.
Every C01-C17 acceptance remains required.

## Complete supplied native-runtime identity

Pure Kotlin `REQ-C-CLANG-RUNTIME-IDENTITY-001` composes exactly compiler, server
and formatter runtime captures in fixed role order. Require matching roles,
distinct physical executables, one coherent cache/preload capture, consistent
shared physical object metadata and bounded search-observation counts. Retain
immutable supplied captures. Partial, mismatched or inconsistent tuples refuse;
an uncaptured runtime has an explicit unavailable result.

Each runtime digest frames the version/profile, complete manifest identity,
cache role/path/size/hash and all immutable parsed cache fields, selected
preload-path absence, and every ordered search observation (requester,
dependency index, kind and raw path). Preserve repetitions, spelling and order.
The bundle digest frames its own version and the three role/runtime-digest
pairs in fixed order. Strings use strict length-framed UTF-8 with explicit null;
counts/integers use big-endian four/eight-byte values. Never normalize paths or
Unicode, read files, consult the environment, or grant freshness/launch authority
while constructing the identity.

Six approved Kotest specifications cover independent fixed digests, role/full
input coherence, shared-object consistency, all retained runtime/cache/preload/
observation fields, ordering/multiplicity and immutable bounded composition.
These supplied-capture rules prepare native integration; before-probe capture,
currency, guards at every native consumer, preview leases/post-commit and full
C02 remain `actionable`. Every C01-C17 acceptance remains required.
