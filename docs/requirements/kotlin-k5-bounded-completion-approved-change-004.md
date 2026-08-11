# Kotlin K5 bounded completion approved change 004

Status: incorporated before replacement native qualification.

## Superseded candidate evidence

- superseded aggregate requirement SHA-256: `f49d39898cf334b746e9bd5e7c955795c9b2063bda64fa54d63929fcee9f701e`
- failed local candidate: `e0ffc264d851d6538b665d9be7dba99c3e8dee53`
- independent adversarial audit SHA-256: `956bcf46f9d0292500df189b681c128beecb2051d6cf3f2653178744cac723f6`
- audit: `docs/requirements/evidence/v0.7.0-k5-pre-native-audit-e0ffc26-fail-956bcf46f9d0292500df189b681c128beecb2051d6cf3f2653178744cac723f6.md`
- user nested-alias probe SHA-256: `11f3829105fd4b22d2dce129117ab72cbe3cff85b227e23e5f40e573cac28e78`
- user probe output SHA-256: `a9a36fe03f1024f3d946633fd88e2c62ca2625e22ce836e85f28466e231ee6b9`
- user probe output: `docs/requirements/evidence/probe-e0ffc26-nested-alias-parameter-rebound-output-a9a36fe03f1024f3d946633fd88e2c62ca2625e22ce836e85f28466e231ee6b9.json`
- stdlib nested-alias probe SHA-256: `23ece4aa9ae54c9e5ba8738f05bf2112c0e82d0605399b976cd89fb875403883`
- stdlib probe output SHA-256: `e8c2b201735f00f540e5ab23f4200d8d5c70c9cc9bb852dec6a73b9c0324a0f3`
- stdlib probe output: `docs/requirements/evidence/probe-e0ffc26-stdlib-nested-alias-rebound-output-e8c2b201735f00f540e5ab23f4200d8d5c70c9cc9bb852dec6a73b9c0324a0f3.json`
- exact strengthened 24-case GREEN SHA-256: `1a14d85b2fc7c2c14cdcd2332b18896a2dac41c48cbf23ffd46968e12f6e5459`
- exact GREEN: `docs/requirements/evidence/v0.7.0-k5-completion-nested-typealias-24-case-green-1a14d85b2fc7c2c14cdcd2332b18896a2dac41c48cbf23ffd46968e12f6e5459.log`

No native run or promotion followed the failed audit.

## Approved non-weakening correction

The organize-imports leaf already requires complete recursive type identity.
Candidate `e0ffc26` checked only the outer resolved FIR type and could therefore
remove an explicit alias import from `List<Chosen>`, cleanly rebinding its JVM
generic signature to a retained star import. The same defect affected stdlib
aliases such as `kotlin.collections.ArrayList`.

Usage extraction now recursively checks all resolved `ConeKotlinTypeProjection`
arguments. A nested abbreviated type returns
`kotlin.usageTypeAliasUnsupported`; a type graph deeper than the bounded 64-level
model returns `kotlin.usageTypeDepthLimitExceeded`. The existing 24-case
completion oracle is strengthened in place for user and stdlib nested aliases,
and the packaged completion smoke uses a nested generic counterexample with
no-write proof.

This adds refusal authority only. It does not claim alias identity mutation,
general typealias support, or a broader organize-imports shape. Replacement
four-host receipts and fresh independent review remain mandatory.
