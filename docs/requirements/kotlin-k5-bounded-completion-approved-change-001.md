# Kotlin K5 bounded completion approved change 001

Status: incorporated before replacement native qualification.

## Superseded candidate evidence

- superseded aggregate requirement SHA-256: `e09f270c9e4daffd578b04e7444cad6ec9658af722734867a2ad01f2f6b44f42`
- failed candidate: `285da48f368a3f220bfe4c8e1e15af2487ed4982`
- Actions run: `31421721819` (all four dedicated move and completion jobs passed, but promotion was withheld)
- independent adversarial audit SHA-256: `f03763afc912473399ac0872bb1268bfe618bf92bfd7100391821d8778607ce2`
- audit: `docs/requirements/evidence/v0.7.0-k5-completion-pre-native-audit-285da48-fail-f03763afc912473399ac0872bb1268bfe618bf92bfd7100391821d8778607ce2.md`
- executable audit probes SHA-256: `ddeb32e82c77471e3585f227efb109dd81e2abdbdb61dc1e1247ad7056bb9be1`
- probes: `docs/requirements/evidence/v0.7.0-k5-completion-pre-native-audit-probes-ddeb32e82c77471e3585f227efb109dd81e2abdbdb61dc1e1247ad7056bb9be1.py`
- tests-only RED SHA-256: `b66166ffd37e5027b127bc450abd4c27feefeb3de635073f1a11d9a25b62cf6d`
- tests-only RED: `docs/requirements/evidence/v0.7.0-k5-completion-adversarial-tests-only-red-b66166ffd37e5027b127bc450abd4c27feefeb3de635073f1a11d9a25b62cf6d.log`
- exact 22-case GREEN SHA-256: `5ab69dbb96130a003ac61ba66621cabe6afd7e283bfe33a2380c310ece67958e`
- exact 22-case GREEN: `docs/requirements/evidence/v0.7.0-k5-completion-adversarial-22-case-green-5ab69dbb96130a003ac61ba66621cabe6afd7e283bfe33a2380c310ece67958e.log`

The four native receipts from run `31421721819` are internally useful historical
evidence but cannot authorize promotion because their exact oracle omitted the
counterexamples below. No roadmap row changed.

## Approved non-weakening corrections

The bound leaf requirements already require complete evidence, binding
preservation and generated-source refusal. Their content and hashes remain
unchanged. This aggregate gate is strengthened as follows:

1. a resolved external JVM field that lacks modeled exact field identity makes
   usage evidence incomplete and produces typed `kotlin.usageExternalFieldUnsupported`;
   organize imports cannot remove its used import through a compiling shadow;
2. parameter rename refuses when the proposed identifier already occurs in an
   affected source, preventing a new parameter from capturing or exchanging
   omitted/local/imported bindings;
3. extract requires the inserted call's exact K2 usage to target the newly
   created private helper, not an imported same-name callable; and
4. extract and inline require one authoritative non-generated source root before
   proposing edits.

The exact native source oracle grows from 18 to 22 cases. The packaged gate also
runs and hash-binds the pre-existing Kotlin smoke so operation-less shared
add-parameter compatibility is proven on every host rather than merely claimed.
The replacement aggregate requirement SHA-256 is
`c7563bbfa0737d27d69aa9cda8ae6249f6886b298b81d6a1330e981fd788ed4a`.

These corrections only add refusal/equivalence proof. They do not authorize Java
field mutation, arbitrary capture analysis, general extract/inline, generated
source writes, another signature mode, or promotion without four replacement
receipts and fresh independent post-native review.
