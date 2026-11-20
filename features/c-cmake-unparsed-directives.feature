@c @cmake @REQ-C-CMAKE-DIRECTIVE-TOKEN-001 @partial
Feature: Managed CMake moves contain unparsed prepared moved-source directives
  Requirement authority: docs/arc42/appendix-requirements.adoc and C03/C05/C08
  boundaries in docs/releases/v0.8.0-plan.md. Local bounded containment only;
  not a complete directive/translation-phase or evaluated CMake model.

  A prepared first-token # or contiguous %: followed by an exact path-directive
  identifier must not become authority when the existing include parser does not
  account for that include line. Comments, strings, lookalikes, null directives
  and non-directive macro stringization are not directive-entry tokens.
  Existing parsed angled includes and all raw guards remain unchanged.
  Unsupported capture retains inspectable structural preview without lease or
  evaluated diagnostics; apply refuses before WAL with caller gates disabled.
  The ordinary control binds policy/input digest and permits exact rollback.

  Scenario Outline: A CMake project with <state> unparsed directives has <disposition> authority
    Given an isolated CMake directive-token fixture in state "<state>"
    When that directive-token fixture previews its main translation unit move
    Then its managed directive-token disposition is "<disposition>"
    And its retained directive-token policy permits exact rollback or no WAL

    Examples:
      | state                            | disposition         |
      | ordinary-directive-token-trivia   | applicable          |
      | spaced-include                   | unavailable-context |
      | digraph-include                  | unavailable-context |
      | introducer-comment-include       | unavailable-context |
      | operand-comment-include          | unavailable-context |
      | digraph-line-directive           | unavailable-context |
