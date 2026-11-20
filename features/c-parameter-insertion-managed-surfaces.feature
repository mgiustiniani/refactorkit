# language: en
# Authority: bounded C10/C16 library and local protocol contract; ARC42 owns integrated status.
@functional-requirement @partial
Feature: Supply an explicit expression when inserting a C parameter
  A caller selects a source-owned static integer function and an insertion position.
  The engine binds the caller's explicit expression to every captured direct call.
  Preview remains read-only. Approved apply retains native authority and rollback
  restores exact bytes. Missing expressions, effects and changed inputs refuse.

  @REQ-C-PARAMETER-INSERTION-SURFACE-001 @c-parameter-insertion-managed-surfaces
  Scenario Outline: C parameter insertion surfaces retain explicit caller expressions through preview apply and rollback
    Given the C parameter insertion surface "<surface>" has condition "<condition>"
    When that surface requests the explicit parameter insertion lifecycle
    Then the parameter insertion surface reports "<outcome>"
    And the parameter insertion surface preserves configuration and releases compiler material

    Examples:
      | surface | condition                  | outcome                 |
      | library | complete integer family    | applied and rolled back |
      | daemon  | explicit void family       | applied and rolled back |
      | MCP     | complete integer family    | applied and rolled back |
      | library | caller expression omitted  | missing argument refused |
      | daemon  | database changed           | changed input refused   |
      | MCP     | new argument modifies state | effects refused         |
