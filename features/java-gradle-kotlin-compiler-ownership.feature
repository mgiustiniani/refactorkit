@functional-requirement @partial
Feature: Evaluated Kotlin compiler ownership in Gradle

  @REQ-JAVA-GRADLE-KOTLIN-OWNERSHIP-001#evaluated-gradle-identifies-the-kotlin-compiler-source-and-destination
  Scenario Outline: Evaluated Gradle identifies the Kotlin compiler source and destination
    Given an evaluated Gradle compiler fixture is "<project>"
    When the explicit Gradle model synchronizes the compiler fixture
    Then the Gradle source set reports "<outcome>" and keeps its Java source ownership

    Examples:
      | project      | outcome                      |
      | Java Kotlin  | owned Kotlin compiler inputs |
      | Java only    | Java compiler inputs only    |

  @REQ-JAVA-KOTLIN-DEFINITION-002#saved-java-definition-follows-the-exact-compiled-kotlin-declaration
  Scenario Outline: Saved Java definition follows the exact compiled Kotlin declaration
    Given the daemon advertises optional Kotlin definition tooling before opening a project
    And the saved Gradle JVM definition fixture is "<condition>"
    When the daemon resolves the selected Java token with its bound context
    Then the definition returns "<outcome>" and preserves the saved workspace and binaries

    Examples:
      | condition        | outcome                    |
      | Kotlin call      | exact Kotlin method        |
      | Kotlin type      | exact Kotlin object        |
      | stale bytecode   | compiled Kotlin drift      |
      | ambiguous binary | Kotlin binary ambiguity    |
      | missing compiler | Kotlin authority required  |
      | Java control     | exact Java method          |
