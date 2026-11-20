# language: en
# Authority: bounded C10 library transaction; ARC42 owns integrated status.
@functional-requirement @partial
Feature: Apply and roll back a compiler-bound C parameter reorder
  A parameter reorder's immutable preview authority binds its source edits,
  captured configuration, compiler and resolved native runtime identity.
  Library callers cannot replace this authority with disabled diagnostics.
  Approval permits exactly the previewed patch, and rollback restores the
  original source and hash.

  @REQ-C-PARAMETER-REORDER-APPLY-001 @c-parameter-reordering-managed-apply
  Scenario Outline: C parameter reordering applies only an approved unchanged preview and rolls back exactly
    Given a compiler-bound C parameter reorder awaits "<case>"
    When library apply evaluates that parameter reorder authorization
    Then the managed parameter reorder has outcome "<outcome>"
    And managed parameter reordering retains configuration bytes and releases compiler processes

    Examples:
      | case                       | outcome                    |
      | approved retained gate     | applied and rolled back    |
      | approved disabled gate     | applied and rolled back    |
      | approval missing           | approval refused           |
      | database changed           | changed authority refused  |
      | unrelated body edit added  | changed edit refused       |
      | preview lease removed      | missing authority refused  |

  @REQ-C-PARAMETER-REORDER-APPLY-001#native-runtime-identity-mismatch-refuses-before-transaction
  Scenario: Native runtime identity mismatch refuses before transaction
    Given a compiler-bound C parameter reorder awaits "native runtime identity changed"
    When library apply evaluates that parameter reorder authorization
    Then the managed parameter reorder has outcome "native runtime identity refused"
    And managed parameter reordering retains configuration bytes and releases compiler processes
