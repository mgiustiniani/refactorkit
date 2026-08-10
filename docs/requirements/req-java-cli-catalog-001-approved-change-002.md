# REQ-JAVA-CLI-CATALOG-001 Approved Change Receipt 002

- Change ID: `REQ-JAVA-CLI-CATALOG-001-APPROVED-CHANGE-002`
- Parent requirement: `REQ-JAVA-CLI-CATALOG-001`
- Recorded at: `2026-08-10T02:14:18Z`
- Approval authority: user authorization to complete the seven named K1/K2/shared-foundation v0.7.0 rows without deferral
- Scope dependency: K2 requires explicit, machine-readable refusal boundaries for Android, compiler-plugin semantics, and generated-code mutation.

## Approved delta

The existing `refactorkit capabilities` language-capability document may add exactly these three Kotlin adapter rows:

| operation | stability | evidence | mutation authority | backend | extension |
|---|---|---|---|---|---|
| `android` | `refused` | `none` | `none` | `kotlin-analysis-unavailable-v1` | `kt` |
| `compilerPluginSemantics` | `refused` | `none` | `none` | `kotlin-analysis-unavailable-v1` | `kt` |
| `generatedCodeMutation` | `refused` | `none` | `none` | `kotlin-analysis-unavailable-v1` | `kt` |

The approved source-built output is bound byte-for-byte by:

- prior language-capability fixture SHA-256: `dec4087fa8b7ba038a5b9a82728ec724054ee6fd28456eb9ec637fd7d0d0d1c3`;
- evolved language-capability fixture: `modules/refactorkit-cli/src/test/resources/org/refactorkit/cli/reqjavaclicatalog001/capabilities-k1-k2-approved-872120125a3a.json`;
- evolved fixture SHA-256: `872120125a3a84e5304635adcecfb1c65a11485a51fb13b7d831687b63dc666e`.

## Preserved contract

This change does not authorize any command-catalogue field, route, alias, mode, parser, help, operation-result, approval, or write-authority change. The capability document remains schema version 1 with the same closed top-level shape. Every prior adapter and capability row remains byte-for-byte equivalent; the delta is additive and refusal-only. Catalogue v1 output remains unchanged.

The installed runtime remains guarded and non-authoritative. This receipt does not authorize installation mutation or claim qualified Kotlin support. The three rows remain candidate evidence until the dedicated K1/K2 four-host qualification and independent review pass.
