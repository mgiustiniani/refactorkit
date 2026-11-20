@functional-requirement @REQ-JAVA-GRADLE-MODULE-CREATE-001
Feature: Create a Java Gradle module through the managed refactoring route
  Gradle module creation must use the synchronized provider context and remain previewable,
  applicable, and exactly rollbackable without executing project build code implicitly.
  Authority: this Story; architecture: docs/arc42/appendix-requirements.adoc.

  @REQ-JAVA-GRADLE-MODULE-CREATE-001#create-a-nested-gradle-module-through-preview-apply-and-exact-rollback
  Scenario Outline: Create a nested Gradle module through preview apply and exact rollback
    Given a synchronized <dsl> Gradle composite workspace with one existing Java module
    When the daemon previews and applies Gradle module path ":services:catalog"
    Then the stable Gradle module capability previews the exact <dsl> settings append and matching build script
    And managed rollback restores every workspace byte and invalidates the synchronized Gradle model

    Examples:
      | dsl    |
      | Kotlin |
      | Groovy |
