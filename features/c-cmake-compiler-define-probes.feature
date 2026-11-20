# language: en
@functional-requirement @partial
Feature: Managed CMake moves refuse captured compiler-define availability probes
  A bounded -D identifier definition can introduce __has_include or
  __has_include_next even when no source contains those operator identifiers.
  Stationary helpers can then select different compiler-valid branches after
  moving another TU. Source-token containment and clean syntax are insufficient.
  Reuse the sanitized flags of every captured TU and the existing token check;
  do not add a command acceptance grammar or infer complete macro expansion.
  After clean staged all-TU checks, exact operator tokens in sanitized defines
  refuse unavailable-consumer authority, including joined/separated defines,
  a chain with an explicit operator definition and unused definitions on the
  moved TU. Ordinary/lookalike identifiers remain admitted. Prior compiler-error
  and direct-source-probe precedence, features and partial receipts stay intact.
  The responsible rebased TU is reported; refusal has no applicable edits,
  lease, approval or verified claim and cannot write WAL with disabled gates.
  A separate versioned define policy and input-bound digest are required in the
  preview lease and retained apply, without reinterpreting the old source policy
  or changing its core evidenceHash format. Absent/downgraded policy or a changed
  digest refuses before WAL; the ordinary control applies and rolls back exact
  bytes. Commands from the database and CMake project code are never executed.
  This is direct-operator-token containment, not token-pasted/generated/external
  expansion, full preprocessing/header/consumer closure or source equivalence.
  Authority: docs/releases/v0.8.0-plan.md (C03/C05/C08/C15); integrated status:
  docs/arc42/appendix-requirements.adoc. No catalogue or release promotion.

  @REQ-C-CMAKE-DEFINE-PROBE-001
  Scenario Outline: A CMake project with <state> compiler defines has <disposition> authority
    Given an isolated CMake compiler-define fixture in state "<state>"
    When that compiler-define fixture previews its main translation unit move
    Then its captured define-probe disposition is "<disposition>"
    And its retained define-probe policy permits exact rollback or no WAL

    Examples:
      | state                 | disposition    |
      | ordinary-defines      | applicable     |
      | joined-probe          | refused-staged |
      | separated-probe       | refused-staged |
      | chained-probe         | refused-staged |
      | next-probe            | refused-staged |
      | unused-moved-probe    | refused-staged |
