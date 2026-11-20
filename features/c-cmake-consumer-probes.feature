# language: en
@functional-requirement @partial
Feature: Managed CMake moves refuse unmodeled direct availability-probe consumers
  A stationary TU can choose different compiler-valid branches when the moved
  source disappears from header lookup. An absolute workspace-path probe can
  also observe physical files outside a staged overlay. All-TU syntax success
  is not consumer independence or complete preprocessing evidence.
  After baseline and clean staged compiler checks, managed authority refuses
  direct __has_include or __has_include_next identifier tokens in any captured
  TU, including macro-header aliases and phase-two spliced identifiers. Reuse
  the existing tokenizer; comment and string/character trivia is not a probe.
  Do not guess inactive branches, unrelated roots or absolute-path stability.
  Compiler errors keep their existing precedence and diagnostics; historical
  compiler-error receipts are not rewritten by this clean-fallback containment.
  The responsible TU has explicit unavailable-consumer diagnostics, no preview
  lease/edits/approval or verified claim and no WAL despite disabled caller gates.
  Eligible previews bind a versioned direct-token consumer policy and retained
  apply rejects missing/downgraded policy before WAL, then still permits genuine
  approved apply and exact rollback for the no-probe control.
  This is bounded direct-token containment, not token-pasted/generated/external
  probe expansion, a full header/consumer/preprocessor graph or equivalence proof.
  Captured compilation commands and CMake project code are never executed.
  Authority: docs/releases/v0.8.0-plan.md (C03/C05/C08/C15); integrated status:
  docs/arc42/appendix-requirements.adoc. Requirements/earlier receipts stay partial.

  @REQ-C-CMAKE-CONSUMER-PROBE-001
  Scenario Outline: A CMake project with <state> consumer probes has <disposition> authority
    Given an isolated CMake consumer-probe fixture in state "<state>"
    When that consumer-probe fixture previews its main translation unit move
    Then its direct-probe consumer disposition is "<disposition>"
    And its retained direct-probe policy permits exact rollback or no WAL

    Examples:
      | state                   | disposition    |
      | system-include-trivia   | applicable     |
      | relative-query          | refused-staged |
      | macro-header-query      | refused-staged |
      | absolute-query          | refused-staged |
      | include-next-query      | refused-staged |
      | spliced-query           | refused-staged |
