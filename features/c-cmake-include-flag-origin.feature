# language: en
@functional-requirement @partial
Feature: Managed CMake moves refuse unconsumed literal include-flag origins
  Literal PRIVATE target_include_directories records are compiler-flag origins,
  even when their directories are empty, belong to an unchanged TU owner or
  overlap the moved source parent. The admitted captured-command policy has no
  include flags; a database omitting -I cannot make those origins modeled merely
  because source-only syntax checks pass. Baseline/staged managed authority must
  reject any such captured root. Explicit database -I remains unsupported too;
  matching literal roots do not qualify include/header/consumer resolution.
  Unsupported structural previews stay inspectable without lease or verified
  compiler evidence and refuse before WAL despite caller-disabled diagnostics.
  Eligible no-root contexts bind a versioned no-include flag policy in their
  preview lease; missing/downgraded policy refuses at the retained apply gate.
  The no-root control still applies under explicit approval and rolls back exact
  recorded bytes. The same parser retains all roots, not another CMake grammar.
  This is fail-closed containment, not complete CMake-derived flag agreement,
  target execution, header/consumer/preprocessor equivalence or release acceptance.
  Captured compilation commands and CMake project code are never executed.
  Authority: docs/releases/v0.8.0-plan.md (C03/C05/C08); integrated status:
  docs/arc42/appendix-requirements.adoc. Earlier partial receipts stay unchanged.

  @REQ-C-CMAKE-INCLUDE-FLAG-ORIGIN-001
  Scenario Outline: A CMake project in include-flag <state> has <disposition> authority
    Given an isolated CMake include-flag fixture in state "<state>"
    When that include-flag fixture previews its main translation unit move
    Then its CMake include-flag disposition is "<disposition>"
    And its retained no-include flag policy permits exact rollback or no WAL

    Examples:
      | state                 | disposition         |
      | no-roots              | applicable          |
      | moved-owner-root      | unavailable-origin  |
      | other-owner-root      | unavailable-origin  |
      | source-parent-root    | unavailable-origin  |
      | empty-unused-root     | unavailable-origin  |
      | matching-command-root | unavailable-command |
