# language: en
# Architecture narrative and authority: docs/arc42/08-crosscutting-concepts.adoc, "Java/Maven Diagnostics Authority".
@functional-requirement @partial @REQ-JAVA-MAVEN-OWNERSHIP-001
Feature: Maven ownership moves refuse JPMS analysis without explicit SDK authority
  The first ownership-migration row is fail-closed when semantic diagnostics need a project SDK.
  This Story qualifies typed refusal only; it does not qualify modular ownership moves.

  @REQ-JAVA-MAVEN-OWNERSHIP-001#jpms-module-move-refuses-without-configured-sdk-authority
  Scenario: JPMS module move refuses without configured SDK authority
    Given an offline Maven reactor has a named source module and no configured project SDK
    When java.moveAcrossMavenModules previews moving a source root from that reactor
    Then the adapter returns a REFUSED plan with java.context.sdkRequired and no edit authority
    And all reactor bytes remain unchanged and no transaction is created
