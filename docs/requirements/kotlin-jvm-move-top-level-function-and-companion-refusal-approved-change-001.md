# Kotlin/JVM move-next requirement change 001

Status: incorporated before candidate commit and native qualification.

## Superseded baseline

- requirement SHA-256: `21e0670cfb407f0a19ca6a98968375b3586ad2643effe99fc184f0c02bc07980`
- independent review receipt: `docs/requirements/evidence/v0.7.0-k5-pre-native-review-fail-f715fc8beeeb0d67fa9888ec7ad1261364969df4899675a6a7154df28de8862b.txt`

## Approved clarification

The review demonstrated that the phrase “compiler-proven public top-level
Kotlin function with exact file-facade owner” had been implemented too
lexically. The normalized requirement now states explicitly that:

1. Kotlin PUBLIC visibility may be explicit or implicit;
2. the compiler-reported default file-facade owner is authoritative independent
   of source-filename casing; and
3. an overload family includes a private same-name sibling.

The final requirement SHA-256 is
`a78db967566b7d7f3920f8653b910fe079d696ec50bcac455757454b8b2e149b`.

This change does not weaken a refusal, add Java function consumers, permit a
previously excluded consumer form, broaden declaration splitting, or authorize
promotion. It resolves demonstrated conformance defects under the user's
existing instruction to complete the named roadmap rows through executable
requirements and independent review.
