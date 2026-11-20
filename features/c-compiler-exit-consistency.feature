# language: en
@functional-requirement @partial
Feature: Managed C diagnostics never treat an unexplained compiler failure as clean
  A syntax-check process with a nonzero exit and no parsed error diagnostic is
  explicitly unavailable, even when warnings and summaries were captured.
  Only a summary saying errors occurred is not an attributable error diagnostic.
  Zero-exit warnings stay available; real nonzero compiler error diagnostics are
  retained as available errors and still refuse managed apply.
  The preview-bound all-TU CMake gate refuses unavailable baseline/staged evidence
  before authority or approval, and unavailable evidence after preview before WAL.
  Caller-disabled diagnostics cannot bypass that retained gate.
  An isolated test-only hashed wrapper forwards syntax checks to real Clang
  22.1.8 and then overrides selected successful exits. This is fault injection,
  not a reproduced native compiler crash, wrapper/discovery qualification or
  independent release acceptance. Compiler/project/database bytes remain stable.
  Captured compilation commands and CMake code are never executed.
  Authority: docs/releases/v0.8.0-plan.md (C03/C05/C08); integrated status:
  docs/arc42/appendix-requirements.adoc. Complete target/header/consumer and
  preprocessing equivalence, ABI, native LSP and packaged acceptance stay pending.

  @REQ-C-COMPILER-EXIT-CONSISTENCY-001
  Scenario Outline: A CMake compiler process in <state> has <disposition> authority
    Given an isolated CMake compiler exit fixture in state "<state>"
    When that compiler exit fixture previews its main translation unit move
    Then its compiler evidence and managed preview disposition is "<disposition>"
    And its retained compiler exit gate preserves byte-exact rollback or no WAL

    Examples:
      | state                   | disposition               |
      | successful-warnings     | applicable                |
      | baseline-warning-exit   | unavailable-baseline      |
      | staged-warning-exit     | unavailable-staged        |
      | late-warning-exit       | unavailable-apply         |
      | unpaired-error-summary  | unavailable-baseline      |
      | genuine-compiler-error  | reported-error-baseline   |
