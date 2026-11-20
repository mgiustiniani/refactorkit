# C clangd native-runtime currency — approved change 001

- Change ID: `REQ-C-CLANGD-RUNTIME-CURRENCY-001-APPROVED-CHANGE-001`
- Parent: C02, bounded native runtime authority at clangd consumers
- Recorded at: `2026-10-22`
- Approval authority: the user's explicit active objective, “riprendi la roadmap e completa la 0.8.0”.
- Scope: one Cucumber scenario outline, six cases, existing shared runner and current process fixtures.

## Approved budget override

The default §18.6 changed-file maximum of eight was increased to twelve,
including this record. Post-implementation execution showed that the shared
runner's explicit tag allowlist excluded the new scenario: Gradle exited
successfully while the feature XML reported zero selected examples. The budget
is therefore amended to exactly thirteen files to add the exact scenario identity
to that existing runner allowlist and make the acceptance executable. This does
not add a runner, a duplicated glue expression, or a new behavior case.

The slice still selects one scenario with six expanded cases, three new step
definitions, one exact reused step, one effective runner, zero ambiguity and
zero duplicated expressions.

The files are required to keep this authority cohesive: the runtime-provider
contract and clangd consumer enforce current captured inputs; three existing test
fixtures adapt simulated/fake sessions while a fourth shared Cucumber fixture
qualifies actual native behavior; the feature binds the single Story; the existing
Cucumber runner must admit its exact scenario tag; the C02 satellite and user-facing
C safety guide state the refusal contract; ARC42 is the integrated authority; and
the progress ledger records remaining C02/C17 gates. This approval record is the
thirteenth file. No dependency or new runner is added.

## Approved boundary

- Require complete current native runtime authority before clangd launch and on
  every document/semantic exchange.
- Close and refuse a session when retained runtime inputs change.
- Preserve fake-process tests only through explicit injected simulation; those
  tests do not qualify native runtime authority.
- Claim only the bounded clangd runtime cell; full C02 and all C01-C17 gates remain.
