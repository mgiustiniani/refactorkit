# language: en
@functional-requirement @partial @REQ-JAVA-PROJECT-CONTEXT-001
Feature: Create a Maven module through the RefactorKit project transformation route
  Add Module remains a project transformation rather than a Java semantic editor capability.
  Preview must use the selected parent's effective declared coordinates and constrain every generated path.

  @REQ-JAVA-PROJECT-CONTEXT-001#maven-add-module-previews-builds-offline-and-rolls-back
  Scenario: Maven Add Module previews, builds offline, and rolls back
    Given a Maven Add Module fixture with declared parent coordinates and an occupied child
    When daemon Add Module preview is applied for module "reports"
    Then the Add Module preview uses declared parent coordinates and only contained generated paths
    And the applied reactor builds offline in a disposable copy and normal rollback restores exact inputs

  @REQ-JAVA-PROJECT-CONTEXT-001#maven-add-module-remains-available-with-partial-java-context
  Scenario: Maven Add Module remains available with partial Java context
    Given a Maven Add Module fixture with declared parent coordinates and an occupied child
    When daemon Add Module preview is applied for module "reports" with the partial Java context
    Then the Add Module preview uses declared parent coordinates and only contained generated paths
    And the applied reactor builds offline in a disposable copy and normal rollback restores exact inputs
