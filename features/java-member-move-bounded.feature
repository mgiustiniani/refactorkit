# language: en
@functional-requirement
Feature: Bounded Java member move
  Requirement: REQ-JAVA-MOVE-MEMBER-001.
  ARC42 owns integrated status. The first managed cell moves one package-private static method with no parameters and a primitive int literal result between existing same-package top-level classes in one available Java 8 Maven source set. It rewrites only complete compiler-bound direct invocations. Other member kinds, signatures, dependencies, source sets, and consumers remain outside this cell.

  @REQ-JAVA-MOVE-MEMBER-001#move-a-bound-static-method-between-top-level-types-through-sdk-preview-apply-and-rollback
  Scenario: Move a bound static method between top-level types through SDK preview, Apply, and rollback
    Given an available Java 8 Maven module has a package-private static int literal method, a same-package target type, and a direct caller
    When the IDE asks the daemon to move the saved method selection to the exact target type
    Then RefactorKit advertises compiler-backed managed moveMember and previews the exact bound invocation update without new JDT errors
    And the staged project compiles and daemon Apply followed by rollback restores every captured source byte and path
