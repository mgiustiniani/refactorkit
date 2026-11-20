# language: en
# Authority: bounded C11 helper preview; ARC42 owns integrated acceptance.
@functional-requirement @partial
Feature: Preview a straight-line C statement selection as an integer helper
  The selection retains compiler-bound integer inputs and one ordinary result.
  An explicit private helper receives those inputs and copies the declarations.
  Both models compile in captured context and the native post-model must match
  the exact planned helper and caller. Unknown effects or identities refuse.

  @REQ-C-STATEMENT-PREVIEW-001 @c-statement-helper-preview
  Scenario Outline: C statement helper preview preserves input output and runtime outcomes or refuses unproven facts
    Given a captured C statement helper request has condition "<condition>"
    When the engine previews that explicit statement helper
    Then the statement helper preview reports "<outcome>"
    And the statement helper preview preserves saved inputs and releases compiler material

    Examples:
      | condition                    | outcome                   |
      | C17 integer inputs           | exact helper preview      |
      | C11 zero inputs Unicode CRLF | exact helper preview      |
      | nonfinal live local          | multiple outputs refused  |
      | initializer increments local | effects refused           |
      | helper name captures global  | name collision refused    |
      | saved database changed       | changed input refused     |
