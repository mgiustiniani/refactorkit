# REQ-JAVA-CLI-CATALOG-002 Approved Change Receipt 001

- Change ID: `REQ-JAVA-CLI-CATALOG-002-APPROVED-CHANGE-001`
- Parent requirement: `REQ-JAVA-CLI-CATALOG-002`
- Recorded at: `2026-08-10T02:14:18Z`
- Approval authority: user authorization to complete the seven named K1/K2/shared-foundation v0.7.0 rows without deferral
- Scope dependency: K2 requires explicit, machine-readable refusal boundaries for Android, compiler-plugin semantics, and generated-code mutation.

## Approved delta

The language-capability schema used alongside command catalogue v2 may add exactly the three refusal-only Kotlin rows recorded by `REQ-JAVA-CLI-CATALOG-001-APPROVED-CHANGE-002`:

- `android`;
- `compilerPluginSemantics`;
- `generatedCodeMutation`.

The evolved language-capability fixture SHA-256 is `872120125a3a84e5304635adcecfb1c65a11485a51fb13b7d831687b63dc666e`. Its exact path is `modules/refactorkit-cli/src/test/resources/org/refactorkit/cli/reqjavaclicatalog001/capabilities-k1-k2-approved-872120125a3a.json`.

## Preserved contract

Catalogue v2 and the explicit v1 selection remain byte-for-byte unchanged. The capability document remains separate from command discovery, retains schema version 1 and its closed top-level shape, and gains no command-catalogue field. No parser, help, operation-result, approval, managed-write, installed-runtime, or release-support claim is authorized.

This approved delta is candidate-only until the dedicated K1/K2 four-host qualification and independent review pass.
