# language: en
# Authority: bounded C11/C16 public scalar lifecycle; ARC42 owns integrated acceptance.
@functional-requirement @partial
Feature: Public compiler-bound C scalar extraction lifecycle
  Library, daemon and MCP keep the native integer type and initializer conversion.
  Existing zero-based UTF-16 range arguments select the native UTF-8 expression.
  The public routes keep the approved preview and refuse unavailable authority.

  @REQ-C-SCALAR-SURFACE-001 @c-scalar-extraction-managed-surfaces
  Scenario Outline: C scalar public routes preserve native preview apply and exact rollback
    Given the C scalar extraction surface "<surface>" has condition "<condition>"
    When that surface requests the selected scalar extraction lifecycle
    Then the scalar extraction surface reports "<outcome>"
    And the scalar extraction surface preserves configuration and releases compiler material

    Examples:
      | surface | condition                 | outcome                 |
      | library | integer division          | applied and rolled back |
      | daemon  | integer Unicode CRLF C11  | applied and rolled back |
      | MCP     | void const parameters     | applied and rolled back |
      | library | selection omitted         | missing range refused   |
      | MCP     | initializer changes state | effects refused         |
      | daemon  | database changed          | changed input refused   |
