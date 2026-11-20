# language: en
@functional-requirement @partial
Feature: Managed CMake consumer guards match configured compiler newline boundaries
  Configured Clang accepts LF, CRLF and lone CR source newline boundaries.
  Ending comments or joining an identifier/operator differently from the consumer
  lexer can conceal a header/resource/paste consumer despite compiler-clean checks.
  Before existing managed consumer splicing/tokenization, canonicalize CRLF and
  lone CR to LF in lexical input only. Never rewrite source bytes or globally
  change CTokenizer or other C parsing/indexing/refactoring paths in this Story.
  Existing header/resource/paste token guards then refuse with their original
  reason/precedence and responsible rebased TU after clean staged compiler checks.
  Ordinary CR-only comment/string trivia and separate null directives stay admitted.
  There is no blanket CR refusal, second lexer or macro/encoding interpreter.
  Separate newline-preparation policy and captured-input digest bind preview and
  retained apply. Missing/downgraded scope or altered digest requires fresh preview
  before WAL even with caller-disabled diagnostics; earlier core/policies/digests
  and historical feature/receipt/error evidence remain intact.
  Refused previews have no applicable edits/affected files/lease/approval/verified
  claim and refused-plan apply cannot write WAL. Ordinary control applies with
  explicit approval and rolls back exact raw source/manifest/database bytes/hash.
  Captured commands and CMake project code never execute; only configured hash-bound
  Clang source-only checks. This is managed lexical preparation, not global C
  tokenizer qualification, arbitrary encodings/translation phases/generated/external
  preprocessing/consumer closure, source/runtime equivalence or release acceptance.
  Raw moved-source path-macro regex handling is a separate unqualified obligation.
  Authority: docs/releases/v0.8.0-plan.md (C03/C05/C08/C15); integrated narrative:
  docs/arc42/appendix-requirements.adoc. Requirement remains partial.

  @REQ-C-CMAKE-NEWLINE-LEX-001
  Scenario Outline: A CMake project with <state> newline inputs has <disposition> authority
    Given an isolated CMake consumer-newline fixture in state "<state>"
    When that consumer-newline fixture previews its main translation unit move
    Then its managed newline-input disposition is "<disposition>"
    And its retained newline-input policy permits raw-byte rollback or no WAL

    Examples:
      | state                   | disposition    |
      | ordinary-cr-trivia      | applicable     |
      | cr-header-comment       | refused-staged |
      | cr-resource-comment     | refused-staged |
      | cr-header-splice        | refused-staged |
      | cr-resource-splice      | refused-staged |
      | cr-paste-splice         | refused-staged |
