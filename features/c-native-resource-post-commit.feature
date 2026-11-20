# language: en
# Authority: bounded native C resource post-commit faults; ARC42 owns integrated acceptance.
@functional-requirement @partial
Feature: Roll back committed C source when retained resources change
  A committed source image cannot report success after resource authority is lost.
  Rollback restores source while preserving the concurrently changed resource.

  @REQ-C-RESOURCE-POST-COMMIT-001 @c-native-resource-post-commit
  Scenario Outline: Native C transactions restore source after resource revocation or complete unchanged controls
    Given an approved native C "<operation>" transaction awaits "<condition>"
    When its committed source is checked against the retained compiler gate
    Then the native C transaction reports "<outcome>" with an exact source journal
    And the native C transaction preserves concurrent configuration and releases compiler material

    Examples:
      | operation         | condition          | outcome                          |
      | reorderParameters | resources revoked  | automatic rollback and refusal   |
      | addParameter      | resources revoked  | automatic rollback and refusal   |
      | extractStatements | resources revoked  | automatic rollback and refusal   |
      | extractExpression | resources revoked  | automatic rollback and refusal   |
      | extractStatements | resources unchanged | applied and manually rolled back |
      | extractExpression | resources unchanged | applied and manually rolled back |
