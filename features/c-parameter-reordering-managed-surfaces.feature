# language: en
# Authority: bounded C10 daemon/MCP contracts; ARC42 owns integrated status.
@functional-requirement @partial
Feature: Use a compiler-bound C parameter reorder through local protocols
  A client selects the C source, function and complete original-index mapping.
  Both protocols preserve the same preview authority through explicit apply and
  rollback. Invalid mappings and changed captured inputs cannot become writes.

  @REQ-C-PARAMETER-REORDER-SURFACE-001 @c-parameter-reordering-managed-surfaces
  Scenario Outline: C parameter reorder protocols retain preview authority through apply refusal and rollback
    Given the C parameter reorder protocol "<surface>" has condition "<condition>"
    When that protocol requests the parameter reorder lifecycle
    Then the parameter reorder protocol reports "<outcome>"
    And the parameter reorder protocol preserves configuration and disposes private compiler material

    Examples:
      | surface | condition                | outcome                 |
      | daemon  | complete family          | applied and rolled back |
      | MCP     | complete family          | applied and rolled back |
      | daemon  | duplicate original index | mapping refused         |
      | MCP     | duplicate original index | mapping refused         |
      | daemon  | database changed         | changed input refused   |
      | MCP     | database changed         | changed input refused   |
