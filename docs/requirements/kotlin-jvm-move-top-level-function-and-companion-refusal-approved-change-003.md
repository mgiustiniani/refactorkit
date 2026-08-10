# Kotlin/JVM move-next requirement change 003

Status: incorporated before the replacement candidate commit and native qualification.

## Failed candidate evidence

- unchanged sealed requirement SHA-256: `52dd7de86022e2d86e143c453fe2c445c2718149a6393d5ba462bbceb2642a0f`
- failed candidate: `ccec93b71aa1c60eca7892248acd47bbe888b47f`
- dedicated native run: `31410944555`
- independent failed review SHA-256: `c0819dac0b763ee9674ce2239ece662abdcf34581ab796fd21c1a138d3483bb4`
- independent failed review: `docs/requirements/evidence/v0.7.0-k5-post-native-review-run-31410944555-fail-c0819dac0b763ee9674ce2239ece662abdcf34581ab796fd21c1a138d3483bb4.md`
- tests-only RED SHA-256: `3afd3f824129abc0491b1643a2993c2630ba3fe63df8f83a65d90d916a6ee4f5`
- tests-only RED: `docs/requirements/evidence/kotlin-jvm-move-implicit-iterator-tests-only-red-3afd3f824129abc0491b1643a2993c2630ba3fe63df8f83a65d90d916a6ee4f5.log`
- exact 23-case GREEN SHA-256: `e6c221977cd58070761e8a704d732804ea1a03859fb36aaea3a6eadead12eb39`
- exact 23-case GREEN: `docs/requirements/evidence/kotlin-jvm-move-implicit-conventions-green-e6c221977cd58070761e8a704d732804ea1a03859fb36aaea3a6eadead12eb39.log`

## Approved non-weakening correction

The independent reviewer demonstrated that K2 FIR represents compiler-desugared
convention calls, such as `for (value in box)` resolving `box.iterator()`, without
a literal `iterator` source token. The previous usage projection recorded only
resolved callable references whose PSI token text matched the callable name.
A move could therefore compile cleanly while rebinding an implicit convention
call from a source-package extension to a target-package extension.

The sealed requirement already requires exact retention of every outbound
callable binding, so its content and checksum do not change. The approved
correction is limited to:

1. project K2 FIR resolved function references/calls that are not already
   represented by an explicit same-name PSI token, including non-name PSI used
   for compiler-desugared convention calls;
2. bind a bounded stable PSI range to the exact source or external JVM callable
   identity and compare it in the existing pre/post outbound multiset;
3. fail closed on missing target identity evidence;
4. add the exact implicit-iterator rebound as a regression; and
5. expand the native planner oracle from 19 to 20 cases (23 total across the
   three focused suites).

The regression directly proves location-bound identities for `iterator`,
`component1`, `contains`, `get`, and `invoke`. This correction does not authorize
loops, operators, destructuring, delegated operations or any other new mutation
form as a broad capability. It merely
makes their compiler-resolved callable identity participate in the already
required equivalence proof. It adds no consumer form, Java caller, declaration
split, textual fallback, or promotion authorization. The four receipts from run
`31410944555` are historical failed-candidate evidence only and cannot be reused.
