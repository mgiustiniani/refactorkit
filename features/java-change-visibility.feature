@functional-requirement
Feature: Bounded Java change visibility
  Requirement: REQ-JAVA-CHANGE-VISIBILITY-001, the IDE backlog for bounded visibility repair.
  ARC42 owns integrated status and release qualification: docs/arc42/appendix-requirements.adoc#java-change-visibility-bounded-operation.
  The first cell promotes one exact JDT-bound final private method to public; ambiguous override behavior and unsupported declarations refuse.

  @implemented-and-validated @REQ-JAVA-CHANGE-VISIBILITY-001#promote-a-final-private-method-to-public-and-rollback
  Scenario: Promote a final private method to public and rollback
    Given an open Maven Java fixture with a JDT-bound service parameter and explicit SDK context
    When I preview changeVisibility from private to public for the final helper method
    Then changeVisibility is advertised as stable, widens only the selected method, and refuses an unsafe non-final method
    And applying and rolling back changeVisibility restores exact source and method binding

  @implemented-and-validated @REQ-JAVA-CHANGE-VISIBILITY-001#promote-a-private-non-final-method-on-a-final-class-and-rollback
  Scenario: Promote a private non-final method on a final class and rollback
    Given a Maven Java fixture with a final class containing a private non-final method and an explicit SDK context
    When I preview changeVisibility for the private non-final method on the final class
    Then changeVisibility accepts the non-final method because its declaring class is final and edits only that method
    And applying and rolling back the final-owner change restores exact source and method binding

  @partial @REQ-JAVA-CHANGE-VISIBILITY-001#lsp-previews-final-private-helper-promotion-from-dirty-buffer
  Scenario: LSP previews final private helper promotion from dirty buffer
    Given a Java Maven LSP fixture with a dirty buffer and a private final helper method
    When the LSP previews changeVisibility to public at the helper declaration
    Then the client receives a versioned client-managed visibility edit without workspace writes
