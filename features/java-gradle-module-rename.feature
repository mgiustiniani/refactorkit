@functional-requirement @REQ-JAVA-GRADLE-MODULE-RENAME-001
Feature: Rename a Gradle Java project through the managed refactoring route
  A synchronized Gradle project may be renamed only when its settings identity and
  physical project-directory mapping are exact and supported. The operation must
  remain previewable, model-checked, and rollbackable without evaluating new build code.
  Authority: this Story; architecture: docs/arc42/appendix-requirements.adoc.

  @REQ-JAVA-GRADLE-MODULE-RENAME-001#rename-a-gradle-module-path-with-managed-rollback
  Scenario Outline: Rename a Gradle module path with managed rollback
    Given the Gradle rename settings form "<settingsForm>" is selected
    And a synchronized <dsl> Gradle composite workspace with one existing Java module
    When the daemon previews the Gradle module rename for "<settingsForm>" settings
    Then the experimental Gradle module rename returns <expectedStatus> with refusal <expectedRefusalCode> and a reversible settings-only preview
    And the workspace and synchronized Gradle model remain safe after the result

    Examples:
      | dsl    | settingsForm             | expectedStatus | expectedRefusalCode                                                   |
      | Kotlin | literal include          | PREVIEW        | none                                                                   |
      | Groovy | literal include          | PREVIEW        | none                                                                   |
      | Kotlin | explicit projectDir map  | REFUSED        | java.renameGradleModule.projectDirectoryMappingUnsupported             |
      | Kotlin | conditional include      | REFUSED        | java.renameGradleModule.settingsIncludeUnsupported                    |


  @REQ-JAVA-GRADLE-MODULE-RENAME-001#multiproject-literal-includes-retain-managed-rename-and-rollback
  Scenario Outline: Multiproject literal includes retain managed rename and rollback
    Given a synchronized "<dsl>" Gradle multiproject rename fixture uses "<form>" includes
    When the daemon previews a literal Gradle app project rename
    Then the multiproject Gradle rename is "<status>" with "<refusal>"
    And the Gradle rename preserves peer project identity and supports exact rollback

    Examples:
      | dsl    | form               | status  | refusal                                                               |
      | Kotlin | literal list       | PREVIEW | none                                                                  |
      | Groovy | literal list       | PREVIEW | none                                                                  |
      | Kotlin | conditional list   | REFUSED | java.renameGradleModule.settingsIncludeUnsupported                     |
      | Kotlin | literal dependency | PREVIEW | none                                                                  |
      | Groovy | literal dependency | PREVIEW | none                                                                  |
      | Groovy | dynamic dependency | REFUSED | java.renameGradleModule.inboundProjectDependencyUnsupported            |
