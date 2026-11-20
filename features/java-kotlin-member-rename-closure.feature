@functional-requirement @partial
Feature: Java member renames preserve Kotlin consumers

  @REQ-JAVA-KOTLIN-MEMBER-RENAME-001#only-closed-java-member-renames-can-be-previewed-in-mixed-workspaces
  Scenario Outline: Only closed Java member renames can be previewed in mixed workspaces
    Given a Java member rename fixture has "<kind>" consumer scope
    When the caller requests the exact Java member preview
    Then the member rename returns "<outcome>" with preserved saved files

    Examples:
      | kind                       | outcome                 |
      | package-private with Kotlin | Kotlin closure refused |
      | public with Kotlin         | Kotlin closure refused  |
      | unsigned field with Kotlin | Kotlin closure refused  |
      | private with Kotlin        | exact Java preview      |
      | Java only                  | exact Java preview      |
