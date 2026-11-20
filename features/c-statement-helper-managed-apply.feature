# language: en
# Authority: bounded C11 library managed transaction; ARC42 owns integrated acceptance.
@functional-requirement @partial
Feature: Apply and roll back a compiler-bound C statement helper
  The native helper preview remains bound to immutable source, configuration,
  compiler and resolved native runtime identity.
  Explicit approval and unchanged semantic authority are required through core
  baseline, staged and committed checks. A caller cannot disable that authority.

  @REQ-C-STATEMENT-APPLY-001 @c-statement-helper-managed-apply
  Scenario Outline: C statement helper applies approved unchanged authority and rolls back exact source
    Given a compiler-bound C statement helper awaits "<condition>"
    When library apply evaluates that statement helper authority
    Then managed statement helper apply has outcome "<outcome>"
    And managed statement helper retains configuration and releases compiler material

    Examples:
      | condition               | outcome                   |
      | approved retained gate  | applied and rolled back   |
      | approved disabled gate  | applied and rolled back   |
      | approval missing        | approval refused          |
      | preview lease removed   | missing authority refused |
      | helper identity changed | changed authority refused |
      | database changed        | changed input refused     |

  @REQ-C-STATEMENT-APPLY-001#native-runtime-identity-mismatch-refuses-before-transaction
  Scenario: Native runtime identity mismatch refuses before transaction
    Given a compiler-bound C statement helper awaits "native runtime identity changed"
    When library apply evaluates that statement helper authority
    Then managed statement helper apply has outcome "native runtime identity refused"
    And managed statement helper retains configuration and releases compiler material
