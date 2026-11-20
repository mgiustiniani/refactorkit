@functional-requirement @REQ-JAVA-MOVE-CLASS-CROSS-MODULE-001
Feature: Move a Java class into a package in another supported build module
  Authority: this bounded Story; integrated status: docs/arc42/appendix-requirements.adoc.
  Cross-module placement is admitted only when the target module/package maps to one declared source root.

  @REQ-JAVA-MOVE-CLASS-CROSS-MODULE-001#moves-a-class-to-the-unique-module-owning-the-target-package
  Scenario: Moves a class to the unique module owning the target package
    Given a clean two-module Maven reactor whose client module depends on the target package module
    When I preview moving com.acme.legacy.Product into package com.acme.api
    Then the preview moves Product into the target module source root with JDT binding evidence
    And the preview introduces no Java errors and leaves the workspace unchanged

  @REQ-JAVA-MOVE-CLASS-CROSS-MODULE-001#product-fixture-retains-staged-jdt-bindings-for-a-cross-module-move
  Scenario: Product fixture retains staged JDT bindings for a cross-module move
    Given a six-module Maven reactor for the IDE Product migration with generated sources and compiled outputs
    When I preview moving pkg.MigrateToLibrary into fixtures.library with an acquired Java context
    Then the daemon previews the exact Product move with staged JDT binding evidence and clean diagnostics without writing files

  @REQ-JAVA-MOVE-CLASS-CROSS-MODULE-001#moves-a-class-to-an-explicit-module-before-its-target-package-exists
  Scenario: Moves a class to an explicit module before its target package exists
    Given a six-module Maven reactor for the IDE Product migration with generated sources and compiled outputs
    When I preview moving pkg.MigrateToLibrary into new package fixtures.newlibrary in module library with an acquired Java context
    Then the daemon advertises the bounded moveClass target-module selection contract
    And the daemon previews the exact Product move with staged JDT binding evidence and clean diagnostics without writing files
    And managed apply and rollback restore the exact Product workspace images

  @REQ-JAVA-MOVE-CLASS-CROSS-MODULE-001#explains-missing-gradle-outputs-without-relaxing-bindings
  Scenario Outline: Explains missing Gradle outputs without relaxing bindings
    Given a Gradle class move has "<condition>"
    When I preview moving the Gradle class into its library dependency with an acquired Java context
    Then the Gradle move reports "<outcome>" and preserves the saved workspace

    Examples:
      | condition                       | outcome                         |
      | cold annotation processing      | missing Gradle outputs          |
      | materialized annotation outputs | binding-proven preview          |
      | cold ordinary Java              | binding-proven preview          |
      | unrelated binding error         | generic incomplete JDT bindings |
