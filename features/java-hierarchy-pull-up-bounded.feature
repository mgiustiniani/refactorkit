@functional-requirement
Feature: Bounded Java pull-up member
  Requirement: REQ-JAVA-HIERARCHY-PULL-UP-BOUNDED-001.
  ARC42 owns integrated status and release qualification. This first managed cell moves one exact JDT-bound final `int` method with no parameters and a single numeric-literal return to the direct superclass in the same available source set. Other shapes remain refused; reflective and external consumers are not claimed safe.

  @REQ-JAVA-HIERARCHY-PULL-UP-BOUNDED-001#move-a-bound-final-method-to-its-direct-superclass-and-rollback
  Scenario: Move a bound final method to its direct superclass and rollback
    Given an available Java 8 Maven module contains one final int literal method on a child of a direct superclass
    When I request pullUpMember for fixture.Child#value into fixture.Parent from the saved method selection
    Then RefactorKit advertises a compiler-backed managed pullUpMember preview that is read-only and hierarchy-proven
    And applying and rolling back pullUpMember restores every original source byte and path
