# language: en
@functional-requirement
Feature: Bounded Java push-down member
  Requirement: REQ-JAVA-HIERARCHY-PUSH-DOWN-BOUNDED-001.
  ARC42 owns integrated status. This first managed cell moves one exact JDT-bound protected final int literal method from a package-private abstract parent to its only final direct child in the same available source set. Other shapes remain refused; reflection and external consumers are not claimed safe.

  @REQ-JAVA-HIERARCHY-PUSH-DOWN-BOUNDED-001#move-a-bound-final-method-to-its-only-direct-child-and-rollback
  Scenario: Move a bound final method to its only direct child and rollback
    Given an available Java 8 Maven module contains one final int literal method on an abstract parent with one final child
    When I request pushDownMember for fixture.Parent#value into fixture.Child from the saved method selection
    Then RefactorKit advertises a compiler-backed managed pushDownMember preview that is read-only and hierarchy-proven
    And applying and rolling back pushDownMember restores every original source byte and path
