# language: en
@functional-requirement @partial
Feature: Managed CMake moves refuse unmodeled direct resource-availability probes
  Configured Clang can accept __has_embed under admitted C11/C17 contexts and
  select different compiler-valid branches after a captured source resource moves.
  Existing header/paste/trigraph guards and clean syntax are not consumer closure.
  After clean staged compiler/source/define/paste/trigraph checks, refuse exact
  __has_embed identifier tokens in every captured source and sanitized -D payload.
  Splices reuse the existing token input; separated defines reuse the sanitizer.
  Ordinary lookalike identifiers and probe-looking comment/string trivia remain
  admitted. Unused moved-TU operator definitions are not inferred harmless.
  No resource resolver, macro interpreter, second lexer or command grammar is added.
  Report source or define reason and responsible rebased TU, without applicable
  edits/affected files/approval/lease/verified claim or WAL even if caller gates
  are disabled. Earlier compiler/consumer precedence and receipts remain intact.
  Separate embed policy and captured-input digest bind the lease and retained
  apply; absent/downgraded policy or changed digest requires fresh preview before WAL.
  The ordinary control applies with explicit approval and rolls back exact bytes.
  Original core/source/define/paste/trigraph identities/digests remain unchanged.
  Captured database commands and project CMake code are never executed.
  This is direct source/define resource-token containment, not C23 support, #embed
  data ingestion, macro/translation-phase closure, complete resource/header/include/
  consumer graphs, source/runtime equivalence or independent release qualification.
  Authority: docs/releases/v0.8.0-plan.md (C03/C05/C08/C15); integrated narrative:
  docs/arc42/appendix-requirements.adoc. Requirement remains partial.

  @REQ-C-CMAKE-EMBED-PROBE-001
  Scenario Outline: A CMake project with <state> resource probes has <disposition> authority
    Given an isolated CMake resource-probe fixture in state "<state>"
    When that resource-probe fixture previews its main translation unit move
    Then its direct resource-probe disposition is "<disposition>"
    And its retained resource-probe policy permits exact rollback or no WAL

    Examples:
      | state                         | disposition    |
      | ordinary-resource-trivia      | applicable     |
      | direct-c17-query              | refused-staged |
      | direct-c11-query              | refused-staged |
      | spliced-resource-query        | refused-staged |
      | define-alias-query            | refused-staged |
      | unused-moved-resource-define  | refused-staged |
