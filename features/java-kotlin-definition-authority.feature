@functional-requirement @partial
Feature: Java definition reports Kotlin source authority

  @REQ-JAVA-KOTLIN-DEFINITION-001#java-definition-requires-qualified-kotlin-source-evidence
  Scenario Outline: Java definition requires qualified Kotlin source evidence
    Given a Java definition fixture with compiled Java and Kotlin types
    When the caller requests the definition of "<token>" in the saved Java source
    Then the definition reports "<outcome>" and preserves the workspace sources

    Examples:
      | token      | outcome                          |
      | Greeter    | Kotlin source authority required |
      | message    | Kotlin source authority required |
      | value      | Kotlin source authority required |
      | LocalJava  | exact saved Java declaration     |
      | BinaryJava | no workspace Java declaration    |
      | Missing    | no workspace Java declaration    |
