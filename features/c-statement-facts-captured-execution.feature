# language: en
# Authority: bounded C11 native fact capture; ARC42 owns integrated status.
@functional-requirement @partial
Feature: Capture source-bound C statements in the admitted compiler context
  A source-only statement selection uses the captured translation unit flags.
  Saved and staged analysis keep saved input authority and return immutable
  binding facts. Changed input, changed candidate configuration or effects
  cannot become complete clean evidence.

  @REQ-C-STATEMENT-EXECUTION-001 @c-statement-facts-captured-execution
  Scenario Outline: C statement facts use captured compiler authority for saved and staged selections
    Given a captured C statement selection has condition "<condition>"
    When the statement facts are evaluated in the admitted compiler context
    Then the captured statement analysis reports "<outcome>"
    And statement fact evaluation preserves saved bytes and releases compiler material

    Examples:
      | condition                    | outcome             |
      | C17 saved selection          | available facts     |
      | C11 Unicode CRLF selection   | available facts     |
      | source-only staged helper    | available facts     |
      | saved database changed       | changed input       |
      | initializer increments local | unavailable effects |
      | candidate database changed   | invalid boundary    |
