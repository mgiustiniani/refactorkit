# language: en
# Authority: bounded C11/C16 library and local protocol lifecycle; ARC42 owns integrated status.
@functional-requirement @partial
Feature: Extract a C statement helper through library daemon and MCP
  A caller selects source-owned integer declarations by UTF-8 byte extent and
  chooses a private helper name. Preview is read-only. Explicit apply retains
  native authority and rollback restores exact source. Missing selection,
  effects and changed configuration refuse without a transaction.

  @REQ-C-STATEMENT-SURFACE-001 @c-statement-helper-managed-surfaces
  Scenario Outline: C statement helper surfaces retain explicit selection and native authority through the managed lifecycle
    Given the C statement helper surface "<surface>" has condition "<condition>"
    When that surface requests the selected statement helper lifecycle
    Then the statement helper surface reports "<outcome>"
    And the statement helper surface preserves configuration and releases compiler material

    Examples:
      | surface | condition                 | outcome                  |
      | library | integer inputs            | applied and rolled back  |
      | daemon  | zero inputs Unicode CRLF  | applied and rolled back  |
      | MCP     | integer inputs            | applied and rolled back  |
      | library | selection omitted         | missing argument refused |
      | daemon  | database changed          | changed input refused    |
      | MCP     | initializer changes state | effects refused          |
