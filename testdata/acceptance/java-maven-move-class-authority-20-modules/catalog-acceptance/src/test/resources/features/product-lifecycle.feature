# language: en
# Inert fixture content only; this is not executable RefactorKit acceptance.
Feature: Product lifecycle lexical fixture

  @FIXTURE-JAVA-MAVEN-MOVE-AUTH-20M-NONJAVA-001
  Scenario: Preserve a legacy product reference in non-Java text
    Given the non-Java lexical candidate is "com.acme.catalog.legacy.Product"
    When a Java semantic move is evaluated
    Then the candidate remains "com.acme.catalog.legacy.Product"
