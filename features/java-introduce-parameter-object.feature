# language: en
# Requirement source: docs/integration-contracts.md, P1 "Add requested delegate and signature-shape transformations".
# Architecture authority: docs/arc42/appendix-requirements.adoc and the Java managed project-context boundary.
@functional-requirement
Feature: Group contiguous Java method parameters in a parameter object
  In an SDK-bound Java project, a saved method selection can group two adjacent
  primitive parameters of a private method with one unqualified call in its class.
  The generated nested type also works at Java source level 8.

  @REQ-JAVA-INTRODUCE-PARAMETER-OBJECT-001#saved-java-call-preserves-evaluation-when-two-parameters-are-grouped
  Scenario: Saved Java call preserves evaluation when two parameters are grouped
    Given a saved Java 8 Maven fixture with side effects in each of four call arguments
    When the daemon selects the private Java method at its saved cursor
    Then the parameter object rewrite preserves argument evaluation through managed apply and exact rollback
