# language: en
# Authority: bounded C10 insertion transaction; ARC42 owns integrated status.
@functional-requirement @partial
Feature: Apply and roll back explicit C parameter insertion
  An immutable insertion preview binds its caller expressions, source edits,
  candidate, compiler and resolved native runtime identity. Explicit approval permits that exact patch.
  A disabled caller gate or changed input cannot replace the retained authority.

  @REQ-C-PARAMETER-INSERTION-APPLY-001 @c-parameter-insertion-managed-apply
  Scenario Outline: C parameter insertion applies approved unchanged authority and rolls back exact bytes
    Given a compiler-bound C parameter insertion awaits "<case>"
    When library apply evaluates that explicit parameter insertion
    Then the managed parameter insertion has outcome "<outcome>"
    And managed parameter insertion retains configuration and releases compiler material

    Examples:
      | case                       | outcome                   |
      | approved retained gate     | applied and rolled back   |
      | approved disabled gate     | applied and rolled back   |
      | approval missing           | approval refused          |
      | preview lease removed      | missing authority refused |
      | caller expression tampered | changed authority refused |
      | database changed           | changed input refused     |

  @REQ-C-PARAMETER-INSERTION-APPLY-001#native-runtime-identity-mismatch-refuses-before-transaction
  Scenario: Native runtime identity mismatch refuses before transaction
    Given a compiler-bound C parameter insertion awaits "native runtime identity changed"
    When library apply evaluates that explicit parameter insertion
    Then the managed parameter insertion has outcome "native runtime identity refused"
    And managed parameter insertion retains configuration and releases compiler material
