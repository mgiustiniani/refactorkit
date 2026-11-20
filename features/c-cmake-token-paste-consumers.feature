# language: en
@functional-requirement @partial
Feature: Managed CMake moves refuse unmodeled source token-paste consumers
  A source macro can construct a header-availability operator by pasting
  __has_ and include, without an exact operator identifier in source or -D flags.
  It may select different compiler-valid branches when another TU is moved.
  Clean syntax and earlier direct-source/define guards are not consumer closure.
  After clean staged compiler/source/define checks, refuse ## and digraph %:%:
  punctuation token sequences in every captured TU, including spliced operators,
  chained macro definitions and unused definitions on the moved TU. Reuse the
  existing tokenizer and phase-two splice preparation, not a macro interpreter.
  Ordinary no-paste macros and operator-looking comment/string trivia stay
  admitted; do not infer activation or irrelevance for actual paste operators.
  Existing compiler/source/define refusal precedence and receipts remain intact.
  The responsible rebased TU is reported, with no applicable edits/approval,
  preview lease/verified claim or WAL even when caller diagnostics are disabled.
  A separate versioned paste policy and captured-input digest bind the lease and
  retained apply; absent/downgraded policy or a changed digest refuses before WAL.
  The ordinary control applies under explicit approval and rolls back exact bytes.
  The original core hash and source/define policy identities remain unchanged.
  CMake project code and captured compilation commands are never executed.
  This is bounded source-operator containment, not full expansion, phase-one
  trigraph processing, generated/external preprocessing, complete header/consumer
  or source/runtime equivalence evidence. Requirements remain partial.
  Authority: docs/releases/v0.8.0-plan.md (C03/C05/C08/C15); integrated status:
  docs/arc42/appendix-requirements.adoc. No catalogue/release promotion.

  @REQ-C-CMAKE-TOKEN-PASTE-001
  Scenario Outline: A CMake project with <state> token pasting has <disposition> authority
    Given an isolated CMake token-paste fixture in state "<state>"
    When that token-paste fixture previews its main translation unit move
    Then its source token-paste disposition is "<disposition>"
    And its retained token-paste policy permits exact rollback or no WAL

    Examples:
      | state                | disposition    |
      | no-paste-trivia       | applicable     |
      | classic-paste        | refused-staged |
      | spliced-paste        | refused-staged |
      | digraph-paste        | refused-staged |
      | chained-paste        | refused-staged |
      | unused-moved-paste   | refused-staged |
