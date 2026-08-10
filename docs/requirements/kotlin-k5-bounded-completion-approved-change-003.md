# Kotlin K5 bounded completion approved change 003

Status: incorporated before replacement native qualification.

## Superseded candidate evidence

- superseded aggregate requirement SHA-256: `7bb83fdf5a1f89ff358c6662bcd5ecfb1f4d086e1034f725a40a7652291b3197`
- failed local candidate: `a8822c28070956f56936bdc7b82888fc4a54ed06`
- independent adversarial audit SHA-256: `d1dee7d777b99817192d8f039c7b2a0e5dbeef092861f8f471232286acb43d2e`
- audit: `docs/requirements/evidence/v0.7.0-k5-pre-native-audit-a8822c2-fail-d1dee7d777b99817192d8f039c7b2a0e5dbeef092861f8f471232286acb43d2e.md`
- organize-imports typealias probe SHA-256: `4d6ed84ac2de2a1d5d55b14bfacf90b9634b5bd99e62f5a5b6f3355a41139f93`
- organize-imports typealias probe: `docs/requirements/evidence/probe-a8822-organize-typealias-rebound-4d6ed84ac2de2a1d5d55b14bfacf90b9634b5bd99e62f5a5b6f3355a41139f93.py`
- Maven `-Xplugin` extract probe SHA-256: `231462b891ce4611f7a62121d6373ab21f33fc6dc4c953c337a58d0212f9c16e`
- Maven `-Xplugin` extract probe: `docs/requirements/evidence/probe-a8822-maven-xplugin-valid-apply-231462b891ce4611f7a62121d6373ab21f33fc6dc4c953c337a58d0212f9c16e.py`
- exact 24-case GREEN SHA-256: `33f640f4015c6b0555febeea3a9be0c135e0e9c0887f8447c7f5cfa182f92c8e`
- exact 24-case GREEN: `docs/requirements/evidence/v0.7.0-k5-completion-typealias-xplugin-24-case-green-33f640f4015c6b0555febeea3a9be0c135e0e9c0887f8447c7f5cfa182f92c8e.log`

No native run or promotion followed the failed audit.

## Approved non-weakening corrections

The sealed organize-imports leaf already requires complete compiler usage
identity, and the matrices leaf already refuses compiler-plugin semantics. They
remain unchanged. The correction:

1. treats FIR typealias expansion/qualifiers as typed incomplete usage
   evidence (`kotlin.usageTypeAliasUnsupported`) instead of allowing the source
   spelling to disappear from the import fingerprint;
2. projects Kotlin Maven `-Xplugin` compiler arguments as the stable non-path ID
   `external-xplugin`, which activates the existing
   `kotlin.compilerPluginsUnsupported` refusal; and
3. strengthens packaged qualification with no-write typealias organize-imports
   and Maven `-Xplugin` extract refusals.

The completion oracle grows from 23 to 24 cases; the Maven plugin matrix case is
strengthened in place. A replacement aggregate requirement hash binds this
approved change and the corrected exact oracle.

These changes add refusal authority only. They do not claim typealias identity
mutation, arbitrary compiler-argument execution, plugin loading, or a broader
organize/extract shape. Replacement four-host receipts and fresh independent
review remain mandatory.
