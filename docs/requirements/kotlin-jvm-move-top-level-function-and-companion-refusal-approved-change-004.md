# Kotlin/JVM move-next requirement change 004

Status: incorporated before replacement candidate commit and native qualification.

## Failed candidate evidence

- unchanged sealed requirement SHA-256: `52dd7de86022e2d86e143c453fe2c445c2718149a6393d5ba462bbceb2642a0f`
- failed local candidate: `ce65175a4aed6c12920792dd283a6a4480272c86`
- independent adversarial audit SHA-256: `48f3815f314ddb88c41aaf0300972bcc55766eef68a5bec0c61241798c92203e`
- audit: `docs/requirements/evidence/v0.7.0-k5-pre-native-audit-ce65175-fail-48f3815f314ddb88c41aaf0300972bcc55766eef68a5bec0c61241798c92203e.md`
- executable callable-reference consumer probe SHA-256: `e22627fc83fe9d37b9d0d3e5bc68b9ec8799993dbab09d193304be9dbbb6020a`
- probe: `docs/requirements/evidence/probe-k5-move-callref-consumer-e22627fc83fe9d37b9d0d3e5bc68b9ec8799993dbab09d193304be9dbbb6020a.py`
- exact 24-case GREEN SHA-256: `284ee50479b6f95c00958c2f3cbd190e84be33b868e5b42c1938adcdedd80bad`
- exact 24-case GREEN: `docs/requirements/evidence/kotlin-jvm-move-callable-consumer-24-case-green-284ee50479b6f95c00958c2f3cbd190e84be33b868e5b42c1938adcdedd80bad.log`

## Approved non-weakening correction

The sealed requirement already excludes callable references and requires every
consumer to be proven. The prior planner refused callable references in every
declaration carried by the moved file, but the K2 usage projection intentionally
omitted callable-reference consumers. A consumer import could therefore remain
unchanged and compile against a different Java/static callable after the move.

For the bounded top-level-function move, any compiler-PSI-proven callable
reference in another Kotlin declaration now refuses before planning with
`kotlin.moveFunctionCallableReferenceUnsupported`. This conservative whole-project
check does not claim target resolution for callable references; it prevents the
omitted consumer form from acquiring mutation authority. The exact JDK-shadow
counterexample is added to the native planner oracle, increasing the move gate
from 23 to 24 cases.

No callable-reference consumer, new import shape, Java caller, declaration split
or broader move form is authorized. A replacement four-host run and fresh
independent post-native review remain mandatory.
