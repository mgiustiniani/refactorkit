# language: en
# Authority: bounded C10/C11 core transaction with public C gate; ARC42 owns integrated acceptance.
@functional-requirement @partial
Feature: Restore native C edits when compilation authority changes after commit
  A compiler-bound preview is approved in its captured configuration.
  If that configuration changes after the source is committed, the managed
  transaction refuses success, restores the source and preserves the new
  configuration. Unchanged controls can apply and roll back normally.

  @REQ-C-NATIVE-POST-COMMIT-001 @c-native-managed-post-commit
  Scenario Outline: Native C transaction restores source after committed authority revocation or completes its unchanged control
    Given an approved native C "<operation>" transaction awaits "<condition>"
    When its committed source is checked against the retained compiler gate
    Then the native C transaction reports "<outcome>" with an exact source journal
    And the native C transaction preserves concurrent configuration and releases compiler material

    Examples:
      | operation         | condition            | outcome                          |
      | reorderParameters | database revoked     | automatic rollback and refusal   |
      | addParameter      | database revoked     | automatic rollback and refusal   |
      | extractStatements | database revoked     | automatic rollback and refusal   |
      | extractExpression | database revoked     | automatic rollback and refusal   |
      | extractStatements | unchanged authority  | applied and manually rolled back |
      | extractExpression | unchanged authority  | applied and manually rolled back |
