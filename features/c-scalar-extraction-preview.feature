# language: en
# Authority: bounded C11 native scalar preview; ARC42 owns integrated acceptance.
@functional-requirement @partial
Feature: Preview a scalar temporary with native integer type and placement
  A caller selects an exact expression by UTF-8 bytes. The compiler resolves its
  owning function and integer type independently of its double target. The engine
  copies that expression once into a const integer before its original statement.
  Exact candidate facts and runtime outcomes retain the selected computation.

  @REQ-C-SCALAR-PREVIEW-001 @c-scalar-extraction-preview
  Scenario Outline: C scalar preview preserves native type placement and runtime outcomes or refuses unavailable authority
    Given a captured C scalar extraction request has condition "<condition>"
    When the engine previews that selected scalar temporary
    Then the scalar extraction preview reports "<outcome>"
    And the scalar extraction preview preserves saved inputs and releases compiler material

    Examples:
      | condition                    | outcome                |
      | public C17 integer division  | exact scalar preview   |
      | C11 subtree Unicode CRLF     | exact scalar preview   |
      | inline void const parameters | exact scalar preview   |
      | temporary captures global    | name collision refused |
      | initializer increments       | effects refused        |
      | saved database changed       | changed input refused  |
