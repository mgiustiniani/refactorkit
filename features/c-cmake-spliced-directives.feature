@functional-requirement @c @cmake @REQ-C-CMAKE-SPLICED-DIRECTIVE-001 @partial
Feature: Managed CMake moves account for prepared moved-source directive input
  Requirement authority: docs/arc42/appendix-requirements.adoc and the C03/C05/C08
  boundaries in docs/releases/v0.8.0-plan.md. Local bounded containment only;
  no evaluated CMake, header admission or complete directive/preprocessing model.

  Newline/splice preparation must feed the existing moved-source include/directive
  guards as well as lexical guards. Raw source bytes and conservative raw guards
  remain unchanged. Reconstructed quoted workspace and macro includes, or line
  directives, cannot acquire managed move authority from clean syntax alone.
  Spliced angled system includes and ordinary trivia are not blanket-refused.
  Unsupported capture remains an inspectable structural preview without lease or
  evaluated diagnostics; apply refuses before WAL even with caller gates disabled.
  The ordinary control binds policy/input digest and supports exact rollback.

  @REQ-C-CMAKE-SPLICED-DIRECTIVE-001#a-cmake-project-with-state-spliced-directives-has-disposition-authority
  Scenario Outline: A CMake project with <state> spliced directives has <disposition> authority
    Given an isolated CMake spliced-directive fixture in state "<state>"
    When that spliced-directive fixture previews its main translation unit move
    Then its managed spliced-directive disposition is "<disposition>"
    And its retained spliced-directive policy permits exact rollback or no WAL

    Examples:
      | state                             | disposition         |
      | ordinary-spliced-directive-trivia  | applicable          |
      | lf-quoted-directive               | unavailable-context |
      | crlf-quoted-directive             | unavailable-context |
      | cr-quoted-directive               | unavailable-context |
      | lf-macro-directive                | unavailable-context |
      | spliced-line-directive            | unavailable-context |
