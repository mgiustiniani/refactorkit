# language: en
@functional-requirement @partial
Feature: Managed CMake moves refuse unmodeled raw C11 and C17 trigraph inputs
  ISO C11/C17 trigraph replacement precedes line splicing and comment/string lexing.
  Raw ??=??= can supply paste operators; a ??/ newline can reconstruct a header
  availability identifier absent from current source and compiler-define tokens.
  Compiler-valid changed branches are not consumer independence. Comments/strings
  cannot be excluded by a later lexer as evidence that phase-one input is irrelevant.
  After clean staged compiler/source/define/paste checks, refuse every recognized
  raw trigraph triplet in every captured source, including comments/strings and
  the moved TU. Recognized tails are = / ' ( ) ! < > - after adjacent question marks.
  Ordinary unrecognized question-looking trivia remains admitted. This is raw
  triplet containment, not transformation, macro activation or a second lexer.
  Existing compiler/source/define/paste refusal precedence and receipts stay intact.
  Report responsible rebased TU, with no applicable edits/affected files/approval,
  preview lease/verified claim or WAL even when caller diagnostics are disabled.
  A separate trigraph policy and captured-input digest bind lease/retained apply;
  absent/downgraded policy or changed digest requires fresh preview before WAL.
  The ordinary control applies with explicit approval and rolls back exact bytes.
  The original core hash and earlier source/define/paste policies remain unchanged.
  CMake project code and captured compilation commands never execute.
  This does not establish a full translation-phase/expansion/header/consumer model,
  external/generated preprocessing, runtime/source equivalence or per-trigraph
  independent acceptance. Requirements remain partial, no catalogue/release promotion.
  Authority: docs/releases/v0.8.0-plan.md (C03/C05/C08/C15); integrated narrative:
  docs/arc42/appendix-requirements.adoc.

  @REQ-C-CMAKE-TRIGRAPH-001
  Scenario Outline: A CMake project with <state> trigraph inputs has <disposition> authority
    Given an isolated CMake trigraph fixture in state "<state>"
    When that trigraph fixture previews its main translation unit move
    Then its raw trigraph disposition is "<disposition>"
    And its retained trigraph policy permits exact rollback or no WAL

    Examples:
      | state                      | disposition    |
      | ordinary-question-trivia   | applicable     |
      | trigraph-paste-query       | refused-staged |
      | trigraph-splice-query      | refused-staged |
      | comment-splice-trigraph     | refused-staged |
      | string-trigraph            | refused-staged |
      | moved-brace-trigraph       | refused-staged |
