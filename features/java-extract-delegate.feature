# language: en
# Requirement source: docs/integration-contracts.md, P1 delegate and signature-shape transformations.
# Architecture authority: docs/arc42/appendix-requirements.adoc and the Java managed project-context boundary.
@functional-requirement
Feature: Extract a closed group of private Java members into a delegate
  A saved, compiler-selected private method and its closed group can move into
  a private nested delegate while the public API and runtime results stay exact.
  Unproved dependencies and initialization dependencies refuse before any write.

  @REQ-JAVA-EXTRACT-DELEGATE-001#saved-maven-private-members-are-extracted-into-a-delegate-with-exact-rollback
  Scenario Outline: Saved Maven private members are extracted into a delegate with exact rollback
    Given the Java delegate fixture has "<case>"
    When I request Extract Delegate from the saved Java method cursor
    Then the delegate extraction "<outcome>" with exact workspace evidence

    Examples:
      | case                         | outcome                   |
      | a closed group               | applies and rolls back    |
      | an unselected owner method   | dependencyUnsupported     |
      | an unresolved owner call     | bindingIncomplete         |
      | a computed constant          | initializationUnsupported |
      | an owner initializer use     | initializationUnsupported |
      | an existing delegate name    | nameConflict              |
