# Kotlin/JVM move-next requirement change 006

Status: incorporated before replacement candidate commit and native qualification.

## Failed candidate evidence

- unchanged sealed requirement SHA-256: `52dd7de86022e2d86e143c453fe2c445c2718149a6393d5ba462bbceb2642a0f`
- failed local candidate: `e0ffc264d851d6538b665d9be7dba99c3e8dee53`
- independent adversarial audit SHA-256: `956bcf46f9d0292500df189b681c128beecb2051d6cf3f2653178744cac723f6`
- audit: `docs/requirements/evidence/v0.7.0-k5-pre-native-audit-e0ffc26-fail-956bcf46f9d0292500df189b681c128beecb2051d6cf3f2653178744cac723f6.md`
- user nested-alias probe SHA-256: `11f3829105fd4b22d2dce129117ab72cbe3cff85b227e23e5f40e573cac28e78`
- user nested-alias probe: `docs/requirements/evidence/probe-e0ffc26-nested-alias-parameter-rebound-11f3829105fd4b22d2dce129117ab72cbe3cff85b227e23e5f40e573cac28e78.py`
- user probe output SHA-256: `a9a36fe03f1024f3d946633fd88e2c62ca2625e22ce836e85f28466e231ee6b9`
- user probe output: `docs/requirements/evidence/probe-e0ffc26-nested-alias-parameter-rebound-output-a9a36fe03f1024f3d946633fd88e2c62ca2625e22ce836e85f28466e231ee6b9.json`
- stdlib nested-alias probe SHA-256: `23ece4aa9ae54c9e5ba8738f05bf2112c0e82d0605399b976cd89fb875403883`
- stdlib nested-alias probe: `docs/requirements/evidence/probe-e0ffc26-stdlib-nested-alias-rebound-23ece4aa9ae54c9e5ba8738f05bf2112c0e82d0605399b976cd89fb875403883.py`
- stdlib probe output SHA-256: `e8c2b201735f00f540e5ab23f4200d8d5c70c9cc9bb852dec6a73b9c0324a0f3`
- stdlib probe output: `docs/requirements/evidence/probe-e0ffc26-stdlib-nested-alias-rebound-output-e8c2b201735f00f540e5ab23f4200d8d5c70c9cc9bb852dec6a73b9c0324a0f3.json`
- exact strengthened 25-case GREEN SHA-256: `35a6d9aedeeef64f59af39b2605ebee2b60724bb75ea137a85999edd560f4e39`
- exact GREEN: `docs/requirements/evidence/kotlin-jvm-move-nested-typealias-25-case-green-35a6d9aedeeef64f59af39b2605ebee2b60724bb75ea137a85999edd560f4e39.log`

## Approved non-weakening correction

Candidate `e0ffc26` detected only an outer FIR typealias expansion or direct
`FirTypeAliasSymbol` qualifier. A typealias nested inside a generic type argument
therefore disappeared from usage evidence. Moving a file containing
`List<Chosen>` could cleanly change its JVM generic signature, including for the
stdlib `ArrayList` alias.

The compiler usage extractor now recursively examines every
`ConeKotlinTypeProjection` in every source-backed resolved type, with a bounded
64-level traversal. Any nested abbreviated type returns
`kotlin.usageTypeAliasUnsupported`; excess depth returns the separate typed
`kotlin.usageTypeDepthLimitExceeded`. The existing 25-case oracle is strengthened
in place to cover both a user alias and the stdlib nested alias. The packaged move
smoke now uses the nested generic counterexample and proves no source or WAL
write.

This adds refusal authority only. It does not model alias declarations, authorize
typealias mutation, widen the move shape, or weaken any consumer/binding proof.
A replacement four-host run and fresh independent post-native review remain
mandatory.
