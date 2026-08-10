# Kotlin/JVM move-next requirement change 002

Status: incorporated before candidate commit and native qualification.

## Superseded baseline

- requirement SHA-256: `a78db967566b7d7f3920f8653b910fe079d696ec50bcac455757454b8b2e149b`
- independent review receipt: `docs/requirements/evidence/v0.7.0-k5-pre-native-review-v4-fail-e38cd0bbf8f5da8109ed1f4e6d0710059d7c15bfd2468b6b624719ff8279cacf.txt`

## Approved clarification

The fourth independent review demonstrated two cleanly compiling semantic gaps:
a move could create a same-name function family already present in the target
package, and an implicit outbound source binding could silently rebind to a
different target-package declaration.

Requirement item 5 now makes the existing semantic-safety intent explicit:

1. no same-name top-level function may pre-exist in the destination package;
2. every location-bound compiler-proven outbound internal/external type/callable
   binding from the moved file must retain exact identity; and
3. bindings to declarations carried in the same file must resolve to their
   exact computed post-move identities.

The final requirement SHA-256 is
`52dd7de86022e2d86e143c453fe2c445c2718149a6393d5ba462bbceb2642a0f`.

This is a non-weakening fail-closed clarification. It does not add consumer
forms, declaration splitting, Java callers, arbitrary import insertion, or any
promotion authorization.
