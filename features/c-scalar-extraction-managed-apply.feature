# language: en
# Authority: bounded C11 managed library transaction; ARC42 owns integrated acceptance.
@functional-requirement @partial
Feature: Apply and roll back a compiler-bound C scalar temporary
  The integer expression preview retains exact source and compilation authority.
  Approval and unchanged native identity remain mandatory through baseline,
  stage and committed checks. A disabled caller gate cannot bypass those checks.

  @REQ-C-SCALAR-APPLY-001 @c-scalar-extraction-managed-apply
  Scenario Outline: C scalar extraction applies approved unchanged authority and rolls back exact source
    Given a compiler-bound C scalar temporary awaits "<condition>"
    When library apply evaluates that scalar extraction authority
    Then managed scalar extraction apply has outcome "<outcome>"
    And managed scalar extraction retains configuration and releases compiler material

    Examples:
      | condition               | outcome                   |
      | approved retained gate  | applied and rolled back   |
      | approved disabled gate  | applied and rolled back   |
      | approval missing        | approval refused          |
      | preview lease removed   | missing authority refused |
      | temporary identity changed | changed authority refused |
      | database changed        | changed input refused     |

  @REQ-C-SCALAR-APPLY-001#native-runtime-identity-mismatch-refuses-before-transaction
  Scenario: Native runtime identity mismatch refuses before transaction
    Given a compiler-bound C scalar temporary awaits "native runtime identity changed"
    When library apply evaluates that scalar extraction authority
    Then managed scalar extraction apply has outcome "native runtime identity refused"
    And managed scalar extraction retains configuration and releases compiler material
