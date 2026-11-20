# language: en
@functional-requirement @partial
Feature: C formatting does not organize include order
  Formatting and include organization are different operations. A style that
  requests sorting must not silently reorder macro-sensitive includes during
  formatFile. Full snapshot/style/range authority remains C07 qualification work.
  Authority: docs/releases/v0.8.0-plan.md (C07); integrated status: docs/arc42/appendix-requirements.adoc.

  @REQ-C-FORMAT-PRESERVES-INCLUDES-001
  Scenario: Formatting an include-order-sensitive C file never changes its include sequence
    Given an isolated C formatting file with order-sensitive includes and sorting style
    When the managed C formatting preview requests clang-format
    Then the formatter receives the no-include-sort option and no include edit is proposed
    And the formatted C source bytes and transaction journal remain unchanged
