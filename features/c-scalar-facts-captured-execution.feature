# language: en
# Authority: bounded C11/C05 saved and staged scalar facts; ARC42 owns integrated acceptance.
@functional-requirement @partial
Feature: Capture scalar expression facts with the retained C compiler context
  Exact saved or source-only staged bytes are analyzed by configured Clang.
  Native expression types remain separate from initializer target types.
  Changed configuration, effects and altered candidate boundaries refuse.
  The analysis leaves source and configuration bytes unchanged.

  @REQ-C-SCALAR-EXECUTION-001 @c-scalar-facts-captured-execution
  Scenario Outline: C scalar facts retain native type and captured authority for saved and staged selections
    Given a captured C scalar selection has condition "<condition>"
    When the scalar facts are evaluated in the admitted compiler context
    Then the captured scalar analysis reports "<outcome>"
    And scalar fact evaluation preserves saved bytes and releases compiler material

    Examples:
      | condition                   | outcome          |
      | C17 integer division        | available facts  |
      | C11 Unicode CRLF selection  | available facts  |
      | source-only staged const    | available facts  |
      | saved database changed      | changed input    |
      | initializer increments      | unavailable effects |
      | candidate database changed  | invalid boundary |
