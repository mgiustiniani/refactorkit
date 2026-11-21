# language: en
# Requirement authority: the Java Change Signature Add Parameter operation.
@functional-requirement @java
Feature: Preserve the functional signature of a bound private Java method

  @REQ-JAVA-CHANGE-SIGNATURE-ADD-001#saved-this-method-reference-retains-its-functional-signature
  Scenario Outline: Saved this method reference retains its functional signature
    Given a Java Add Parameter fixture uses "<reference>"
    When I preview a parameter addition from the saved method cursor
    Then the bound reference migration "<outcome>" with exact workspace evidence

    Examples:
      | reference                         | outcome                         |
      | an unqualified this receiver      | applies and rolls back          |
      | this without explicit migration   | requires explicit migration     |
      | an expressive receiver            | refuses unsupported references  |
      | mixed receivers and an overload   | refuses unsupported references  |
