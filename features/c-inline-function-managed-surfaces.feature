# language: en
@functional-requirement @partial
Feature: Public compiler-bound C function-inline lifecycle
  Authority: docs/arc42/appendix-requirements.adoc (C12/C16).
  Daemon and MCP retain the native preview authority through apply and rollback,
  and keep refused or stale compilation inputs outside the transaction journal.

  @REQ-C-INLINE-FUNCTION-SURFACE-001
  @REQ-C-INLINE-FUNCTION-SURFACE-001#c-function-inline-preserves-managed-transport-authority
  Scenario Outline: C function inline preserves managed transport authority
    Given the C function-inline surface "<surface>" captures "<standard>" inputs with "<condition>"
    When that surface requests the managed C function-inline lifecycle
    Then the C function-inline surface reports "<outcome>"
    And the C function-inline surface restores captured inputs and releases compiler material

    Examples:
      | surface | standard | condition          | outcome                 |
      | daemon  | c11      | pure local reads   | applied and rolled back |
      | MCP     | c17      | pure local reads   | applied and rolled back |
      | daemon  | c17      | argument effect    | effects refused         |
      | MCP     | c11      | argument effect    | effects refused         |
      | daemon  | c17      | database changed   | changed input refused   |
      | MCP     | c17      | database changed   | changed input refused   |

  @REQ-C-INLINE-FUNCTION-SURFACE-001
  @REQ-C-INLINE-FUNCTION-SURFACE-001#c-function-inline-preserves-the-cli-lifecycle
  Scenario Outline: C function inline preserves the CLI lifecycle
    Given the C function-inline surface "CLI" captures "<standard>" inputs with "<condition>"
    When that surface requests the managed C function-inline lifecycle
    Then the C function-inline surface reports "<outcome>"
    And the C function-inline surface restores captured inputs and releases compiler material

    Examples:
      | standard | condition        | outcome                 |
      | c11      | pure local reads | applied and rolled back |
      | c17      | pure local reads | applied and rolled back |
      | c11      | argument effect  | effects refused         |
      | c17      | argument effect  | effects refused         |
